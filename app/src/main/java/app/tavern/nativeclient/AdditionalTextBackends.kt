package app.tavern.nativeclient

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

internal data class AdditionalTextBackendSpec(
    val protocol: ApiProtocol,
    val displayName: String,
    val defaultEndpoint: String,
    val defaultModel: String,
    val starterModels: List<String>,
    val description: String,
    val supportsLogitBias: Boolean = true,
    val supportsLogprobs: Boolean = true,
    val supportsRemoteTokenCount: Boolean = false,
)

internal val ApiProtocol.isAdditionalTextBackend: Boolean
    get() = AdditionalTextBackends.specFor(this) != null

internal object AdditionalTextBackends {
    private val specs: Map<ApiProtocol, AdditionalTextBackendSpec> = listOf(
        AdditionalTextBackendSpec(
            protocol = ApiProtocol.TABBY_TEXT,
            displayName = "TabbyAPI",
            defaultEndpoint = "http://127.0.0.1:5000/v1",
            defaultModel = "default",
            starterModels = listOf("default"),
            description = "ExLlamaV2 / TabbyAPI 本地服务，支持在线加载与卸载模型。",
            supportsLogitBias = true,
            supportsLogprobs = true,
            supportsRemoteTokenCount = true,
        ),
        AdditionalTextBackendSpec(
            protocol = ApiProtocol.VLLM_TEXT,
            displayName = "vLLM",
            defaultEndpoint = "http://127.0.0.1:8000/v1",
            defaultModel = "default",
            starterModels = listOf("default"),
            description = "高性能 vLLM 推理引擎（/v1/completions 与 /tokenize）。",
            supportsLogitBias = true,
            supportsLogprobs = true,
            supportsRemoteTokenCount = true,
        ),
        AdditionalTextBackendSpec(
            protocol = ApiProtocol.APHRODITE_TEXT,
            displayName = "Aphrodite Engine",
            defaultEndpoint = "http://127.0.0.1:2242/v1",
            defaultModel = "default",
            starterModels = listOf("default"),
            description = "Aphrodite 推理引擎，支持 DRY、XTC、Mirostat、动态温度与结构化输出。",
            supportsLogitBias = true,
            supportsLogprobs = true,
        ),
        AdditionalTextBackendSpec(
            protocol = ApiProtocol.OOBABOOGA_TEXT,
            displayName = "text-generation-webui (Oobabooga)",
            defaultEndpoint = "http://127.0.0.1:5000/v1",
            defaultModel = "default",
            starterModels = listOf("default"),
            description = "Oobabooga OpenAI 兼容扩展接口，完整映射 DRY/XTC/GBNF 采样参数。",
            supportsLogitBias = true,
            supportsLogprobs = true,
            supportsRemoteTokenCount = true,
        ),
        AdditionalTextBackendSpec(
            protocol = ApiProtocol.FEATHERLESS_TEXT,
            displayName = "Featherless AI",
            defaultEndpoint = "https://api.featherless.ai/v1",
            defaultModel = "meta-llama/Meta-Llama-3.1-8B-Instruct",
            starterModels = listOf(
                "meta-llama/Meta-Llama-3.1-8B-Instruct",
                "mistralai/Mistral-Nemo-Instruct-2407",
                "Qwen/Qwen2.5-72B-Instruct",
            ),
            description = "Featherless AI 托管开源模型文本补全服务。",
            supportsLogitBias = false,
            supportsLogprobs = false,
        ),
        AdditionalTextBackendSpec(
            protocol = ApiProtocol.INFERMATIC_TEXT,
            displayName = "Infermatic AI",
            defaultEndpoint = "https://api.totalgpt.ai/v1",
            defaultModel = "Sao10K-L3-8B-Lunaris-v1",
            starterModels = listOf(
                "Sao10K-L3-8B-Lunaris-v1",
                "TheDrummer-Rocinante-12B-v1.1",
                "Midnight-Miqu-70B-v1.5",
            ),
            description = "Infermatic / TotalGPT 高速开源模型文本补全服务。",
            supportsLogitBias = false,
            supportsLogprobs = true,
        ),
        AdditionalTextBackendSpec(
            protocol = ApiProtocol.DREAMGEN_TEXT,
            displayName = "DreamGen",
            defaultEndpoint = "https://dreamgen.com/api/openai/v1",
            defaultModel = "lucid-v1-medium",
            starterModels = listOf("lucid-v1-medium", "lucid-v1-extra-large", "opus-v1-xl"),
            description = "DreamGen 故事与角色扮演专用文本补全接口。",
            supportsLogitBias = false,
            supportsLogprobs = false,
        ),
        AdditionalTextBackendSpec(
            protocol = ApiProtocol.MANCER_TEXT,
            displayName = "Mancer",
            defaultEndpoint = "https://neuro.mancer.tech/oai/v1",
            defaultModel = "mytholite",
            starterModels = listOf("mytholite", "weaver", "goliath-120b"),
            description = "Mancer 角色扮演模型服务（/oai/v1/completions）。",
            supportsLogitBias = true,
            supportsLogprobs = false,
        ),
        AdditionalTextBackendSpec(
            protocol = ApiProtocol.HUGGINGFACE_TEXT,
            displayName = "HuggingFace 文本推理",
            defaultEndpoint = "https://api-inference.huggingface.co",
            defaultModel = "mistralai/Mistral-Nemo-Instruct-2407",
            starterModels = listOf(
                "mistralai/Mistral-Nemo-Instruct-2407",
                "Qwen/Qwen2.5-72B-Instruct",
                "meta-llama/Llama-3.1-8B-Instruct",
                "google/gemma-2-9b-it",
            ),
            description = "HuggingFace Inference API 与自托管 TGI（Text Generation Inference）原生协议。",
            supportsLogitBias = false,
            supportsLogprobs = false,
        ),
    ).associateBy(AdditionalTextBackendSpec::protocol)

