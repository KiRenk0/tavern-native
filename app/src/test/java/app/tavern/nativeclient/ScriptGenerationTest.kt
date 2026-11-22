package app.tavern.nativeclient

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptGenerationTest {
    @Test
    fun `raw script arguments retain arrays and resolve saved variables for temporary options`() = runBlocking {
        var received: ScriptGenerationOptions? = null
        var prompt = ""
        val host = TavernScriptHost(
            snapshot = { TavernState() }, input = "", saveChat = {}, savePrefs = {}, ask = { null }, trigger = { _, _ -> },
            quiet = { _, _ -> error("raw should use its independent handler") }, draft = {}, saveVariables = { _, _ -> },
            generateRaw = { text, values ->
                prompt = text
                received = ScriptGenerationOptions.read(values, raw = true) { if (it == "limitVar") "64" else it }
                "Generated."
            }, notice = {},
        )
        val script = StScript(host)
        val result = script.run("""/genraw length=limitVar system="An instruction" prefill="Start " stop=["END HERE", "A|B"] instruct=off trim=off as=char One question | /pass {{pipe}}""")
        assertEquals("Generated.", result)
        assertEquals("One question", prompt)
        val settings = requireNotNull(received)
        assertEquals(64, settings.length)
        assertEquals("An instruction", settings.system)
        assertEquals("Start ", settings.prefill)
        assertEquals(listOf("END HERE", "A|B"), settings.stops)
        assertTrue(settings.asCharacter)
        assertFalse(settings.instruct)
        assertFalse(settings.trim)
    }

    @Test
    fun `sysgen posts compact narration at requested depth and returns a portable message`() = runBlocking {
        var state = TavernState(chat = Chat("c", "r", "Test", messages = listOf(
            Message("user", "Mio", "First."), Message("assistant", "Rina", "Last."),
        )))
        var quietName: String? = "unset"
        val spoken = mutableListOf<String>()
        val host = TavernScriptHost(
            snapshot = { state }, input = "", saveChat = { state = state.copy(chat = it) }, savePrefs = {},
            ask = { null }, trigger = { _, _ -> }, draft = {},
            quiet = { prompt, arguments ->
                quietName = arguments["name"]
                assertEquals("Describe the harbor.", prompt)
                "A ship arrived."
            },
            posted = { spoken += it.text }, saveVariables = { _, _ -> }, notice = {},
        )
        val result = StScript(host).run("""/sysgen name="Narrator" compact=true at=-1 return=object Describe the harbor.""")
        val row = JSONObject(result)
        assertEquals(null, quietName)
        assertEquals("Narrator", row.getString("name"))
        assertEquals("A ship arrived.", row.getString("mes"))
        assertEquals("narrator", row.getJSONObject("extra").getString("type"))
        assertTrue(Message.from(row).compactNarrator)
        assertEquals(listOf("First.", "A ship arrived.", "Last."), state.chat?.messages?.map(Message::text))
        assertEquals(listOf("A ship arrived."), spoken)
    }

    @Test
    fun `raw cleanup respects speaker boundaries`() {
        assertEquals("", trimScriptReplyNames("Mio: Wrong speaker.", "Mio", "Rina"))
        assertEquals("A true reply.", trimScriptReplyNames("Rina: A true reply.\nMio: Next turn.", "Mio", "Rina"))
        assertEquals("Discuss Mio: then continue.", trimScriptReplyNames("Discuss Mio: then continue.", "Mio", "Rina"))
    }
}
