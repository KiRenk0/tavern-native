package app.tavern.nativeclient

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

internal sealed interface RichBlock {
    data class Heading(val level: Int, val text: String) : RichBlock
    data class CodeBlock(val language: String, val code: String) : RichBlock
    data class BlockQuote(val lines: List<String>) : RichBlock
    data class BulletList(val items: List<String>) : RichBlock
    data class OrderedList(val items: List<Pair<String, String>>) : RichBlock
    data object HorizontalRule : RichBlock
    data class MarkdownTable(val headers: List<String>, val rows: List<List<String>>) : RichBlock
    data class DetailsBlock(val summary: String, val body: String, val openByDefault: Boolean) : RichBlock
    data class HtmlCardBlock(val rawHtml: String, val label: String) : RichBlock
    data class Paragraph(val text: String) : RichBlock
}

private val headingPattern = Regex("""^(#{1,6})\s+(.+?)\s*#*\s*$""")
private val hrPattern = Regex("""^\s*(?:[-*_]\s*){3,}$""")
private val bulletPattern = Regex("""^\s*[-*+]\s+(.*)$""")
private val orderedPattern = Regex("""^\s*(\d+)[.)]\s+(.*)$""")
private val tableSeparatorPattern = Regex("""^\s*\|?\s*:?-{2,}:?\s*(?:\|\s*:?-{2,}:?\s*)*\|?\s*$""")
private val detailsOpenTag = Regex("""(?is)^\s*<details(\b[^>]*)?>""")
private val summaryTag = Regex("""(?is)<summary\b[^>]*>(.*?)</summary>""")
private val htmlBlockStarter = Regex("""(?is)^\s*<(style|div|section|article|table|fieldset|progress|meter|svg|center|header|footer|aside|nav)\b[^>]*>""")
private val voidHtmlTags = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")
private val htmlTitleCandidate = Regex("""(?is)<(?:h[1-4]|title|summary|caption)\b[^>]*>(.*?)</(?:h[1-4]|title|summary|caption)>""")
private val htmlTagStripper = Regex("""<[^>]+>""")

