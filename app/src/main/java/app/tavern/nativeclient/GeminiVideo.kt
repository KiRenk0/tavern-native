package app.tavern.nativeclient

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.util.Base64

internal const val DEFAULT_VIDEO_MODEL = "gemini-omni-1.2-flash"
private val videoFileName = Regex("files/([A-Za-z0-9_-]{1,128})(?::download)?")
private const val MAX_INLINE_VIDEO = 8 * 1024 * 1024
private const val MAX_VIDEO_REFERENCE_BYTES = 12 * 1024 * 1024
internal const val MAX_VIDEO_REFERENCES = 6

internal enum class VideoAspect(val label: String, val wire: String) {
    LANDSCAPE("横屏 16:9", "16:9"),
    PORTRAIT("竖屏 9:16", "9:16"),
}

internal enum class VideoResolution(val wire: String) {
    LOW("360p"), HD("720p"), FULL_HD("1080p"), UHD("4k"),
}

internal data class VideoProfile(
    val providerId: String = "",
    val aspect: VideoAspect = VideoAspect.LANDSCAPE,
    val resolution: VideoResolution = VideoResolution.HD,
    val seconds: Int = 8,
) {
    fun json() = jsonOf("provider" to providerId, "aspect" to aspect.wire, "resolution" to resolution.wire, "seconds" to seconds)

    companion object {
        fun from(row: JSONObject?): VideoProfile = VideoProfile(
            row?.string("provider").orEmpty(), VideoAspect.entries.firstOrNull { it.wire == row?.string("aspect") } ?: VideoAspect.LANDSCAPE,
            VideoResolution.entries.firstOrNull { it.wire == row?.string("resolution") } ?: VideoResolution.HD,
            row?.optInt("seconds", 8)?.coerceIn(3, 15) ?: 8,
        )
    }
}

internal fun supportsVideoGeneration(provider: Provider): Boolean = provider.apiKey.isNotBlank() &&
    (provider.template == ProviderTemplate.GOOGLE || provider.protocol == ApiProtocol.GEMINI_NATIVE)

internal data class VideoRequest(
    val provider: Provider, val profile: VideoProfile, val prompt: String, val references: List<ChatImage> = emptyList(),
)

internal class GeminiVideoService(private val pollDelayMillis: Long = 4_000L) {
    suspend fun <T> generate(
        options: VideoRequest,
        readImage: (ChatImage) -> ByteArray?,
        progress: (String) -> Unit = {},
        consume: (InputStream) -> T,
    ): T {
        require(supportsVideoGeneration(options.provider)) { "请先保存 Google Gemini 连接和 Key" }
        require(options.prompt.isNotBlank()) { "请填写视频提示词" }
        require(options.profile.seconds in 3..15) { "视频时长须在 3–15 秒之间" }
        require(options.references.size <= MAX_VIDEO_REFERENCES) { "最多可添加 $MAX_VIDEO_REFERENCES 张参考图" }
        val base = ProviderProtocols.geminiApiRoot(checkedAddress(options.provider.endpoint).toString())
        val origin = URI(base)
        val key = options.provider.apiKey.trim()
        val auth = mapOf("x-goog-api-key" to key)
        var total = 0L
        val content = JSONArray().put(jsonOf("type" to "text", "text" to options.prompt.trim()))
        for (image in options.references) {
            val bytes = requireNotNull(readImage(image)) { "找不到参考图片 ${image.name}" }
            total += bytes.size
            require(total <= MAX_VIDEO_REFERENCE_BYTES) { "参考图总大小超过 12 MB" }
            content.put(jsonOf("type" to "image", "mime_type" to image.format.mime,
                "data" to Base64.getEncoder().encodeToString(bytes)))
        }
        val body = jsonOf(
            "model" to DEFAULT_VIDEO_MODEL, "input" to content, "store" to false,
            "response_format" to jsonOf("type" to "video", "delivery" to "uri",
                "aspect_ratio" to options.profile.aspect.wire, "resolution" to options.profile.resolution.wire,
                "duration" to "${options.profile.seconds}s"),
        )
        progress("正在生成视频…")
        val response = request("$base/interactions", body, auth, readTimeout = 300_000, redactError = { it.redactValue(key) }) { input, _ ->
            JSONObject(input.readLimited(12 * 1024 * 1024).decodeToString())
        }
        val clip = response.optJSONObject("output_video")?.takeIf { it.isMp4() }
            ?: response.optJSONArray("steps")?.objects().orEmpty().asSequence()
            .filter { it.string("type") == "model_output" }
            .flatMap { it.optJSONArray("content")?.objects().orEmpty().asSequence() }
            .lastOrNull { it.string("type") == "video" && it.isMp4() }
            ?: error(response.optJSONObject("error")?.string("message")?.redactValue(key)?.take(250)?.takeIf(String::isNotBlank)
                ?: "Google Gemini 没有返回 MP4 视频")
        val inline = clip.string("data")
        val uri = clip.string("uri")
        if (uri.isBlank() && inline.isNotBlank()) {
            require(inline.length <= 4 * ((MAX_INLINE_VIDEO + 2) / 3)) { "内嵌视频超过大小限制，请使用文件链接返回" }
            val data = Base64.getDecoder().decode(inline)
            require(data.size <= MAX_INLINE_VIDEO) { "内嵌视频超过大小限制" }
            return ByteArrayInputStream(data).use(consume)
        }

        val file = geminiVideoFile(origin, uri)
        progress("正在等待视频文件…")
        withTimeoutOrNull(180_000L) {
            var active = false
            while (!active) {
                val metadata = request("$base/$file", headers = auth, readTimeout = 15_000, redactError = { it.redactValue(key) }) { input, _ ->
                    JSONObject(input.readLimited(256 * 1024).decodeToString())
                }
                when (metadata.string("state")) {
                    "ACTIVE" -> active = true
                    "PROCESSING", "STATE_UNSPECIFIED" -> delay(pollDelayMillis)
                    "FAILED" -> error(metadata.optJSONObject("error")?.string("message")?.redactValue(key)?.take(250)
                        ?.takeIf(String::isNotBlank) ?: "Google Gemini 视频文件处理失败")
                    else -> error("Google Gemini 返回了未知的视频文件状态")
                }
            }
        } ?: error("等待 Google Gemini 视频文件超时")
        progress("正在下载视频…")
        return downloadGeminiVideo(origin, URI("$base/$file:download?alt=media"), auth, key, consume)
    }
}

