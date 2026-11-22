package app.tavern.nativeclient

import org.json.JSONObject

internal data class SelfHostedVoiceOptions(val voice: String = "", val parameters: Map<String, String> = emptyMap()) {
    fun withParameter(parameter: SpeechParameter, value: String) = copy(parameters =
        if (value == parameter.default.toString()) parameters - parameter.key else parameters + (parameter.key to value))

    fun json() = jsonOf("voice" to voice, "parameters" to JSONObject(parameters))

    companion object {
        fun from(value: JSONObject) = SelfHostedVoiceOptions(value.string("voice").take(500),
            value.optJSONObject("parameters").variableStrings())
    }
}

internal data class SpeechParameter(
    val key: String,
    val label: String,
    val default: Any,
    val choices: List<String> = emptyList(),
    val range: ClosedFloatingPointRange<Double>? = null,
) {
    fun raw(options: SelfHostedVoiceOptions): String = options.parameters[key] ?: default.toString()

    fun read(options: SelfHostedVoiceOptions): Any {
        val raw = raw(options).trim()
        val value = when (default) {
            is Boolean -> raw.toBooleanStrictOrNull()
            is Int -> raw.toIntOrNull()
            is Double -> raw.toDoubleOrNull()?.takeIf(Double::isFinite)
            else -> raw.takeIf { it.length <= 500 && (choices.isEmpty() || it in choices) }
        }
        require(value != null && (value !is Number || range == null || value.toDouble() in range)) { "${label}的设置无效" }
        return value
    }
}

private val vitsParameters = listOf(
    SpeechParameter("lang", "朗读语言", "auto", listOf("auto", "zh", "en", "ja", "ko")),
    SpeechParameter("format", "音频格式", "wav", listOf("wav", "ogg", "mp3", "flac")),
    SpeechParameter("length", "语音长度倍率", 1.0, range = 0.01..5.0),
    SpeechParameter("noise", "噪声", 0.33, range = 0.0..2.0),
    SpeechParameter("noisew", "SDP 噪声", 0.4, range = 0.0..2.0),
    SpeechParameter("segment_size", "分段长度", 50, range = 0.0..1000.0),
    SpeechParameter("dim_emotion", "W2V2-VITS 情绪编号", 0, range = 0.0..5457.0),
    SpeechParameter("sdp_ratio", "BERT-VITS2 SDP 比例", 0.2, range = 0.0..1.0),
    SpeechParameter("emotion", "BERT-VITS2 情绪编号", 0, range = 0.0..9.0),
    SpeechParameter("text_prompt", "BERT-VITS2 情绪提示词", ""),
    SpeechParameter("style_text", "BERT-VITS2 风格文本", ""),
    SpeechParameter("style_weight", "BERT-VITS2 风格权重", 1.0, range = 0.0..1.0),
)

private val styleBertParameters = listOf(
    SpeechParameter("language", "朗读语言", "JP", listOf("JP", "ZH", "EN")),
    SpeechParameter("sdp_ratio", "SDP 比例", 0.2, range = 0.0..1.0),
    SpeechParameter("noise", "噪声", 0.6, range = 0.0..2.0),
    SpeechParameter("noisew", "SDP 噪声", 0.8, range = 0.0..2.0),
    SpeechParameter("length", "语音长度倍率", 1.0, range = 0.01..5.0),
    SpeechParameter("auto_split", "按换行分段", true),
    SpeechParameter("split_interval", "段间停顿（秒）", 0.5, range = 0.0..5.0),
    SpeechParameter("assist_text", "辅助文本", ""),
    SpeechParameter("assist_text_weight", "辅助文本权重", 1.0, range = 0.0..1.0),
    SpeechParameter("style_weight", "风格权重", 1.0, range = 0.0..20.0),
    SpeechParameter("reference_audio_path", "服务端参考音频路径", ""),
)

private val xttsParameters = listOf(
    SpeechParameter("language", "朗读语言", "en",
        listOf("ar", "pt", "zh-cn", "cs", "nl", "en", "fr", "de", "it", "pl", "ru", "es", "tr", "ja", "ko", "hu")),
    SpeechParameter("speed", "语速", 1.0, range = 0.2..2.0),
    SpeechParameter("temperature", "温度", 0.75, range = 0.01..1.0),
    SpeechParameter("length_penalty", "长度惩罚", 1.0, range = 0.0..10.0),
    SpeechParameter("repetition_penalty", "重复惩罚", 5.0, range = 0.0..20.0),
    SpeechParameter("top_k", "Top K", 50, range = 0.0..100.0),
    SpeechParameter("top_p", "Top P", 0.85, range = 0.0..1.0),
    SpeechParameter("enable_text_splitting", "服务端拆分长文本", true),
)

internal val TtsBackend.speechParameters: List<SpeechParameter>
    get() = when (this) {
        TtsBackend.VITS -> vitsParameters
        TtsBackend.STYLE_BERT_VITS2 -> styleBertParameters
        TtsBackend.XTTS -> xttsParameters
        else -> emptyList()
    }

internal fun VoicePreferences.readSpeechParameters(): Map<String, Any> =
    activeBackend.speechParameters.associate { it.key to it.read(selfHostedVoice) }
