package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal val scriptTextCommands = setOf("replace", "re", "test", "match", "array-wrap", "list-wrap", "array-unwrap", "list-unwrap")

internal fun scriptTextCommand(command: String, named: Map<String, String>, text: String): String {
    fun pattern() = named["pattern"]?.takeIf(String::isNotEmpty) ?: error("请用 pattern= 指定搜索表达式")
    return when (command) {
        "replace", "re" -> {
            val find = pattern()
            val replace = named["replacer"].orEmpty()
            when (named["mode"] ?: "literal") {
                "literal" -> scriptLiteralReplace(find, replace, text)
                "regex" -> scriptRegexReplace(find, replace, text)
                else -> error("mode= 应为 literal 或 regex")
            }
        }
        "test" -> scriptRegexTest(pattern(), text).toString()
        "match" -> scriptRegexMatch(pattern(), text)
        "array-wrap", "list-wrap" -> {
            val stringify = named["stringify"].orEmpty().trim().lowercase() !in setOf("false", "off", "0")
            wrapScriptArray(text, stringify)
        }
        "array-unwrap", "list-unwrap" -> unwrapScriptArray(text)
        else -> error("未知的文本命令 $command")
    }
}

private fun wrapScriptArray(text: String, stringify: Boolean): String {
    if (text.isEmpty()) return "[]"
    val value = readScriptJson(text)
    if (value is JSONArray) return text
    return JSONArray().put(if (value is JSONObject || !stringify && value != null) value else text).toString()
}

private fun unwrapScriptArray(text: String): String {
    val value = readScriptJson(text) as? JSONArray ?: return text
    return if (value.isNull(0)) "" else value.opt(0)?.toString().orEmpty()
}

private fun readScriptJson(text: String): Any? {
    // JSONTokener also accepts Javascript-style names and comments. STscript uses JSON.parse.
    if (!ScriptJsonSyntax(text).complete()) return null
    return runCatching { JSONTokener(text).nextValue() }.getOrNull()
}

private val jsonNumber = Regex("""-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?""")
private val jsonString = Regex("""\x22(?:[^\x22\\\x00-\x1F]|\\(?:[\x22\\/bfnrt]|u[0-9a-fA-F]{4}))*+\x22""")

private class ScriptJsonSyntax(private val source: String) {
    private var cursor = 0

    fun complete(): Boolean = value(0) && run { skipSpace(); cursor == source.length }

    private fun value(depth: Int): Boolean {
        if (depth > 96) return false
        skipSpace()
        return when (source.getOrNull(cursor)) {
            '"' -> token(jsonString)
            '[' -> { cursor++; entries(']', depth, objectKeys = false) }
            '{' -> { cursor++; entries('}', depth, objectKeys = true) }
            'n' -> literal("null")
            't' -> literal("true")
            'f' -> literal("false")
            '-', in '0'..'9' -> token(jsonNumber)
            else -> false
        }
    }

    private fun entries(end: Char, depth: Int, objectKeys: Boolean): Boolean {
        if (take(end)) return true
        do {
            if (objectKeys && (!token(jsonString) || !take(':'))) return false
            if (!value(depth + 1)) return false
            if (take(end)) return true
        } while (take(','))
        return false
    }

    private fun take(expected: Char): Boolean {
        skipSpace()
        if (source.getOrNull(cursor) != expected) return false
        cursor++
        return true
    }

    private fun literal(expected: String): Boolean {
        if (!source.startsWith(expected, cursor)) return false
        cursor += expected.length
        return true
    }

    private fun token(pattern: Regex): Boolean {
        skipSpace()
        val match = pattern.matchAt(source, cursor) ?: return false
        cursor = match.range.last + 1
        return true
    }

    private fun skipSpace() {
        while (cursor < source.length && source[cursor] in " \t\r\n") cursor++
    }
}

internal fun fuzzyMatchList(
    query: String,
    candidates: List<String>,
    threshold: Double = 0.4,
    mode: String = "first",
): String {
    val needle = query.trim()
    val limit = threshold.coerceIn(0.0, 1.0)
    if (candidates.isEmpty()) return if (mode.equals("all", true)) "[]" else ""
    if (needle.isEmpty()) {
        return if (mode.equals("all", true)) JSONArray(candidates).toString() else candidates.first()
    }
    val scored = candidates.mapNotNull { candidate ->
        val score = fuzzyScore(needle, candidate)
        if (score >= limit) candidate to score else null
    }.sortedByDescending { it.second }.map { it.first }
    return when (mode.lowercase()) {
        "all" -> JSONArray(scored).toString()
        "first", "" -> scored.firstOrNull().orEmpty()
        else -> error("mode= 应为 first 或 all")
    }
}

internal fun fuzzyScore(query: String, candidate: String): Double {
    val q = query.trim().lowercase()
    val c = candidate.trim().lowercase()
    if (q.isEmpty() && c.isEmpty()) return 1.0
    if (q.isEmpty() || c.isEmpty()) return 0.0
    if (q == c) return 1.0
    if (c.startsWith(q)) return 0.90 + 0.09 * (q.length.toDouble() / c.length.toDouble())
    if (q in c) return 0.75 + 0.14 * (q.length.toDouble() / c.length.toDouble())
    val dist = levenshteinDistance(q, c)
    val maxLen = maxOf(q.length, c.length)
    val editScore = (1.0 - dist.toDouble() / maxLen.toDouble()).coerceIn(0.0, 1.0)
    val subseqScore = subsequenceScore(q, c)
    return maxOf(editScore, subseqScore)
}

private fun subsequenceScore(query: String, candidate: String): Double {
    var qi = 0
    var contiguous = 0
    var bestContiguous = 0
    for (ch in candidate) {
        if (qi < query.length && ch == query[qi]) {
            qi++
            contiguous++
            if (contiguous > bestContiguous) bestContiguous = contiguous
        } else {
            contiguous = 0
        }
    }
    if (qi < query.length) return 0.0
    val density = query.length.toDouble() / candidate.length.toDouble()
    val streak = bestContiguous.toDouble() / query.length.toDouble()
    return (0.45 + 0.30 * density + 0.20 * streak).coerceAtMost(0.88)
}

private fun levenshteinDistance(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var prev = IntArray(b.length + 1) { it }
    var curr = IntArray(b.length + 1)
    for (i in 1..a.length) {
        curr[0] = i
        val ca = a[i - 1]
        for (j in 1..b.length) {
            val cost = if (ca == b[j - 1]) 0 else 1
            curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
        }
        val tmp = prev
        prev = curr
        curr = tmp
    }
    return prev[b.length]
}
