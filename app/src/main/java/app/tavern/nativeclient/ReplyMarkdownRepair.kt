package app.tavern.nativeclient

private val emphasis = Regex("""(?<![\\_*])(\*{1,3}|(?<![\p{L}\p{N}])_{1,3})(?![*_])([^\r\n]+?)(?<!\\)\1(?![*_])""")
private val markup = Regex("""</?[A-Za-z][^>]*>""")
private val fence = Regex("""^`{3,}|^~{3,}""")
private val listMarker = Regex("""^\*[ \t]+""")
private val rule = Regex("""^(?:\*[ \t]*){3,}$""")
private val unescapedStar = Regex("""(?<!\\)\*""")
private val unescapedQuote = Regex("""(?<!\\)""" + "\"")

internal fun repairReplyMarkdown(text: String, forDisplay: Boolean = false): String {
    if ('*' !in text && '_' !in text && (!forDisplay || '"' !in text)) return text
    var openFence: String? = null
    return text.lineSequence().joinToString("\n") { line ->
        val start = line.trimStart()
        val marker = fence.find(start)?.value
        val active = openFence
        if (active != null) {
            if (marker != null && marker.first() == active.first() && marker.length >= active.length &&
                start.drop(marker.length).isBlank()) openFence = null
            return@joinToString line
        }
        if (marker != null) {
            openFence = marker
            return@joinToString line
        }
        if ('`' in line || markup.containsMatchIn(line) || rule.matches(start)) return@joinToString line

        val fixed = emphasis.replace(line) { match ->
            val (delimiter, body) = match.destructured
            val trimmed = body.trim()
            if (trimmed.isEmpty()) match.value else delimiter + trimmed + delimiter
        }
        if (!forDisplay) return@joinToString fixed
        val body = fixed.trimStart().let { listMarker.replaceFirst(it, "") }
        val closeStar = unescapedStar.findAll(body).count() % 2 != 0
        val closeQuote = unescapedQuote.findAll(body).count() % 2 != 0
        if (!closeStar && !closeQuote) fixed else buildString {
            append(fixed.trimEnd())
            if (closeStar) append('*')
            if (closeQuote) append('"')
        }
    }
}
