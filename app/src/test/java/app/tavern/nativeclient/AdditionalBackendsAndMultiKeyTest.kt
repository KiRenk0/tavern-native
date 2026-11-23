package app.tavern.nativeclient

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

class AdditionalBackendsAndMultiKeyTest {

    @Before
    fun resetRotator() {
        MultiKeyManager.clearAll()
    }

    private fun startServer(
        handler: (method: String, path: String, headers: Map<String, String>, body: String) -> Pair<Int, Pair<String, String>>,
    ): Pair<HttpServer, String> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val reqBody = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val headers = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") }
            val path = ex.requestURI.rawPath + (ex.requestURI.rawQuery?.let { "?$it" } ?: "")
            val (code, payload) = handler(ex.requestMethod, path, headers, reqBody)
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
    fun `all 9 text completion backends build valid payloads and map backend-specific samplers`() {
        val richSampler = jsonOf(
            TextSamplerField.TOP_K.key to 40,
            TextSamplerField.MIN_P.key to 0.05,
            TextSamplerField.TOP_A.key to 0.1,
            TextSamplerField.TFS.key to 0.9,
            TextSamplerField.TYPICAL.key to 0.95,
            TextSamplerField.REPETITION.key to 1.12,
            TextSamplerField.REPETITION_RANGE.key to 1024,
            TextSamplerField.FREQUENCY.key to 0.2,
            TextSamplerField.PRESENCE.key to 0.15,
            TextSamplerField.SMOOTHING.key to 0.3,
            TextSamplerField.MIROSTAT.key to 2,
            TextSamplerField.MIROSTAT_TAU.key to 5.0,
            TextSamplerField.MIROSTAT_ETA.key to 0.1,
            TextSamplerField.DRY_MULTIPLIER.key to 0.8,
            TextSamplerField.DRY_BASE.key to 1.75,
            TextSamplerField.DRY_LENGTH.key to 2,
            TextSamplerField.DRY_RANGE.key to 512,
            TextSamplerField.DRY_BREAKERS.key to JSONArray(listOf("\n", ":")),
            TextSamplerField.XTC_THRESHOLD.key to 0.1,
            TextSamplerField.XTC_PROBABILITY.key to 0.5,
            TextSamplerField.NSIGMA.key to 1.5,
            TextSamplerField.IGNORE_EOS.key to true,
            TextSamplerField.SEED.key to 42,
            TextSamplerField.GRAMMAR.key to "root ::= \"yes\"",
            TextSamplerField.JSON_SCHEMA.key to jsonOf("type" to "object"),
            TextSamplerField.DYNAMIC.key to true,
            TextSamplerField.MIN_TEMP.key to 0.4,
            TextSamplerField.MAX_TEMP.key to 1.4,
            TextSamplerField.DYNAMIC_EXPONENT.key to 1.2,
        )
        val basePayload = jsonOf(
            "model" to "test-model",
            "max_tokens" to 128,
            "temperature" to 0.8,
            "top_p" to 0.92,
            "stream" to true,
            TEXT_SAMPLER_PAYLOAD to richSampler,
        )

        val expectedBackends = listOf(
            ApiProtocol.TABBY_TEXT,
            ApiProtocol.VLLM_TEXT,
            ApiProtocol.APHRODITE_TEXT,
            ApiProtocol.OOBABOOGA_TEXT,
            ApiProtocol.FEATHERLESS_TEXT,
            ApiProtocol.INFERMATIC_TEXT,
            ApiProtocol.DREAMGEN_TEXT,
            ApiProtocol.MANCER_TEXT,
            ApiProtocol.HUGGINGFACE_TEXT,
        )
        assertEquals(9, AdditionalTextBackends.allSpecs.size)
        for (protocol in expectedBackends) {
            val spec = requireNotNull(AdditionalTextBackends.specFor(protocol))
            val provider = AdditionalTextBackends.createProviderDraft(spec).copy(apiKey = "secret-token")
            assertTrue("usesTextCompletion should be true for $protocol", provider.usesTextCompletion)
            assertTrue("supportsTextSampling should be true for $protocol", provider.supportsTextSampling)
            val (url, body) = AdditionalTextBackends.buildCompletionRequest(
                provider, basePayload, "Hello prompt", listOf("\nUser:"), sendStops = true,
            )
            if (protocol == ApiProtocol.HUGGINGFACE_TEXT) {
                assertTrue(url.contains("/models/test-model"))
                assertEquals("Hello prompt", body.getString("inputs"))
                val params = body.getJSONObject("parameters")
                assertEquals(128, params.getInt("max_new_tokens"))
                assertEquals(40, params.getInt("top_k"))
                assertEquals(1.12, params.getDouble("repetition_penalty"), 1e-6)
                assertEquals(listOf("\nUser:"), params.getJSONArray("stop").strings())
            } else {
                assertTrue("Expected /completions for $protocol, got $url", url.endsWith("/completions"))
                assertEquals("Hello prompt", body.getString("prompt"))
                assertEquals(128, body.getInt("max_tokens"))
                assertEquals(40, body.getInt("top_k"))
                assertEquals(0.05, body.getDouble("min_p"), 1e-6)
                assertEquals(1.12, body.getDouble("repetition_penalty"), 1e-6)
                assertEquals(listOf("\nUser:"), body.getJSONArray("stop").strings())
            }
        }

        // Verify backend-specific field names.
        val tabbyBody = AdditionalTextBackends.buildCompletionRequest(
            Provider("t", endpoint = "http://127.0.0.1:5000/v1", protocol = ApiProtocol.TABBY_TEXT),
            basePayload, "p", emptyList(), false,
        ).second
        assertEquals(0.95, tabbyBody.getDouble("typical"), 1e-6)
        assertTrue(tabbyBody.getBoolean("ban_eos_token"))
        assertEquals(0.4, tabbyBody.getDouble("min_temp"), 1e-6)
        assertEquals(1.4, tabbyBody.getDouble("max_temp"), 1e-6)
        assertEquals(512, tabbyBody.getInt("dry_range"))

        val vllmBody = AdditionalTextBackends.buildCompletionRequest(
            Provider("v", endpoint = "http://127.0.0.1:8000/v1", protocol = ApiProtocol.VLLM_TEXT),
            basePayload, "p", emptyList(), false,
        ).second
        assertTrue(vllmBody.getBoolean("ignore_eos"))
        assertEquals("object", vllmBody.getJSONObject("guided_json").getString("type"))
        assertEquals("root ::= \"yes\"", vllmBody.getString("guided_grammar"))

        val aphroditeBody = AdditionalTextBackends.buildCompletionRequest(
            Provider("a", endpoint = "http://127.0.0.1:2242/v1", protocol = ApiProtocol.APHRODITE_TEXT),
            basePayload, "p", emptyList(), false,
        ).second
        assertEquals(0.4, aphroditeBody.getDouble("dynatemp_min"), 1e-6)
        assertEquals(1.4, aphroditeBody.getDouble("dynatemp_max"), 1e-6)
        assertEquals(0.8, aphroditeBody.getDouble("dry_multiplier"), 1e-6)
        assertEquals(0.5, aphroditeBody.getDouble("xtc_probability"), 1e-6)

        val oobaBody = AdditionalTextBackends.buildCompletionRequest(
            Provider("o", endpoint = "http://127.0.0.1:5000/v1", protocol = ApiProtocol.OOBABOOGA_TEXT),
            basePayload, "p", emptyList(), false,
        ).second
        assertTrue(oobaBody.getBoolean("dynamic_temperature"))
        assertEquals(0.4, oobaBody.getDouble("dynatemp_low"), 1e-6)
        assertEquals(1.4, oobaBody.getDouble("dynatemp_high"), 1e-6)
        assertEquals(1.5, oobaBody.getDouble("top_n_sigma"), 1e-6)
        assertEquals("root ::= \"yes\"", oobaBody.getString("grammar_string"))

        val infermaticBody = AdditionalTextBackends.buildCompletionRequest(
            Provider("i", endpoint = "https://api.totalgpt.ai/v1", protocol = ApiProtocol.INFERMATIC_TEXT),
            basePayload, "p", emptyList(), false,
        ).second
        assertEquals(0.9, infermaticBody.getDouble("tfs_z"), 1e-6)
        assertTrue(infermaticBody.getBoolean("ignore_eos"))

        val mancerBody = AdditionalTextBackends.buildCompletionRequest(
            Provider("m", endpoint = "https://neuro.mancer.tech/oai/v1", protocol = ApiProtocol.MANCER_TEXT),
            basePayload, "p", emptyList(), false,
        ).second
        assertEquals(0.9, mancerBody.getDouble("temperature"), 1e-6)
        assertEquals(0.5, mancerBody.getDouble("dynatemp_range"), 1e-6)
    }

