package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Base64

internal enum class ImageProtocol(val label: String, val source: String) {
    GEMINI("Google Gemini", "google"), COMPATIBLE("OpenAI 兼容", "openai"), DIFFUSION("AUTOMATIC1111 / Forge", "auto"),
    COMFY("ComfyUI", "comfy"), COMFY_RUNPOD("ComfyUI · RunPod", "comfyrunpod");

    val usesWorkflow get() = this == COMFY || this == COMFY_RUNPOD
    val supportsReferences get() = this == GEMINI || usesWorkflow
    val usesSamplerParameters get() = this == DIFFUSION || usesWorkflow

    fun available(provider: Provider) = when (this) {
        GEMINI -> provider.template == ProviderTemplate.GOOGLE && provider.apiKey.isNotBlank()
        COMPATIBLE, DIFFUSION, COMFY -> provider.template == null && provider.endpoint.isNotBlank()
        COMFY_RUNPOD -> provider.template == null && provider.endpoint.isNotBlank() && provider.apiKey.isNotBlank()
    }

    fun initialModel(provider: Provider) = when (this) {
        GEMINI -> DEFAULT_GEMINI_IMAGE
        COMPATIBLE, COMFY, COMFY_RUNPOD -> provider.defaultModel
        DIFFUSION -> ""
    }
}

internal enum class ImageShape(val label: String, val ratio: String, val width: Int, val height: Int, val compatibleSize: String) {
    SQUARE("正方形", "1:1", 512, 512, "1024x1024"),
    PORTRAIT("竖图", "2:3", 512, 768, "1024x1536"),
    LANDSCAPE("横图", "3:2", 768, 512, "1536x1024");
}

internal data class ImageRequest(
    val provider: Provider, val protocol: ImageProtocol, val model: String, val shape: ImageShape,
    val prompt: String, val references: List<ChatImage> = emptyList(),
    val tuning: ImageTuning = ImageTuning(),
    val workflowId: String? = null,
) {
    fun recipe() = DrawingRecipe(ImageProfile(provider.id, protocol, model, shape, tuning, workflowId), prompt, references)
}

internal const val DEFAULT_GEMINI_IMAGE = "gemini-nano-banana-2.1"

internal class ImageService(private val geminiBase: String = "https://generativelanguage.googleapis.com/v1beta") {
    suspend fun generate(options: ImageRequest, comfy: ComfyInputs? = null, readImage: (ChatImage) -> ByteArray?): ByteArray {
        require(options.protocol.available(options.provider)) { "请先配置对应的图像服务" }
        require(options.prompt.isNotBlank()) { "请输入绘图提示词" }
        return when (options.protocol) {
            ImageProtocol.GEMINI -> gemini(options, readImage)
            ImageProtocol.COMPATIBLE -> compatible(options)
            ImageProtocol.DIFFUSION -> diffusion(options)
            ImageProtocol.COMFY, ImageProtocol.COMFY_RUNPOD ->
                ComfyService().generate(options, comfy ?: ComfyInputs(defaultComfyWorkflow), readImage)
        }
    }

    private suspend fun gemini(options: ImageRequest, readImage: (ChatImage) -> ByteArray?): ByteArray {
        val input = JSONArray().put(jsonOf("type" to "text", "text" to descriptivePrompt(options)))
        options.references.take(MAX_CHAT_IMAGES).forEach { image ->
            val bytes = requireNotNull(readImage(image)) { "缺少参考图片 ${image.name}" }
            input.put(jsonOf("type" to "image", "mime_type" to image.format.mime,
                "data" to Base64.getEncoder().encodeToString(bytes)))
        }
        val body = jsonOf("model" to options.model.removePrefix("models/").ifBlank { DEFAULT_GEMINI_IMAGE },
            "input" to input, "store" to false,
            "response_format" to jsonOf("type" to "image", "aspect_ratio" to options.shape.ratio))
        val headers = mapOf("x-goog-api-key" to options.provider.apiKey.trim(), "Api-Revision" to "2026-05-20")
        val response = payload("$geminiBase/interactions", body, headers)
        val image = response.optJSONArray("steps")?.objects().orEmpty().asSequence().filter { it.string("type") == "model_output" }
            .flatMap { it.optJSONArray("content")?.objects().orEmpty() }.firstOrNull { it.string("type") == "image" }
        return decodeGeneratedImage(image?.string("data").orEmpty())
    }

