package app.tavern.nativeclient.media

import app.tavern.nativeclient.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Base64
import java.util.Locale

private const val MAX_RVC_AUDIO_BYTES = 16 * 1024 * 1024
internal val rvcF0Methods = listOf("rmvpe", "harvest", "pm", "crepe", "fcpe")

internal data class RvcSettings(
    val enabled: Boolean = false,
    val endpoint: String = "",
    val apiKey: String = "",
    val modelName: String = "",
    val pitchShift: Int = 0,
    val indexRate: Float = 0.75f,
    val filterRadius: Int = 3,
    val f0Method: String = "rmvpe",
    val rmsMixRate: Float = 0.25f,
    val protect: Float = 0.33f,
) {
    fun validated(): RvcSettings {
        val cleanEndpoint = endpoint.trim()
        val cleanModel = modelName.trim()
        val cleanMethod = f0Method.trim().lowercase(Locale.ROOT).takeIf { it in rvcF0Methods } ?: "rmvpe"
        if (enabled) {
            require(cleanEndpoint.isNotBlank()) { "请填写 RVC 服务地址" }
            checkedAddress(cleanEndpoint)
            require(cleanModel.isNotBlank()) { "请选择或填写 RVC 模型名称" }
        } else if (cleanEndpoint.isNotBlank()) {
            checkedAddress(cleanEndpoint)
        }
        return copy(
            endpoint = cleanEndpoint,
            apiKey = apiKey.trim(),
            modelName = cleanModel,
            pitchShift = pitchShift.coerceIn(-24, 24),
            indexRate = indexRate.coerceIn(0f, 1f),
            filterRadius = filterRadius.coerceIn(0, 7),
            f0Method = cleanMethod,
            rmsMixRate = rmsMixRate.coerceIn(0f, 1f),
            protect = protect.coerceIn(0f, 0.5f),
        )
    }

    fun json(seal: (String) -> String = { it }): JSONObject = jsonOf(
        "enabled" to enabled,
        "endpoint" to endpoint,
        "apiKey" to seal(apiKey),
        "modelName" to modelName,
        "pitchShift" to pitchShift,
        "indexRate" to indexRate.toDouble(),
        "filterRadius" to filterRadius,
        "f0Method" to f0Method,
        "rmsMixRate" to rmsMixRate.toDouble(),
        "protect" to protect.toDouble(),
    )

    companion object {
        fun from(row: JSONObject?, unseal: (String) -> String = { it }): RvcSettings {
            if (row == null) return RvcSettings()
            val method = row.string("f0Method").trim().lowercase(Locale.ROOT).takeIf { it in rvcF0Methods } ?: "rmvpe"
            return RvcSettings(
                enabled = row.optBoolean("enabled", false),
                endpoint = row.string("endpoint"),
                apiKey = unseal(row.string("apiKey")),
                modelName = row.string("modelName"),
                pitchShift = row.optInt("pitchShift", 0).coerceIn(-24, 24),
                indexRate = row.optDouble("indexRate", 0.75).toFloat().coerceIn(0f, 1f),
                filterRadius = row.optInt("filterRadius", 3).coerceIn(0, 7),
                f0Method = method,
                rmsMixRate = row.optDouble("rmsMixRate", 0.25).toFloat().coerceIn(0f, 1f),
                protect = row.optDouble("protect", 0.33).toFloat().coerceIn(0f, 0.5f),
            )
        }
    }
}

internal fun rvcApiRoot(endpoint: String): String =
    checkedAddress(endpoint).toString().trimEnd('/')
        .removeSuffix("/api/rvc/process-audio")
        .removeSuffix("/api/rvc/models")
        .removeSuffix("/api/rvc")

