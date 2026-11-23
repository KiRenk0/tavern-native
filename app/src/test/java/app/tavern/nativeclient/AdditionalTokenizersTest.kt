package app.tavern.nativeclient

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AdditionalTokenizersTest {

    private val tokenizers = ChatTokenizers { path ->
        listOf(File("app/src/main/assets", path), File("src/main/assets", path)).first(File::isFile).inputStream()
    }

    @Test
    fun `auto-matching detects all 7 new tokenizer families by model ID without false positives`() {
        val cases = mapOf(
            "mistral-large-latest" to TextTokenizer.MISTRAL_NEMO,
            "mistralai/Mistral-Nemo-Instruct-2407" to TextTokenizer.MISTRAL_NEMO,
            "open-mistral-nemo" to TextTokenizer.MISTRAL_NEMO,
            "codestral-latest" to TextTokenizer.MISTRAL_NEMO,
            "Qwen/Qwen2.5-72B-Instruct" to TextTokenizer.QWEN,
            "qwen3-32b" to TextTokenizer.QWEN,
            "qwq-32b" to TextTokenizer.QWEN,
            "google/gemma-2-27b-it" to TextTokenizer.GEMMA,
            "gemma-3-12b-it" to TextTokenizer.GEMMA,
            "claude-sonnet-4-5" to TextTokenizer.CLAUDE,
            "anthropic/claude-3.7-sonnet" to TextTokenizer.CLAUDE,
            "command-r-plus-08-2024" to TextTokenizer.COMMAND_R,
            "command-a-03-2025" to TextTokenizer.COMMAND_R,
            "CohereForAI/c4ai-command-r-v01" to TextTokenizer.COMMAND_R,
            "meta-llama/Llama-2-70b-chat-hf" to TextTokenizer.LLAMA_1_2,
            "Gryphe/MythoMax-L2-13b" to TextTokenizer.LLAMA_1_2,
            "codellama-34b-instruct" to TextTokenizer.LLAMA_1_2,
            "01-ai/Yi-1.5-34B-Chat" to TextTokenizer.YI,
            "yi-large" to TextTokenizer.YI,
            "yi-lightning" to TextTokenizer.YI,
        )
        for ((modelId, expected) in cases) {
            assertEquals("Expected $expected for $modelId", expected, AdditionalTokenizers.forModel(modelId))
            val preview = runBlocking {
                tokenizers.inspect(ModelConnection(Provider("p"), modelId), "Hello world", TextTokenizer.MODEL, remote = false)
            }
            assertTrue("Expected ${expected.label} in source '${preview.source}'", preview.source.contains(expected.label))
            assertTrue(preview.count > 0)
            assertEquals(preview.count, preview.pieces.size)
        }

        // Must not falsely match unrelated or Llama-3 models.
        assertNull(AdditionalTokenizers.forModel("some-local"))
        assertNull(AdditionalTokenizers.forModel("meta-llama/Llama-3.3-70B-Instruct"))
        assertNull(AdditionalTokenizers.forModel("saying-hello-model"))
    }

    @Test
    fun `all 7 tokenizer profiles produce deterministic IDs within vocab bounds and consistent counts`() = runBlocking {
        val sampleText = "Hello Tavern Native! 123456 你好世界 🌸\nNext line."
        val newTokenizers = listOf(
            TextTokenizer.MISTRAL_NEMO,
            TextTokenizer.QWEN,
            TextTokenizer.GEMMA,
            TextTokenizer.CLAUDE,
            TextTokenizer.COMMAND_R,
            TextTokenizer.LLAMA_1_2,
            TextTokenizer.YI,
        )

        for (mode in newTokenizers) {
            val info = requireNotNull(AdditionalTokenizers.profileInfo(mode))
            val counter = tokenizers.counterFor(null, mode)
            val encodeFn = requireNotNull(counter.encode) { "encode should be available for $mode" }
            val ids1 = encodeFn(sampleText)
            val ids2 = encodeFn(sampleText)
            assertArrayEquals("Deterministic encoding failed for $mode", ids1, ids2)
            assertEquals("Count must match encoded length for $mode", ids1.size, counter.count(sampleText))
            assertTrue("All IDs must be within [0, ${info.vocabSize}) for $mode", ids1.all { it in 0 until info.vocabSize })

            val preview = tokenizers.inspect(null, sampleText, mode, remote = false)
            assertEquals(ids1.size, preview.count)
            assertEquals(ids1.toList(), preview.pieces.map(TokenPiece::id))
            assertTrue(preview.pieces.all { !it.text.isNullOrEmpty() })

            // Empty string produces 0 tokens.
            assertEquals(0, counter.count(""))
            assertEquals(0, encodeFn("").size)

            // Provider persistence round-trip preserves selected tokenizer.
            val provider = Provider("p", textTokenizer = mode)
            val restored = Provider.fromJson(provider.storageJson { it }) { it }
            assertEquals(mode, restored.textTokenizer)
        }
    }

    @Test
    fun `family-specific digit grouping, special tokens, and CJK byte fallback match real tokenizer traits`() {
        val mistral = AdditionalTokenizers.tokenizerFor(TextTokenizer.MISTRAL_NEMO)!!
        val qwen = AdditionalTokenizers.tokenizerFor(TextTokenizer.QWEN)!!
        val gemma = AdditionalTokenizers.tokenizerFor(TextTokenizer.GEMMA)!!
        val claude = AdditionalTokenizers.tokenizerFor(TextTokenizer.CLAUDE)!!
        val commandR = AdditionalTokenizers.tokenizerFor(TextTokenizer.COMMAND_R)!!
        val llama2 = AdditionalTokenizers.tokenizerFor(TextTokenizer.LLAMA_1_2)!!
        val yi = AdditionalTokenizers.tokenizerFor(TextTokenizer.YI)!!

        // Single-digit splitting (Qwen, Gemma, Command-R, Llama 1/2, Yi) vs 3-digit grouping (Mistral/Tekken, Claude).
        assertEquals(6, qwen.count("123456"))
        assertEquals(6, gemma.count("123456"))
        assertEquals(6, commandR.count("123456"))
        assertEquals(6, llama2.count("123456"))
        assertEquals(6, yi.count("123456"))
        assertEquals(2, mistral.count("123456"))
        assertEquals(2, claude.count("123456"))

        // Special token canonical IDs.
        assertEquals(listOf(151_644, 151_645), qwen.encode("<|im_start|><|im_end|>").toList())
        assertEquals(listOf(2, 106, 107, 1), gemma.encode("<bos><start_of_turn><end_of_turn><eos>").toList())
        assertEquals(listOf(5, 255_006, 255_007, 255_001), commandR.encode("<BOS_TOKEN><|START_OF_TURN_TOKEN|><|END_OF_TURN_TOKEN|><EOS_TOKEN>").toList())
        assertEquals(listOf(1, 3, 4, 2), mistral.encode("<s>[INST][/INST]</s>").toList())
        assertEquals(listOf(1, 259, 260, 2), llama2.encode("<s>[INST][/INST]</s>").toList())
        assertEquals(listOf(1, 6, 7, 2), yi.encode("<|startoftext|><|im_start|><|im_end|><|endoftext|>").toList())

        // Llama 1/2 UTF-8 byte fallback for CJK ("你好" = 6 UTF-8 bytes -> 6 byte tokens in 3..258).
        val llamaCjk = llama2.preview("你好")
        assertEquals(6, llamaCjk.count)
        assertTrue(llamaCjk.pieces.all { it.id in 3..258 && it.text!!.startsWith("<0x") })

        // Qwen & Yi CJK-dense vocabularies merge common bigrams ("你好世界" -> 2 tokens).
        assertEquals(2, qwen.count("你好世界"))
        assertEquals(2, yi.count("你好世界"))

        // Gemma / Command-R / Mistral multilingual CJK encodes cleanly without 3x byte explosion.
        assertEquals(4, gemma.count("你好世界"))
        assertEquals(4, commandR.count("你好世界"))
        assertEquals(4, mistral.count("你好世界"))

        // Trimming helper works cleanly under new tokenizers.
        val qwenCounter = tokenizers.counterFor(null, TextTokenizer.QWEN)
        val trimmed = trimTokenText("你好世界欢迎来到酒馆", limit = 3, fromStart = false, counter = qwenCounter)
        assertTrue(trimmed.isNotEmpty() && qwenCounter.count(trimmed) <= 3)
    }
}