internal fun parseRichBlocks(text: String, renderHtml: Boolean): List<RichBlock> {
    val cleaned = app.tavern.nativeclient.script.MvuEngine.stripUpdateVariableBlocks(text)
    if (cleaned.isEmpty()) return emptyList()
    val normalized = cleaned.replace("\r\n", "\n").replace('\r', '\n')
    val blocks = mutableListOf<RichBlock>()
    val lines = normalized.split('\n')
    var index = 0

    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
            index++
            continue
        }

        // 1. Fenced code block (``` or ~~~)
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            val fence = if (trimmed.startsWith("```")) "```" else "~~~"
            val language = trimmed.removePrefix(fence).trim()
            val codeLines = mutableListOf<String>()
            index++
            while (index < lines.size && !lines[index].trim().startsWith(fence)) {
                codeLines += lines[index]
                index++
            }
            if (index < lines.size && lines[index].trim().startsWith(fence)) index++
            blocks += RichBlock.CodeBlock(language, codeLines.joinToString("\n"))
            continue
        }

        // 2. Native <details> collapsible block
        if (detailsOpenTag.containsMatchIn(trimmed)) {
            val parsedDetails = consumeDetailsBlock(lines, index)
            if (parsedDetails != null) {
                blocks += parsedDetails.first
                index = parsedDetails.second
                continue
            }
        }

        // 3. Rich HTML/CSS status card block
        if (renderHtml && htmlBlockStarter.containsMatchIn(trimmed)) {
            val consumed = consumeHtmlCardBlock(lines, index)
            if (consumed != null) {
                blocks += consumed.first
                index = consumed.second
                continue
            }
        }

        // 4. Heading
        val heading = headingPattern.matchEntire(trimmed)
        if (heading != null) {
            blocks += RichBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trim())
            index++
            continue
        }

        // 5. Horizontal rule
        if (hrPattern.matches(trimmed)) {
            blocks += RichBlock.HorizontalRule
            index++
            continue
        }

        // 6. Markdown table (header + separator row)
        if ('|' in line && index + 1 < lines.size && isTableSeparator(lines[index + 1])) {
            val headers = splitTableRow(line)
            val rows = mutableListOf<List<String>>()
            index += 2
            while (index < lines.size) {
                val rowLine = lines[index]
                if (rowLine.trim().isEmpty() || '|' !in rowLine) break
                rows += splitTableRow(rowLine)
                index++
            }
            blocks += RichBlock.MarkdownTable(headers, rows)
            continue
        }

        // 7. Blockquote
        if (trimmed.startsWith(">")) {
            val quoteLines = mutableListOf<String>()
            while (index < lines.size && lines[index].trim().startsWith(">")) {
                quoteLines += lines[index].trimStart().removePrefix(">").removePrefix(" ")
                index++
            }
            blocks += RichBlock.BlockQuote(quoteLines)
            continue
        }

        // 8. Bullet list
        val bullet = bulletPattern.matchEntire(line)
        if (bullet != null) {
            val items = mutableListOf<String>()
            while (index < lines.size) {
                val match = bulletPattern.matchEntire(lines[index]) ?: break
                items += match.groupValues[1].trim()
                index++
            }
            blocks += RichBlock.BulletList(items)
            continue
        }

        // 9. Ordered list
        val ordered = orderedPattern.matchEntire(line)
        if (ordered != null) {
            val items = mutableListOf<Pair<String, String>>()
            while (index < lines.size) {
                val match = orderedPattern.matchEntire(lines[index]) ?: break
                items += match.groupValues[1] to match.groupValues[2].trim()
                index++
            }
            blocks += RichBlock.OrderedList(items)
            continue
        }

        // 10. Paragraph
        val paragraphLines = mutableListOf<String>()
        while (index < lines.size) {
            val current = lines[index]
            val currentTrimmed = current.trim()
            if (currentTrimmed.isEmpty()) break
            if (currentTrimmed.startsWith("```") || currentTrimmed.startsWith("~~~")) break
            if (detailsOpenTag.containsMatchIn(currentTrimmed)) break
            if (renderHtml && htmlBlockStarter.containsMatchIn(currentTrimmed) && looksLikeHtmlCard(currentTrimmed)) break
            if (headingPattern.matches(currentTrimmed) || hrPattern.matches(currentTrimmed)) break
            if (currentTrimmed.startsWith(">")) break
            if (bulletPattern.matches(current) || orderedPattern.matches(current)) break
            if ('|' in current && index + 1 < lines.size && isTableSeparator(lines[index + 1])) break
            paragraphLines += current
            index++
        }
        if (paragraphLines.isNotEmpty()) {
            blocks += RichBlock.Paragraph(paragraphLines.joinToString("\n"))
        } else {
            // Failsafe against unexpected single-line stagnation
            blocks += RichBlock.Paragraph(line)
            index++
        }
    }

    return blocks
}

private fun isTableSeparator(line: String): Boolean {
    val trimmed = line.trim()
    return '-' in trimmed && '|' in trimmed && tableSeparatorPattern.matches(trimmed)
}

private fun splitTableRow(line: String): List<String> {
    var raw = line.trim()
    if (raw.startsWith("|")) raw = raw.drop(1)
    if (raw.endsWith("|") && !raw.endsWith("\\|")) raw = raw.dropLast(1)
    val cells = mutableListOf<String>()
    val current = StringBuilder()
    var i = 0
    while (i < raw.length) {
        if (raw[i] == '\\' && i + 1 < raw.length && raw[i + 1] == '|') {
            current.append('|')
            i += 2
        } else if (raw[i] == '|') {
            cells += current.toString().trim()
            current.clear()
            i++
        } else {
            current.append(raw[i])
            i++
        }
    }
    cells += current.toString().trim()
    return cells
}