internal fun parseRvcModelsJson(raw: String): List<String> {
    val parsed = JSONTokener(raw).nextValue()
    val array = when (parsed) {
        is JSONArray -> parsed
        is JSONObject -> parsed.optJSONArray("models") ?: parsed.optJSONArray("data")
        else -> null
    } ?: error("RVC 服务返回了无效的模型列表")
    val models = mutableListOf<String>()
    for (i in 0 until array.length()) {
        val item = array.opt(i)
        val name = when (item) {
            is String -> item.trim()
            is JSONObject -> item.string("name").ifBlank { item.string("model_name") }.ifBlank { item.string("id") }.trim()
            else -> ""
        }
        if (name.isNotEmpty() && name.length <= 256 && name.none(Char::isISOControl)) models += name
    }
    return models.distinct()
}

internal suspend fun listRvcModels(settings: RvcSettings): List<String> {
    val root = rvcApiRoot(settings.endpoint)
    val key = settings.apiKey.trim()
    val headers = buildMap {
        put("Accept", "application/json")
        if (key.isNotBlank()) put("Authorization", "Bearer $key")
    }
    return request(
        url = "$root/api/rvc/models",
        headers = headers,
        readTimeout = 20_000,
        redactError = { it.redactValue(key) },
    ) { input, _ ->
        parseRvcModelsJson(input.readLimited(2 * 1024 * 1024).decodeToString())
    }
}

internal fun buildRvcProcessPayload(audioBytes: ByteArray, settings: RvcSettings): JSONObject {
    val clean = settings.validated()
    return jsonOf(
        "audio_data" to Base64.getEncoder().encodeToString(audioBytes),
        "model_name" to clean.modelName,
        "pitch_extraction" to clean.f0Method,
        "pitch_offset" to clean.pitchShift,
        "index_rate" to clean.indexRate.toDouble(),
        "filter_radius" to clean.filterRadius,
        "rms_mix_rate" to clean.rmsMixRate.toDouble(),
        "protect" to clean.protect.toDouble(),
    )
}

internal fun decodeRvcAudioResponse(bytes: ByteArray, contentType: String): ByteArray {
    val mediaType = contentType.substringBefore(';').trim().lowercase(Locale.ROOT)
    if (mediaType.startsWith("audio/") || mediaType == "application/octet-stream") {
        check(bytes.isNotEmpty()) { "RVC 服务未返回转换后的音频" }
        return bytes
    }
    val json = JSONObject(bytes.decodeToString())
    val encoded = json.string("audio_data").ifBlank { json.string("audio") }.ifBlank { json.string("data") }
    check(encoded.isNotBlank()) { "RVC 响应中缺少音频数据" }
    val raw = if (encoded.startsWith("data:")) encoded.substringAfter(',') else encoded
    require(raw.length <= (MAX_RVC_AUDIO_BYTES + 2) / 3 * 4) { "RVC 返回的音频超过大小限制" }
    return Base64.getDecoder().decode(raw).also {
        check(it.isNotEmpty() && it.size <= MAX_RVC_AUDIO_BYTES) { "RVC 返回的音频数据无效" }
    }
}

internal suspend fun convertAudioWithRvc(audioBytes: ByteArray, settings: RvcSettings): ByteArray {
    if (!settings.enabled) return audioBytes
    require(audioBytes.isNotEmpty() && audioBytes.size <= MAX_RVC_AUDIO_BYTES) { "待转换的语音音频无效" }
    val clean = settings.validated()
    val root = rvcApiRoot(clean.endpoint)
    val key = clean.apiKey
    val headers = buildMap {
        put("Accept", "audio/wav, audio/mpeg, application/json")
        if (key.isNotBlank()) put("Authorization", "Bearer $key")
    }
    val payload = buildRvcProcessPayload(audioBytes, clean)
    return request(
        url = "$root/api/rvc/process-audio",
        body = payload,
        headers = headers,
        readTimeout = 120_000,
        redactError = { it.redactValue(key) },
    ) { input, mime ->
        val raw = input.readLimited(MAX_RVC_AUDIO_BYTES)
        decodeRvcAudioResponse(raw, mime)
    }
}
