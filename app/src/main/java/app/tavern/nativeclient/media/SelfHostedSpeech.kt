package app.tavern.nativeclient.media

import app.tavern.nativeclient.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLEncoder
import java.util.Locale

private val vitsModels = listOf("VITS", "W2V2-VITS", "BERT-VITS2")
private val styleBertVoice = Regex("([0-9]+)-([0-9]+)-(.+)")
private const val SILERO_SESSION = "tavern-native"
private const val LOCAL_TTS_AUDIO_BYTES = 16 * 1024 * 1024

internal fun VoicePreferences.selfHostedSpeechRoot(): String {
    val address = checkedAddress(ttsEndpoint)
    val base = address.toASCIIString().trimEnd('/')
    return if (activeBackend == TtsBackend.SILERO && address.path.trim('/').isEmpty()) "$base/tts" else base
}

private fun VoicePreferences.speechHeaders(accept: String): Map<String, String> = buildMap {
    put("Accept", accept)
    put("Cache-Control", "no-cache")
    if (ttsApiKey.isNotBlank()) put("Authorization", "Bearer ${ttsApiKey.trim()}")
}

internal fun VoicePreferences.checkSelfHostedVoice(voice: String = defaultVoice) {
    require(voice.isNotBlank() && voice.length <= 500 && voice.none { it.isISOControl() }) { "请选择或填写有效的音色 ID" }
    when (activeBackend) {
        TtsBackend.VITS -> require(voice.substringBefore('&') in vitsModels &&
            (voice.substringAfter('&', "").toIntOrNull() ?: -1) >= 0) { "VITS 音色 ID 应为 VITS&0 等格式" }
        TtsBackend.STYLE_BERT_VITS2 -> require(styleBertVoice.matches(voice)) { "Style-Bert-VITS2 音色 ID 应为 0-0-Neutral 等格式" }
        else -> Unit
    }
}

internal suspend fun selfHostedVoiceCatalog(options: VoicePreferences): List<ServiceVoice> {
    val path = when (options.activeBackend) {
        TtsBackend.VITS -> "/voice/speakers"
        TtsBackend.STYLE_BERT_VITS2 -> "/models/info"
        else -> "/speakers"
    }
    val response = request(options.selfHostedSpeechRoot() + path, headers = options.speechHeaders("application/json"),
        readTimeout = 20_000) { input, _ -> JSONTokener(input.readLimited(2 * 1024 * 1024).decodeToString()).nextValue() }
    val voices = when (options.activeBackend) {
        TtsBackend.VITS -> vitsVoices(response as? JSONObject ?: error("VITS 返回了无效的音色列表"))
        TtsBackend.STYLE_BERT_VITS2 -> styleBertVoices(response as? JSONObject ?: error("Style-Bert-VITS2 返回了无效的模型列表"))
        else -> namedVoices(response as? JSONArray ?: (response as? JSONObject)?.optJSONArray("speakers")
            ?: error("语音服务返回了无效的音色列表"))
    }
    return voices.distinctBy(ServiceVoice::id).take(2_000).toList()
}

private fun vitsVoices(response: JSONObject): Sequence<ServiceVoice> = sequence {
    for (type in vitsModels) {
        for (voice in response.optJSONArray(type)?.objects().orEmpty()) {
            val speaker = voice.string("id").toIntOrNull()?.takeIf { it >= 0 } ?: continue
            val name = voice.string("name").ifBlank { speaker.toString() }.take(200)
            val langs = voice.optJSONArray("lang")?.let { items ->
                (0 until items.length()).joinToString("、") { items.optString(it) }
            } ?: voice.string("lang")
            yield(ServiceVoice("$type&$speaker", "[$type] $name", langs.take(200)))
        }
    }
}

private fun styleBertVoices(response: JSONObject): Sequence<ServiceVoice> = sequence {
    for (model in response.keys()) {
        if ((model.toIntOrNull() ?: -1) < 0) continue
        val config = response.optJSONObject(model) ?: continue
        val speakers = config.optJSONObject("spk2id") ?: continue
        val styles = config.optJSONObject("style2id") ?: continue
        for (speaker in speakers.keys()) {
            val speakerId = speakers.string(speaker).toIntOrNull()?.takeIf { it >= 0 } ?: continue
            for (style in styles.keys()) {
                if (style.isNotBlank() && style.length <= 400 && style.none(Char::isISOControl)) {
                    yield(ServiceVoice("$model-$speakerId-$style", "${speaker.take(200)} ($style)", "模型 $model"))
                }
            }
        }
    }
}