    val allSpecs: List<AdditionalTextBackendSpec> get() = specs.values.toList()

    fun specFor(protocol: ApiProtocol): AdditionalTextBackendSpec? = specs[protocol]

    fun defaultEndpoint(protocol: ApiProtocol): String? = specs[protocol]?.defaultEndpoint

    fun defaultModel(protocol: ApiProtocol): String? = specs[protocol]?.defaultModel

    fun createProviderDraft(spec: AdditionalTextBackendSpec, id: String = newId()): Provider = Provider(
        id = id,
        name = spec.displayName,
        endpoint = spec.defaultEndpoint,
        defaultModel = spec.defaultModel,
        models = spec.starterModels,
        protocol = spec.protocol,
        streaming = true,
    )

    internal fun v1Base(base: String): String {
        val clean = base.trimEnd('/').removeSuffix("/completions")
        return when {
            clean.endsWith("/v1") || "/v1/" in clean -> clean
            else -> "$clean/v1"
        }
    }

    internal fun rootAddress(base: String): String =
        base.trimEnd('/')
            .removeSuffix("/completions")
            .removeSuffix("/generate_stream")
            .removeSuffix("/generate")
            .removeSuffix("/v1")

    fun buildHeaders(provider: Provider): Map<String, String> = buildMap {
        val key = provider.apiKey.trim()
        if (key.isNotEmpty()) {
            put("Authorization", "Bearer $key")
            if (provider.protocol == ApiProtocol.TABBY_TEXT) {
                put("x-api-key", key)
                put("x-admin-key", key)
            }
        }
    }

    fun buildCompletionRequest(
        provider: Provider,
        payload: JSONObject,
        promptText: String,
        stops: List<String>,
        sendStops: Boolean,
    ): Pair<String, JSONObject> {
        val base = ProviderProtocols.baseAddress(provider)
        val streaming = payload.optBoolean("stream", true)
        val maxTokens = payload.optInt("max_tokens", 0).takeIf { it > 0 } ?: 512
        val sampler = payload.optJSONObject(TEXT_SAMPLER_PAYLOAD)
        val model = payload.string("model").ifBlank { provider.defaultModel }

        if (provider.protocol == ApiProtocol.HUGGINGFACE_TEXT) {
            return buildHuggingFaceRequest(base, model, promptText, payload, sampler, maxTokens, streaming, stops, sendStops)
        }

        val url = "${v1Base(base)}/completions"
        val body = jsonOf(
            "model" to model,
            "prompt" to promptText,
            "max_tokens" to maxTokens,
            "stream" to streaming,
        )
        if (payload.has("temperature")) body.put("temperature", payload.optDouble("temperature"))
        if (payload.has("top_p")) body.put("top_p", payload.optDouble("top_p"))
        if (payload.has("seed")) body.put("seed", payload.optLong("seed"))

        applyAdditionalTextSampler(provider.protocol, sampler, body, ModelConnection(provider, model))

        val spec = specFor(provider.protocol)
        if (spec?.supportsLogitBias == true) {
            payload.optJSONObject(LOGIT_BIAS)?.let { body.put(LOGIT_BIAS, it) }
        }
        if (spec?.supportsLogprobs == true && payload.has("logprobs")) {
            body.put("logprobs", payload.optInt("logprobs"))
        }
        if (sendStops && stops.isNotEmpty()) {
            val maxStops = when (provider.protocol) {
                ApiProtocol.DREAMGEN_TEXT, ApiProtocol.FEATHERLESS_TEXT -> 4
                else -> 16
            }
            body.put("stop", JSONArray(stops.take(maxStops)))
        }
        return url to body
    }