private fun consumeDetailsBlock(lines: List<String>, startIndex: Int): Pair<RichBlock.DetailsBlock, Int>? {
    val collected = StringBuilder()
    var depth = 0
    var seenOpen = false
    var i = startIndex
    while (i < lines.size) {
        val line = lines[i]
        if (collected.isNotEmpty()) collected.append('\n')
        collected.append(line)
        val openMatches = Regex("(?is)<details\\b[^>]*>").findAll(line).count()
        val closeMatches = Regex("(?is)</details\\s*>").findAll(line).count()
        if (openMatches > 0) seenOpen = true
        depth += openMatches - closeMatches
        i++
        if (seenOpen && depth <= 0) break
    }
    val full = collected.toString()
    if (!seenOpen) return null
    val openMatch = detailsOpenTag.find(full) ?: return null
    val openAttrs = openMatch.groupValues[1]
    val openByDefault = Regex("(?i)\\bopen\\b").containsMatchIn(openAttrs)
    var inner = full.substring(openMatch.range.last + 1)
    val lastClose = inner.lastIndexOf("</details", ignoreCase = true)
    if (lastClose >= 0) inner = inner.substring(0, lastClose)
    val summaryMatch = summaryTag.find(inner)
    val summary = summaryMatch?.groupValues?.get(1)?.replace(htmlTagStripper, "")?.trim().orEmpty().ifBlank { "详细信息" }
    val body = if (summaryMatch != null) {
        (inner.substring(0, summaryMatch.range.first) + inner.substring(summaryMatch.range.last + 1)).trim()
    } else inner.trim()
    return RichBlock.DetailsBlock(summary, body, openByDefault) to i
}

private fun looksLikeHtmlCard(snippet: String): Boolean {
    val lower = snippet.lowercase()
    return "<style" in lower || "style=" in lower || "<table" in lower || "<progress" in lower ||
        "<meter" in lower || "<svg" in lower || "class=" in lower
}

private fun consumeHtmlCardBlock(lines: List<String>, startIndex: Int): Pair<RichBlock.HtmlCardBlock, Int>? {
    val parts = mutableListOf<String>()
    var i = startIndex

    // Consume leading <style>...</style> plus following container, or a single balanced HTML container
    var passes = 0
    while (i < lines.size && passes < 4) {
        val trimmed = lines[i].trim()
        if (trimmed.isEmpty()) {
            if (passes > 0 && parts.isNotEmpty()) break
            i++
            continue
        }
        val tagMatch = htmlBlockStarter.find(trimmed) ?: break
        val rootTag = tagMatch.groupValues[1].lowercase()
        val consumed = consumeBalancedTag(lines, i, rootTag)
        parts += consumed.first
        i = consumed.second
        passes++
        if (rootTag != "style") break
    }

    val rawHtml = parts.joinToString("\n").trim()
    if (rawHtml.isEmpty()) return null
    // Check if it looks like a substantive HTML card rather than a simple single-line tag
    if (!looksLikeHtmlCard(rawHtml) && '\n' !in rawHtml && rawHtml.count { it == '<' } < 4) return null
    val label = extractHtmlCardLabel(rawHtml)
    return RichBlock.HtmlCardBlock(rawHtml, label) to i
}

private fun consumeBalancedTag(lines: List<String>, startIndex: Int, tag: String): Pair<String, Int> {
    val builder = StringBuilder()
    var depth = 0
    var seenOpen = false
    val openRe = Regex("(?is)<$tag\\b([^>]*)>")
    val closeRe = Regex("(?is)</$tag\\s*>")
    var i = startIndex
    while (i < lines.size) {
        val line = lines[i]
        if (builder.isNotEmpty()) builder.append('\n')
        builder.append(line)
        val opens = openRe.findAll(line).count { m ->
            tag !in voidHtmlTags && !m.groupValues[1].trimEnd().endsWith("/")
        }
        val closes = closeRe.findAll(line).count()
        if (opens > 0) seenOpen = true
        depth += opens - closes
        i++
        if (seenOpen && depth <= 0) break
        if (!seenOpen && tag in voidHtmlTags) break
    }
    return builder.toString() to i
}

private fun extractHtmlCardLabel(rawHtml: String): String {
    val title = htmlTitleCandidate.find(rawHtml)?.groupValues?.get(1)
        ?.replace(htmlTagStripper, "")?.replace(Regex("\\s+"), " ")?.trim()
    if (!title.isNullOrBlank()) return title.take(32)
    return if ("<svg" in rawHtml.lowercase()) "矢量图形面板" else "状态面板"
}

