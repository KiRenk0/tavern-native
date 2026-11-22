package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale
import java.util.UUID

internal const val POLLINATIONS_BUILTIN_ID = "builtin-pollinations"
internal const val DEFAULT_POLLINATIONS_ENDPOINT = "https://image.pollinations.ai"
internal const val DEFAULT_STABILITY_ENDPOINT = "https://api.stability.ai"
internal const val DEFAULT_TOGETHER_ENDPOINT = "https://api.together.xyz/v1"
internal const val DEFAULT_IMAGEN_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta"
internal const val DEFAULT_XAI_ENDPOINT = "https://api.x.ai/v1"
internal const val DEFAULT_NANOGPT_ENDPOINT = "https://nano-gpt.com/api"
internal const val DEFAULT_HUGGINGFACE_ENDPOINT = "https://router.huggingface.co/hf-inference/models"

internal fun pollinationsBuiltinProvider(): Provider = Provider(
    id = POLLINATIONS_BUILTIN_ID,
    name = "Pollinations.ai（免 Key）",
    endpoint = DEFAULT_POLLINATIONS_ENDPOINT,
    defaultModel = "flux",
)

internal fun allImageProviders(providers: List<Provider>): List<Provider> = buildList {
    addAll(providers.filter { provider -> ImageProtocol.entries.any { it.available(provider) } })
    if (none { it.id == POLLINATIONS_BUILTIN_ID }) add(pollinationsBuiltinProvider())
}

internal fun resolveImageProtocol(source: String): ImageProtocol? = when (source.trim().lowercase(Locale.ROOT)) {
    "google", "gemini" -> ImageProtocol.GEMINI
    "openai", "compatible" -> ImageProtocol.COMPATIBLE
    "auto", "automatic1111", "sd", "forge" -> ImageProtocol.DIFFUSION
    "novelai", "nai" -> ImageProtocol.NOVELAI
    "horde", "aihorde", "ai-horde" -> ImageProtocol.HORDE
    "comfy", "comfyui" -> ImageProtocol.COMFY
    "comfyrunpod", "runpod" -> ImageProtocol.COMFY_RUNPOD
    "pollinations", "pollinations.ai" -> ImageProtocol.POLLINATIONS
    "stability", "stabilityai", "stability.ai", "sd3" -> ImageProtocol.STABILITY
    "drawthings", "draw-things", "draw_things" -> ImageProtocol.DRAW_THINGS
    "sdnext", "sd.next", "sd-next", "vlad" -> ImageProtocol.SD_NEXT
    "together", "togetherai", "together.ai" -> ImageProtocol.TOGETHER
    "imagen", "vertex", "vertexai", "google-imagen" -> ImageProtocol.IMAGEN
    "xai", "grok", "grok-imagine" -> ImageProtocol.XAI
    "nanogpt", "nano-gpt", "nano_gpt" -> ImageProtocol.NANOGPT
    "huggingface", "hf", "hugging-face", "hf-inference" -> ImageProtocol.HUGGINGFACE
    else -> ImageProtocol.entries.firstOrNull {
        it.source.equals(source.trim(), true) || it.name.equals(source.trim(), true)
    }
}

internal fun suggestedImageModels(protocol: ImageProtocol): List<String> = when (protocol) {
    ImageProtocol.POLLINATIONS -> listOf("flux", "turbo", "flux-realism", "flux-anime", "flux-3d", "gptimage")
    ImageProtocol.STABILITY -> listOf(
        "sd3.5-large", "sd3.5-large-turbo", "sd3.5-medium", "ultra", "core", "stable-diffusion-xl-1024-v1-0",
    )
    ImageProtocol.TOGETHER -> listOf(
        "black-forest-labs/FLUX.1-schnell-Free",
        "black-forest-labs/FLUX.1-schnell",
        "black-forest-labs/FLUX.1.1-pro",
        "stabilityai/stable-diffusion-xl-base-1.0",
    )
    ImageProtocol.IMAGEN -> listOf(
        "imagen-3.0-generate-002",
        "imagen-3.0-fast-generate-001",
        "imagen-4.0-generate-preview-06-06",
    )
    ImageProtocol.XAI -> listOf("grok-2-image", "grok-2-image-1212")
    ImageProtocol.NANOGPT -> listOf("flux-pro", "flux-schnell", "hidream", "recraft-v3", "chroma")
    ImageProtocol.HUGGINGFACE -> listOf(
        "black-forest-labs/FLUX.1-schnell",
        "black-forest-labs/FLUX.1-dev",
        "stabilityai/stable-diffusion-xl-base-1.0",
        "stabilityai/stable-diffusion-3.5-large",
    )
    else -> emptyList()
}

