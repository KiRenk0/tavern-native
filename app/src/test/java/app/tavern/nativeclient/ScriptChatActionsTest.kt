package app.tavern.nativeclient

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScriptChatActionsTest {
    @Test
    fun chatArchiveCommandsReadTheCurrentTitleAndPassTheActiveChatToEachAction() = runBlocking {
        val saved = Chat("group-chat", null, "港口", groupId = "group-1",
            messages = listOf(Message("user", "旅人", "打开酒馆的门")))
        var active: Chat? = saved
        val calls = mutableListOf<Triple<ScriptChatAction, String, String>>()
        val host = TavernScriptHost(snapshot = { TavernState(chat = active) }, input = "草稿", saveChat = {},
            savePrefs = {}, ask = { null }, trigger = { _, _ -> }, quiet = { _, _ -> "" }, draft = {},
            saveVariables = { _, _ -> }, notice = {}, chatAction = { action, chat, value ->
                calls += Triple(action, chat.id, value)
                when (action) {
                    ScriptChatAction.RENAME -> active = chat.copy(title = value)
                    ScriptChatAction.RELOAD -> assertEquals(saved.messages, chat.messages)
                    ScriptChatAction.CLOSE -> active = null
                    ScriptChatAction.JUMP -> assertEquals(ChatArchiveJump(chat.id, 0), scriptChatJump(chat, value))
                }
            })
        val script = StScript(host)
        assertEquals("港口", script.run("/getchatname | /pass {{pipe}}"))
        assertEquals("夜晚的港口", script.run("/renamechat 夜晚的港口 | /getchatname"))
        assertEquals("", script.run("/chat-reload | /chat-jump 0 | /chat-scrollto 0 | /floor-teleport 0"))
        assertEquals("", script.run("/closechat | /getchatname"))
        assertEquals(listOf(
            Triple(ScriptChatAction.RENAME, saved.id, "夜晚的港口"),
            Triple(ScriptChatAction.RELOAD, saved.id, ""),
            Triple(ScriptChatAction.JUMP, saved.id, "0"),
            Triple(ScriptChatAction.JUMP, saved.id, "0"),
            Triple(ScriptChatAction.JUMP, saved.id, "0"),
            Triple(ScriptChatAction.CLOSE, saved.id, ""),
        ), calls)
    }

    @Test
    fun jumpUsesMessageIndexAndRejectsAnInvalidOrOutOfRangePosition() {
        val chat = Chat("chat", "card", "名称", messages = List(4) { Message("user", "User", "消息 $it") })
        assertEquals(ChatArchiveJump("chat", 0), scriptChatJump(chat, "0"))
        assertEquals(ChatArchiveJump("chat", 3), scriptChatJump(chat, " 3.0 "))
        for (invalid in listOf("", "-1", "3.5", "4", "NaN", "Infinity", "旧消息")) assertNull(scriptChatJump(chat, invalid))
        assertNull(scriptChatJump(chat.copy(messages = emptyList()), "0"))
    }
}