    private suspend fun compatible(options: ImageRequest): ByteArray {
        val endpoint = checkedAddress(options.provider.endpoint)
        val address = endpoint.toString().trimEnd('/').removeSuffix("/chat/completions").removeSuffix("/images/generations")
        val model = options.model.trim().ifBlank { options.provider.defaultModel }
        val width = options.tuning.width ?: options.shape.compatibleSize.substringBefore('x').toInt()
        val height = options.tuning.height ?: options.shape.compatibleSize.substringAfter('x').toInt()
        val body = jsonOf("model" to model, "prompt" to descriptivePrompt(options), "n" to 1, "size" to "${width}x$height")
        val headers = bearer(options.provider.apiKey)
        val result = payload("$address/images/generations", body, headers).optJSONArray("data")?.optJSONObject(0)
        val encoded = result?.string("b64_json").orEmpty()
        if (encoded.isNotBlank()) return decodeGeneratedImage(encoded)
        val url = result?.string("url").orEmpty()
        check(url.isNotBlank()) { "图像服务未返回图片" }
        val uri = URI(url)
        val sameOrigin = uri.scheme == endpoint.scheme && uri.host.equals(endpoint.host, true) && uri.port == endpoint.port
        require(uri.host != null && uri.rawUserInfo == null && uri.rawFragment == null &&
            (uri.scheme == "https" || uri.scheme == "http" && sameOrigin)) { "图像服务返回了无效的图片地址" }
        return request(uri.toString(), headers = mapOf("Accept" to "image/*")) { stream, _ -> stream.readLimited(MAX_IMAGE_SOURCE) }
    }

    private suspend fun diffusion(options: ImageRequest): ByteArray {
        val endpoint = checkedAddress(options.provider.endpoint).toString().trimEnd('/').removeSuffix("/sdapi/v1/txt2img")
        val key = options.provider.apiKey.trim()
        val headers = if (':' in key) mapOf("Authorization" to "Basic ${Base64.getEncoder().encodeToString(key.toByteArray())}")
            else bearer(key)
        val tuning = options.tuning
        val body = jsonOf("prompt" to options.prompt, "width" to (tuning.width ?: options.shape.width),
            "height" to (tuning.height ?: options.shape.height),
            "batch_size" to 1)
        tuning.negative.takeIf(String::isNotBlank)?.let { body.put("negative_prompt", it) }
        tuning.steps?.let { body.put("steps", it) }
        tuning.cfg?.let { body.put("cfg_scale", it) }
        tuning.seed?.let { body.put("seed", it) }
        tuning.sampler.takeIf(String::isNotBlank)?.let { body.put("sampler_name", it) }
        tuning.scheduler.takeIf(String::isNotBlank)?.let { body.put("scheduler", it) }
        val overrides = JSONObject()
        options.model.takeIf(String::isNotBlank)?.let { overrides.put("sd_model_checkpoint", it) }
        tuning.vae.takeIf(String::isNotBlank)?.let { overrides.put("sd_vae", it) }
        tuning.skip?.let { overrides.put("CLIP_stop_at_last_layers", it) }
        if (overrides.length() > 0) body.put("override_settings", overrides).put("override_settings_restore_afterwards", true)
        return decodeGeneratedImage(payload("$endpoint/sdapi/v1/txt2img", body, headers).optJSONArray("images")?.optString(0).orEmpty())
    }

    private fun descriptivePrompt(options: ImageRequest) = if (options.tuning.negative.isBlank()) options.prompt
        else "${options.prompt}\nAvoid these visual elements: ${options.tuning.negative}"

    private suspend fun payload(url: String, body: JSONObject, headers: Map<String, String>) =
        request(url, body, headers, readTimeout = 240_000) { stream, _ -> JSONObject(stream.readLimited().toString(Charsets.UTF_8)) }

    private fun bearer(key: String) = if (key.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${key.trim()}")

}

internal fun decodeGeneratedImage(value: String): ByteArray {
    check(value.isNotBlank()) { "图像服务未返回图片" }
    val data = if (value.startsWith("data:")) value.substringAfter(',') else value
    require(data.length <= (MAX_IMAGE_SOURCE + 2) / 3 * 4) { "图像服务返回的图片过大" }
    return Base64.getDecoder().decode(data).also { require(it.size <= MAX_IMAGE_SOURCE) { "图像服务返回的图片过大" } }
}
