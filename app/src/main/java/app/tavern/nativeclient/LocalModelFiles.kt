package app.tavern.nativeclient

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

internal suspend fun downloadModelWeights(path: String, install: (InputStream) -> Unit, onMirror: () -> Unit) {
    var started = false
    val headers = mapOf("Accept" to "application/octet-stream")
    try {
        request("https://huggingface.co/$path", headers = headers, readTimeout = 45_000, followRedirects = true) { input, _ ->
            started = true
            install(input)
        }
    } catch (e: Exception) {
        if (e is CancellationException || started) throw e
        onMirror()
        request("https://hf-mirror.com/$path", headers = headers, readTimeout = 45_000, followRedirects = true) { input, _ ->
            install(input)
        }
    }
}

internal fun installVerifiedWeights(weights: File, bytes: Long, sha256: String, source: InputStream,
                                    progress: (Long) -> Unit, commit: (File) -> Unit) {
    val directory = requireNotNull(weights.parentFile)
    check(directory.isDirectory || directory.mkdirs()) { "无法创建本地模型目录" }
    val candidate = File.createTempFile(weights.nameWithoutExtension, ".part", directory)
    try {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var received = 0L
        candidate.outputStream().use { output ->
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                received += count
                require(received <= bytes) { "文件与所需的量化模型不符" }
                digest.update(buffer, 0, count)
                output.write(buffer, 0, count)
                progress(received)
            }
            output.fd.sync()
        }
        require(received == bytes && digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == sha256) {
            "本地模型不完整或校验失败"
        }
        commit(candidate)
    } finally {
        candidate.delete()
    }
}
