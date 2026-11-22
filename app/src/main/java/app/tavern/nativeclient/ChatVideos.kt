package app.tavern.nativeclient

import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestOutputStream
import java.security.MessageDigest

internal const val MAX_CHAT_VIDEO_BYTES = 128L * 1024 * 1024
internal const val MAX_VIDEOS_PER_MESSAGE = 5
private val videoId = Regex("[0-9a-f]{64}")

internal data class ChatVideo(val id: String, val name: String, val byteCount: Long) {
    val filename get() = "$id.mp4"
    val displayFilename get() = name.substringBeforeLast('.').ifBlank { "视频" } + ".mp4"

    fun json() = jsonOf("id" to id, "name" to name, "bytes" to byteCount, "mime" to "video/mp4")

    companion object {
        fun from(row: JSONObject): ChatVideo? {
            val id = row.string("id")
            val bytes = row.optLong("bytes")
            if (row.string("mime") != "video/mp4" || !videoId.matches(id) || bytes !in 12..MAX_CHAT_VIDEO_BYTES) return null
            return ChatVideo(id, row.string("name").take(120), bytes)
        }
    }
}

internal class ChatVideoStore(private val directory: File) {
    fun file(video: ChatVideo): File {
        require(videoId.matches(video.id))
        return File(directory, video.filename)
    }

    fun input(video: ChatVideo): InputStream? = file(video)
        .takeIf { it.isFile && it.length() == video.byteCount }?.inputStream()

    fun import(input: InputStream, name: String, checkActive: () -> Unit = {}): ChatVideo = write(input, name, null, checkActive)

    fun restore(video: ChatVideo, input: InputStream, checkActive: () -> Unit = {}) {
        write(input, video.name, video, checkActive)
    }

    private fun write(input: InputStream, name: String, expected: ChatVideo?, checkActive: () -> Unit): ChatVideo {
        if (!directory.isDirectory) check(directory.mkdirs() || directory.isDirectory) { "无法创建视频目录" }
        val temporary = Files.createTempFile(directory.toPath(), ".video-", ".partial").toFile()
        try {
            val sha256 = MessageDigest.getInstance("SHA-256")
            val count = DigestOutputStream(temporary.outputStream().buffered(), sha256).use { out ->
                copyVideo(input, out, expected?.byteCount ?: MAX_CHAT_VIDEO_BYTES, checkActive)
            }
            require(count >= 12) { "文件不是有效的 MP4 视频" }
            val header = ByteArray(12)
            DataInputStream(temporary.inputStream()).use { it.readFully(header) }
            require(header.decodeToString(4, 8) == "ftyp") { "文件不是有效的 MP4 视频" }
            val video = ChatVideo(sha256.digest().joinToString("") { "%02x".format(it.toInt() and 255) },
                name.filterNot(Char::isISOControl).take(120).ifBlank { "视频.mp4" }, count)
            require(expected == null || video.id == expected.id && count == expected.byteCount) { "视频文件校验失败" }
            val target = file(video).toPath()
            try {
                Files.move(temporary.toPath(), target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
            }
            return video
        } finally {
            temporary.delete()
        }
    }
}

internal fun copyVideo(input: InputStream, output: OutputStream, limit: Long, checkActive: () -> Unit = {}): Long {
    val buffer = ByteArray(64 * 1024)
    var count = 0L
    while (true) {
        checkActive()
        val amount = input.read(buffer)
        if (amount < 0) return count
        count += amount
        require(count <= limit) { "视频超过大小限制" }
        output.write(buffer, 0, amount)
    }
}
