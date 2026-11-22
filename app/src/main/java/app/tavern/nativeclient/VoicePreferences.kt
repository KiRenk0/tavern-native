package app.tavern.nativeclient

import org.json.JSONObject
import java.util.Locale

internal enum class DictationMode(val label: String) {
    APPEND("追加到草稿"), REPLACE("替换草稿"), SEND("直接发送");
}

internal enum class TtsBackend(val label: String, val usesProvider: Boolean = false, val localPort: Int? = null) {
    SYSTEM("Android 系统朗读"),
    OPENAI_COMPAT("OpenAI 兼容 TTS", usesProvider = true),
    EDGE_TTS("Edge 微软神经语音（免费）"),
    ELEVENLABS("ElevenLabs"),
    FISH_AUDIO("Fish Audio"),
    GPT_SOVITS("GPT-SoVITS（本地/自建）"),
    GEMINI_NATIVE("Google Gemini TTS", usesProvider = true),
    NOVELAI("NovelAI TTS", usesProvider = true),
    VITS("VITS", localPort = 23456),
    STYLE_BERT_VITS2("Style-Bert-VITS2", localPort = 5000),
    XTTS("XTTSv2", localPort = 8020),
    SILERO("Silero", localPort = 8001),
    COSYVOICE("CosyVoice（非官方接口）", localPort = 9880);

    val selfHosted get() = localPort != null
    val hasOwnCredentials get() = this == ELEVENLABS || this == FISH_AUDIO || this == GPT_SOVITS || selfHosted

    fun accepts(provider: Provider): Boolean = when (this) {
        OPENAI_COMPAT -> provider.template == null
        GEMINI_NATIVE -> provider.apiKey.isNotBlank() &&
            (provider.template == ProviderTemplate.GOOGLE || provider.protocol == ApiProtocol.GEMINI_NATIVE)
        NOVELAI -> provider.protocol == ApiProtocol.NOVELAI_TEXT && provider.apiKey.isNotBlank()
        else -> false
    }
}

internal const val DEFAULT_GEMINI_TTS_MODEL = "gemini-3.8-flash-tts"
internal const val DEFAULT_GEMINI_TTS_VOICE = "Kore"
internal const val DEFAULT_NOVELAI_TTS_VOICE = "Ligeia"

internal data class TtsCredentials(val endpoint: String = "", val key: String = "")

