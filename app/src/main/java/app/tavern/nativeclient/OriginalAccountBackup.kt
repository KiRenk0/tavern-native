package app.tavern.nativeclient

import java.io.File
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

internal data class OriginalLibraryFile(
    val name: String,
    val modified: Long,
    private val open: () -> InputStream,
    private val checksum: Long = -1L,
) {
    val extension get() = name.substringAfterLast('.', "")
    val nameWithoutExtension get() = name.substringBeforeLast('.')

    fun bytes(limit: Int = 24 * 1024 * 1024): ByteArray {
        val data = open().use { it.readLimited(limit) }
        if (checksum >= 0L) {
            require(CRC32().apply { update(data) }.value == checksum) { "备份文件校验失败" }
        }
        return data
    }
}

internal interface OriginalLibrarySource {
    fun file(path: String): OriginalLibraryFile?
    fun files(directory: String): List<OriginalLibraryFile>
}

internal fun safeOriginalPath(path: String): Boolean =
    path.isNotBlank() && path.split('/').all { it.isNotEmpty() && it != "." && it != ".." && ':' !in it && '\\' !in it && '\u0000' !in it }

internal class FolderOriginalLibrary(private val profile: File) : OriginalLibrarySource {
    private fun resolve(path: String): File? {
        if (!safeOriginalPath(path)) return null
        val root = profile.canonicalFile
        return File(root, path).canonicalFile.takeIf { it.toPath().startsWith(root.toPath()) }
    }

    override fun file(path: String): OriginalLibraryFile? = resolve(path)?.takeIf(File::isFile)?.let { file ->
        OriginalLibraryFile(file.name, file.lastModified(), file::inputStream)
    }

    override fun files(directory: String): List<OriginalLibraryFile> = resolve(directory)?.listFiles()?.mapNotNull {
        file("$directory/${it.name}")
    }.orEmpty()
}

private class ZipOriginalLibrary(
    private val zip: ZipFile,
    private val entries: Map<String, ZipEntry>,
) : OriginalLibrarySource {
    private val folders = entries.keys.groupBy { it.substringBeforeLast('/', "") }

    override fun file(path: String): OriginalLibraryFile? = entries[path]?.let { entry ->
        OriginalLibraryFile(path.substringAfterLast('/'), entry.time, { zip.getInputStream(entry) }, entry.crc)
    }

    override fun files(directory: String): List<OriginalLibraryFile> = folders[directory].orEmpty().mapNotNull(::file)
}

internal fun <T> readOriginalBackup(input: InputStream, cache: File, displayName: String, checkActive: () -> Unit,
                                    import: (String, OriginalLibrarySource) -> T): List<T> {
    val spool = File.createTempFile("sillytavern-account-", ".zip", cache)
    try {
        spool.outputStream().buffered().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkActive()
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
        }
        return ZipFile(spool).use { zip ->
            val entries = linkedMapOf<String, ZipEntry>()
            val roots = linkedSetOf<String>()
            for (entry in zip.entries()) {
                checkActive()
                val path = entry.name.replace('\\', '/').trimEnd('/')
                require(safeOriginalPath(path)) { "备份 ZIP 包含不安全的路径" }
                if (entry.isDirectory) continue
                require(entries.putIfAbsent(path, entry) == null) { "备份 ZIP 包含重复文件名" }
                val parts = path.split('/')
                val root = (0..minOf(3, parts.lastIndex)).firstOrNull { level ->
                    if (level > 1 && parts[level - 2] != "data") false
                    else profileFile(parts.drop(level))
                }
                if (root != null) roots += parts.take(root).joinToString("/")
            }
            require(roots.isNotEmpty()) { "ZIP 中未找到 SillyTavern 账户备份" }
            val filename = displayName.substringAfterLast('/').substringAfterLast('\\')
            val originalHandle = Regex("""^(.+)-\d{8}-\d{6}\.zip$""", RegexOption.IGNORE_CASE)
                .matchEntire(filename)?.groupValues?.get(1)
            val dataRoots = if ("" in roots) listOf("") else roots.filter { candidate ->
                roots.none { parent -> candidate != parent && candidate.startsWith("$parent/") }
            }.sorted()
            dataRoots.map { root ->
                checkActive()
                val account = root.substringAfterLast('/').ifBlank { originalHandle ?: "default-user" }
                val files = if (root.isEmpty()) entries else entries.filterKeys { it.startsWith("$root/") }
                    .mapKeys { it.key.removePrefix("$root/") }
                import(account, ZipOriginalLibrary(zip, files))
            }
        }
    } finally {
        spool.delete()
    }
}

private fun profileFile(path: List<String>): Boolean = when (path.firstOrNull()) {
    "settings.json" -> path.size == 1
    "characters", "groups", "worlds", "group chats", "OpenAI Settings", "QuickReplies" ->
        path.size == 2 && path[1].substringAfterLast('.').lowercase() in setOf("json", "jsonl", "png")
    else -> false
}
