package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject

internal const val TEXT_SAMPLER_FILE_LIMIT = 256 * 1024
internal const val TEXT_SAMPLER_PAYLOAD = "native_text_sampler"

internal enum class SamplerValue { DECIMAL, INTEGER, BOOLEAN, TEXT, ARRAY, OBJECT }

internal enum class TextBackend { KOBOLD, LLAMA_CPP, OLLAMA }

internal enum class TextSamplerField(
    val key: String, val label: String, val kind: SamplerValue = SamplerValue.DECIMAL,
    val kobold: String? = null, val llama: String? = null, val ollama: String? = null,
    val aliases: List<String> = emptyList(),
) {
    TEMPERATURE("temp", "温度", aliases = listOf("temperature")),
    TOP_P("top_p", "Top P"),
    TOP_K("top_k", "Top K", SamplerValue.INTEGER, "top_k", "top_k", "top_k"),
    MIN_P("min_p", "Min P", kobold = "min_p", llama = "min_p", ollama = "min_p"),
    REPETITION("rep_pen", "重复惩罚", kobold = "rep_pen", llama = "repeat_penalty", ollama = "repeat_penalty",
        aliases = listOf("repeat_penalty", "repetition_penalty")),
    REPETITION_RANGE("rep_pen_range", "重复惩罚范围", SamplerValue.INTEGER, "rep_pen_range", "repeat_last_n",
        "repeat_last_n", listOf("repeat_last_n")),
    FREQUENCY("freq_pen", "频率惩罚", llama = "frequency_penalty", ollama = "frequency_penalty",
        aliases = listOf("frequency_penalty")),
    PRESENCE("presence_pen", "存在惩罚", llama = "presence_penalty", ollama = "presence_penalty",
        aliases = listOf("presence_penalty")),
    MAX_OUTPUT("max_new_tokens", "最大回复 Token", SamplerValue.INTEGER,
        aliases = listOf("max_tokens", "amount_gen", "max_length", "num_predict")),
    CONTEXT("max_context", "上下文 Token 上限", SamplerValue.INTEGER,
        aliases = listOf("truncation_length", "max_context_length", "num_ctx", "n_ctx")),
    SEED("seed", "随机种子", SamplerValue.INTEGER, "sampler_seed", "seed", "seed", listOf("sampler_seed")),
    TOP_A("top_a", "Top A", kobold = "top_a"),
    TFS("tfs", "尾部自由采样", kobold = "tfs"),
    TYPICAL("typical_p", "Typical P", kobold = "typical", llama = "typical_p", aliases = listOf("typical")),
    DYNAMIC("dynatemp", "动态温度", SamplerValue.BOOLEAN),
    MIN_TEMP("min_temp", "动态温度下限"),
    MAX_TEMP("max_temp", "动态温度上限"),
    DYNAMIC_EXPONENT("dynatemp_exponent", "动态温度指数"),
    SMOOTHING("smoothing_factor", "平滑系数", kobold = "smoothing_factor"),
    MIROSTAT("mirostat_mode", "Mirostat 模式", SamplerValue.INTEGER, "mirostat", aliases = listOf("mirostat")),
    MIROSTAT_TAU("mirostat_tau", "Mirostat Tau", kobold = "mirostat_tau"),
    MIROSTAT_ETA("mirostat_eta", "Mirostat Eta", kobold = "mirostat_eta"),
    DRY_MULTIPLIER("dry_multiplier", "DRY 倍率", kobold = "dry_multiplier", llama = "dry_multiplier"),
    DRY_BASE("dry_base", "DRY 基数", kobold = "dry_base", llama = "dry_base"),
    DRY_LENGTH("dry_allowed_length", "DRY 允许重复长度", SamplerValue.INTEGER, "dry_allowed_length", "dry_allowed_length"),
    DRY_RANGE("dry_penalty_last_n", "DRY 惩罚范围", SamplerValue.INTEGER, "dry_penalty_last_n", "dry_penalty_last_n"),
    DRY_BREAKERS("dry_sequence_breakers", "DRY 分段字符串（JSON 数组）", SamplerValue.ARRAY,
        "dry_sequence_breakers", "dry_sequence_breakers"),
    XTC_THRESHOLD("xtc_threshold", "XTC 阈值", kobold = "xtc_threshold", llama = "xtc_threshold"),
    XTC_PROBABILITY("xtc_probability", "XTC 概率", kobold = "xtc_probability", llama = "xtc_probability"),
    NSIGMA("nsigma", "Top N Sigma", kobold = "nsigma", llama = "top_n_sigma", aliases = listOf("top_n_sigma")),
    MIN_KEEP("min_keep", "最少保留 Token", SamplerValue.INTEGER, llama = "min_keep"),
    KOBOLD_ORDER("sampler_order", "Kobold 采样顺序（JSON 数组）", SamplerValue.ARRAY, "sampler_order"),
    LLAMA_ORDER("samplers", "llama.cpp 采样顺序（JSON 数组）", SamplerValue.ARRAY, llama = "samplers"),
    BAD_WORDS("use_default_badwordsids", "Kobold 默认禁词", SamplerValue.BOOLEAN, "use_default_badwordsids"),
    IGNORE_EOS("ignore_eos_token", "忽略结束 Token", SamplerValue.BOOLEAN, "bypass_eos", "ignore_eos",
        aliases = listOf("ignore_eos", "bypass_eos")),
    GRAMMAR("grammar_string", "GBNF 语法", SamplerValue.TEXT, "grammar", "grammar", aliases = listOf("grammar")),
    JSON_SCHEMA("json_schema", "JSON Schema", SamplerValue.OBJECT, llama = "json_schema");

    fun wire(backend: TextBackend): String? = when (backend) {
        TextBackend.KOBOLD -> kobold
        TextBackend.LLAMA_CPP -> llama
        TextBackend.OLLAMA -> ollama
    }

    fun value(text: String): Any? {
        if (text.isBlank()) return null
        val trimmed = text.trim()
        val result = when (kind) {
            SamplerValue.DECIMAL -> trimmed.toDoubleOrNull()?.takeIf { it.isFinite() && it in -1e6..1e6 }
            SamplerValue.INTEGER -> trimmed.toLongOrNull()?.takeIf { it in -1L..4_294_967_295L }
            SamplerValue.BOOLEAN -> trimmed.lowercase().toBooleanStrictOrNull()
            SamplerValue.TEXT -> text.takeIf { it.length <= TEXT_SAMPLER_FILE_LIMIT }
            SamplerValue.ARRAY -> runCatching { JSONArray(trimmed) }.getOrNull()?.takeIf(::validArray)
            SamplerValue.OBJECT -> runCatching { JSONObject(trimmed) }.getOrNull()
        }
        require(result != null) { "${label}的值无效" }
        when (this) {
            MAX_OUTPUT, CONTEXT -> require(result is Long && result in 1..MAX_CONTEXT_TOKENS.toLong()) { "${label}超出范围" }
            TOP_P, MIN_P, TYPICAL, TOP_A, TFS, XTC_THRESHOLD, XTC_PROBABILITY ->
                require(result is Double && result in 0.0..1.0) { "${label}必须在 0 到 1 之间" }
            MIROSTAT -> require(result is Long && result in 0L..2L) { "Mirostat 模式必须为 0、1 或 2" }
            else -> Unit
        }
        return result
    }

    private fun validArray(array: JSONArray) = when (this) {
        KOBOLD_ORDER -> (0 until array.length()).map(array::opt).let { order ->
            order.size in 6..7 && order.all { it is Int && it in 0..6 } && order.distinct().size == order.size
        }
        LLAMA_ORDER, DRY_BREAKERS -> array.length() <= 64 &&
            (0 until array.length()).all { index -> (array.opt(index) as? String)?.let { it.length <= 1024 } == true }
        else -> false
    }
}