private val scriptTagStrip = Regex("""(?is)<script\b[^>]*>[\s\S]*?</script\s*>|<script\b[^>]*>""")
private val dangerousContainerStrip = Regex("""(?is)<(?:iframe|object|embed|applet|form|meta|base|link)\b[^>]*>[\s\S]*?</(?:iframe|object|embed|applet|form)\s*>|<(?:iframe|object|embed|applet|form|meta|base|link)\b[^>]*/?>""")
private val eventHandlerStrip = Regex("""(?is)\s+on[a-z0-9_-]+\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+)""")
private val dangerousUrlAttr = Regex("""(?is)\b(src|href|action|poster|xlink:href)\s*=\s*(?:"\s*(?:javascript:|vbscript:|data:text/html|https?://)[^"]*"|'\s*(?:javascript:|vbscript:|data:text/html|https?://)[^']*'|[^\s>]*?(?:javascript:|vbscript:|https?://)[^\s>]*)""")
private val cssImportStrip = Regex("""(?is)@import\b[^;]*;?""")
private val cssExternalUrlStrip = Regex("""(?is)url\(\s*['"]?\s*(?:https?:|javascript:|vbscript:)[^)]*\)""")

internal fun sanitizeCardHtml(
    rawHtml: String,
    darkTheme: Boolean = false,
    bgHex: String = if (darkTheme) "#181E1A" else "#F7FAF5",
    surfaceHex: String = if (darkTheme) "#222B24" else "#EAF0E9",
    fgHex: String = if (darkTheme) "#E0E8E0" else "#181D19",
    mutedHex: String = if (darkTheme) "#BFCAC0" else "#404A43",
    accentHex: String = if (darkTheme) "#94D3B1" else "#28664B",
    borderHex: String = if (darkTheme) "#3F4A42" else "#BFCAC0",
): String {
    var cleaned = rawHtml
        .replace(scriptTagStrip, "")
        .replace(dangerousContainerStrip, "")
        .replace(eventHandlerStrip, "")
        .replace(dangerousUrlAttr) { m -> " ${m.groupValues[1]}=\"#blocked\"" }
        .replace(cssImportStrip, "")
        .replace(cssExternalUrlStrip, "none")

    return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
        <style>
        :root {
            --bg: $bgHex;
            --surface: $surfaceHex;
            --fg: $fgHex;
            --fg-muted: $mutedHex;
            --accent: $accentHex;
            --border: $borderHex;
            color-scheme: ${if (darkTheme) "dark" else "light"};
        }
        * { box-sizing: border-box; max-width: 100%; }
        html, body {
            margin: 0; padding: 0;
            background: transparent;
            color: var(--fg);
            font-family: sans-serif;
            font-size: 14px;
            line-height: 1.45;
            overflow-wrap: anywhere;
            word-break: break-word;
        }
        body { padding: 8px 10px; }
        table { width: 100%; border-collapse: collapse; margin: 4px 0; }
        th, td { border: 1px solid var(--border); padding: 4px 6px; text-align: left; }
        th { background: var(--surface); font-weight: 600; }
        progress, meter { width: 100%; accent-color: var(--accent); }
        hr { border: 0; border-top: 1px solid var(--border); margin: 6px 0; }
        </style>
        </head>
        <body>$cleaned</body>
        </html>
    """.trimIndent()
}

private val inlineTokenRegex = Regex(
    """(`[^`\n]+`|\[([^\]\n]+)\]\(([^)\s]+)\)|<br\s*/?>|<span\b([^>]*)>([\s\S]*?)</span>|<(b|strong|i|em|u|s|del|strike|code|mark)\b[^>]*>([\s\S]*?)</\6>|~~([^\n~]+?)~~|\*\*\*([^\n*]+?)\*\*\*|\*\*([^\n*]+?)\*\*|\*([^\s*\n](?:[^\n*]*?[^\s*\n])?)\*|(?<![\p{L}\p{N}_])__([^\n_]+?)__(?![\p{L}\p{N}_])|(?<![\p{L}\p{N}_])_([^\s_\n](?:[^\n_]*?[^\s_\n])?)_(?![\p{L}\p{N}_]))""",
    RegexOption.IGNORE_CASE,
)
private val colorAttrRegex = Regex("""(?i)(?:color\s*[:=]\s*['"]?)(#[0-9a-fA-F]{3,8}|[a-zA-Z]{3,20})""")

internal fun buildRichInline(
    text: String,
    highlightQuotes: Boolean,
    quoteColor: Color,
    codeBg: Color,
    linkColor: Color,
): AnnotatedString {
    val codeRanges = mutableListOf<IntRange>()
    val built = buildAnnotatedString {
        appendInlineTokens(text, codeRanges, codeBg, linkColor, depth = 0)
    }
    if (!highlightQuotes) return built
    val plain = built.text
    val quoteRanges = findDialogueQuoteRanges(plain, codeRanges)
    if (quoteRanges.isEmpty()) return built
    return buildAnnotatedString {
        append(built)
        val style = SpanStyle(color = quoteColor, fontWeight = FontWeight.Medium)
        quoteRanges.forEach { range ->
            if (range.first >= 0 && range.last < plain.length && range.first < range.last) {
                addStyle(style, range.first, range.last + 1)
            }
        }
    }
}

private fun AnnotatedString.Builder.appendInlineTokens(
    text: String,
    codeRanges: MutableList<IntRange>,
    codeBg: Color,
    linkColor: Color,
    depth: Int,
) {
    if (depth > 8 || text.isEmpty()) {
        append(text)
        return
    }
    var cursor = 0
    for (match in inlineTokenRegex.findAll(text)) {
        if (match.range.first > cursor) {
            append(text.substring(cursor, match.range.first))
        }
        val token = match.value
        when {
            token.startsWith("`") -> {
                val content = token.removeSurrounding("`")
                val start = length
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) {
                    append(content)
                }
                if (length > start) codeRanges += start until length
            }
            match.groupValues[2].isNotEmpty() -> {
                val label = match.groupValues[2]
                val url = match.groupValues[3]
                val safeUrl = url.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
                val style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                if (safeUrl != null) {
                    withLink(LinkAnnotation.Url(safeUrl)) {
                        withStyle(style) { appendInlineTokens(label, codeRanges, codeBg, linkColor, depth + 1) }
                    }
                } else {
                    withStyle(style) { appendInlineTokens(label, codeRanges, codeBg, linkColor, depth + 1) }
                }
            }
            token.startsWith("<br", ignoreCase = true) -> append('\n')
            match.groupValues[5].isNotEmpty() || token.startsWith("<span", ignoreCase = true) -> {
                val attrs = match.groupValues[4]
                val inner = match.groupValues[5]
                val parsedColor = parseCssColor(colorAttrRegex.find(attrs)?.groupValues?.get(1))
                if (parsedColor != null) {
                    withStyle(SpanStyle(color = parsedColor)) {
                        appendInlineTokens(inner, codeRanges, codeBg, linkColor, depth + 1)
                    }
                } else {
                    appendInlineTokens(inner, codeRanges, codeBg, linkColor, depth + 1)
                }
            }
            match.groupValues[6].isNotEmpty() -> {
                val tag = match.groupValues[6].lowercase()
                val inner = match.groupValues[7]
                when (tag) {
                    "b", "strong" -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        appendInlineTokens(inner, codeRanges, codeBg, linkColor, depth + 1)
                    }
                    "i", "em" -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        appendInlineTokens(inner, codeRanges, codeBg, linkColor, depth + 1)
                    }
                    "u" -> withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
                        appendInlineTokens(inner, codeRanges, codeBg, linkColor, depth + 1)
                    }
                    "s", "del", "strike" -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        appendInlineTokens(inner, codeRanges, codeBg, linkColor, depth + 1)
                    }
                    "code" -> {
                        val start = length
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(inner) }
                        if (length > start) codeRanges += start until length
                    }
                    "mark" -> withStyle(SpanStyle(background = codeBg, fontWeight = FontWeight.Medium)) {
                        appendInlineTokens(inner, codeRanges, codeBg, linkColor, depth + 1)
                    }
                    else -> appendInlineTokens(inner, codeRanges, codeBg, linkColor, depth + 1)
                }
            }
            match.groupValues[8].isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                appendInlineTokens(match.groupValues[8], codeRanges, codeBg, linkColor, depth + 1)
            }
            match.groupValues[9].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                appendInlineTokens(match.groupValues[9], codeRanges, codeBg, linkColor, depth + 1)
            }
            match.groupValues[10].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                appendInlineTokens(match.groupValues[10], codeRanges, codeBg, linkColor, depth + 1)
            }
            match.groupValues[11].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendInlineTokens(match.groupValues[11], codeRanges, codeBg, linkColor, depth + 1)
            }
            match.groupValues[12].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                appendInlineTokens(match.groupValues[12], codeRanges, codeBg, linkColor, depth + 1)
            }
            match.groupValues[13].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendInlineTokens(match.groupValues[13], codeRanges, codeBg, linkColor, depth + 1)
            }
            else -> append(token)
        }
        cursor = match.range.last + 1
    }
    if (cursor < text.length) {
        append(text.substring(cursor))
    }
}

