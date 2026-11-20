package app.tavern.nativeclient

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RegexEngineTest {
    private val card = Card.from(jsonOf("name" to "Lyra", "description" to "Archivist"), id = "card-lyra")

    @Test
    fun scopesStagesAndDepthLimitsWork() {
        val globalRule = RegexRule(
            id = "g1",
            name = "Replace greeting",
            pattern = "/\\bhello\\b/gi",
            replacement = "greetings",
            sites = listOf(RegexSite.REPLY),
            displayOnly = true,
            minDepth = 0,
            maxDepth = 1,
        )
        val preset = PromptPreset(
            id = "p1",
            name = "Preset",
            regexRules = listOf(
                RegexRule(
                    id = "p-rule",
                    name = "Strip tag",
                    pattern = "/<think>[\\s\\S]*?<\\/think>/gi",
                    replacement = "",
                    sites = listOf(RegexSite.REPLY),
                    requestOnly = true,
                ),
            ),
        )
        val cardWithRule = card.withRegexRules(
            listOf(
                RegexRule(
                    id = "c1",
                    name = "Macro escape",
                    pattern = "/\\b{{char}}\\b/",
                    replacement = "Sage {{char}}",
                    sites = listOf(RegexSite.REPLY),
                    macros = RegexMacros.ESCAPED,
                ),
            ),
        )
        val config = RegexConfig(
            enabled = true,
            global = listOf(globalRule),
            allowedCards = setOf(card.id),
            allowedPresets = setOf(preset.id),
        )
        val runtime = config.runtime(cardWithRule, "Traveler", preset)

        // Display rule applies at depth 1, skipped at depth 2
        assertEquals("greetings", runtime.transform("Hello", RegexSite.REPLY, RegexStage.DISPLAY, depth = 1))
        assertEquals("Hello", runtime.transform("Hello", RegexSite.REPLY, RegexStage.DISPLAY, depth = 2))

        // Request rule strips <think> only at REQUEST stage
        assertEquals("Answer", runtime.transform("<think>plan</think>Answer", RegexSite.REPLY, RegexStage.REQUEST, depth = 0))
        assertEquals("<think>plan</think>Answer", runtime.transform("<think>plan</think>Answer", RegexSite.REPLY, RegexStage.DISPLAY, depth = 0))

        // Card storage rule expands {{char}} safely
        assertEquals("Sage Lyra arrives", runtime.transform("Lyra arrives", RegexSite.REPLY, RegexStage.STORAGE, depth = 0))
    }

    @Test
    fun captureGroupsNamedGroupsTrimsAndKeywordMatchesWork() {
        // Warm Joni/JCodings classloading once outside the 150ms per-rule runtime budget.
        regexKeywordMatches("/warmup/", "warmup")

        val rule = RegexRule(
            pattern = "/(?<who>\\w+):\\s*\"([^\"]+)\"/g",
            replacement = "$<who>=[$2] ({{match}})",
            trims = listOf("\""),
            sites = listOf(RegexSite.REPLY),
        )
        val result = regexTest(rule, "Alice: \"hello world\"", RegexNames(card, "Bob"))
        assertNull(result.issue)
        assertTrue(result.text.startsWith("Alice=[hello world]"))

        assertEquals(true, regexKeywordMatches("/\\bdragon\\b/i", "A Dragon appears"))
        assertEquals(false, regexKeywordMatches("/^dragon$/", "A dragon appears"))
        assertNull(regexKeywordMatches("plainKeyword", "plainKeyword"))
    }

    @Test
    fun androidIcuRegexAuditAllMainSourceLiteralsValid() {
        val javaRoot = listOf(
            File("app/src/main/java"),
            File("src/main/java"),
        ).firstOrNull(File::isDirectory)
            ?: error("Could not locate app/src/main/java from ${File(".").absolutePath}")

        val ktFiles = javaRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("Expected Kotlin source files in $javaRoot", ktFiles.isNotEmpty())

        val violations = mutableListOf<String>()
        var checkedCount = 0

        for (file in ktFiles) {
            val content = file.readText()
            val literals = extractRegexLiterals(content)
            for ((line, pattern) in literals) {
                checkedCount++
                try {
                    Regex(pattern)
                } catch (e: Exception) {
                    violations += "${file.name}:$line failed Regex compilation ($pattern): ${e.message}"
                    continue
                }
                findUnescapedIcuBraces(pattern)?.let { detail ->
                    violations += "${file.name}:$line ICU bare brace risk ($detail) in: $pattern"
                }
            }
        }

        assertTrue("Expected to audit multiple Regex literals, checked $checkedCount", checkedCount >= 10)
        assertTrue("Regex audit violations:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    private fun extractRegexLiterals(source: String): List<Pair<Int, String>> {
        val found = mutableListOf<Pair<Int, String>>()
        val callPattern = Regex("""\bRegex\s*\(""")
        var pos = 0
        while (pos < source.length) {
            val match = callPattern.find(source, pos) ?: break
            var i = match.range.last + 1
            while (i < source.length && source[i].isWhitespace()) i++
            val line = source.substring(0, match.range.first).count { it == '\n' } + 1
            if (source.startsWith("\"\"\"", i)) {
                val start = i + 3
                val end = source.indexOf("\"\"\"", start)
                if (end > start) {
                    val body = source.substring(start, end)
                    if (!containsKotlinInterpolation(body)) {
                        found += line to body
                    }
                    pos = end + 3
                    continue
                }
            } else if (i < source.length && source[i] == '"') {
                val (literal, nextPos) = readQuotedString(source, i + 1)
                if (literal != null && !containsKotlinInterpolation(literal)) {
                    found += line to literal
                }
                pos = nextPos
                continue
            }
            pos = match.range.last + 1
        }
        return found
    }

    private fun containsKotlinInterpolation(raw: String): Boolean {
        var i = 0
        while (i < raw.length) {
            if (raw[i] == '$' && i + 1 < raw.length) {
                val next = raw[i + 1]
                if (next == '{' || next.isLetter() || next == '_') return true
            }
            i++
        }
        return false
    }

    private fun readQuotedString(source: String, start: Int): Pair<String?, Int> {
        val sb = StringBuilder()
        var i = start
        while (i < source.length) {
            val ch = source[i]
            if (ch == '\\' && i + 1 < source.length) {
                val esc = source[i + 1]
                sb.append(
                    when (esc) {
                        '\\' -> '\\'
                        '"' -> '"'
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        '$' -> '$'
                        else -> esc
                    },
                )
                i += 2
            } else if (ch == '"') {
                return sb.toString() to (i + 1)
            } else {
                sb.append(ch)
                i++
            }
        }
        return null to start
    }

    /**
     * Android's ICU regex engine rejects literal `{` or `}` outside character classes `[...]`,
     * valid numeric quantifiers `{n}` / `{n,}` / `{n,m}` attached to a repeatable atom,
     * and `\p{...}` / `\P{...}` / `\x{...}` / `\u{...}` / `\N{...}` escapes.
     */
    private fun findUnescapedIcuBraces(pattern: String): String? {
        val quantifier = Regex("""\{\d+(?:,\d*)?\}""")
        val propEscape = Regex("""\\[pPxuN]\{[^}]+\}""")
        var inClass = false
        var i = 0
        while (i < pattern.length) {
            val ch = pattern[i]
            if (ch == '\\' && i + 1 < pattern.length) {
                val next = pattern[i + 1]
                if (next in "pPxuN" && i + 2 < pattern.length && pattern[i + 2] == '{') {
                    val m = propEscape.matchAt(pattern, i)
                    if (m != null) {
                        i = m.range.last + 1
                        continue
                    }
                }
                i += 2
                continue
            }
            if (ch == '[' && !inClass) {
                inClass = true
                i++
                continue
            }
            if (ch == ']' && inClass) {
                inClass = false
                i++
                continue
            }
            if (!inClass && ch == '{') {
                val q = quantifier.matchAt(pattern, i)
                val validQuantifier = q != null && i > 0 && pattern[i - 1] !in "(|^"
                if (validQuantifier) {
                    i = q.range.last + 1
                    continue
                }
                return "bare '{' at index $i"
            }
            if (!inClass && ch == '}') {
                return "bare '}' at index $i"
            }
            i++
        }
        return null
    }
}
