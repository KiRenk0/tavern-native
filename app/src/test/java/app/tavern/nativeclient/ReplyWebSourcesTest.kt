package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyWebSourcesTest {
    @Test
    fun `search setting follows the original preset and source metadata stays with its alternative`() {
        val preset = PromptPreset.read(JSONObject("""{"prompts":[],"enable_web_search":true}"""), "Search")
        assertTrue(preset.chatCompletion.webSearch)
        assertTrue(preset.json().getBoolean("enable_web_search"))
        assertFalse(PromptPreset(name = "Legacy").json().has("enable_web_search"))
        val one = ReplySource("https://example.com/one", "One")
        val two = ReplySource("https://example.com/two", "Two")
        val connection = ModelConnection(Provider("deepseek", template = ProviderTemplate.DEEPSEEK), "deepseek-flash")
        val replay = WebReplyReplay.create(WebSearchBackend.DEEPSEEK, connection, JSONArray().put(jsonOf("index" to 0)))
        val message = Message("assistant", "Rina", "First answer", webReply = StoredWebReply(listOf(one), replay))
            .withAlternative("Second answer", web = StoredWebReply(listOf(two)))
        val imported = Message.from(JSONObject(message.toJson().toString()))
        assertEquals(listOf(two), imported.webReply?.sources)
        assertEquals(listOf(one), imported.select(0).webReply?.sources)
        val restoredReplay = imported.select(0).webReply?.replay
        assertEquals(0, restoredReplay?.wireValue(connection, WebSearchBackend.DEEPSEEK)?.getJSONObject(0)?.getInt("index"))
        assertNull(restoredReplay?.wireValue(connection.copy(model = "deepseek-pro"), WebSearchBackend.DEEPSEEK))
        assertNull(imported.select(0).withText("Edited").webReply)
    }

    @Test
    fun `source links reject unsupported links and deduplicate streamed citations`() {
        assertNull(ReplySource.read(jsonOf("url" to "javascript:alert(1)")))
        assertNull(ReplySource.read(jsonOf("url" to "https://user:secret@example.com/")))
        val accumulator = CompletionAccumulator()
        val url = "https://example.com/article?q=1"
        accumulator.appendCitations(JSONArray().put(jsonOf("url" to url)))
        accumulator.appendCitations(JSONArray().put(jsonOf("url_citation" to jsonOf("url" to url, "title" to "Article"))))
        assertEquals(listOf(ReplySource(url, "Article")), accumulator.sources)
    }

    @Test
    fun `Claude SSE replay keeps complete original tool result text and citation blocks`() {
        val capture = AnthropicWebTranscript()
        capture.start(0, jsonOf("type" to "server_tool_use", "id" to "srvtoolu_1", "name" to "web_search", "input" to JSONObject()))
        capture.delta(0, jsonOf("type" to "input_json_delta", "partial_json" to "{\"query\":\"status\"}"))
        capture.start(1, jsonOf("type" to "web_search_tool_result", "tool_use_id" to "srvtoolu_1"))
        capture.start(2, jsonOf("type" to "text", "text" to ""))
        capture.delta(2, jsonOf("type" to "text_delta", "text" to "Result"))
        capture.delta(2, jsonOf("type" to "citations_delta", "citation" to jsonOf("url" to "https://example.com")))
        val blocks = requireNotNull(capture.replay())
        assertEquals("status", blocks.getJSONObject(0).getJSONObject("input").getString("query"))
        assertEquals("srvtoolu_1", blocks.getJSONObject(1).getString("tool_use_id"))
        assertEquals("Result", blocks.getJSONObject(2).getString("text"))
        assertEquals("https://example.com", blocks.getJSONObject(2).getJSONArray("citations").getJSONObject(0).getString("url"))
    }
}
