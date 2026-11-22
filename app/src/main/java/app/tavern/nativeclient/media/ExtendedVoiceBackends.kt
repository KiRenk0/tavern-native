package app.tavern.nativeclient.media

import app.tavern.nativeclient.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToInt

private const val EXTENDED_TTS_AUDIO_LIMIT = 16 * 1024 * 1024
internal const val DEFAULT_AZURE_REGION = "eastus"
internal const val DEFAULT_AZURE_VOICE = "zh-CN-XiaoxiaoNeural"
internal const val DEFAULT_ALLTALK_LANGUAGE = "en"
internal const val DEFAULT_KOKORO_VOICE = "af_heart"
private val azureRegionRegex = Regex("^[a-z0-9-]{2,40}$")

internal val curatedKokoroVoices = listOf(
    ServiceVoice("af_heart", "Heart (美式女声 · 推荐)", "en-US"),
    ServiceVoice("af_bella", "Bella (美式女声)", "en-US"),
    ServiceVoice("af_nicole", "Nicole (美式女声 · 柔和)", "en-US"),
    ServiceVoice("af_sarah", "Sarah (美式女声)", "en-US"),
    ServiceVoice("af_sky", "Sky (美式女声)", "en-US"),
    ServiceVoice("am_adam", "Adam (美式男声)", "en-US"),
    ServiceVoice("am_michael", "Michael (美式男声)", "en-US"),
    ServiceVoice("bf_emma", "Emma (英式女声)", "en-GB"),
    ServiceVoice("bm_george", "George (英式男声)", "en-GB"),
    ServiceVoice("jf_alpha", "Alpha (日语女声)", "ja-JP"),
    ServiceVoice("jm_kumo", "Kumo (日语男声)", "ja-JP"),
    ServiceVoice("zf_xiaobei", "小贝 (中文女声)", "zh-CN"),
    ServiceVoice("zf_xiaoni", "小妮 (中文女声)", "zh-CN"),
    ServiceVoice("zf_xiaoxiao", "晓晓 (中文女声)", "zh-CN"),
    ServiceVoice("zm_yunjian", "云健 (中文男声)", "zh-CN"),
    ServiceVoice("zm_yunxi", "云希 (中文男声)", "zh-CN"),
)

internal val allTalkLanguages = listOf(
    "en", "zh-cn", "ja", "ko", "fr", "de", "es", "it", "pt", "ru", "pl", "nl", "tr", "ar", "cs", "hu",
)

internal fun azureSpeechRoot(region: String, endpointOverride: String): String {
    val custom = endpointOverride.trim()
    if (custom.isNotBlank()) {
        return checkedAddress(custom).toString().trimEnd('/')
            .removeSuffix("/cognitiveservices/v1")
            .removeSuffix("/cognitiveservices/voices/list")
    }
    val cleanRegion = region.trim().lowercase(Locale.ROOT).ifBlank { DEFAULT_AZURE_REGION }
    require(azureRegionRegex.matches(cleanRegion)) { "Azure 区域代码无效（例如 eastus、japaneast）" }
    return "https://$cleanRegion.tts.speech.microsoft.com"
}

internal fun buildAzureSsml(text: String, voice: String, speed: Float = 1f, pitch: Float = 1f): String {
    val cleanVoice = voice.trim().ifBlank { DEFAULT_AZURE_VOICE }
    val parts = cleanVoice.split('-')
    val xmlLang = if (parts.size >= 2) "${parts[0]}-${parts[1]}" else "en-US"
    val ratePct = ((speed.coerceIn(0.5f, 2f) - 1f) * 100f).roundToInt()
    val pitchPct = ((pitch.coerceIn(0.5f, 2f) - 1f) * 50f).roundToInt()
    val rateAttr = if (ratePct >= 0) "+$ratePct%" else "$ratePct%"
    val pitchAttr = if (pitchPct >= 0) "+$pitchPct%" else "$pitchPct%"
    val escapedVoice = escapeXmlText(cleanVoice)
    val escapedBody = escapeXmlText(text)
    return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='${escapeXmlText(xmlLang)}'>" +
        "<voice name='$escapedVoice'><prosody rate='$rateAttr' pitch='$pitchAttr'>$escapedBody</prosody></voice></speak>"
}

