package app.tavern.nativeclient

import java.time.ZonedDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.random.Random

private val legacyNames = Regex("<(USER|BOT|CHAR|GROUP|CHARIFNOTGROUP)>", RegexOption.IGNORE_CASE)
private val diceFormula = Regex("(?i)([0-9]*)d([0-9]+)([+-][0-9]+)?")
private val falsyMacros = setOf("", "false", "off", "no", "0")
private val macroEscapes = setOf('{', '}')
private val argumentEscapes = macroEscapes + setOf(',', ':')
private val utcOffset = Regex("(?i)UTC(?:([+-])([0-9]{1,2})(?::?([0-9]{2}))?)?")
private val variableExpression = Regex("""([.$])(\p{L}(?:[\p{L}\p{N}_-]*[\p{L}\p{N}])?)\s*(\|\|=|\?\?=|\+\+|--|\+=|-=|\|\||\?\?|==|!=|>=|<=|=|>|<)?""")
private val scopedVariables = setOf("setvar", "setglobalvar", "addvar", "addglobalvar", "setvarkey",
    "setglobalvarkey", "setvarindex", "setglobalvarindex")

internal fun macroDice(formula: String, random: Random): String {
    val value = formula.trim().replace(" ", "")
    val digits = value.toIntOrNull()
    val match = if (digits == null) diceFormula.matchEntire(value) ?: return "" else null
    val count = if (match == null) 1 else match.groupValues[1].ifEmpty { "1" }.toIntOrNull() ?: return ""
    val faces = digits ?: match?.groupValues?.get(2)?.toIntOrNull() ?: return ""
    val modifier = match?.groupValues?.get(3).orEmpty().ifEmpty { "0" }.toLongOrNull() ?: return ""
    if (count !in 1..1_000 || faces !in 1..1_000_000 || modifier !in -1_000_000_000L..1_000_000_000L) return ""
    var total = modifier
    repeat(count) { total += random.nextInt(1, faces + 1) }
    return total.toString()
}

