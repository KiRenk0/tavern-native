package app.tavern.nativeclient

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptCheckpointsTest {
    @Test
    fun branchingAnAlternateReplyCopiesItsContentAndKeepsTheOriginalSelection() {
        val replies = Message("assistant", "Mio", "Original")
            .withAlternative("Second", details = "Thoughts").select(0)
        val original = Chat("chat-a", "card", "Original chat",
            messages = listOf(Message("user", "A", "Question"), replies, Message("user", "A", "Later")),
            header = """{"chat_metadata":{"integrity":"original","custom":"keep"}}""",
            modelOverride = ModelSelection("google", "gemini-3.5-flash"))
        val (source, fork) = original.fork(1, "Fork", checkpoint = false, swipe = 1)
        assertEquals(0, source.messages[1].index)
        assertEquals("Original", source.messages[1].text)
        assertEquals(ChatPointer("Fork", fork.id), source.messages[1].branches.single())
        assertEquals(2, fork.messages.size)
        assertEquals("Second", fork.messages.last().text)
        assertEquals("Thoughts", fork.messages.last().reasoning)
        assertEquals(1, fork.messages.last().index)
        assertEquals(ChatPointer(original.title, original.id), fork.options.parent)
        assertEquals(original.modelOverride, fork.modelOverride)
        val metadata = JSONObject(requireNotNull(fork.header)).getJSONObject("chat_metadata")
        assertNotEquals("original", metadata.getString("integrity"))
        assertEquals("keep", metadata.getString("custom"))
    }

    @Test
    fun originalCheckpointCommandsReturnNamesAndIndicesAndOpenTheCorrectSavedChat() = runBlocking {
        var active = Chat("parent", null, "Parent", groupId = "group-x",
            messages = listOf(Message("user", "User", "Hi"),
                Message("assistant", "Mio", "Old").withAlternative("New").select(0)))
        val chats = linkedMapOf(active.id to active)
        val navigation = mutableListOf<String>()
        fun history() = chats.values.map { ChatSummary(it.id, it.title, it.updated) }
        val host = TavernScriptHost(
            snapshot = { TavernState(chat = active, history = history()) }, input = "", saveChat = {},
            savePrefs = {}, ask = { null }, trigger = { _, _ -> }, quiet = { _, _ -> "" },
            draft = {}, saveVariables = { _, _ -> }, notice = {},
            checkpointCommand = { command, named, text ->
                executeCheckpointCommand(command, named, text, active, history(), { index, checkpoint, requested, swipe ->
                    val title = requested.ifBlank { active.nextForkTitle(history(), checkpoint) }
                    val (source, child) = active.fork(index, title, checkpoint, swipe)
                    chats[source.id] = source
                    chats[child.id] = child
                    active = if (checkpoint) source else child
                    child.title
                }, { target ->
                    active = chats.getValue(target.id)
                    navigation += target.id
                    active.title
                })
            },
        )
        val script = StScript(host)
        assertEquals("Meeting Point", script.run("/checkpoint-create mesId=0 Meeting Point"))
        assertEquals("parent", active.id)
        assertEquals("[0]", script.run("/checkpoint-list"))
        assertEquals("""["Meeting Point"]""", script.run("/checkpoint-list links=true"))
        assertEquals("Meeting Point", script.run("/checkpoint-get 0"))
        assertEquals("", script.run("/checkpoint-go 1"))
        assertEquals("Meeting Point", script.run("/checkpoint-go 0"))
        assertEquals("group-x", active.groupId)
        assertEquals("Parent", script.run("/checkpoint-parent"))
        assertEquals("Parent", script.run("/checkpoint-exit"))
        assertEquals("Parent · 分支 1", script.run("/branch-create swipe=1 1"))
        assertEquals("New", active.messages.last().text)
        assertEquals("Old", chats.getValue("parent").messages.last().text)
        assertEquals("Parent", script.run("/checkpoint-exit"))
        assertTrue(navigation.last() == "parent")
        assertEquals("", script.run("/checkpoint-exit"))
    }
}