internal data class TextSamplerPreset(val name: String, val fields: Map<TextSamplerField, String> = emptyMap()) {
    operator fun get(field: TextSamplerField) = fields[field].orEmpty()

    fun json(): JSONObject {
        val result = jsonOf("name" to name.trim().take(100))
        require(result.string("name").isNotBlank()) { "请填写采样预设名称" }
        fields.forEach { (field, text) -> field.value(text)?.let { result.put(field.key, it) } }
        require(result.length() > 1) { "请至少填写一项采样参数" }
        val minimum = result.optDouble(TextSamplerField.MIN_TEMP.key, 0.0)
        val maximum = result.optDouble(TextSamplerField.MAX_TEMP.key, 2.0)
        if (result.optBoolean(TextSamplerField.DYNAMIC.key)) {
            require(minimum >= 0 && maximum >= minimum) { "动态温度上限必须大于或等于非负的下限" }
        }
        require(result.toString().encodeToByteArray().size <= TEXT_SAMPLER_FILE_LIMIT) { "采样预设过大" }
        return result
    }

    companion object {
        fun read(json: JSONObject, fallback: String = "导入的采样预设"): TextSamplerPreset {
            val fields = TextSamplerField.entries.mapNotNull { field ->
                val key = (listOf(field.key) + field.aliases).firstOrNull { !json.isNull(it) } ?: return@mapNotNull null
                // Original exports can store optional structured fields as JSON-encoded strings.
                val text = json.string(key)
                val value = field.value(text) ?: return@mapNotNull null
                field to value.toString()
            }.toMap()
            val name = json.string("name").trim().ifBlank { fallback }.take(100)
            return TextSamplerPreset(name, fields).also { it.json() }
        }
    }
}

