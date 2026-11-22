package app.tavern.nativeclient

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HordeTest {
    private data class Call(val method: String, val path: String, val key: String?, val body: JSONObject?)

    private fun server(handler: (Call) -> Pair<Int, String>): Pair<HttpServer, String> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.use {
                val text = it.requestBody.readBytes().decodeToString()
                val call = Call(it.requestMethod, it.requestURI.toString(), it.requestHeaders.getFirst("apikey"),
                    text.takeIf(String::isNotBlank)?.let(::JSONObject))
                val (code, response) = handler(call)
                val bytes = response.encodeToByteArray()
                it.responseHeaders.add("Content-Type", "application/json")
                it.sendResponseHeaders(code, bytes.size.toLong())
                it.responseBody.write(bytes)
            }
        }
        server.start()
        return server to "http://127.0.0.1:${server.address.port}/api"
    }

    @Test
    fun `Horde text discovery and the chat entry point apply worker limits and the selected model`() = runBlocking {
        val calls = CopyOnWriteArrayList<Call>()
        val polls = AtomicInteger()
        val (server, origin) = server { call ->
            calls += call
            when (call.path) {
                "/api/v2/status/models?type=text" -> 200 to """[{"name":"model-a","count":2},{"name":"model-b","count":1},{"name":"offline","count":0}]"""
                "/api/v2/workers?type=text" -> 200 to """[
                    {"trusted":true,"models":["model-a"],"max_context_length":4096,"max_length":256},
                    {"trusted":true,"models":["model-a"],"max_context_length":3072,"max_length":190},
                    {"trusted":false,"models":["model-a"],"max_context_length":1024,"max_length":100},
                    {"trusted":true,"accepts_prompt_jobs":false,"models":["model-a"],"max_context_length":256,"max_length":32},
                    {"trusted":true,"models":["model-b"],"max_context_length":2048,"max_length":128}]"""
                "/api/v2/generate/text/async" -> 202 to """{"id":"text-task"}"""
                "/api/v2/generate/text/status/text-task" -> if (polls.incrementAndGet() == 1) {
                    200 to """{"done":false,"is_possible":true,"queue_position":4,"wait_time":12}"""
                } else 200 to """{"done":true,"generations":[{"text":"列车到了。\nMio: phantom","worker_name":"A worker","model":"model-a"}]}"""
                else -> 404 to """{"message":"Unexpected endpoint"}"""
            }
        }
        try {
            val draft = Provider("h", "Horde", origin, defaultModel = HORDE_AUTO_MODEL,
                protocol = ApiProtocol.HORDE_TEXT,
                horde = HordeSettings(trustedOnly = true, adjustContext = true)).validated()
            val catalog = ModelService.discoverModels(draft)
            assertEquals(listOf(HORDE_AUTO_MODEL, "model-a", "model-b"), catalog.ids)
            assertEquals(mapOf("model-a" to 3072, "model-b" to 2048), catalog.contexts)
            val provider = draft.copy(models = catalog.ids, modelContexts = catalog.contexts,
                horde = draft.horde.copy(modelOutputs = requireNotNull(catalog.hordeOutputs)))
            assertEquals(provider, Provider.fromJson(provider.storageJson { it }) { it })
            assertEquals(128, hordeOutput(provider, HORDE_AUTO_MODEL, 400))
            val history = listOf(Message("user", "Mio", "列车到了吗？"))
            val character = Card("r", """{"name":"Rina","description":"A conductor."}""")
            val reply = StringBuilder()
            val progress = mutableListOf<String>()
            val budgets = mutableListOf<PromptBudgetUsage>()
            val result = ModelService.reply(Preferences(maxTokens = "500"), ModelConnection(provider, "model-a"),
                character, history, emptyList(), null, persona = PersonaProfile(name = "Mio"),
                onContextBudget = { budgets += it }, onQueueProgress = { progress += it }) { reply.append(it) }
            assertEquals("列车到了。", reply.toString())
            assertTrue(result.stoppedBySequence)
            assertEquals(3072, budgets.single().context)
            assertEquals(190, budgets.single().output)
            assertTrue(progress.any { "第 4 位" in it })
            assertEquals("", progress.last())
            val queued = calls.single { it.path.endsWith("/async") }
            assertEquals("0000000000", queued.key)
            val payload = requireNotNull(queued.body)
            assertEquals(listOf("model-a"), payload.getJSONArray("models").strings())
            assertTrue(payload.getBoolean("trusted_workers"))
            assertTrue(payload.getString("prompt").endsWith("Mio: 列车到了吗？\nRina:"))
            val params = payload.getJSONObject("params")
            assertEquals(3072, params.getInt("max_context_length"))
            assertEquals(190, params.getInt("max_length"))
            assertEquals(listOf("\nMio:"), params.getJSONArray("stop_sequence").strings())
            assertFalse(calls.any { it.method == "DELETE" })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `stopping a Horde submission deletes the eventual server job with the same key`() = runBlocking {
        val submitted = CompletableDeferred<Unit>()
        val releaseResponse = CountDownLatch(1)
        val calls = CopyOnWriteArrayList<Call>()
        val (server, origin) = server { call ->
            calls += call
            when {
                call.method == "POST" -> {
                    submitted.complete(Unit)
                    check(releaseResponse.await(5, TimeUnit.SECONDS))
                    202 to """{"id":"in-flight"}"""
                }
                call.method == "DELETE" -> 200 to """{"done":true}"""
                else -> 200 to """{"done":false,"waiting":1}"""
            }
        }
        try {
            val provider = Provider("h", "Horde", origin, "demo-key", HORDE_AUTO_MODEL, protocol = ApiProtocol.HORDE_TEXT)
            val job = async {
                Horde(provider, pollIntervalMs = 10).complete(
                    jsonOf("model" to HORDE_AUTO_MODEL, "messages" to JSONArray()), null, emptyList(), true, {}, {},
                )
            }
            withTimeout(5000) { submitted.await() }
            job.cancel()
            releaseResponse.countDown()
            withTimeout(5000) { job.join() }
            assertTrue(job.isCancelled)
            assertEquals(listOf("POST", "DELETE"), calls.map(Call::method))
            assertEquals("/api/v2/generate/text/status/in-flight", calls.last().path)
            assertEquals("demo-key", calls.last().key)
        } finally {
            releaseResponse.countDown()
            server.stop(0)
        }
    }

    @Test
    fun `Horde drawings and image descriptions use the native image and interrogation queues`() = runBlocking {
        val sent = CopyOnWriteArrayList<Call>()
        val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jB1sAAAAASUVORK5CYII="
        val (server, origin) = server { call ->
            sent += call
            when (call.path) {
                "/api/v2/status/models?type=image" -> 200 to """[{"name":"art-model","count":2}]"""
                "/api/v2/generate/async" -> 202 to """{"id":"draw-1"}"""
                "/api/v2/generate/check/draw-1" -> 200 to """{"done":true}"""
                "/api/v2/generate/status/draw-1" -> 200 to """{"done":true,"generations":[{"img":"$png","censored":false}]}"""
                "/api/v2/interrogate/async" -> 202 to """{"id":"describe-1"}"""
                "/api/v2/interrogate/status/describe-1" -> 200 to """{"state":"done","forms":[{"form":"caption","result":{"caption":"A violet sky"}}]}"""
                else -> 404 to "{}"
            }
        }
        try {
            val provider = Provider("h", "Horde", origin, defaultModel = HORDE_AUTO_MODEL, protocol = ApiProtocol.HORDE_TEXT)
            assertEquals(listOf(ImageProtocol.HORDE), ImageProtocol.entries.filter { it.available(provider) })
            val client = Horde(provider)
            assertEquals(listOf("art-model"), client.imageModels())
            val request = ImageRequest(provider, ImageProtocol.HORDE, "art-model", ImageShape.PORTRAIT, "anime city",
                tuning = ImageTuning(negative = "noise", seed = 123L, steps = 27))
            assertArrayEquals(Base64.getDecoder().decode(png), ImageService().generate(request) { null })
            assertEquals("A violet sky", client.caption("data:image/png;base64,$png"))
            val drawing = requireNotNull(sent.first { it.path == "/api/v2/generate/async" }.body)
            val description = requireNotNull(sent.first { it.path == "/api/v2/interrogate/async" }.body)
            assertEquals("anime city ### noise", drawing.getString("prompt"))
            assertEquals("123", drawing.getJSONObject("params").getString("seed"))
            assertEquals(768, drawing.getJSONObject("params").getInt("height"))
            assertFalse(drawing.getBoolean("r2"))
            assertEquals(png, description.getString("source_image"))
            assertEquals("caption", description.getJSONArray("forms").getJSONObject(0).getString("name"))
            val prefs = Preferences(providers = listOf(provider))
            val settings = CaptionSettings(source = CaptionSource.HORDE, hordeProviderId = "h")
            assertEquals(settings, CaptionSettings.from(settings.json()))
            assertEquals(provider, settings.hordeProvider(prefs))
            assertFalse(sent.any { it.method == "DELETE" })
        } finally {
            server.stop(0)
        }
    }
}