internal class TavernMacroRenderer(
    private val seed: String,
    private val lookup: (String, List<String>) -> String?,
    private val random: Random = Random.Default,
    private val now: ZonedDateTime = ZonedDateTime.now(),
) {
    fun expand(text: String): String = if ("{{" !in text && '<' !in text && "\\{" !in text && "\\}" !in text) text
        else Source(text).render(0, text.length, 0)

    private data class Tag(val name: String, val args: Int, val end: Int, val preserve: Boolean)
    private data class Scope(val close: Int, val end: Int, val otherwise: Int?, val elseEnd: Int?)
    private data class ClosingScope(val name: String, val start: Int, val end: Int, var otherwise: Int? = null, var elseEnd: Int? = null)

    private inner class Source(private val text: String) {
        private val ends = hashMapOf<Int, Int>()
        private val contentHash = text.hashCode()

        init {
            val openings = ArrayDeque<Int>()
            var i = 0
            while (i < text.length) {
                when {
                    escaped(i) -> i += 2
                    text.startsWith("{{", i) -> { openings.addLast(i); i += 2 }
                    text.startsWith("}}", i) -> {
                        openings.removeLastOrNull()?.let { ends[it] = i + 2 }
                        i += 2
                    }
                    else -> i++
                }
            }
        }

        private fun escaped(at: Int, argument: Boolean = false) = text[at] == '\\' &&
            text.getOrNull(at + 1) in if (argument) argumentEscapes else macroEscapes

        private fun tag(start: Int, end: Int): Tag {
            var i = start + 2
            val limit = end - 2
            fun skipSpace() { while (i < limit && text[i].isWhitespace()) i++ }
            skipSpace()
            val preserve = i < limit && text[i] == '#'
            if (preserve) { i++; skipSpace() }
            if (i < limit && text[i] in ".$") return Tag("variable", i, limit, preserve)
            val nameAt = i
            val name: String
            if (text.startsWith("//", i)) {
                i += if (text.startsWith("///", i)) 3 else 2
                name = text.substring(nameAt, i)
            } else {
                val closing = i < limit && text[i] == '/'
                if (closing) i++
                if (closing) skipSpace()
                val identifierStart = if (closing) i else nameAt
                while (i < limit && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '-')) i++
                val identifier = text.substring(identifierStart, i).lowercase()
                name = if (i < limit && !text[i].isWhitespace() && text[i] != ':') ""
                    else if (closing) "/$identifier" else identifier
            }
            skipSpace()
            if (i < limit && text[i] == ':') {
                i++
                if (i < limit && text[i] == ':') i++
            }
            return Tag(name, i, limit, preserve)
        }

        private fun scopes(from: Int, to: Int): Map<Int, Scope> {
            val found = hashMapOf<Int, Scope>()
            val tags = mutableListOf<Int>()
            var at = from
            while (at < to) {
                val end = ends[at]?.takeIf { it <= to }
                if (end != null) {
                    tags += at
                    at = end
                } else at++
            }
            // A standalone trim or comment is only a block opener if it has a matching closing tag.
            val stack = ArrayDeque<ClosingScope>()
            for (start in tags.asReversed()) {
                val end = ends.getValue(start)
                val token = tag(start, end)
                val top = stack.lastOrNull()
                when {
                    top != null && token.name == top.name &&
                        (token.name == "if" || token.name in scopedVariables || token.args == token.end) -> {
                        stack.removeLast()
                        found[start] = Scope(top.start, top.end, top.otherwise, top.elseEnd)
                    }
                    top?.name == "//" -> Unit
                    token.name == "/if" || token.name == "/trim" || token.name == "///" ||
                        token.name.startsWith('/') && token.name.drop(1) in scopedVariables ->
                        stack.addLast(ClosingScope(token.name.drop(1), start, end))
                    token.name == "else" && top?.name == "if" -> {
                        top.otherwise = start
                        top.elseEnd = end
                    }
                }
            }
            return found
        }

        fun render(from: Int, to: Int, depth: Int, argument: Boolean = false): String {
            require(depth <= 32) { "宏嵌套层数过多" }
            val blocks = scopes(from, to)
            return buildString {
                var at = from
                while (at < to) {
                    val end = ends[at]?.takeIf { it <= to }
                    val legacy = if (text[at] == '<') legacyNames.matchAt(text, at)?.takeIf { it.range.last < to } else null
                    when {
                        escaped(at, argument) && at + 1 < to -> { append(text[at + 1]); at += 2 }
                        legacy != null -> {
                            val name = legacy.groupValues[1].lowercase().let { if (it == "bot") "char" else it }
                            append(lookup(name, emptyList()) ?: legacy.value)
                            at = legacy.range.last + 1
                        }
                        end == null -> append(text[at++])
                        else -> {
                            val token = tag(at, end)
                            val block = blocks[at]
                            if (block != null) {
                                if (token.name != "//") {
                                    val condition = token.name != "if" || condition(token, depth)
                                    val start = if (condition) end else block.elseEnd ?: block.close
                                    val stop = if (condition) block.otherwise ?: block.close else block.close
                                    val raw = render(start, stop, depth + 1)
                                    val content = if (token.preserve) raw else raw.trimIndent().trim()
                                    if (token.name in scopedVariables) {
                                        lookup(token.name, arguments(token, depth) + content)?.let(::append)
                                    } else append(content)
                                }
                                at = block.end
                            } else if (token.name == "trim" && token.args == token.end) {
                                while (isNotEmpty() && last() in "\r\n") setLength(length - 1)
                                at = end
                                while (at < to && text[at] in "\r\n") at++
                            } else {
                                append(value(token, at, depth) ?: text.substring(at, end))
                                at = end
                            }
                        }
                    }
                    require(length <= 1_000_000) { "宏展开结果过长" }
                }
            }
        }

        private fun condition(token: Tag, depth: Int): Boolean {
            var start = token.args
            val negated = start < token.end && text[start] == '!'
            if (negated) start++
            while (start < token.end && text[start].isWhitespace()) start++
            val condition = text.substring(start, token.end).trimEnd()
            val value = when {
                condition.startsWith('.') || condition.startsWith('$') -> variable(start, token.end, depth).orEmpty()
                condition.startsWith("{{") -> render(start, token.end, depth + 1).trim()
                else -> lookup(condition.lowercase(), emptyList()) ?: condition
            }
            return truthy(value) != negated
        }

        private fun variable(start: Int, end: Int, depth: Int): String? {
            val expression = text.substring(start, end)
            val parts = variableExpression.matchAt(expression, 0) ?: return null
            val (prefix, name, operation) = parts.destructured
            val valueAt = start + parts.range.last + 1
            if (operation in setOf("", "++", "--") && text.substring(valueAt, end).isNotBlank()) return null
            val scope = if (prefix == "$") "global" else ""
            fun run(action: String, item: String? = null) =
                lookup(action + scope + "var", listOfNotNull(name, item)).orEmpty()
            fun right() = render(valueAt, end, depth + 1, argument = true).trim()
            val current = run("get")
            return when (operation) {
                "" -> current
                "=" -> run("set", right())
                "+=" -> run("add", right())
                "-=" -> variableNumber(right())?.let { run("add", formatVariableNumber(-it)) }.orEmpty()
                "++" -> run("inc")
                "--" -> run("dec")
                "||", "||=", "??", "??=" -> {
                    val missing = if (operation.startsWith('|')) !truthy(current) else run("has") != "true"
                    if (!missing) current else right().also { if (operation.endsWith('=')) run("set", it) }
                }
                "==" -> (current == right()).toString()
                "!=" -> (current != right()).toString()
                else -> {
                    val target = variableNumber(right()) ?: return "false"
                    val existing = variableNumber(current) ?: return "false"
                    when (operation) {
                        ">" -> existing > target
                        ">=" -> existing >= target
                        "<" -> existing < target
                        else -> existing <= target
                    }.toString()
                }
            }
        }

        private fun arguments(token: Tag, depth: Int): List<String> {
            if (token.args == token.end) return emptyList()
            fun split(separator: String): List<IntRange> = buildList {
                var start = token.args
                var at = start
                while (at < token.end) {
                    val nested = ends[at]
                    when {
                        escaped(at, argument = true) -> at += 2
                        nested != null && nested <= token.end -> at = nested
                        text.startsWith(separator, at) -> {
                            add(start until at)
                            at += separator.length
                            start = at
                        }
                        else -> at++
                    }
                }
                add(start until token.end)
            }
            val parts = split("::")
            val ranges = if (parts.size == 1 && token.name in setOf("random", "pick")) split(",") else parts
            return ranges.map { render(it.first, it.last + 1, depth + 1, argument = true).trim() }
        }

        private fun value(token: Tag, position: Int, depth: Int): String? {
            val name = token.name
            if (name.isEmpty()) return null
            if (name == "//" || name == "comment" || name == "else" || name == "noop") return ""
            if (name == "variable") return variable(token.args, token.end, depth)
            val args = arguments(token, depth)
            val first = args.firstOrNull().orEmpty()
            return when (name) {
                "newline", "space" -> (if (name == "newline") "\n" else " ").repeat((first.toIntOrNull() ?: 1).coerceIn(0, 1_000))
                "reverse" -> first.reversed()
                "roll" -> macroDice(first, random)
                "random" -> args.randomOrNull(random).orEmpty()
                "pick" -> if (args.isEmpty()) "" else args[Random("$seed:$contentHash:$position".hashCode()).nextInt(args.size)]
                "isodate" -> now.toLocalDate().toString()
                "isotime" -> now.format(DateTimeFormatter.ofPattern("HH:mm"))
                "time" -> localTime(first)
                "date" -> now.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT))
                "weekday" -> now.format(DateTimeFormatter.ofPattern("EEEE"))
                else -> lookup(name, args)
            }
        }
    }

    private fun truthy(value: String) = value.trim().lowercase() !in falsyMacros

    private fun localTime(offset: String): String {
        val time = if (offset.isBlank()) now else {
            val match = utcOffset.matchEntire(offset) ?: return ""
            val (sign, hours, minutes) = match.destructured
            val direction = if (sign == "-") -1 else 1
            val zone = runCatching { ZoneOffset.ofHoursMinutes(hours.ifEmpty { "0" }.toInt() * direction,
                minutes.ifEmpty { "0" }.toInt() * direction) }.getOrNull() ?: return ""
            now.withZoneSameInstant(zone)
        }
        return time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    }
}
