package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject

internal enum class ApiProtocol(val wire: String, val label: String) {
    OPENAI_COMPATIBLE("openai", "OpenAI 兼容"),
    ANTHROPIC("anthropic", "Anthropic Claude 原生"),
    GEMINI_NATIVE("gemini", "Google Gemini 原生"),
    OPENROUTER("openrouter", "OpenRouter"),
    OLLAMA("ollama", "Ollama 本地"),
    KOBOLD_TEXT("kobold", "KoboldCPP / Text Completion");

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
) {
    val ready get() = endpoint.isNotBlank() && defaultModel.isNotBlank() && (template == null || apiKey.isNotBlank())
    val choices get() = (listOf(defaultModel) + models).filter(String::isNotBlank).distinct()
    val defaultSelection get() = ModelSelection(id, defaultModel)

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
        )
        require(clean.name.isNotBlank()) { "请填写提供商名称" }
        require(clean.defaultModel.isNotBlank()) { "请填写默认模型 ID" }
        require(clean.template == null || clean.apiKey.isNotBlank()) { "请填写 API Key" }
        require(clean.contextLimit == null || clean.contextLimit in 1..MAX_CONTEXT_TOKENS) { "上下文 Token 上限无效" }
        checkedAddress(clean.endpoint)
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
    )

    companion object {
        fun fromJson(json: JSONObject, unseal: (String) -> String): Provider {
            val template = ProviderTemplate.entries.firstOrNull { it.id == json.string("template") }
            val contexts = json.optJSONObject("modelContexts")
            val parsedProtocol = ApiProtocol.from(json.string("protocol")) ?: template?.defaultProtocol() ?: ApiProtocol.OPENAI_COMPATIBLE
            val safeProtocol = if (template != null && parsedProtocol !in template.allowedProtocols()) template.defaultProtocol() else parsedProtocol
            return Provider(
                json.getString("id"), template?.label ?: json.string("name"),
                template?.endpoint ?: json.string("endpoint"), unseal(json.string("apiKey")),
                json.string("defaultModel").ifBlank { template?.defaultModel.orEmpty() },
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

internal data class DiscoveredModels(val ids: List<String>, val contexts: Map<String, Int>) {
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
