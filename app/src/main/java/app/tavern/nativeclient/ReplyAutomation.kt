package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject
import java.lang.Character.UnicodeScript
import java.util.Locale

internal data class ReplyAutomation(
    val autoContinue: Boolean = false,
    val targetTokens: Int = 400,
    val autoSwipe: Boolean = false,
    val minimumCharacters: Int = 0,
    val blockedWords: List<String> = emptyList(),
    val blockedWordThreshold: Int = 2,
    val extraRequests: Int = 3,
) {
    private val blockedPattern by lazy {
        blockedWords.sortedByDescending(String::length).takeIf { it.isNotEmpty() }?.joinToString("|") { word ->
            val literal = escapeLiteral(word)
            val dense = word.codePoints().anyMatch { UnicodeScript.of(it) in unspacedScripts }
            if (dense) literal else {
                val first = word.codePointAt(0)
                val last = word.codePointBefore(word.length)
                val prefix = if (isWordCodePoint(first)) "(?<![\\p{L}\\p{N}_])" else ""
                val suffix = if (isWordCodePoint(last)) "(?![\\p{L}\\p{N}_])" else ""
                "$prefix$literal$suffix"
            }
        }?.let { Regex(it, RegexOption.IGNORE_CASE) }
    }

    fun needsSwipe(text: String): Boolean {
        if (!autoSwipe) return false
        val trimmed = text.trim()
        if (minimumCharacters > 0 && trimmed.codePointCount(0, trimmed.length) < minimumCharacters) return true
        return blockedWordThreshold > 0 &&
            blockedPattern?.findAll(text)?.take(blockedWordThreshold)?.count() == blockedWordThreshold
    }

    fun needsContinuation(text: String, chunk: String, counter: TextTokenCounter): Boolean {
        if (!autoContinue || targetTokens <= 0) return false
        val trimmedChunk = chunk.trim()
        if (trimmedChunk.isEmpty()) return false
        if (trimmedChunk.length <= 5 && counter.count(trimmedChunk) < 2) return false
        return counter.count(text) < targetTokens
    }

    fun json() = JSONObject().also(::applyTo)

    fun applyTo(profile: JSONObject) {
        profile.put("auto_continue", jsonOf("enabled" to autoContinue, "allow_chat_completions" to autoContinue,
            "target_length" to targetTokens))
        profile.put("auto_swipe", autoSwipe)
        profile.put("auto_swipe_minimum_length", minimumCharacters)
        profile.put("auto_swipe_blacklist", JSONArray(blockedWords))
        profile.put("auto_swipe_blacklist_threshold", blockedWordThreshold)
        profile.put("tavern_native_auto_reply_limit", extraRequests)
    }

    companion object {
        private val unspacedScripts = setOf(UnicodeScript.HAN, UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA,
            UnicodeScript.THAI, UnicodeScript.KHMER, UnicodeScript.MYANMAR, UnicodeScript.LAO)

        private fun isWordCodePoint(codePoint: Int): Boolean =
            Character.isLetterOrDigit(codePoint) || codePoint == '_'.code

        private fun escapeLiteral(word: String): String = buildString(word.length) {
            for (ch in word) {
                if (ch in "\\.^$*+?{}[]/|()-") append('\\')
                append(ch)
            }
        }

        fun words(entries: List<String>): List<String> = entries.asSequence().map(String::trim)
            .filter { it.isNotEmpty() && it.length <= 100 }.distinctBy { it.lowercase(Locale.ROOT) }.take(100).toList()

        fun from(profile: JSONObject?): ReplyAutomation {
            profile ?: return ReplyAutomation()
            val continuation = profile.optJSONObject("auto_continue")
            return ReplyAutomation(
                autoContinue = continuation?.let { it.optBoolean("enabled") && it.optBoolean("allow_chat_completions") } == true,
                targetTokens = (continuation?.optInt("target_length", 400) ?: 400).coerceIn(0, 1024),
                autoSwipe = profile.optBoolean("auto_swipe"),
                minimumCharacters = profile.optInt("auto_swipe_minimum_length").coerceIn(0, 4000),
                blockedWords = words(profile.optJSONArray("auto_swipe_blacklist").strings()),
                blockedWordThreshold = profile.optInt("auto_swipe_blacklist_threshold", 2).coerceIn(0, 100),
                extraRequests = profile.optInt("tavern_native_auto_reply_limit", 3).coerceIn(1, 5),
            )
        }
    }
}
