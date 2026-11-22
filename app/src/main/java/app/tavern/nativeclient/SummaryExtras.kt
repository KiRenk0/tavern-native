package app.tavern.nativeclient

import org.json.JSONObject

internal data class SummaryExtrasSettings(val endpoint: String = "", val apiKey: String = "") {
    fun requestAddress(): String {
        val base = checkedAddress(endpoint).toASCIIString().trimEnd('/')
        return when {
            base.endsWith("/api/summarize") -> base
            base.endsWith("/api") -> "$base/summarize"
            else -> "$base/api/summarize"
        }
    }

    fun json(seal: (String) -> String) = jsonOf("endpoint" to endpoint, "apiKey" to seal(apiKey))

    companion object {
        fun from(value: JSONObject?, unseal: (String) -> String) = SummaryExtrasSettings(
            value?.string("endpoint").orEmpty(), value?.string("apiKey")?.takeIf(String::isNotBlank)?.let(unseal).orEmpty(),
        )
    }
}

internal suspend fun summarizeWithExtras(settings: SummaryExtrasSettings, text: String): String {
    val key = settings.apiKey.trim()
    val headers = buildMap {
        put("Accept", "application/json")
        put("Bypass-Tunnel-Reminder", "bypass")
        if (key.isNotBlank()) put("Authorization", "Bearer $key")
    }
    val body = jsonOf("text" to text, "params" to JSONObject())
    return request(settings.requestAddress(), body, headers, redactError = { error ->
        if (key.isNotEmpty()) error.replace(key, "•••") else error
    }) { input, _ ->
        JSONObject(input.readLimited(1_000_000).decodeToString()).string("summary").trim().also {
            check(it.isNotBlank()) { "Extras 未返回可用的摘要" }
        }
    }
}