internal data class TextSamplingSettings(
    val default: TextSamplerPreset? = null,
    val byModel: Map<String, TextSamplerPreset> = emptyMap(),
) {
    fun forModel(model: String) = byModel[model] ?: default

    fun json() = jsonOf("default" to default?.json(), "byModel" to JSONObject().apply {
        byModel.forEach { (id, preset) -> put(id, preset.json()) }
    })

    companion object {
        fun read(json: JSONObject?): TextSamplingSettings {
            val models = json?.optJSONObject("byModel")
            fun parse(entry: JSONObject?) = entry?.let { runCatching { TextSamplerPreset.read(it) }.getOrNull() }
            return TextSamplingSettings(parse(json?.optJSONObject("default")), models?.keys()?.asSequence()?.mapNotNull { id ->
                parse(models.optJSONObject(id))?.let { id to it }
            }?.toMap().orEmpty())
        }
    }
}

internal fun ModelConnection.textSampler() =
    provider.textSampling.takeIf { provider.supportsTextSampling }?.forModel(model)

private val llamaSamplers = setOf("penalties", "dry", "top_n_sigma", "top_k", "typ_p", "top_p", "min_p", "xtc",
    "temperature", "infill", "adaptive_p")

internal fun applyTextSampler(source: JSONObject?, target: JSONObject, backend: TextBackend) {
    if (source == null) return
    for (field in TextSamplerField.entries) {
        val wire = field.wire(backend) ?: continue
        val value = source.opt(field.key) ?: continue
        when {
            backend == TextBackend.KOBOLD && field == TextSamplerField.SEED && source.optLong(field.key) !in 1L..999_999L ->
                Unit // Native Kobold uses its server RNG when no valid fixed seed was requested.
            backend == TextBackend.KOBOLD && field == TextSamplerField.REPETITION_RANGE && source.optInt(field.key) < 0 ->
                Unit
            field == TextSamplerField.LLAMA_ORDER -> {
                val order = (value as JSONArray).strings()
                val supported = order.filterNot { it == "typical_p" && "typ_p" in order }
                    .map { if (it == "typical_p") "typ_p" else it }.filter(llamaSamplers::contains)
                if (supported.isNotEmpty()) target.put(wire, JSONArray(supported))
            }
            field == TextSamplerField.NSIGMA && backend == TextBackend.LLAMA_CPP && source.optDouble(field.key) == 0.0 ->
                target.put(wire, -1)
            field == TextSamplerField.JSON_SCHEMA && source.has(TextSamplerField.GRAMMAR.key) -> Unit
            else -> target.put(wire, value)
        }
    }
    if (backend != TextBackend.OLLAMA && source.optBoolean(TextSamplerField.DYNAMIC.key)) {
        val min = source.optDouble(TextSamplerField.MIN_TEMP.key, 0.0)
        val max = source.optDouble(TextSamplerField.MAX_TEMP.key, 2.0)
        target.put("temperature", (max + min) / 2)
        target.put("dynatemp_range", (max - min) / 2)
        source.opt(TextSamplerField.DYNAMIC_EXPONENT.key)?.let { target.put("dynatemp_exponent", it) }
    }
}
