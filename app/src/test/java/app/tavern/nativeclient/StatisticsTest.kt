package app.tavern.nativeclient

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StatisticsTest {
    @Test
    fun restoresLegacySwipeMetricsAndSwitchesOrDeletesWithoutLeakingThem() {
        val row = JSONObject("""
            {"name":"Guide","mes":"first draft","send_date":"November 21, 2026 08:30",
             "gen_started":"2026-11-21T00:30:00Z","gen_finished":"2026-11-21T00:30:02Z",
             "extra":{"model":"first-model","api":"openai","token_count":3,"time_to_first_token":900},
             "swipe_id":0,"swipes":["first draft","next draft","manual"],"swipe_info":[{},
               {"gen_started":"2026-11-21T00:32:00Z","gen_finished":"2026-11-21T00:32:04Z",
                "extra":{"model":"next-model","api":"gemini","token_count":4,"reasoning":"example"}},{}]}
        """.trimIndent())
        val original = Message.from(row)
        assertNotNull(messageEpoch(original.date))
        assertEquals(2000L, original.replyMetrics?.elapsed)
        assertEquals(900L, original.replyMetrics?.firstText)
        val next = Message.from(original.select(1).toJson())
        assertEquals("next-model", next.replyMetrics?.model)
        assertEquals(4000L, next.replyMetrics?.elapsed)
        assertEquals("example", next.reasoning)
        assertEquals(2000L, next.select(0).replyMetrics?.elapsed)
        assertNull(Message.from(next.select(2).toJson()).replyMetrics)
        val removed = Message.from(next.withoutAlternative(0).toJson())
        assertEquals("next-model", removed.replyMetrics?.model)
        assertNull(removed.withoutAlternative(0).replyMetrics)
        val edited = next.withText("revised words")
        assertEquals(4000L, edited.replyMetrics?.elapsed)
        assertNull(Message.from(edited.toJson()).replyMetrics?.bodyTokens)
    }

    @Test
    fun accumulatesContinuationsAndPreservesTimingInOriginalFields() {
        val original = ReplyMetrics(started = 1_790_000_000_000L, finished = 1_790_000_002_000L, elapsed = 2000L,
            firstText = 600L, model = "first", usage = ResponseUsage().record(ResponseTokens(50, 7)))
        val continuation = original.continued(ReplyMetrics(
            started = 1_790_100_000_000L, finished = 1_790_100_003_000L, elapsed = 3000L,
            firstText = 1000L, model = "second", bodyTokens = 29L, counter = "本机估算",
            usage = ResponseUsage().record(ResponseTokens()).record(ResponseTokens(91, 19, 4)),
        ))
        val reply = Message("assistant", "Guide", "joined reply", replyMetrics = continuation)
        val row = reply.toJson()
        assertEquals(5000L, messageEpoch(row.opt("gen_finished"))!! - messageEpoch(row.opt("gen_started"))!!)
        val restored = Message.from(row).replyMetrics!!
        assertEquals(continuation, restored)
        assertEquals(600L, restored.firstText)
        assertEquals(ResponseUsage(ResponseTokens(141, 26, 4), 3, 2), restored.usage)
        assertEquals("second", restored.model)
        assertEquals(29L, restored.bodyTokens)
    }

    @Test
    fun statisticsCountSavedTextAndAlternativeTimingOnce() {
        val start = "2026-11-21T00:30:00Z"
        val finish = "2026-11-21T00:35:00Z"
        val user = Message("user", "Player", "Where are we?", start)
        val assistant = Message("assistant", "Guide", "By the river.", finish, hidden = true,
            replyMetrics = ReplyMetrics(elapsed = 4000)).withAlternative(
            "On a hill.", metrics = ReplyMetrics(elapsed = 2000))
        val irrelevant = Message("system", "System", "tool invocation omitted", "2026-11-21T23:50:00Z")
        val solo = Chat("solo", "guide", "Guide", messages = listOf(user, assistant, irrelevant))
        val group = Chat("group", null, "Group", groupId = "friends",
            messages = listOf(Message("assistant", "Someone", "Welcome.", finish)))
        val collector = ConversationStatisticsCollector().apply { add(solo); add(group) }
        assertEquals(ConversationStatistics(2, 1, 2, 3, 4, 1,
            messageEpoch(start), messageEpoch(finish), 6000L, 1), collector.snapshot(skippedChats = 1))
        assertNull(ConversationStatisticsCollector().snapshot().generationMillis)
    }

    @Test
    fun appearanceMigratesOriginalTimingAndTokenPreferences() {
        val appearance = ChatAppearance.fromOriginal(jsonOf("timestamps_enabled" to true,
            "timer_enabled" to true, "message_token_count_enabled" to true))
        assertTrue(appearance.showMessageTime && appearance.showGenerationTimer && appearance.showMessageTokens)
        assertEquals(appearance, ChatAppearance.from(appearance.json()))
        assertEquals(ChatAppearance(), ChatAppearance.fromOriginal(null))
    }
}