internal enum class SttBackend(val label: String) {
    SYSTEM("Android 系统语音识别"),
    WHISPER_API("Whisper API（兼容接口）"),
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
    val ttsBackend: TtsBackend = TtsBackend.SYSTEM,
    val ttsEndpoint: String = "",
    val ttsApiKey: String = "",
    val edgeVoice: String = "zh-CN-XiaoxiaoNeural",
    val elevenLabsVoiceId: String = "",
    val elevenLabsModelId: String = "eleven_multilingual_v2",
    val fishReferenceId: String = "",
    val gptSovitsRefAudio: String = "",
    val gptSovitsPromptText: String = "",
    val gptSovitsPromptLang: String = "zh",
    val gptSovitsTextLang: String = "auto",
    val backendId: String = "",
    val backendModel: String = "",
    val backendVoice: String = "",
    val voices: Map<String, String> = emptyMap(),
    val dictationEnabled: Boolean = true,
    val dictationMode: DictationMode = DictationMode.APPEND,
    val sttBackend: SttBackend = SttBackend.SYSTEM,
    val sttEndpoint: String = "",
    val sttApiKey: String = "",
    val sttModel: String = "whisper-1",
    val language: String = "",
    val requireOffline: Boolean = false,
    val shortcuts: Map<String, String> = emptyMap(),
    val translatedText: Boolean = false,
    val inactiveTtsCredentials: Map<TtsBackend, TtsCredentials> = emptyMap(),
    val geminiTtsProvider: String = "",
    val geminiTtsModel: String = DEFAULT_GEMINI_TTS_MODEL,
    val geminiTtsVoice: String = DEFAULT_GEMINI_TTS_VOICE,
    val geminiTtsStyle: String = "",
    val novelAiTtsProvider: String = "",
    val novelAiTtsVoice: String = DEFAULT_NOVELAI_TTS_VOICE,
    val selfHostedVoices: Map<TtsBackend, SelfHostedVoiceOptions> = emptyMap(),
) {
    val activeBackend: TtsBackend get() = ttsBackend
    val selfHostedVoice get() = selfHostedVoices[activeBackend] ?: SelfHostedVoiceOptions()

    fun withSelfHostedVoice(value: SelfHostedVoiceOptions) = copy(
        selfHostedVoices = if (value == SelfHostedVoiceOptions()) selfHostedVoices - activeBackend
            else selfHostedVoices + (activeBackend to value),
    )

    fun speechProvider(providers: List<Provider>): Provider? {
        val id = when (activeBackend) {
            TtsBackend.OPENAI_COMPAT -> backendId.takeIf(String::isNotBlank) ?: return null
            TtsBackend.GEMINI_NATIVE -> geminiTtsProvider
            TtsBackend.NOVELAI -> novelAiTtsProvider
            else -> return null
        }
        if (id.isBlank() && activeBackend == TtsBackend.GEMINI_NATIVE) {
            providers.firstOrNull { it.template == ProviderTemplate.GOOGLE && activeBackend.accepts(it) }?.let { return it }
        }
        return providers.firstOrNull { (id.isBlank() || it.id == id) && activeBackend.accepts(it) }
    }

    fun credentialsFor(backend: TtsBackend): TtsCredentials? = when {
        !backend.hasOwnCredentials -> null
        backend == activeBackend -> TtsCredentials(ttsEndpoint, ttsApiKey)
        else -> inactiveTtsCredentials[backend]
    }

    fun selectTtsBackend(backend: TtsBackend): VoicePreferences {
        if (backend == activeBackend) return this
        val selected = credentialsFor(backend) ?: TtsCredentials()
        val stored = inactiveTtsCredentials.toMutableMap()
        credentialsFor(activeBackend)?.takeUnless { it == TtsCredentials() }?.let { stored[activeBackend] = it }
        stored.remove(backend)
        return copy(ttsBackend = backend, ttsEndpoint = selected.endpoint, ttsApiKey = selected.key,
            inactiveTtsCredentials = stored)
    }

    val backendScopeKey: String
        get() = when (activeBackend) {
            TtsBackend.SYSTEM -> ""
            TtsBackend.OPENAI_COMPAT -> backendId
            TtsBackend.EDGE_TTS -> "edge"
            TtsBackend.ELEVENLABS -> "elevenlabs"
            TtsBackend.FISH_AUDIO -> "fish"
            TtsBackend.GPT_SOVITS -> "gptsovits"
            TtsBackend.GEMINI_NATIVE -> "gemini:$geminiTtsProvider"
            TtsBackend.NOVELAI -> "novelai:$novelAiTtsProvider"
            TtsBackend.VITS, TtsBackend.STYLE_BERT_VITS2, TtsBackend.XTTS, TtsBackend.SILERO, TtsBackend.COSYVOICE ->
                activeBackend.name.lowercase(Locale.ROOT)
        }

    val defaultVoice: String
        get() = when (activeBackend) {
            TtsBackend.SYSTEM -> systemVoice
            TtsBackend.OPENAI_COMPAT -> backendVoice
            TtsBackend.EDGE_TTS -> edgeVoice.ifBlank { "zh-CN-XiaoxiaoNeural" }
            TtsBackend.ELEVENLABS -> elevenLabsVoiceId
            TtsBackend.FISH_AUDIO -> fishReferenceId
            TtsBackend.GPT_SOVITS -> gptSovitsRefAudio
            TtsBackend.GEMINI_NATIVE -> geminiTtsVoice.ifBlank { DEFAULT_GEMINI_TTS_VOICE }
            TtsBackend.NOVELAI -> novelAiTtsVoice.ifBlank { DEFAULT_NOVELAI_TTS_VOICE }
            TtsBackend.VITS, TtsBackend.STYLE_BERT_VITS2, TtsBackend.XTTS, TtsBackend.SILERO, TtsBackend.COSYVOICE -> selfHostedVoice.voice
        }

    fun actorVoice(actor: String?): String {
        if (actor.isNullOrBlank()) return defaultVoice
        return voices["$backendScopeKey:$actor"].orEmpty().ifBlank { defaultVoice }
    }

    fun withVoice(actor: String, voice: String): VoicePreferences {
        val key = "$backendScopeKey:$actor"
        return copy(voices = if (voice.isBlank()) voices - key else voices + (key to voice))
    }

    fun json(seal: (String) -> String = { it }) = jsonOf(
        "enabled" to enabled, "automatic" to automatic, "includeUser" to includeUser,
        "quoteOnly" to quoteOnly, "skipActions" to skipActions, "skipCode" to skipCode, "skipTags" to skipTags,
        "filter" to filter, "speed" to speed, "pitch" to pitch, "systemVoice" to systemVoice,
        "ttsBackend" to activeBackend.name,
        "ttsEndpoint" to ttsEndpoint,
        "ttsApiKey" to seal(ttsApiKey),
        "ttsProfiles" to JSONObject().apply {
            inactiveTtsCredentials.forEach { (backend, credentials) ->
                if (backend != activeBackend && backend.hasOwnCredentials) {
                    put(backend.name, jsonOf("endpoint" to credentials.endpoint, "key" to seal(credentials.key)))
                }
            }
        },
        "geminiTtsProvider" to geminiTtsProvider,
        "geminiTtsModel" to geminiTtsModel,
        "geminiTtsVoice" to geminiTtsVoice,
        "geminiTtsStyle" to geminiTtsStyle,
        "novelAiTtsProvider" to novelAiTtsProvider,
        "novelAiTtsVoice" to novelAiTtsVoice,
        "selfHostedVoices" to JSONObject().apply {
            selfHostedVoices.forEach { (backend, voice) -> put(backend.name, voice.json()) }
        },
        "edgeVoice" to edgeVoice,
        "elevenLabsVoiceId" to elevenLabsVoiceId,
        "elevenLabsModelId" to elevenLabsModelId,
        "fishReferenceId" to fishReferenceId,
        "gptSovitsRefAudio" to gptSovitsRefAudio,
        "gptSovitsPromptText" to gptSovitsPromptText,
        "gptSovitsPromptLang" to gptSovitsPromptLang,
        "gptSovitsTextLang" to gptSovitsTextLang,
        "backendId" to backendId, "backendModel" to backendModel, "backendVoice" to backendVoice, "voices" to JSONObject(voices),
        "dictationEnabled" to dictationEnabled, "dictationMode" to dictationMode.name,
        "sttBackend" to sttBackend.name,
        "sttEndpoint" to sttEndpoint,
        "sttApiKey" to seal(sttApiKey),
        "sttModel" to sttModel,
        "language" to language, "requireOffline" to requireOffline, "shortcuts" to JSONObject(shortcuts),
        "translatedText" to translatedText,
    )

    companion object {
        fun from(row: JSONObject?, unseal: (String) -> String = { it }): VoicePreferences {
            if (row == null) return VoicePreferences()
            val legacyBackendId = row.string("backendId")
            val choice = TtsBackend.entries.firstOrNull { it.name == row.string("ttsBackend") }
            val parsedTts = if (legacyBackendId.isNotBlank() &&
                (choice == null || choice == TtsBackend.SYSTEM && !row.has("ttsProfiles"))) {
                TtsBackend.OPENAI_COMPAT
            } else choice ?: TtsBackend.SYSTEM
            val parsedStt = SttBackend.entries.firstOrNull { it.name == row.string("sttBackend") }
                ?: SttBackend.SYSTEM
            val profiles = row.optJSONObject("ttsProfiles")
            return VoicePreferences(
                enabled = row.optBoolean("enabled", true), automatic = row.optBoolean("automatic"),
                includeUser = row.optBoolean("includeUser"), quoteOnly = row.optBoolean("quoteOnly"),
                skipActions = row.optBoolean("skipActions"), skipCode = row.optBoolean("skipCode", true),
                skipTags = row.optBoolean("skipTags"), filter = row.string("filter"),
                speed = row.optDouble("speed", 1.0).toFloat().coerceIn(0.5f, 2f),
                pitch = row.optDouble("pitch", 1.0).toFloat().coerceIn(0.5f, 2f), systemVoice = row.string("systemVoice"),
                ttsBackend = parsedTts,
                ttsEndpoint = row.string("ttsEndpoint"),
                ttsApiKey = unseal(row.string("ttsApiKey")),
                edgeVoice = row.string("edgeVoice").ifBlank { "zh-CN-XiaoxiaoNeural" },
                elevenLabsVoiceId = row.string("elevenLabsVoiceId"),
                elevenLabsModelId = row.string("elevenLabsModelId").ifBlank { "eleven_multilingual_v2" },
                fishReferenceId = row.string("fishReferenceId"),
                gptSovitsRefAudio = row.string("gptSovitsRefAudio"),
                gptSovitsPromptText = row.string("gptSovitsPromptText"),
                gptSovitsPromptLang = row.string("gptSovitsPromptLang").ifBlank { "zh" },
                gptSovitsTextLang = row.string("gptSovitsTextLang").ifBlank { "auto" },
                backendId = legacyBackendId,
                backendModel = row.string("backendModel"),
                backendVoice = row.string("backendVoice"),
                voices = row.optJSONObject("voices").variableStrings(),
                dictationEnabled = row.optBoolean("dictationEnabled", true),
                dictationMode = DictationMode.entries.firstOrNull { it.name == row.string("dictationMode") } ?: DictationMode.APPEND,
                sttBackend = parsedStt,
                sttEndpoint = row.string("sttEndpoint"),
                sttApiKey = unseal(row.string("sttApiKey")),
                sttModel = row.string("sttModel").ifBlank { "whisper-1" },
                language = row.string("language"), requireOffline = row.optBoolean("requireOffline"),
                shortcuts = row.optJSONObject("shortcuts").variableStrings(),
                translatedText = row.optBoolean("translatedText"),
                inactiveTtsCredentials = TtsBackend.entries.filter { it != parsedTts && it.hasOwnCredentials }
                    .mapNotNull { backend ->
                        profiles?.optJSONObject(backend.name)?.let { backend to TtsCredentials(it.string("endpoint"), unseal(it.string("key"))) }
                    }.toMap(),
                geminiTtsProvider = row.string("geminiTtsProvider"),
                geminiTtsModel = row.string("geminiTtsModel").ifBlank { DEFAULT_GEMINI_TTS_MODEL },
                geminiTtsVoice = row.string("geminiTtsVoice").ifBlank { DEFAULT_GEMINI_TTS_VOICE },
                geminiTtsStyle = row.string("geminiTtsStyle").take(1000),
                novelAiTtsProvider = row.string("novelAiTtsProvider"),
                novelAiTtsVoice = row.string("novelAiTtsVoice").ifBlank { DEFAULT_NOVELAI_TTS_VOICE },
                selfHostedVoices = TtsBackend.entries.filter(TtsBackend::selfHosted).mapNotNull { backend ->
                    row.optJSONObject("selfHostedVoices")?.optJSONObject(backend.name)?.let { backend to SelfHostedVoiceOptions.from(it) }
                }.toMap(),
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
