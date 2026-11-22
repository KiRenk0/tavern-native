package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject

internal const val REPLY_IMAGE_OPTIONS = "tavern_native_reply_image_options"
internal const val GEMINI_REPLAY_PARTS = "tavern_native_gemini_replay_parts"
internal val replyImageRatios = listOf("", "1:1", "2:3", "3:2", "3:4", "4:3", "4:5", "5:4",
    "9:16", "16:9", "21:9", "1:4", "4:1", "1:8", "8:1")
internal val replyImageSizes = listOf("", "512", "1K", "2K", "4K")

internal data class ReplyImageOptions(
    val enabled: Boolean = false,
    val ratio: String = "",
    val size: String = "",
) {
    fun writeTo(json: JSONObject) {
        if (enabled) json.put("request_images", true)
        if (ratio.isNotEmpty()) json.put("request_image_aspect_ratio", ratio)
        if (size.isNotEmpty()) json.put("request_image_resolution", size)
    }

    fun applyTo(connection: ModelConnection, payload: JSONObject) {
        if (!enabled) return
        when (connection.provider.protocol) {
            ApiProtocol.GEMINI_NATIVE -> if (isGeminiImageModel(connection.model)) {
                payload.put(REPLY_IMAGE_OPTIONS, jsonOf("aspectRatio" to ratio.takeIf(String::isNotEmpty),
                    "imageSize" to size.takeIf { it.isNotEmpty() && !connection.model.removePrefix("models/").startsWith("gemini-2.") }))
            }
            ApiProtocol.OPENROUTER -> {
                payload.put("modalities", JSONArray(listOf("text", "image")))
                if (ratio.isNotEmpty() || size.isNotEmpty()) payload.put("image_config",
                    jsonOf("aspect_ratio" to ratio.takeIf(String::isNotEmpty), "image_size" to size.takeIf(String::isNotEmpty)))
            }
            else -> Unit
        }
    }

    companion object {
        fun read(json: JSONObject) = ReplyImageOptions(json.optBoolean("request_images"),
            json.string("request_image_aspect_ratio").takeIf { it in replyImageRatios }.orEmpty(),
            json.string("request_image_resolution").uppercase().takeIf { it in replyImageSizes }.orEmpty())
    }
}

private val geminiImageId = Regex("""^gemini-(?:nano-banana(?:-|$)|[\w.]+(?:-[\w.]+)*-image(?:-|$))""")
internal fun isGeminiImageModel(model: String) =
    model.removePrefix("models/").let { it == "gemini-2.0-flash-exp" || geminiImageId.containsMatchIn(it) }

private val replyImageMimes = setOf("image/png", "image/jpeg", "image/webp")
internal const val MAX_REPLY_IMAGE_BYTES = 24 * 1024 * 1024
private const val MAX_REPLY_SIGNATURE = 256 * 1024
internal const val MAX_REPLAY_PARTS = 128

internal data class ReplyRaster(val bytes: ByteArray, val mime: String, val key: String) {
    companion object {
        fun read(mime: String, data: String): ReplyRaster? {
            val type = mime.lowercase().let { if (it == "image/jpg") "image/jpeg" else it }
            if (type !in replyImageMimes || data.isBlank()) return null
            val bytes = runCatching { decodeGeneratedImage(data) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
            return ReplyRaster(bytes, type, ChatImageStore.digest(bytes))
        }

        fun fromDataUrl(url: String): ReplyRaster? {
            if (!url.startsWith("data:image/")) return null
            val header = url.substringBefore(',').removePrefix("data:")
            if (!header.endsWith(";base64")) return null
            return read(header.removeSuffix(";base64"), url.substringAfter(',', ""))
        }
    }
}

internal data class ReplyContentPart(val text: String? = null, val imageKey: String? = null, val signature: String? = null) {
    fun json() = jsonOf("text" to text, "image" to imageKey, "signature" to signature)

    companion object {
        fun read(json: JSONObject): ReplyContentPart? {
            val text = (json.opt("text") as? String)?.takeIf { it.length <= MAX_MODEL_TEXT }
            val image = json.string("image").takeIf { it.isNotBlank() }
            if ((text == null) == (image == null)) return null
            return ReplyContentPart(text, image, boundedReplySignature(json.string("signature")))
        }
    }
}

internal fun boundedReplySignature(value: String): String? =
    value.takeIf { it.isNotBlank() && it.length <= MAX_REPLY_SIGNATURE }

internal data class GeminiReplyReplay(val providerId: String, val model: String, val parts: List<ReplyContentPart>) {
    fun json() = jsonOf("provider" to providerId, "model" to model, "parts" to JSONArray(parts.map(ReplyContentPart::json)))

    fun wireParts(connection: ModelConnection, urls: Map<String, String>): JSONArray? {
        if (providerId != connection.provider.id || model != connection.model.removePrefix("models/")) return null
        val rows = parts.map { part ->
            val row = if (part.text != null) jsonOf("text" to part.text) else {
                val url = urls[part.imageKey] ?: return null
                jsonOf("inlineData" to jsonOf("mimeType" to url.substringAfter("data:").substringBefore(';'),
                    "data" to url.substringAfter(',')))
            }
            part.signature?.let { row.put("thoughtSignature", it) }
            row
        }
        return JSONArray(rows)
    }

    companion object {
        fun read(json: JSONObject?): GeminiReplyReplay? {
            val provider = json?.string("provider")?.takeIf(String::isNotBlank) ?: return null
            val model = json.string("model").takeIf(String::isNotBlank) ?: return null
            val parts = json.optJSONArray("parts")?.objects().orEmpty().take(MAX_REPLAY_PARTS).mapNotNull(ReplyContentPart::read)
            if (parts.isEmpty()) return null
            return GeminiReplyReplay(provider, model, parts)
        }
    }
}

internal data class StoredReplyMedia(val images: List<ChatImage>, val gemini: GeminiReplyReplay? = null) {
    fun json() = jsonOf("images" to JSONArray(images.map(ChatImage::json)), "gemini" to gemini?.json())

    fun available(contains: (ChatImage) -> Boolean): StoredReplyMedia? {
        val items = images.filter(contains)
        val replay = gemini?.takeIf { items.size == images.size }
        return StoredReplyMedia(items, replay).takeIf { items.isNotEmpty() || replay != null }
    }

    companion object {
        fun read(json: JSONObject?): StoredReplyMedia? {
            if (json == null) return null
            val images = json.optJSONArray("images")?.objects().orEmpty().take(MAX_CHAT_IMAGES).mapNotNull(ChatImage::from)
            val replay = GeminiReplyReplay.read(json.optJSONObject("gemini"))
            return StoredReplyMedia(images, replay).takeIf { images.isNotEmpty() || replay != null }
        }
    }
}
