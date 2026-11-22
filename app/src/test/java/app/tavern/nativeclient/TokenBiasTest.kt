package app.tavern.nativeclient

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TokenBiasTest {
    @Test
    fun `original preset rows survive import export and settings round trip`() {
        val rows = """[{"id":"a","text":" bond","value":-50},{"id":"a","text":"[123,456]","value":0.25}]"""
        val preset = TokenBiasPreset.fromOriginal("\uFEFF$rows", "Imported")
        assertEquals(" bond", preset.rows.first().text)
        assertEquals(2, preset.rows.map { it.id }.distinct().size)
        assertEquals(preset.rows, TokenBiasPreset.fromOriginal(preset.originalJson().toString(), preset.name).rows)
        val selected = TokenBiasSelection(preset.id, BiasTokenizer.CL100K)
        val settings = TokenBiasSettings(listOf(preset), selected, mapOf("disabled" to TokenBiasSelection()))
        val provider = Provider("connection", tokenBias = settings)
        assertEquals(settings, Provider.fromJson(provider.storageJson { "" }, { "" }).tokenBias)
        val beforeBias = Provider("old").storageJson { "" }.apply { remove("tokenBias") }
        assertEquals(TokenBiasSettings(), Provider.fromJson(beforeBias) { "" }.tokenBias)
        assertEquals(settings.default, settings.forModel("new"))
        assertEquals("", settings.forModel("disabled").presetId)
        assertEquals(TokenBiasSelection(), settings.remove(preset.id).forModel("new"))
    }

    @Test
    fun `only nonnegative integer IDs and finite in-range values are allowed`() {
        for (ids in listOf("[1.5]", "[-1]", "[2147483648]", """["12"]""")) {
            assertThrows(IllegalArgumentException::class.java) {
                TokenBiasPreset("bad", "Invalid", listOf(TokenBiasRow("a", ids, -10.0))).validate()
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            TokenBiasPreset("bad", "Invalid", listOf(TokenBiasRow("a", "[123]", Double.NaN))).validate()
        }
        assertEquals(" hello", originalBiasText("hello", true))
        assertEquals("hello ", originalBiasText(" {hello } ", true))
        assertEquals(" {hello } ", originalBiasText(" {hello } ", false))
    }

    @Test
    fun `model switches tokenize independently and later rows replace earlier bias`(): Unit = runBlocking {
        val preset = TokenBiasPreset("bias", "Test", listOf(
            TokenBiasRow("a", "hello", -50.0), TokenBiasRow("b", "[15339,0]", 0.25),
        ))
        val selected = TokenBiasSelection(preset.id)
        val provider = Provider("connection", tokenBias = TokenBiasSettings(
            listOf(preset), selected, mapOf("off" to TokenBiasSelection()),
        ))
        val oldTokens = requireNotNull(ModelConnection(provider, "gpt-4-0613").compileTokenBias())
        assertEquals(2, oldTokens.length())
        assertEquals(0.25, oldTokens.getDouble("15339"), 0.0)
        assertEquals(0.25, oldTokens.getDouble("0"), 0.0)
        val newTokens = requireNotNull(ModelConnection(provider, "openai/gpt-4o:online").compileTokenBias())
        assertEquals(-50.0, newTokens.getDouble("24912"), 0.0)
        assertEquals(0.25, newTokens.getDouble("15339"), 0.0)
        assertNull(ModelConnection(provider, "off").compileTokenBias())
        assertNull(ModelConnection(provider.copy(template = ProviderTemplate.DEEPSEEK), "gpt-4").compileTokenBias())
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { ModelConnection(provider, "unknown").compileTokenBias() }
        }
    }

    @Test
    fun `Kobold style preset JSON can supply bias rows alongside its other samplers`() {
        val json = """{"logit_bias":[{"text":"{你好}","value":10}],"temperature":0.8}"""
        val preset = TokenBiasPreset.fromOriginal(json, "Kobold")
        assertEquals("{你好}", preset.rows.single().text)
        assertEquals(10.0, preset.rows.single().value, 0.0)
    }

    @Test
    fun `built in encodings match tiktoken for CJK emoji and leading spaces`() = runBlocking {
        val rows = listOf(" hello", "你好", "🌸", "接続").mapIndexed { index, text ->
            TokenBiasRow(index.toString(), text, (index + 1).toDouble())
        }
        val preset = TokenBiasPreset("bias", "Test", rows)
        val provider = Provider("connection", tokenBias = TokenBiasSettings(listOf(preset)))
        // Reference IDs from OpenAI tiktoken encode_ordinary with the corresponding vocabularies.
        val gpt2 = listOf(listOf(23748), listOf(19526, 254, 25001, 121), listOf(8582, 234, 116), listOf(162, 236, 98, 163, 114, 248))
        val fixtures = mapOf(
            BiasTokenizer.CL100K to listOf(listOf(24748), listOf(57668, 53901), listOf(9468, 234, 116), listOf(30177, 163, 90183)),
            BiasTokenizer.O200K to listOf(listOf(40617), listOf(177519), listOf(64364, 116), listOf(9878, 47554)),
            BiasTokenizer.R50K to gpt2,
            BiasTokenizer.P50K to gpt2,
            BiasTokenizer.P50K_EDIT to gpt2,
        )
        for ((encoding, ids) in fixtures) {
            val result = requireNotNull(ModelConnection(provider, "other").compileTokenBias(TokenBiasSelection(preset.id, encoding)))
            val expected = buildMap { ids.forEachIndexed { index, tokens -> tokens.forEach { put(it.toString(), index + 1) } } }
            assertEquals(expected, result.keys().asSequence().associateWith(result::getInt))
        }
    }
}
