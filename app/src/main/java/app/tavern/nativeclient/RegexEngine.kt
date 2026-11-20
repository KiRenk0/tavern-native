package app.tavern.nativeclient

import org.jcodings.specific.UTF8Encoding
import org.joni.Option
import org.joni.Regex as JoniRegex
import org.joni.Syntax
import org.joni.WarnCallback
import org.joni.constants.SyntaxProperties.OP2_ESC_K_NAMED_BACKREF
import org.joni.constants.SyntaxProperties.OP2_QMARK_LT_NAMED_GROUP
import org.joni.exception.JOniException

internal data class RegexTestResult(val text: String, val issue: String? = null)

internal class RegexRuntime(private val rules: List<RegexRule>, private val names: RegexNames) {
    fun applies(site: RegexSite, stage: RegexStage) = rules.any { it.applies(site, stage, null, false) }

    fun named(name: String, text: String): String {
        val rule = rules.firstOrNull { !it.disabled && it.name == name } ?: error("找不到可用的正则规则 $name")
        return regexTest(rule, text, names).text
    }

    fun transform(text: String, site: RegexSite, stage: RegexStage, depth: Int? = null, editing: Boolean = false): String {
        if (rules.isEmpty() || text.isEmpty()) return text
        val deadline = System.nanoTime() + MATCH_NANOS
        var current = text
        for (rule in rules) {
            if (System.nanoTime() >= deadline || current.isEmpty()) break
            if (rule.applies(site, stage, depth, editing)) current = evaluate(rule, current, names, deadline).text
        }
        return current
    }

    companion object {
        val EMPTY = RegexRuntime(emptyList(), RegexNames(null, ""))
    }
}

private const val MATCH_NANOS = 150_000_000L
private const val MAX_PATTERN_LENGTH = 65_536
private const val MAX_REPLACEMENT_SIZE = 1_000_000
private val replacements = Regex("""\{\{match\}\}|\$(\d+)|\$<([^>]+)>""", RegexOption.IGNORE_CASE)
private val encoding = UTF8Encoding.INSTANCE
private val javascript = Syntax.ECMAScript.run {
    Syntax("JavaScript", op, op2 or OP2_QMARK_LT_NAMED_GROUP or OP2_ESC_K_NAMED_BACKREF, op3, behavior, options, metaCharTable)
}
private val cached = linkedMapOf<String, CompiledPattern?>()

private data class PatternSource(val text: String, val flags: String) {
    companion object {
        fun parse(input: String): PatternSource {
            val delimiter = input.lastIndexOf('/')
            if (input.startsWith('/') && delimiter > 0) {
                val flags = input.substring(delimiter + 1)
                if (flags.all(Char::isLetter)) {
                    require(flags.all { it in "gimsuyd" } && flags.length == flags.toSet().size)
                    return PatternSource(input.substring(1, delimiter), flags)
                }
            }
            return PatternSource(input, "")
        }
    }
}

private data class CompiledPattern(val regex: JoniRegex, val source: PatternSource)

private fun compiled(input: String): CompiledPattern? {
    synchronized(cached) { if (input in cached) return cached[input] }
    val result = try {
        require(input.isNotEmpty() && input.length <= MAX_PATTERN_LENGTH)
        val source = PatternSource.parse(input)
        val bytes = source.text.encodeToByteArray()
        var options = if ('m' in source.flags) Option.NEGATE_SINGLELINE else Option.SINGLELINE
        if ('s' in source.flags) options = options or Option.MULTILINE
        if ('i' in source.flags) options = options or Option.IGNORECASE
        CompiledPattern(JoniRegex(bytes, 0, bytes.size, options, encoding, javascript, WarnCallback.NONE), source)
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: JOniException) {
        null
    } catch (_: StackOverflowError) {
        null
    }
    synchronized(cached) {
        if (cached.size >= 128) cached.remove(cached.keys.first())
        cached[input] = result
    }
    return result
}

