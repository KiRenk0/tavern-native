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

class NovelAiProtocolTest {

    @Test
    fun `NovelAI chat switches model presets and preserves interrupted streaming text`() = runBlocking {
        val requests = mutableListOf<Pair<String, JSONObject>>()
        var streamed = 0
        val (server, base) = startProtocolTestServer { path, headers, text ->
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
}
