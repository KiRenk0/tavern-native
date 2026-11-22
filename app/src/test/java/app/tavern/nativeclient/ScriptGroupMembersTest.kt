package app.tavern.nativeclient

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptGroupMembersTest {
    private val rina = Card("r", jsonOf("name" to "Rína").toString()).withScriptAvatarKey("Rina original.png")
    private val kaoru = Card("k", jsonOf("name" to "薫").toString()).withScriptAvatarKey("kaoru.png")
    private val mio = Card("m", jsonOf("name" to "Mio").toString())
    private val group = TavernGroup("g", "Cast", listOf(rina.id, kaoru.id), mutedIds = setOf(kaoru.id))
    private val chat = Chat("c", null, "Scene", groupId = group.id)
    private val groupState = TavernState(cards = listOf(kaoru, rina, mio), groups = listOf(group), chat = chat, groupId = group.id)

    @Test
    fun `group script queries and edits ordered roster without replacing its conversation`() = runBlocking {
        var state = groupState
        val viewed = mutableListOf<Card>()
        val notices = mutableListOf<String>()
        val host = TavernScriptHost(
            snapshot = { state }, input = "", saveChat = { error("membership must not rewrite history") },
            savePrefs = {}, ask = { dialog -> viewed += requireNotNull(dialog.card); null }, trigger = { _, _ -> },
            quiet = { _, _ -> "" }, draft = {}, saveVariables = { _, _ -> },
            updateGroup = { id, change ->
                assertEquals(group.id, id)
                val previous = requireNotNull(state.group)
                val updated = change(previous)
                state = state.copy(groups = listOf(updated))
                updated != previous
            }, notice = { notices += it },
        )
        val script = StScript(host)
        assertEquals("2", script.run("/member-count"))
        assertEquals("Rína", script.run("/getmember 0"))
        assertEquals("1", script.run("/member-get field=index kaoru"))
        assertEquals("0", script.run("/member-get field=id 薫"))
        assertEquals("Rina original.png", script.run("/member-get field=avatar Rina"))
        assertEquals("Mio", script.run("/addmember Mio"))
        assertEquals("", script.run("/member-add Mio"))
        assertTrue(notices.single().contains("已在群组"))
        script.run("/member-up Mio | /member-down 0 | /enablemember 薫 | /disablemember Rina")
        assertEquals(listOf(mio.id, rina.id, kaoru.id), state.group?.memberIds)
        assertEquals(setOf(rina.id), state.group?.mutedIds)
        script.run("/peek 1 | /member-remove 1")
        assertEquals(listOf(rina), viewed)
        assertEquals(listOf(mio.id, kaoru.id), state.group?.memberIds)
        assertTrue(state.group!!.mutedIds.isEmpty())
        assertEquals(chat, state.chat)
        script.run("/member-remove 0 | /member-remove 0")
        assertEquals("0", script.run("/member-count"))
        assertEquals(group.id, state.group?.id)
    }

    @Test
    fun `a roster change applies to the current stored order`() = runBlocking {
        val latest = group.copy(memberIds = listOf(mio.id, rina.id, kaoru.id))
        var stored = latest
        ScriptGroupCommand.UP.execute(groupState, "name", "薫",
            update = { _, transform -> stored = transform(stored); stored != latest }, peek = {}, notice = {})
        assertEquals(listOf(mio.id, kaoru.id, rina.id), stored.memberIds)
        assertEquals(setOf(kaoru.id), stored.mutedIds)
    }

    @Test
    fun `ask returns the invited actor reply and trigger returns no pipeline payload`() = runBlocking {
        val solo = Chat("solo", rina.id, "Owner chat")
        val state = TavernState(cards = listOf(rina, kaoru), chat = solo, cardId = rina.id)
        val triggered = mutableListOf<Pair<String?, Boolean>>()
        val asked = mutableListOf<Pair<String, String>>()
        val host = TavernScriptHost(
            snapshot = { state }, input = "", saveChat = {}, savePrefs = {}, ask = { null },
            trigger = { target, await -> triggered += target to await }, quiet = { _, _ -> "" },
            draft = {}, saveVariables = { _, _ -> },
            askCharacter = { actor, question ->
                asked += actor to question
                Message("assistant", kaoru.name, "来客的回复", characterId = kaoru.id)
            }, notice = {},
        )
        val script = StScript(host)
        assertEquals("来客的回复", script.run("""/ask name=kaoru.png 你是谁？ | /pass {{pipe}}"""))
        val reply = JSONObject(script.run("""/ask name=薫 return=object 再问一次"""))
        assertEquals("薫", reply.getString("name"))
        val stored = Message.from(reply)
        assertEquals(kaoru.id, stored.characterId)
        assertEquals(kaoru, state.replyingCharacter(stored))
        assertEquals(rina, state.card)
        assertEquals(listOf("kaoru.png" to "你是谁？", "薫" to "再问一次"), asked)
        assertEquals("", script.run("/trigger 0"))
        assertEquals("", script.run("/trigger await=true 薫"))
        assertEquals(listOf("0" to false, "薫" to true), triggered)
        assertEquals(rina.id, state.chat?.cardId)
    }

    @Test
    fun `queued generation waits for idle and abandons a different conversation`() = runBlocking {
        val state = MutableStateFlow(groupState.copy(busy = false, generation = Generation(chat.id)))
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { awaitScriptTriggerSlot(state, chat.id) }
        assertFalse(waiting.isCompleted)
        state.value = state.value.copy(generation = null)
        assertEquals(true, waiting.await())

        state.value = state.value.copy(busy = true)
        val switched = async(start = CoroutineStart.UNDISPATCHED) { awaitScriptTriggerSlot(state, chat.id) }
        state.value = state.value.copy(chat = chat.copy(id = "next"))
        assertEquals(false, switched.await())
        state.value = state.value.copy(chat = chat)
        assertNull(awaitScriptTriggerSlot(state, chat.id, timeoutMillis = 5))
    }
}
