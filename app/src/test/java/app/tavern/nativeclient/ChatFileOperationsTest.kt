package app.tavern.nativeclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ChatFileOperationsTest {
    @Test
    fun `refresh replaces only the checkpoint snapshot and keeps links usable by id`() {
        val target = Chat("checkpoint", "rina", "Harbor checkpoint", messages = listOf(Message("assistant", "Rina", "Old")),
            options = ConversationOptions(parent = ChatPointer("Old title", "source")))
        val source = Chat("source", "rina", "Harbor", messages = listOf(
            Message("user", "Mio", "Start"),
            Message("assistant", "Rina", "Selected choice", variants = listOf("Old choice", "Selected choice"), index = 1,
                checkpoint = ChatPointer(target.title, target.id), branches = listOf(ChatPointer("Alternate port", "branch"))),
            Message("assistant", "Rina", "Future"),
        ))
        val history = listOf(summary(source), summary(target))
        val (parent, snapshot) = source.refreshCheckpoint(1, target, history)
        assertEquals(source.messages, parent.messages)
        assertEquals(target.id, snapshot.id)
        assertEquals(target.title, snapshot.title)
        assertEquals(listOf("Start", "Selected choice"), snapshot.messages.map(Message::text))
        assertEquals(1, snapshot.messages.last().index)
        assertNull(snapshot.messages.last().checkpoint)
        assertEquals(listOf(ChatPointer("Alternate port", "branch")), snapshot.messages.last().branches)
        assertEquals(ChatPointer(source.title, source.id), snapshot.options.parent)
        assertThrows(IllegalArgumentException::class.java) {
            source.refreshCheckpoint(1, target.copy(options = ConversationOptions(parent = ChatPointer("Unrelated", "other"))), history)
        }
    }

    @Test
    fun `renaming upgrades matching legacy links without touching other chat identities`() {
        val original = ChatSummary("target", "Old name", 7)
        val editor = Chat("source", "rina", "Link chat", messages = listOf(Message("user", "Mio", "Continue",
            checkpoint = ChatPointer("Old name"), branches = listOf(ChatPointer("Old name"), ChatPointer("Old name", "another"),
                ChatPointer("Unrelated")))), options = ConversationOptions(parent = ChatPointer("Old name")))
        val renamed = editor.retargetLegacyChatLinks(original, "New name")
        val link = ChatPointer("New name", "target")
        assertEquals(link, renamed.options.parent)
        assertEquals(link, renamed.messages.single().checkpoint)
        assertEquals(listOf(link, ChatPointer("Old name", "another"), ChatPointer("Unrelated")), renamed.messages.single().branches)
        assertEquals(editor.updated, renamed.updated)
        assertEquals("Continue", renamed.messages.single().text)
    }

    private fun summary(chat: Chat) = ChatSummary(chat.id, chat.title, chat.updated)
}
