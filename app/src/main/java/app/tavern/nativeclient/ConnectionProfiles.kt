package app.tavern.nativeclient

import org.json.JSONObject

internal data class ConnectionProfile(
    val id: String = newId(),
    val name: String = "",
    val providerId: String = "",
    val model: String = "",
    val presetId: String? = null,
    val temperature: String = "",
    val maxTokens: String = "",
    val topP: String = "",
    val includeReasoning: Boolean? = null,
) {
    fun validated(): ConnectionProfile {
        val clean = copy(
            name = name.trim(),
            providerId = providerId.trim(),
            model = model.trim(),
            presetId = presetId?.takeIf(String::isNotBlank),
            temperature = temperature.trim(),
            maxTokens = maxTokens.trim(),
            topP = topP.trim(),
        )
        require(clean.name.isNotBlank()) { "请填写配置档案名称" }
        require(clean.providerId.isNotBlank() && clean.model.isNotBlank()) { "请选择提供商和模型" }
        require(clean.temperature.isBlank() || clean.temperature.toDoubleOrNull()?.let { it in 0.0..2.0 } == true) {
            "温度应在 0–2 之间"
        }
        require(clean.maxTokens.isBlank() || clean.maxTokens.toIntOrNull()?.let { it > 0 } == true) {
            "最大输出 Token 应为正整数"
        }
        require(clean.topP.isBlank() || clean.topP.toDoubleOrNull()?.let { it in 0.0..1.0 } == true) {
            "Top P 应在 0–1 之间"
        }
        return clean
    }

    fun json(): JSONObject = jsonOf(
        "id" to id,
        "name" to name,
        "providerId" to providerId,
        "model" to model,
        "presetId" to presetId,
        "temperature" to temperature,
        "maxTokens" to maxTokens,
        "topP" to topP,
        "includeReasoning" to includeReasoning,
    )

    companion object {
        fun fromJson(json: JSONObject?): ConnectionProfile? {
            json ?: return null
            val id = json.string("id").ifBlank { return null }
            val name = json.string("name").ifBlank { return null }
            val providerId = json.string("providerId")
            val model = json.string("model")
            if (providerId.isBlank() || model.isBlank()) return null
            return ConnectionProfile(
                id = id,
                name = name,
                providerId = providerId,
                model = model,
                presetId = json.takeUnless { it.isNull("presetId") }?.string("presetId")?.takeIf(String::isNotBlank),
                temperature = json.string("temperature"),
                maxTokens = json.string("maxTokens"),
                topP = json.string("topP"),
                includeReasoning = if (json.has("includeReasoning") && !json.isNull("includeReasoning")) {
                    json.optBoolean("includeReasoning")
                } else null,
            )
        }
    }
}

internal data class EffectiveSampling(
    val temperature: String = "",
    val maxTokens: String = "",
    val topP: String = "",
    val includeReasoning: Boolean = false,
)

internal data class EffectiveGenerationConfig(
    val profile: ConnectionProfile?,
    val connection: ModelConnection?,
    val presetId: String?,
    val sampling: EffectiveSampling,
)

internal fun Preferences.activeProfile(chat: Chat? = null): ConnectionProfile? {
    val chatId = chat?.options?.connectionProfileId
    val selectedId = when {
        chatId == "" -> null
        chatId != null -> chatId
        else -> activeProfileId
    }
    return selectedId?.let { id -> connectionProfiles.firstOrNull { it.id == id } }
}

internal fun Preferences.effectiveConfig(chat: Chat? = null): EffectiveGenerationConfig {
    val profile = activeProfile(chat)
    val profileConnection = profile?.let { resolve(ModelSelection(it.providerId, it.model)) }
    val connection = resolve(chat?.modelOverride) ?: profileConnection ?: resolve(defaultSelection)
    val effectiveTemp = profile?.temperature?.takeIf(String::isNotBlank) ?: temperature
    val effectiveMax = profile?.maxTokens?.takeIf(String::isNotBlank) ?: maxTokens
    val effectiveTopP = profile?.topP?.takeIf(String::isNotBlank).orEmpty()
    val effectiveReasoning = profile?.includeReasoning ?: includeReasoning
    return EffectiveGenerationConfig(
        profile = profile,
        connection = connection,
        presetId = profile?.presetId,
        sampling = EffectiveSampling(effectiveTemp, effectiveMax, effectiveTopP, effectiveReasoning),
    )
}
