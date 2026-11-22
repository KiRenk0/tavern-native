package app.tavern.nativeclient.media

import app.tavern.nativeclient.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale

private const val TTS_AUDIO_LIMIT = 12 * 1024 * 1024
private val geminiModelId = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
private val pcmRate = Regex("(?:^|;)\\s*rate\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE)
private val pcmChannels = Regex("(?:^|;)\\s*channels\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE)

internal val geminiTtsModels = listOf(DEFAULT_GEMINI_TTS_MODEL, "gemini-3.1-flash-tts-preview",
    "gemini-2.5-flash-preview-tts", "gemini-2.5-pro-preview-tts")

internal data class ServiceVoice(val id: String, val name: String = id, val description: String = "")

private val geminiVoices = listOf(
    "Zephyr" to "明亮", "Puck" to "活泼", "Charon" to "说明性", "Kore" to "坚定",
    "Fenrir" to "激昂", "Leda" to "年轻", "Orus" to "坚定", "Aoede" to "轻快",
    "Callirrhoe" to "随和", "Autonoe" to "明亮", "Enceladus" to "带气声", "Iapetus" to "清晰",
    "Umbriel" to "随和", "Algieba" to "顺滑", "Despina" to "顺滑", "Erinome" to "清晰",
    "Algenib" to "粗哑", "Rasalgethi" to "说明性", "Laomedeia" to "活泼", "Achernar" to "柔和",
    "Alnilam" to "坚定", "Schedar" to "平稳", "Gacrux" to "成熟", "Pulcherrima" to "直接",
    "Achird" to "友好", "Zubenelgenubi" to "随意", "Vindemiatrix" to "温和",
    "Sadachbia" to "生动", "Sadaltager" to "见多识广", "Sulafat" to "温暖",
).map { (id, description) -> ServiceVoice(id, description = description) }

private val novelAiVoices = listOf("Ligeia", "Aini", "Orea", "Claea", "Lim", "Aurae", "Naia", "Aulon", "Elei",
    "Ogma", "Raid", "Pega", "Lam").map { ServiceVoice(it) }

internal val TtsBackend.hasVoiceCatalog: Boolean
    get() = selfHosted || this == TtsBackend.GEMINI_NATIVE || this == TtsBackend.NOVELAI || this == TtsBackend.ELEVENLABS

internal suspend fun listServiceVoices(options: VoicePreferences): List<ServiceVoice> = when (options.activeBackend) {
    TtsBackend.GEMINI_NATIVE -> geminiVoices
    TtsBackend.NOVELAI -> novelAiVoices
    TtsBackend.ELEVENLABS -> elevenLabsVoices(options)
    else -> if (options.activeBackend.selfHosted) selfHostedVoiceCatalog(options) else emptyList()
}

internal fun checkedGeminiTtsModel(value: String): String =
    value.trim().removePrefix("models/").ifBlank { DEFAULT_GEMINI_TTS_MODEL }.also {
        require(geminiModelId.matches(it)) { "Gemini 语音模型 ID 无效" }
    }

internal suspend fun synthesizeGeminiVoice(
    provider: Provider, options: VoicePreferences, voice: String, text: String,
): ByteArray {
    require(TtsBackend.GEMINI_NATIVE.accepts(provider)) { "请选择已配置 Key 的 Google Gemini 连接" }
    val key = provider.apiKey.trim()
    val model = checkedGeminiTtsModel(options.geminiTtsModel)
    val root = ProviderProtocols.geminiApiRoot(checkedAddress(provider.endpoint).toString())
    val style = options.geminiTtsStyle.trim().take(1000)
    val interactions = model.startsWith("gemini-3.8-")
    val payload = if (interactions) {
        val annotations = style.takeIf(String::isNotEmpty)?.let {
            JSONArray().put(jsonOf("type" to "speech_metadata", "style" to it))
        }
        jsonOf(
            "model" to model, "store" to false,
            "input" to JSONArray().put(jsonOf("type" to "user_input",
                "content" to JSONArray().put(jsonOf("type" to "text", "text" to text, "annotations" to annotations)))),
            "response_format" to jsonOf("type" to "audio"),
            "generation_config" to jsonOf("speech_config" to JSONArray().put(jsonOf("voice" to voice))),
        )
    } else {
        val input = if (style.isEmpty()) text else "朗读风格：$style\n\n$text"
        jsonOf(
            "contents" to JSONArray().put(jsonOf("role" to "user", "parts" to JSONArray().put(jsonOf("text" to input)))),
            "generationConfig" to jsonOf("responseModalities" to JSONArray().put("AUDIO"),
                "speechConfig" to jsonOf("voiceConfig" to jsonOf("prebuiltVoiceConfig" to jsonOf("voiceName" to voice)))),
        )
    }
    val path = if (interactions) "interactions" else "models/$model:generateContent"
    return request("$root/$path", payload, mapOf("x-goog-api-key" to key), redactError = { it.redactValue(key) }) { input, _ ->
        geminiSpeechAudio(JSONObject(input.readLimited().decodeToString()), interactions)
    }
}

internal fun geminiSpeechAudio(result: JSONObject, interactions: Boolean): ByteArray {
    val parts = if (interactions) {
        val audio = result.optJSONArray("steps")?.objects().orEmpty().asSequence()
            .filter { it.string("type") == "model_output" }
            .flatMap { it.optJSONArray("content")?.objects().orEmpty().asSequence() }
            .lastOrNull { it.string("type") == "audio" && it.string("data").isNotBlank() }
        listOfNotNull(audio)
    } else {
        result.optJSONArray("candidates")?.objects().orEmpty().firstNotNullOfOrNull { candidate ->
            candidate.optJSONObject("content")?.optJSONArray("parts")?.objects().orEmpty().mapNotNull { it.optJSONObject("inlineData") }
                .filter { it.string("data").isNotBlank() }.takeIf(List<JSONObject>::isNotEmpty)
        }.orEmpty()
    }
    check(parts.isNotEmpty()) { "Google Gemini 没有返回可播放的语音" }
    fun mime(part: JSONObject) = part.string("mime_type").ifBlank { part.string("mimeType") }.lowercase(Locale.ROOT)
        .ifBlank { if (interactions) "audio/wav" else "audio/l16" }

    fun bytes(part: JSONObject, limit: Int): ByteArray {
        val encoded = part.string("data")
        require(encoded.length <= (limit + 2) / 3 * 4) { "Gemini 音频响应超过大小限制" }
        return Base64.getDecoder().decode(encoded).also {
            require(it.isNotEmpty() && it.size <= limit) { "Gemini 音频响应大小无效" }
        }
    }

    val type = mime(parts.first())
    val kind = type.substringBefore(';').trim()
    if (kind !in setOf("audio/l16", "audio/pcm", "audio/raw")) {
        require(kind.startsWith("audio/") && parts.size == 1) { "Gemini 返回了无法合并的音频格式" }
        return bytes(parts.single(), TTS_AUDIO_LIMIT)
    }
    fun rate(mime: String) = pcmRate.find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: 24_000
    fun channels(mime: String) = pcmChannels.find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: 1
    val sampleRate = rate(type)
    val channelCount = channels(type)
    require(sampleRate in 8_000..96_000 && channelCount in 1..2) { "Gemini PCM 采样参数无效" }
    val pcm = ByteArrayOutputStream()
    for (part in parts) {
        val contentType = mime(part)
        require(contentType.substringBefore(';').trim() == kind && rate(contentType) == sampleRate &&
            channels(contentType) == channelCount) { "Gemini 音频片段格式不一致" }
        pcm.write(bytes(part, TTS_AUDIO_LIMIT - pcm.size()))
    }
    require(pcm.size() % (2 * channelCount) == 0) { "Gemini PCM 音频数据不完整" }
    return encodePcm16Wav(pcm.toByteArray(), sampleRate, channelCount)
}

internal suspend fun synthesizeNovelAiVoice(provider: Provider, voice: String, text: String): ByteArray {
    require(TtsBackend.NOVELAI.accepts(provider)) { "请选择已配置 Key 的 NovelAI 连接" }
    require(voice.isNotBlank()) { "请填写 NovelAI 音色种子" }
    val query = listOf("text" to text, "voice" to "-1", "seed" to voice, "opus" to "false", "version" to "v2")
        .joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, "UTF-8")}" }
    val key = provider.apiKey.trim()
    return request("${NovelAi.speechRoot(provider)}/ai/generate-voice?$query",
        headers = mapOf("Authorization" to "Bearer $key", "Accept" to "audio/mpeg"),
        redactError = { it.redactValue(key) }) { input, type ->
        check(!type.startsWith("application/json", true) && !type.startsWith("text/", true)) { "NovelAI 返回了非音频响应" }
        input.readLimited(TTS_AUDIO_LIMIT).also { check(it.isNotEmpty()) { "NovelAI 未返回音频" } }
    }
}

