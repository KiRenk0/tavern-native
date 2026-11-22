package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject

internal enum class ApiProtocol(val wire: String, val label: String) {
    OPENAI_COMPATIBLE("openai", "OpenAI 兼容"),
    ANTHROPIC("anthropic", "Anthropic Claude 原生"),
    GEMINI_NATIVE("gemini", "Google Gemini 原生"),
    OPENROUTER("openrouter", "OpenRouter"),
    OLLAMA("ollama", "Ollama 本地"),
    KOBOLD_TEXT("kobold", "KoboldCPP / llama.cpp 文本补全"),
    NOVELAI_TEXT("novelai", "NovelAI 文本补全"),
    HORDE_TEXT("horde", "AI Horde");

    companion object {
        fun from(wire: String): ApiProtocol? = entries.firstOrNull { it.wire == wire }
    }
}

internal enum class GeminiSafetyLevel(val wire: String, val label: String) {
    DEFAULT("default", "默认安全过滤"),
    BLOCK_NONE("BLOCK_NONE", "关闭拦截（BLOCK_NONE）"),
    BLOCK_ONLY_HIGH("BLOCK_ONLY_HIGH", "仅拦截高风险"),
    BLOCK_MEDIUM_AND_ABOVE("BLOCK_MEDIUM_AND_ABOVE", "拦截中及以上风险");

    companion object {
        fun from(wire: String): GeminiSafetyLevel = entries.firstOrNull { it.wire == wire } ?: BLOCK_NONE
    }
}

internal enum class ProviderTemplate(
    val label: String,
    val endpoint: String,
    val defaultModel: String,
    val starterModels: List<String>,
) {
    DEEPSEEK("DeepSeek", "https://api.deepseek.com", "deepseek-flash",
        listOf("deepseek-flash", "deepseek-v4-pro")),
    GOOGLE("Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-4-hydrogen",
        listOf("gemini-4-hydrogen")),
    ANTHROPIC("Anthropic Claude", "https://api.anthropic.com", "claude-sonnet-4-5",
        listOf("claude-sonnet-4-5", "claude-opus-4-1", "claude-3-7-sonnet-latest")),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "anthropic/claude-sonnet-4.5",
        listOf("anthropic/claude-sonnet-4.5", "deepseek/deepseek-chat", "google/gemini-2.5-pro"));

    val id get() = name.lowercase(java.util.Locale.ROOT)

    fun defaultProtocol(): ApiProtocol = when (this) {
        ANTHROPIC -> ApiProtocol.ANTHROPIC
        OPENROUTER -> ApiProtocol.OPENROUTER
        DEEPSEEK, GOOGLE -> ApiProtocol.OPENAI_COMPATIBLE
    }

    fun allowedProtocols(): List<ApiProtocol> = when (this) {
        GOOGLE -> listOf(ApiProtocol.OPENAI_COMPATIBLE, ApiProtocol.GEMINI_NATIVE)
        ANTHROPIC -> listOf(ApiProtocol.ANTHROPIC)
        OPENROUTER -> listOf(ApiProtocol.OPENROUTER)
        DEEPSEEK -> listOf(ApiProtocol.OPENAI_COMPATIBLE)
    }

    fun profile() = Provider(
        id, label, endpoint, defaultModel = defaultModel, template = this,
        models = starterModels, protocol = defaultProtocol(),
    )

    companion object {
        val builtIn = listOf(DEEPSEEK, GOOGLE)
    }
}

