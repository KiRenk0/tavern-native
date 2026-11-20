package app.tavern.nativeclient

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.util.AtomicFile
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import kotlin.math.roundToInt

internal const val MAX_CHAT_IMAGES = 5
internal const val MAX_IMAGE_SOURCE = 16 * 1024 * 1024
internal const val MAX_STORED_IMAGE = 2 * 1024 * 1024
private const val MAX_PROMPT_IMAGES = 12 * 1024 * 1024
private val imageId = Regex("[0-9a-f]{64}")

internal enum class ImageFormat(val suffix: String, val mime: String) {
    JPEG("jpg", "image/jpeg"), PNG("png", "image/png");
}

internal data class ChatImage(val id: String, val name: String, val format: ImageFormat) {
    val filename get() = "$id.${format.suffix}"
    val displayFilename get() = "${name.substringBeforeLast('.').ifBlank { "图片" }}.${format.suffix}"

    fun json() = jsonOf("id" to id, "name" to name, "mime" to format.mime)
    fun dataUrl(bytes: ByteArray) = "data:${format.mime};base64,${Base64.getEncoder().encodeToString(bytes)}"

    companion object {
        fun from(row: JSONObject): ChatImage? {
            val id = row.string("id")
            val format = ImageFormat.entries.firstOrNull { it.mime == row.string("mime") } ?: return null
            return if (imageId.matches(id)) ChatImage(id, row.string("name").take(120), format) else null
        }
    }
}

internal class ChatImageStore(private val directory: File) {
    fun file(image: ChatImage): File {
        require(imageId.matches(image.id))
        return File(directory, image.filename)
    }

    fun bytes(image: ChatImage): ByteArray? = file(image).takeIf(File::isFile)?.inputStream()?.use {
        it.readLimited(MAX_STORED_IMAGE)
    }

    fun import(source: ByteArray, name: String): ChatImage {
        var bitmap = decode(source)
        val format = if (bitmap.hasAlpha()) ImageFormat.PNG else ImageFormat.JPEG
        val compression = if (format == ImageFormat.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        try {
            while (true) {
                val bytes = ByteArrayOutputStream().use { out ->
                    check(bitmap.compress(compression, 90, out)) { "无法编码图片" }
                    out.toByteArray()
                }
                if (bytes.size <= MAX_STORED_IMAGE) {
                    val image = ChatImage(digest(bytes), name.take(120), format)
                    restore(image, bytes)
                    return image
                }
                val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * 0.7).roundToInt().coerceAtLeast(1),
                    (bitmap.height * 0.7).roundToInt().coerceAtLeast(1), true)
                bitmap.recycle()
                bitmap = scaled
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Synchronized
    fun restore(image: ChatImage, bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size <= MAX_STORED_IMAGE && digest(bytes) == image.id && matchesFormat(bytes, image.format)) {
            "聊天图片文件校验失败"
        }
        val target = file(image)
        if (target.isFile && target.length() == bytes.size.toLong()) return
        val atomic = AtomicFile(target)
        val out = atomic.startWrite()
        try {
            out.write(bytes)
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        }
    }

    private fun decode(source: ByteArray): Bitmap {
        val edge = 2048
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(source))) { decoder, info, _ ->
                requireDimensions(info.size.width, info.size.height)
                val scale = minOf(1.0, edge.toDouble() / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1),
                    (info.size.height * scale).roundToInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        requireDimensions(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > edge) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return requireNotNull(BitmapFactory.decodeByteArray(source, 0, source.size, options)) { "无法读取图片" }
    }

    private fun requireDimensions(width: Int, height: Int) {
        require(width > 0 && height > 0 && width.toLong() * height <= 64_000_000L) { "图片格式或尺寸超出支持范围" }
    }

    companion object {
        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        private fun matchesFormat(bytes: ByteArray, format: ImageFormat): Boolean = when (format) {
            ImageFormat.JPEG -> bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()
            ImageFormat.PNG -> bytes.size >= 8 && bytes.copyOf(8).contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
        }
    }
}

internal fun promptHistory(history: List<Message>, includeTool: (ToolTurn) -> Boolean = { false }) = history.filter {
    if (it.hidden) false
    else if (it.role == "system") it.toolTurn?.let(includeTool) == true
    else it.text.isNotBlank() || it.documents.isNotEmpty() ||
        it.displayImages.any { image -> it.role == "user" || it.captionFor(image) != null }
}.takeLast(80)

internal fun promptImages(history: List<Message>, read: (ChatImage) -> ByteArray?): Map<Int, List<String>> {
    val chosen = mutableMapOf<Int, List<String>>()
    var remaining = MAX_PROMPT_IMAGES
    for (index in history.indices.reversed()) {
        val message = history[index]
        if (message.role != "user" || remaining == 0) continue
        val urls = message.displayImages.mapNotNull { image ->
            val bytes = read(image)?.takeIf { it.size <= remaining } ?: return@mapNotNull null
            remaining -= bytes.size
            image.dataUrl(bytes)
        }
        if (urls.isNotEmpty()) chosen[index] = urls
    }
    return chosen
}
