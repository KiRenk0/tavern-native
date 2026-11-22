package app.tavern.nativeclient

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class InstructTemplateTest {
    private val builtIns get() = JSONArray(File("src/main/assets/instruct-presets.json").readText()).objects()
        .map { InstructTemplate.fromJson(it) }
    private fun template(name: String) = builtIns.first { it.name == name }

    @Test
    fun `upstream templates round trip and provider settings migrate without changing credentials`() {
        val presets = builtIns
        assertEquals(38, presets.distinctBy(InstructTemplate::name).size)
        assertEquals(presets, presets.map { InstructTemplate.parse(it.json().toString()) })
        assertEquals(listOf(ProviderTemplate.DEEPSEEK, ProviderTemplate.GOOGLE), ProviderTemplate.builtIn)
        for (legacy in listOf(ProviderTemplate.ANTHROPIC, ProviderTemplate.OPENROUTER)) {
            val prior = legacy.profile().copy(apiKey = "saved", defaultModel = "selected")
            val migrated = Provider.fromJson(prior.storageJson { "stored:$it" }) { it.removePrefix("stored:") }
            assertEquals(prior.copy(template = null), migrated)
            assertEquals(migrated, Provider.fromJson(migrated.storageJson { it }) { it })
        }
        val provider = Provider("custom", protocol = ApiProtocol.KOBOLD_TEXT, instruct = template("ChatML"))
        assertEquals(provider, Provider.fromJson(provider.storageJson { it }) { it })
        assertThrows(IllegalArgumentException::class.java) { InstructTemplate.parse("""{"name":"context","story_string":"x"}""") }
    }

    @Test
    fun `formats examples and group speakers and selects the proper generation prefix`() {
        val card = Card("haru", jsonOf("name" to "Haru", "description" to "Card details", "system_prompt" to "Story seed",
            "mes_example" to "<START>\nMio: sample question\nHaru: sample answer").toString())
        val persona = PersonaProfile("mio", "Mio")
        val group = GroupPrompt(TavernGroup(name = "Harbor"), emptyList(), listOf("Haru", "Aoi"))
        val messages = listOf(
            Message("assistant", "Haru", "opening", characterId = card.id),
            Message("assistant", "Aoi", "second speaker", characterId = "aoi"),
            Message("system", "Narrator", "The tide rose."),
            Message("user", "Mio", "current input"),
            Message("user", "Mio", "hidden", hidden = true),
        )
        val chatML = template("ChatML")
        fun prepare(trigger: String, instruct: InstructTemplate) = ModelService.prompt(
            card, messages, false, emptyList(), group, persona, ConversationOptions(), trigger = trigger, instruct = instruct,
        ).textPrompt!!
        val normal = prepare("normal", chatML)
        assertTrue(normal.text.startsWith("<|im_start|>system\nStory seed"))
        assertTrue(normal.text.contains("<|im_start|>user\nMio: sample question<|im_end|>\n"))
        assertTrue(normal.text.contains("<|im_start|>assistant\nAoi: second speaker<|im_end|>\n"))
        assertTrue(normal.text.contains("<|im_start|>system\nThe tide rose.<|im_end|>\n"))
        assertTrue(normal.text.endsWith("<|im_start|>assistant\nHaru:"))
        assertFalse(normal.text.contains("hidden"))
        assertTrue(normal.stops.contains("<|im_end|>"))
        assertFalse(prepare("normal", chatML.copy(skipExamples = true)).text.contains("sample question"))
        assertTrue(prepare("impersonate", chatML).text.endsWith("<|im_start|>user\nMio:"))
    }

    @Test
    fun `continuation opens the final assistant turn after instructions without closing it`() {
        val card = Card("a", jsonOf("name" to "Rin", "post_history_instructions" to "Remember the instructions.").toString())
        val base = template("ChatML")
        val instruct = base.copy(sequences = base.sequences + (InstructSequence.LAST_ASSISTANT to "Final {{name}}"))
        val history = listOf(Message("user", "Traveler", "Go on"), Message("assistant", "Rin", "Partial reply."))
        val prompt = ModelService.prompt(card, history, false, emptyList(), null, PersonaProfile(name = "Traveler"),
            ConversationOptions(), trigger = "continue", instruct = instruct).textPrompt!!
        assertTrue(prompt.text.contains("<|im_start|>system\nRemember the instructions.<|im_end|>"))
        assertTrue(prompt.text.endsWith("Final Rin\nPartial reply."))
        assertTrue(prompt.stops.contains("\nFinal Rin"))
    }

    @Test
    fun `split stop sequences never enter output and incomplete prefixes are retained`() {
        val filter = InstructStopFilter(listOf("<stop>", "END"))
        assertEquals("Text ", filter.append("Text <st"))
        assertEquals("<style> ", filter.append("yle> E"))
        assertEquals("", filter.append("ND surprise"))
        assertEquals("", filter.append("discard"))
        assertTrue(filter.stopped)
        val cut = InstructStopFilter(listOf("<stop>"))
        assertEquals("Text ", cut.append("Text <st"))
        assertEquals("<st", cut.flush())
    }
}
