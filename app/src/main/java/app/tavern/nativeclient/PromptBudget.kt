package app.tavern.nativeclient

import org.json.JSONArray

internal enum class ExampleBudget(val label: String) {
    AVAILABLE_SPACE("有剩余额度时保留"),
    PINNED("优先保留"),
    OMITTED("不发送"),
}

internal data class PromptBudgetUsage(
    val context: Int,
    val output: Int,
    val used: Long,
    val omittedMessages: Int,
    val counter: String,
    val firstHistoryIndex: Int?,
    val firstHistoryDate: String?,
) {
    val limit get() = (context - output).coerceAtLeast(0)
}

internal fun ModelConnection.outputReserve(configured: Int?, context: Int): Int {
    val explicit = configured?.takeIf { it > 0 }
    return when (provider.protocol) {
        ApiProtocol.ANTHROPIC -> anthropicMaxOutput(explicit, provider.thinkingBudget)
        ApiProtocol.KOBOLD_TEXT -> explicit ?: 512
        ApiProtocol.NOVELAI_TEXT -> NovelAi.output(provider, model, explicit)
        ApiProtocol.HORDE_TEXT -> hordeOutput(provider, model, explicit)
        else -> explicit ?: minOf(512, context / 4)
    }
}

internal fun anthropicMaxOutput(explicit: Int?, thinkingBudget: Int?): Int =
    maxOf(explicit?.takeIf { it > 0 } ?: 4096,
        thinkingBudget?.takeIf { it > 0 }?.coerceAtLeast(1024)?.plus(1024) ?: 0)

internal class PromptTokenMeter(private val counter: TextTokenCounter) {
    // Reuse counts of individual rows when comparing history windows. The final text prompt
    // is counted as one string so template separators and token joins are included.
    private val counts = mutableMapOf<String, Int>()
    private fun count(text: String): Int = if (text.isEmpty()) 0
        else if (text.length <= 16_384 && counts.size < 2_048) counts.getOrPut(text) { counter.count(text) }
        else counts[text] ?: counter.count(text)

    fun measure(messages: JSONArray, textPrompt: String?, prefill: String, tools: JSONArray): Long {
        if (textPrompt != null) return counter.count(textPrompt).toLong()
        val dialog = messages.objects().sumOf { row ->
            val attachments = row.optJSONArray("content")?.objects().orEmpty().count { it.string("type") == "image_url" }
            4L + count(row.promptText()) + count(row.string("name")) + count(row.string("reasoning_content")) +
                count(row.optJSONArray("tool_calls")?.toString().orEmpty()) + count(row.string("tool_call_id")) +
                count(row.optJSONArray("server_tool_calls")?.toString().orEmpty()) +
                count(row.optJSONArray(ANTHROPIC_WEB_REPLAY)?.toString().orEmpty()) +
                256L * attachments
        }
        val schema = if (tools.length() == 0) 0 else count(tools.toString())
        return 3L + dialog + count(prefill) + schema
    }
}

internal data class FittedPrompt<T>(val value: T, val historySize: Int, val tokens: Long)

internal fun <T> fitPromptWindow(
    maximum: Int, minimum: Int, limit: Int, examples: ExampleBudget, count: (T) -> Long,
    assemble: (historySize: Int, includeExamples: Boolean) -> T,
): FittedPrompt<T> {
    fun sample(size: Int, examples: Boolean): FittedPrompt<T> {
        val value = assemble(size, examples)
        return FittedPrompt(value, size, count(value))
    }
    val initialSize = 80.coerceIn(minimum, maximum)
    val initial = sample(initialSize, examples != ExampleBudget.OMITTED)
    if (initial.historySize == maximum && initial.tokens <= limit) return initial
    val pinExamples = examples == ExampleBudget.PINNED
    val seed = if (examples == ExampleBudget.AVAILABLE_SPACE) sample(initialSize, false) else initial
    var upper = maximum + 1
    var fitted = if (seed.tokens <= limit) seed else {
        upper = initialSize
        val smallest = if (initialSize == minimum) seed else sample(minimum, pinExamples)
        if (smallest.tokens > limit) return smallest
        smallest
    }
    while (upper - fitted.historySize > 1) {
        val size = if (upper > maximum) {
            minOf(maximum, maxOf(fitted.historySize + 1, fitted.historySize * 2))
        } else {
            fitted.historySize + (upper - fitted.historySize) / 2
        }
        val trial = sample(size, pinExamples)
        if (trial.tokens <= limit) fitted = trial else upper = size
    }
    if (examples != ExampleBudget.AVAILABLE_SPACE) return fitted
    val withExamples = if (fitted.historySize == initialSize) initial else sample(fitted.historySize, true)
    return withExamples.takeIf { it.tokens <= limit } ?: fitted
}