internal fun resolveImageProfileForSource(
    source: String,
    providers: List<Provider>,
    settings: DrawingSettings,
    providerHint: String? = null,
    modelOverride: String? = null,
): ImageProfile {
    val saved = settings.defaultProfile(providers)
    val protocol = resolveImageProtocol(source)
    val providerName = providerHint ?: source.takeIf { protocol == null }
    val candidates = if (protocol == ImageProtocol.POLLINATIONS && providerName == null) {
        providers + pollinationsBuiltinProvider()
    } else providers
    val provider = requireNotNull(
        candidates.sortedByDescending { it.id == saved?.providerId }.firstOrNull { candidate ->
            (providerName == null || candidate.id == providerName || candidate.name.equals(providerName, true)) &&
                (protocol?.available(candidate) ?: ImageProtocol.entries.any { it.available(candidate) })
        },
    ) { "请先配置对应的绘图服务" }
    val selected = protocol ?: saved?.protocol?.takeIf { it.available(provider) }
        ?: ImageProtocol.entries.first { it.available(provider) }
    val model = modelOverride?.trim() ?: selected.initialModel(provider)
    return if (saved?.providerId == provider.id && saved.protocol == selected && modelOverride == null) saved
    else ImageProfile(
        provider.id,
        selected,
        model,
        workflowId = settings.comfyWorkflowId.takeIf { selected.usesWorkflow },
    )
}

internal object ExtendedImageBackends {
    suspend fun generate(options: ImageRequest, readImage: (ChatImage) -> ByteArray?): ByteArray = when (options.protocol) {
        ImageProtocol.POLLINATIONS -> pollinations(options)
        ImageProtocol.STABILITY -> stability(options)
        ImageProtocol.DRAW_THINGS -> drawThings(options, readImage)
        ImageProtocol.SD_NEXT -> sdNext(options)
        ImageProtocol.TOGETHER -> together(options)
        ImageProtocol.IMAGEN -> imagen(options)
        ImageProtocol.XAI -> xAi(options)
        ImageProtocol.NANOGPT -> nanoGpt(options)
        ImageProtocol.HUGGINGFACE -> huggingFace(options)
        else -> error("不支持的扩展图像协议：${options.protocol.name}")
    }

    internal fun pollinationsUrl(options: ImageRequest): String {
        val rawEndpoint = options.provider.endpoint.trim().ifBlank { DEFAULT_POLLINATIONS_ENDPOINT }
        val base = checkedAddress(rawEndpoint).toString().trimEnd('/').removeSuffix("/prompt")
        val promptText = descriptivePrompt(options)
        val encodedPrompt = URLEncoder.encode(promptText, "UTF-8").replace("+", "%20")
        val width = (options.tuning.width ?: options.shape.compatibleSize.substringBefore('x').toInt()).coerceIn(128, 4096)
        val height = (options.tuning.height ?: options.shape.compatibleSize.substringAfter('x').toInt()).coerceIn(128, 4096)
        val model = options.model.trim().ifBlank { "flux" }
        val params = buildList {
            add("model=${URLEncoder.encode(model, "UTF-8")}")
            add("width=$width")
            add("height=$height")
            add("nologo=true")
            add("enhance=false")
            options.tuning.seed?.takeIf { it >= 0 }?.let { add("seed=$it") }
            options.tuning.negative.trim().takeIf(String::isNotBlank)?.let {
                add("negative_prompt=${URLEncoder.encode(it, "UTF-8")}")
            }
        }
        return "$base/prompt/$encodedPrompt?${params.joinToString("&")}"
    }

    private suspend fun pollinations(options: ImageRequest): ByteArray {
        val url = pollinationsUrl(options)
        val key = options.provider.apiKey.trim()
        val headers = buildMap {
            put("Accept", "image/*")
            if (key.isNotBlank()) put("Authorization", "Bearer $key")
        }
        return request(
            url = url,
            headers = headers,
            readTimeout = 240_000,
            followRedirects = true,
            redactError = { it.redactValue(key) },
        ) { stream, contentType ->
            readImageResponseBytes(stream, contentType, "Pollinations")
        }
    }