private fun findDialogueQuoteRanges(text: String, codeRanges: List<IntRange>): List<IntRange> {
    fun inCode(pos: Int) = codeRanges.any { pos in it }
    val ranges = mutableListOf<IntRange>()
    var lineStart = 0
    while (lineStart <= text.length) {
        val nl = text.indexOf('\n', lineStart)
        val lineEnd = if (nl < 0) text.length else nl
        scanQuotesInLine(text, lineStart, lineEnd, ::inCode, ranges)
        if (nl < 0) break
        lineStart = nl + 1
    }
    return ranges
}

private fun scanQuotesInLine(
    text: String,
    start: Int,
    end: Int,
    inCode: (Int) -> Boolean,
    out: MutableList<IntRange>,
) {
    var straightOpen = -1
    var curlyOpen = -1
    var cornerOpen = -1
    var doubleCornerOpen = -1
    var i = start
    while (i < end) {
        if (inCode(i)) {
            i++
            continue
        }
        when (text[i]) {
            '"' -> {
                if (straightOpen < 0) straightOpen = i
                else {
                    if (i > straightOpen + 1) out += straightOpen..i
                    straightOpen = -1
                }
            }
            '“' -> if (curlyOpen < 0) curlyOpen = i
            '”' -> if (curlyOpen >= 0) {
                if (i > curlyOpen + 1) out += curlyOpen..i
                curlyOpen = -1
            }
            '「' -> if (cornerOpen < 0) cornerOpen = i
            '」' -> if (cornerOpen >= 0) {
                if (i > cornerOpen + 1) out += cornerOpen..i
                cornerOpen = -1
            }
            '『' -> if (doubleCornerOpen < 0) doubleCornerOpen = i
            '』' -> if (doubleCornerOpen >= 0) {
                if (i > doubleCornerOpen + 1) out += doubleCornerOpen..i
                doubleCornerOpen = -1
            }
        }
        i++
    }
}

private fun parseCssColor(raw: String?): Color? {
    val value = raw?.trim()?.lowercase() ?: return null
    if (value.startsWith("#")) {
        val hex = value.drop(1)
        return when (hex.length) {
            3 -> {
                val expanded = hex.map { "$it$it" }.joinToString("")
                expanded.toLongOrNull(16)?.let { Color((0xFF000000L or it).toInt()) }
            }
            6 -> hex.toLongOrNull(16)?.let { Color((0xFF000000L or it).toInt()) }
            8 -> hex.toLongOrNull(16)?.let { Color(it.toInt()) }
            else -> null
        }
    }
    return when (value) {
        "red" -> Color(0xFFD32F2F)
        "blue" -> Color(0xFF1976D2)
        "green" -> Color(0xFF388E3C)
        "orange" -> Color(0xFFF57C00)
        "purple" -> Color(0xFF7B1FA2)
        "gold" -> Color(0xFFFFB300)
        "gray", "grey" -> Color(0xFF757575)
        else -> null
    }
}
