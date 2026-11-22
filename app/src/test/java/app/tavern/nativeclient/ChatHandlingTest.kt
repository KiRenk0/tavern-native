package app.tavern.nativeclient

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHandlingTest {
    @Test
    fun `empty send continues eligible character replies and keeps other draft submissions separate`() {
        val reply = Message("assistant", "Rina", "The conversation continues.")
        val chat = Chat("chat", "rina", "Example", messages = listOf(reply))
        val settings = ChatHandling(continueOnSend = true)

        assertTrue(settings.shouldContinueOnSend(chat, "", hasAttachments = false))
        assertFalse(settings.shouldContinueOnSend(chat, "", hasAttachments = true))
        assertFalse(settings.shouldContinueOnSend(chat, "Next", hasAttachments = false))
        assertFalse(settings.shouldContinueOnSend(chat, " ", hasAttachments = false))
        assertFalse(settings.shouldContinueOnSend(chat.copy(groupId = "group"), "", hasAttachments = false))
        assertFalse(settings.shouldContinueOnSend(chat.copy(messages = listOf(reply.copy(hidden = true))), "", hasAttachments = false))
        assertFalse(settings.shouldContinueOnSend(chat.copy(messages = listOf(reply.copy(role = "user"))), "", hasAttachments = false))
    }

    @Test
    fun `keyboard and quick response options retain SillyTavern values and existing fallbacks`() {
        val source = JSONObject("""{
            "send_on_enter": 0,
            "continue_on_send": true,
            "quick_continue": true,
            "quick_impersonate": false,
            "auto_scroll_chat_to_bottom": false
        }""")
        val handling = ChatHandling.from(source)
        assertEquals(EnterToSend.HARDWARE, handling.enterToSend)
        assertTrue(handling.enterToSend.acceptsEnter(hardwareKeyboard = true))
        assertFalse(handling.enterToSend.acceptsEnter(hardwareKeyboard = false))
        assertEquals(handling, ChatHandling.from(handling.json()))
        assertEquals(handling, ChatHandling.from(JSONObject().put("send_on_enter", 9), handling))
        assertEquals(handling, decodePreferences(encodePreferences(Preferences(chatHandling = handling), { it }), { it }).chatHandling)
    }

    @Test
    fun `display preferences migrate bounded original values without changing existing defaults`() {
        val original = JSONObject().put("power_user", JSONObject()
            .put("chat_truncation", 1200).put("streaming_fps", 1)
            .put("smooth_streaming", true).put("smooth_streaming_speed", 135)
            .put("smooth_streaming_no_think", true).put("stream_fade_in", true).put("restore_user_input", true))
        val migrated = ChatHandling.fromOriginal(original)
        assertEquals(1000, migrated.historyPageSize)
        assertEquals(5, migrated.streamingFps)
        assertEquals(100, migrated.smoothStreamingSpeed)
        assertTrue(migrated.smoothStreaming && migrated.immediateReasoning && migrated.streamFadeIn && migrated.restoreDraft)
        assertEquals(migrated, ChatHandling.from(migrated.json()))
        assertEquals(0, ChatHandling.from(JSONObject()).historyPageSize)
        assertFalse(ChatHandling.from(JSONObject()).restoreDraft)
    }
}
