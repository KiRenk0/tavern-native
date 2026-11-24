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

class WebSearchAndMediaProtocolTest {

    @Test
    fun `Gemini search holds streaming text until late citations and suggestions arrive`() = runBlocking {
        val html = """<a href="https://www.google.com/search?q=moon">moon</a>"""
        val requests = mutableListOf<JSONObject>()
        val firstText = "月球的资料"
        val lastText = "。\n"
        val (server, base) = startProtocolTestServer { path, headers, body ->
            assertEquals("/v1beta/models/gemini-4-hydrogen:streamGenerateContent?alt=sse", path)
            assertEquals("google-key", headers["x-goog-api-key"])
            requests += JSONObject(body)
            val text = jsonOf("candidates" to JSONArray().put(jsonOf("content" to
                jsonOf("parts" to JSONArray().put(jsonOf("text" to firstText))))))
            val finish = """{"candidates":[{"content":{"parts":[{"text":"。\n"}]},"finishReason":"STOP","groundingMetadata":{"groundingChunks":[{"web":{"uri":"https://example.org/moon","title":"Moon"}}]}}]}"""
            val metadata = jsonOf("candidates" to JSONArray().put(jsonOf("groundingMetadata" to jsonOf(
                "searchEntryPoint" to jsonOf("renderedContent" to html),
                "webSearchQueries" to JSONArray().put("moon"),
                "groundingChunks" to JSONArray().put(jsonOf("web" to jsonOf("uri" to "https://example.org/orbit", "title" to "Orbit"))),
            ))))
            200 to ("text/event-stream" to listOf(text.toString(), finish, metadata.toString(), "[DONE]")
                .joinToString("") { "data: $it\n\n" })
        }
        try {
            val provider = Provider("google", endpoint = base, defaultModel = "gemini-4-hydrogen",
                apiKey = "google-key", protocol = ApiProtocol.GEMINI_NATIVE)
            val card = Card("r", jsonOf("name" to "Rina", "description" to "Long system context. ".repeat(600)).toString())
            val preset = PromptPreset(name = "Search", chatCompletion = ChatCompletionPreset(webSearch = true))
            val result = ModelService.reply(Preferences(toolCalling = true), ModelConnection(provider, provider.defaultModel),
                card, listOf(Message("user", "Mio", "查找月球资料")), emptyList(), null, preset = preset,
                tools = ReplyTools(reload = { error("Gemini Search uses a hosted tool") }, record = {})) {
                error("Grounded content must stay in the current chat view")
            }
            val request = requests.single()
            assertEquals("""[{"googleSearch":{}}]""", request.getJSONArray("tools").toString())
            assertFalse(request.has("cachedContent"))
            assertEquals(null, result.webReply)
            val grounded = requireNotNull(result.googleGrounded)
            assertEquals(firstText + lastText, grounded.text)
            assertEquals(html, grounded.suggestionHtml)
            assertEquals(listOf("moon"), grounded.searchQueries)
            assertEquals(listOf("Moon", "Orbit"), grounded.sources.map(ReplySource::title))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `Gemini sends a regular reply when the non-streaming model does not use search`() = runBlocking {
        val (server, base) = startProtocolTestServer { path, _, body ->
            assertEquals("/v1beta/models/gemini-2.5-flash:generateContent", path)
            assertEquals("""[{"googleSearch":{}}]""", JSONObject(body).getJSONArray("tools").toString())
            200 to ("application/json" to """{"candidates":[{"content":{"parts":[{"text":"Hello"}]},"groundingMetadata":{},"finishReason":"STOP"}]}""")
        }
        try {
            val provider = Provider("google", endpoint = base, defaultModel = "gemini-2.5-flash",
                protocol = ApiProtocol.GEMINI_NATIVE, streaming = false)
            val card = Card("r", jsonOf("name" to "Rina").toString())
            val output = StringBuilder()
            val result = ModelService.reply(Preferences(), ModelConnection(provider, provider.defaultModel), card,
                listOf(Message("user", "Mio", "Hi")), emptyList(), null,
                preset = PromptPreset(name = "Search", chatCompletion = ChatCompletionPreset(webSearch = true))) {
                output.append(it)
            }
            assertEquals("Hello", output.toString())
            assertEquals(null, result.googleGrounded)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `Google template routes search to the native endpoint without changing custom connections`() {
        val google = ProviderTemplate.GOOGLE.profile().copy(apiKey = "google-key")
        assertEquals(ApiProtocol.GEMINI_NATIVE, ModelConnection(google, google.defaultModel).googleSearchProvider()?.protocol)
        assertEquals(null, ModelConnection(google.copy(endpoint = "https://example.org/openai"), google.defaultModel).googleSearchProvider())
        assertEquals(null, ModelConnection(google, "gemini-3-pro-image-preview").googleSearchProvider())
        assertTrue(isGoogleSearchSuggestion("https://www.google.com/search?q=moon"))
        assertFalse(isGoogleSearchSuggestion("https://www.google.com.evil.example/search"))
        assertFalse(isGoogleSearchSuggestion("https://example.org@www.google.com/search"))
        assertFalse(isGoogleSearchSuggestion("javascript:alert(1)"))
    }

    @Test
    fun `DeepSeek web citations and signed calls survive SSE and the next request`() = runBlocking {
        val bodies = mutableListOf<JSONObject>()
        val calls = JSONArray("""[{"index":0,"calls":[{"index":0,"id":"srvtoolu_1","type":"deepseek_read_url","kind":"read_link","signature":"kept"}]}]""")
        val source = jsonOf("type" to "url_citation",
            "url_citation" to jsonOf("url" to "https://example.com/topic", "title" to "Primary source"))
        val (server, base) = startProtocolTestServer { path, _, body ->
            assertEquals("/chat/completions", path)
            bodies += JSONObject(body)
            val text = jsonOf("choices" to JSONArray().put(jsonOf("delta" to jsonOf("content" to "Found the answer"))))
            val annotations = jsonOf("choices" to JSONArray().put(jsonOf("finish_reason" to "stop", "delta" to
                jsonOf("annotations" to JSONArray().put(source), "server_tool_calls" to calls))))
            200 to ("text/event-stream" to listOf(text.toString(), annotations.toString(), "[DONE]")
                .joinToString("") { "data: $it\n\n" })
        }
        try {
            val provider = Provider("deepseek", endpoint = base, defaultModel = "deepseek-flash", template = ProviderTemplate.DEEPSEEK)
            val connection = ModelConnection(provider, provider.defaultModel)
            val card = Card("r", jsonOf("name" to "Rina").toString())
            val preset = PromptPreset(name = "Search", continuePrefill = true, chatCompletion = ChatCompletionPreset(webSearch = true))
            val user = Message("user", "Mio", "Find the latest update")
            val content = StringBuilder()
            val first = ModelService.reply(Preferences(toolCalling = true), connection, card, listOf(user), emptyList(), null,
                preset = preset, tools = ReplyTools(reload = { error("The hosted tools are operated by DeepSeek") }, record = {})) {
                content.append(it)
            }
            assertEquals("Found the answer", content.toString())
            val web = requireNotNull(first.webReply)
            assertEquals(listOf(ReplySource("https://example.com/topic", "Primary source")), web.sources)
            assertEquals(listOf("deepseek_web_search", "deepseek_read_url"),
                bodies[0].getJSONArray("tools").objects().map { it.string("type") })
            val assistant = Message("assistant", "Rina", content.toString(), webReply = web)
            var inspected = JSONArray()
            ModelService.reply(Preferences(), connection, card, listOf(user, assistant), emptyList(), null,
                preset = preset, trigger = "continue", onPrompt = { inspected = it }) {}
            val restored = bodies[1].getJSONArray("messages").objects().single { it.string("role") == "assistant" }
            assertEquals("kept", restored.getJSONArray("server_tool_calls").getJSONObject(0)
                .getJSONArray("calls").getJSONObject(0).getString("signature"))
            assertFalse(restored.has("prefix"))
            assertFalse(inspected.toString().contains("server_tool_calls"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `OpenRouter search receives annotations even after the finish event`() = runBlocking {
        var request = JSONObject()
        val (server, base) = startProtocolTestServer { _, _, body ->
            request = JSONObject(body)
            val text = """{"choices":[{"delta":{"content":"Current information"},"finish_reason":"stop"}]}"""
            val source = """{"choices":[{"delta":{"annotations":[{"type":"url_citation","url_citation":{"url":"https://example.com/open","title":"Open source"}}]}}]}"""
            200 to ("text/event-stream" to listOf(text, source, "[DONE]").joinToString("") { "data: $it\n\n" })
        }
        try {
            val provider = Provider("router", endpoint = base, defaultModel = "example/online", protocol = ApiProtocol.OPENROUTER)
            val card = Card("r", jsonOf("name" to "Rina").toString())
            val response = ModelService.reply(Preferences(), ModelConnection(provider, provider.defaultModel), card,
                listOf(Message("user", "Mio", "Find current information")), emptyList(), null,
                preset = PromptPreset(name = "Search", chatCompletion = ChatCompletionPreset(webSearch = true))) {}
            assertEquals("openrouter:web_search", request.getJSONArray("tools").getJSONObject(0).getString("type"))
            assertEquals("Open source", response.webReply?.sources?.single()?.title)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `Claude web search sends hosted tool and replays returned results on the same connection`() = runBlocking {
        val bodies = mutableListOf<JSONObject>()
        val (server, base) = startProtocolTestServer { _, _, body ->
            bodies += JSONObject(body)
            200 to ("application/json" to """{
              "content":[
                {"type":"server_tool_use","id":"srvtoolu_1","name":"web_search","input":{"query":"status"}},
                {"type":"web_search_tool_result","tool_use_id":"srvtoolu_1","content":[
                  {"type":"web_search_result","url":"https://example.org/web","title":"Example","encrypted_content":"opaque"}
                ]},
                {"type":"text","text":"Found it","citations":[
                  {"type":"web_search_result_location","url":"https://example.org/web","title":"Example","encrypted_index":"index"}
                ]}
              ],"stop_reason":"end_turn"
            }""")
        }
        try {
            val provider = Provider("claude", endpoint = base, defaultModel = "claude-sonnet-4-6",
                protocol = ApiProtocol.ANTHROPIC, streaming = false, promptCaching = false)
            val connection = ModelConnection(provider, provider.defaultModel)
            val preset = PromptPreset(name = "Search", chatCompletion = ChatCompletionPreset(webSearch = true))
            val card = Card("r", jsonOf("name" to "Rina").toString())
            val user = Message("user", "Mio", "Search")
            val first = ModelService.reply(Preferences(), connection, card, listOf(user), emptyList(), null, preset = preset) {}
            val web = requireNotNull(first.webReply)
            assertEquals("web_search_20250305", bodies[0].getJSONArray("tools").getJSONObject(0).getString("type"))
            assertEquals(listOf(ReplySource("https://example.org/web", "Example")), web.sources)
            ModelService.reply(Preferences(), connection, card, listOf(user, Message("assistant", "Rina", "Found it", webReply = web),
                Message("user", "Mio", "What else did it say?")), emptyList(), null, preset = preset) {}
            val assistant = bodies[1].getJSONArray("messages").objects().single { it.string("role") == "assistant" }
            val result = assistant.getJSONArray("content").objects().single { it.string("type") == "web_search_tool_result" }
            assertEquals("opaque", result.getJSONArray("content").getJSONObject(0).getString("encrypted_content"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `Gemini streamed image-only replies replay the image and its signature on the next request`() = runBlocking {
        val bodies = mutableListOf<JSONObject>()
        val (server, base) = startProtocolTestServer { path, _, raw ->
            assertTrue(path.startsWith("/v1beta/models/gemini-nano-banana-2.1:streamGenerateContent"))
            bodies += JSONObject(raw)
            val thought = """{"candidates":[{"content":{"parts":[{"thought":true,"inlineData":{"mimeType":"image/png","data":"ignored"}}]}}]}"""
            val image = """{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"image/png","data":"$REPLY_PNG_FIXTURE"},"thoughtSignature":"opaque-image-signature"}]}}]}"""
            val finish = """{"candidates":[{"finishReason":"STOP"}]}"""
            val events = if (bodies.size == 1) listOf(thought, image, finish) else listOf(image)
            200 to ("text/event-stream" to events.joinToString("") { "data: $it\n\n" })
        }
        try {
            val provider = Provider("google", endpoint = base, defaultModel = "gemini-nano-banana-2.1",
                protocol = ApiProtocol.GEMINI_NATIVE)
            val connection = ModelConnection(provider, provider.defaultModel)
            val card = Card("r", jsonOf("name" to "Rina").toString())
            val preset = PromptPreset(name = "Media", chatCompletion = ChatCompletionPreset(images = ReplyImageOptions(true, "16:9", "2K")))
            val request = Message("user", "Mio", "Draw the scene")
            val first = ModelService.reply(Preferences(), connection, card, listOf(request), emptyList(), null,
                preset = preset) { error("The fixture contains an image only") }
            assertEquals(null, first.incomplete)
            assertFalse(first.imageWarning)
            val raster = first.images.single()
            val generation = bodies[0].getJSONObject("generationConfig")
            assertEquals(listOf("TEXT", "IMAGE"), generation.getJSONArray("responseModalities").strings())
            assertEquals("2K", generation.getJSONObject("imageConfig").getString("imageSize"))
            assertFalse(bodies[0].has("systemInstruction"))
            val image = ChatImage(raster.key, "Reply", ImageFormat.PNG, originalReply = true)
            val media = StoredReplyMedia(listOf(image), GeminiReplyReplay(provider.id, connection.model, first.geminiReplayParts))
            var inspected = JSONArray()
            val partial = ModelService.reply(Preferences(), connection, card, listOf(request,
                Message("assistant", "Rina", "", replyMedia = media), Message("user", "Mio", "Use the same character")),
                emptyList(), null, preset = preset, readImage = { raster.bytes }, onPrompt = { inspected = it }) {}
            assertNotNull(partial.incomplete)
            assertEquals(raster.key, partial.images.single().key)
            val modelPart = bodies[1].getJSONArray("contents").objects().single { it.string("role") == "model" }
                .getJSONArray("parts").getJSONObject(0)
            assertEquals("opaque-image-signature", modelPart.getString("thoughtSignature"))
            assertEquals(REPLY_PNG_FIXTURE, modelPart.getJSONObject("inlineData").getString("data"))
            assertFalse(inspected.toString().contains(REPLY_PNG_FIXTURE))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `OpenRouter collects an image arriving after the finish event without duplicating it`() = runBlocking {
        var request = JSONObject()
        val (server, base) = startProtocolTestServer { _, _, raw ->
            request = JSONObject(raw)
            val text = """{"choices":[{"delta":{"content":"Scene"},"finish_reason":"stop"}]}"""
            val image = """{"choices":[{"delta":{"images":[{"type":"image_url","image_url":{"url":"data:image/png;base64,$REPLY_PNG_FIXTURE"}}]}}]}"""
            val final = """{"choices":[{"message":{"images":[{"type":"image_url","image_url":{"url":"data:image/png;base64,$REPLY_PNG_FIXTURE"}}]}}]}"""
            200 to ("text/event-stream" to listOf(text, image, final, "[DONE]").joinToString("") { "data: $it\n\n" })
        }
        try {
            val provider = Provider("router", endpoint = base, defaultModel = "google/gemini-3.1-flash-image", protocol = ApiProtocol.OPENROUTER)
            val card = Card("r", jsonOf("name" to "Rina").toString())
            val preset = PromptPreset(name = "Media", chatCompletion = ChatCompletionPreset(images = ReplyImageOptions(true, "4:3")))
            val output = StringBuilder()
            val reply = ModelService.reply(Preferences(), ModelConnection(provider, provider.defaultModel), card,
                listOf(Message("user", "Mio", "Draw")), emptyList(), null, preset = preset) { output.append(it) }
            assertEquals("Scene", output.toString())
            assertEquals(listOf("text", "image"), request.getJSONArray("modalities").strings())
            assertEquals("4:3", request.getJSONObject("image_config").getString("aspect_ratio"))
            assertEquals(1, reply.images.size)
            assertEquals(null, reply.incomplete)
        } finally {
            server.stop(0)
        }
    }
}
