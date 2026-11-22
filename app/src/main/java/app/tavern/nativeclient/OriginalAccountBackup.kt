package app.tavern.nativeclient

import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.nio.file.Files
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

private const val MAX_ARCHIVE_PATH_LENGTH = 512
private const val MAX_ARCHIVE_PATH_DEPTH = 16
private val reservedWindowsSegment = Regex("(?i)^(?:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?$")
private val encodedTraversalPattern = Regex("(?i)%(?:25)*(?:2e|2f|5c|00)")

internal data class OriginalLibraryFile(
    val name: String,
    val modified: Long,
    private val open: () -> InputStream,
    private val checksum: Long = -1L,
    val relativePath: String = name,
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

    fun <T> streaming(read: (InputStream) -> T): T = open().use { input ->
        if (checksum < 0) return@use read(input)
        val checked = CheckedInputStream(input, CRC32())
        val result = read(checked)
        require(checked.checksum.value == checksum) { "备份文件校验失败" }
        result
    }
}

internal interface OriginalLibrarySource {
    fun file(path: String): OriginalLibraryFile?
    fun files(directory: String): List<OriginalLibraryFile>
    fun allFiles(directory: String): List<OriginalLibraryFile> = files(directory)
}

internal fun safeOriginalPath(path: String): Boolean {
    if (path.isBlank() || path.length > MAX_ARCHIVE_PATH_LENGTH) return false
    if (path.startsWith("/") || path.endsWith("/")) return false
    if ('\\' in path || '\u0000' in path || ':' in path) return false
    if (path.any(Char::isISOControl)) return false
    if (encodedTraversalPattern.containsMatchIn(path)) return false
    val segments = path.split('/')
    if (segments.size > MAX_ARCHIVE_PATH_DEPTH) return false
    return segments.all { segment ->
        segment.isNotEmpty() &&
            segment != "." &&
            segment != ".." &&
            !segment.endsWith(".") &&
            !segment.endsWith(" ") &&
            !reservedWindowsSegment.matches(segment)
    }
}

internal fun decodeAndCheckOriginalPath(raw: String): String? {
    val candidate = raw.trim()
    if (candidate.isEmpty() || '\\' in candidate || '\u0000' in candidate) return null
    if (encodedTraversalPattern.containsMatchIn(candidate)) return null
    val decoded = runCatching { URLDecoder.decode(candidate, "UTF-8") }.getOrNull() ?: return null
    val normalized = decoded.removePrefix("./").removePrefix("/")
    return normalized.takeIf(::safeOriginalPath)
}

internal class FolderOriginalLibrary(private val profile: File) : OriginalLibrarySource {
    private fun resolve(path: String): File? {
        if (!safeOriginalPath(path)) return null
        val root = profile.canonicalFile
        val target = File(root, path)
        if (Files.isSymbolicLink(target.toPath())) return null
        return target.canonicalFile.takeIf { it.toPath().startsWith(root.toPath()) }
    }

    override fun file(path: String): OriginalLibraryFile? = resolve(path)?.takeIf(File::isFile)?.let { file ->
        OriginalLibraryFile(file.name, file.lastModified(), file::inputStream, relativePath = path)
    }

    override fun files(directory: String): List<OriginalLibraryFile> = resolve(directory)?.listFiles()?.mapNotNull {
        file("$directory/${it.name}")
    }.orEmpty()

    override fun allFiles(directory: String): List<OriginalLibraryFile> {
        val base = resolve(directory)?.takeIf(File::isDirectory) ?: return emptyList()
        val rootPath = profile.canonicalFile.toPath()
        val collected = mutableListOf<OriginalLibraryFile>()
        fun walk(dir: File, relPrefix: String, depth: Int) {
            if (depth > MAX_ARCHIVE_PATH_DEPTH) return
            for (child in dir.listFiles().orEmpty().sortedBy(File::getName)) {
                if (Files.isSymbolicLink(child.toPath())) continue
                val rel = "$relPrefix/${child.name}"
                if (!safeOriginalPath(rel)) continue
                val canonical = child.canonicalFile
                if (!canonical.toPath().startsWith(rootPath)) continue
                if (canonical.isDirectory) walk(canonical, rel, depth + 1)
                else if (canonical.isFile) collected += OriginalLibraryFile(
                    canonical.name, canonical.lastModified(), canonical::inputStream, relativePath = rel,
                )
            }
        }
        walk(base, directory, directory.count { it == '/' } + 1)
        return collected
    }
}

private class ZipOriginalLibrary(
    private val zip: ZipFile,
    private val entries: Map<String, ZipEntry>,
) : OriginalLibrarySource {
    private val folders = entries.keys.groupBy { it.substringBeforeLast('/', "") }

    override fun file(path: String): OriginalLibraryFile? = entries[path]?.let { entry ->
        OriginalLibraryFile(
            name = path.substringAfterLast('/'),
            modified = entry.time,
            open = { zip.getInputStream(entry) },
            checksum = entry.crc,
            relativePath = path,
        )
    }

    override fun files(directory: String): List<OriginalLibraryFile> = folders[directory].orEmpty().mapNotNull(::file)

    override fun allFiles(directory: String): List<OriginalLibraryFile> {
        val prefix = "$directory/"
        return entries.keys.asSequence()
            .filter { it.startsWith(prefix) }
            .sorted()
            .mapNotNull(::file)
            .toList()
    }
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

private fun profileFile(path: List<String>): Boolean = when (path.firstOrNull()?.lowercase(Locale.ROOT)) {
    "settings.json" -> path.size == 1
    "characters", "groups", "worlds", "group chats", "openai settings", "quickreplies", "themes" ->
        path.size == 2 && path[1].substringAfterLast('.').lowercase(Locale.ROOT) in setOf("json", "jsonl", "png")
    "user avatars" -> path.size == 2 && path[1].substringAfterLast('.').lowercase(Locale.ROOT) in originalImageExtensions
    "backgrounds" -> path.size >= 2 && path.last().substringAfterLast('.').lowercase(Locale.ROOT) in originalImageExtensions
    "assets" -> path.size >= 2 && path.last().substringAfterLast('.').lowercase(Locale.ROOT) in originalImageExtensions
    "user" -> path.size >= 3 && path[1].lowercase(Locale.ROOT) in setOf("images", "assets") &&
        path.last().substringAfterLast('.').lowercase(Locale.ROOT) in originalImageExtensions
    else -> false
}
