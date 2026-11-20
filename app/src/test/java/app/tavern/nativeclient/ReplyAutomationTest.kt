package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyAutomationTest {

    @Test
    fun `needsSwipe disables minimum character check when zero and trims whitespace`() {
        val disabledMin = ReplyAutomation(autoSwipe = true, minimumCharacters = 0)
        assertFalse(disabledMin.needsSwipe("   \n  "))
        assertFalse(disabledMin.needsSwipe("ok"))

        val withMin = ReplyAutomation(autoSwipe = true, minimumCharacters = 5)
        assertTrue(withMin.needsSwipe("   hi   "))
        assertTrue(withMin.needsSwipe("    \n\t   "))
        assertFalse(withMin.needsSwipe("12345"))
        // CJK & emoji surrogate pairs count by codePoints
        assertTrue(withMin.needsSwipe("😀😀😀😀"))
        assertFalse(withMin.needsSwipe("你好呀世界"))
    }

    @Test
    fun `needsSwipe handles latin boundaries punctuation tokens and cjk words`() {
        val config = ReplyAutomation(
            autoSwipe = true,
            minimumCharacters = 0,
            blockedWords = listOf("bot", "[OOC]", "作为AI", "{system}"),
            blockedWordThreshold = 2,
        )
        // 'robot' and 'bottom' must NOT match word 'bot'
        assertFalse(config.needsSwipe("The robot reached the bottom of the hill [OOC]."))
        // 'bot' + '[OOC]' = 2 hits -> triggers swipe
        assertTrue(config.needsSwipe("I am a BOT [ooc]."))
        // Punctuation/brace words and CJK unspaced words
        assertTrue(config.needsSwipe("我作为AI非常抱歉，请查看{system}提示"))
        // Threshold 0 disables blocked words
        assertFalse(config.copy(blockedWordThreshold = 0).needsSwipe("bot [OOC] 作为AI {system}"))
        // Master toggle off
        assertFalse(config.copy(autoSwipe = false).needsSwipe("a"))
    }

    @Test
    fun `needsContinuation allows short cjk chunks while stopping on blank chunks`() {
        val config = ReplyAutomation(autoContinue = true, targetTokens = 50)
        // Short CJK continuation (~3 chars >= 2 estimated tokens) should continue if total < target
        assertTrue(config.needsContinuation("这是第一段话。", "继续写", estimatedTokens))
        // Blank/whitespace chunk terminates continuation
        assertFalse(config.needsContinuation("这是第一段话。", "   \n  ", estimatedTokens))
        // Short single Latin punctuation shouldn't continue infinitely
        assertFalse(config.needsContinuation("Hello", ".", estimatedTokens))
        // Once target token count is reached, stop continuing
        val longText = "这是一段很长很长的中文叙事文本，用来测试达到目标令牌数量之后是否会停止自动续写。".repeat(5)
        assertFalse(config.needsContinuation(longText, "继续输出更多内容", estimatedTokens))
        // Disabled or target 0
        assertFalse(config.copy(autoContinue = false).needsContinuation("short", "more text here", estimatedTokens))
        assertFalse(config.copy(targetTokens = 0).needsContinuation("short", "more text here", estimatedTokens))
    }

    @Test
    fun `json serialization round-trip clamps out-of-range fields`() {
        val raw = JSONObject().apply {
            put("auto_continue", JSONObject().put("enabled", true).put("allow_chat_completions", true).put("target_length", 5000))
            put("auto_swipe", true)
            put("auto_swipe_minimum_length", 9999)
            put("auto_swipe_blacklist", JSONArray(listOf(" Alpha ", "alpha", "", "Beta")))
            put("auto_swipe_blacklist_threshold", 500)
            put("tavern_native_auto_reply_limit", 99)
        }
        val parsed = ReplyAutomation.from(raw)
        assertTrue(parsed.autoContinue)
        assertEquals(1024, parsed.targetTokens)
        assertTrue(parsed.autoSwipe)
        assertEquals(4000, parsed.minimumCharacters)
        assertEquals(listOf("Alpha", "Beta"), parsed.blockedWords)
        assertEquals(100, parsed.blockedWordThreshold)
        assertEquals(5, parsed.extraRequests)

        val roundTrip = ReplyAutomation.from(parsed.json())
        assertEquals(parsed, roundTrip)
    }
}
