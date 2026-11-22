package app.tavern.nativeclient

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TextContextTemplateTest {
    @Test
    fun `all bundled context settings render and survive native storage and original JSON round trips`() {
        val builtIns = JSONArray(File("src/main/assets/context-presets.json").readText()).objects()
            .map { TextContextTemplate.fromJson(it) }
        assertEquals(34, builtIns.distinctBy(TextContextTemplate::name).size)
        val expander = TavernMacroRenderer("test", { _, _ -> null })
        for (template in builtIns) {
            assertEquals(template, TextContextTemplate.parse(template.json().toString()))
            val rendered = StoryString.render(template.story, mapOf("description" to "Plain <unsafe> & unescaped"), expander::expand)
            assertTrue(template.name, rendered.contains("Plain <unsafe> & unescaped"))
            assertFalse(template.name, rendered.contains("{{"))
        }
        val provider = Provider("p", protocol = ApiProtocol.KOBOLD_TEXT, textContext = builtIns.first())
        assertEquals(provider, Provider.fromJson(provider.storageJson { it }) { it })
        assertThrows(IllegalArgumentException::class.java) {
            TextContextTemplate("broken", "{{#if persona}}abc").validated()
        }
        assertThrows(IllegalArgumentException::class.java) {
            TextContextTemplate.parse("""{"name":"not context","input_sequence":"x"}""")
        }
    }

    @Test
    fun `matches Handlebars blocks and expands ST macros and removes surrounding line breaks`() {
        val renderer = TavernMacroRenderer("test", { name, _ -> if (name == "user") "Mio" else null })
        val story = "{{#if description}}D={{description}}{{else}}EMPTY{{/if}}\n{{trim}}\n" +
            "{{#unless persona}}P={{random::{{user}}::{{user}}}}{{/unless}}"
        val rendered = StoryString.render(story, mapOf("description" to "Rin", "persona" to ""), renderer::expand)
        assertEquals("D=RinP=Mio", rendered)
        assertEquals("fallback", StoryString.render("{{#if scenario}}wrong{{else}}fallback{{/if}}", emptyMap(), renderer::expand))
        assertEquals("inner", StoryString.render("{{#with user}}{{this}}{{/with}}", mapOf("user" to "inner"), renderer::expand))
        assertEquals("text", StoryString.render("text{{precompile \"test\"}}{{embedded \"test\"}}", emptyMap(), renderer::expand))
        assertThrows(IllegalArgumentException::class.java) {
            StoryString.render("{{> missing}}", emptyMap(), renderer::expand)
        }
    }

    @Test
    fun `context builds story lore and note anchors and distinguishes example and chat separators`() {
        val card = Card("rin", jsonOf(
            "name" to "Rin", "description" to "Character dossier", "scenario" to "hidden scenario",
            "system_prompt" to "Primary instruction", "mes_example" to "<START>\nMio: Trial one\nRin: Demo one\n<START>\nRin: Demo two",
        ).toString())
        val book = WorldBook(name = "Harbor", enabled = true).withEntries(listOf(
            LoreEntry(0, content = "Active lore", constant = true),
        ))
        val note = AuthorNote("Keep the tone calm.", position = NotePosition.BEFORE_SCENARIO)
        val context = TextContextTemplate("Story",
            "{{system}}\n{{anchorBefore}}\n{{wiBefore}}\n{{description}}\n{{persona}}",
            exampleSeparator = "EXAMPLE", chatStart = "CHAT", separatorsAsStops = true, singleLine = true)
        val history = listOf(Message("user", "Mio", "Actual request"))
        val result = ModelService.prompt(card, history, false, listOf(book), null, PersonaProfile(name = "Mio", description = "Persona"),
            ConversationOptions(authorNote = note), textContext = context).textPrompt!!
        assertEquals("Primary instruction\nKeep the tone calm.\nActive lore\nCharacter dossier\nPersona\n" +
            "EXAMPLE\nMio: Trial one\nRin: Demo one\nEXAMPLE\nRin: Demo two\nCHAT\nMio: Actual request\nRin:", result.text)
        assertEquals(listOf("\n", "\nMio:", "\nCHAT", "\nEXAMPLE"), result.stops)
    }

    @Test
    fun `in chat story uses the chosen role at requested depth and example inlining is independent`() {
        val card = Card("rin", jsonOf("name" to "Rin", "description" to "SET", "mes_example" to "<START>\nRin: SAMPLE").toString())
        val persona = PersonaProfile(name = "Mio")
        val history = listOf(Message("assistant", "Rin", "OPEN"), Message("user", "Mio", "LATEST"))
        val context = TextContextTemplate("T", "{{description}} / {{mesExamplesRaw}}", exampleSeparator = "EX", chatStart = "CHAT",
            inChat = true, depth = 1, role = PromptRole.USER, skipExamples = true)
        val instruct = InstructTemplate.parse("""{"name":"I","macro":false,"input_sequence":"[USER]",
            "output_sequence":"[ASSISTANT]","story_string_prefix":"[STORY]","user_alignment_message":""}""")
        val result = ModelService.prompt(card, history, false, emptyList(), null, persona, ConversationOptions(),
            instruct = instruct, textContext = context).textPrompt!!
        assertEquals("CHAT\n[ASSISTANT]\nOPEN\n[USER]\nSET / <START>\nRin: SAMPLE\n[USER]\nLATEST\n[ASSISTANT]\n", result.text)
        assertEquals("一句。", trimToCompleteSentence("一句。 下一句"))
        assertEquals("连贯文字", trimToCompleteSentence("连贯文字"))
    }
}