private fun escapeXmlText(raw: String): String = buildString(raw.length) {
    for (ch in raw) when (ch) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '"' -> append("&quot;")
        '\'' -> append("&apos;")
        else -> append(ch)
    }
}

internal fun parseAzureVoicesJson(raw: String): List<ServiceVoice> {
    val array = JSONArray(raw)
    val voices = mutableListOf<ServiceVoice>()
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val shortName = item.string("ShortName").trim()
        if (shortName.isEmpty()) continue
        val localName = item.string("LocalName").ifBlank { item.string("DisplayName") }.ifBlank { shortName }
        val locale = item.string("Locale")
        val gender = item.string("Gender")
        val desc = listOf(locale, gender).filter(String::isNotBlank).joinToString(" · ")
        voices += ServiceVoice(shortName, "$localName ($shortName)", desc)
    }
    return voices.distinctBy(ServiceVoice::id)
}

internal suspend fun listAzureVoices(options: VoicePreferences): List<ServiceVoice> {
    val key = options.ttsApiKey.trim()
    require(key.isNotBlank()) { "请填写 Azure Speech 订阅密钥" }
    val root = azureSpeechRoot(options.azureRegion, options.ttsEndpoint)
    return request(
        url = "$root/cognitiveservices/voices/list",
        headers = mapOf("Ocp-Apim-Subscription-Key" to key, "Accept" to "application/json"),
        readTimeout = 20_000,
        redactError = { it.redactValue(key) },
    ) { input, _ ->
        parseAzureVoicesJson(input.readLimited(4 * 1024 * 1024).decodeToString())
    }
}

internal suspend fun synthesizeAzureSpeech(options: VoicePreferences, voice: String, text: String): ByteArray {
    val key = options.ttsApiKey.trim()
    require(key.isNotBlank()) { "请填写 Azure Speech 订阅密钥" }
    val targetVoice = voice.trim().ifBlank { options.azureVoice.trim() }.ifBlank { DEFAULT_AZURE_VOICE }
    val root = azureSpeechRoot(options.azureRegion, options.ttsEndpoint)
    val ssml = buildAzureSsml(text, targetVoice, options.speed, options.pitch)
    return request(
        url = "$root/cognitiveservices/v1",
        headers = mapOf(
            "Ocp-Apim-Subscription-Key" to key,
            "X-Microsoft-OutputFormat" to "audio-24khz-48kbitrate-mono-mp3",
            "User-Agent" to "TavernNative",
            "Accept" to "audio/mpeg",
        ),
        readTimeout = 60_000,
        upload = HttpUpload("application/ssml+xml; charset=utf-8", ssml.toByteArray(Charsets.UTF_8)),
        redactError = { it.redactValue(key) },
    ) { input, mime ->
        check(!mime.startsWith("application/json", true) && !mime.startsWith("text/", true)) {
            "Azure Speech 返回了非音频响应"
        }
        input.readLimited(EXTENDED_TTS_AUDIO_LIMIT).also { check(it.isNotEmpty()) { "Azure Speech 未返回音频" } }
    }
}

internal fun allTalkRoot(endpoint: String): String =
    checkedAddress(endpoint.trim().ifBlank { "http://127.0.0.1:7851" }).toString().trimEnd('/')
        .removeSuffix("/api/tts-generate")
        .removeSuffix("/api/voices")
        .removeSuffix("/api")

internal fun parseAllTalkVoicesJson(raw: String): List<ServiceVoice> {
    val parsed = JSONTokener(raw).nextValue()
    val array = when (parsed) {
        is JSONArray -> parsed
        is JSONObject -> parsed.optJSONArray("voices") ?: parsed.optJSONArray("speakers")
        else -> null
    } ?: error("AllTalk 返回了无效的音色列表")
    val result = mutableListOf<ServiceVoice>()
    for (i in 0 until array.length()) {
        val item = array.opt(i)
        val voice = when (item) {
            is String -> item.trim().takeIf(String::isNotEmpty)?.let { ServiceVoice(it, it.substringBeforeLast('.').ifBlank { it }) }
            is JSONObject -> {
                val id = item.string("id").ifBlank { item.string("name") }.ifBlank { item.string("voice") }.trim()
                if (id.isEmpty()) null else ServiceVoice(id, item.string("name").ifBlank { id }, item.string("language"))
            }
            else -> null
        }
        if (voice != null && voice.id.length <= 500 && voice.id.none(Char::isISOControl)) result += voice
    }
    return result.distinctBy(ServiceVoice::id)
}

