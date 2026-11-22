package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.Base64
import java.util.zip.ZipInputStream

internal enum class ImageProtocol(val label: String, val source: String) {
    GEMINI("Google Gemini", "google"), COMPATIBLE("OpenAI 兼容", "openai"), DIFFUSION("AUTOMATIC1111 / Forge", "auto"),
    NOVELAI("NovelAI", "novelai"), HORDE("AI Horde", "horde"),
    COMFY("ComfyUI", "comfy"), COMFY_RUNPOD("ComfyUI · RunPod", "comfyrunpod"),
    POLLINATIONS("Pollinations.ai", "pollinations"),
    STABILITY("Stability AI", "stability"),
    DRAW_THINGS("Draw Things", "drawthings"),
    SD_NEXT("SD.Next", "sdnext"),
    TOGETHER("Together AI", "together"),
    IMAGEN("Google Imagen / Vertex", "imagen"),
    XAI("xAI (Grok Imagine)", "xai"),
    NANOGPT("NanoGPT", "nanogpt"),
    HUGGINGFACE("HuggingFace Inference", "huggingface");

    val usesWorkflow get() = this == COMFY || this == COMFY_RUNPOD
    val supportsReferences get() = this == GEMINI || this == DRAW_THINGS || usesWorkflow
    val usesSamplerParameters
        get() = this in setOf(
            DIFFUSION, DRAW_THINGS, SD_NEXT, NOVELAI, HORDE, POLLINATIONS, STABILITY, TOGETHER, NANOGPT, HUGGINGFACE,
        ) || usesWorkflow
    val modelOptional get() = this in setOf(DIFFUSION, DRAW_THINGS, SD_NEXT, HORDE, POLLINATIONS)

    fun available(provider: Provider): Boolean {
        if (provider.protocol == ApiProtocol.HORDE_TEXT) return this == HORDE && provider.endpoint.isNotBlank()
        if (provider.id == POLLINATIONS_BUILTIN_ID) return this == POLLINATIONS
        return when (this) {
            GEMINI -> provider.template == ProviderTemplate.GOOGLE && provider.apiKey.isNotBlank()
            IMAGEN -> (provider.template == ProviderTemplate.GOOGLE || provider.template == null) && provider.apiKey.isNotBlank()
            COMPATIBLE, DIFFUSION, DRAW_THINGS, SD_NEXT, COMFY -> provider.template == null && provider.endpoint.isNotBlank()
            POLLINATIONS -> provider.template == null
            NOVELAI, STABILITY, TOGETHER, XAI, NANOGPT, HUGGINGFACE -> provider.template == null && provider.apiKey.isNotBlank()
            COMFY_RUNPOD -> provider.template == null && provider.endpoint.isNotBlank() && provider.apiKey.isNotBlank()
            HORDE -> false
        }
    }