    @Test
    fun `HuggingFace TGI streaming, non-streaming, and model info discovery work end to end`() = runBlocking {
        val requests = mutableListOf<Pair<String, JSONObject>>()
        val (server, base) = startServer { method, path, headers, body ->
            assertEquals("Bearer hf-secret", headers["authorization"])
            when {
                method == "GET" && path == "/info" ->
                    200 to ("application/json" to """{"model_id":"mistralai/Mistral-Nemo-Instruct-2407","max_total_tokens":32768}""")
                path == "/generate" -> {
                    requests += path to JSONObject(body)
                    200 to ("application/json" to """[{"generated_text":"Hello from HF!"}]""")
                }
                path == "/generate_stream" -> {
                    requests += path to JSONObject(body)
                    val sse = listOf(
                        """{"token":{"id":10,"text":"Hello ","special":false},"generated_text":null,"details":null}""",
                        """{"token":{"id":11,"text":"TGI!","special":false},"generated_text":null,"details":null}""",
                        """{"token":{"id":2,"text":"</s>","special":true},"generated_text":"Hello TGI!","details":{"finish_reason":"eos_token","generated_tokens":2}}""",
                    ).joinToString("") { "data: $it\n\n" }
                    200 to ("text/event-stream" to sse)
                }
                else -> 404 to ("application/json" to """{"error":"not found"}""")
            }
        }
        try {
            val provider = Provider(
                id = "hf",
                name = "TGI",
                endpoint = base,
                apiKey = "hf-secret",
                defaultModel = "mistralai/Mistral-Nemo-Instruct-2407",
                protocol = ApiProtocol.HUGGINGFACE_TEXT,
                streaming = false,
            )
            val discovered = ModelService.discoverModels(provider)
            assertEquals(listOf("mistralai/Mistral-Nemo-Instruct-2407"), discovered.ids)
            assertEquals(32768, discovered.contexts["mistralai/Mistral-Nemo-Instruct-2407"])

            val card = Card("c1", jsonOf("name" to "Rina").toString())
            val nonStreamOut = StringBuilder()
            ModelService.reply(
                Preferences(),
                ModelConnection(provider, provider.defaultModel),
                card,
                listOf(Message("user", "Mio", "Hi")),
                emptyList(),
                null,
            ) { nonStreamOut.append(it) }
            assertEquals("Hello from HF!", nonStreamOut.toString())

            val streamOut = StringBuilder()
            val streamResult = ModelService.reply(
                Preferences(),
                ModelConnection(provider.copy(streaming = true), provider.defaultModel),
                card,
                listOf(Message("user", "Mio", "Hi")),
                emptyList(),
                null,
            ) { streamOut.append(it) }
            assertEquals("Hello TGI!", streamOut.toString())
            assertEquals(2L, streamResult.usage.tokens.output)
            assertEquals(listOf("/generate", "/generate_stream"), requests.map { it.first })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `TabbyAPI model control loads, unloads, inspects status, lists models, and tokenizes`() = runBlocking {
        var loaded: String? = null
        var loadedMaxSeq: Int? = null
        var loadedCache: String? = null
        val capturedCalls = mutableListOf<Triple<String, String, Map<String, String>>>()

        val (server, base) = startServer { method, path, headers, body ->
            capturedCalls += Triple(method, path, headers)
            when (path) {
                "/v1/model" -> {
                    if (loaded == null) 404 to ("application/json" to """{"detail":"No model loaded"}""")
                    else 200 to ("application/json" to jsonOf(
                        "id" to loaded,
                        "parameters" to jsonOf("max_seq_len" to loadedMaxSeq, "cache_mode" to loadedCache),
                    ).toString())
                }
                "/v1/model/list" ->
                    200 to ("application/json" to """{"data":[{"id":"Mistral-Nemo-EXL2"},{"id":"Qwen2.5-32B-EXL2"}]}""")
                "/v1/model/load" -> {
                    val req = JSONObject(body)
                    loaded = req.getString("model_name")
                    loadedMaxSeq = req.optInt("max_seq_len", 8192)
                    loadedCache = req.optString("cache_mode", "FP16")
                    200 to ("application/json" to """{"status":"finished"}""")
                }
                "/v1/model/unload" -> {
                    loaded = null
                    loadedMaxSeq = null
                    loadedCache = null
                    200 to ("application/json" to """{"status":"unloaded"}""")
                }
                "/v1/token/encode" -> {
                    val req = JSONObject(body)
                    assertEquals("hello tabby", req.getString("text"))
                    200 to ("application/json" to """{"tokens":[101,202,303],"length":3}""")
                }
                else -> 404 to ("application/json" to """{"detail":"unknown"}""")
            }
        }
        try {
            val provider = Provider(
                id = "tabby",
                name = "Tabby",
                endpoint = "$base/v1",
                apiKey = "tabby-admin-secret",
                defaultModel = "default",
                protocol = ApiProtocol.TABBY_TEXT,
            )

            val models = TabbyModelClient.listModels(provider)
            assertEquals(listOf("Mistral-Nemo-EXL2", "Qwen2.5-32B-EXL2"), models)

            val loadReq = TabbyLoadRequest(
                modelName = "Qwen2.5-32B-EXL2",
                maxSeqLen = 16384,
                cacheMode = "Q6",
                gpuSplitAuto = false,
                gpuSplit = listOf(12f, 12f),
                ropeScale = 1.5,
                chunkSize = 2048,
            )
            val builtPayload = TabbyModelClient.buildLoadPayload(loadReq)
            assertEquals("Qwen2.5-32B-EXL2", builtPayload.getString("model_name"))
            assertEquals(16384, builtPayload.getInt("max_seq_len"))
            assertEquals("Q6", builtPayload.getString("cache_mode"))
            assertFalse(builtPayload.getBoolean("gpu_split_auto"))
            assertEquals(2, builtPayload.getJSONArray("gpu_split").length())

            val loadMsg = TabbyModelClient.loadModel(provider, loadReq)
            assertEquals("finished", loadMsg)

            val status = TabbyModelClient.fetchStatus(provider)
            assertEquals("Qwen2.5-32B-EXL2", status.loadedModel)
            assertEquals(16384, status.maxSeqLen)
            assertEquals("Q6", status.cacheMode)

            val discovered = ModelService.discoverModels(provider)
            assertEquals(listOf("Mistral-Nemo-EXL2", "Qwen2.5-32B-EXL2"), discovered.ids)
            assertEquals(16384, discovered.contexts["Qwen2.5-32B-EXL2"])

            val tokenPreview = ProviderProtocols.inspectRemoteTokens(ModelConnection(provider, "Qwen2.5-32B-EXL2"), "hello tabby")
            assertEquals(3, tokenPreview.count)
            assertEquals(listOf(101, 202, 303), tokenPreview.pieces.map(TokenPiece::id))

            val unloadMsg = TabbyModelClient.unloadModel(provider)
            assertEquals("unloaded", unloadMsg)
            assertNull(loaded)

            // Verify admin headers were sent on load/unload.
            val loadHeaders = capturedCalls.first { it.second == "/v1/model/load" }.third
            assertEquals("tabby-admin-secret", loadHeaders["x-admin-key"])
            assertEquals("tabby-admin-secret", loadHeaders["x-api-key"])
            assertEquals("Bearer tabby-admin-secret", loadHeaders["authorization"])
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `vLLM and Oobabooga completions and remote tokenizers work end to end`() = runBlocking {
        val paths = mutableListOf<String>()
        val (server, base) = startServer { _, path, _, body ->
            paths += path
            when (path) {
                "/v1/completions" -> {
                    val req = JSONObject(body)
                    val reply = if (req.optBoolean("stream")) {
                        val chunk = """{"choices":[{"text":"Streamed OK","finish_reason":"stop"}]}"""
                        return@startServer 200 to ("text/event-stream" to "data: $chunk\n\ndata: [DONE]\n\n")
                    } else {
                        """{"choices":[{"text":"Sync OK","finish_reason":"stop"}]}"""
                    }
                    200 to ("application/json" to reply)
                }
                "/tokenize" -> 200 to ("application/json" to """{"tokens":[1,2,3,4],"count":4}""")
                "/v1/internal/encode" -> 404 to ("application/json" to """{"error":"no encode"}""")
                "/v1/internal/token-count" -> 200 to ("application/json" to """{"length":7}""")
                else -> 404 to ("application/json" to """{"error":"missing"}""")
            }
        }
        try {
            val vllm = Provider("vllm", "vLLM", "$base/v1", defaultModel = "llama", protocol = ApiProtocol.VLLM_TEXT, streaming = true)
            val ooba = Provider("ooba", "Ooba", "$base/v1", defaultModel = "llama", protocol = ApiProtocol.OOBABOOGA_TEXT, streaming = false)
            val card = Card("r", jsonOf("name" to "Rina").toString())

            val vllmOut = StringBuilder()
            ModelService.reply(Preferences(), ModelConnection(vllm, "llama"), card, listOf(Message("user", "Mio", "Hi")), emptyList(), null) {
                vllmOut.append(it)
            }
            assertEquals("Streamed OK", vllmOut.toString())

            val oobaOut = StringBuilder()
            ModelService.reply(Preferences(), ModelConnection(ooba, "llama"), card, listOf(Message("user", "Mio", "Hi")), emptyList(), null) {
                oobaOut.append(it)
            }
            assertEquals("Sync OK", oobaOut.toString())

            val vllmTokens = ProviderProtocols.inspectRemoteTokens(ModelConnection(vllm, "llama"), "hello")
            assertEquals(4, vllmTokens.count)
            assertEquals(listOf(1, 2, 3, 4), vllmTokens.pieces.map(TokenPiece::id))

            val oobaTokens = ProviderProtocols.inspectRemoteTokens(ModelConnection(ooba, "llama"), "hello")
            assertEquals(7, oobaTokens.count)
            assertTrue(paths.containsAll(listOf("/v1/completions", "/tokenize", "/v1/internal/encode", "/v1/internal/token-count")))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `all 10 chat completion provider presets have valid endpoints, models, and auth conventions`() {
        assertEquals(10, ChatProviderPreset.entries.size)
        val expectedNames = setOf(
            ChatProviderPreset.MISTRAL,
            ChatProviderPreset.COHERE,
            ChatProviderPreset.VERTEX_AI,
            ChatProviderPreset.AZURE_OPENAI,
            ChatProviderPreset.XAI,
            ChatProviderPreset.GROQ,
            ChatProviderPreset.PERPLEXITY,
            ChatProviderPreset.MOONSHOT,
            ChatProviderPreset.ZHIPU,
            ChatProviderPreset.NANOGPT,
        )
        assertEquals(expectedNames, ChatProviderPreset.entries.toSet())

        for (preset in ChatProviderPreset.entries) {
            val draft = preset.createProviderDraft().copy(apiKey = "test-api-key")
            val validated = draft.validated()
            assertTrue("Expected https endpoint for ${preset.name}", validated.endpoint.startsWith("https://"))
            assertTrue("Expected defaultModel for ${preset.name}", validated.defaultModel.isNotBlank())
            assertTrue("Expected recommended models for ${preset.name}", preset.recommendedModels.isNotEmpty())
            val headers = preset.buildAuthHeaders("test-api-key")
            when (preset) {
                ChatProviderPreset.AZURE_OPENAI -> assertEquals("test-api-key", headers["api-key"])
                ChatProviderPreset.VERTEX_AI -> assertEquals("Bearer test-api-key", headers["Authorization"])
                else -> assertEquals("Bearer test-api-key", headers["Authorization"])
            }
        }
        // Vertex Express key convention uses x-goog-api-key.
        assertEquals("AIzaExpressKey123", ChatProviderPreset.VERTEX_AI.buildAuthHeaders("AIzaExpressKey123")["x-goog-api-key"])
    }

    @Test
    fun `Azure OpenAI uses deployment path, api-version query, api-key header, and persists azureConfig`() = runBlocking {
        val captured = mutableListOf<Triple<String, Map<String, String>, String>>()
        val (server, base) = startServer { _, path, headers, body ->
            captured += Triple(path, headers, body)
            when {
                path.startsWith("/openai/deployments?") ->
                    200 to ("application/json" to """{"data":[{"id":"my-gpt4o-deploy","model":"gpt-4o"}]}""")
                path.startsWith("/openai/deployments/my-gpt4o-deploy/chat/completions") ->
                    200 to ("application/json" to """{"choices":[{"message":{"content":"Azure reply"},"finish_reason":"stop"}]}""")
                else -> 404 to ("application/json" to """{"error":"unexpected path $path"}""")
            }
        }
        try {
            val original = ChatProviderPreset.AZURE_OPENAI.createProviderDraft("azure-1").copy(
                endpoint = base,
                apiKey = "az-secret-key",
                streaming = false,
                azureConfig = AzureOpenAiConfig(deployment = "my-gpt4o-deploy", apiVersion = "2024-10-21"),
            ).validated()

            val roundTripped = Provider.fromJson(original.storageJson { "enc:$it" }) { it.removePrefix("enc:") }
            assertEquals(original.azureConfig, roundTripped.azureConfig)
            assertEquals(ApiProtocol.AZURE_OPENAI, roundTripped.protocol)

            val models = ModelService.discoverModels(roundTripped)
            assertEquals(listOf("my-gpt4o-deploy"), models.ids)

            val output = StringBuilder()
            val card = Card("r", jsonOf("name" to "Rina").toString())
            ModelService.reply(
                Preferences(),
                ModelConnection(roundTripped, "gpt-4o"),
                card,
                listOf(Message("user", "Mio", "Hello Azure")),
                emptyList(),
                null,
            ) { output.append(it) }

            assertEquals("Azure reply", output.toString())
            assertEquals("/openai/deployments?api-version=2024-10-21", captured[0].first)
            assertEquals("/openai/deployments/my-gpt4o-deploy/chat/completions?api-version=2024-10-21", captured[1].first)
            assertEquals("az-secret-key", captured[1].second["api-key"])
            assertNull(captured[1].second["authorization"])
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `Vertex AI executes Gemini native and OpenAI-compat endpoints with correct auth headers`() = runBlocking {
        val captured = mutableListOf<Pair<String, Map<String, String>>>()
        val (server, base) = startServer { _, path, headers, _ ->
            captured += path to headers
            when {
                path.endsWith(":generateContent") ->
                    200 to ("application/json" to """{"candidates":[{"content":{"parts":[{"text":"Vertex Gemini OK"}]},"finishReason":"STOP"}]}""")
                path.endsWith("/openapi/chat/completions") ->
                    200 to ("application/json" to """{"choices":[{"message":{"content":"Vertex OpenAI OK"},"finish_reason":"stop"}]}""")
                else -> 404 to ("application/json" to """{"error":"unexpected $path"}""")
            }
        }
        try {
            val card = Card("r", jsonOf("name" to "Rina").toString())
            val expressVertex = ChatProviderPreset.VERTEX_AI.createProviderDraft("vx-1").copy(
                endpoint = "$base/v1/publishers/google",
                apiKey = "AIzaVertexExpressKey",
                defaultModel = "gemini-2.5-pro",
                streaming = false,
            )
            val out1 = StringBuilder()
            ModelService.reply(
                Preferences(),
                ModelConnection(expressVertex, "gemini-2.5-pro"),
                card,
                listOf(Message("user", "Mio", "Hi")),
                emptyList(),
                null,
            ) { out1.append(it) }
            assertEquals("Vertex Gemini OK", out1.toString())
            assertEquals("/v1/publishers/google/models/gemini-2.5-pro:generateContent", captured[0].first)
            assertEquals("AIzaVertexExpressKey", captured[0].second["x-goog-api-key"])

            val oauthVertex = expressVertex.copy(
                id = "vx-2",
                endpoint = "$base/v1beta1/projects/p/locations/us-central1/endpoints/openapi",
                apiKey = "ya29.oauth-access-token",
            )
            val out2 = StringBuilder()
            ModelService.reply(
                Preferences(),
                ModelConnection(oauthVertex, "google/gemini-2.5-pro"),
                card,
                listOf(Message("user", "Mio", "Hi")),
                emptyList(),
                null,
            ) { out2.append(it) }
            assertEquals("Vertex OpenAI OK", out2.toString())
            assertEquals("/v1beta1/projects/p/locations/us-central1/endpoints/openapi/chat/completions", captured[1].first)
            assertEquals("Bearer ya29.oauth-access-token", captured[1].second["authorization"])
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `multi-key encryption seals every key and round-robin plus cooldown expiration work deterministically`() {
        var now = 1_000_000L
        val rotator = MultiKeyRotator(clockMs = { now })

        val config = MultiKeyConfig(
            keys = listOf(
                ApiKeyEntry("k1", "Primary", "sk-first-11111111", enabled = true),
                ApiKeyEntry("k2", "Disabled", "sk-disabled-2222", enabled = false),
                ApiKeyEntry("k3", "Secondary", "sk-second-33333333", enabled = true),
                ApiKeyEntry("k4", "Tertiary", "sk-third-44444444", enabled = true),
            ),
            policy = KeyRotationPolicy.ROUND_ROBIN,
            cooldownSeconds = 30,
        )
        val provider = Provider("p-rr", "Test", "https://api.example.com/v1", defaultModel = "gpt-4o", multiKeys = config).validated()

        // Verify encryption round-trip seals every key secret.
        val stored = provider.storageJson { plain -> if (plain.isEmpty()) "" else "ENC:${plain.reversed()}" }
        val storedStr = stored.toString()
        assertFalse(storedStr.contains("sk-first-11111111"))
        assertFalse(storedStr.contains("sk-second-33333333"))
        assertFalse(storedStr.contains("sk-third-44444444"))
        val restored = Provider.fromJson(stored) { cipher ->
            if (cipher.startsWith("ENC:")) cipher.removePrefix("ENC:").reversed() else cipher
        }
        assertEquals(provider.multiKeys, restored.multiKeys)
        assertEquals("sk-f••••••1111", restored.multiKeys.keys.first().masked())
        assertEquals("••••••", ApiKeyEntry(secret = "short").masked())

        // Round-robin cycles through enabled keys k1 -> k3 -> k4 -> k1.
        assertEquals("k1", rotator.selectKey(restored)?.id)
        assertEquals("k3", rotator.selectKey(restored)?.id)
        assertEquals("k4", rotator.selectKey(restored)?.id)
        assertEquals("k1", rotator.selectKey(restored)?.id)

        // Put k3 in cooldown for 30s; rotator must skip k3 until cooldown elapses.
        rotator.reportFailure(restored.id, "k3", 429, "Rate limit exceeded", cooldownSeconds = 30)
        assertTrue(rotator.healthFor(restored.id, "k3")!!.isInCooldown(now))
        val picksDuringCooldown = List(4) { rotator.selectKey(restored)?.id }
        assertFalse(picksDuringCooldown.contains("k3"))

        // Advance clock past cooldown; k3 becomes eligible again.
        now += 31_000L
        assertFalse(rotator.healthFor(restored.id, "k3")!!.isInCooldown(now))
        val picksAfterCooldown = List(6) { rotator.selectKey(restored)?.id }
        assertTrue(picksAfterCooldown.contains("k3"))
    }

    @Test
    fun `multi-key failover automatically rotates on 401, 403, 429 and quota errors during live requests`() = runBlocking {
        val seenAuths = mutableListOf<String>()
        val (server, base) = startServer { _, path, headers, _ ->
            val auth = headers["authorization"].orEmpty()
            seenAuths += "$path:$auth"
            when (auth) {
                "Bearer sk-expired-key" -> 401 to ("application/json" to """{"error":{"message":"Invalid key sk-expired-key"}}""")
                "Bearer sk-ratelimited-key" -> 429 to ("application/json" to """{"error":{"message":"Rate limit exceeded"}}""")
                "Bearer sk-healthy-key" -> {
                    if (path == "/models") 200 to ("application/json" to """{"data":[{"id":"gpt-4o"}]}""")
                    else 200 to ("application/json" to """{"choices":[{"message":{"content":"Recovered!"},"finish_reason":"stop"}]}""")
                }
                else -> 403 to ("application/json" to """{"error":{"message":"Forbidden"}}""")
            }
        }
        try {
            val multi = MultiKeyConfig(
                keys = listOf(
                    ApiKeyEntry("k1", "Expired", "sk-expired-key"),
                    ApiKeyEntry("k2", "RateLimited", "sk-ratelimited-key"),
                    ApiKeyEntry("k3", "Healthy", "sk-healthy-key"),
                ),
                policy = KeyRotationPolicy.FAILOVER_ON_ERROR,
                cooldownSeconds = 120,
            )
            val provider = Provider(
                id = "p-failover",
                name = "Multi",
                endpoint = base,
                defaultModel = "gpt-4o",
                streaming = false,
                multiKeys = multi,
            ).validated()

            val card = Card("r", jsonOf("name" to "Rina").toString())
            val out = StringBuilder()
            ModelService.reply(
                Preferences(),
                ModelConnection(provider, "gpt-4o"),
                card,
                listOf(Message("user", "Mio", "Hello")),
                emptyList(),
                null,
            ) { out.append(it) }

            assertEquals("Recovered!", out.toString())
            assertEquals(
                listOf(
                    "/chat/completions:Bearer sk-expired-key",
                    "/chat/completions:Bearer sk-ratelimited-key",
                    "/chat/completions:Bearer sk-healthy-key",
                ),
                seenAuths,
            )

            // Subsequent request goes directly to healthy k3 because k1 & k2 are in cooldown!
            seenAuths.clear()
            val discovered = ModelService.discoverModels(provider)
            assertEquals(listOf("gpt-4o"), discovered.ids)
            assertEquals(listOf("/models:Bearer sk-healthy-key"), seenAuths)

            // Individual key test verifies k3 succeeds and k1 fails with redacted error.
            val okRes = MultiKeyManager.testKey(provider, multi.keys[2])
            assertTrue(okRes.isSuccess)
            val badRes = MultiKeyManager.testKey(provider, multi.keys[0])
            assertTrue(badRes.isFailure)
            assertFalse(badRes.exceptionOrNull()?.message.orEmpty().contains("sk-expired-key"))

            // When ALL keys fail, error is thrown and secrets are redacted.
            val allBad = provider.copy(
                id = "p-all-bad",
                multiKeys = multi.copy(keys = multi.keys.take(2)),
            )
            val failure = runCatching { ModelService.discoverModels(allBad) }.exceptionOrNull()
            assertNotNull(failure)
            assertFalse(failure.toString().contains("sk-expired-key"))
            assertFalse(failure.toString().contains("sk-ratelimited-key"))
        } finally {
            server.stop(0)
        }
    }
}
