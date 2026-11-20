package app.tavern.nativeclient

import org.json.JSONObject

internal enum class DictationMode(val label: String) {
    APPEND("追加到草稿"), REPLACE("替换草稿"), SEND("直接发送");
}

internal data class VoicePreferences(
    val enabled: Boolean = true,
    val automatic: Boolean = false,
    val includeUser: Boolean = false,
    val quoteOnly: Boolean = false,
    val skipActions: Boolean = false,
    val skipCode: Boolean = true,
    val skipTags: Boolean = false,
    val filter: String = "",
    val speed: Float = 1f,
    val pitch: Float = 1f,
    val systemVoice: String = "",
    val backendId: String = "",
    val backendModel: String = "",
    val backendVoice: String = "",
    val voices: Map<String, String> = emptyMap(),
    val dictationEnabled: Boolean = true,
    val dictationMode: DictationMode = DictationMode.APPEND,
    val language: String = "",
    val requireOffline: Boolean = false,
    val shortcuts: Map<String, String> = emptyMap(),
    val translatedText: Boolean = false,
) {
    val defaultVoice get() = if (backendId.isEmpty()) systemVoice else backendVoice

    fun actorVoice(actor: String?) = voices["$backendId:$actor"].orEmpty().ifBlank { defaultVoice }

    fun withVoice(actor: String, voice: String): VoicePreferences {
        val key = "$backendId:$actor"
        return copy(voices = if (voice.isBlank()) voices - key else voices + (key to voice))
    }

    fun json() = jsonOf(
        "enabled" to enabled, "automatic" to automatic, "includeUser" to includeUser,
        "quoteOnly" to quoteOnly, "skipActions" to skipActions, "skipCode" to skipCode, "skipTags" to skipTags,
        "filter" to filter, "speed" to speed, "pitch" to pitch, "systemVoice" to systemVoice,
        "backendId" to backendId, "backendModel" to backendModel, "backendVoice" to backendVoice, "voices" to JSONObject(voices),
        "dictationEnabled" to dictationEnabled, "dictationMode" to dictationMode.name,
        "language" to language, "requireOffline" to requireOffline, "shortcuts" to JSONObject(shortcuts),
        "translatedText" to translatedText,
    )

    companion object {
        fun from(row: JSONObject?): VoicePreferences {
            if (row == null) return VoicePreferences()
            return VoicePreferences(
                enabled = row.optBoolean("enabled", true), automatic = row.optBoolean("automatic"),
                includeUser = row.optBoolean("includeUser"), quoteOnly = row.optBoolean("quoteOnly"),
                skipActions = row.optBoolean("skipActions"), skipCode = row.optBoolean("skipCode", true),
                skipTags = row.optBoolean("skipTags"), filter = row.string("filter"),
                speed = row.optDouble("speed", 1.0).toFloat().coerceIn(0.5f, 2f),
                pitch = row.optDouble("pitch", 1.0).toFloat().coerceIn(0.5f, 2f), systemVoice = row.string("systemVoice"),
                backendId = row.string("backendId"), backendModel = row.string("backendModel"), backendVoice = row.string("backendVoice"),
                voices = row.optJSONObject("voices").variableStrings(),
                dictationEnabled = row.optBoolean("dictationEnabled", true),
                dictationMode = DictationMode.entries.firstOrNull { it.name == row.string("dictationMode") } ?: DictationMode.APPEND,
                language = row.string("language"), requireOffline = row.optBoolean("requireOffline"),
                shortcuts = row.optJSONObject("shortcuts").variableStrings(),
                translatedText = row.optBoolean("translatedText"),
            )
        }
    }
}

internal data class DictatedText(val text: String, val shortcut: Boolean, val send: Boolean)

internal fun VoicePreferences.dictated(raw: String, draft: String): DictatedText {
    val spoken = raw.trim()
    val shortcut = shortcuts.entries.firstOrNull { it.key.equals(spoken, ignoreCase = true) }?.value
    val value = shortcut ?: spoken
    val body = if (dictationMode == DictationMode.APPEND && draft.isNotBlank() && value.isNotBlank()) "${draft.trimEnd()} $value" else value
    return DictatedText(body, shortcut != null, dictationMode == DictationMode.SEND)
}

private val fenced = Regex("(?s)```.*?(?:```|$)|~~~.*?(?:~~~|$)")
private val tagged = Regex("(?is)<([a-z][a-z0-9_-]*)\\b[^>]*>.*?</\\1\\s*>")
private val inlineTag = Regex("</?[a-zA-Z][^>]*>")
private val actions = Regex("(\\*{1,3})(?!\\*)([^*]+)(?:\\1|$)")
private val links = Regex("""(!?)\[([^\]\r\n]*)\]\([^)]*\)""")
private val whitespace = Regex("\\s+")
private val quotePairs = mapOf('"' to '"', '“' to '”', '‘' to '’', '„' to '“', '«' to '»',
    '「' to '」', '『' to '』', '＂' to '＂')

internal fun VoicePreferences.speechText(input: String): String {
    var text = input.removePrefix("\uFEFF")
    if (skipCode) text = fenced.replace(text, " ")
    if (skipTags) text = tagged.replace(text, " ")
    text = inlineTag.replace(text, " ")
    if (skipActions) text = actions.replace(text, " ")
    text = links.replace(text) { if (it.groupValues[1] == "!") " " else it.groupValues[2] }
    text = text.replace("*", "").replace("`", "")
    if (filter.isNotBlank()) text = regexTest(RegexRule(pattern = filter), text, RegexNames(null, "")).text
    if (quoteOnly) {
        val stack = ArrayDeque<Char>()
        val quotes = mutableListOf<String>()
        var start = 0
        var escaped = false
        for ((index, char) in text.withIndex()) {
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == stack.lastOrNull() -> {
                    stack.removeLast()
                    if (stack.isEmpty()) quotes += text.substring(start, index + 1)
                }
                char in quotePairs -> {
                    if (stack.isEmpty()) start = index
                    stack.addLast(quotePairs.getValue(char))
                }
            }
        }
        if (quotes.isNotEmpty()) text = quotes.joinToString(" … ")
    }
    return whitespace.replace(text, " ").trim()
}

internal fun speechChunks(text: String, limit: Int): List<String> {
    require(limit >= 8)
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = minOf(start + limit, text.length)
        if (end < text.length) {
            val boundary = text.lastIndexOfAny(charArrayOf(' ', '。', '！', '？', '.', '!', '?', '；', '，', ','), end - 1)
            if (boundary > start + limit / 2) end = boundary + 1
            else if (Character.isHighSurrogate(text[end - 1])) end--
        }
        val part = text.substring(start, end).trim()
        if (part.isNotEmpty()) chunks += part
        start = end
    }
    return chunks
}