internal fun elevenLabsTtsRoot(endpoint: String): String =
    checkedAddress(endpoint.trim().ifBlank { "https://api.elevenlabs.io" }).toString().trimEnd('/')
        .removeSuffix("/v1/text-to-speech").removeSuffix("/v2/voices").removeSuffix("/v1/voices")
        .removeSuffix("/v1").removeSuffix("/v2")

private suspend fun elevenLabsVoices(options: VoicePreferences): List<ServiceVoice> {
    val key = options.ttsApiKey.trim()
    require(key.isNotEmpty()) { "请填写 ElevenLabs API Key" }
    val root = elevenLabsTtsRoot(options.ttsEndpoint)
    suspend fun load(path: String) = request("$root$path", headers = mapOf("xi-api-key" to key),
        redactError = { it.redactValue(key) }) { input, _ -> JSONObject(input.readLimited().decodeToString()) }

    val catalog = linkedMapOf<String, ServiceVoice>()
    fun readPage(page: JSONObject) {
        page.optJSONArray("voices")?.objects().orEmpty().forEach { voice ->
            val id = voice.string("voice_id").trim()
            if (id.isNotEmpty()) {
                catalog[id] = ServiceVoice(id, voice.string("name").ifBlank { id }, voice.string("description").take(200))
            }
        }
    }
    var cursor: String? = null
    val seen = hashSetOf<String>()
    repeat(10) { index ->
        val suffix = cursor?.let { "&next_page_token=${URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
        val page = try {
            load("/v2/voices?page_size=100&include_total_count=false$suffix")
        } catch (e: HttpRequestError) {
            if (index != 0 || e.status !in setOf(404, 405)) throw e
            readPage(load("/v1/voices"))
            return catalog.values.toList()
        }
        readPage(page)
        cursor = page.string("next_page_token").takeIf { it.isNotBlank() && seen.add(it) }
        if (!page.optBoolean("has_more") || cursor == null) return catalog.values.toList()
    }
    return catalog.values.toList()
}
