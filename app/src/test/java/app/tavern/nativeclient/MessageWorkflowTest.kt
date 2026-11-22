package app.tavern.nativeclient

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageWorkflowTest {
    @Test
    fun `markdown shortcut toggles around a selected word without losing the selection`() {
        val plain = TextFieldValue("Hello Rin", TextRange(7))
        val wrapped = plain.toggleMarkdown("**")
        assertEquals("Hello **Rin**", wrapped.text)
        assertEquals(TextRange(8, 11), wrapped.selection)
        val unwrapped = wrapped.toggleMarkdown("**")
        assertEquals("Hello Rin", unwrapped.text)
        assertEquals(TextRange(6, 9), unwrapped.selection)
        assertEquals("Hello `` ", TextFieldValue("Hello  ", TextRange(6)).toggleMarkdown("`").text)
    }

    @Test
    fun `embedded lore produces a stable editable book and retains original card data`() {
        val lore = jsonOf("name" to "Harbor", "scan_depth" to 7,
            "entries" to JSONArray(listOf(jsonOf("id" to 21, "keys" to JSONArray(listOf("pier")),
                "content" to "The lighthouse is closed."))))
        val card = Card.from(jsonOf("name" to "Rin", "character_book" to lore))
        val extracted = card.embeddedWorldBook()
        assertNotNull(extracted)
        requireNotNull(extracted)
        assertEquals("Harbor", extracted.name)
        assertEquals(7, extracted.scanDepthOverride)
        assertFalse(extracted.enabled)
        assertEquals("The lighthouse is closed.", extracted.entries.single().content)
        assertEquals(extracted.id, card.embeddedWorldBook()?.id)
        assertEquals(21, card.book?.optJSONArray("entries")?.optJSONObject(0)?.optInt("id"))
    }

    @Test
    fun `original editor settings persist and collapse multiple newline styles`() {
        val handling = ChatHandling(collapseNewlines = true, markdownHotkeys = true, selectInputOnFocus = true)
        val options = CharacterLibraryOptions(askToImportLore = true)
        assertEquals(handling, ChatHandling.from(handling.json()))
        assertTrue(CharacterLibraryOptions.from(options.json()).askToImportLore)
        assertEquals("one\ntwo\nthree", collapseChatNewlines("one\r\n\ntwo\n\n\nthree"))
        assertEquals("Cue\n\none\ntwo", collapseChatNewlines("Cue\n\none\n\ntwo", "Cue\n\n"))
    }
}