    fun initialModel(provider: Provider) = when (this) {
        GEMINI -> DEFAULT_GEMINI_IMAGE
        COMPATIBLE, COMFY, COMFY_RUNPOD -> provider.defaultModel
        NOVELAI -> provider.defaultModel.ifBlank { "nai-diffusion-4-curated-preview" }
        POLLINATIONS -> provider.defaultModel.ifBlank { "flux" }
        STABILITY -> provider.defaultModel.ifBlank { "sd3.5-large" }
        TOGETHER -> provider.defaultModel.ifBlank { "black-forest-labs/FLUX.1-schnell-Free" }
        IMAGEN -> provider.defaultModel.ifBlank { "imagen-3.0-generate-002" }
        XAI -> provider.defaultModel.ifBlank { "grok-2-image" }
        NANOGPT -> provider.defaultModel.ifBlank { "flux-pro" }
        HUGGINGFACE -> provider.defaultModel.ifBlank { "black-forest-labs/FLUX.1-schnell" }
        DIFFUSION, DRAW_THINGS, SD_NEXT, HORDE -> ""
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
            ImageProtocol.NOVELAI -> novelAi(options)
            ImageProtocol.HORDE -> Horde(options.provider).image(options)
            ImageProtocol.COMFY, ImageProtocol.COMFY_RUNPOD ->
                ComfyService().generate(options, comfy ?: ComfyInputs(defaultComfyWorkflow), readImage)
            ImageProtocol.POLLINATIONS, ImageProtocol.STABILITY, ImageProtocol.DRAW_THINGS,
            ImageProtocol.SD_NEXT, ImageProtocol.TOGETHER, ImageProtocol.IMAGEN,
            ImageProtocol.XAI, ImageProtocol.NANOGPT, ImageProtocol.HUGGINGFACE ->
                ExtendedImageBackends.generate(options, readImage)
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

    private suspend fun novelAi(options: ImageRequest): ByteArray {
        val rawEndpoint = options.provider.endpoint.trim().ifBlank { "https://image.novelai.net" }
        val base = checkedAddress(rawEndpoint).toString().trimEnd('/').removeSuffix("/ai/generate-image")
        val targetUrl = "$base/ai/generate-image"
        val tuning = options.tuning
        val width = (tuning.width ?: when (options.shape) {
            ImageShape.SQUARE -> 1024
            ImageShape.PORTRAIT -> 832
            ImageShape.LANDSCAPE -> 1216
        }).coerceIn(64, 2048)
        val height = (tuning.height ?: when (options.shape) {
            ImageShape.SQUARE -> 1024
            ImageShape.PORTRAIT -> 1216
            ImageShape.LANDSCAPE -> 832
        }).coerceIn(64, 2048)
        val params = jsonOf(
            "params_version" to 3,
            "width" to width,
            "height" to height,
            "scale" to (tuning.cfg ?: 5.0),
            "sampler" to tuning.sampler.ifBlank { "k_euler_ancestral" },
            "steps" to (tuning.steps ?: 28),
            "n_samples" to 1,
            "ucPreset" to 0,
            "qualityToggle" to true,
            "negative_prompt" to tuning.negative,
        )
        tuning.seed?.let { params.put("seed", it) }
        val body = jsonOf(
            "input" to options.prompt,
            "model" to options.model.trim().ifBlank { "nai-diffusion-4-curated-preview" },
            "action" to "generate",
            "parameters" to params,
        )
        val headers = mapOf(
            "Authorization" to "Bearer ${options.provider.apiKey.trim()}",
            "Accept" to "application/zip, application/x-zip-compressed, image/png",
        )
        val zipBytes = request(targetUrl, body, headers, readTimeout = 240_000) { stream, _ ->
            stream.readLimited(MAX_IMAGE_SOURCE)
        }
        return extractNovelAiZipImage(zipBytes)
    }

    private fun descriptivePrompt(options: ImageRequest) = if (options.tuning.negative.isBlank()) options.prompt
        else "${options.prompt}\nAvoid these visual elements: ${options.tuning.negative}"

    private suspend fun payload(url: String, body: JSONObject, headers: Map<String, String>) =
        request(url, body, headers, readTimeout = 240_000) { stream, _ -> JSONObject(stream.readLimited().toString(Charsets.UTF_8)) }

    private fun bearer(key: String) = if (key.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${key.trim()}")

}

internal fun extractNovelAiZipImage(zipBytes: ByteArray): ByteArray {
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.isDirectory) continue
            val name = entry.name.lowercase()
            if (name.endsWith(".png") || name.endsWith(".webp") || name.endsWith(".jpg") || name.endsWith(".jpeg") || '.' !in name) {
                return zip.readLimited(MAX_IMAGE_SOURCE).also {
                    check(it.isNotEmpty()) { "NovelAI 返回了空的图片条目" }
                }
            }
        }
    }
    error("NovelAI ZIP 响应中未找到生成的图片")
}

internal fun decodeGeneratedImage(value: String): ByteArray {
    check(value.isNotBlank()) { "图像服务未返回图片" }
    val data = if (value.startsWith("data:")) value.substringAfter(',') else value
    require(data.length <= (MAX_IMAGE_SOURCE + 2) / 3 * 4) { "图像服务返回的图片过大" }
    return Base64.getDecoder().decode(data).also { require(it.size <= MAX_IMAGE_SOURCE) { "图像服务返回的图片过大" } }
}
