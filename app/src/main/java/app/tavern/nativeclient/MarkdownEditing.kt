package app.tavern.nativeclient

import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

internal fun markdownKey(event: KeyEvent): String? {
    if (!event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return null
    if (event.isShiftPressed) return "~~".takeIf { event.key == Key.Grave }
    return when (event.key) {
        Key.B -> "**"
        Key.I -> "*"
        Key.U -> "__"
        Key.K -> "`"
        else -> null
    }
}

internal fun TextFieldValue.toggleMarkdown(marker: String): TextFieldValue {
    var start = selection.min
    var end = selection.max
    if (start == end) {
        while (start > 0 && text[start - 1].isLetterOrDigit()) start--
        while (end < text.length && text[end].isLetterOrDigit()) end++
    }
    val selected = text.substring(start, end)
    val left = start - marker.length
    val right = end + marker.length
    val enclosed = left >= 0 && right <= text.length && text.regionMatches(left, marker, 0, marker.length) &&
        text.regionMatches(end, marker, 0, marker.length)
    return when {
        enclosed -> copy(text = text.replaceRange(left, right, selected), selection = TextRange(left, left + selected.length))
        selected.length >= marker.length * 2 && selected.startsWith(marker) && selected.endsWith(marker) -> {
            val inner = selected.removeSurrounding(marker)
            copy(text = text.replaceRange(start, end, inner), selection = TextRange(start, start + inner.length))
        }
        else -> {
            val opening = start + marker.length
            copy(text = text.replaceRange(start, end, "$marker$selected$marker"),
                selection = TextRange(opening, opening + selected.length))
        }
    }
}