internal suspend fun listAllTalkVoices(options: VoicePreferences): List<ServiceVoice> {
    val root = allTalkRoot(options.ttsEndpoint)
    val key = options.ttsApiKey.trim()
    val headers = buildMap {
        put("Accept", "application/json")
        if (key.isNotBlank()) put("Authorization", "Bearer $key")
    }
    return request(
        url = "$root/api/voices",
        headers = headers,
        readTimeout = 20_000,
        redactError = { it.redactValue(key) },
    ) { input, _ ->
        parseAllTalkVoicesJson(input.readLimited(2 * 1024 * 1024).decodeToString())
    }
}

internal fun buildAllTalkForm(text: String, voice: String, language: String): String {
    val fields = linkedMapOf(
        "text_input" to text,
        "text_filtering" to "standard",
        "character_voice_gen" to voice,
        "narrator_enabled" to "false",
        "narrator_voice_gen" to voice,
        "text_not_inside" to "character",
        "language" to language.trim().ifBlank { DEFAULT_ALLTALK_LANGUAGE },
        "output_file_name" to "tavern_tts",
        "output_file_timestamp" to "true",
        "autoplay" to "false",
        "autoplay_volume" to "0.8",
    )
    return fields.entries.joinToString("&") { (k, v) ->
        "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
    }
}

internal fun resolveAllTalkAudioUrl(root: String, outputUrl: String): String {
    val trimmed = outputUrl.trim()
    require(trimmed.isNotBlank()) { "AllTalk 未返回音频地址" }
    val base = URI(root)
    val resolved = if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
        URI(trimmed)
    } else {
        base.resolve(if (trimmed.startsWith("/")) trimmed else "/$trimmed")
    }.normalize()
    val sameOrigin = resolved.scheme == base.scheme &&
        resolved.host.equals(base.host, true) &&
        resolved.port == base.port
    require(sameOrigin && resolved.rawUserInfo == null && resolved.rawFragment == null) {
        "AllTalk 返回了跨站音频地址"
    }
    return resolved.toString()
}

internal suspend fun synthesizeAllTalk(options: VoicePreferences, voice: String, text: String): ByteArray {
    val targetVoice = voice.trim().ifBlank { options.allTalkVoice.trim() }
    require(targetVoice.isNotBlank()) { "请选择或填写 AllTalk 音色文件" }
    val root = allTalkRoot(options.ttsEndpoint)
    val key = options.ttsApiKey.trim()
    val headers = buildMap {
        put("Accept", "application/json, audio/*")
        if (key.isNotBlank()) put("Authorization", "Bearer $key")
    }
    val form = buildAllTalkForm(text, targetVoice, options.allTalkLanguage)
    val response = request(
        url = "$root/api/tts-generate",
        headers = headers,
        readTimeout = 120_000,
        upload = HttpUpload("application/x-www-form-urlencoded", form.toByteArray(Charsets.UTF_8)),
        redactError = { it.redactValue(key) },
    ) { input, mime ->
        val mediaType = mime.substringBefore(';').trim().lowercase(Locale.ROOT)
        val bytes = input.readLimited(EXTENDED_TTS_AUDIO_LIMIT)
        mediaType to bytes
    }
    val (mime, bytes) = response
    if (mime.startsWith("audio/") || mime == "application/octet-stream") {
        check(bytes.isNotEmpty()) { "AllTalk 未返回音频" }
        return bytes
    }
    val json = JSONObject(bytes.decodeToString())
    val audioPath = json.string("output_file_url").ifBlank { json.string("output_cache_url") }
    val audioUrl = resolveAllTalkAudioUrl(root, audioPath)
    val fetchHeaders = buildMap {
        put("Accept", "audio/*")
        if (key.isNotBlank()) put("Authorization", "Bearer $key")
    }
    return request(
        url = audioUrl,
        headers = fetchHeaders,
        readTimeout = 60_000,
        redactError = { it.redactValue(key) },
    ) { input, _ ->
        input.readLimited(EXTENDED_TTS_AUDIO_LIMIT).also { check(it.isNotEmpty()) { "AllTalk 音频文件为空" } }
    }
}

