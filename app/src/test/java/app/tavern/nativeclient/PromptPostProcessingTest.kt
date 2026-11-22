package app.tavern.nativeclient

import app.tavern.nativeclient.backup.SettingsSnapshot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PromptPostProcessingTest {
    private val names = PromptPostProcessingNames("Mio", "Rina", listOf("Kira", "Rina"))

    @Test
    fun allRoleModesMatchTheOriginalOrderingAndPlaceholder() {
        val input = JSONArray("""[
            {"role":"system","content":"System"},
            {"role":"system","name":"example_assistant","content":"Kira: Come in"},
            {"role":"assistant","content":"First"},
            {"role":"system","content":"Mid-chat rule"},
            {"role":"user","name":"Mio","content":"Hello"},
            {"role":"user","content":"Anyone there?"}
        ]""")
        val before = input.toString()
        fun run(mode: PromptPostProcessing) = mode.process(input, names).objects().map { it.string("role") to it.string("content") }
        val merge = listOf(
            "system" to "System\n\nKira: Come in", "assistant" to "First",
            "system" to "Mid-chat rule", "user" to "Mio: Hello\n\nAnyone there?",
        )
        val semi = listOf(merge[0], merge[1], "user" to "Mid-chat rule\n\nMio: Hello\n\nAnyone there?")
        val strict = listOf(semi[0], "user" to "[Start a new chat]", semi[1], semi[2])
        assertSame(input, PromptPostProcessing.NONE.process(input, names))
        for (mode in listOf(PromptPostProcessing.MERGE, PromptPostProcessing.MERGE_TOOLS)) assertEquals(merge, run(mode))
        for (mode in listOf(PromptPostProcessing.SEMI, PromptPostProcessing.SEMI_TOOLS)) assertEquals(semi, run(mode))
        for (mode in listOf(PromptPostProcessing.STRICT, PromptPostProcessing.STRICT_TOOLS)) assertEquals(strict, run(mode))
        assertEquals(listOf("user" to "System\n\nKira: Come in\n\nRina: First\n\nMid-chat rule\n\nMio: Hello\n\nMio: Anyone there?"),
            run(PromptPostProcessing.SINGLE))
        assertEquals(before, input.toString())
    }

    @Test
    fun mediaAndConsecutiveToolCallsRetainTheirOrderAndIdentifiers() {
        val input = JSONArray("""[
            {"role":"user","name":"Mio","content":[{"type":"image_url","image_url":{"url":"data:image/png;base64,abc"}},{"type":"text","text":"First"}]},
            {"role":"user","content":[{"type":"text","text":"Next"},{"type":"input_audio","input_audio":{"data":"abc","format":"wav"}}]},
            {"role":"assistant","content":null,"tool_calls":[{"id":"first","type":"function"}]},
            {"role":"assistant","content":null,"tool_calls":[{"id":"second","type":"function"}]},
            {"role":"tool","tool_call_id":"first","content":"result one"},
            {"role":"tool","tool_call_id":"second","content":"result two"}
        ]""")
        val tools = PromptPostProcessing.STRICT_TOOLS.process(input, names)
        val parts = tools.getJSONObject(0).getJSONArray("content")
        assertEquals(listOf("text", "image_url", "text", "input_audio"), parts.objects().map { it.string("type") })
        assertEquals("Mio: ", parts.getJSONObject(0).string("text"))
        assertEquals("First\n\nNext", parts.getJSONObject(2).string("text"))
        assertEquals(listOf("first", "second"),
            tools.getJSONObject(1).getJSONArray("tool_calls").objects().map { it.string("id") })
        assertEquals(listOf("first", "second"), tools.objects().drop(2).map { it.string("tool_call_id") })
        val withoutTools = PromptPostProcessing.MERGE.process(input, names)
        assertTrue(withoutTools.objects().none { it.has("tool_calls") || it.has("tool_call_id") || it.string("role") == "tool" })
        assertEquals("result one\n\nresult two", withoutTools.getJSONObject(withoutTools.length() - 1).string("content"))
    }

    @Test
    fun strictModeHandlesSystemOnlyAndLegacyModeRemainsPortableWithoutRequestSecrets() {
        val empty = PromptPostProcessing.STRICT.process(JSONArray(), names)
        assertEquals("[Start a new chat]", empty.getJSONObject(0).string("content"))
        val leadingSystem = PromptPostProcessing.STRICT_TOOLS.process(JSONArray("""[{"role":"system","content":"rules"}]"""), names)
        assertEquals(listOf("system", "user"), leadingSystem.objects().map { it.string("role") })
        val imported = readOriginalProviderRequestOptions("""{"custom_prompt_post_processing":"claude"}""".encodeToByteArray())
        assertEquals(PromptPostProcessing.MERGE, imported.processing)
        val settings = imported.copy(includeHeaders = "Authorization: Bearer example-private")
        val provider = Provider("custom", "Custom", "https://example.com/v1", defaultModel = "m", requestOptions = settings)
        val snapshot = SettingsSnapshot.capture("Mode", Preferences(providers = listOf(provider))) { "sealed:$it" }
        val restored = SettingsSnapshot.read(snapshot.portableBytes()).restore(Preferences()) { "" }.providers.single().requestOptions
        assertEquals(PromptPostProcessing.MERGE, restored.processing)
        assertFalse(restored.hasYaml)
    }
}
