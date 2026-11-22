package app.tavern.nativeclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatArchiveSearchTest {
    @Test
    fun matchRequiresAllFragmentsAndCanUseDifferentMessagesOrTheDisplayedTranslation() {
        val messages = listOf(
            Message("assistant", "Mio", "At the OBSERVATORY"),
            Message("user", "Reader", "Borrow the key"),
            Message("assistant", "Mio", "We'll return", translation = "回到观测站"),
        )
        val query = ChatArchiveQuery("  observatory\u3000Key\n观测站  ")
        assertEquals(listOf("observatory", "Key", "观测站"), query.terms)
        assertEquals(0, query.match(messages)?.index)
        assertEquals(2, ChatArchiveQuery("观测站 return").match(messages)?.index)
        assertNull(ChatArchiveQuery("observatory missing").match(messages))
        assertNull(ChatArchiveQuery("观测站").match(messages.take(2)))
        assertTrue(ChatArchiveQuery("RETURN key").matchesTitle("The Return of the Key"))
        assertFalse(ChatArchiveQuery("RETURN key").matchesTitle("The Return"))
    }

    @Test
    fun previewUsesAMatchingMessageAndPreservesSupplementaryCharacters() {
        val text = "𠮷".repeat(130) + " 百合　花園\n" + "🌿".repeat(125)
        val hit = ChatArchiveQuery("百合 花園").match(listOf(
            Message("assistant", "A", "百合"),
            Message("assistant", "B", text),
            Message("assistant", "C", "Last reply without the search terms"),
        ))
        val preview = requireNotNull(hit).preview
        assertEquals(1, hit.index)
        assertEquals("B", hit.speaker)
        assertTrue(preview.contains("百合 花園"))
        assertTrue(preview.startsWith("…") && preview.endsWith("…"))
        val encoded = preview.encodeToByteArray()
        assertEquals(preview, encoded.decodeToString())
        assertTrue(preview.length <= 182)
    }
}