internal data class Provider(
    val id: String,
    val name: String = "",
    val endpoint: String = "",
    val apiKey: String = "",
    val defaultModel: String = "",
    val models: List<String> = emptyList(),
    val template: ProviderTemplate? = null,
    val streaming: Boolean = true,
    val contextLimit: Int? = null,
    val modelContexts: Map<String, Int> = emptyMap(),
    val protocol: ApiProtocol = template?.defaultProtocol() ?: ApiProtocol.OPENAI_COMPATIBLE,
    val promptCaching: Boolean = true,
    val thinkingBudget: Int? = null,
    val geminiSafety: GeminiSafetyLevel = GeminiSafetyLevel.BLOCK_NONE,
    val openRouterProviders: List<String> = emptyList(),
    val openRouterAllowFallbacks: Boolean = true,
    val openRouterReasoningEffort: String = "",
    val textCompletion: Boolean = false,
    val instruct: InstructTemplate? = null,
    val textContext: TextContextTemplate? = null,
    val modelFormatting: ModelFormatting = ModelFormatting(),
    val textSampling: TextSamplingSettings = TextSamplingSettings(),
    val requestOptions: ProviderRequestOptions = ProviderRequestOptions(),
    val tokenBias: TokenBiasSettings = TokenBiasSettings(),
    val textTokenizer: TextTokenizer = TextTokenizer.MODEL,
    val novelAi: NovelAiSettings = NovelAiSettings(),
    val horde: HordeSettings = HordeSettings(),
) {
    val requiresApiKey get() = template != null || protocol == ApiProtocol.NOVELAI_TEXT
    val ready get() = endpoint.isNotBlank() && defaultModel.isNotBlank() && (!requiresApiKey || apiKey.isNotBlank())
    val choices get() = (listOf(defaultModel) + models).filter(String::isNotBlank).distinct()
    val defaultSelection get() = ModelSelection(id, defaultModel)
    val usesTextCompletion get() = protocol in setOf(ApiProtocol.KOBOLD_TEXT, ApiProtocol.NOVELAI_TEXT, ApiProtocol.HORDE_TEXT) ||
        protocol == ApiProtocol.OLLAMA && textCompletion
    val supportsTextSampling get() = protocol in setOf(ApiProtocol.KOBOLD_TEXT, ApiProtocol.OLLAMA, ApiProtocol.HORDE_TEXT)

    fun validated(): Provider {
        val profile = template?.profile()
        val resolvedProtocol = if (template != null && protocol !in template.allowedProtocols()) {
            template.defaultProtocol()
        } else protocol
        val clean = copy(
            name = profile?.name ?: name.trim(),
            endpoint = profile?.endpoint ?: endpoint.trim(),
            apiKey = apiKey.trim(),
            defaultModel = defaultModel.trim().ifBlank { profile?.defaultModel.orEmpty() },
            models = models.map(String::trim).filter(String::isNotBlank).distinct(),
            protocol = resolvedProtocol,
            thinkingBudget = thinkingBudget?.takeIf { it > 0 }?.coerceAtMost(128_000),
            openRouterProviders = openRouterProviders.map(String::trim).filter(String::isNotBlank).distinct(),
            openRouterReasoningEffort = openRouterReasoningEffort.trim().lowercase().takeIf { it in setOf("low", "medium", "high") }.orEmpty(),
            instruct = instruct?.validated(),
            textContext = textContext?.validated(),
        )
        require(clean.name.isNotBlank()) { "请填写提供商名称" }
        require(clean.defaultModel.isNotBlank()) { "请填写默认模型 ID" }
        require(!clean.requiresApiKey || clean.apiKey.isNotBlank()) { "请填写 API Key" }
        if (clean.protocol == ApiProtocol.NOVELAI_TEXT) {
            require(NovelAiModel.from(clean.defaultModel) != null && clean.models.all { NovelAiModel.from(it) != null }) {
                "请选择 NovelAI 的模型 ID。可使用「读取模型列表」查看。"
            }
        }
        require(clean.contextLimit == null || clean.contextLimit in 1..MAX_CONTEXT_TOKENS) { "上下文 Token 上限无效" }
        checkedAddress(clean.endpoint)
        if (clean.supportsRequestOptions && !requestOptions.isEmpty) {
            requestOptions.compile()
        }
        return clean
    }

    fun storageJson(seal: (String) -> String) = jsonOf(
        "id" to id, "name" to name, "endpoint" to endpoint, "apiKey" to seal(apiKey),
        "defaultModel" to defaultModel, "models" to JSONArray(models), "template" to template?.id,
        "streaming" to streaming,
        "contextLimit" to contextLimit, "modelContexts" to JSONObject(modelContexts),
        "protocol" to protocol.wire,
        "promptCaching" to promptCaching,
        "thinkingBudget" to thinkingBudget,
        "geminiSafety" to geminiSafety.wire,
        "openRouterProviders" to JSONArray(openRouterProviders),
        "openRouterAllowFallbacks" to openRouterAllowFallbacks,
        "openRouterReasoningEffort" to openRouterReasoningEffort,
        "textCompletion" to textCompletion,
        "instruct" to instruct?.json(),
        "textContext" to textContext?.json(),
        "modelFormatting" to modelFormatting.json(),
        "textSampling" to textSampling.json(),
        "requestOptions" to requestOptions.takeIf(ProviderRequestOptions::hasYaml)?.settingsJson()?.toString()?.let(seal),
        "requestPromptProcessing" to requestOptions.processing.wire.takeIf(String::isNotEmpty),
        "tokenBias" to tokenBias.takeUnless { it == TokenBiasSettings() }?.json(),
        "textTokenizer" to textTokenizer.name.takeUnless { textTokenizer == TextTokenizer.MODEL },
        "novelAi" to novelAi.takeUnless { it == NovelAiSettings() }?.json(),
        "horde" to horde.takeUnless { it == HordeSettings() }?.json(),
    )

    companion object {
        fun fromJson(json: JSONObject, unseal: (String) -> String): Provider {
            val storedTemplate = ProviderTemplate.entries.firstOrNull { it.id == json.string("template") }
            val template = storedTemplate?.takeIf { it in ProviderTemplate.builtIn }
            val contexts = json.optJSONObject("modelContexts")
            val parsedProtocol = ApiProtocol.from(json.string("protocol")) ?: storedTemplate?.defaultProtocol()
                ?: ApiProtocol.OPENAI_COMPATIBLE
            val safeProtocol = if (template != null && parsedProtocol !in template.allowedProtocols()) template.defaultProtocol() else parsedProtocol
            return Provider(
                json.getString("id"), template?.label ?: json.string("name").ifBlank { storedTemplate?.label.orEmpty() },
                template?.endpoint ?: json.string("endpoint").ifBlank { storedTemplate?.endpoint.orEmpty() },
                unseal(json.string("apiKey")), json.string("defaultModel").ifBlank { storedTemplate?.defaultModel.orEmpty() },
                json.optJSONArray("models").strings(), template, json.optBoolean("streaming", true),
                json.optInt("contextLimit").takeIf { it in 1..MAX_CONTEXT_TOKENS },
                contexts?.keys()?.asSequence()?.mapNotNull { id ->
                    contexts.optInt(id).takeIf { it in 1..MAX_CONTEXT_TOKENS }?.let { id to it }
                }?.toMap().orEmpty(),
                protocol = safeProtocol,
                promptCaching = json.optBoolean("promptCaching", true),
                thinkingBudget = json.optInt("thinkingBudget").takeIf { it > 0 },
                geminiSafety = GeminiSafetyLevel.from(json.string("geminiSafety")),
                openRouterProviders = json.optJSONArray("openRouterProviders").strings(),
                openRouterAllowFallbacks = json.optBoolean("openRouterAllowFallbacks", true),
                openRouterReasoningEffort = json.string("openRouterReasoningEffort"),
                textCompletion = json.optBoolean("textCompletion", false),
                instruct = json.optJSONObject("instruct")?.let { runCatching { InstructTemplate.fromJson(it) }.getOrNull() },
                textContext = json.optJSONObject("textContext")?.let {
                    runCatching { TextContextTemplate.fromJson(it, checkStory = false) }.getOrNull()
                },
                modelFormatting = ModelFormatting.read(json.optJSONObject("modelFormatting")),
                textSampling = TextSamplingSettings.read(json.optJSONObject("textSampling")),
                requestOptions = ProviderRequestOptions.fromJson(json.string("requestOptions").takeIf(String::isNotBlank)
                    ?.let { runCatching { JSONObject(unseal(it)) }.getOrNull() })
                    .copy(processing = PromptPostProcessing.from(json.string("requestPromptProcessing"))),
                tokenBias = TokenBiasSettings.read(json.optJSONObject("tokenBias")),
                textTokenizer = TextTokenizer.from(json.string("textTokenizer")),
                novelAi = NovelAiSettings.read(json.optJSONObject("novelAi")),
                horde = HordeSettings.read(json.optJSONObject("horde")),
            )
        }

        fun legacy(json: JSONObject, unseal: (String) -> String): Provider? {
            val endpoint = json.string("endpoint")
            val model = json.string("model")
            if (endpoint.isBlank() && model.isBlank() && json.string("apiKey").isBlank()) return null
            return Provider("legacy", "原有 API 配置", endpoint, unseal(json.string("apiKey")), model)
        }
    }
}

