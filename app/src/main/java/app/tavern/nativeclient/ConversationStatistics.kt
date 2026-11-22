package app.tavern.nativeclient

import java.text.BreakIterator
import java.util.Locale

internal data class ConversationStatistics(
    val chats: Int,
    val userMessages: Long,
    val assistantMessages: Long,
    val userWords: Long,
    val assistantWords: Long,
    val alternatives: Long,
    val firstMessage: Long?,
    val lastMessage: Long?,
    val generationMillis: Long?,
    val skippedChats: Int,
)

internal class ConversationStatisticsCollector {
    private val words = BreakIterator.getWordInstance(Locale.ROOT)
    private var chats = 0
    private var userMessages = 0L
    private var assistantMessages = 0L
    private var userWords = 0L
    private var assistantWords = 0L
    private var alternatives = 0L
    private var firstMessage: Long? = null
    private var lastMessage: Long? = null
    private var generationMillis: Long? = null

    fun add(chat: Chat, checkActive: () -> Unit = {}) {
        chats++
        chat.messages.forEachIndexed { index, message ->
            if (index % 128 == 0) checkActive()
            if (message.role != "assistant" && message.role != "user") return@forEachIndexed
            messageEpoch(message.date)?.let { timestamp ->
                firstMessage = minOf(firstMessage ?: timestamp, timestamp)
                lastMessage = maxOf(lastMessage ?: timestamp, timestamp)
            }
            val count = words.countWords(message.text)
            if (message.role == "user") {
                userMessages++
                userWords += count
            } else {
                assistantMessages++
                assistantWords += count
                alternatives += (message.variants.size - 1).coerceAtLeast(0)
                if (message.variants.isEmpty()) addElapsed(message.replyMetrics)
                else message.variants.indices.forEach { addElapsed(message.metricsAt(it)) }
            }
        }
    }

    private fun addElapsed(metrics: ReplyMetrics?) {
        metrics?.elapsed?.let { generationMillis = (generationMillis ?: 0) + it }
    }

    fun snapshot(skippedChats: Int = 0) = ConversationStatistics(chats, userMessages, assistantMessages, userWords,
        assistantWords, alternatives, firstMessage, lastMessage, generationMillis, skippedChats)
}

internal fun BreakIterator.countWords(text: String): Long {
    setText(text)
    var count = 0L
    var start = first()
    while (true) {
        val end = next()
        if (end == BreakIterator.DONE) return count
        if (end > start && Character.isLetterOrDigit(text.codePointAt(start))) count++
        start = end
    }
}
