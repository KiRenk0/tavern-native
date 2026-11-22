package app.tavern.nativeclient

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChatTokenizersTest {
    private val tokenizers = ChatTokenizers { path ->
        listOf(File("app/src/main/assets", path), File("src/main/assets", path)).first(File::isFile).inputStream()
    }

    @Test
    fun `NovelAI token IDs match the three original model vocabularies`() {
        val provider = Provider("novel", protocol = ApiProtocol.NOVELAI_TEXT)
        val examples = mapOf(
            "你好   世界\nRina: こんにちは🌸" to listOf(
                listOf(55345, 49685, 14, 49209, 1452, 85, 49284, 1661, 49287, 49209, 32380, 55144),
                listOf(55345, 49685, 14, 49209, 1452, 85, 49284, 1661, 49287, 49209, 32380, 55144),
                listOf(57668, 53901, 256, 127365, 198, 49, 2259, 25, 220, 90115, 9468, 234, 116),
            ),
            " ＡＢＣ 1234\tMercy's\n****\n<|mask|>{Hello}" to listOf(
                listOf(49209, 51067, 51507, 51388, 49209, 5, 6, 7, 8, 84, 49266, 27116, 49256, 49216, 85,
                    23, 49399, 85, 52, 49333, 13071, 49332),
                listOf(49209, 51067, 51507, 51388, 49209, 124, 125, 126, 127, 84, 49266, 27116, 5, 85,
                    23, 49399, 85, 52, 49333, 13071, 49332),
                listOf(63923, 94, 124944, 117812, 220, 4513, 19, 9391, 3035, 88, 596, 198, 67619, 27, 91,
                    11508, 91, 7457, 9906, 92),
            ),
            "▁word　next􏿿" to listOf(
                listOf(1611, 49364, 20976, 319, 218, 266, 266),
                listOf(1611, 49364, 20976, 319, 218, 266, 266),
                listOf(10634, 223, 1178, 23249, 3684, 176, 237, 123, 123),
            ),
        )
        NovelAiModel.entries.forEachIndexed { index, model ->
            val counter = tokenizers.counterFor(ModelConnection(provider, model.id))
            examples.forEach { (text, values) ->
                assertEquals(model.label, values[index], counter.encode!!(text).toList())
                assertEquals(values[index].size, counter.count(text))
            }
        }
        val erato = tokenizers.counterFor(ModelConnection(provider, NovelAiModel.ERATO.id))
        assertEquals(listOf(128000, 14262, 319, 7816, 0), erato.encode!!("<|begin_of_text|>Welcome\r\nMore!").toList())
    }

    @Test
    fun `preview and history budget use unicode-aware GPT vocabulary for active model`() = runBlocking {
        val provider = Provider("oai")
        val older = ModelConnection(provider, "gpt-4-0613")
        val newer = ModelConnection(provider, "openai/gpt-4o")
        val text = "你好🌸"
        val legacy = tokenizers.inspect(older, text, TextTokenizer.MODEL, remote = false)
        val modern = tokenizers.inspect(newer, text, TextTokenizer.MODEL, remote = false)
        assertEquals(listOf(57668, 53901, 9468, 234, 116), legacy.pieces.map(TokenPiece::id))
        assertEquals(listOf(177519, 64364, 116), modern.pieces.map(TokenPiece::id))
        assertEquals("你好", modern.pieces.first().text)
        assertEquals("[B8]", modern.pieces.last().text)
        assertEquals(3, tokenizers.counterFor(newer).count(text))
        val overridden = provider.copy(textTokenizer = TextTokenizer.CL100K)
        assertEquals(5, tokenizers.counterFor(ModelConnection(overridden, newer.model)).count(text))
        assertEquals(TextTokenizer.CL100K, Provider.fromJson(overridden.storageJson { "" }) { "" }.textTokenizer)
        val unknown = tokenizers.inspect(ModelConnection(provider, "some-local"), text, TextTokenizer.MODEL, remote = false)
        assertEquals(estimatedTokens.label, unknown.source)
        assertTrue(unknown.pieces.isEmpty())
    }

    @Test
    fun `DeepSeek V41 preview matches canonical tokenizer including added tokens`() = runBlocking {
        val connection = ModelConnection(Provider("deepseek", template = ProviderTemplate.DEEPSEEK), "deepseek-flash")
        // Reference IDs from the official DeepSeek-V4.1-Flash tokenizer.json with add_special_tokens=false.
        val fixtures = mapOf(
            "Hello SillyTavern Native roleplay world!" to listOf(19923, 10550, 367, 39919, 2152, 22109, 3615, 3026, 2058, 3),
            "你好，月亮🌸" to listOf(30594, 303, 39629, 116448),
            "月の物語。12345\r\n Next..." to listOf(1266, 1576, 1196, 10967, 320, 6895, 1883, 204, 201, 14493, 1613),
            "<think>hello</think><｜deepseek_image｜>" to listOf(128821, 33310, 128822, 129264),
            "\u00a0hello\u2003\uD801\uDC37\t你好\n" to listOf(2162, 33310, 77469, 120577, 241, 118, 200, 30594, 201),
        )
        for ((text, ids) in fixtures) {
            val result = tokenizers.inspect(connection, text, TextTokenizer.MODEL, remote = false)
            assertEquals(ids, result.pieces.map(TokenPiece::id))
            assertEquals(ids.size, tokenizers.counterFor(connection).count(text))
        }
    }

    @Test
    fun `inspection limits fragments and full chat count filters system rows`() = runBlocking {
        val connection = ModelConnection(Provider("oai"), "gpt-4o")
        val text = "hello ".repeat(600)
        val inspection = tokenizers.inspect(connection, text, TextTokenizer.MODEL, remote = false)
        assertEquals(601, inspection.count)
        assertEquals(TOKEN_PREVIEW_PIECES, inspection.pieces.size)
        val chat = Chat("chat", "role", "Chat", messages = listOf(
            Message("user", "Persona", "hello"),
            Message("system", "Narrator", "system hint"),
            Message("assistant", "Role", "world"),
        ))
        assertEquals("hello world", chatTokenText(chat))
        val counted = tokenizers.countChat(chat, connection, TextTokenizer.MODEL)
        assertEquals(2, counted.count)
    }

    @Test
    fun `trimming both directions preserves supplementary unicode under selected vocabulary`() {
        val counter = tokenizers.counterFor(ModelConnection(Provider("oai"), "gpt-4o"))
        assertEquals("A", trimTokenText("A🌸B", 1, false, counter))
        assertEquals("B", trimTokenText("A🌸B", 1, true, counter))
        assertEquals("", trimTokenText("🌸", 1, true, counter))
        assertEquals("🌸", trimTokenText("🌸", 2, false, counter))
        assertEquals("", trimTokenText("hello", 0, false, counter))
    }

    @Test
    fun `slash count returns full conversation tokens to STscript pipelines`() = runBlocking {
        val provider = Provider("oai", endpoint = "http://127.0.0.1:1234/v1", defaultModel = "gpt-4o")
        val chat = Chat("chat", "role", "Chat", messages = listOf(
            Message("user", "Persona", "hello"),
            Message("system", "Narrator", "one two three four"),
            Message("assistant", "Role", "world"),
        ))
        val state = TavernState(chat = chat, prefs = Preferences(providers = listOf(provider), defaultSelection = provider.defaultSelection))
        val notes = mutableListOf<String>()
        val host = TavernScriptHost(
            snapshot = { state }, input = "", saveChat = {}, savePrefs = {}, ask = { null }, trigger = { _, _ -> },
            quiet = { _, _ -> "" }, draft = {}, saveVariables = { _, _ -> }, tokenizers = tokenizers, notice = { notes += it },
        )
        val script = StScript(host)
        assertEquals("2", script.run("/count | /pass {{pipe}}"))
        assertTrue(notes.single().contains("o200k_base"))
        assertEquals("B", script.run("/trimtokens limit=1 direction=start A🌸B"))
    }
}
