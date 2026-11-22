package app.tavern.nativeclient

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCompletionPresetTest {
    @Test
    fun `generation controls round trip using the original preset keys`() {
        val raw = JSONObject("""{
            "data": {
                "prompts": [], "names_behavior": 1, "continue_postfix": "\n\n",
                "openai_max_context": 8192, "stream_openai": false, "send_if_empty": "Go on"
            }
        }""")
        val preset = PromptPreset.read(raw, "Imported")
        assertEquals(ChatCompletionPreset(ChatSpeakerNames.API_FIELD, ContinueSpacing.PARAGRAPH, 8192, false),
            preset.chatCompletion)
        assertEquals("Go on", preset.text(PresetUtility.EMPTY_INPUT))
        assertEquals(preset.chatCompletion, PromptPreset.read(preset.json(), "Imported").chatCompletion)
        assertEquals(1, preset.json().getInt("names_behavior"))
        assertFalse(preset.json().getBoolean("stream_openai"))

        val old = PromptPreset.read(JSONObject("""{"prompts":[]}"""), "Old")
        assertEquals(ChatCompletionPreset(), old.chatCompletion)
        assertFalse(old.json().has("stream_openai"))
        assertNull(preset.chatCompletionFor(ModelConnection(Provider("text", protocol = ApiProtocol.KOBOLD_TEXT), "a")))
    }

    @Test
    fun `role and names are preserved according to each group mode`() {
        val rina = Card("rina", jsonOf("name" to "Rina").toString())
        val mio = Card("mio", jsonOf("name" to "星ノ-Mio").toString())
        val history = listOf(Message("user", "Aoi", "Question"), Message("assistant", mio.name, "Previous reply", characterId = mio.id))
        val group = GroupPrompt(TavernGroup(name = "Group"), listOf(mio), listOf(rina.name, mio.name))
        val persona = PersonaProfile(name = "Aoi")
        fun request(mode: ChatSpeakerNames, namesSupported: Boolean = true): List<JSONObject> {
            val preset = PromptPreset(name = "Names", utilities = mapOf(PresetUtility.EMPTY_INPUT to "继续吧"),
                chatCompletion = ChatCompletionPreset(names = mode))
            return ModelService.prompt(rina, history, false, emptyList(), group, persona, ConversationOptions(),
                preset = preset, hasChatNameField = namesSupported).messages.objects()
        }
        val keyed = request(ChatSpeakerNames.API_FIELD)
        val other = keyed.single { it.string("content") == "Previous reply" }
        assertEquals("assistant", other.string("role"))
        assertEquals("___Mio", other.string("name"))
        assertEquals("Aoi", keyed.single { it.string("content") == "Question" }.string("name"))
        assertTrue(keyed.any { it.string("role") == "user" && it.string("content") == "继续吧" })
        val fallback = request(ChatSpeakerNames.API_FIELD, false)
        assertTrue(fallback.any { it.string("role") == "assistant" && it.string("content") == "${mio.name}: Previous reply" })
        assertFalse(fallback.any { it.has("name") })
        assertTrue(request(ChatSpeakerNames.NONE).any { it.string("role") == "assistant" && it.string("content") == "Previous reply" })
        assertTrue(request(ChatSpeakerNames.DEFAULT).any { it.string("content") == "Question" })
        assertTrue(request(ChatSpeakerNames.CONTENT).any { it.string("content") == "Aoi: Question" })
    }

    @Test
    fun `continuation applies the chosen separator after storage transforms`() {
        assertEquals("Before\n\nAfter", joinContinuation("Before", " after", ContinueSpacing.PARAGRAPH) { it.replace("a", "A") })
        assertEquals("BeforeAfter", joinContinuation("Before", "After", ContinueSpacing.NONE))
        assertEquals("Before", joinContinuation("Before", "   ", ContinueSpacing.LINE))
        assertEquals("Before After", joinContinuation("Before", "After"))
    }
}
