package app.tavern.nativeclient

import app.tavern.nativeclient.backup.SettingsSnapshot
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AdvancedFormattingTest {
    @Test
    fun `system presets and literal newline stops persist and import original settings`() {
        val presets = JSONArray(File("src/main/assets/system-prompt-presets.json").readText()).objects()
            .map { TextSystemPreset.from(it) }
        assertEquals(13, presets.distinctBy(TextSystemPreset::name).size)
        assertEquals(presets, presets.map { TextSystemPreset.from(it.json()) })
        val selected = TextSystemPreset("Custom", "Speak to {{user}}", "End clearly")
        val system = TextSystemSettings().saving(selected).copy(preferCharacterPostHistory = false)
        val stops = CustomStoppingStrings.parse("""["\n{{user}}:", "<END>", "<END>"]""", macros = true)
        assertEquals(listOf("\nMio:", "<END>"), stops.render { it.replace("{{user}}", "Mio") })
        assertEquals(listOf("\n{{user}}:", "<END>"), stops.copy(macros = false).render { "not used" })
        assertThrows(IllegalArgumentException::class.java) { CustomStoppingStrings.parse("""["OK",3]""", true) }
        val prefs = Preferences(textSystem = system, customStops = stops)
        val restored = decodePreferences(encodePreferences(prefs) { it }, { it })
        assertEquals(system, restored.textSystem)
        assertEquals(stops, restored.customStops)
        val original = jsonOf("power_user" to jsonOf(
            "sysprompt" to selected.json().put("enabled", false),
            "prefer_character_prompt" to false, "prefer_character_jailbreak" to false,
            "custom_stopping_strings" to JSONArray(stops.values).toString(), "custom_stopping_strings_macro" to false,
        )).toString().encodeToByteArray()
        val migrated = SettingsSnapshot.import(original, "settings.json", Preferences()).restore(Preferences()) { it }
        assertEquals(TextSystemSettings(selected = selected, enabled = false,
            preferCharacterPrompt = false, preferCharacterPostHistory = false), migrated.textSystem)
        assertEquals(stops.copy(macros = false), migrated.customStops)
    }

    @Test
    fun `system prompt and history suffix use independent preference and explicit chat override`() {
        val card = Card("char", jsonOf("name" to "Rin", "description" to "Character facts",
            "system_prompt" to "Card main {{original}}", "post_history_instructions" to "Card last {{original}}").toString())
        val genre = TextSystemPreset("Preset", "Preset for {{user}}", "Global suffix.")
        val template = TextContextTemplate("Context", "{{system}}\n{{description}}")
        val persona = PersonaProfile(name = "Mio")
        val history = listOf(Message("user", "Mio", "Hello"))
        fun prompt(settings: TextSystemSettings, options: ConversationOptions = ConversationOptions()) = ModelService.prompt(
            card, history, false, emptyList(), null, persona, options, textContext = template, textSystem = settings,
            customStops = CustomStoppingStrings(listOf("\n{{user}}:")),
        )
        val configured = TextSystemSettings(selected = genre, preferCharacterPostHistory = false)
        val default = prompt(configured)
        assertEquals("Card main Preset for Mio\nCharacter facts\nMio: Hello\nMio: Global suffix.\nRin:", default.textPrompt!!.text)
        assertEquals(listOf("\nMio:"), default.stops)
        val chat = prompt(configured.copy(preferCharacterPrompt = false, preferCharacterPostHistory = true),
            ConversationOptions(systemPromptOverride = "Chat {{original}}"))
        assertTrue(chat.textPrompt!!.text.startsWith("Chat Preset for Mio"))
        assertTrue(chat.textPrompt.text.contains("Card last Global suffix."))
        val disabled = prompt(configured.copy(enabled = false)).textPrompt!!.text
        assertFalse(disabled.contains("Preset for Mio"))
        assertFalse(disabled.contains("Global suffix."))
        assertTrue(disabled.startsWith("Character facts\n"))
        val inherited = TextSystemSettings(preferCharacterPrompt = false, preferCharacterPostHistory = false)
        assertTrue(inherited.configured)
        val safeFallback = prompt(inherited).textPrompt!!.text
        assertTrue(safeFallback.contains("以 Rin 的身份与 Mio 对话"))
        assertFalse(safeFallback.contains("Card main"))
        assertFalse(safeFallback.contains("Card last"))
    }

    @Test
    fun `chat completion prompt flags preserve preset text and explicit chat overrides`() {
        val card = Card.from(jsonOf("name" to "Rin", "system_prompt" to "Card main {{original}}",
            "post_history_instructions" to "Card last {{original}}"))
        val preset = PromptPreset.read(jsonOf("name" to "Preset", "prompts" to JSONArray(listOf(
            jsonOf("identifier" to "main", "name" to "Main", "role" to "system", "content" to "Preset main"),
            jsonOf("identifier" to "jailbreak", "name" to "After", "role" to "system", "content" to "Preset last"),
        ))), "Preset")
        fun prompt(preferSystem: Boolean, preferAfter: Boolean, override: String = "") =
            ModelService.prompt(card, listOf(Message("user", "Mio", "Hello")), false, emptyList(), null,
                PersonaProfile(name = "Mio"), ConversationOptions(systemPromptOverride = override),
                preset = preset, preferCharacterPrompt = preferSystem, preferCharacterPostHistory = preferAfter)
                .messages.objects().joinToString("\n") { it.string("content") }
        val default = prompt(preferSystem = true, preferAfter = false)
        assertTrue(default.contains("Card main Preset main"))
        assertTrue(default.contains("Preset last"))
        assertFalse(default.contains("Card last"))
        val overridden = prompt(preferSystem = false, preferAfter = true, override = "Chat {{original}}")
        assertTrue(overridden.contains("Chat Preset main"))
        assertFalse(overridden.contains("Card main"))
        assertTrue(overridden.contains("Card last Preset last"))
    }
}
