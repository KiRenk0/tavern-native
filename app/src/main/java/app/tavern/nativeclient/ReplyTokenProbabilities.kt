package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.exp

internal const val TOKEN_PROBABILITY_EXTRA = "tavern_native_token_probabilities"
internal const val TOKEN_PROBABILITY_COUNT = 5
private const val MAX_MEASURED_TOKENS = 1024
private const val MAX_TOKEN_SIZE = 256

internal data class ReplyTokenCandidate(
    val text: String,
    val logProbability: Double?,
    val bytes: List<Int> = emptyList(),
) {
    val probability get() = logProbability?.let(::exp)
    fun encoded(): ByteArray = if (bytes.isEmpty()) text.encodeToByteArray() else bytes.map(Int::toByte).toByteArray()
    fun json() = jsonOf("token" to text, "logprob" to logProbability, "bytes" to bytes.takeIf { it.isNotEmpty() }?.let(::JSONArray))

    companion object {
        fun from(row: JSONObject): ReplyTokenCandidate? {
            val text = row.opt("token") as? String ?: return null
            val raw = row.optJSONArray("bytes")?.takeIf { it.length() in 1..MAX_TOKEN_SIZE }
            val bytes = raw?.let { array -> List(array.length()) { array.optInt(it, -1) }.takeIf { list ->
                list.all { it in 0..255 }
            } }.orEmpty()
            return create(text, row.optDouble("logprob"), bytes)
        }

        fun create(text: String, value: Double, bytes: List<Int> = emptyList()): ReplyTokenCandidate? =
            text.takeIf { it.length <= MAX_TOKEN_SIZE && (it.isNotEmpty() || bytes.isNotEmpty()) }
                ?.let { ReplyTokenCandidate(it, value.takeIf { p -> p.isFinite() && p <= 0 && p > -9999 }, bytes) }
    }
}

internal data class ReplyToken(val chosen: ReplyTokenCandidate, val alternatives: List<ReplyTokenCandidate>) {
    fun json() = chosen.json().put("top_logprobs", JSONArray(alternatives.map(ReplyTokenCandidate::json)))

    companion object {
        fun from(row: JSONObject): ReplyToken? = ReplyTokenCandidate.from(row)?.let { chosen ->
            ReplyToken(chosen, row.optJSONArray("top_logprobs")?.objects().orEmpty().take(TOKEN_PROBABILITY_COUNT)
                .mapNotNull(ReplyTokenCandidate::from).distinctBy { it.text to it.bytes })
        }
    }
}

internal data class ReplyTokenTrace(
    val prefix: String,
    val tokens: List<ReplyToken>,
    val aligned: Boolean,
) {
    fun replacement(index: Int, candidate: ReplyTokenCandidate): String? {
        if (!aligned || candidate !in tokens.getOrNull(index)?.alternatives.orEmpty()) return null
        val generated = decodeTokens(tokens.take(index).map(ReplyToken::chosen) + candidate) ?: return null
        return (prefix + generated).takeIf(String::isNotBlank)
    }

    fun stored(body: String, prefix: String): ReplyTokenTrace {
        val generated = if (aligned) decodeTokens(tokens.map(ReplyToken::chosen)) else null
        val matches = generated != null && if (prefix.isEmpty()) body.startsWith(generated.trimStart())
            else body.startsWith(prefix + generated)
        return copy(prefix = prefix, aligned = matches)
    }

    fun json() = jsonOf("prefix" to prefix, "tokens" to JSONArray(tokens.map(ReplyToken::json)), "aligned" to aligned)

    companion object {
        fun from(row: JSONObject?): ReplyTokenTrace? {
            row ?: return null
            val prefix = (row.opt("prefix") as? String).orEmpty()
            if (prefix.length > MAX_MODEL_TEXT) return null
            val values = row.optJSONArray("tokens") ?: return null
            if (values.length() !in 1..MAX_MEASURED_TOKENS) return null
            val tokens = values.objects().mapNotNull(ReplyToken::from)
            return ReplyTokenTrace(prefix, tokens, row.optBoolean("aligned") && tokens.size == values.length())
                .takeIf { tokens.isNotEmpty() }
        }
    }
}

internal class ReplyTokenCollector {
    private val rows = mutableListOf<ReplyToken>()

    fun accept(logprobs: JSONObject?) {
        if (logprobs == null || rows.size >= MAX_MEASURED_TOKENS) return
        val remaining = MAX_MEASURED_TOKENS - rows.size
        val content = logprobs.optJSONArray("content")
        if (content != null) {
            for (i in 0 until minOf(remaining, content.length())) {
                content.optJSONObject(i)?.let(ReplyToken::from)?.let(rows::add)
            }
            return
        }
        val tokens = logprobs.optJSONArray("tokens") ?: return
        val selected = logprobs.optJSONArray("token_logprobs")
        val top = logprobs.optJSONArray("top_logprobs")
        for (i in 0 until minOf(remaining, tokens.length())) {
            val text = tokens.opt(i) as? String ?: continue
            val chosen = ReplyTokenCandidate.create(text, selected?.optDouble(i) ?: Double.NaN) ?: continue
            val choices = top?.optJSONObject(i)
            val alternatives = choices?.keys()?.asSequence()?.take(TOKEN_PROBABILITY_COUNT)
                ?.mapNotNull { ReplyTokenCandidate.create(it, choices.optDouble(it)) }?.toList().orEmpty()
            rows += ReplyToken(chosen, alternatives)
        }
    }

    fun finish(visible: String): ReplyTokenTrace? {
        if (rows.isEmpty() || visible.isBlank()) return null
        val tokens = rows.toList()
        val raw = decodeTokens(tokens.map(ReplyToken::chosen))
        val aligned = raw != null && (raw == visible || tokens.size == MAX_MEASURED_TOKENS && visible.startsWith(raw))
        return ReplyTokenTrace("", tokens, aligned)
    }
}

private fun decodeTokens(tokens: List<ReplyTokenCandidate>): String? {
    val bytes = ByteArrayOutputStream()
    tokens.forEach { bytes.write(it.encoded()) }
    return runCatching { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toByteArray())).toString() }.getOrNull()
}

internal val Provider.canRequestTokenProbabilities: Boolean
    get() = when (protocol) {
        ApiProtocol.OPENAI_COMPATIBLE -> template != ProviderTemplate.GOOGLE
        ApiProtocol.OPENROUTER, ApiProtocol.AZURE_OPENAI, ApiProtocol.KOBOLD_TEXT -> true
        else -> AdditionalTextBackends.specFor(protocol)?.supportsLogprobs == true
    }

internal val Provider.canRerollToken: Boolean get() = canRequestTokenProbabilities &&
    (template == ProviderTemplate.DEEPSEEK || protocol == ApiProtocol.KOBOLD_TEXT)
