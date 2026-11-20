package app.tavern.nativeclient

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class ServicesProtocolTest {

    private fun startServer(handler: (path: String, headers: Map<String, String>, body: String) -> Pair<Int, Pair<String, String>>): Pair<HttpServer, String> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val reqBody = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val headers = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") }
            val path = ex.requestURI.rawPath + (ex.requestURI.rawQuery?.let { "?$it" } ?: "")
            val (code, payload) = handler(path, headers, reqBody)
            val (contentType, bodyText) = payload
            val bytes = bodyText.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", contentType)
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server to "http://127.0.0.1:${server.address.port}"
    }

    @Test
    fun `anthropic native handles prompt caching thinking prefill sse and model discovery`() = runBlocking {
        var capturedPath = ""
        var capturedHeaders = emptyMap<String, String>()
        var capturedBody = JSONObject()
        val (server, baseUrl) = startServer { path, headers, body ->
            capturedPath = path
            capturedHeaders = headers
            if (path == "/v1/models") {
                200 to ("application/json" to JSONObject().put("data", JSONArray().put(jsonOf("id" to "claude-sonnet-4-5", "max_tokens" to 200000))).toString())
            } else {
                capturedBody = JSONObject(body)
                val sse = buildString {
                    append("event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"m1\"}}\n\n")
                    append("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"Plan A\"}}\n\n")
                    append("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello \"}}\n\n")
                    append("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"World\"}}\n\n")
                    append("event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n\n")
                    append("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n")
                }
                200 to ("text/event-stream; charset=utf-8" to sse)
            }
        }
        try {
            val provider = Provider(
                id = "anth",
                name = "Claude",
                endpoint = baseUrl,
                apiKey = "sk-ant-test",
                defaultModel = "claude-sonnet-4-5",
                protocol = ApiProtocol.ANTHROPIC,
                promptCaching = true,
                thinkingBudget = 2048,
                streaming = true,
            )
            val discovered = ModelService.discoverModels(provider)
            assertEquals(listOf("claude-sonnet-4-5"), discovered.ids)
            assertEquals(200000, discovered.contexts["claude-sonnet-4-5"])

            val messages = JSONArray()
                .put(jsonOf("role" to "system", "content" to "You are Aria."))
                .put(jsonOf("role" to "user", "content" to "Hi"))
                .put(jsonOf("role" to "assistant", "content" to "*Aria smiles* "))
            val payload = jsonOf("model" to "claude-sonnet-4-5", "messages" to messages, "stream" to true, "temperature" to 0.7)
            val reasoning = StringBuilder()
            val output = StringBuilder()
            val result = ModelService.complete(provider, payload, { reasoning.append(it) }, { output.append(it) })

            assertEquals("/v1/messages", capturedPath)
            assertEquals("sk-ant-test", capturedHeaders["x-api-key"])
            assertEquals("2023-06-01", capturedHeaders["anthropic-version"])
            assertEquals("prompt-caching-2024-07-31", capturedHeaders["anthropic-beta"])
            assertFalse(capturedBody.has("temperature")) // omitted when thinking enabled
            assertEquals(2048, capturedBody.getJSONObject("thinking").getInt("budget_tokens"))
            val sysBlock = capturedBody.getJSONArray("system").getJSONObject(0)
            assertEquals("ephemeral", sysBlock.getJSONObject("cache_control").getString("type"))
            val sentMsgs = capturedBody.getJSONArray("messages")
            assertEquals("assistant", sentMsgs.getJSONObject(sentMsgs.length() - 1).getString("role"))
            assertEquals("ephemeral", sentMsgs.getJSONObject(0).getJSONArray("content").getJSONObject(0).getJSONObject("cache_control").getString("type"))

            assertEquals("Plan A", reasoning.toString())
            assertEquals("Hello World", output.toString())
            assertEquals("stop", result.finishReason)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `gemini native sends safety settings thinking budget context cache and parses sse`() = runBlocking {
        ProviderProtocols.clearCachesForTest()
        var cacheCreated = false
        var generateBody = JSONObject()
        val (server, baseUrl) = startServer { path, headers, body ->
            assertEquals("gem-key", headers["x-goog-api-key"])
            when {
                path == "/v1beta/cachedContents" -> {
                    cacheCreated = true
                    200 to ("application/json" to jsonOf("name" to "cachedContents/abc123").toString())
                }
                path.contains(":streamGenerateContent") -> {
                    generateBody = JSONObject(body)
                    val chunk1 = jsonOf("candidates" to JSONArray().put(jsonOf(
                        "content" to jsonOf("parts" to JSONArray().put(jsonOf("thought" to true, "text" to "Think."))),
                    )))
                    val chunk2 = jsonOf("candidates" to JSONArray().put(jsonOf(
                        "content" to jsonOf("parts" to JSONArray().put(jsonOf("text" to "Reply!"))),
                        "finishReason" to "STOP",
                    )))
                    200 to ("text/event-stream" to "data: $chunk1\n\ndata: $chunk2\n\n")
                }
                else -> 404 to ("application/json" to "{}")
            }
        }
        try {
            val provider = Provider(
                id = "gem",
                name = "Gemini",
                endpoint = "$baseUrl/v1beta",
                apiKey = "gem-key",
                defaultModel = "gemini-2.5-pro",
                protocol = ApiProtocol.GEMINI_NATIVE,
                promptCaching = true,
                thinkingBudget = 1024,
                geminiSafety = GeminiSafetyLevel.BLOCK_NONE,
                streaming = true,
            )
            val longSystem = "World lore entry. ".repeat(600) // > 8,000 chars triggers context cache
            val messages = JSONArray()
                .put(jsonOf("role" to "system", "content" to longSystem))
                .put(jsonOf("role" to "user", "content" to "Hello"))
            val payload = jsonOf("model" to "gemini-2.5-pro", "messages" to messages, "stream" to true)
            val reasoning = StringBuilder()
            val output = StringBuilder()
            val result = ModelService.complete(provider, payload, { reasoning.append(it) }, { output.append(it) })

            assertTrue(cacheCreated)
            assertEquals("cachedContents/abc123", generateBody.getString("cachedContent"))
            assertFalse(generateBody.has("systemInstruction"))
            assertEquals("BLOCK_NONE", generateBody.getJSONArray("safetySettings").getJSONObject(0).getString("threshold"))
            assertEquals(1024, generateBody.getJSONObject("generationConfig").getJSONObject("thinkingConfig").getInt("thinkingBudget"))
            assertEquals("Think.", reasoning.toString())
            assertEquals("Reply!", output.toString())
            assertEquals("stop", result.finishReason)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `openrouter ollama and kobold protocols execute and parse cleanly`() = runBlocking {
        var orHeaders = emptyMap<String, String>()
        var orBody = JSONObject()
        val (server, baseUrl) = startServer { path, headers, body ->
            when (path) {
                "/or/chat/completions" -> {
                    orHeaders = headers
                    orBody = JSONObject(body)
                    200 to ("application/json" to jsonOf(
                        "choices" to JSONArray().put(jsonOf(
                            "message" to jsonOf("role" to "assistant", "content" to "OR ok"),
                            "finish_reason" to "stop",
                        )),
                    ).toString())
                }
                "/ollama/api/tags" -> {
                    200 to ("application/json" to jsonOf(
                        "models" to JSONArray().put(jsonOf("name" to "qwen2.5:14b")),
                    ).toString())
                }
                "/ollama/api/chat" -> {
                    val ndjson = """
                        {"message":{"role":"assistant","content":"","thinking":"hm"},"done":false}
                        {"message":{"role":"assistant","content":"Ollama ok","thinking":""},"done":true,"done_reason":"stop"}
                    """.trimIndent() + "\n"
                    200 to ("application/x-ndjson" to ndjson)
                }
                "/kobold/api/v1/generate" -> {
                    200 to ("application/json" to jsonOf(
                        "results" to JSONArray().put(jsonOf("text" to "Kobold ok")),
                    ).toString())
                }
                else -> 404 to ("application/json" to "{}")
            }
        }
        try {
            val orProvider = Provider(
                id = "or",
                name = "OpenRouter",
                endpoint = "$baseUrl/or",
                apiKey = "sk-or",
                defaultModel = "anthropic/claude-sonnet-4.5",
                protocol = ApiProtocol.OPENROUTER,
                openRouterProviders = listOf("anthropic", "google-vertex"),
                openRouterAllowFallbacks = false,
                openRouterReasoningEffort = "medium",
                streaming = false,
            )
            val orOut = StringBuilder()
            ModelService.complete(
                orProvider,
                jsonOf("model" to "anthropic/claude-sonnet-4.5", "messages" to JSONArray().put(jsonOf("role" to "user", "content" to "hi")), "stream" to false),
                {}, { orOut.append(it) },
            )
            assertEquals("OR ok", orOut.toString())
            assertEquals("Tavern Native", orHeaders["x-title"])
            assertFalse(orBody.getJSONObject("provider").getBoolean("allow_fallbacks"))
            assertEquals("medium", orBody.getJSONObject("reasoning").getString("effort"))

            val ollamaProvider = Provider(
                id = "oll",
                name = "Ollama",
                endpoint = "$baseUrl/ollama",
                defaultModel = "qwen2.5:14b",
                protocol = ApiProtocol.OLLAMA,
                streaming = true,
            )
            assertEquals(listOf("qwen2.5:14b"), ModelService.discoverModels(ollamaProvider).ids)
            val ollReason = StringBuilder()
            val ollOut = StringBuilder()
            ModelService.complete(
                ollamaProvider,
                jsonOf("model" to "qwen2.5:14b", "messages" to JSONArray().put(jsonOf("role" to "user", "content" to "hi")), "stream" to true),
                { ollReason.append(it) }, { ollOut.append(it) },
            )
            assertEquals("hm", ollReason.toString())
            assertEquals("Ollama ok", ollOut.toString())

            val koboldProvider = Provider(
                id = "kob",
                name = "Kobold",
                endpoint = "$baseUrl/kobold",
                defaultModel = "local",
                protocol = ApiProtocol.KOBOLD_TEXT,
                streaming = false,
            )
            val kobOut = StringBuilder()
            ModelService.complete(
                koboldProvider,
                jsonOf("model" to "local", "messages" to JSONArray().put(jsonOf("role" to "user", "content" to "hi")), "stream" to false),
                {}, { kobOut.append(it) },
            )
            assertEquals("Kobold ok", kobOut.toString())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `connection profile resolution applies profile connection and sampling cleanly`() {
        val p1 = Provider("p1", "OpenAI", "https://api.example.com/v1", "k1", "gpt-4o", protocol = ApiProtocol.OPENAI_COMPATIBLE)
        val p2 = Provider("p2", "Claude", "https://api.anthropic.com", "k2", "claude-sonnet-4-5", protocol = ApiProtocol.ANTHROPIC)
        val profile = ConnectionProfile(
            id = "prof1",
            name = "Claude RP",
            providerId = "p2",
            model = "claude-sonnet-4-5",
            presetId = "preset-rp",
            temperature = "0.85",
            maxTokens = "2048",
            topP = "0.95",
            includeReasoning = true,
        )
        val prefs = Preferences(
            temperature = "0.5",
            maxTokens = "1024",
            includeReasoning = false,
            providers = listOf(p1, p2),
            defaultSelection = ModelSelection("p1", "gpt-4o"),
            connectionProfiles = listOf(profile),
            activeProfileId = "prof1",
        )
        val chatDefault = Chat("c1", "card1", "Solo")
        val cfg1 = prefs.effectiveConfig(chatDefault)
        assertNotNull(cfg1.connection)
        assertEquals("claude-sonnet-4-5", cfg1.connection?.model)
        assertEquals("preset-rp", cfg1.presetId)
        assertEquals("0.85", cfg1.sampling.temperature)
        assertEquals("2048", cfg1.sampling.maxTokens)
        assertEquals("0.95", cfg1.sampling.topP)
        assertTrue(cfg1.sampling.includeReasoning)

        // Explicit chat model override keeps model priority while profile sampling/preset apply
        val chatOverride = chatDefault.copy(modelOverride = ModelSelection("p1", "gpt-4o"))
        assertEquals("gpt-4o", prefs.effectiveConfig(chatOverride).connection?.model)

        // Clearing profile on chat falls back to global default
        val chatCleared = chatDefault.copy(options = ConversationOptions(connectionProfileId = ""))
        val cfg3 = prefs.effectiveConfig(chatCleared)
        assertEquals("gpt-4o", cfg3.connection?.model)
        assertEquals("0.5", cfg3.sampling.temperature)
        assertFalse(cfg3.sampling.includeReasoning)
    }
}