    internal fun applyAdditionalTextSampler(
        protocol: ApiProtocol,
        sampler: JSONObject?,
        target: JSONObject,
        connection: ModelConnection,
    ) {
        if (sampler == null) return
        fun copy(field: TextSamplerField, wireName: String) {
            sampler.opt(field.key)?.let { target.put(wireName, it) }
        }

        when (protocol) {
            ApiProtocol.TABBY_TEXT -> {
                copy(TextSamplerField.TOP_K, "top_k")
                copy(TextSamplerField.MIN_P, "min_p")
                copy(TextSamplerField.TOP_A, "top_a")
                copy(TextSamplerField.TFS, "tfs")
                copy(TextSamplerField.TYPICAL, "typical")
                copy(TextSamplerField.REPETITION, "repetition_penalty")
                copy(TextSamplerField.REPETITION_RANGE, "repetition_penalty_range")
                copy(TextSamplerField.FREQUENCY, "frequency_penalty")
                copy(TextSamplerField.PRESENCE, "presence_penalty")
                copy(TextSamplerField.SMOOTHING, "smoothing_factor")
                copy(TextSamplerField.MIROSTAT, "mirostat_mode")
                copy(TextSamplerField.MIROSTAT_TAU, "mirostat_tau")
                copy(TextSamplerField.MIROSTAT_ETA, "mirostat_eta")
                copy(TextSamplerField.DRY_MULTIPLIER, "dry_multiplier")
                copy(TextSamplerField.DRY_BASE, "dry_base")
                copy(TextSamplerField.DRY_LENGTH, "dry_allowed_length")
                copy(TextSamplerField.DRY_RANGE, "dry_range")
                copy(TextSamplerField.DRY_BREAKERS, "dry_sequence_breakers")
                copy(TextSamplerField.XTC_THRESHOLD, "xtc_threshold")
                copy(TextSamplerField.XTC_PROBABILITY, "xtc_probability")
                copy(TextSamplerField.NSIGMA, "nsigma")
                copy(TextSamplerField.IGNORE_EOS, "ban_eos_token")
                copy(TextSamplerField.SEED, "seed")
                sampler.optJSONObject(TextSamplerField.JSON_SCHEMA.key)?.let { target.put("json_schema", it) }
                if (sampler.optBoolean(TextSamplerField.DYNAMIC.key)) {
                    target.put("min_temp", sampler.optDouble(TextSamplerField.MIN_TEMP.key, 0.0))
                    target.put("max_temp", sampler.optDouble(TextSamplerField.MAX_TEMP.key, 2.0))
                    sampler.opt(TextSamplerField.DYNAMIC_EXPONENT.key)?.let { target.put("temperature_exponent", it) }
                }
            }
            ApiProtocol.VLLM_TEXT -> {
                copy(TextSamplerField.TOP_K, "top_k")
                copy(TextSamplerField.MIN_P, "min_p")
                copy(TextSamplerField.REPETITION, "repetition_penalty")
                copy(TextSamplerField.FREQUENCY, "frequency_penalty")
                copy(TextSamplerField.PRESENCE, "presence_penalty")
                copy(TextSamplerField.IGNORE_EOS, "ignore_eos")
                copy(TextSamplerField.SEED, "seed")
                sampler.optJSONObject(TextSamplerField.JSON_SCHEMA.key)?.let { target.put("guided_json", it) }
                sampler.optString(TextSamplerField.GRAMMAR.key).takeIf(String::isNotBlank)?.let {
                    target.put("guided_grammar", it)
                }
                target.put("skip_special_tokens", false)
            }
            ApiProtocol.APHRODITE_TEXT -> {
                copy(TextSamplerField.TOP_K, "top_k")
                copy(TextSamplerField.MIN_P, "min_p")
                copy(TextSamplerField.TOP_A, "top_a")
                copy(TextSamplerField.TFS, "tfs")
                copy(TextSamplerField.TYPICAL, "typical_p")
                copy(TextSamplerField.REPETITION, "repetition_penalty")
                copy(TextSamplerField.FREQUENCY, "frequency_penalty")
                copy(TextSamplerField.PRESENCE, "presence_penalty")
                copy(TextSamplerField.SMOOTHING, "smoothing_factor")
                copy(TextSamplerField.MIROSTAT, "mirostat_mode")
                copy(TextSamplerField.MIROSTAT_TAU, "mirostat_tau")
                copy(TextSamplerField.MIROSTAT_ETA, "mirostat_eta")
                copy(TextSamplerField.DRY_MULTIPLIER, "dry_multiplier")
                copy(TextSamplerField.DRY_BASE, "dry_base")
                copy(TextSamplerField.DRY_LENGTH, "dry_allowed_length")
                copy(TextSamplerField.DRY_RANGE, "dry_penalty_last_n")
                copy(TextSamplerField.DRY_BREAKERS, "dry_sequence_breakers")
                copy(TextSamplerField.XTC_THRESHOLD, "xtc_threshold")
                copy(TextSamplerField.XTC_PROBABILITY, "xtc_probability")
                copy(TextSamplerField.NSIGMA, "nsigma")
                copy(TextSamplerField.IGNORE_EOS, "ignore_eos")
                copy(TextSamplerField.SEED, "seed")
                sampler.optJSONObject(TextSamplerField.JSON_SCHEMA.key)?.let { target.put("guided_json", it) }
                sampler.optString(TextSamplerField.GRAMMAR.key).takeIf(String::isNotBlank)?.let {
                    target.put("guided_grammar", it)
                }
                if (sampler.optBoolean(TextSamplerField.DYNAMIC.key)) {
                    target.put("dynatemp_min", sampler.optDouble(TextSamplerField.MIN_TEMP.key, 0.0))
                    target.put("dynatemp_max", sampler.optDouble(TextSamplerField.MAX_TEMP.key, 2.0))
                    sampler.opt(TextSamplerField.DYNAMIC_EXPONENT.key)?.let { target.put("dynatemp_exponent", it) }
                }
            }
            ApiProtocol.OOBABOOGA_TEXT -> {
                copy(TextSamplerField.TOP_K, "top_k")
                copy(TextSamplerField.MIN_P, "min_p")
                copy(TextSamplerField.TOP_A, "top_a")
                copy(TextSamplerField.TFS, "tfs")
                copy(TextSamplerField.TYPICAL, "typical_p")
                copy(TextSamplerField.REPETITION, "repetition_penalty")
                copy(TextSamplerField.REPETITION_RANGE, "repetition_penalty_range")
                copy(TextSamplerField.FREQUENCY, "frequency_penalty")
                copy(TextSamplerField.PRESENCE, "presence_penalty")
                copy(TextSamplerField.SMOOTHING, "smoothing_factor")
                copy(TextSamplerField.MIROSTAT, "mirostat_mode")
                copy(TextSamplerField.MIROSTAT_TAU, "mirostat_tau")
                copy(TextSamplerField.MIROSTAT_ETA, "mirostat_eta")
                copy(TextSamplerField.DRY_MULTIPLIER, "dry_multiplier")
                copy(TextSamplerField.DRY_BASE, "dry_base")
                copy(TextSamplerField.DRY_LENGTH, "dry_allowed_length")
                copy(TextSamplerField.DRY_BREAKERS, "dry_sequence_breakers")
                copy(TextSamplerField.XTC_THRESHOLD, "xtc_threshold")
                copy(TextSamplerField.XTC_PROBABILITY, "xtc_probability")
                copy(TextSamplerField.NSIGMA, "top_n_sigma")
                copy(TextSamplerField.IGNORE_EOS, "ban_eos_token")
                copy(TextSamplerField.GRAMMAR, "grammar_string")
                copy(TextSamplerField.SEED, "seed")
                if (sampler.has(TextSamplerField.CONTEXT.key)) {
                    target.put("truncation_length", connection.contextTokens())
                }
                if (sampler.optBoolean(TextSamplerField.DYNAMIC.key)) {
                    target.put("dynamic_temperature", true)
                    target.put("dynatemp_low", sampler.optDouble(TextSamplerField.MIN_TEMP.key, 0.0))
                    target.put("dynatemp_high", sampler.optDouble(TextSamplerField.MAX_TEMP.key, 2.0))
                    sampler.opt(TextSamplerField.DYNAMIC_EXPONENT.key)?.let { target.put("dynatemp_exponent", it) }
                }
            }
            ApiProtocol.FEATHERLESS_TEXT -> {
                copy(TextSamplerField.TOP_K, "top_k")
                copy(TextSamplerField.MIN_P, "min_p")
                copy(TextSamplerField.REPETITION, "repetition_penalty")
                copy(TextSamplerField.FREQUENCY, "frequency_penalty")
                copy(TextSamplerField.PRESENCE, "presence_penalty")
                copy(TextSamplerField.TYPICAL, "typical_p")
                copy(TextSamplerField.SEED, "seed")
            }
            ApiProtocol.INFERMATIC_TEXT -> {
                copy(TextSamplerField.TOP_K, "top_k")
                copy(TextSamplerField.MIN_P, "min_p")
                copy(TextSamplerField.REPETITION, "repetition_penalty")
                copy(TextSamplerField.FREQUENCY, "frequency_penalty")
                copy(TextSamplerField.PRESENCE, "presence_penalty")
                copy(TextSamplerField.TYPICAL, "typical_p")
                copy(TextSamplerField.TFS, "tfs_z")
                copy(TextSamplerField.SMOOTHING, "smoothing_factor")
                copy(TextSamplerField.IGNORE_EOS, "ignore_eos")
                copy(TextSamplerField.SEED, "seed")
            }
            ApiProtocol.DREAMGEN_TEXT -> {
                copy(TextSamplerField.TOP_K, "top_k")
                copy(TextSamplerField.MIN_P, "min_p")
                copy(TextSamplerField.REPETITION, "repetition_penalty")
                copy(TextSamplerField.FREQUENCY, "frequency_penalty")
                copy(TextSamplerField.PRESENCE, "presence_penalty")
                copy(TextSamplerField.SEED, "seed")
            }
            ApiProtocol.MANCER_TEXT -> {
                copy(TextSamplerField.TOP_K, "top_k")
                copy(TextSamplerField.MIN_P, "min_p")
                copy(TextSamplerField.TOP_A, "top_a")
                copy(TextSamplerField.TFS, "tfs")
                copy(TextSamplerField.TYPICAL, "typical_p")
                copy(TextSamplerField.REPETITION, "repetition_penalty")
                copy(TextSamplerField.REPETITION_RANGE, "repetition_penalty_range")
                copy(TextSamplerField.FREQUENCY, "frequency_penalty")
                copy(TextSamplerField.PRESENCE, "presence_penalty")
                copy(TextSamplerField.SMOOTHING, "smoothing_factor")
                copy(TextSamplerField.MIROSTAT, "mirostat_mode")
                copy(TextSamplerField.MIROSTAT_TAU, "mirostat_tau")
                copy(TextSamplerField.MIROSTAT_ETA, "mirostat_eta")
                copy(TextSamplerField.SEED, "seed")
                if (sampler.optBoolean(TextSamplerField.DYNAMIC.key)) {
                    val min = sampler.optDouble(TextSamplerField.MIN_TEMP.key, 0.0)
                    val max = sampler.optDouble(TextSamplerField.MAX_TEMP.key, 2.0)
                    target.put("temperature", (min + max) / 2.0)
                    target.put("dynatemp_range", (max - min) / 2.0)
                    sampler.opt(TextSamplerField.DYNAMIC_EXPONENT.key)?.let { target.put("dynatemp_exponent", it) }
                }
            }
            else -> Unit
        }
    }

