package app.tavern.nativeclient

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val NATIVE_METRICS = "tavern_native_reply_metrics"

private fun JSONObject?.count(key: String): Long? =
    this?.opt(key)?.toString()?.toLongOrNull()?.takeIf { it in 0..1_000_000_000_000L }

private fun addKnown(left: Long?, right: Long?): Long? =
    if (left == null && right == null) null else (left ?: 0) + (right ?: 0)

internal data class ResponseTokens(val input: Long? = null, val output: Long? = null, val reasoning: Long? = null) {
    val reported get() = input != null || output != null || reasoning != null
    fun update(snapshot: ResponseTokens) = ResponseTokens(snapshot.input ?: input, snapshot.output ?: output,
        snapshot.reasoning ?: reasoning)
    operator fun plus(other: ResponseTokens) = ResponseTokens(addKnown(input, other.input), addKnown(output, other.output),
        addKnown(reasoning, other.reasoning))

    fun json() = jsonOf("input" to input, "output" to output, "reasoning" to reasoning)

    companion object {
        fun from(value: JSONObject?) = ResponseTokens(value.count("input"), value.count("output"), value.count("reasoning"))

        fun openAi(response: JSONObject): ResponseTokens {
            val usage = response.optJSONObject("usage")
            return ResponseTokens(usage.count("prompt_tokens"), usage.count("completion_tokens"),
                usage?.optJSONObject("completion_tokens_details").count("reasoning_tokens"))
        }

        fun anthropic(usage: JSONObject?): ResponseTokens {
            val input = usage.count("input_tokens")
            val cached = addKnown(usage.count("cache_creation_input_tokens"), usage.count("cache_read_input_tokens"))
            return ResponseTokens(addKnown(input, cached), usage.count("output_tokens"))
        }

        fun gemini(response: JSONObject): ResponseTokens {
            val usage = response.optJSONObject("usageMetadata")
            val thinking = usage.count("thoughtsTokenCount")
            return ResponseTokens(addKnown(usage.count("promptTokenCount"), usage.count("toolUsePromptTokenCount")),
                addKnown(usage.count("candidatesTokenCount"), thinking), thinking)
        }

        fun ollama(response: JSONObject) = ResponseTokens(response.count("prompt_eval_count"), response.count("eval_count"))
    }
}

internal data class ResponseUsage(val tokens: ResponseTokens = ResponseTokens(), val requests: Int = 0, val reported: Int = 0) {
    fun record(snapshot: ResponseTokens) = this + ResponseUsage(snapshot, 1, if (snapshot.reported) 1 else 0)
    operator fun plus(other: ResponseUsage) = ResponseUsage(tokens + other.tokens,
        (requests + other.requests).coerceAtMost(100_000), (reported + other.reported).coerceAtMost(100_000))

    fun json() = jsonOf("tokens" to tokens.json(), "requests" to requests, "reported" to reported)

    companion object {
        fun from(value: JSONObject?): ResponseUsage {
            val requests = value?.optInt("requests")?.coerceIn(0, 100_000) ?: 0
            return ResponseUsage(ResponseTokens.from(value?.optJSONObject("tokens")), requests,
                value?.optInt("reported")?.coerceIn(0, requests) ?: 0)
        }
    }
}

internal data class ReplyMetrics(
    val started: Long? = null,
    val finished: Long? = null,
    val elapsed: Long? = null,
    val firstText: Long? = null,
    val provider: String = "",
    val model: String = "",
    val api: String = "",
    val bodyTokens: Long? = null,
    val counter: String = "",
    val usage: ResponseUsage = ResponseUsage(),
    val reasoningElapsed: Long? = null,
) {
    fun continued(next: ReplyMetrics) = next.copy(started = started ?: next.started,
        elapsed = addKnown(elapsed, next.elapsed), firstText = firstText ?: next.firstText, usage = usage + next.usage,
        reasoningElapsed = addKnown(reasoningElapsed, next.reasoningElapsed))

    fun write(row: JSONObject) {
        val extra = row.optJSONObject("extra") ?: JSONObject().also { row.put("extra", it) }
        val exportStart = if (finished != null && elapsed != null) finished - elapsed else started
        exportStart?.let { row.put("gen_started", Instant.ofEpochMilli(it).toString()) } ?: row.remove("gen_started")
        finished?.let { row.put("gen_finished", Instant.ofEpochMilli(it).toString()) } ?: row.remove("gen_finished")
        extra.put(NATIVE_METRICS, jsonOf("started" to started, "elapsed" to elapsed, "firstText" to firstText, "provider" to provider,
            "model" to model, "api" to api, "counter" to counter, "bodyTokens" to bodyTokens, "usage" to usage.json()))
        extra.replaceMetric("model", model.takeIf(String::isNotBlank))
        extra.replaceMetric("api", api.takeIf(String::isNotBlank))
        extra.replaceMetric("token_count", bodyTokens)
        extra.replaceMetric("time_to_first_token", firstText)
        extra.replaceMetric("reasoning_duration", reasoningElapsed)
    }

    companion object {
        fun from(row: JSONObject?): ReplyMetrics? {
            row ?: return null
            val extra = row.optJSONObject("extra")
            val saved = extra?.optJSONObject(NATIVE_METRICS)
            val originalStart = messageEpoch(row.opt("gen_started"))
            val start = messageEpoch(saved?.opt("started")) ?: originalStart
            val finish = messageEpoch(row.opt("gen_finished"))
            val duration = if (originalStart != null && finish != null && finish >= originalStart) finish - originalStart else null
            val parsed = ReplyMetrics(start, finish, saved.count("elapsed") ?: duration,
                saved.count("firstText") ?: extra.count("time_to_first_token"), saved?.string("provider").orEmpty().take(120),
                (saved?.string("model") ?: extra?.string("model")).orEmpty().take(180),
                (saved?.string("api") ?: extra?.string("api")).orEmpty().take(80),
                saved.count("bodyTokens") ?: extra.count("token_count"), saved?.string("counter").orEmpty().take(100),
                ResponseUsage.from(saved?.optJSONObject("usage")), extra.count("reasoning_duration"))
            return parsed.takeUnless { it == ReplyMetrics() }
        }

        fun clear(row: JSONObject) {
            row.remove("gen_started")
            row.remove("gen_finished")
            row.optJSONObject("extra")?.let { extra ->
                listOf(NATIVE_METRICS, "model", "api", "token_count", "time_to_first_token", "reasoning_duration").forEach(extra::remove)
            }
        }
    }
}

private fun JSONObject.replaceMetric(key: String, value: Any?) {
    if (value == null) remove(key) else put(key, value)
}

private val originalMessageDates = listOf("MMMM d, uuuu h:mm a", "MMMM d, uuuu HH:mm",
    "MMMM d, uuuu h:mm:ss a", "MMMM d, uuuu HH:mm:ss", "uuuu-MM-dd HH:mm:ss")
    .map { DateTimeFormatter.ofPattern(it, Locale.ENGLISH) }

internal fun messageEpoch(value: Any?): Long? {
    val text = value?.toString()?.trim()?.takeIf { it.length in 1..80 } ?: return null
    text.toLongOrNull()?.let { return it.takeIf { time -> time in 1..253_402_300_799_999L } }
    runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()?.let { return it }
    runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
    return originalMessageDates.firstNotNullOfOrNull {
        runCatching { LocalDateTime.parse(text, it).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
    }
}
