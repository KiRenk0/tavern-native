package app.tavern.nativeclient

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProviderProtocolsWireTest {

    @Test
    fun `strict prompt processing is used on each tool round and inspected requests`() = runBlocking {
        val requests = mutableListOf<JSONObject>()
        val (server, base) = startProtocolTestServer { _, _, text ->
            requests += JSONObject(text)
            val payload = if (requests.size == 1) {
                """{"choices":[{"message":{"role":"assistant","tool_calls":[{"id":"call_1","type":"function","function":{"name":"Echo","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
            } else {
                """{"choices":[{"message":{"role":"assistant","content":"Done"},"finish_reason":"stop"}]}"""
            }
            200 to ("application/json" to payload)
        }
        try {
            val provider = Provider("custom", endpoint = base, defaultModel = "custom-model", streaming = false,
                requestOptions = ProviderRequestOptions(processing = PromptPostProcessing.STRICT_TOOLS))
            val connection = ModelConnection(provider, provider.defaultModel)
            val card = Card("r", jsonOf("name" to "Rina", "description" to "Guard").toString())
            val history = listOf(Message("assistant", "Rina", "Opening line"), Message("user", "Mio", "What time?"))
            val tool = ModelTool("Echo", "Echo", "Test tool", jsonOf("type" to "object")) { "The time is noon" }
            val turns = mutableListOf<ToolTurn>()
            val seen = mutableListOf<JSONArray>()
            val text = StringBuilder()
            ModelService.reply(Preferences(toolCalling = true), connection, card, history, emptyList(), null,
                tools = ReplyTools(tools = listOf(tool), record = { turns += it }), onPrompt = { seen += it }) { text.append(it) }
            assertEquals("Done", text.toString())
            assertEquals(1, turns.size)
            assertEquals(2, requests.size)
            val first = requests[0].getJSONArray("messages").objects()
            assertEquals(listOf("system", "user", "assistant", "user"), first.map { it.string("role") })
            assertEquals("[Start a new chat]", first[1].string("content"))
            val last = requests[1].getJSONArray("messages").objects()
            assertEquals("call_1", last.last().string("tool_call_id"))
            assertEquals("The time is noon", last.last().string("content"))
            val inspected = seen.last().objects()
            assertEquals(last.map { it.string("role") to it.string("content") },
                inspected.map { it.string("role") to it.string("content") })
            assertEquals("call_1", inspected.last().string("tool_call_id"))

            val noTools = provider.copy(requestOptions = ProviderRequestOptions(processing = PromptPostProcessing.SEMI))
            ModelService.reply(Preferences(toolCalling = true), ModelConnection(noTools, provider.defaultModel),
                card, history + Message("system", "Tool", "Echo", toolTurn = turns.single()), emptyList(), null,
                tools = ReplyTools(reload = { error("No-tools post-processing must not register tools") }, record = {})) {}
            assertFalse(requests.last().has("tools"))
            val converted = requests.last().getJSONArray("messages").objects()
            assertTrue(converted.any { it.string("role") == "user" && it.string("content").contains("The time is noon") })
            assertTrue(converted.none { it.has("tool_call_id") || it.has("tool_calls") })

            val stranger = Card("k", jsonOf("name" to "Kira").toString())
            val group = TavernGroup(name = "Group", memberIds = listOf(card.id, stranger.id))
            val single = provider.copy(requestOptions = ProviderRequestOptions(processing = PromptPostProcessing.SINGLE))
            ModelService.reply(Preferences(), ModelConnection(single, provider.defaultModel), card,
                listOf(Message("assistant", "Kira", "I am here.", characterId = stranger.id)),
                emptyList(), GroupPrompt(group, emptyList(), listOf("Rina", "Kira"))) {}
            val singleRow = requests.last().getJSONArray("messages").objects().single()
            assertEquals("user", singleRow.string("role"))
            assertTrue(singleRow.string("content").split("\n\n").contains("Kira: I am here."))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `custom provider applies header and body options on the real routes`() = runBlocking {
        val paths = mutableListOf<String>()
        val (server, base) = startProtocolTestServer { path, headers, text ->
            paths += path
            assertEquals("Bearer private", headers["authorization"])
            if (path == "/models") {
                200 to ("application/json" to """{"data":[{"id":"example"}]}""")
            } else {
                assertEquals("/chat/completions", path)
                assertEquals("application/json; profile=example", headers["content-type"])
                val body = JSONObject(text)
                assertEquals("example", body.string("model"))
                assertEquals(30, body.getInt("top_k"))
                assertFalse(body.has("temperature"))
                assertEquals("Mio", headers["x-persona"])
                assertEquals("Rina", body.getJSONObject("metadata").string("character"))
                200 to ("application/json" to """{"choices":[{"message":{"content":"Ready"},"finish_reason":"stop"}]}""")
            }
        }
        try {
            val provider = Provider("custom", endpoint = base, apiKey = "default", defaultModel = "example",
                requestOptions = ProviderRequestOptions(
                    "top_k: 30\nmetadata: {character: '{{char}}'}", "- temperature",
                    "Authorization: Bearer private\nContent-Type: application/json; profile=example\nX-Persona: '{{user}}'",
                ))
            assertEquals(listOf("example"), ModelService.models(provider))
            val result = StringBuilder()
            val persona = PersonaProfile(id = "mio", name = "Mio")
            ModelService.reply(Preferences(temperature = "0.7"), ModelConnection(provider, "example"),
                Card("r", jsonOf("name" to "Rina").toString()), listOf(Message("user", "Mio", "Testing")), emptyList(), null,
                persona = persona) { result.append(it) }
            assertEquals("Ready", result.toString())
            assertEquals(listOf("/models", "/chat/completions"), paths)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `provider errors redact custom credentials`() = runBlocking {
        val (server, base) = startProtocolTestServer { _, _, _ ->
            403 to ("application/json" to """{"error":{"message":"Rejected private-auth and private-query"}}""")
        }
        try {
            val provider = Provider("custom", endpoint = base, defaultModel = "example",
                requestOptions = ProviderRequestOptions("api_key: private-query", includeHeaders = "X-Api-Key: private-auth"))
            val failure = runCatching {
                ModelService.utility(ModelConnection(provider, "example"), JSONArray(), maxTokens = null)
            }.exceptionOrNull()
            assertTrue(failure is HttpRequestError)
            assertFalse(failure.toString().contains("private-auth") || failure.toString().contains("private-query"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `DeepSeek preset continuation uses beta then retries with previous response intact`() = runBlocking {
        val paths = mutableListOf<String>()
        val bodies = mutableListOf<JSONObject>()
        val (server, base) = startProtocolTestServer { path, _, body ->
            paths += path
            bodies += JSONObject(body)
            if (path.startsWith("/beta/")) {
                400 to ("application/json" to """{"error":{"message":"This model does not support assistant message prefill."}}""")
            } else {
                200 to ("application/json" to """{"choices":[{"message":{"content":"New part"},"finish_reason":"stop"}]}""")
            }
        }
        try {
            val provider = Provider("deepseek", endpoint = base, defaultModel = "deepseek-flash",
                template = ProviderTemplate.DEEPSEEK, contextLimit = 2000, streaming = true)
            val card = Card("n", jsonOf("name" to "Rina").toString())
            val history = listOf(Message("user", "Mio", "Start"), Message("assistant", "Rina", "Earlier part"))
            val preset = PromptPreset(name = "Continue", continuePrefill = true,
                utilities = mapOf(PresetUtility.CONTINUE to "Keep going"),
                chatCompletion = ChatCompletionPreset(ChatSpeakerNames.API_FIELD, ContinueSpacing.LINE, 1200, false))
            val reply = StringBuilder()
            var retries = 0
            var measured: PromptBudgetUsage? = null
            ModelService.reply(Preferences(maxTokens = "100"), ModelConnection(provider, provider.defaultModel), card, history,
                emptyList(), null, trigger = "continue", preset = preset, onRetry = { retries++ },
                onContextBudget = { measured = it }) { reply.append(it) }
            assertEquals("New part", reply.toString())
            assertEquals(listOf("/beta/chat/completions", "/chat/completions"), paths)
            assertEquals(1, retries)
            assertEquals(1100, requireNotNull(measured).limit)
            assertTrue(bodies.none { it.getBoolean("stream") })
            val prefix = bodies[0].getJSONArray("messages").objects().last()
            assertEquals("assistant", prefix.getString("role"))
            assertEquals("Rina", prefix.getString("name"))
            assertEquals("Earlier part\n", prefix.getString("content"))
            assertTrue(prefix.getBoolean("prefix"))
            val fallback = bodies[1].getJSONArray("messages").objects()
            assertEquals("Keep going", fallback.last().getString("content"))
            assertEquals("user", fallback.last().getString("role"))
            assertFalse(fallback.any { it.has("prefix") })
            assertEquals(1, fallback.count { it.string("content").trimEnd() == "Earlier part" })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `anthropic native handles prompt caching thinking prefill sse and model discovery`() = runBlocking {
        var capturedPath = ""
        var capturedHeaders = emptyMap<String, String>()
        var capturedBody = JSONObject()
        val (server, baseUrl) = startProtocolTestServer { path, headers, body ->
            capturedPath = path
            capturedHeaders = headers
            if (path == "/v1/models") {
                200 to ("application/json" to JSONObject().put("data", JSONArray().put(jsonOf("id" to "claude-sonnet-4-5", "max_tokens" to 200000))).toString())
            } else {
                capturedBody = JSONObject(body)
                val sse = buildString {
                    append("event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"m1\",\"usage\":{\"input_tokens\":13,\"cache_creation_input_tokens\":2,\"cache_read_input_tokens\":7,\"output_tokens\":1}}}\n\n")
                    append("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"Plan A\"}}\n\n")
                    append("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello \"}}\n\n")
                    append("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"World\"}}\n\n")
                    append("event: message_delta\ndata: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":12}}\n\n")
                    append("event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":25}}\n\n")
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
            assertEquals(ResponseTokens(22, 25), result.usage)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `gemini native sends safety settings thinking budget context cache and parses sse`() = runBlocking {
        ProviderProtocols.clearCachesForTest()
        var cacheCreated = false
        var generateBody = JSONObject()
        val (server, baseUrl) = startProtocolTestServer { path, headers, body ->
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
                    val usage = jsonOf("usageMetadata" to jsonOf("promptTokenCount" to 47, "toolUsePromptTokenCount" to 3,
                        "candidatesTokenCount" to 29, "thoughtsTokenCount" to 7))
                    200 to ("text/event-stream" to "data: $chunk1\n\ndata: $chunk2\n\ndata: $usage\n\n")
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
            assertEquals(ResponseTokens(50, 36, 7), result.usage)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `openrouter ollama and kobold protocols execute and parse cleanly`() = runBlocking {
        var orHeaders = emptyMap<String, String>()
        var orBody = JSONObject()
        val (server, baseUrl) = startProtocolTestServer { path, headers, body ->
            when (path) {
                "/or/chat/completions" -> {
                    orHeaders = headers
                    orBody = JSONObject(body)
                    200 to ("application/json" to jsonOf(
                        "choices" to JSONArray().put(jsonOf(
                            "message" to jsonOf("role" to "assistant", "content" to "OR ok"),
                            "finish_reason" to "stop",
                        )),
                        "usage" to jsonOf("prompt_tokens" to 140, "completion_tokens" to 30,
                            "completion_tokens_details" to jsonOf("reasoning_tokens" to 20)),
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
                        {"message":{"role":"assistant","content":"Ollama ok","thinking":""},"done":true,"done_reason":"stop","prompt_eval_count":30,"eval_count":16}
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
            val orReply = ModelService.complete(
                orProvider,
                jsonOf("model" to "anthropic/claude-sonnet-4.5", "messages" to JSONArray().put(jsonOf("role" to "user", "content" to "hi")), "stream" to false),
                {}, { orOut.append(it) },
            )
            assertEquals("OR ok", orOut.toString())
            assertEquals(ResponseTokens(140, 30, 20), orReply.usage)
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
            val ollamaReply = ModelService.complete(
                ollamaProvider,
                jsonOf("model" to "qwen2.5:14b", "messages" to JSONArray().put(jsonOf("role" to "user", "content" to "hi")), "stream" to true),
                { ollReason.append(it) }, { ollOut.append(it) },
            )
            assertEquals("hm", ollReason.toString())
            assertEquals("Ollama ok", ollOut.toString())
            assertEquals(ResponseTokens(30, 16), ollamaReply.usage)

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