internal fun kokoroRoot(endpoint: String): String =
    checkedAddress(endpoint.trim().ifBlank { "http://127.0.0.1:8880" }).toString().trimEnd('/')
        .removeSuffix("/v1/audio/speech")
        .removeSuffix("/v1/audio/voices")
        .removeSuffix("/audio/speech")
        .removeSuffix("/audio/voices")
        .removeSuffix("/v1")

internal fun parseKokoroVoicesJson(raw: String): List<ServiceVoice> {
    val parsed = JSONTokener(raw).nextValue()
    val array = when (parsed) {
        is JSONArray -> parsed
        is JSONObject -> parsed.optJSONArray("voices") ?: parsed.optJSONArray("data")
        else -> null
    } ?: error("Kokoro 返回了无效的音色列表")
    val known = curatedKokoroVoices.associateBy(ServiceVoice::id)
    val voices = mutableListOf<ServiceVoice>()
    for (i in 0 until array.length()) {
        val item = array.opt(i)
        val voice = when (item) {
            is String -> item.trim().takeIf(String::isNotEmpty)?.let { id -> known[id] ?: ServiceVoice(id) }
            is JSONObject -> {
                val id = item.string("id").ifBlank { item.string("name") }.trim()
                if (id.isEmpty()) null else ServiceVoice(id, item.string("name").ifBlank { id }, item.string("language"))
            }
            else -> null
        }
        if (voice != null && voice.id.length <= 200 && voice.id.none(Char::isISOControl)) voices += voice
    }
    return voices.distinctBy(ServiceVoice::id)
}

internal suspend fun listKokoroVoices(options: VoicePreferences): List<ServiceVoice> {
    val endpoint = options.ttsEndpoint.trim()
    if (endpoint.isBlank()) return curatedKokoroVoices
    val root = kokoroRoot(endpoint)
    val key = options.ttsApiKey.trim()
    val headers = buildMap {
        put("Accept", "application/json")
        if (key.isNotBlank()) put("Authorization", "Bearer $key")
    }
    return try {
        request(
            url = "$root/v1/audio/voices",
            headers = headers,
            readTimeout = 20_000,
            redactError = { it.redactValue(key) },
        ) { input, _ ->
            parseKokoroVoicesJson(input.readLimited(2 * 1024 * 1024).decodeToString())
        }
    } catch (e: HttpRequestError) {
        if (e.status !in setOf(404, 405)) throw e
        curatedKokoroVoices
    }
}

internal fun buildKokoroPayload(text: String, voice: String, speed: Float): JSONObject = jsonOf(
    "model" to "kokoro",
    "input" to text,
    "voice" to voice.trim().ifBlank { DEFAULT_KOKORO_VOICE },
    "response_format" to "mp3",
    "speed" to speed.coerceIn(0.5f, 2f),
)

internal suspend fun synthesizeKokoro(options: VoicePreferences, voice: String, text: String): ByteArray {
    val targetVoice = voice.trim().ifBlank { options.kokoroVoice.trim() }.ifBlank { DEFAULT_KOKORO_VOICE }
    val root = kokoroRoot(options.ttsEndpoint)
    val key = options.ttsApiKey.trim()
    val headers = buildMap {
        put("Accept", "audio/mpeg, audio/wav, audio/*")
        if (key.isNotBlank()) put("Authorization", "Bearer $key")
    }
    val payload = buildKokoroPayload(text, targetVoice, options.speed)
    return request(
        url = "$root/v1/audio/speech",
        body = payload,
        headers = headers,
        readTimeout = 120_000,
        redactError = { it.redactValue(key) },
    ) { input, mime ->
        check(!mime.startsWith("application/json", true) && !mime.startsWith("text/", true)) {
            "Kokoro TTS 返回了非音频响应"
        }
        input.readLimited(EXTENDED_TTS_AUDIO_LIMIT).also { check(it.isNotEmpty()) { "Kokoro TTS 未返回音频" } }
    }
}
