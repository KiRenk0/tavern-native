package app.tavern.nativeclient

import com.knuddels.jtokkit.api.EncodingType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal const val LOGIT_BIAS = "logit_bias"
internal const val MAX_TOKEN_BIAS_ROWS = 256
private const val MAX_TOKEN_IDS = 4_096

internal enum class BiasTokenizer(val label: String, val encoding: EncodingType? = null) {
    AUTO("按模型自动选择"),
    O200K("o200k_base", EncodingType.O200K_BASE),
    CL100K("cl100k_base", EncodingType.CL100K_BASE),
    P50K("p50k_base", EncodingType.P50K_BASE),
    P50K_EDIT("p50k_edit", EncodingType.P50K_EDIT),
    R50K("r50k_base（GPT-2 词表）", EncodingType.R50K_BASE),
    LLAMA_CPP("llama.cpp 服务端"),
    KOBOLD_CPP("KoboldCpp 服务端"),
    IDS("仅使用 Token ID");

    companion object {
        fun from(value: String) = entries.firstOrNull { it.name == value } ?: AUTO
    }
}

internal data class TokenBiasRow(val id: String, val text: String = "", val value: Double = 0.0) {
    fun json() = jsonOf("id" to id, "text" to text, "value" to value)

    companion object {
        fun read(row: JSONObject): TokenBiasRow {
            require(row.opt("text") is String) { "偏置规则缺少文本或 Token ID" }
            val value = (row.opt("value") as? Number)?.toDouble()
            require(value != null && value in -100.0..100.0) { "词元偏置必须在 −100 到 100 之间" }
            val id = row.string("id").takeIf { it.isNotBlank() && it.length <= 128 } ?: newId()
            return TokenBiasRow(id, row.getString("text"), value)
        }
    }
}

internal data class TokenBiasPreset(val id: String, val name: String, val rows: List<TokenBiasRow> = emptyList()) {
    fun originalJson() = JSONArray(rows.map(TokenBiasRow::json))

    fun validate(): TokenBiasPreset {
        val title = name.trim()
        require(title.isNotBlank() && title.length <= 100) { "预设名称应为 1 至 100 个字符" }
        require(rows.size <= MAX_TOKEN_BIAS_ROWS) { "一个预设最多可包含 $MAX_TOKEN_BIAS_ROWS 条规则" }
        require(rows.sumOf { it.text.length.toLong() } <= 65_536) { "预设的文本总量超过 65536 个字符" }
        rows.forEach { row ->
            require(row.value in -100.0..100.0) { "词元偏置必须在 −100 到 100 之间" }
            require(row.text.length <= 8_192) { "单条偏置规则最多可包含 8192 个字符" }
            rawTokenIds(row.text)
        }
        return copy(name = title)
    }

    companion object {
        fun fromOriginal(source: String, name: String): TokenBiasPreset {
            val input = source.trimStart('\uFEFF').trim()
            val items = if (input.startsWith('[')) JSONArray(input)
                else requireNotNull(JSONObject(input).optJSONArray(LOGIT_BIAS)) { "文件没有词元偏置规则" }
            return fromRows(newId(), name, items)
        }

        fun fromRows(id: String, name: String, items: JSONArray): TokenBiasPreset {
            require(items.length() <= MAX_TOKEN_BIAS_ROWS) { "一个预设最多可包含 $MAX_TOKEN_BIAS_ROWS 条规则" }
            val ids = mutableSetOf<String>()
            return TokenBiasPreset(id, name, List(items.length()) { i ->
                val row = TokenBiasRow.read(items.getJSONObject(i))
                if (ids.add(row.id)) row else row.copy(id = newId().also(ids::add))
            }).validate()
        }
    }
}

private val antiBondPreset = TokenBiasPreset("st-anti-bond", "Anti-bond", listOf(
    TokenBiasRow("bond", " bond", -50.0),
    TokenBiasRow("future", " future", -50.0),
    TokenBiasRow("bonding", " bonding", -50.0),
    TokenBiasRow("connection", " connection", -25.0),
))

internal data class TokenBiasSelection(val presetId: String = "", val tokenizer: BiasTokenizer = BiasTokenizer.AUTO) {
    fun json() = jsonOf("preset" to presetId, "tokenizer" to tokenizer.name)

    companion object {
        fun read(json: JSONObject?) = TokenBiasSelection(
            json?.string("preset").orEmpty(), BiasTokenizer.from(json?.string("tokenizer").orEmpty()),
        )
    }
}