private fun escapedMacro(text: String): String = buildString {
    for (char in text) when (char) {
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> {
            if (char in "\\.^$*+?{}[]/|()") append('\\')
            append(char)
        }
    }
}

internal fun regexTest(rule: RegexRule, test: String, names: RegexNames) =
    evaluate(rule, test, names, System.nanoTime() + MATCH_NANOS)

private fun evaluate(rule: RegexRule, input: String, names: RegexNames, deadline: Long): RegexTestResult {
    val expression = when (rule.macros) {
        RegexMacros.NONE -> rule.pattern
        RegexMacros.RAW -> names.expand(rule.pattern)
        RegexMacros.ESCAPED -> names.expand(rule.pattern, ::escapedMacro)
    }
    val compiled = compiled(expression) ?: return RegexTestResult(input, "正则表达式或标志无效")
    if (input.isEmpty()) return RegexTestResult(input)
    val bytes = input.encodeToByteArray()
    val matcher = compiled.regex.matcher(bytes, 0, bytes.size, MATCH_NANOS)
    val trim = rule.trims.map(names::expand).filter(String::isNotEmpty)
    val result = StringBuilder()
    val limit = maxOf(MAX_REPLACEMENT_SIZE, input.length)
    var cursor = 0
    var next = 0
    var matched = false
    try {
        while (next <= bytes.size) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return RegexTestResult(input, "正则处理超时")
            matcher.setTimeout(remaining)
            val index = matcher.searchInterruptible(next, bytes.size, Option.NONE)
            if (index < 0 || ('y' in compiled.source.flags && matcher.begin != next)) break
            val start = matcher.begin
            val end = matcher.end
            val region = matcher.eagerRegion
            val updated = replacements.replace(rule.replacement) { token ->
                val numeric = token.groups[1]?.let { it.value.toIntOrNull() ?: -1 } ?: 0
                val named = token.groups[2]?.value
                val number = if (named == null) numeric else {
                    val raw = named.encodeToByteArray()
                    runCatching { compiled.regex.nameToBackrefNumber(raw, 0, raw.size, region) }.getOrDefault(-1)
                }
                val value = when {
                    number == 0 -> String(bytes, start, end - start, Charsets.UTF_8)
                    number in 1 until region.numRegs && region.getBeg(number) >= 0 ->
                        String(bytes, region.getBeg(number), region.getEnd(number) - region.getBeg(number), Charsets.UTF_8)
                    else -> ""
                }
                trim.fold(value) { text, part -> text.replace(part, "") }
            }
            result.append(String(bytes, cursor, start - cursor, Charsets.UTF_8)).append(names.expand(updated))
            if (result.length > limit) return RegexTestResult(input, "替换结果过长")
            matched = true
            cursor = end
            if ('g' !in compiled.source.flags) break
            next = if (end > start) end else if (end == bytes.size) end + 1 else end + encoding.length(bytes, end, bytes.size)
        }
    } catch (_: InterruptedException) {
        return RegexTestResult(input, "正则处理超时")
    } catch (_: StackOverflowError) {
        return RegexTestResult(input, "正则表达式过于复杂")
    }
    if (!matched) return RegexTestResult(input)
    result.append(String(bytes, cursor, bytes.size - cursor, Charsets.UTF_8))
    return if (result.length <= limit) RegexTestResult(result.toString()) else RegexTestResult(input, "替换结果过长")
}

internal fun regexKeywordMatches(key: String, text: String): Boolean? {
    if (!key.startsWith('/') || key.lastIndexOf('/') == 0) return null
    val pattern = compiled(key)?.takeIf { it.source.text != key } ?: return null
    val bytes = text.encodeToByteArray()
    return try {
        val matcher = pattern.regex.matcher(bytes, 0, bytes.size, MATCH_NANOS)
        val index = matcher.searchInterruptible(0, bytes.size, Option.NONE)
        index >= 0 && ('y' !in pattern.source.flags || index == 0)
    } catch (_: InterruptedException) {
        false
    }
}