private fun namedVoices(response: JSONArray): Sequence<ServiceVoice> = (0 until response.length()).asSequence().mapNotNull { index ->
    val item = response.opt(index)
    val voice = when (item) {
        is String -> ServiceVoice(item)
        is JSONObject -> {
            val id = item.string("voice_id").ifBlank { item.string("id") }.ifBlank { item.string("name") }
            ServiceVoice(id, item.string("name").ifBlank { id }.take(200), item.string("lang").take(200))
        }
        else -> return@mapNotNull null
    }
    voice.takeIf { it.id.isNotBlank() && it.id.length <= 500 && it.id.none(Char::isISOControl) }
}

internal suspend fun synthesizeSelfHostedVoice(options: VoicePreferences, voice: String, text: String): ByteArray {
    options.checkSelfHostedVoice(voice)
    val root = options.selfHostedSpeechRoot()
    val headers = options.speechHeaders("audio/*, application/octet-stream")
    val settings = options.readSpeechParameters()
    return when (options.activeBackend) {
        TtsBackend.VITS -> {
            val (model, speaker) = voice.split('&', limit = 2)
            val fields = settings.filterKeys { it in setOf("lang", "format", "length", "noise", "noisew", "segment_size") }.toMutableMap()
            fields["text"] = text
            fields["id"] = speaker
            when (model) {
                "W2V2-VITS" -> fields["emotion"] = settings.getValue("dim_emotion")
                "BERT-VITS2" -> {
                    fields.putAll(settings.filterKeys { it in setOf("sdp_ratio", "emotion") })
                    if (settings["text_prompt"] != "") fields["text_prompt"] = settings.getValue("text_prompt")
                    if (settings["style_text"] != "") fields.putAll(settings.filterKeys { it in setOf("style_text", "style_weight") })
                }
            }
            val form = HttpUpload("application/x-www-form-urlencoded", fields.asForm().encodeToByteArray())
            readSpeech("$root/voice/${model.lowercase(Locale.ROOT)}", headers, upload = form)
        }
        TtsBackend.STYLE_BERT_VITS2 -> {
            val (model, speaker, style) = requireNotNull(styleBertVoice.matchEntire(voice)).destructured
            val fields = settings.filterValues { it != "" }.toMutableMap()
            if ("assist_text" !in fields) fields.remove("assist_text_weight")
            fields.putAll(mapOf("text" to text.replace("<br>", "\n"), "model_id" to model, "speaker_id" to speaker, "style" to style))
            readSpeech("$root/voice?${fields.asForm()}", headers)
        }
        TtsBackend.XTTS -> {
            val language = settings.getValue("language")
            val controls = JSONObject(settings - "language").put("stream_chunk_size", 100)
            try {
                request("$root/set_tts_settings", controls, options.speechHeaders("application/json"), readTimeout = 20_000) { _, _ -> }
            } catch (failure: HttpRequestError) {
                if (failure.status !in setOf(404, 405) || options.selfHostedVoice.parameters.keys.any { it != "language" }) throw failure
            }
            readSpeech("$root/tts_to_audio/", headers, jsonOf("text" to text, "speaker_wav" to voice, "language" to language))
        }
        TtsBackend.SILERO -> {
            try {
                request("$root/session", jsonOf("path" to SILERO_SESSION), options.speechHeaders("application/json"), readTimeout = 20_000) { _, _ -> }
            } catch (failure: HttpRequestError) {
                if (failure.status !in setOf(404, 405)) throw failure
            }
            readSpeech("$root/generate", headers, jsonOf("text" to text, "speaker" to voice, "session" to SILERO_SESSION))
        }
        TtsBackend.COSYVOICE -> readSpeech("$root/", headers, jsonOf("text" to text, "speaker" to voice))
        else -> error("该朗读引擎不使用自建语音接口")
    }
}

private fun Map<String, Any>.asForm() = entries.joinToString("&") { (key, value) ->
    "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value.toString(), "UTF-8")}"
}

private suspend fun readSpeech(url: String, headers: Map<String, String>, body: JSONObject? = null, upload: HttpUpload? = null): ByteArray =
    request(url, body, headers, readTimeout = 120_000, upload = upload, method = "POST") { input, mime ->
        val mediaType = mime.substringBefore(';').trim().lowercase(Locale.ROOT)
        check(mediaType.startsWith("audio/") || mediaType in setOf("", "application/octet-stream", "application/ogg")) {
            "语音服务返回了非音频响应"
        }
        input.readLimited(LOCAL_TTS_AUDIO_BYTES).also { check(it.isNotEmpty()) { "语音服务未返回音频" } }
    }