private fun JSONObject.isMp4(): Boolean = string("mime_type").let { it.isBlank() || it.equals("video/mp4", true) } &&
    (string("uri").isNotBlank() || string("data").isNotBlank())

internal fun geminiVideoFile(base: URI, value: String): String {
    require(value.isNotBlank()) { "Google Gemini 未给出视频文件地址" }
    val candidate = URI(base.toString().trimEnd('/') + "/").resolve(value)
    require(sameVideoOrigin(base, candidate) && candidate.rawUserInfo == null && candidate.rawFragment == null) {
        "Google Gemini 返回了无效的视频文件地址"
    }
    val prefix = base.rawPath.trimEnd('/') + "/"
    val file = candidate.rawPath.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
        ?.let { videoFileName.matchEntire(it)?.groupValues?.get(1) }
    return "files/${requireNotNull(file) { "Google Gemini 视频文件标识无效" }}"
}

private fun sameVideoOrigin(base: URI, candidate: URI): Boolean {
    fun port(uri: URI) = uri.port.takeIf { it >= 0 } ?: if (uri.scheme.equals("https", true)) 443 else 80
    return base.scheme.equals(candidate.scheme, true) && base.host.equals(candidate.host, true) && port(base) == port(candidate)
}

private suspend fun <T> downloadGeminiVideo(
    origin: URI, address: URI, auth: Map<String, String>, key: String, consume: (InputStream) -> T,
): T {
    var uri = address
    var authenticate = true
    repeat(5) {
        val headers = (if (authenticate) auth else emptyMap()) + ("Accept" to "video/mp4, application/octet-stream")
        try {
            return request(uri.toASCIIString(), headers = headers, readTimeout = 60_000, reportRedirects = true,
                redactError = { it.redactValue(key) }) { input, type ->
                check(type.startsWith("video/mp4", true) || type.startsWith("application/octet-stream", true) || type.isBlank()) {
                    "视频下载接口返回了非 MP4 数据"
                }
                consume(input)
            }
        } catch (redirect: HttpRedirect) {
            val next = uri.resolve(redirect.location)
            val sameOrigin = sameVideoOrigin(origin, next)
            val host = next.host.orEmpty().lowercase()
            val googleStorage = host == "storage.googleapis.com" || host.endsWith(".storage.googleapis.com") ||
                host == "googleusercontent.com" || host.endsWith(".googleusercontent.com")
            require(next.rawUserInfo == null && next.rawFragment == null &&
                (sameOrigin || next.scheme == "https" && googleStorage)) { "视频下载重定向地址无效" }
            authenticate = authenticate && sameOrigin
            uri = next
        }
    }
    error("视频下载重定向次数过多")
}
