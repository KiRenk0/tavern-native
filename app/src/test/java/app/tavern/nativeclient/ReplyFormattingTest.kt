package app.tavern.nativeclient

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ReplyFormattingTest {
    @Test
    fun `group reply trimming handles split speaker labels and leaves mid sentence names alone`() {
        val filter = GroupReplyTrimmer(listOf("Nia", "Dove"))
        val fragments = listOf("Ni", "a", " is listening.", "\nD", "ove", ": Let me continue.")
        val result = fragments.joinToString("") { filter.append(it) } + filter.finish()
        assertEquals("Nia is listening.", result)
        assertTrue(filter.stopped)
        val firstLine = GroupReplyTrimmer(listOf("Nia"))
        assertEquals("", firstLine.append("Nia: wrong speaker"))
        assertTrue(firstLine.stopped)
        val partialLine = GroupReplyTrimmer(listOf("Nia"))
        assertEquals("\nhello\nNi", partialLine.append("\nhello\nNi") + partialLine.finish())
        assertFalse(partialLine.stopped)
    }

    private val xml = ReasoningFormat("Think XML", "<think>", "</think>", "\n")

    @Test
    fun `stream separates split reasoning markers and removes a split echo of the prefill`() {
        val body = StringBuilder()
        val thought = StringBuilder()
        var hidden = ""
        val stream = FormattedReplyStream("Rin: ", false, xml, { thought.append(it) }, { body.append(it) }, { hidden = it })
        listOf("Ri", "n: <th", "ink>Hidden ", "thought</th", "ink>", "\n", "Answer").forEach(stream::accept)
        stream.finish()
        assertEquals("Rin: Answer", body.toString())
        assertEquals("Hidden thought", thought.toString())
        assertEquals("Rin: ", hidden)
        assertTrue(stream.hasBody)
        assertTrue(stream.hasReasoning)
    }

    @Test
    fun `a reasoning starter can continue and truncated reasoning stays separate`() {
        val thought = StringBuilder()
        val body = StringBuilder()
        val starter = FormattedReplyStream("<think>Plan: ", true, xml, { thought.append(it) }, { body.append(it) })
        listOf("look around", "</", "think>", "\n", "Found it").forEach(starter::accept)
        starter.finish()
        assertEquals("Plan: look around", thought.toString())
        assertEquals("Found it", body.toString())
        val partial = FormattedReplyStream("", true, xml, { thought.append(it) }, { fail("thought leaked into body") })
        partial.accept("<think> unfinished </th")
        partial.finish()
        assertFalse(partial.hasBody)
        assertTrue(partial.hasReasoning)
        assertTrue(thought.toString().endsWith(" unfinished </th"))
    }

    @Test
    fun `hidden prefix survives portable chat rows and stays paired with each alternative`() {
        val single = Message("assistant", "Rin", "Cue: one", hiddenReplyPrefix = "Cue: ")
        val alternatives = single.withAlternative("New: two", hiddenPrefix = "New: ").withScriptAlternative("three", select = false)
        val restored = Message.from(alternatives.toJson())
        assertEquals("New: two", restored.requestText)
        assertEquals("two", restored.displayText)
        assertEquals("one", restored.select(0).displayText)
        assertEquals("three", restored.select(2).displayText)
        assertEquals("one", restored.withoutAlternative(1).select(0).displayText)
    }

    @Test
    fun `reasoning history limit and reply prefix are applied after resolving macros and preset history`() {
        val presets = JSONArray(File("src/main/assets/reasoning-presets.json").readText()).objects().map { ReasoningFormat.from(it) }
        assertEquals(5, presets.distinctBy(ReasoningFormat::name).size)
        assertEquals(presets, presets.map { ReasoningFormat.from(it.json()) })
        val formatting = ReplyFormatting("Next {{char}}: ", false, xml, true, 1, true)
        val prefs = Preferences(replyFormatting = formatting)
        assertEquals(formatting, decodePreferences(encodePreferences(prefs) { it }, { it }).replyFormatting)
        val card = Card("rin", jsonOf("name" to "Rin", "description" to "Facts").toString())
        val history = listOf(Message("assistant", "Rin", "Old", reasoning = "Skip thought"),
            Message("assistant", "Rin", "Latest", reasoning = "Recent thought"), Message("user", "Mio", "Again?"))
        val prepared = ModelService.prompt(card, history, false, emptyList(), null, PersonaProfile(name = "Mio"),
            ConversationOptions(), includeReasoning = true, textContext = TextContextTemplate("Context", "{{description}}"),
            replyFormatting = formatting, textCompletion = true)
        val output = prepared.textPrompt!!.text
        assertTrue(output.contains("<think>Recent thought</think>\nLatest"))
        assertFalse(output.contains("Skip thought"))
        assertTrue(output.endsWith("Rin: Next Rin: "))
        assertEquals("Next Rin: ", prepared.replyPrefix)
        assertEquals(xml, prepared.reasoningFormat)
    }
}
