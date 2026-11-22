package app.tavern.nativeclient

import app.tavern.nativeclient.backup.SettingsSnapshot
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

class SummaryOptionsTest {
    @Test
    fun wordTriggerAdvancesOverOnlyTheMessagesSentInThisBatch() {
        val memory = MemoryOptions(enabled = true, interval = 0, triggerWords = 4, maxMessages = 1, targetWords = 80,
            instruction = "At most {{words}} words.")
        val history = listOf(
            Message("user", "Rin", "We reached port"),
            Message("assistant", "Mio", "This hidden message must be skipped", hidden = true),
            Message("assistant", "Mio", "The gate opened"),
        )
        val chat = Chat("id", "card", "Story", messages = history, options = ConversationOptions(memory = memory))
        val first = requireNotNull(chat.summaryBatch(force = false))
        assertEquals(0, first.lastIndex)
        assertEquals("Rin:\nWe reached port", first.forExtras())
        val firstRequest = first.forModel(memory.modelInstruction())
        assertEquals("At most 80 words.", firstRequest.getJSONObject(0).getString("content"))
        assertEquals("We reached port", JSONObject(firstRequest.getJSONObject(1).getString("content"))
            .getJSONArray("messages").getJSONObject(0).getString("text"))
        val next = chat.copy(messages = history.withSummary(first.lastIndex, "Arrival saved"))
        assertNull(next.summaryBatch(force = false))
        val manual = requireNotNull(next.summaryBatch(force = true))
        assertEquals(2, manual.lastIndex)
        assertEquals("Arrival saved\n\nMio:\nThe gate opened", manual.forExtras())
        assertEquals(memory, ConversationOptions.fromMetadata(chat.options.json()).memory)
    }

    @Test
    fun chatModelInstructionsKeepExistingBehaviorUntilAWordTargetIsSet() {
        assertEquals(DEFAULT_MEMORY_PROMPT, MemoryOptions().modelInstruction())
        assertEquals("Limit: 200.", MemoryOptions().modelInstruction("Limit: {{words}}."))
        assertEquals("Summarize.\nLimit the summary to 100 words.", MemoryOptions(targetWords = 100).modelInstruction("Summarize."))
    }

    @Test
    fun loreScanUsesLatestValidSummaryEvenWhenTheSummaryIsNotInjected() {
        val card = Card("character", """{"name":"Rin"}""")
        val scene = LoreEntry(1, content = "The alliance changed.", keywords = listOf("TurquoiseSignal"))
        val book = WorldBook("alliance", "Alliance", enabled = true).withEntries(listOf(scene))
        val messages = listOf(Message("user", "Mio", "Have we arrived?")).withSummary(0, "TurquoiseSignal cleared.")
        fun prompt(history: List<Message>, scan: Boolean) = ModelService.prompt(card, history, false, listOf(book), null,
            PersonaProfile(name = "Mio"), ConversationOptions(memory = MemoryOptions(scanLore = scan))).messages.toString()
        assertFalse(prompt(messages, scan = false).contains(scene.content))
        val activated = prompt(messages, scan = true)
        assertTrue(activated.contains(scene.content))
        assertFalse(activated.contains("TurquoiseSignal cleared"))
        assertFalse(prompt(messages.map { it.copy(text = "Edited source") }, scan = true).contains(scene.content))
    }

    @Test
    fun extrasSendsASummaryRequestWithOptionalAuthAndKeepsItsKeyOutOfPortableSettings() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val observed = AtomicReference<Triple<String, String, String>>()
        server.createContext("/proxy/api/summarize") { exchange ->
            exchange.use { call ->
                observed.set(Triple(call.requestMethod, call.requestHeaders.getFirst("Authorization").orEmpty(),
                    call.requestBody.readBytes().decodeToString()))
                val bytes = """{"summary":"  Before dawn, the bell rang.  "}""".encodeToByteArray()
                call.responseHeaders.set("Content-Type", "application/json")
                call.sendResponseHeaders(200, bytes.size.toLong())
                call.responseBody.write(bytes)
            }
        }
        server.start()
        try {
            val settings = SummaryExtrasSettings("http://127.0.0.1:${server.address.port}/proxy/api/", "sample-secret")
            assertEquals("Before dawn, the bell rang.", summarizeWithExtras(settings, "Mio:\nListen."))
            val (method, authorization, raw) = observed.get()
            assertEquals("POST", method)
            assertEquals("Bearer sample-secret", authorization)
            assertEquals("Mio:\nListen.", JSONObject(raw).getString("text"))
            assertEquals(0, JSONObject(raw).getJSONObject("params").length())
            val snapshot = SettingsSnapshot.capture("Summary", Preferences(summaryExtras = settings)) { "$it-sealed" }
            assertFalse(snapshot.portableBytes().decodeToString().contains(settings.apiKey))
            val restored = snapshot.restore(Preferences()) { it.removeSuffix("-sealed") }
            assertEquals(settings, restored.summaryExtras)
        } finally {
            server.stop(0)
        }
    }
}
