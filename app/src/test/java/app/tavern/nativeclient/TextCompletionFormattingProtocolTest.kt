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

class TextCompletionFormattingProtocolTest {

    @Test
    fun `text completion sends instruct wire format across protocols and trims a streamed stop`() = runBlocking {
        val captured = mutableMapOf<String, JSONObject>()
        val (server, base) = startProtocolTestServer { path, _, raw ->
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
        val (server, base) = startProtocolTestServer { path, _, raw ->
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
        val (server, base) = startProtocolTestServer { path, _, body ->
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
        val (server, base) = startProtocolTestServer { _, _, body ->
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
        val (server, base) = startProtocolTestServer { _, _, body ->
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
        val (server, base) = startProtocolTestServer { _, _, body ->
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
        val (server, base) = startProtocolTestServer { path, _, body ->
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
        val (server, base) = startProtocolTestServer { path, _, wire ->
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
        val (server, base) = startProtocolTestServer { path, _, raw ->
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
}