internal data class TokenBiasSettings(
    val presets: List<TokenBiasPreset> = listOf(antiBondPreset),
    val default: TokenBiasSelection = TokenBiasSelection(),
    val byModel: Map<String, TokenBiasSelection> = emptyMap(),
) {
    fun forModel(model: String) = byModel[model] ?: default

    fun select(model: String?, selection: TokenBiasSelection?): TokenBiasSettings =
        when {
            model == null -> copy(default = selection ?: TokenBiasSelection())
            selection != null -> copy(byModel = byModel + (model to selection))
            else -> copy(byModel = byModel - model)
        }

    fun remove(id: String) = copy(
        presets = presets.filterNot { it.id == id },
        default = if (default.presetId == id) TokenBiasSelection() else default,
        byModel = byModel.mapValues { (_, selection) -> if (selection.presetId == id) TokenBiasSelection() else selection },
    )

    fun json() = jsonOf(
        "presets" to JSONArray(presets.map { jsonOf("id" to it.id, "name" to it.name, "rows" to it.originalJson()) }),
        "default" to default.json(),
        "models" to JSONObject(byModel.mapValues { (_, selection) -> selection.json() }),
    )

    companion object {
        fun read(json: JSONObject?): TokenBiasSettings {
            if (json == null) return TokenBiasSettings()
            val models = json.optJSONObject("models")
            return TokenBiasSettings(
                json.optJSONArray("presets")?.objects().orEmpty().mapNotNull {
                    runCatching { TokenBiasPreset.fromRows(it.getString("id"), it.getString("name"), it.getJSONArray("rows")) }.getOrNull()
                }.distinctBy(TokenBiasPreset::id),
                TokenBiasSelection.read(json.optJSONObject("default")),
                models?.keys()?.asSequence()?.mapNotNull { model ->
                    models.optJSONObject(model)?.let { model to TokenBiasSelection.read(it) }
                }?.toMap().orEmpty(),
            )
        }
    }
}

private fun rawTokenIds(text: String): IntArray? {
    val value = text.trim()
    if (!value.startsWith('[') || !value.endsWith(']')) return null
    val ids = runCatching { JSONArray(value) }.getOrElse { throw IllegalArgumentException("Token ID 应为 JSON 整数数组，例如 [123, 456]") }
    return tokenIds(ids)
}

internal fun tokenIds(values: JSONArray): IntArray {
    require(values.length() <= MAX_TOKEN_IDS) { "Token ID 数量超过 $MAX_TOKEN_IDS" }
    return IntArray(values.length()) { i ->
        val id = values.opt(i)?.toString()?.toIntOrNull()
        require(values.opt(i) is Number && id != null && id >= 0) { "Token ID 必须是非负整数" }
        id
    }
}

internal fun originalBiasText(text: String, textCompletion: Boolean): String {
    if (!textCompletion) return text
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return ""
    return if (trimmed.startsWith('{') && trimmed.endsWith('}')) trimmed.substring(1, trimmed.lastIndex) else " $trimmed"
}

internal val Provider.supportsTokenBias get() = template == null &&
    (protocol in setOf(ApiProtocol.OPENAI_COMPATIBLE, ApiProtocol.OPENROUTER, ApiProtocol.AZURE_OPENAI, ApiProtocol.KOBOLD_TEXT) ||
        AdditionalTextBackends.specFor(protocol)?.supportsLogitBias == true)

internal fun defaultBiasTokenizer(connection: ModelConnection): BiasTokenizer {
    if (connection.provider.protocol == ApiProtocol.KOBOLD_TEXT) {
        return ProviderProtocols.defaultTextBiasTokenizer(connection.provider)
    }
    val type = OpenAiTokenizers.forModel(connection.model) ?: return BiasTokenizer.IDS
    return BiasTokenizer.entries.first { it.encoding == type }
}

internal suspend fun ModelConnection.compileTokenBias(
    selection: TokenBiasSelection = provider.tokenBias.forModel(model),
): JSONObject? {
    if (!provider.supportsTokenBias || selection.presetId.isEmpty()) return null
    val preset = provider.tokenBias.presets.firstOrNull { it.id == selection.presetId } ?: return null
    val tokenizer = if (selection.tokenizer == BiasTokenizer.AUTO) defaultBiasTokenizer(this) else selection.tokenizer
    val idsByText = mutableMapOf<String, IntArray>()
    val result = JSONObject()
    for (row in preset.rows) {
        var ids = rawTokenIds(row.text)
        if (ids == null) {
            val input = originalBiasText(row.text, provider.usesTextCompletion)
            if (input.isEmpty()) continue
            ids = idsByText[input] ?: encodeBiasText(input, tokenizer).also { idsByText[input] = it }
        }
        ids.forEach { result.put(it.toString(), row.value) }
    }
    val limit = if (provider.protocol == ApiProtocol.KOBOLD_TEXT &&
        ProviderProtocols.defaultTextBiasTokenizer(provider) == BiasTokenizer.KOBOLD_CPP) 512 else MAX_TOKEN_IDS
    require(result.length() <= limit) { "此连接的词元偏置最多支持 $limit 个 Token ID" }
    return result.takeIf { it.length() > 0 }
}

private suspend fun ModelConnection.encodeBiasText(text: String, tokenizer: BiasTokenizer): IntArray {
    tokenizer.encoding?.let { encoding ->
        return withContext(Dispatchers.Default) { OpenAiTokenizers.encoding(encoding).encodeOrdinary(text).toArray() }
    }
    require(tokenizer == BiasTokenizer.LLAMA_CPP || tokenizer == BiasTokenizer.KOBOLD_CPP) {
        "模型 $model 需要指定分词器，或用 [123, 456] 格式输入 Token ID"
    }
    return ProviderProtocols.tokenizeBias(this, tokenizer, text)
}