    internal fun buildHuggingFaceRequest(
        base: String,
        model: String,
        promptText: String,
        payload: JSONObject,
        sampler: JSONObject?,
        maxTokens: Int,
        streaming: Boolean,
        stops: List<String>,
        sendStops: Boolean,
    ): Pair<String, JSONObject> {
        val clean = base.trimEnd('/')
        val isHostedHf = "huggingface.co" in clean.lowercase() || "/models/" in clean
        val url = when {
            clean.endsWith("/generate_stream") || clean.endsWith("/generate") -> {
                val root = clean.removeSuffix("/generate_stream").removeSuffix("/generate")
                if (streaming) "$root/generate_stream" else "$root/generate"
            }
            "/models/" in clean -> clean
            isHostedHf -> "$clean/models/${model.trim().removePrefix("/")}"
            else -> if (streaming) "$clean/generate_stream" else "$clean/generate"
        }

        val parameters = jsonOf(
            "max_new_tokens" to maxTokens,
            "return_full_text" to false,
            "do_sample" to true,
        )
        if (payload.has("temperature")) {
            val t = payload.optDouble("temperature")
            if (t <= 0.0) {
                parameters.put("do_sample", false)
            } else {
                parameters.put("temperature", t)
            }
        }
        if (payload.has("top_p")) {
            val topP = payload.optDouble("top_p")
            // TGI/HF requires top_p strictly in (0, 1) when set.
            if (topP > 0.0 && topP < 1.0) parameters.put("top_p", topP)
        }
        if (payload.has("seed")) parameters.put("seed", payload.optLong("seed"))
        if (sampler != null) {
            sampler.optInt(TextSamplerField.TOP_K.key, 0).takeIf { it > 0 }?.let { parameters.put("top_k", it) }
            sampler.opt(TextSamplerField.REPETITION.key)?.let { parameters.put("repetition_penalty", it) }
            sampler.opt(TextSamplerField.FREQUENCY.key)?.let { parameters.put("frequency_penalty", it) }
            sampler.opt(TextSamplerField.TYPICAL.key)?.let { parameters.put("typical_p", it) }
            sampler.opt(TextSamplerField.SEED.key)?.let { parameters.put("seed", it) }
        }
        if (sendStops && stops.isNotEmpty()) {
            parameters.put("stop", JSONArray(stops.take(4)))
        }
        val body = jsonOf(
            "inputs" to promptText,
            "parameters" to parameters,
            "stream" to streaming,
        )
        return url to body
    }

