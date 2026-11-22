package app.tavern.nativeclient

import android.util.JsonReader
import android.util.JsonToken
import java.io.Reader

internal data class ChatArchiveScope(val cardId: String? = null, val groupId: String? = null)
internal data class ChatArchiveCursor(val updated: Long, val chatId: String)
internal data class ChatArchiveJump(val chatId: String, val messageIndex: Int)
internal data class ChatArchiveMatch(val index: Int, val speaker: String, val preview: String)
internal data class ChatArchiveHit(
    val chat: ChatSummary, val cardId: String?, val groupId: String?, val match: ChatArchiveMatch? = null,
)
internal data class ChatArchivePage(val hits: List<ChatArchiveHit>, val next: ChatArchiveCursor? = null)

internal val archiveWhitespace = Regex("[\\s\\p{Z}]+")
private const val ARCHIVE_PREVIEW_LENGTH = 180

internal class ChatArchiveQuery(input: String) {
    val terms = input.trim().split(archiveWhitespace).filter(String::isNotEmpty).distinctBy { it.lowercase() }

    fun matchesTitle(title: String) = terms.all { title.contains(it, ignoreCase = true) }

    fun match(messages: List<Message>): ChatArchiveMatch? {
        val scanner = ChatArchiveMatcher(this)
        for ((index, message) in messages.withIndex()) {
            if (scanner.scan(index, message.name, listOf(message.text, message.displayText))) break
        }
        return scanner.result()
    }
}

internal class ChatArchiveMatcher(private val query: ChatArchiveQuery) {
    private val found = BooleanArray(query.terms.size)
    private var remaining = found.size
    private var bestCount = 0
    private var best: ChatArchiveMatch? = null

    fun scan(index: Int, speaker: String, texts: List<String>): Boolean {
        for (text in texts.distinct()) {
            var count = 0
            for ((i, term) in query.terms.withIndex()) {
                if (!text.contains(term, ignoreCase = true)) continue
                count++
                if (!found[i]) {
                    found[i] = true
                    remaining--
                }
            }
            if (count > bestCount) {
                bestCount = count
                best = ChatArchiveMatch(index, speaker, archivePreview(text, query.terms))
            }
            if (count > 0 && count == found.size) return true
        }
        return false
    }

    fun result(): ChatArchiveMatch? = best.takeIf { remaining == 0 }
}

internal fun scanChatArchive(query: ChatArchiveQuery, input: Reader): ChatArchiveMatch? {
    val matcher = ChatArchiveMatcher(query)
    JsonReader(input).use { reader ->
        reader.beginObject()
        while (reader.hasNext()) {
            if (reader.nextName() != "messages" || reader.peek() != JsonToken.BEGIN_ARRAY) {
                reader.skipValue()
                continue
            }
            reader.beginArray()
            var index = 0
            while (reader.hasNext()) {
                if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                    reader.beginObject()
                    var speaker = ""
                    val texts = mutableListOf<String>()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "name" -> speaker = reader.archiveString()
                            "mes" -> texts += reader.archiveString()
                            "extra" -> if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                                reader.beginObject()
                                while (reader.hasNext()) {
                                    when (reader.nextName()) {
                                        "display_text", "tavern_native_original_input" -> texts += reader.archiveString()
                                        else -> reader.skipValue()
                                    }
                                }
                                reader.endObject()
                            } else reader.skipValue()
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                    if (matcher.scan(index++, speaker, texts)) return matcher.result()
                } else reader.skipValue()
            }
            return matcher.result()
        }
    }
    return null
}

private fun JsonReader.archiveString(): String =
    if (peek() == JsonToken.STRING) nextString() else "".also { skipValue() }

private fun archivePreview(value: String, terms: List<String>): String {
    val text = value.replace(archiveWhitespace, " ").trim()
    val first = terms.map { text.indexOf(it, ignoreCase = true) }.filter { it >= 0 }.minOrNull() ?: 0
    var start = (first - 48).coerceIn(0, (text.length - ARCHIVE_PREVIEW_LENGTH).coerceAtLeast(0))
    if (start > 0 && Character.isLowSurrogate(text[start])) start--
    var end = minOf(text.length, start + ARCHIVE_PREVIEW_LENGTH)
    if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
    return (if (start > 0) "…" else "") + text.substring(start, end).trim() + if (end < text.length) "…" else ""
}
