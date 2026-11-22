package app.tavern.nativeclient

import org.jcodings.specific.UTF8Encoding
import org.joni.Matcher
import org.joni.Option
import org.joni.Regex as JoniRegex
import org.joni.Syntax
import org.joni.WarnCallback
import org.joni.constants.SyntaxProperties.OP2_ESC_K_NAMED_BACKREF
import org.joni.constants.SyntaxProperties.OP2_QMARK_LT_NAMED_GROUP
import org.joni.exception.JOniException
import org.json.JSONArray
import org.json.JSONObject

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

internal fun validModelRegex(pattern: String): Boolean = compiled(pattern) != null

internal fun matchModelRegex(pattern: String, model: String, deadline: Long): Boolean {
    if (System.nanoTime() >= deadline) return false
    val expression = compiled(pattern) ?: return false
    val remaining = deadline - System.nanoTime()
    if (remaining <= 0) return false
    return expression.matches(model.take(512), remaining)
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
    val trim = rule.trims.map(names::expand).filter(String::isNotEmpty)
    val result = StringBuilder()
    val limit = maxOf(MAX_REPLACEMENT_SIZE, input.length)
    var cursor = 0
    var matched = false
    try {
        compiled.visitMatches(bytes, deadline) { matcher ->
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

private inline fun CompiledPattern.visitMatches(bytes: ByteArray, deadline: Long, global: Boolean = 'g' in source.flags,
                                                visit: (Matcher) -> Unit) {
    val matcher = regex.matcher(bytes, 0, bytes.size, MATCH_NANOS)
    var next = 0
    while (next <= bytes.size) {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) throw InterruptedException()
        matcher.setTimeout(remaining)
        val index = matcher.searchInterruptible(next, bytes.size, Option.NONE)
        if (index < 0 || ('y' in source.flags && matcher.begin != next)) break
        visit(matcher)
        if (!global) break
        val end = matcher.end
        next = if (end > matcher.begin) end else if (end == bytes.size) end + 1 else end + encoding.length(bytes, end, bytes.size)
    }
}

private fun scriptPattern(pattern: String): CompiledPattern = requireNotNull(compiled(pattern)) { "正则表达式或标志无效" }

private inline fun <T> scriptRegex(action: (Long) -> T): T = try {
    action(System.nanoTime() + MATCH_NANOS)
} catch (_: InterruptedException) {
    error("正则处理超时")
} catch (_: StackOverflowError) {
    error("正则表达式过于复杂")
}

internal fun scriptRegexTest(pattern: String, text: String): Boolean {
    val expression = scriptPattern(pattern)
    val bytes = text.encodeToByteArray()
    return scriptRegex { deadline ->
        expression.visitMatches(bytes, deadline, global = false) { return true }
        false
    }
}

internal fun scriptRegexMatch(pattern: String, text: String): String {
    val expression = scriptPattern(pattern)
    val bytes = text.encodeToByteArray()
    val global = 'g' in expression.source.flags
    return scriptRegex { deadline ->
        val matches = StringBuilder("[")
        expression.visitMatches(bytes, deadline) { match ->
            val row = JSONArray()
            row.put(String(bytes, match.begin, match.end - match.begin, Charsets.UTF_8))
            for (group in 1..expression.regex.numberOfCaptures()) row.put(match.capture(bytes, group) ?: JSONObject.NULL)
            if (!global) return row.toString()
            if (matches.length > 1) matches.append(',')
            matches.appendBounded(row.toString(), MAX_REPLACEMENT_SIZE - 1)
        }
        if (global) matches.append(']').toString() else ""
    }
}

internal fun scriptRegexReplace(pattern: String, replacement: String, input: String): String {
    val expression = scriptPattern(pattern)
    val bytes = input.encodeToByteArray()
    val output = StringBuilder()
    val limit = maxOf(MAX_REPLACEMENT_SIZE, input.length)
    val slice: (Int, Int) -> String = { start, end -> String(bytes, start, end - start, Charsets.UTF_8) }
    val count = expression.regex.numberOfCaptures()
    val hasNames = expression.regex.numberOfNames() != 0
    var cursor = 0
    scriptRegex { deadline ->
        expression.visitMatches(bytes, deadline) { match ->
            output.appendBounded(slice(cursor, match.begin), limit)
            val named: ((String) -> String?)? = if (hasNames) { name ->
                val key = name.encodeToByteArray()
                val index = runCatching { expression.regex.nameToBackrefNumber(key, 0, key.size, match.eagerRegion) }.getOrDefault(-1)
                match.capture(bytes, index)
            } else null
            output.appendSubstitution(replacement, match.begin, match.end, bytes.size, limit, slice, count,
                capture = { group -> match.capture(bytes, group) }, named = named)
            cursor = match.end
        }
    }
    return if (output.isEmpty() && cursor == 0) input else output.appendBounded(slice(cursor, bytes.size), limit).toString()
}

internal fun scriptLiteralReplace(pattern: String, replacement: String, input: String): String {
    require(pattern.isNotEmpty()) { "请填写 pattern= 搜索文本" }
    val output = StringBuilder()
    val limit = maxOf(MAX_REPLACEMENT_SIZE, input.length)
    var cursor = 0
    while (true) {
        val start = input.indexOf(pattern, cursor)
        if (start < 0) break
        val end = start + pattern.length
        output.appendBounded(input.substring(cursor, start), limit)
        output.appendSubstitution(replacement, start, end, input.length, limit, input::substring)
        cursor = end
    }
    return output.appendBounded(input.substring(cursor), limit).toString()
}

private fun Matcher.capture(bytes: ByteArray, number: Int): String? {
    val region = eagerRegion
    if (number !in 1 until region.numRegs) return null
    val start = region.getBeg(number)
    return if (start < 0) null else String(bytes, start, region.getEnd(number) - start, Charsets.UTF_8)
}

private fun StringBuilder.appendBounded(value: String, limit: Int): StringBuilder {
    require(value.length <= limit - length) { "正则输出或替换结果过长" }
    return append(value)
}

private fun StringBuilder.appendSubstitution(template: String, begin: Int, end: Int, extent: Int, limit: Int,
                                            slice: (Int, Int) -> String, captureCount: Int = 0,
                                            capture: (Int) -> String? = { null }, named: ((String) -> String?)? = null) {
    var cursor = 0
    var canReadName = named != null
    while (cursor < template.length) {
        val dollar = template.indexOf('$', cursor)
        if (dollar < 0) break
        appendBounded(template.substring(cursor, dollar), limit)
        cursor = dollar + 1
        val marker = template.getOrNull(cursor)
        val replacement = when (marker) {
            '$' -> "$"
            '&' -> slice(begin, end)
            '`' -> slice(0, begin)
            '\'' -> slice(end, extent)
            '<' -> if (!canReadName) null else {
                val close = template.indexOf('>', cursor + 1)
                if (close < 0) {
                    canReadName = false
                    null
                } else named?.invoke(template.substring(cursor + 1, close)).orEmpty().also { cursor = close }
            }
            in '0'..'9' -> {
                val digit = requireNotNull(marker) - '0'
                val second = template.getOrNull(cursor + 1)?.takeIf { it in '0'..'9' }
                val pair = second?.let { digit * 10 + (it - '0') }
                when {
                    pair != null && pair in 1..captureCount -> capture(pair).orEmpty().also { cursor++ }
                    digit in 1..captureCount -> capture(digit).orEmpty()
                    else -> null
                }
            }
            else -> null
        }
        if (replacement == null) appendBounded("$", limit) else {
            appendBounded(replacement, limit)
            cursor++
        }
    }
    appendBounded(template.substring(cursor), limit)
}

internal fun regexKeywordMatches(key: String, text: String): Boolean? {
    if (!key.startsWith('/') || key.lastIndexOf('/') == 0) return null
    val pattern = compiled(key)?.takeIf { it.source.text != key } ?: return null
    return pattern.matches(text, MATCH_NANOS)
}

private fun CompiledPattern.matches(text: String, timeout: Long): Boolean {
    val bytes = text.encodeToByteArray()
    return try {
        val matcher = regex.matcher(bytes, 0, bytes.size, timeout)
        val index = matcher.searchInterruptible(0, bytes.size, Option.NONE)
        index >= 0 && ('y' !in source.flags || index == 0)
    } catch (_: InterruptedException) {
        false
    }
}
