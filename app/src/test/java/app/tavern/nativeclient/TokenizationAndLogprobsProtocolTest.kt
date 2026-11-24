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

class TokenizationAndLogprobsProtocolTest {

    @Test
    fun `token counter uses Gemini countTokens and native text token endpoints`() = runBlocking {
        val captured = mutableListOf<Triple<String, Map<String, String>, JSONObject>>()
        val (server, base) = startProtocolTestServer { path, headers, body ->
            captured += Triple(path, headers, JSONObject(body))
            val payload = when (path) {
                "/v1beta/models/gemini-4-hydrogen:countTokens" -> """{"totalTokens":17}"""
                "/tokenize" -> """{"tokens":[{"id":1,"piece":" hello"},{"id":2,"piece":[240,159]},{"id":3,"piece":[140,184]}]}"""
                "/api/extra/tokencount" -> """{"value":2,"ids":[21,22]}"""
                "/kobold/tokenize" -> """{"error":"unknown route"}"""
                "/kobold/api/extra/tokencount" -> """{"value":11}"""
                else -> """{"error":"unknown route"}"""
            }
            val status = if (path == "/kobold/tokenize") 404 else 200
            status to ("application/json" to payload)
        }
        try {
            val gemini = Provider("gemini", endpoint = "$base/v1beta", apiKey = "fixture-google-key", protocol = ApiProtocol.GEMINI_NATIVE)
            val text = Provider("local", endpoint = "$base/v1", apiKey = "fixture-local-key", protocol = ApiProtocol.KOBOLD_TEXT)
            val google = ProviderProtocols.inspectRemoteTokens(ModelConnection(gemini, "models/gemini-4-hydrogen"), "你好")
            assertEquals(17, google.count)
            assertTrue(google.pieces.isEmpty())
            val llama = ProviderProtocols.inspectRemoteTokens(ModelConnection(text, "loaded-llama"), " hello🌸")
            assertEquals(listOf(TokenPiece(1, " hello"), TokenPiece(2, "[F0] [9F]"), TokenPiece(3, "[8C] [B8]")), llama.pieces)
            val kobold = ProviderProtocols.inspectRemoteTokens(ModelConnection(text.copy(endpoint = base), "loaded-kobold"), "hello")
            assertEquals(listOf(21, 22), kobold.pieces.map(TokenPiece::id))
            val compatibleKobold = ProviderProtocols.inspectRemoteTokens(ModelConnection(text.copy(endpoint = "$base/kobold/v1"), "local"), "hello")
            assertEquals(11, compatibleKobold.count)
            assertTrue(compatibleKobold.pieces.isEmpty())
            assertEquals(listOf("/v1beta/models/gemini-4-hydrogen:countTokens", "/tokenize", "/api/extra/tokencount",
                "/kobold/tokenize", "/kobold/api/extra/tokencount"), captured.map { it.first })
            assertEquals("fixture-google-key", captured.first().second["x-goog-api-key"])
            assertEquals("你好", captured.first().third.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"))
            assertTrue(captured[1].third.getBoolean("with_pieces"))
            assertEquals("loaded-llama", captured[1].third.getString("model"))
            assertFalse(captured[2].third.getBoolean("special"))
            assertEquals("Bearer fixture-local-key", captured.last().second["authorization"])
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `token bias sends matching model IDs and respects request body overrides`() = runBlocking {
        val captured = mutableListOf<Pair<String, JSONObject>>()
        val (server, base) = startProtocolTestServer { path, _, text ->
            captured += path to JSONObject(text)
            200 to ("application/json" to """{"choices":[{"message":{"content":"Ready"},"finish_reason":"stop"}]}""")
        }
        try {
            val bias = TokenBiasPreset("terms", "Terms", listOf(TokenBiasRow("a", "hello", -45.0)))
            val provider = Provider("C", endpoint = "$base/v1", defaultModel = "gpt-4", streaming = false,
                tokenBias = TokenBiasSettings(listOf(bias), TokenBiasSelection(bias.id), mapOf("disabled" to TokenBiasSelection())))
            val card = Card("r", jsonOf("name" to "Rina").toString())
            suspend fun reply(connection: ModelConnection) {
                ModelService.reply(Preferences(), connection, card, listOf(Message("user", "Mio", "Go")), emptyList(), null) {}
            }
            reply(ModelConnection(provider, "gpt-4"))
            reply(ModelConnection(provider.copy(protocol = ApiProtocol.OPENROUTER), "openai/gpt-4o"))
            reply(ModelConnection(provider, "disabled"))
            reply(ModelConnection(provider.copy(requestOptions = ProviderRequestOptions(includeBody = "logit_bias: {'42': 3}")), "unknown"))
            reply(ModelConnection(provider.copy(requestOptions = ProviderRequestOptions(excludeBody = "- logit_bias")), "unknown"))
            assertEquals(List(5) { "/v1/chat/completions" }, captured.map { it.first })
            assertEquals(-45.0, captured[0].second.getJSONObject(LOGIT_BIAS).getDouble("15339"), 0.0)
            assertEquals(-45.0, captured[1].second.getJSONObject(LOGIT_BIAS).getDouble("24912"), 0.0)
            assertFalse(captured[2].second.has(LOGIT_BIAS))
            assertEquals(3, captured[3].second.getJSONObject(LOGIT_BIAS).getInt("42"))
            assertFalse(captured[4].second.has(LOGIT_BIAS))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `Kobold and llama tokenize bias on the configured service and send numeric maps`() = runBlocking {
        val captured = mutableListOf<Triple<String, Map<String, String>, JSONObject>>()
        val (server, base) = startProtocolTestServer { path, headers, text ->
            captured += Triple(path, headers, JSONObject(text))
            val payload = when (path) {
                "/api/extra/tokencount" -> """{"value":1,"ids":[11]}"""
                "/api/v1/generate" -> """{"results":[{"text":"Ready"}]}"""
                "/tokenize" -> """{"tokens":[22]}"""
                else -> """{"choices":[{"text":"Ready","finish_reason":"stop"}]}"""
            }
            200 to ("application/json" to payload)
        }
        try {
            val bias = TokenBiasPreset("terms", "Terms", listOf(
                TokenBiasRow("a", "hello", -45.0),
                TokenBiasRow("b", "hello", -25.0),
                TokenBiasRow("c", "[30]", 10.0),
            ))
            val provider = Provider("T", endpoint = "$base/api/v1", apiKey = "local-key", defaultModel = "local",
                protocol = ApiProtocol.KOBOLD_TEXT, streaming = false,
                tokenBias = TokenBiasSettings(listOf(bias), TokenBiasSelection(bias.id)))
            val card = Card("r", jsonOf("name" to "Rina").toString())
            for (connection in listOf(ModelConnection(provider, "kobold"), ModelConnection(provider.copy(endpoint = "$base/v1/completions"), "llama"))) {
                ModelService.reply(Preferences(), connection, card, listOf(Message("user", "Mio", "Go")), emptyList(), null) {}
            }
            assertEquals(listOf("/api/extra/tokencount", "/api/v1/generate", "/tokenize", "/v1/completions"), captured.map { it.first })
            assertTrue(captured.all { it.second["authorization"] == "Bearer local-key" })
            assertEquals(" hello", captured[0].third.getString("prompt"))
            assertFalse(captured[0].third.getBoolean("special"))
            assertEquals(-25.0, captured[1].third.getJSONObject(LOGIT_BIAS).getDouble("11"), 0.0)
            assertEquals(" hello", captured[2].third.getString("content"))
            assertEquals("llama", captured[2].third.getString("model"))
            assertFalse(captured[2].third.getBoolean("add_special"))
            assertEquals(-25.0, captured[3].third.getJSONObject(LOGIT_BIAS).getDouble("22"), 0.0)
            assertTrue(listOf(captured[1], captured[3]).all { it.third.getJSONObject(LOGIT_BIAS).getInt("30") == 10 })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `DeepSeek probabilities arrive in late SSE chunks and stay with that swipe`() = runBlocking {
        val (server, base) = startProtocolTestServer { path, _, body ->
            assertEquals("/chat/completions", path)
            val request = JSONObject(body)
            assertTrue(request.getBoolean("logprobs"))
            assertEquals(5, request.getInt("top_logprobs"))
            val finish = """{"choices":[{"delta":{"content":"你好"},"finish_reason":"stop"}]}"""
            val late = """{"choices":[{"logprobs":{"content":[{"token":"你","logprob":-0.1,"top_logprobs":[{"token":"我","logprob":-2.0}]},{"token":"好","logprob":-0.2,"top_logprobs":[{"token":"们","logprob":-1.8}]}]}}]}"""
            200 to ("text/event-stream" to listOf(finish, late, "[DONE]").joinToString("") { "data: $it\n\n" })
        }
        try {
            val provider = Provider("deepseek", endpoint = base, defaultModel = "deepseek-flash", template = ProviderTemplate.DEEPSEEK)
            val card = Card("r", jsonOf("name" to "Rina").toString())
            val text = StringBuilder()
            val result = ModelService.reply(Preferences(requestTokenProbabilities = true), ModelConnection(provider, provider.defaultModel),
                card, listOf(Message("user", "Mio", "打招呼")), emptyList(), null) { text.append(it) }
            val trace = requireNotNull(result.replyTokens).stored(text.toString(), "")
            assertEquals("你们", trace.replacement(1, trace.tokens[1].alternatives.single()))
            val media = StoredWebReply(listOf(ReplySource("https://example.org/hello", "Hello")))
            val first = Message("assistant", "Rina", text.toString(), replyTokens = trace, webReply = media)
            val restored = Message.from(first.withAlternative("早上好").toJson())
            assertEquals(null, restored.replyTokens)
            assertEquals(trace, restored.select(0).replyTokens)
            assertEquals(trace, restored.withoutAlternative(1).replyTokens)
            assertEquals(media, restored.withoutAlternative(1).webReply)
            assertEquals(null, restored.select(0).withText("编辑").replyTokens)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `logprobs rejection retries without optional fields before showing any output`() = runBlocking {
        val requests = mutableListOf<JSONObject>()
        val (server, base) = startProtocolTestServer { _, _, body ->
            requests += JSONObject(body)
            if (requests.size == 1) {
                400 to ("application/json" to """{"error":{"message":"top_logprobs is not supported"}}""")
            } else {
                200 to ("application/json" to """{"choices":[{"message":{"content":"Hello"},"finish_reason":"stop"}]}""")
            }
        }
        try {
            val provider = Provider("custom", endpoint = base, defaultModel = "local", streaming = false)
            val card = Card("r", jsonOf("name" to "Rina").toString())
            val text = StringBuilder()
            val result = ModelService.reply(Preferences(requestTokenProbabilities = true), ModelConnection(provider, provider.defaultModel),
                card, listOf(Message("user", "Mio", "Say hello")), emptyList(), null) { text.append(it) }
            assertEquals(2, requests.size)
            assertTrue(requests.first().has("logprobs"))
            assertFalse(requests.last().has("logprobs"))
            assertFalse(requests.last().has("top_logprobs"))
            assertEquals("Hello", text.toString())
            assertEquals(null, result.replyTokens)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `reroll sends the exact candidate prefix in llama cpp and DeepSeek native continuation`() = runBlocking {
        val prefix = "新的 "
        val paths = mutableListOf<String>()
        val (server, base) = startProtocolTestServer { path, _, body ->
            paths += path
            val request = JSONObject(body)
            if (path.endsWith("/chat/completions")) {
                val row = request.getJSONArray("messages").objects().last()
                assertEquals(prefix, row.string("content"))
                assertTrue(row.getBoolean("prefix"))
                200 to ("application/json" to """{"choices":[{"message":{"content":"方向"},"finish_reason":"stop"}]}""")
            } else {
                assertTrue(request.string("prompt").endsWith(prefix))
                assertEquals(5, request.getInt("logprobs"))
                200 to ("application/json" to """{"choices":[{"text":"方向","logprobs":{"tokens":["方向"],"token_logprobs":[-0.2],"top_logprobs":[{"路":-0.9}]},"finish_reason":"stop"}]}""")
            }
        }
        try {
            val card = Card("r", jsonOf("name" to "Rina").toString())
            val history = listOf(Message("user", "Mio", "继续向前"), Message("assistant", "Rina", prefix))
            val llama = Provider("llama", endpoint = "$base/v1", defaultModel = "local", protocol = ApiProtocol.KOBOLD_TEXT, streaming = false)
            val deepSeek = Provider("deepseek", endpoint = base, defaultModel = "deepseek-flash", template = ProviderTemplate.DEEPSEEK, streaming = false)
            for (provider in listOf(llama, deepSeek)) {
                val result = ModelService.reply(Preferences(requestTokenProbabilities = true),
                    ModelConnection(provider, provider.defaultModel), card, history, emptyList(), null,
                    trigger = "continue", forcedContinuation = prefix) {}
                if (provider == llama) assertEquals("路", result.replyTokens?.tokens?.single()?.alternatives?.single()?.text)
            }
            assertEquals(listOf("/v1/completions", "/beta/chat/completions"), paths)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `token replacement combines UTF-8 bytes and rejects incomplete characters`() {
        val collector = ReplyTokenCollector()
        collector.accept(JSONObject("""{"content":[
            {"token":"�","bytes":[240,159],"logprob":-0.1,"top_logprobs":[{"token":"�","bytes":[240,159],"logprob":-0.3}]},
            {"token":"�","bytes":[152,128],"logprob":-0.2,"top_logprobs":[{"token":"�","bytes":[152,129],"logprob":-0.4}]}
        ]}"""))
        val tokens = requireNotNull(collector.finish("😀"))
        assertEquals(null, tokens.replacement(0, tokens.tokens[0].alternatives.single()))
        assertEquals("😁", tokens.replacement(1, tokens.tokens[1].alternatives.single()))
        assertEquals(tokens, ReplyTokenTrace.from(tokens.json()))
    }

    @Test
    fun `Kobold streaming requests late probabilities with the same generation key`() = runBlocking {
        var generationKey = ""
        val (server, base) = startProtocolTestServer { path, _, body ->
            val request = JSONObject(body)
            when (path) {
                "/api/extra/generate/stream" -> {
                    generationKey = request.getString("genkey")
                    assertTrue(generationKey.isNotBlank() && request.getBoolean("logprobs"))
                    200 to ("text/event-stream" to "data: {\"token\":\"夜\",\"finish_reason\":\"stop\"}\n\n")
                }
                "/api/extra/last_logprobs" -> {
                    assertEquals(generationKey, request.getString("genkey"))
                    200 to ("application/json" to """{"logprobs":{"content":[{"token":"夜","logprob":-0.2,"top_logprobs":[{"token":"晨","logprob":-1.1}]}]}}""")
                }
                else -> error("Unexpected path: $path")
            }
        }
        try {
            val provider = Provider("kobold", endpoint = base, defaultModel = "local", protocol = ApiProtocol.KOBOLD_TEXT)
            val card = Card("r", jsonOf("name" to "Rina").toString())
            val result = ModelService.reply(Preferences(requestTokenProbabilities = true), ModelConnection(provider, provider.defaultModel),
                card, listOf(Message("user", "Mio", "Write a scene")), emptyList(), null) {}
            assertEquals("晨", result.replyTokens?.tokens?.single()?.alternatives?.single()?.text)
        } finally {
            server.stop(0)
        }
    }
}