    internal fun stabilityEndpointAndMode(options: ImageRequest): Pair<String, Boolean> {
        val rawEndpoint = options.provider.endpoint.trim().ifBlank { DEFAULT_STABILITY_ENDPOINT }
        val base = checkedAddress(rawEndpoint).toString().trimEnd('/')
            .removeSuffix("/v2beta/stable-image/generate/ultra")
            .removeSuffix("/v2beta/stable-image/generate/core")
            .removeSuffix("/v2beta/stable-image/generate/sd3")
        val model = options.model.trim().ifBlank { "sd3.5-large" }
        val lower = model.lowercase(Locale.ROOT)
        return when {
            lower == "ultra" -> "$base/v2beta/stable-image/generate/ultra" to true
            lower == "core" -> "$base/v2beta/stable-image/generate/core" to true
            lower.startsWith("sd3") -> "$base/v2beta/stable-image/generate/sd3" to true
            else -> "$base/v1/generation/${URLEncoder.encode(model, "UTF-8")}/text-to-image" to false
        }
    }

    internal fun buildStabilityMultipart(options: ImageRequest, boundary: String): ByteArray {
        val model = options.model.trim().ifBlank { "sd3.5-large" }
        val lower = model.lowercase(Locale.ROOT)
        return ByteArrayOutputStream().apply {
            fun field(name: String, value: String) {
                write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
                write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(Charsets.UTF_8))
                write(value.toByteArray(Charsets.UTF_8))
                write("\r\n".toByteArray(Charsets.UTF_8))
            }
            field("prompt", options.prompt)
            field("output_format", "png")
            field("aspect_ratio", stabilityAspectRatio(options.shape, options.tuning))
            if (lower.startsWith("sd3")) field("model", model)
            options.tuning.negative.trim().takeIf(String::isNotBlank)?.let { field("negative_prompt", it) }
            options.tuning.seed?.takeIf { it >= 0 }?.let { field("seed", it.toString()) }
            options.tuning.cfg?.let { field("cfg_scale", it.toString()) }
            write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        }.toByteArray()
    }

    internal fun buildStabilityV1Payload(options: ImageRequest): JSONObject {
        val prompts = JSONArray().put(jsonOf("text" to options.prompt, "weight" to 1.0))
        options.tuning.negative.trim().takeIf(String::isNotBlank)?.let {
            prompts.put(jsonOf("text" to it, "weight" to -1.0))
        }
        val width = (options.tuning.width ?: options.shape.compatibleSize.substringBefore('x').toInt()).coerceIn(512, 2048)
        val height = (options.tuning.height ?: options.shape.compatibleSize.substringAfter('x').toInt()).coerceIn(512, 2048)
        return jsonOf(
            "text_prompts" to prompts,
            "width" to width,
            "height" to height,
            "samples" to 1,
        ).apply {
            options.tuning.steps?.let { put("steps", it) }
            options.tuning.cfg?.let { put("cfg_scale", it) }
            options.tuning.seed?.takeIf { it >= 0 }?.let { put("seed", it) }
            options.tuning.sampler.takeIf(String::isNotBlank)?.let { put("sampler", it) }
        }
    }

    private suspend fun stability(options: ImageRequest): ByteArray {
        val key = options.provider.apiKey.trim()
        require(key.isNotBlank()) { "请填写 Stability AI API Key" }
        val (url, isV2Beta) = stabilityEndpointAndMode(options)
        return if (isV2Beta) {
            val boundary = "----TavernStability${UUID.randomUUID().toString().replace("-", "")}"
            val bytes = buildStabilityMultipart(options, boundary)
            request(
                url = url,
                headers = mapOf("Authorization" to "Bearer $key", "Accept" to "image/*"),
                readTimeout = 240_000,
                upload = HttpUpload("multipart/form-data; boundary=$boundary", bytes),
                redactError = { it.redactValue(key) },
            ) { stream, contentType ->
                readImageResponseBytes(stream, contentType, "Stability AI")
            }
        } else {
            val body = buildStabilityV1Payload(options)
            val result = request(
                url = url,
                body = body,
                headers = mapOf("Authorization" to "Bearer $key", "Accept" to "application/json"),
                readTimeout = 240_000,
                redactError = { it.redactValue(key) },
            ) { stream, _ -> JSONObject(stream.readLimited().toString(Charsets.UTF_8)) }
            val encoded = result.optJSONArray("artifacts")?.optJSONObject(0)?.string("base64").orEmpty()
            decodeGeneratedImage(encoded)
        }
    }

    internal fun buildDrawThingsPayload(options: ImageRequest, initImages: List<String> = emptyList()): JSONObject {
        val tuning = options.tuning
        return jsonOf(
            "prompt" to options.prompt,
            "width" to (tuning.width ?: options.shape.width),
            "height" to (tuning.height ?: options.shape.height),
            "batch_size" to 1,
        ).apply {
            tuning.negative.takeIf(String::isNotBlank)?.let { put("negative_prompt", it) }
            tuning.steps?.let { put("steps", it) }
            tuning.cfg?.let {
                put("cfg_scale", it)
                put("guidance_scale", it)
            }
            tuning.seed?.let { put("seed", it) }
            tuning.sampler.takeIf(String::isNotBlank)?.let { put("sampler", it) }
            options.model.trim().takeIf(String::isNotBlank)?.let { put("model", it) }
            if (initImages.isNotEmpty()) {
                put("init_images", JSONArray(initImages))
                put("denoising_strength", tuning.denoise ?: 0.7)
            }
        }
    }

    private suspend fun drawThings(options: ImageRequest, readImage: (ChatImage) -> ByteArray?): ByteArray {
        val base = checkedAddress(options.provider.endpoint).toString().trimEnd('/')
            .removeSuffix("/sdapi/v1/txt2img")
            .removeSuffix("/sdapi/v1/img2img")
        val key = options.provider.apiKey.trim()
        val headers = authHeaders(key)
        val initImages = options.references.take(1).mapNotNull { ref ->
            readImage(ref)?.let { Base64.getEncoder().encodeToString(it) }
        }
        val path = if (initImages.isNotEmpty()) "/sdapi/v1/img2img" else "/sdapi/v1/txt2img"
        val body = buildDrawThingsPayload(options, initImages)
        val response = request(
            url = "$base$path",
            body = body,
            headers = headers,
            readTimeout = 240_000,
            redactError = { it.redactValue(key) },
        ) { stream, _ -> JSONObject(stream.readLimited().toString(Charsets.UTF_8)) }
        return decodeGeneratedImage(response.optJSONArray("images")?.optString(0).orEmpty())
    }

    internal fun buildSdNextPayload(options: ImageRequest): JSONObject {
        val tuning = options.tuning
        return jsonOf(
            "prompt" to options.prompt,
            "width" to (tuning.width ?: options.shape.width),
            "height" to (tuning.height ?: options.shape.height),
            "batch_size" to 1,
            "save_images" to false,
            "send_images" to true,
        ).apply {
            tuning.negative.takeIf(String::isNotBlank)?.let { put("negative_prompt", it) }
            tuning.steps?.let { put("steps", it) }
            tuning.cfg?.let { put("cfg_scale", it) }
            tuning.seed?.let { put("seed", it) }
            tuning.sampler.takeIf(String::isNotBlank)?.let { put("sampler_name", it) }
            tuning.scheduler.takeIf(String::isNotBlank)?.let { put("scheduler", it) }
            val overrides = JSONObject()
            options.model.trim().takeIf(String::isNotBlank)?.let { overrides.put("sd_model_checkpoint", it) }
            tuning.vae.trim().takeIf(String::isNotBlank)?.let { overrides.put("sd_vae", it) }
            tuning.skip?.let { overrides.put("CLIP_stop_at_last_layers", it) }
            if (overrides.length() > 0) {
                put("override_settings", overrides)
                put("override_settings_restore_afterwards", true)
            }
        }
    }

    private suspend fun sdNext(options: ImageRequest): ByteArray {
        val base = checkedAddress(options.provider.endpoint).toString().trimEnd('/')
            .removeSuffix("/sdapi/v1/txt2img")
        val key = options.provider.apiKey.trim()
        val headers = authHeaders(key)
        val body = buildSdNextPayload(options)
        val response = request(
            url = "$base/sdapi/v1/txt2img",
            body = body,
            headers = headers,
            readTimeout = 240_000,
            redactError = { it.redactValue(key) },
        ) { stream, _ -> JSONObject(stream.readLimited().toString(Charsets.UTF_8)) }
        return decodeGeneratedImage(response.optJSONArray("images")?.optString(0).orEmpty())
    }

    internal fun buildTogetherPayload(options: ImageRequest): JSONObject {
        val tuning = options.tuning
        val width = (tuning.width ?: options.shape.compatibleSize.substringBefore('x').toInt()).coerceIn(256, 2048)
        val height = (tuning.height ?: options.shape.compatibleSize.substringAfter('x').toInt()).coerceIn(256, 2048)
        val model = options.model.trim().ifBlank { "black-forest-labs/FLUX.1-schnell-Free" }
        return jsonOf(
            "model" to model,
            "prompt" to options.prompt,
            "width" to width,
            "height" to height,
            "n" to 1,
            "response_format" to "b64_json",
        ).apply {
            tuning.negative.trim().takeIf(String::isNotBlank)?.let { put("negative_prompt", it) }
            tuning.steps?.let { put("steps", it) }
            tuning.seed?.takeIf { it >= 0 }?.let { put("seed", it) }
        }
    }

    private suspend fun together(options: ImageRequest): ByteArray {
        val key = options.provider.apiKey.trim()
        require(key.isNotBlank()) { "请填写 Together AI API Key" }
        val rawEndpoint = options.provider.endpoint.trim().ifBlank { DEFAULT_TOGETHER_ENDPOINT }
        val endpoint = checkedAddress(rawEndpoint)
        val base = endpoint.toString().trimEnd('/')
            .removeSuffix("/chat/completions")
            .removeSuffix("/images/generations")
        val result = request(
            url = "$base/images/generations",
            body = buildTogetherPayload(options),
            headers = mapOf("Authorization" to "Bearer $key", "Accept" to "application/json"),
            readTimeout = 240_000,
            redactError = { it.redactValue(key) },
        ) { stream, _ -> JSONObject(stream.readLimited().toString(Charsets.UTF_8)) }
        return extractOpenAiStyleImage(result, endpoint)
    }

    internal fun buildImagenRequest(options: ImageRequest): Triple<String, JSONObject, Map<String, String>> {
        val key = options.provider.apiKey.trim()
        require(key.isNotBlank()) { "请填写 Google Imagen / Vertex API Key 或访问令牌" }
        val rawEndpoint = options.provider.endpoint.trim().ifBlank { DEFAULT_IMAGEN_ENDPOINT }
        val base = checkedAddress(rawEndpoint).toString().trimEnd('/')
            .removeSuffix("/openai")
            .removeSuffix("/chat/completions")
        val model = options.model.trim().removePrefix("models/").ifBlank { "imagen-3.0-generate-002" }
        val isVertex = "aiplatform.googleapis.com" in base.lowercase(Locale.ROOT) || base.endsWith(":predict")
        val url = when {
            base.endsWith(":predict") -> base
            isVertex && "/publishers/google/models/" in base -> "$base:predict"
            isVertex -> "$base/publishers/google/models/$model:predict"
            else -> "$base/models/$model:predict"
        }
        val headers = if (isVertex) {
            mapOf("Authorization" to "Bearer $key", "Accept" to "application/json")
        } else {
            mapOf("x-goog-api-key" to key, "Accept" to "application/json")
        }
        val parameters = jsonOf(
            "sampleCount" to 1,
            "aspectRatio" to imagenAspectRatio(options.shape),
        ).apply {
            options.tuning.negative.trim().takeIf(String::isNotBlank)?.let { put("negativePrompt", it) }
            options.tuning.seed?.takeIf { it >= 0 }?.let { put("seed", it) }
        }
        val body = jsonOf(
            "instances" to JSONArray().put(jsonOf("prompt" to options.prompt)),
            "parameters" to parameters,
        )
        return Triple(url, body, headers)
    }

    internal fun parseImagenResponse(result: JSONObject): ByteArray {
        val prediction = result.optJSONArray("predictions")?.optJSONObject(0)
        val b64 = prediction?.string("bytesBase64Encoded").orEmpty()
            .ifBlank {
                result.optJSONArray("generatedImages")?.optJSONObject(0)
                    ?.optJSONObject("image")?.string("imageBytes").orEmpty()
            }
        return decodeGeneratedImage(b64)
    }

    private suspend fun imagen(options: ImageRequest): ByteArray {
        val key = options.provider.apiKey.trim()
        val (url, body, headers) = buildImagenRequest(options)
        val result = request(
            url = url,
            body = body,
            headers = headers,
            readTimeout = 240_000,
            redactError = { it.redactValue(key) },
        ) { stream, _ -> JSONObject(stream.readLimited().toString(Charsets.UTF_8)) }
        return parseImagenResponse(result)
    }

    internal fun buildXAiPayload(options: ImageRequest): JSONObject {
        val model = options.model.trim().ifBlank { "grok-2-image" }
        return jsonOf(
            "model" to model,
            "prompt" to descriptivePrompt(options),
            "n" to 1,
            "response_format" to "b64_json",
        )
    }

    private suspend fun xAi(options: ImageRequest): ByteArray {
        val key = options.provider.apiKey.trim()
        require(key.isNotBlank()) { "请填写 xAI API Key" }
        val rawEndpoint = options.provider.endpoint.trim().ifBlank { DEFAULT_XAI_ENDPOINT }
        val endpoint = checkedAddress(rawEndpoint)
        val base = endpoint.toString().trimEnd('/')
            .removeSuffix("/chat/completions")
            .removeSuffix("/images/generations")
        val result = request(
            url = "$base/images/generations",
            body = buildXAiPayload(options),
            headers = mapOf("Authorization" to "Bearer $key", "Accept" to "application/json"),
            readTimeout = 240_000,
            redactError = { it.redactValue(key) },
        ) { stream, _ -> JSONObject(stream.readLimited().toString(Charsets.UTF_8)) }
        return extractOpenAiStyleImage(result, endpoint)
    }

    internal fun buildNanoGptPayload(options: ImageRequest): JSONObject {
        val tuning = options.tuning
        val width = (tuning.width ?: options.shape.compatibleSize.substringBefore('x').toInt()).coerceIn(256, 2048)
        val height = (tuning.height ?: options.shape.compatibleSize.substringAfter('x').toInt()).coerceIn(256, 2048)
        val model = options.model.trim().ifBlank { "flux-pro" }
        return jsonOf(
            "prompt" to options.prompt,
            "model" to model,
            "width" to width,
            "height" to height,
            "nImages" to 1,
        ).apply {
            tuning.negative.trim().takeIf(String::isNotBlank)?.let { put("negative_prompt", it) }
            tuning.steps?.let { put("steps", it) }
            tuning.cfg?.let { put("scale", it) }
            tuning.seed?.takeIf { it >= 0 }?.let { put("seed", it) }
        }
    }

    private suspend fun nanoGpt(options: ImageRequest): ByteArray {
        val key = options.provider.apiKey.trim()
        require(key.isNotBlank()) { "请填写 NanoGPT API Key" }
        val rawEndpoint = options.provider.endpoint.trim().ifBlank { DEFAULT_NANOGPT_ENDPOINT }
        val endpoint = checkedAddress(rawEndpoint)
        val base = endpoint.toString().trimEnd('/')
            .removeSuffix("/v1/chat/completions")
            .removeSuffix("/chat/completions")
            .removeSuffix("/generate-image")
            .removeSuffix("/v1")
        val result = request(
            url = "$base/generate-image",
            body = buildNanoGptPayload(options),
            headers = mapOf("x-api-key" to key, "Accept" to "application/json"),
            readTimeout = 240_000,
            redactError = { it.redactValue(key) },
        ) { stream, _ -> JSONObject(stream.readLimited().toString(Charsets.UTF_8)) }
        val direct = result.string("image").ifBlank { result.optJSONArray("images")?.optString(0).orEmpty() }
        return if (direct.isNotBlank()) decodeGeneratedImage(direct) else extractOpenAiStyleImage(result, endpoint)
    }

    internal fun buildHuggingFaceRequest(options: ImageRequest): Pair<String, JSONObject> {
        val model = options.model.trim().ifBlank { "black-forest-labs/FLUX.1-schnell" }
        val rawEndpoint = options.provider.endpoint.trim().ifBlank { DEFAULT_HUGGINGFACE_ENDPOINT }
        val base = checkedAddress(rawEndpoint).toString().trimEnd('/')
        val url = if (base.endsWith("/$model") || base.endsWith(model)) base else "$base/$model"
        val tuning = options.tuning
        val parameters = JSONObject().apply {
            tuning.negative.trim().takeIf(String::isNotBlank)?.let { put("negative_prompt", it) }
            tuning.width?.let { put("width", it) }
            tuning.height?.let { put("height", it) }
            tuning.steps?.let { put("num_inference_steps", it) }
            tuning.cfg?.let { put("guidance_scale", it) }
            tuning.seed?.takeIf { it >= 0 }?.let { put("seed", it) }
        }
        val body = jsonOf("inputs" to options.prompt).apply {
            if (parameters.length() > 0) put("parameters", parameters)
        }
        return url to body
    }

    private suspend fun huggingFace(options: ImageRequest): ByteArray {
        val key = options.provider.apiKey.trim()
        require(key.isNotBlank()) { "请填写 HuggingFace Access Token" }
        val (url, body) = buildHuggingFaceRequest(options)
        return request(
            url = url,
            body = body,
            headers = mapOf(
                "Authorization" to "Bearer $key",
                "Accept" to "image/png, image/jpeg, image/webp, application/json",
            ),
            readTimeout = 240_000,
            redactError = { it.redactValue(key) },
        ) { stream, contentType ->
            if (contentType.startsWith("application/json", true)) {
                val json = JSONObject(stream.readLimited().toString(Charsets.UTF_8))
                val b64 = json.string("image")
                    .ifBlank { json.optJSONArray("images")?.optString(0).orEmpty() }
                    .ifBlank { json.optJSONArray("data")?.optJSONObject(0)?.string("b64_json").orEmpty() }
                decodeGeneratedImage(b64)
            } else {
                readImageResponseBytes(stream, contentType, "HuggingFace")
            }
        }
    }

    private suspend fun extractOpenAiStyleImage(result: JSONObject, origin: URI): ByteArray {
        val first = result.optJSONArray("data")?.optJSONObject(0)
        val encoded = first?.string("b64_json").orEmpty()
        if (encoded.isNotBlank()) return decodeGeneratedImage(encoded)
        val url = first?.string("url").orEmpty()
        check(url.isNotBlank()) { "图像服务未返回图片" }
        val uri = URI(url)
        val sameOrigin = uri.scheme == origin.scheme && uri.host.equals(origin.host, true) && uri.port == origin.port
        require(
            uri.host != null && uri.rawUserInfo == null && uri.rawFragment == null &&
                (uri.scheme == "https" || uri.scheme == "http" && sameOrigin),
        ) { "图像服务返回了无效的图片地址" }
        return request(uri.toString(), headers = mapOf("Accept" to "image/*")) { stream, _ ->
            stream.readLimited(MAX_IMAGE_SOURCE)
        }
    }

    private fun readImageResponseBytes(stream: java.io.InputStream, contentType: String, service: String): ByteArray {
        check(!contentType.startsWith("application/json", true) && !contentType.startsWith("text/", true)) {
            "$service 返回了非图片响应"
        }
        return stream.readLimited(MAX_IMAGE_SOURCE).also {
            check(it.isNotEmpty()) { "$service 未返回图片数据" }
        }
    }

    private fun authHeaders(key: String): Map<String, String> = when {
        key.isBlank() -> emptyMap()
        ':' in key -> mapOf("Authorization" to "Basic ${Base64.getEncoder().encodeToString(key.toByteArray())}")
        else -> mapOf("Authorization" to "Bearer $key")
    }

    private fun descriptivePrompt(options: ImageRequest): String =
        if (options.tuning.negative.isBlank()) options.prompt
        else "${options.prompt}\nAvoid these visual elements: ${options.tuning.negative}"

    private fun stabilityAspectRatio(shape: ImageShape, tuning: ImageTuning): String {
        val w = tuning.width
        val h = tuning.height
        if (w != null && h != null) {
            val r = w.toDouble() / h.toDouble()
            return when {
                r >= 1.65 -> "16:9"
                r >= 1.35 -> "3:2"
                r >= 1.15 -> "5:4"
                r <= 0.6 -> "9:16"
                r <= 0.75 -> "2:3"
                r <= 0.88 -> "4:5"
                else -> "1:1"
            }
        }
        return shape.ratio
    }

    private fun imagenAspectRatio(shape: ImageShape): String = when (shape) {
        ImageShape.SQUARE -> "1:1"
        ImageShape.PORTRAIT -> "3:4"
        ImageShape.LANDSCAPE -> "4:3"
    }
}
