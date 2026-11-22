package app.tavern.nativeclient

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.Base64
import kotlin.math.roundToInt

internal const val GROUP_AVATAR_EDGE = 300
private const val groupAvatarBytes = 128 * 1024
private const val jpegDataUrl = "data:image/jpeg;base64,"

@JvmInline
internal value class GroupAvatar private constructor(private val encoded: String) {
    val dataUrl get() = jpegDataUrl + encoded
    fun decode() = decodeBoundedImage(Base64.getDecoder().decode(encoded), GROUP_AVATAR_EDGE)

    companion object {
        fun fromDataUrl(value: String): GroupAvatar? {
            if (!value.startsWith(jpegDataUrl, ignoreCase = true)) return null
            val data = value.substring(jpegDataUrl.length)
            val bytes = decodeAvatarBase64(data, groupAvatarBytes) ?: return null
            if (bytes.size < 4 || bytes[0] != 0xff.toByte() || bytes[1] != 0xd8.toByte() ||
                bytes[2] != 0xff.toByte()) return null
            return GroupAvatar(data)
        }

        fun create(bitmap: Bitmap, crop: GroupAvatarCrop = GroupAvatarCrop()): GroupAvatar {
            val (left, top, side) = crop.square(bitmap.width, bitmap.height)
            val thumbnail = Bitmap.createBitmap(GROUP_AVATAR_EDGE, GROUP_AVATAR_EDGE, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(thumbnail)
                canvas.drawColor(android.graphics.Color.WHITE)
                canvas.drawBitmap(bitmap, Rect(left, top, left + side, top + side),
                    Rect(0, 0, GROUP_AVATAR_EDGE, GROUP_AVATAR_EDGE), Paint(Paint.FILTER_BITMAP_FLAG))
                val bytes = ByteArrayOutputStream().use {
                    check(thumbnail.compress(Bitmap.CompressFormat.JPEG, 88, it)) { "无法保存群组头像" }
                    it.toByteArray()
                }
                check(bytes.size <= groupAvatarBytes) { "群组头像文件过大" }
                return GroupAvatar(Base64.getEncoder().encodeToString(bytes))
            } finally {
                thumbnail.recycle()
            }
        }
    }
}

internal data class GroupAvatarSquare(val left: Int, val top: Int, val side: Int)

internal data class GroupAvatarCrop(
    val zoom: Float = 1f,
    val x: Float = 0.5f,
    val y: Float = 0.5f,
) {
    fun square(width: Int, height: Int): GroupAvatarSquare {
        val side = (minOf(width, height) / zoom).roundToInt().coerceAtLeast(1)
        return GroupAvatarSquare(((width - side) * x).roundToInt(), ((height - side) * y).roundToInt(), side)
    }

    fun move(width: Int, height: Int, dx: Float, dy: Float, scale: Float): GroupAvatarCrop {
        val previous = square(width, height)
        val adjusted = copy(zoom = (zoom * scale).coerceIn(1f, 4f))
        val side = adjusted.square(width, height).side
        fun anchor(previousStart: Int, delta: Float, available: Int): Float =
            if (available == 0) 0.5f else ((previousStart + (previous.side - side) / 2f - delta * previous.side) / available)
                .coerceIn(0f, 1f)
        return adjusted.copy(x = anchor(previous.left, dx, width - side), y = anchor(previous.top, dy, height - side))
    }
}

internal fun originalGroupAvatarPath(value: String): String? {
    val uri = runCatching { URI(value) }.getOrNull()
    if (uri?.isAbsolute == true || value.startsWith("//")) return null
    return (uri?.path ?: value).removePrefix("./").removePrefix("/")
        .takeIf { it.startsWith("user/images/") && safeOriginalPath(it) &&
            it.substringAfterLast('.').lowercase() in originalImageExtensions }
}

internal fun originalGroupAvatarData(value: String): ByteArray? {
    if (!value.startsWith("data:image/", ignoreCase = true) || !value.substringBefore(',').endsWith(";base64", ignoreCase = true)) {
        return null
    }
    return decodeAvatarBase64(value.substringAfter(',', ""), MAX_IMAGE_SOURCE)
}

private fun decodeAvatarBase64(data: String, limit: Int): ByteArray? {
    if (data.isEmpty() || data.length > 4 * ((limit + 2) / 3)) return null
    return runCatching { Base64.getDecoder().decode(data) }.getOrNull()?.takeIf { it.size in 1..limit }
}
