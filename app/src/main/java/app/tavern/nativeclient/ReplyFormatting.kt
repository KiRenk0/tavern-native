package app.tavern.nativeclient

import org.json.JSONObject

internal const val REPLY_PREFIX_LIMIT = 4096
internal const val REASONING_MARKER_LIMIT = 512

internal data class ReasoningFormat(
    val name: String = "DeepSeek",
    val prefix: String = "<think>\n",
    val suffix: String = "\n</think>",
    val separator: String = "\n\n",
) {
    fun json() = jsonOf("name" to name, "prefix" to prefix, "suffix" to suffix, "separator" to separator)
    fun render(body: String, thought: String) = "$prefix$thought$suffix$separator$body"

    fun validated(): ReasoningFormat {
        require(name.isNotBlank() && name.length <= 80) { "请输入 1–80 个字符的模板名称" }
        require(listOf(prefix, suffix, separator).all { it.length <= REASONING_MARKER_LIMIT }) {
            "每段推理标记不能超过 $REASONING_MARKER_LIMIT 个字符"
        }
        return copy(name = name.trim())
    }

    companion object {
        fun from(json: JSONObject, fallback: String = ""): ReasoningFormat {
            require(listOf("prefix", "suffix", "separator").all { json.opt(it) is String }) { "JSON 缺少推理格式字段" }
            return ReasoningFormat(json.string("name").ifBlank { fallback }, json.string("prefix"),
                json.string("suffix"), json.string("separator")).validated()
        }
    }
}

internal data class ReplyFormatting(
    val prefix: String = "",
    val showPrefix: Boolean = true,
    val reasoning: ReasoningFormat = ReasoningFormat(),
    val parseReasoning: Boolean = false,
    val maxReasoning: Int = 80,
    val autoExpand: Boolean = false,
) {
    fun json() = jsonOf(
        "prefix" to prefix, "showPrefix" to showPrefix, "reasoning" to reasoning.json(),
        "parseReasoning" to parseReasoning, "maxReasoning" to maxReasoning, "autoExpand" to autoExpand,
    )

    companion object {
        fun from(json: JSONObject?): ReplyFormatting {
            if (json == null) return ReplyFormatting()
            return ReplyFormatting(
                prefix = json.string("prefix").take(REPLY_PREFIX_LIMIT),
                showPrefix = json.optBoolean("showPrefix", true),
                reasoning = json.optJSONObject("reasoning")?.let { runCatching { ReasoningFormat.from(it) }.getOrNull() }
                    ?: ReasoningFormat(),
                parseReasoning = json.optBoolean("parseReasoning"),
                maxReasoning = json.optInt("maxReasoning", 80).coerceIn(0, 80),
                autoExpand = json.optBoolean("autoExpand"),
            )
        }
    }
}

/** Removes an exact prefix even when the network divides it over multiple fragments. */
private class InitialText(private val expected: String) {
    private var pending = ""
    private var resolved = expected.isEmpty()

    fun accept(next: String): String {
        if (resolved) return next
        val value = pending + next
        if (value.length < expected.length && expected.startsWith(value)) {
            pending = value
            return ""
        }
        resolved = true
        pending = ""
        return value.removePrefix(expected)
    }

    fun finish(): String = pending.also { resolved = true; pending = "" }
}

internal class FormattedReplyStream(
    private val prefix: String,
    showPrefix: Boolean,
    private val format: ReasoningFormat?,
    private val onReasoning: (String) -> Unit,
    private val onBody: (String) -> Unit,
    private val onHiddenPrefix: (String) -> Unit = {},
) {
    private enum class Section { START, REASONING, BODY }
    private val seed = format?.takeIf { prefix.startsWith(it.prefix) }
    private val seedEnd = seed?.let { prefix.indexOf(it.suffix, it.prefix.length) } ?: -1
    private val seedReasoning = seed?.let { prefix.substring(it.prefix.length, if (seedEnd >= 0) seedEnd else prefix.length) }.orEmpty()
    private val seedBody = when {
        seed == null -> prefix
        seedEnd < 0 -> ""
        else -> prefix.substring(seedEnd + seed.suffix.length).removePrefix(seed.separator)
    }
    val hiddenPrefix: String = seedBody.takeUnless { showPrefix }.orEmpty()
    var hasBody: Boolean = false
        private set
    var hasReasoning: Boolean = false
        private set
    private var section = when {
        format == null || seedEnd >= 0 -> Section.BODY
        seed != null -> Section.REASONING
        else -> Section.START
    }
    private var pending = ""
    private var echoed = InitialText(prefix)
    private var separator: InitialText? = null
    private var seeded = false
    private var nativeReasoning = false
    private var bodyStarted = false

    fun acceptNativeReasoning(text: String) {
        if (text.isEmpty()) return
        nativeReasoning = true
        if (section == Section.START && pending.isEmpty()) section = Section.BODY
        thought(text)
    }

    fun accept(text: String) {
        if (text.isEmpty()) return
        if (!seeded) {
            seeded = true
            if (!nativeReasoning) thought(seedReasoning)
        }
        consume(echoed.accept(text))
    }

    fun finish() {
        consume(echoed.finish())
        if (section == Section.REASONING) thought(pending)
        else body(pending)
        pending = ""
        body(separator?.finish().orEmpty())
    }

    private fun consume(chunk: String) {
        if (chunk.isEmpty()) return
        val marker = format
        if (section == Section.BODY || marker == null) {
            body(separator?.accept(chunk) ?: chunk)
            return
        }
        var text = pending + chunk
        pending = ""
        if (section == Section.START) {
            if (text.length < marker.prefix.length && marker.prefix.startsWith(text)) {
                pending = text
                return
            }
            if (!text.startsWith(marker.prefix)) {
                section = Section.BODY
                body(text)
                return
            }
            section = Section.REASONING
            text = text.removePrefix(marker.prefix)
        }
        val end = text.indexOf(marker.suffix)
        if (end >= 0) {
            thought(text.substring(0, end))
            section = Section.BODY
            separator = InitialText(marker.separator)
            body(separator?.accept(text.substring(end + marker.suffix.length)).orEmpty())
        } else {
            val held = (minOf(text.length, marker.suffix.length - 1) downTo 1).firstOrNull { count ->
                text.regionMatches(text.length - count, marker.suffix, 0, count)
            } ?: 0
            thought(text.dropLast(held))
            pending = text.takeLast(held)
        }
    }

    private fun thought(value: String) {
        if (value.isEmpty()) return
        hasReasoning = hasReasoning || value.isNotBlank()
        onReasoning(value)
    }

    private fun body(value: String) {
        if (value.isEmpty()) return
        if (!bodyStarted && seedBody.isNotEmpty()) {
            bodyStarted = true
            if (hiddenPrefix.isNotEmpty()) onHiddenPrefix(hiddenPrefix)
            onBody(seedBody)
        }
        hasBody = hasBody || value.isNotBlank()
        onBody(value)
    }
}

internal class GroupReplyTrimmer(otherSpeakers: List<String>) {
    private val filter = InstructStopFilter(otherSpeakers.map { "\n$it:" })
    private var started = false
    private var sent = false
    val stopped get() = filter.stopped

    fun append(fragment: String): String {
        if (fragment.isEmpty()) return ""
        val chunk = if (started) fragment else "\n$fragment"
        started = true
        return visible(filter.append(chunk))
    }

    fun finish(): String = visible(filter.flush())

    private fun visible(text: String): String {
        if (sent || text.isEmpty()) return text
        sent = true
        return text.removePrefix("\n")
    }
}