    suspend fun discoverModels(provider: Provider, base: String): DiscoveredModels {
        val headers = buildHeaders(provider)
        val redact: (String) -> String = { it.redactProviderSecrets(provider) }
        return when (provider.protocol) {
            ApiProtocol.TABBY_TEXT -> discoverTabbyModels(base, headers, redact)
            ApiProtocol.OOBABOOGA_TEXT -> discoverOobaboogaModels(base, headers, redact)
            ApiProtocol.HUGGINGFACE_TEXT -> discoverHuggingFaceModels(base, headers, redact)
            else -> {
                val url = "${v1Base(base)}/models"
                request(url, headers = headers, readTimeout = 15_000, redactError = redact) { input, _ ->
                    val raw = input.readLimited().decodeToString().trim()
                    if (raw.startsWith('[')) {
                        val arr = JSONArray(raw)
                        val ids = arr.objects().mapNotNull { it.string("id").ifBlank { it.string("name") }.trim().takeIf(String::isNotBlank) }
                        DiscoveredModels(ids.distinct().sorted(), emptyMap())
                    } else {
                        DiscoveredModels.read(JSONObject(raw))
                    }
                }
            }
        }
    }

    private suspend fun discoverTabbyModels(
        base: String,
        headers: Map<String, String>,
        redact: (String) -> String,
    ): DiscoveredModels {
        val v1 = v1Base(base)
        val ids = linkedSetOf<String>()
        val contexts = linkedMapOf<String, Int>()
        try {
            request("$v1/model", headers = headers, readTimeout = 10_000, redactError = redact) { input, _ ->
                val status = TabbyModelClient.parseCurrentModel(JSONObject(input.readLimited().decodeToString()))
                status.loadedModel?.let { id ->
                    ids += id
                    status.maxSeqLen?.takeIf { it in 1..MAX_CONTEXT_TOKENS }?.let { contexts[id] = it }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // No model may currently be loaded on TabbyAPI; continue to list available models.
        }
        try {
            request("$v1/model/list", headers = headers, readTimeout = 10_000, redactError = redact) { input, _ ->
                ids += TabbyModelClient.parseModelList(JSONObject(input.readLimited().decodeToString()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            request("$v1/models", headers = headers, readTimeout = 10_000, redactError = redact) { input, _ ->
                val found = DiscoveredModels.read(JSONObject(input.readLimited().decodeToString()))
                ids += found.ids
                contexts += found.contexts
            }
        }
        return DiscoveredModels(ids.sorted(), contexts)
    }

    private suspend fun discoverOobaboogaModels(
        base: String,
        headers: Map<String, String>,
        redact: (String) -> String,
    ): DiscoveredModels {
        val v1 = v1Base(base)
        return try {
            request("$v1/models", headers = headers, readTimeout = 12_000, redactError = redact) { input, _ ->
                DiscoveredModels.read(JSONObject(input.readLimited().decodeToString()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            request("$v1/internal/model/info", headers = headers, readTimeout = 10_000, redactError = redact) { input, _ ->
                val json = JSONObject(input.readLimited().decodeToString())
                val name = json.string("model_name").trim()
                DiscoveredModels(if (name.isNotBlank()) listOf(name) else emptyList(), emptyMap())
            }
        }
    }

    private suspend fun discoverHuggingFaceModels(
        base: String,
        headers: Map<String, String>,
        redact: (String) -> String,
    ): DiscoveredModels {
        val root = rootAddress(base)
        // Self-hosted TGI exposes GET /info with model_id and max_total_tokens.
        if ("huggingface.co" !in root.lowercase()) {
            return try {
                request("$root/info", headers = headers, readTimeout = 10_000, redactError = redact) { input, _ ->
                    val json = JSONObject(input.readLimited().decodeToString())
                    val id = json.string("model_id").trim()
                    val maxTotal = json.optInt("max_total_tokens").takeIf { it in 1..MAX_CONTEXT_TOKENS }
                    val contexts = if (id.isNotBlank() && maxTotal != null) mapOf(id to maxTotal) else emptyMap()
                    DiscoveredModels(if (id.isNotBlank()) listOf(id) else emptyList(), contexts)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                request("$root/v1/models", headers = headers, readTimeout = 10_000, redactError = redact) { input, _ ->
                    DiscoveredModels.read(JSONObject(input.readLimited().decodeToString()))
                }
            }
        }
        val starters = specFor(ApiProtocol.HUGGINGFACE_TEXT)?.starterModels.orEmpty()
        return DiscoveredModels(starters, emptyMap())
    }

    suspend fun inspectRemoteTokens(connection: ModelConnection, text: String): TokenPreview {
        val provider = connection.provider
        val base = ProviderProtocols.baseAddress(provider)
        val headers = buildHeaders(provider)
        val redact: (String) -> String = { it.redactProviderSecrets(provider) }
        return when (provider.protocol) {
            ApiProtocol.TABBY_TEXT -> {
                val body = jsonOf("text" to text, "add_bos_token" to false)
                request("${v1Base(base)}/token/encode", body, headers, readTimeout = 10_000, redactError = redact) { input, _ ->
                    val json = JSONObject(input.readLimited(4 * 1024 * 1024).decodeToString())
                    val arr = json.optJSONArray("tokens")
                    val pieces = if (arr != null) List(minOf(TOKEN_PREVIEW_PIECES, arr.length())) { TokenPiece(arr.getInt(it)) } else emptyList()
                    val count = arr?.length() ?: json.optInt("length", -1)
                    require(count >= 0) { "TabbyAPI 未返回 Token 数量" }
                    TokenPreview("TabbyAPI 服务端分词", count, pieces)
                }
            }
            ApiProtocol.VLLM_TEXT -> {
                val body = jsonOf("model" to connection.model, "prompt" to text, "add_special_tokens" to false)
                request("${rootAddress(base)}/tokenize", body, headers, readTimeout = 10_000, redactError = redact) { input, _ ->
                    val json = JSONObject(input.readLimited(4 * 1024 * 1024).decodeToString())
                    val arr = json.optJSONArray("tokens")
                    val pieces = if (arr != null) List(minOf(TOKEN_PREVIEW_PIECES, arr.length())) { TokenPiece(arr.getInt(it)) } else emptyList()
                    val count = json.optInt("count", arr?.length() ?: -1)
                    require(count >= 0) { "vLLM 未返回 Token 数量" }
                    TokenPreview("vLLM 服务端分词", count, pieces)
                }
            }
            ApiProtocol.OOBABOOGA_TEXT -> {
                val v1 = v1Base(base)
                try {
                    request("$v1/internal/encode", jsonOf("text" to text), headers, readTimeout = 10_000, redactError = redact) { input, _ ->
                        val json = JSONObject(input.readLimited(4 * 1024 * 1024).decodeToString())
                        val arr = json.optJSONArray("tokens")
                        val pieces = if (arr != null) List(minOf(TOKEN_PREVIEW_PIECES, arr.length())) { TokenPiece(arr.getInt(it)) } else emptyList()
                        val count = json.optInt("length", arr?.length() ?: -1)
                        require(count >= 0) { "Oobabooga 未返回 Token 数量" }
                        TokenPreview("Oobabooga 服务端分词", count, pieces)
                    }
                } catch (e: HttpRequestError) {
                    if (e.status !in setOf(404, 405)) throw e
                    request("$v1/internal/token-count", jsonOf("text" to text), headers, readTimeout = 10_000, redactError = redact) { input, _ ->
                        val json = JSONObject(input.readLimited(256 * 1024).decodeToString())
                        val count = json.optInt("length", -1)
                        require(count >= 0) { "Oobabooga 未返回 Token 数量" }
                        TokenPreview("Oobabooga 服务端计数", count)
                    }
                }
            }
            else -> error("当前文本补全后端不支持远程分词接口")
        }
    }

    suspend fun complete(
        provider: Provider,
        payload: JSONObject,
        token: (String) -> Unit,
        textPrompt: TextCompletionPrompt?,
        customStops: List<String>,
        sendStops: Boolean,
    ): CompletionAccumulator {
        val formatted = textPrompt ?: ModelConnection(provider, payload.string("model")).resolvedTextTemplates().instruct
            ?.format(payload.optJSONArray("messages") ?: JSONArray(), InstructTurn(quiet = true))
        val flattened = formatted ?: ProviderProtocols.flattenMessagesToPrompt(payload.optJSONArray("messages")?.objects().orEmpty())
        val localStops = (customStops + formatted?.stops.orEmpty()).distinct()
        val wireStops = if (sendStops) (customStops + flattened.stops).distinct() else emptyList()
        val (url, body) = buildCompletionRequest(provider, payload, flattened.text, wireStops, sendStops)
        val headers = buildHeaders(provider)
        if (provider.protocol == ApiProtocol.HUGGINGFACE_TEXT) {
            return executeHuggingFaceCompletion(provider, url, body, headers, token, localStops)
        }
        return ProviderProtocols.executeOpenAiTextCompletion(url, body, headers, token, localStops, provider)
    }

    internal fun parseHuggingFaceJson(raw: String, result: CompletionAccumulator, token: (String) -> Unit) {
        val trimmed = raw.trim()
        val obj = when {
            trimmed.startsWith('[') -> JSONArray(trimmed).optJSONObject(0) ?: error("HuggingFace 返回空响应")
            trimmed.startsWith('{') -> JSONObject(trimmed)
            else -> error("HuggingFace 返回的响应格式无效")
        }
        obj.optString("error").takeIf(String::isNotBlank)?.let { error(it) }
        val text = obj.string("generated_text")
        if (text.isNotEmpty()) result.appendText(text, token)
        val details = obj.optJSONObject("details")
        val rawFinish = details?.string("finish_reason").orEmpty().lowercase()
        result.finishReason = when (rawFinish) {
            "length" -> "length"
            "eos_token", "stop_sequence" -> "stop"
            "" -> "stop"
            else -> rawFinish
        }
        val genTokens = details?.optLong("generated_tokens", -1L)?.takeIf { it >= 0L }
        if (genTokens != null) {
            result.usage = result.usage.update(ResponseTokens(output = genTokens))
        }
    }

    private suspend fun executeHuggingFaceCompletion(
        provider: Provider,
        url: String,
        body: JSONObject,
        headers: Map<String, String>,
        token: (String) -> Unit,
        stops: List<String>,
    ): CompletionAccumulator {
        val result = CompletionAccumulator(stops)
        var streamOpened = false
        var streamConsumed = false
        try {
            request(url, body, headers, redactError = { it.redactProviderSecrets(provider) }) { input, type ->
                if (!type.contains("text/event-stream", ignoreCase = true)) {
                    parseHuggingFaceJson(input.readLimited().toString(Charsets.UTF_8), result, token)
                    return@request
                }
                streamOpened = true
                val reader = input.bufferedReader(Charsets.UTF_8)
                val dataLines = mutableListOf<String>()
                fun handleEvent(): Boolean {
                    val raw = dataLines.joinToString("\n").trim()
                    dataLines.clear()
                    if (raw.isEmpty()) return false
                    if (raw == "[DONE]") return true
                    val json = try {
                        JSONObject(raw)
                    } catch (_: JSONException) {
                        val msg = "HuggingFace 返回的流式分片格式无效"
                        if (!result.hasReceivedText) error(msg)
                        result.interruption = msg
                        return true
                    }
                    val err = json.optString("error").ifBlank { json.optJSONObject("error")?.string("message").orEmpty() }
                    if (err.isNotBlank()) {
                        val safe = err.redactProviderSecrets(provider)
                        if (!result.hasReceivedText) error(safe)
                        result.interruption = safe
                        return true
                    }
                    val tokenObj = json.optJSONObject("token")
                    if (tokenObj != null && !tokenObj.optBoolean("special", false)) {
                        result.appendText(tokenObj.string("text"), token)
                    } else if (tokenObj == null && !result.hasReceivedText) {
                        val full = json.string("generated_text")
                        if (full.isNotEmpty()) result.appendText(full, token)
                    }
                    val details = json.optJSONObject("details")
                    if (details != null) {
                        val finish = details.string("finish_reason").lowercase()
                        result.finishReason = when (finish) {
                            "length" -> "length"
                            "eos_token", "stop_sequence", "" -> "stop"
                            else -> finish
                        }
                        details.optLong("generated_tokens", -1L).takeIf { it >= 0L }?.let {
                            result.usage = result.usage.update(ResponseTokens(output = it))
                        }
                    }
                    return result.reachedTextStop || result.interruption != null
                }
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank() && handleEvent()) {
                        streamConsumed = true
                        return@request
                    }
                    if (line.startsWith("data:")) dataLines += line.removePrefix("data:").removePrefix(" ")
                }
                if (dataLines.isNotEmpty()) handleEvent()
                if (result.finishReason.isBlank() && !result.reachedTextStop && result.interruption == null) {
                    if (result.hasText) result.finishReason = "stop"
                    else result.interruption = "模型连接在生成完成前中断"
                }
                streamConsumed = true
            }
        } catch (e: IOException) {
            if (!streamOpened) throw e
            if (!streamConsumed) result.interruption = "模型连接在生成完成前中断"
        }
        return result.also { it.finishText(token) }
    }
}

internal data class TabbyModelStatus(
    val loadedModel: String?,
    val maxSeqLen: Int? = null,
    val cacheMode: String? = null,
    val loraNames: List<String> = emptyList(),
)

internal data class TabbyLoadRequest(
    val modelName: String,
    val maxSeqLen: Int? = null,
    val cacheMode: String = "FP16",
    val gpuSplitAuto: Boolean = true,
    val gpuSplit: List<Float> = emptyList(),
    val ropeScale: Double? = null,
    val ropeAlpha: Double? = null,
    val chunkSize: Int? = null,
    val draftModelName: String = "",
)

internal object TabbyModelClient {
    fun buildLoadPayload(req: TabbyLoadRequest): JSONObject {
        val name = req.modelName.trim()
        require(name.isNotEmpty()) { "请选择或填写要加载的 TabbyAPI 模型名称" }
        val body = jsonOf(
            "model_name" to name,
            "gpu_split_auto" to req.gpuSplitAuto,
        )
        req.maxSeqLen?.takeIf { it in 256..MAX_CONTEXT_TOKENS }?.let { body.put("max_seq_len", it) }
        req.cacheMode.trim().takeIf(String::isNotBlank)?.let { body.put("cache_mode", it) }
        if (!req.gpuSplitAuto && req.gpuSplit.isNotEmpty()) {
            body.put("gpu_split", JSONArray(req.gpuSplit.map { it.toDouble() }))
        }
        req.ropeScale?.takeIf { it > 0.0 }?.let { body.put("rope_scale", it) }
        req.ropeAlpha?.takeIf { it > 0.0 }?.let { body.put("rope_alpha", it) }
        req.chunkSize?.takeIf { it > 0 }?.let { body.put("chunk_size", it) }
        req.draftModelName.trim().takeIf(String::isNotBlank)?.let {
            body.put("draft_model", jsonOf("draft_model_name" to it))
        }
        return body
    }

    fun parseCurrentModel(json: JSONObject): TabbyModelStatus {
        val id = json.string("id").ifBlank { json.string("model_name") }.trim().takeIf(String::isNotBlank)
        val params = json.optJSONObject("parameters")
        val maxSeq = params?.optInt("max_seq_len")?.takeIf { it > 0 }
            ?: json.optInt("max_seq_len").takeIf { it > 0 }
        val cacheMode = params?.string("cache_mode")?.takeIf(String::isNotBlank)
            ?: json.string("cache_mode").takeIf(String::isNotBlank)
        val loras = json.optJSONArray("loras")?.objects().orEmpty()
            .mapNotNull { it.string("id").ifBlank { it.string("name") }.trim().takeIf(String::isNotBlank) }
        return TabbyModelStatus(id, maxSeq, cacheMode, loras)
    }

    fun parseModelList(json: JSONObject): List<String> {
        val data = json.optJSONArray("data") ?: json.optJSONArray("models") ?: return emptyList()
        return buildList {
            for (i in 0 until data.length()) {
                when (val entry = data.opt(i)) {
                    is String -> entry.trim().takeIf(String::isNotBlank)?.let(::add)
                    is JSONObject -> entry.string("id").ifBlank { entry.string("name") }.trim().takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }.distinct().sorted()
    }

    private fun v1(provider: Provider) = AdditionalTextBackends.v1Base(ProviderProtocols.baseAddress(provider))

    suspend fun fetchStatus(provider: Provider): TabbyModelStatus =
        MultiKeyManager.executeWithRotation(provider) { active, _ ->
            request(
                "${v1(active)}/model",
                headers = AdditionalTextBackends.buildHeaders(active),
                readTimeout = 10_000,
                redactError = { it.redactProviderSecrets(provider) },
            ) { input, _ ->
                parseCurrentModel(JSONObject(input.readLimited().decodeToString()))
            }
        }

    suspend fun listModels(provider: Provider): List<String> =
        MultiKeyManager.executeWithRotation(provider) { active, _ ->
            request(
                "${v1(active)}/model/list",
                headers = AdditionalTextBackends.buildHeaders(active),
                readTimeout = 12_000,
                redactError = { it.redactProviderSecrets(provider) },
            ) { input, _ ->
                parseModelList(JSONObject(input.readLimited().decodeToString()))
            }
        }

    suspend fun loadModel(provider: Provider, loadRequest: TabbyLoadRequest): String {
        val payload = buildLoadPayload(loadRequest)
        return MultiKeyManager.executeWithRotation(provider) { active, _ ->
            request(
                "${v1(active)}/model/load",
                body = payload,
                headers = AdditionalTextBackends.buildHeaders(active),
                readTimeout = 300_000,
                redactError = { it.redactProviderSecrets(provider) },
            ) { input, type ->
                val raw = input.readLimited().toString(Charsets.UTF_8).trim()
                if (type.contains("text/event-stream", ignoreCase = true)) {
                    // TabbyAPI may stream load progress events; check for error or completion.
                    val lastData = raw.lineSequence()
                        .filter { it.startsWith("data:") }
                        .map { it.removePrefix("data:").trim() }
                        .filter { it.isNotEmpty() && it != "[DONE]" }
                        .lastOrNull()
                    val parsed = lastData?.let { runCatching { JSONObject(it) }.getOrNull() }
                    parsed?.string("status").orEmpty().ifBlank { "模型 ${loadRequest.modelName} 已加载" }
                } else {
                    val parsed = runCatching { JSONObject(raw) }.getOrNull()
                    parsed?.string("status").orEmpty()
                        .ifBlank { parsed?.string("message").orEmpty() }
                        .ifBlank { "模型 ${loadRequest.modelName} 已加载" }
                }
            }
        }
    }

    suspend fun unloadModel(provider: Provider): String =
        MultiKeyManager.executeWithRotation(provider) { active, _ ->
            request(
                "${v1(active)}/model/unload",
                body = JSONObject(),
                headers = AdditionalTextBackends.buildHeaders(active),
                readTimeout = 30_000,
                redactError = { it.redactProviderSecrets(provider) },
            ) { input, _ ->
                val raw = input.readLimited().toString(Charsets.UTF_8).trim()
                val parsed = runCatching { JSONObject(raw) }.getOrNull()
                parsed?.string("status").orEmpty()
                    .ifBlank { parsed?.string("message").orEmpty() }
                    .ifBlank { "已卸载当前模型" }
            }
        }
}
