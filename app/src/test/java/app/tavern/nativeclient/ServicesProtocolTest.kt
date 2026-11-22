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
import java.io.File

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
    fun `NovelAI chat switches model presets and preserves interrupted streaming text`() = runBlocking {
        val requests = mutableListOf<Pair<String, JSONObject>>()
        var streamed = 0
        val (server, base) = startServer { path, headers, text ->
            check(headers["authorization"] == "Bearer test-key")
            if (path == "/user/subscription") {
                200 to ("application/json" to """{"tier":3}""")
            } else {
                requests += path to JSONObject(text)
                val stream = """data: {"token":"没说完","ptr":1}""" + "\n\n"
                when {
                    path == "/ai/generate" -> 200 to ("application/json" to """{"output":"普通回复"}""")
                    ++streamed == 1 -> 200 to ("text/event-stream" to stream)
                    streamed == 2 -> 200 to ("text/event-stream" to (
                        """data: {"token":"到站","ptr":1}""" + "\n\n" +
                            """data: {"token":"了","ptr":2,"final":true}""" + "\n\n"
                        ))
                    else -> 405 to ("application/json" to "{}")
                }
            }
        }
        try {
            val custom = NovelAiPreset.parse(JSONObject("""{"name":"Kayra 专用","max_length":211,"temperature":0.61}"""))
            val original = Provider("novel", "NovelAI", "$base/ai", "test-key", NovelAiModel.ERATO.id,
                NovelAiModel.entries.map(NovelAiModel::id), protocol = ApiProtocol.NOVELAI_TEXT,
                novelAi = NovelAiSettings("开场提示", mapOf(NovelAiModel.KAYRA to custom)))
            val found = ModelService.discoverModels(original)
            assertEquals(NovelAiModel.entries.map(NovelAiModel::id), found.ids)
            assertEquals(3, found.novelAiTier)
            val checked = original.copy(novelAi = original.novelAi.copy(tier = found.novelAiTier)).validated()
            val provider = Provider.fromJson(checked.storageJson { "sealed-$it" }) { it.removePrefix("sealed-") }
            assertEquals(checked, provider)
            assertFalse(ProviderTemplate.builtIn.any { it.profile().protocol == ApiProtocol.NOVELAI_TEXT })
            assertFalse(provider.copy(apiKey = "").ready)
            val card = Card("rina", jsonOf("name" to "Rina", "description" to "Rina is a conductor.").toString())
            val tokenizers = ChatTokenizers { path ->
                listOf(File("app/src/main/assets", path), File("src/main/assets", path)).first(File::isFile).inputStream()
            }
            suspend fun reply(model: NovelAiModel): Triple<String, ModelService.ReplyResult, PromptBudgetUsage> {
                val text = StringBuilder()
                var usage: PromptBudgetUsage? = null
                val connection = ModelConnection(provider, model.id)
                val result = ModelService.reply(Preferences(maxTokens = ""), connection, card,
                    listOf(Message("user", "Mio", "列车到了吗？")), emptyList(), null, persona = PersonaProfile(name = "Mio"),
                    loreCounter = tokenizers.counterFor(connection), onContextBudget = { usage = it }) { text.append(it) }
                return Triple(text.toString(), result, requireNotNull(usage))
            }
            val (partial, interrupted, firstBudget) = reply(NovelAiModel.ERATO)
            assertEquals("没说完", partial)
            assertNotNull(interrupted.incomplete)
            assertEquals(1, requests.size)
            val erato = requests.single().second
            assertEquals(NovelAiModel.ERATO.id, erato.getString("model"))
            assertTrue(erato.getString("input").startsWith("<|startoftext|><|reserved_special_token81|>开场提示\n"))
            assertTrue(erato.getString("input").endsWith("Mio: 列车到了吗？\nRina:"))
            assertEquals(150, firstBudget.output)
            val eratoParams = erato.getJSONObject("parameters")
            assertEquals(firstBudget.output, eratoParams.getInt("max_length"))
            assertEquals(1.37, eratoParams.getDouble("temperature"), 0.0001)
            assertTrue(eratoParams.getJSONArray("stop_sequences").toString().contains("[198,44,822,25]"))
            val (full, finished, secondBudget) = reply(NovelAiModel.KAYRA)
            assertEquals("到站了", full)
            assertEquals(null, finished.incomplete)
            assertEquals(211, secondBudget.output)
            val kayra = requests.last().second
            assertEquals(NovelAiModel.KAYRA.id, kayra.getString("model"))
            assertFalse(kayra.getString("input").startsWith("<|startoftext|>"))
            assertEquals(0.61, kayra.getJSONObject("parameters").getDouble("temperature"), 0.0001)
            assertEquals("普通回复", reply(NovelAiModel.KAYRA).first)
            assertEquals(listOf("/ai/generate-stream", "/ai/generate-stream", "/ai/generate-stream", "/ai/generate"),
                requests.map { it.first })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `NovelAI imports official presets with sampler order and text adventure settings`() {
        val preset = NovelAiPreset.parse(JSONObject("""
            {"name":"Experiment","presetVersion":3,"parameters":{"max_length":250,"min_length":120,"top_p":0.87,
            "prefix":"theme_textadventure","order":[{"id":"top_p","enabled":true},{"id":"math1","enabled":false},
            {"id":"temperature","enabled":true}],"bad_words_ids":[[27,28]]}}
        """))
        val id = NovelAiModel.KAYRA
        val provider = Provider("novel", protocol = ApiProtocol.NOVELAI_TEXT,
            novelAi = NovelAiSettings(presets = mapOf(id to preset), tier = 1))
        val raw = jsonOf("model" to id.id, "max_tokens" to 76)
        val body = NovelAi.buildPayload(provider, raw, TextCompletionPrompt("Write a story", emptyList()))
        val params = body.getJSONObject("parameters")
        assertEquals("[2,0]", params.getJSONArray("order").toString())
        assertEquals(76, params.getInt("max_length"))
        assertEquals(76, params.getInt("min_length"))
        assertEquals(49405, params.getInt("eos_token_id"))
        assertEquals("theme_textadventure", params.getString("prefix"))
        assertTrue(params.getJSONArray("bad_words_ids").toString().contains("[27,28]"))
        assertEquals(4096, ModelConnection(provider, id.id).contextTokens())
        val instruction = NovelAi.buildPayload(provider, raw, TextCompletionPrompt("{ Write a story }", emptyList()))
        assertEquals("special_instruct", instruction.getJSONObject("parameters").getString("prefix"))
    }

    @Test
    fun `raw script generation sends only explicit prompts with temporary stops and length`() = runBlocking {
        val requests = mutableListOf<JSONObject>()
        val (server, base) = startServer { _, _, text ->
            requests += JSONObject(text)
            val body = if (requests.size == 1) "Rina: 回答<HALT>忽略" else "Rina: 保留角色名"
            200 to ("application/json" to jsonOf("choices" to JSONArray().put(jsonOf(
                "message" to jsonOf("content" to body), "finish_reason" to "stop",
            ))).toString())
        }
        try {
            val provider = Provider("p", endpoint = "$base/v1", defaultModel = "gpt-4o")
            val connection = ModelConnection(provider, "gpt-4o")
            val turn = InstructTurn(user = "Mio", character = "Rina")
            val options = ScriptGenerationOptions(length = 72, system = "System for {{char}}", prefill = "Opening",
                trim = true, stops = listOf("<HALT>"))
            val transcript = mutableListOf<JSONArray>()
            val sampling = EffectiveSampling("0.4", "220", "0.75")
            val result = ModelService.scriptRaw(connection, "独立问题", options, null, sampling, turn,
                CustomStoppingStrings(), ReplyFormatting(), onPrompt = { transcript += it })
            val second = ModelService.scriptRaw(connection, "另一个问题", ScriptGenerationOptions(), null, sampling, turn,
                CustomStoppingStrings(), ReplyFormatting())
            assertEquals("回答", result)
            assertEquals("Rina: 保留角色名", second)
            assertEquals("gpt-4o", requests[0].getString("model"))
            assertFalse(requests[0].getBoolean("stream"))
            assertEquals(72, requests[0].getInt("max_tokens"))
            assertEquals(0.4, requests[0].getDouble("temperature"), 0.0)
            assertEquals(0.75, requests[0].getDouble("top_p"), 0.0)
            assertEquals(listOf("<HALT>"), requests[0].getJSONArray("stop").strings())
            val rows = requests[0].getJSONArray("messages").objects()
            assertEquals(listOf("system", "user", "assistant"), rows.map { it.getString("role") })
            assertEquals(listOf("System for Rina", "独立问题", "Opening"), rows.map { it.getString("content") })
            assertEquals(rows.map { it.getString("content") }, transcript.single().objects().map { it.getString("content") })
            assertEquals(220, requests[1].getInt("max_tokens"))
            assertFalse(requests[1].has("stop"))
            assertEquals(1, requests[1].getJSONArray("messages").length())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `raw prefix uses DeepSeek beta and retries with an instruction when Gemini requires a final user role`() = runBlocking {
        val requests = mutableListOf<Pair<String, JSONObject>>()
        val (server, base) = startServer { path, _, text ->
            val payload = JSONObject(text)
            requests += path to payload
            when {
                path.contains("gemini") && payload.getJSONArray("contents").objects().last().string("role") == "model" ->
                    400 to ("application/json" to """{"error":{"message":"Please ensure that the request ends with a user role."}}""")
                path.contains("gemini") ->
                    200 to ("application/json" to """{"candidates":[{"content":{"parts":[{"text":"Start answer"}]},"finishReason":"STOP"}]}""")
                else ->
                    200 to ("application/json" to """{"choices":[{"message":{"content":"answer"},"finish_reason":"stop"}]}""")
            }
        }
        try {
            val deep = Provider("d", endpoint = "$base/deep", apiKey = "fixture-key", defaultModel = "deepseek-flash",
                template = ProviderTemplate.DEEPSEEK)
            val gemini = Provider("g", endpoint = "$base/v1beta", apiKey = "fixture-key", defaultModel = "gemini-4-hydrogen",
                protocol = ApiProtocol.GEMINI_NATIVE)
            for (provider in listOf(deep, gemini)) {
                assertEquals("answer", ModelService.scriptRaw(ModelConnection(provider, provider.defaultModel), "Prompt",
                    ScriptGenerationOptions(prefill = "Start "), null, EffectiveSampling(), InstructTurn(),
                    CustomStoppingStrings(), ReplyFormatting()))
            }
            assertEquals(listOf("/deep/beta/chat/completions", "/v1beta/models/gemini-4-hydrogen:generateContent",
                "/v1beta/models/gemini-4-hydrogen:generateContent"), requests.map { it.first })
            val deepPrefix = requests[0].second.getJSONArray("messages").objects().last()
            assertEquals("Start ", deepPrefix.getString("content"))
            assertTrue(deepPrefix.getBoolean("prefix"))
            val geminiRetry = requests[2].second.getJSONArray("contents").objects().last()
            assertEquals("user", geminiRetry.getString("role"))
            assertTrue(geminiRetry.getJSONArray("parts").objects().any { it.getString("text").contains("Start ") })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `raw text completion can use instruct formatting or send a plain prompt`() = runBlocking {
        val requests = mutableListOf<Pair<String, JSONObject>>()
        val (server, base) = startServer { path, _, text ->
            requests += path to JSONObject(text)
            200 to ("application/json" to """{"results":[{"text":"Opening answer"}]}""")
        }
        try {
            val instruct = InstructTemplate("Fixture", mapOf(
                InstructSequence.STORY_PREFIX to "<sys>",
                InstructSequence.STORY_SUFFIX to "</sys>",
                InstructSequence.USER_PREFIX to "<usr>",
                InstructSequence.USER_SUFFIX to "</usr>",
                InstructSequence.QUIET to "<quiet {{name}}>",
                InstructSequence.ASSISTANT_PREFIX to "<actor>",
            ), wrap = false, prefixesAsStops = false)
            val provider = Provider("p", endpoint = base, defaultModel = "local", protocol = ApiProtocol.KOBOLD_TEXT,
                instruct = instruct)
            val connection = ModelConnection(provider, "local")
            val options = ScriptGenerationOptions(system = "Instruction", prefill = "Opening ", length = 24)
            val turn = InstructTurn(user = "Mio", character = "Rina", quiet = true, quietName = "Narrator")
            for (templated in listOf(true, false)) {
                val suffix = ModelService.scriptRaw(connection, "Prompt", options.copy(instruct = templated), null,
                    EffectiveSampling(), turn, CustomStoppingStrings(), ReplyFormatting())
                assertEquals("answer", suffix)
            }
            assertEquals(listOf("/api/v1/generate", "/api/v1/generate"), requests.map { it.first })
            assertEquals("<sys>Instruction</sys><usr>Prompt</usr><quiet Narrator>Opening ", requests[0].second.getString("prompt"))
            assertEquals("Instruction\nMio: Prompt\nOpening ", requests[1].second.getString("prompt"))
            assertTrue(requests.all { it.second.getInt("max_length") == 24 })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `quiet script prompt is appended as an instruction while retaining history and lore scan`() = runBlocking {
        val requests = mutableListOf<JSONObject>()
        val (server, base) = startServer { _, _, text ->
            requests += JSONObject(text)
            200 to ("application/json" to """{"choices":[{"message":{"content":"Complete."},"finish_reason":"stop"}]}""")
        }
        try {
            val provider = Provider("p", endpoint = "$base/v1", defaultModel = "gpt-4o")
            val connection = ModelConnection(provider, "gpt-4o")
            val book = WorldBook("test", "Book", enabled = true)
                .withEntries(listOf(LoreEntry(1, content = "LORE CANARY", keywords = listOf("quiet-key"))))
            val character = Card("r", jsonOf("name" to "Rina").toString())
            val messages = listOf(Message("user", "Mio", "KNOWN CHAT HISTORY"))
            val output = StringBuilder()
            ModelService.reply(Preferences(), connection, character, messages, listOf(book), null,
                persona = PersonaProfile("m", "Mio"), trigger = "quiet",
                effectiveSampling = EffectiveSampling(maxTokens = "56"),
                quietPrompt = ScriptQuietPrompt("quiet-key for {{char}}", "Narrator", stops = listOf("<END>")),
            ) { output.append(it) }
            assertEquals("Complete.", output.toString())
            val sent = requests.single()
            val rows = sent.getJSONArray("messages").objects()
            assertEquals("system", rows.last().getString("role"))
            assertEquals("quiet-key for Rina", rows.last().getString("content"))
            assertTrue(rows.any { it.getString("content") == "KNOWN CHAT HISTORY" })
            assertTrue(rows.any { it.getString("content").contains("LORE CANARY") })
            assertEquals(56, sent.getInt("max_tokens"))
            assertEquals(listOf("<END>"), sent.getJSONArray("stop").strings())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `token counter uses Gemini countTokens and native text token endpoints`() = runBlocking {
        val captured = mutableListOf<Triple<String, Map<String, String>, JSONObject>>()
        val (server, base) = startServer { path, headers, body ->
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
        val (server, base) = startServer { path, _, text ->
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
        val (server, base) = startServer { path, headers, text ->
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
    fun `strict prompt processing is used on each tool round and inspected requests`() = runBlocking {
        val requests = mutableListOf<JSONObject>()
        val (server, base) = startServer { _, _, text ->
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
        val (server, base) = startServer { path, headers, text ->
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
        val (server, base) = startServer { _, _, _ ->
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
    fun `DeepSeek probabilities arrive in late SSE chunks and stay with that swipe`() = runBlocking {
        val (server, base) = startServer { path, _, body ->
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
        val (server, base) = startServer { _, _, body ->
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
        val (server, base) = startServer { path, _, body ->
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
        val (server, base) = startServer { path, _, body ->
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

    @Test
    fun `Gemini search holds streaming text until late citations and suggestions arrive`() = runBlocking {
        val html = """<a href="https://www.google.com/search?q=moon">moon</a>"""
        val requests = mutableListOf<JSONObject>()
        val firstText = "月球的资料"
        val lastText = "。\n"
        val (server, base) = startServer { path, headers, body ->
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
        val (server, base) = startServer { path, _, body ->
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
        val (server, base) = startServer { path, _, body ->
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
        val (server, base) = startServer { _, _, body ->
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
        val (server, base) = startServer { _, _, body ->
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
        val (server, base) = startServer { path, _, raw ->
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
        val (server, base) = startServer { _, _, raw ->
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

    @Test
    fun `DeepSeek preset continuation uses beta then retries with previous response intact`() = runBlocking {
        val paths = mutableListOf<String>()
        val bodies = mutableListOf<JSONObject>()
        val (server, base) = startServer { path, _, body ->
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
        val (server, baseUrl) = startServer { path, headers, body ->
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
    fun `text completion sends instruct wire format across protocols and trims a streamed stop`() = runBlocking {
        val captured = mutableMapOf<String, JSONObject>()
        val (server, base) = startServer { path, _, raw ->
            captured[path] = JSONObject(raw)
            when (path) {
                "/ollama/api/generate" -> 200 to ("application/x-ndjson" to """
                    {"response":"你好<st","done":false}
                    {"response":"op>ignored","done":false}
                """.trimIndent())
                "/kobold/api/v1/generate" -> 200 to ("application/json" to """{"results":[{"text":"Kept<stop>ignored"}]}""")
                "/compat/v1/completions" -> 200 to ("text/event-stream" to
                    "data: {\"choices\":[{\"text\":\"Hello<st\"}]}\n\ndata: {\"choices\":[{\"text\":\"op>ignored\"}]}\n\n")
                "/chat/api/chat" -> 200 to ("application/json" to """{"message":{"content":"chat"},"done":true}""")
                else -> 404 to ("application/json" to "{}")
            }
        }
        try {
            val instruct = InstructTemplate.parse("""
                {"name":"Custom","wrap":false,"macro":false,"input_sequence":"<user>","input_suffix":"</user>",
                "output_sequence":"<assistant>","last_system_sequence":"<quiet>","stop_sequence":"<stop>"}
            """.trimIndent())
            val messages = JSONArray().put(jsonOf("role" to "user", "content" to "Hello"))
            suspend fun run(path: String, protocol: ApiProtocol, stream: Boolean, text: Boolean = true): String {
                val provider = Provider("id", endpoint = "$base$path", protocol = protocol, defaultModel = "chosen",
                    textCompletion = text, instruct = instruct)
                val output = StringBuilder()
                val result = ModelService.complete(provider,
                    jsonOf("model" to "chosen", "messages" to messages, "stream" to stream), {}, { output.append(it) })
                assertEquals("stop", result.finishReason)
                assertEquals(null, result.interruption)
                return output.toString()
            }
            assertEquals("你好", run("/ollama", ApiProtocol.OLLAMA, true))
            val ollama = captured.getValue("/ollama/api/generate")
            assertEquals("<user>Hello</user><quiet>", ollama.string("prompt"))
            assertTrue(ollama.getBoolean("raw"))
            assertTrue(ollama.getJSONObject("options").optJSONArray("stop").strings().contains("<stop>"))
            assertEquals("Kept", run("/kobold", ApiProtocol.KOBOLD_TEXT, false))
            assertEquals(ollama.string("prompt"), captured.getValue("/kobold/api/v1/generate").string("prompt"))
            assertEquals("Hello", run("/compat/v1/completions", ApiProtocol.KOBOLD_TEXT, true))
            assertEquals(ollama.string("prompt"), captured.getValue("/compat/v1/completions").string("prompt"))
            assertEquals("chat", run("/chat", ApiProtocol.OLLAMA, false, text = false))
            assertFalse(captured.getValue("/chat/api/chat").has("prompt"))

            val provider = Provider("local", endpoint = "$base/kobold", defaultModel = "local", streaming = false,
                protocol = ApiProtocol.KOBOLD_TEXT, instruct = instruct)
            val card = Card("c", jsonOf("name" to "Rin", "system_prompt" to "Setting").toString())
            var inspected = ""
            val generated = StringBuilder()
            val reply = ModelService.reply(Preferences(), ModelConnection(provider, "local"), card,
                listOf(Message("user", "You", "Hi")), emptyList(), null,
                persona = PersonaProfile(name = "You"), onPrompt = { inspected = it.getJSONObject(0).string("content") }) {
                generated.append(it)
            }
            assertEquals(null, reply.incomplete)
            assertEquals("Kept", generated.toString())
            assertEquals("Setting<user>Hi</user><assistant>", inspected)
            assertEquals(inspected, captured.getValue("/kobold/api/v1/generate").string("prompt"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `context only sends raw Ollama prompt and stops at the configured first line`() = runBlocking {
        var wire = JSONObject()
        val (server, base) = startServer { path, _, raw ->
            wire = JSONObject(raw)
            if (path == "/api/generate") {
                200 to ("application/x-ndjson" to """{"response":"First line\nIgnored line","done":true}""")
            } else 404 to ("application/json" to "{}")
        }
        try {
            val saved = TextContextTemplate("Plain", "{{description}}", chatStart = "BEGIN", singleLine = true)
            val provider = Provider("ollama", endpoint = base, defaultModel = "chosen", protocol = ApiProtocol.OLLAMA,
                textCompletion = true, textContext = saved)
            val card = Card("rina", jsonOf("name" to "Rina", "description" to "Description & facts").toString())
            var inspected = ""
            val output = StringBuilder()
            val result = ModelService.reply(Preferences(), ModelConnection(provider, "chosen"), card,
                listOf(Message("user", "Mio", "Write one line.")), emptyList(), null, persona = PersonaProfile(name = "Mio"),
                onPrompt = { inspected = it.getJSONObject(0).string("content") }) { output.append(it) }
            assertEquals(null, result.incomplete)
            assertEquals("First line", output.toString())
            assertEquals("Description & facts\nBEGIN\nMio: Write one line.\nRina:", inspected)
            assertEquals(inspected, wire.string("prompt"))
            assertTrue(wire.optBoolean("raw"))
            assertEquals(listOf("\n", "\nMio:"), wire.getJSONObject("options").optJSONArray("stop").strings())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `chat model switches sampler and uses its limits across local text routes`() = runBlocking {
        val requests = mutableListOf<Pair<String, JSONObject>>()
        val (server, base) = startServer { path, _, body ->
            requests += path to JSONObject(body)
            val response = when (path) {
                "/api/v1/generate" -> """{"results":[{"text":"Reply"}]}"""
                "/v1/completions" -> """{"choices":[{"text":"Reply","finish_reason":"stop"}]}"""
                "/api/chat" -> """{"message":{"role":"assistant","content":"Reply"},"done":true}"""
                "/api/generate" -> """{"response":"Reply","done":true}"""
                else -> error(path)
            }
            200 to ("application/json" to response)
        }
        try {
            val preset = TextSamplerPreset.read(JSONObject("""{
                "name":"Default","temp":0.8,"top_k":40,"max_new_tokens":120,"max_context":4096
            }"""))
            val override = TextSamplerPreset.read(JSONObject("""{
                "name":"Model B","temp":1.2,"top_k":12,"max_new_tokens":180,"max_context":2304,"rep_pen":1.2,
                "dynatemp":true,"min_temp":0.5,"max_temp":1.5,"json_schema":{"type":"object"}
            }"""))
            val provider = Provider("local", endpoint = base, defaultModel = "model-a", models = listOf("model-b"),
                protocol = ApiProtocol.KOBOLD_TEXT, streaming = false,
                textSampling = TextSamplingSettings(preset, mapOf("model-b" to override)))
            val card = Card("rina", jsonOf("name" to "Rina").toString())
            suspend fun reply(route: Provider, model: String, sampling: EffectiveSampling? = null): LoreBudgetUsage {
                var budget: LoreBudgetUsage? = null
                val prefs = Preferences(loreSettings = LoreSettings(budgetPercent = 100))
                val output = StringBuilder()
                ModelService.reply(prefs, ModelConnection(route, model), card, listOf(Message("user", "Mio", "Reply")),
                    emptyList(), null, effectiveSampling = sampling, onLoreBudget = { budget = it }) { output.append(it) }
                assertEquals("Reply", output.toString())
                return requireNotNull(budget)
            }
            val first = reply(provider, "model-a")
            assertEquals(4096 - 120, first.limit)
            val (koboldPath, kobold) = requests.last()
            assertEquals("/api/v1/generate", koboldPath)
            assertEquals(40, kobold.getInt("top_k"))
            assertEquals(120, kobold.getInt("max_length"))
            assertEquals(4096, kobold.getInt("max_context_length"))

            val second = reply(provider.copy(endpoint = "$base/v1"), "model-b",
                EffectiveSampling(temperature = "0.4", maxTokens = "150", topP = "0.7"))
            assertEquals(2304 - 150, second.limit)
            val (llamaPath, llama) = requests.last()
            assertEquals("/v1/completions", llamaPath)
            assertEquals(12, llama.getInt("top_k"))
            assertEquals(150, llama.getInt("max_tokens"))
            assertEquals(0.4, llama.getDouble("temperature"), 0.000001)
            assertEquals(0.7, llama.getDouble("top_p"), 0.000001)
            assertFalse(llama.has("dynatemp_range"))
            assertFalse(llama.has(TEXT_SAMPLER_PAYLOAD))

            val ollama = provider.copy(protocol = ApiProtocol.OLLAMA)
            for (textCompletion in listOf(false, true)) {
                reply(ollama.copy(textCompletion = textCompletion), "model-b")
                val (path, body) = requests.last()
                assertEquals(if (textCompletion) "/api/generate" else "/api/chat", path)
                val options = body.getJSONObject("options")
                assertEquals(12, options.getInt("top_k"))
                assertEquals(180, options.getInt("num_predict"))
                assertEquals(2304, options.getInt("num_ctx"))
                assertEquals("object", body.getJSONObject("format").getString("type"))
                assertFalse(options.has("dynatemp_range"))
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `model context selects a longer or shorter suffix and reports original chat indices`() = runBlocking {
        val sent = mutableListOf<JSONObject>()
        val (server, base) = startServer { _, _, body ->
            sent += JSONObject(body)
            200 to ("application/json" to """{"choices":[{"message":{"content":"Reply"},"finish_reason":"stop"}]}""")
        }
        try {
            val provider = Provider("local", endpoint = base, defaultModel = "wide", streaming = false,
                modelContexts = mapOf("wide" to 8192, "narrow" to 480, "tiny" to 165))
            val history = (0..110).map { index ->
                Message(if (index % 2 == 0) "user" else "assistant", if (index % 2 == 0) "Mio" else "Rina",
                    "Turn ${index.toString().padStart(3, '0')} ${"a".repeat(22)}", date = "message-$index", hidden = index == 2)
            }
            val card = Card("rina", jsonOf("name" to "Rina", "description" to "Starts {{firstIncludedMessageId}}").toString())
            suspend fun reply(model: String): PromptBudgetUsage {
                var usage: PromptBudgetUsage? = null
                ModelService.reply(Preferences(maxTokens = "120"), ModelConnection(provider, model), card, history,
                    emptyList(), null, loreCounter = TextTokenCounter("Characters", String::length),
                    onContextBudget = { usage = it }) {}
                return requireNotNull(usage)
            }
            val wide = reply("wide")
            val wideRows = sent.last().getJSONArray("messages").objects()
            assertEquals(0, wide.omittedMessages)
            assertEquals(110, wideRows.count { it.string("content").startsWith("Turn ") })
            assertFalse(wideRows.any { it.string("content").startsWith("Turn 002") })
            val narrow = reply("narrow")
            val narrowRows = sent.last().getJSONArray("messages").objects()
            assertTrue(narrow.omittedMessages > 0)
            assertTrue(narrow.used <= narrow.limit)
            assertEquals(360, narrow.limit)
            assertEquals(120, sent.last().getInt("max_tokens"))
            val first = requireNotNull(narrow.firstHistoryIndex)
            assertTrue(narrowRows.first().string("content").contains("Starts $first"))
            assertEquals(history[first].text, narrowRows[1].string("content"))
            assertEquals(history.last().text, narrowRows.last().string("content"))
            val failure = runCatching { reply("tiny") }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("输入额度为 45"))
            assertEquals(2, sent.size)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `pinning examples changes the history selected for a formatted text request`() = runBlocking {
        var sent = JSONObject()
        val (server, base) = startServer { _, _, body ->
            sent = JSONObject(body)
            200 to ("application/json" to """{"response":"Reply","done":true}""")
        }
        try {
            val provider = Provider("ollama", endpoint = base, defaultModel = "small", protocol = ApiProtocol.OLLAMA,
                streaming = false, textCompletion = true, contextLimit = 600,
                textContext = TextContextTemplate("Plain", "{{description}}"))
            val history = (1..14).map { Message("user", "Mio", "Turn $it ${"m".repeat(32)}") }
            val card = Card("rina", jsonOf("name" to "Rina", "description" to "A brief setting",
                "mes_example" to "<START>\nMio: Example question\nRina: ${"e".repeat(120)}").toString())
            suspend fun reply(mode: ExampleBudget, route: Provider = provider): PromptBudgetUsage {
                var budget: PromptBudgetUsage? = null
                ModelService.reply(Preferences(maxTokens = "100", exampleBudget = mode), ModelConnection(route, "small"),
                    card, history, emptyList(), null, persona = PersonaProfile(name = "Mio"),
                    loreCounter = TextTokenCounter("Characters", String::length),
                    onContextBudget = { budget = it }) {}
                return requireNotNull(budget)
            }
            val unpinned = reply(ExampleBudget.AVAILABLE_SPACE)
            assertFalse(sent.string("prompt").contains("Example question"))
            assertEquals(sent.string("prompt").length.toLong(), unpinned.used)
            val pinned = reply(ExampleBudget.PINNED)
            assertTrue(sent.string("prompt").contains("Example question"))
            assertTrue(sent.string("prompt").contains("Turn 14"))
            assertTrue(pinned.omittedMessages > unpinned.omittedMessages)
            assertTrue(pinned.used <= pinned.limit)
            val wide = provider.copy(contextLimit = 8192)
            reply(ExampleBudget.AVAILABLE_SPACE, wide)
            assertTrue(sent.string("prompt").contains("Example question"))
            assertEquals(0, reply(ExampleBudget.OMITTED, wide).omittedMessages)
            assertFalse(sent.string("prompt").contains("Example question"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `custom stop strings survive a wire rejection and terminate split stream tokens`() = runBlocking {
        val attempts = mutableListOf<JSONObject>()
        val (server, base) = startServer { _, _, body ->
            val request = JSONObject(body)
            attempts += request
            if (request.has("stop")) {
                400 to ("application/json" to """{"error":{"message":"Unsupported parameter: 'stop'"}}""")
            } else {
                200 to ("text/event-stream" to ("data: {\"choices\":[{\"delta\":{\"content\":\"Keep <ST\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":\"OP> ignore\"}}]}\n\n"))
            }
        }
        try {
            val provider = Provider("provider", endpoint = base, defaultModel = "chosen")
            val stops = listOf("<STOP>", "<2>", "<3>", "<4>", "<5>")
            val output = StringBuilder()
            var retries = 0
            val result = ModelService.complete(provider, jsonOf("model" to "chosen", "stream" to true),
                {}, { output.append(it) }, onRetry = { retries++ }, customStops = stops)
            assertEquals(2, attempts.size)
            assertEquals(stops.take(4), attempts.first().optJSONArray("stop").strings())
            assertFalse(attempts.last().has("stop"))
            assertEquals(1, retries)
            assertEquals("Keep ", output.toString())
            assertTrue(result.reachedTextStop)
            assertEquals(null, result.interruption)
            assertEquals("stop", result.finishReason)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `native providers send stop strings in vendor fields and keep filtered results`() = runBlocking {
        val captured = mutableMapOf<String, JSONObject>()
        val (server, base) = startServer { path, _, body ->
            captured[path] = JSONObject(body)
            val response = when (path) {
                "/anthropic/v1/messages" -> """{"content":[{"type":"text","text":"Text<END>discard"}],"stop_reason":"end_turn"}"""
                "/gemini/v1beta/models/chosen:generateContent" ->
                    """{"candidates":[{"content":{"parts":[{"text":"Text<END>discard"}]},"finishReason":"STOP"}]}"""
                "/ollama/api/chat" -> """{"message":{"content":"Text<END>discard"},"done":true}"""
                "/kobold/api/v1/generate" -> """{"results":[{"text":"Text<END>discard"}]}"""
                else -> null
            }
            if (response == null) 404 to ("application/json" to "{}") else 200 to ("application/json" to response)
        }
        try {
            suspend fun call(path: String, protocol: ApiProtocol) {
                val provider = Provider("p", endpoint = "$base/$path", defaultModel = "chosen", protocol = protocol, promptCaching = false)
                val output = StringBuilder()
                val result = ModelService.complete(provider,
                    jsonOf("model" to "chosen", "stream" to false, "messages" to JSONArray().put(jsonOf("role" to "user", "content" to "Hi"))),
                    {}, { output.append(it) }, customStops = listOf("<END>"))
                assertEquals(path, "Text", output.toString())
                assertTrue(result.reachedTextStop)
            }
            call("anthropic", ApiProtocol.ANTHROPIC)
            call("gemini/v1beta", ApiProtocol.GEMINI_NATIVE)
            call("ollama", ApiProtocol.OLLAMA)
            call("kobold", ApiProtocol.KOBOLD_TEXT)
            assertEquals(listOf("<END>"), captured.getValue("/anthropic/v1/messages").optJSONArray("stop_sequences").strings())
            assertEquals(listOf("<END>"), captured.getValue("/gemini/v1beta/models/chosen:generateContent")
                .getJSONObject("generationConfig").optJSONArray("stopSequences").strings())
            assertEquals(listOf("<END>"), captured.getValue("/ollama/api/chat").getJSONObject("options").optJSONArray("stop").strings())
            assertEquals("<END>", captured.getValue("/kobold/api/v1/generate").getJSONArray("stop_sequence").getString(0))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `assistant prefix uses DeepSeek beta and model rejection retries with an instruction`() = runBlocking {
        val requests = mutableListOf<Pair<String, JSONObject>>()
        val (server, base) = startServer { path, _, wire ->
            val data = JSONObject(wire)
            requests += path to data
            val row = data.getJSONArray("messages").objects().last()
            if (path.startsWith("/unsupported") && row.string("role") == "assistant") {
                400 to ("application/json" to """{"error":{"message":"This model does not support assistant message prefill."}}""")
            } else {
                200 to ("text/event-stream" to ("data: {\"choices\":[{\"delta\":{\"content\":\"Cue: <th\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":\"ink>plan</think>\\nDone\"},\"finish_reason\":\"stop\"}]}\n\n"))
            }
        }
        try {
            val format = ReplyFormatting("Cue: ", false, ReasoningFormat("XML", "<think>", "</think>", "\n"), true)
            val card = Card("id", jsonOf("name" to "Rin").toString())
            val history = listOf(Message("user", "Mio", "Continue"))
            suspend fun reply(provider: Provider) {
                val tokens = StringBuilder()
                val reasoning = StringBuilder()
                val result = ModelService.reply(Preferences(replyFormatting = format), ModelConnection(provider, "id"), card, history,
                    emptyList(), null, onReasoning = { reasoning.append(it) }) { tokens.append(it) }
                assertEquals("Cue: Done", tokens.toString())
                assertEquals("plan", reasoning.toString())
                assertEquals("Cue: ", result.hiddenReplyPrefix)
                assertTrue(result.hasReasoning)
            }
            reply(Provider("deep", endpoint = "$base/deep", defaultModel = "id", template = ProviderTemplate.DEEPSEEK))
            assertEquals("/deep/beta/chat/completions", requests.first().first)
            val assistant = requests.first().second.getJSONArray("messages").objects().last()
            assertEquals("Cue: ", assistant.string("content"))
            assertTrue(assistant.optBoolean("prefix"))
            reply(Provider("custom", endpoint = "$base/unsupported", defaultModel = "id"))
            assertEquals(3, requests.size)
            assertEquals("assistant", requests[1].second.getJSONArray("messages").objects().last().string("role"))
            val fallback = requests.last().second.getJSONArray("messages").objects().last()
            assertEquals("user", fallback.string("role"))
            assertTrue(fallback.string("content").endsWith("Cue: "))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `discovered backend formatting reaches wire and explicit chat model binding wins`() = runBlocking {
        val captured = mutableListOf<Pair<String, JSONObject>>()
        val token = "<|im_start|>user / <|im_start|>assistant / <|im_end|>"
        val (server, base) = startServer { path, _, raw ->
            if (path.startsWith("/llama/props?model=local+chatml")) {
                200 to ("application/json" to jsonOf("chat_template" to token,
                    "default_generation_settings" to jsonOf("n_ctx" to 32_768)).toString())
            } else if (path == "/llama/v1/completions" || path == "/kobold/api/v1/generate") {
                captured += path to JSONObject(raw)
                val body = if (path.endsWith("/completions")) """{"choices":[{"text":"reply","finish_reason":"stop"}]}"""
                    else """{"results":[{"text":"reply"}]}"""
                200 to ("application/json" to body)
            } else 404 to ("application/json" to """{"error":"unexpected endpoint"}""")
        }
        try {
            val provider = Provider("local", endpoint = "$base/llama/v1", defaultModel = "local chatml",
                protocol = ApiProtocol.KOBOLD_TEXT, streaming = false)
            val properties = ProviderProtocols.modelProperties(ModelConnection(provider, provider.defaultModel), bundledFormatCatalogForTest())
            val manual = ModelFormatRule(target = "manual", templates = ModelTemplatePair(
                instruct = InstructTemplate("Custom", mapOf(InstructSequence.ASSISTANT_PREFIX to "[ALT]"), wrap = false)))
            val formatted = provider.copy(modelFormatting = ModelFormatting(deriveInstruct = true, deriveContext = true,
                deriveContextSize = true, rules = listOf(manual)).withBackend(provider.defaultModel, properties))
            val prefs = Preferences(providers = listOf(formatted), defaultSelection = formatted.defaultSelection)
            val chat = Chat("id", "card", "Chat")
            val card = Card("card", jsonOf("name" to "Rin", "description" to "Description").toString())
            suspend fun reply(connection: ModelConnection) {
                val output = StringBuilder()
                ModelService.reply(prefs, connection, card, listOf(Message("user", "Mio", "Hi")),
                    emptyList(), null, persona = PersonaProfile(name = "Mio")) { output.append(it) }
                assertEquals("reply", output.toString())
            }
            reply(prefs.connectionFor(chat)!!)
            assertTrue(captured[0].second.string("prompt").endsWith("<|im_start|>assistant\n"))
            reply(prefs.connectionFor(chat.copy(modelOverride = ModelSelection(provider.id, "manual")))!!)
            assertTrue(captured[1].second.string("prompt").endsWith("[ALT]"))
            reply(ModelConnection(formatted.copy(endpoint = "$base/kobold/api/v1"), provider.defaultModel))
            assertEquals("/kobold/api/v1/generate", captured[2].first)
            assertEquals(32_768, captured[2].second.getInt("max_context_length"))
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
