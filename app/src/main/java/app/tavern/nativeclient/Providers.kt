package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject

internal enum class ProviderTemplate(
    val label: String,
    val endpoint: String,
    val defaultModel: String,
    val starterModels: List<String>,
) {
    DEEPSEEK("DeepSeek", "https://api.deepseek.com", "deepseek-flash",
        listOf("deepseek-flash", "deepseek-v4-pro")),
    GOOGLE("Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-4-hydrogen",
        listOf("gemini-4-hydrogen"));

    val id get() = name.lowercase(java.util.Locale.ROOT)

    fun profile() = Provider(id, label, endpoint, defaultModel = defaultModel, template = this, models = starterModels)
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
) {
    val ready get() = endpoint.isNotBlank() && defaultModel.isNotBlank() && (template == null || apiKey.isNotBlank())
    val choices get() = (listOf(defaultModel) + models).filter(String::isNotBlank).distinct()
    val defaultSelection get() = ModelSelection(id, defaultModel)

    fun validated(): Provider {
        val profile = template?.profile()
        val clean = copy(name = profile?.name ?: name.trim(), endpoint = profile?.endpoint ?: endpoint.trim(),
            apiKey = apiKey.trim(), defaultModel = defaultModel.trim().ifBlank { profile?.defaultModel.orEmpty() },
            models = models.map(String::trim).filter(String::isNotBlank).distinct())
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
    )

    companion object {
        fun fromJson(json: JSONObject, unseal: (String) -> String): Provider {
            val template = ProviderTemplate.entries.firstOrNull { it.id == json.string("template") }
            val contexts = json.optJSONObject("modelContexts")
            return Provider(json.getString("id"), template?.label ?: json.string("name"),
                template?.endpoint ?: json.string("endpoint"), unseal(json.string("apiKey")),
                json.string("defaultModel").ifBlank { template?.defaultModel.orEmpty() },
                json.optJSONArray("models").strings(), template, json.optBoolean("streaming", true),
                json.optInt("contextLimit").takeIf { it in 1..MAX_CONTEXT_TOKENS },
                contexts?.keys()?.asSequence()?.mapNotNull { id ->
                    contexts.optInt(id).takeIf { it in 1..MAX_CONTEXT_TOKENS }?.let { id to it }
                }?.toMap().orEmpty())
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
