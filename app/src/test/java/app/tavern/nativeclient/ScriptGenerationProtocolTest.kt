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

class ScriptGenerationProtocolTest {

    @Test
    fun `raw script generation sends only explicit prompts with temporary stops and length`() = runBlocking {
        val requests = mutableListOf<JSONObject>()
        val (server, base) = startProtocolTestServer { _, _, text ->
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
        val (server, base) = startProtocolTestServer { path, _, text ->
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
        val (server, base) = startProtocolTestServer { path, _, text ->
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
        val (server, base) = startProtocolTestServer { _, _, text ->
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
}