internal data class ModelSelection(val providerId: String, val model: String) {
    fun json() = jsonOf("providerId" to providerId, "model" to model)

    companion object {
        fun fromJson(json: JSONObject?): ModelSelection? {
            val providerId = json?.string("providerId").orEmpty()
            val model = json?.string("model").orEmpty()
            return if (providerId.isNotBlank() && model.isNotBlank()) ModelSelection(providerId, model) else null
        }
    }
}

internal data class ModelConnection(val provider: Provider, val model: String)

internal data class DiscoveredModels(
    val ids: List<String>, val contexts: Map<String, Int>, val novelAiTier: Int? = null,
    val hordeOutputs: Map<String, Int>? = null,
) {
    companion object {
        fun read(json: JSONObject, googleAliases: Boolean = false): DiscoveredModels {
            val google = json.has("models")
            val rows = json.getJSONArray(if (google) "models" else "data").objects()
            val ids = linkedSetOf<String>()
            val contexts = linkedMapOf<String, Int>()
            for (row in rows) {
                val methods = row.optJSONArray("supportedGenerationMethods").strings()
                if (google && methods.isNotEmpty() && "generateContent" !in methods) continue
                val name = row.string(if (google) "name" else "id").trim()
                val id = if (google || googleAliases) name.removePrefix("models/") else name
                if (id.isBlank()) continue
                ids += id
                val context = sequenceOf("context_window", "context_length", "inputTokenLimit", "max_model_len")
                    .map { row.optInt(it) }.firstOrNull { it in 1..MAX_CONTEXT_TOKENS }
                if (context != null) contexts[id] = context
            }
            return DiscoveredModels(ids.sorted(), contexts)
        }
    }
}
