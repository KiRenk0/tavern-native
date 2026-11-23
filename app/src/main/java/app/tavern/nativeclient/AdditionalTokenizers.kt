package app.tavern.nativeclient

import com.knuddels.jtokkit.api.Encoding
import com.knuddels.jtokkit.api.EncodingType
import com.knuddels.jtokkit.api.IntArrayList
import java.lang.Character.UnicodeScript
import java.util.Locale

internal enum class DigitGrouping {
    SINGLE,
    UP_TO_THREE,
}

internal enum class CjkHandling {
    /** Uses UTF-8 byte fallback (3 tokens per CJK char, 4 per emoji), matching Llama 1/2 32k SentencePiece. */
    BYTE_FALLBACK,
    /** Uses cl100k-calibrated CJK splitting (~1.5-2 tokens per CJK char), matching Claude's ~65k BPE. */
    CLAUDE_BPE,
    /** Uses o200k-calibrated CJK with 1-token guarantee per CJK char (no multi-byte explosion), matching Tekken/Gemma/Command-R. */
    MULTILINGUAL_SINGLE,
    /** CJK-dense vocab where common 2-char Han pairs merge into 1 token, matching Qwen 151k / Yi 64k. */
    CJK_DENSE,
}

internal data class TokenizerProfileInfo(
    val tokenizer: TextTokenizer,
    val family: String,
    val vocabSize: Int,
    val algorithm: String,
    val summary: String,
)

internal class CalibratedBpeTokenizer(
    val profile: TokenizerProfileInfo,
    private val baseEncodingType: EncodingType,
    private val digitGrouping: DigitGrouping,
    private val cjkHandling: CjkHandling,
    private val sentencePieceSpaces: Boolean,
    private val specialTokens: Map<String, Int>,
    private val idSalt: Int,
) {
    private val baseEncoding: Encoding by lazy { OpenAiTokenizers.encoding(baseEncodingType) }

    private val specialRegex: Regex? by lazy {
        if (specialTokens.isEmpty()) null
        else specialTokens.keys.sortedByDescending(String::length).joinToString("|") { Regex.escape(it) }.toRegex()
    }

    private val digitChunkRegex = Regex("""\d+""")
    private val segmentRegex = Regex(
        """(?:'[sStTdDmM]|'[rR][eE]|'[vV][eE]|'[lL][lL])|[^\r\n\p{L}\p{N}]?\p{L}+|\p{N}+| ?[^\s\p{L}\p{N}]+[\r\n]*|\s*[\r\n]+|\s+(?!\S)|\s+""",
    )

    private val commonCjkBigrams = setOf(
        "你好", "我们", "你们", "他们", "她们", "什么", "怎么", "可以", "没有", "不是",
        "这个", "那个", "现在", "时候", "因为", "所以", "但是", "如果", "已经", "知道",
        "世界", "角色", "对话", "故事", "系统", "模型", "问题", "回答", "开始", "结束",
        "喜欢", "觉得", "应该", "需要", "工作", "时间", "地方", "东西", "自己", "大家",
        "今天", "明天", "昨天", "非常", "真的", "一下", "一点", "一起", "一直", "一定",
        "不会", "不能", "不要", "不知", "可能", "还是", "或者", "然后", "虽然", "不过",
    )

    fun counter(): TextTokenCounter {
        val label = "${profile.tokenizer.label} 本机分词"
        return TextTokenCounter(
            label = label,
            count = { text -> encode(text).size },
            encode = ::encode,
            preview = { text -> preview(text, label) },
        )
    }

    fun count(text: String): Int = encode(text).size

    fun encode(text: String): IntArray {
        if (text.isEmpty()) return IntArray(0)
        val ids = IntArrayList()
        tokenizeInternal(text, ids, pieces = null)
        return ids.toArray()
    }

    fun preview(text: String, label: String = "${profile.tokenizer.label} 本机分词"): TokenPreview {
        if (text.isEmpty()) return TokenPreview(label, 0, emptyList())
        val ids = IntArrayList()
        val pieces = ArrayList<TokenPiece>(minOf(text.length, TOKEN_PREVIEW_PIECES))
        tokenizeInternal(text, ids, pieces)
        return TokenPreview(label, ids.size(), pieces)
    }

    private fun tokenizeInternal(text: String, outIds: IntArrayList, pieces: MutableList<TokenPiece>?) {
        val regex = specialRegex
        if (regex == null) {
            tokenizePlain(text, outIds, pieces)
            return
        }
        var cursor = 0
        for (match in regex.findAll(text)) {
            if (match.range.first > cursor) {
                tokenizePlain(text.substring(cursor, match.range.first), outIds, pieces)
            }
            val tokenText = match.value
            val id = specialTokens.getValue(tokenText)
            outIds.add(id)
            if (pieces != null && pieces.size < TOKEN_PREVIEW_PIECES) {
                pieces += TokenPiece(id, tokenText)
            }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) {
            tokenizePlain(text.substring(cursor), outIds, pieces)
        }
    }

    private fun tokenizePlain(text: String, outIds: IntArrayList, pieces: MutableList<TokenPiece>?) {
        if (text.isEmpty()) return
        for (match in segmentRegex.findAll(text)) {
            val chunk = match.value
            if (chunk.isEmpty()) continue
            when {
                digitChunkRegex.matches(chunk) -> emitDigits(chunk, outIds, pieces)
                containsDenseScriptOrHighUnicode(chunk) -> emitMultilingualChunk(chunk, outIds, pieces)
                else -> emitBpeChunk(chunk, outIds, pieces)
            }
        }
    }

    private fun emitDigits(digits: String, outIds: IntArrayList, pieces: MutableList<TokenPiece>?) {
        val step = if (digitGrouping == DigitGrouping.SINGLE) 1 else 3
        var i = 0
        while (i < digits.length) {
            val end = minOf(i + step, digits.length)
            val part = digits.substring(i, end)
            val id = stableVocabId("num:$part")
            outIds.add(id)
            if (pieces != null && pieces.size < TOKEN_PREVIEW_PIECES) {
                pieces += TokenPiece(id, part)
            }
            i = end
        }
    }

    private fun emitMultilingualChunk(chunk: String, outIds: IntArrayList, pieces: MutableList<TokenPiece>?) {
        when (cjkHandling) {
            CjkHandling.BYTE_FALLBACK -> emitLlamaByteFallbackChunk(chunk, outIds, pieces)
            CjkHandling.CLAUDE_BPE -> emitBpeChunk(chunk, outIds, pieces)
            CjkHandling.MULTILINGUAL_SINGLE -> emitCjkAwareChunk(chunk, mergeBigrams = false, outIds, pieces)
            CjkHandling.CJK_DENSE -> emitCjkAwareChunk(chunk, mergeBigrams = true, outIds, pieces)
        }
    }

    private fun emitLlamaByteFallbackChunk(chunk: String, outIds: IntArrayList, pieces: MutableList<TokenPiece>?) {
        var idx = 0
        val asciiBuf = StringBuilder()
        fun flushAscii() {
            if (asciiBuf.isNotEmpty()) {
                emitBpeChunk(asciiBuf.toString(), outIds, pieces)
                asciiBuf.setLength(0)
            }
        }
        while (idx < chunk.length) {
            val cp = chunk.codePointAt(idx)
            val charLen = Character.charCount(cp)
            if (cp <= 0x00FF) {
                asciiBuf.appendCodePoint(cp)
            } else {
                flushAscii()
                val sub = chunk.substring(idx, idx + charLen)
                val bytes = sub.encodeToByteArray()
                for (b in bytes) {
                    val unsigned = b.toInt() and 0xFF
                    // Llama 1/2 SentencePiece reserves IDs 3..258 for <0x00>..<0xFF> byte fallback tokens.
                    val byteId = 3 + unsigned
                    outIds.add(byteId)
                    if (pieces != null && pieces.size < TOKEN_PREVIEW_PIECES) {
                        pieces += TokenPiece(byteId, "<0x%02X>".format(unsigned))
                    }
                }
            }
            idx += charLen
        }
        flushAscii()
    }

    private fun emitCjkAwareChunk(
        chunk: String,
        mergeBigrams: Boolean,
        outIds: IntArrayList,
        pieces: MutableList<TokenPiece>?,
    ) {
        var idx = 0
        val nonCjkBuf = StringBuilder()
        fun flushNonCjk() {
            if (nonCjkBuf.isNotEmpty()) {
                emitBpeChunk(nonCjkBuf.toString(), outIds, pieces)
                nonCjkBuf.setLength(0)
            }
        }
        while (idx < chunk.length) {
            val cp = chunk.codePointAt(idx)
            val charLen = Character.charCount(cp)
            if (isDenseScript(cp)) {
                flushNonCjk()
                if (mergeBigrams && idx + charLen < chunk.length) {
                    val nextCp = chunk.codePointAt(idx + charLen)
                    val nextLen = Character.charCount(nextCp)
                    if (isDenseScript(nextCp)) {
                        val bigram = chunk.substring(idx, idx + charLen + nextLen)
                        if (bigram in commonCjkBigrams) {
                            val id = stableVocabId("cjk2:$bigram")
                            outIds.add(id)
                            if (pieces != null && pieces.size < TOKEN_PREVIEW_PIECES) {
                                pieces += TokenPiece(id, bigram)
                            }
                            idx += charLen + nextLen
                            continue
                        }
                    }
                }
                val ch = chunk.substring(idx, idx + charLen)
                val id = stableVocabId("cjk:$ch")
                outIds.add(id)
                if (pieces != null && pieces.size < TOKEN_PREVIEW_PIECES) {
                    pieces += TokenPiece(id, ch)
                }
            } else {
                nonCjkBuf.appendCodePoint(cp)
            }
            idx += charLen
        }
        flushNonCjk()
    }

    private fun emitBpeChunk(chunk: String, outIds: IntArrayList, pieces: MutableList<TokenPiece>?) {
        val rawIds = baseEncoding.encodeOrdinary(chunk)
        val one = if (pieces != null || sentencePieceSpaces) IntArrayList(1).apply { add(0) } else null
        for (i in 0 until rawIds.size()) {
            val rawId = rawIds[i]
            val mappedId = mapBaseTokenId(rawId)
            outIds.add(mappedId)
            if (pieces != null && pieces.size < TOKEN_PREVIEW_PIECES) {
                one!![0] = rawId
                val decoded = tokenPieceText(baseEncoding.decodeBytes(one))
                val display = if (sentencePieceSpaces && decoded.startsWith(' ')) {
                    "▁" + decoded.substring(1)
                } else decoded
                pieces += TokenPiece(mappedId, display)
            }
        }
    }

    private fun mapBaseTokenId(rawId: Int): Int {
        // Reserve 0..258 for special & byte tokens, map BPE IDs deterministically into [259, vocabSize).
        val usable = (profile.vocabSize - 259).coerceAtLeast(1024)
        val mixed = ((rawId.toLong() * 2654435761L) xor idSalt.toLong()) and 0x7FFF_FFFFL
        return 259 + (mixed % usable).toInt()
    }

    private fun stableVocabId(key: String): Int {
        val usable = (profile.vocabSize - 259).coerceAtLeast(1024)
        var hash = idSalt * 31 + 17
        for (c in key) {
            hash = hash * 31 + c.code
        }
        return 259 + ((hash and 0x7FFF_FFFF) % usable)
    }

    private fun containsDenseScriptOrHighUnicode(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (cp > 0x00FF && (isDenseScript(cp) || cp > 0xFFFF || cjkHandling == CjkHandling.BYTE_FALLBACK)) {
                return true
            }
            i += Character.charCount(cp)
        }
        return false
    }

    private fun isDenseScript(codePoint: Int): Boolean =
        UnicodeScript.of(codePoint) in denseScripts

    companion object {
        private val denseScripts = setOf(
            UnicodeScript.HAN,
            UnicodeScript.HIRAGANA,
            UnicodeScript.KATAKANA,
            UnicodeScript.HANGUL,
        )
    }
}

internal object AdditionalTokenizers {
    private val yiPattern = Regex("""(?:^|[/_\-])yi(?:[-_.]?(?:1\.5|2|6b|9b|34b|large|medium|spark|lightning|vl|coder|chat|base)|$)""")
    private val nemoWordPattern = Regex("""(?:^|[/_\-])nemo(?:$|[/_\-\d])""")

    private val mistralNemo by lazy {
        CalibratedBpeTokenizer(
            profile = TokenizerProfileInfo(
                tokenizer = TextTokenizer.MISTRAL_NEMO,
                family = "Mistral / Nemo (Tekken)",
                vocabSize = 131_072,
                algorithm = "Tekken / SentencePiece BPE (131k)",
                summary = "适用于 Mistral Large/Small、Mistral Nemo、Mixtral、Codestral、Pixtral。",
            ),
            baseEncodingType = EncodingType.O200K_BASE,
            digitGrouping = DigitGrouping.UP_TO_THREE,
            cjkHandling = CjkHandling.MULTILINGUAL_SINGLE,
            sentencePieceSpaces = true,
            specialTokens = mapOf(
                "<unk>" to 0,
                "<s>" to 1,
                "</s>" to 2,
                "[INST]" to 3,
                "[/INST]" to 4,
                "[AVAILABLE_TOOLS]" to 5,
                "[/AVAILABLE_TOOLS]" to 6,
                "[TOOL_RESULTS]" to 7,
                "[/TOOL_RESULTS]" to 8,
                "[TOOL_CALLS]" to 9,
            ),
            idSalt = 0x4D495354,
        )
    }

    private val qwen by lazy {
        CalibratedBpeTokenizer(
            profile = TokenizerProfileInfo(
                tokenizer = TextTokenizer.QWEN,
                family = "Qwen 2 / 2.5 / 3 / QwQ",
                vocabSize = 151_936,
                algorithm = "Byte-Level BPE (152k CJK-Dense)",
                summary = "适用于 Qwen2、Qwen2.5、Qwen3、QwQ、QVQ 等通义千问系列模型。",
            ),
            baseEncodingType = EncodingType.O200K_BASE,
            digitGrouping = DigitGrouping.SINGLE,
            cjkHandling = CjkHandling.CJK_DENSE,
            sentencePieceSpaces = false,
            specialTokens = mapOf(
                "<|endoftext|>" to 151_643,
                "<|im_start|>" to 151_644,
                "<|im_end|>" to 151_645,
                "<|object_ref_start|>" to 151_646,
                "<|object_ref_end|>" to 151_647,
                "<think>" to 151_667,
                "</think>" to 151_668,
                "<tool_call>" to 151_657,
                "</tool_call>" to 151_658,
            ),
            idSalt = 0x5157454E,
        )
    }

    private val gemma by lazy {
        CalibratedBpeTokenizer(
            profile = TokenizerProfileInfo(
                tokenizer = TextTokenizer.GEMMA,
                family = "Google Gemma 2 / 3",
                vocabSize = 256_000,
                algorithm = "SentencePiece BPE (256k)",
                summary = "适用于 Gemma、Gemma 2、Gemma 3、CodeGemma、PaliGemma 系列模型。",
            ),
            baseEncodingType = EncodingType.O200K_BASE,
            digitGrouping = DigitGrouping.SINGLE,
            cjkHandling = CjkHandling.MULTILINGUAL_SINGLE,
            sentencePieceSpaces = true,
            specialTokens = mapOf(
                "<pad>" to 0,
                "<eos>" to 1,
                "<bos>" to 2,
                "<unk>" to 3,
                "<start_of_turn>" to 106,
                "<end_of_turn>" to 107,
            ),
            idSalt = 0x47454D41,
        )
    }

    private val claude by lazy {
        CalibratedBpeTokenizer(
            profile = TokenizerProfileInfo(
                tokenizer = TextTokenizer.CLAUDE,
                family = "Anthropic Claude",
                vocabSize = 65_536,
                algorithm = "Byte-Level BPE (~65k)",
                summary = "适用于 Claude 3 / 3.5 / 3.7 / 4 系列模型（Sonnet、Opus、Haiku）。",
            ),
            baseEncodingType = EncodingType.CL100K_BASE,
            digitGrouping = DigitGrouping.UP_TO_THREE,
            cjkHandling = CjkHandling.CLAUDE_BPE,
            sentencePieceSpaces = false,
            specialTokens = mapOf(
                "<|endoftext|>" to 0,
                "<thinking>" to 10,
                "</thinking>" to 11,
            ),
            idSalt = 0x434C4155,
        )
    }

    private val commandR by lazy {
        CalibratedBpeTokenizer(
            profile = TokenizerProfileInfo(
                tokenizer = TextTokenizer.COMMAND_R,
                family = "Cohere Command-R / Command-A",
                vocabSize = 255_029,
                algorithm = "Byte-Level BPE (255k Multilingual)",
                summary = "适用于 Cohere Command-R、Command-R+、Command-A、Aya Expanse 模型。",
            ),
            baseEncodingType = EncodingType.O200K_BASE,
            digitGrouping = DigitGrouping.SINGLE,
            cjkHandling = CjkHandling.MULTILINGUAL_SINGLE,
            sentencePieceSpaces = false,
            specialTokens = mapOf(
                "<PAD>" to 0,
                "<UNK>" to 1,
                "<CLS>" to 2,
                "<SEP>" to 3,
                "<MASK_TOKEN>" to 4,
                "<BOS_TOKEN>" to 5,
                "<EOS_TOKEN>" to 255_001,
                "<|START_OF_TURN_TOKEN|>" to 255_006,
                "<|END_OF_TURN_TOKEN|>" to 255_007,
                "<|SYSTEM_TOKEN|>" to 255_008,
                "<|USER_TOKEN|>" to 255_009,
                "<|CHATBOT_TOKEN|>" to 255_010,
            ),
            idSalt = 0x434D4452,
        )
    }

    private val llama12 by lazy {
        CalibratedBpeTokenizer(
            profile = TokenizerProfileInfo(
                tokenizer = TextTokenizer.LLAMA_1_2,
                family = "Meta Llama 1 / 2",
                vocabSize = 32_000,
                algorithm = "SentencePiece BPE (32k + Byte Fallback)",
                summary = "适用于 Llama 1/2、CodeLlama、Vicuna、MythoMax、Noromaid 等 32k 词表模型。",
            ),
            baseEncodingType = EncodingType.R50K_BASE,
            digitGrouping = DigitGrouping.SINGLE,
            cjkHandling = CjkHandling.BYTE_FALLBACK,
            sentencePieceSpaces = true,
            specialTokens = mapOf(
                "<unk>" to 0,
                "<s>" to 1,
                "</s>" to 2,
                "[INST]" to 259,
                "[/INST]" to 260,
                "<<SYS>>" to 261,
                "<</SYS>>" to 262,
            ),
            idSalt = 0x4C4C4132,
        )
    }

    private val yi by lazy {
        CalibratedBpeTokenizer(
            profile = TokenizerProfileInfo(
                tokenizer = TextTokenizer.YI,
                family = "01.AI Yi / Yi-1.5",
                vocabSize = 64_000,
                algorithm = "SentencePiece BPE (64k Bilingual)",
                summary = "适用于 Yi-6B/9B/34B、Yi-1.5、Yi-Large、Yi-Lightning 等零一万物模型。",
            ),
            baseEncodingType = EncodingType.CL100K_BASE,
            digitGrouping = DigitGrouping.SINGLE,
            cjkHandling = CjkHandling.CJK_DENSE,
            sentencePieceSpaces = true,
            specialTokens = mapOf(
                "<unk>" to 0,
                "<|startoftext|>" to 1,
                "<|endoftext|>" to 2,
                "<|im_start|>" to 6,
                "<|im_end|>" to 7,
            ),
            idSalt = 0x59493634,
        )
    }

    fun tokenizerFor(mode: TextTokenizer): CalibratedBpeTokenizer? = when (mode) {
        TextTokenizer.MISTRAL_NEMO -> mistralNemo
        TextTokenizer.QWEN -> qwen
        TextTokenizer.GEMMA -> gemma
        TextTokenizer.CLAUDE -> claude
        TextTokenizer.COMMAND_R -> commandR
        TextTokenizer.LLAMA_1_2 -> llama12
        TextTokenizer.YI -> yi
        else -> null
    }

    fun counterFor(mode: TextTokenizer): TextTokenCounter? = tokenizerFor(mode)?.counter()

    fun profileInfo(mode: TextTokenizer): TokenizerProfileInfo? = tokenizerFor(mode)?.profile

    fun forModel(modelId: String): TextTokenizer? {
        val raw = modelId.trim().lowercase(Locale.ROOT)
        if (raw.isEmpty()) return null
        val short = raw.substringAfterLast('/').substringBefore(':')
        return when {
            "claude" in short || "claude" in raw -> TextTokenizer.CLAUDE
            listOf("mistral", "mixtral", "ministral", "codestral", "pixtral", "devstral", "mathstral", "magistral")
                .any { it in short } || nemoWordPattern.containsMatchIn(short) -> TextTokenizer.MISTRAL_NEMO
            listOf("qwen", "qwq", "qvq").any { it in short } -> TextTokenizer.QWEN
            listOf("gemma", "paligemma", "codegemma", "shieldgemma").any { it in short } -> TextTokenizer.GEMMA
            listOf("command-r", "command-a", "c4ai-command", "aya-expanse").any { it in short } ||
                raw.startsWith("cohere/command") -> TextTokenizer.COMMAND_R
            yiPattern.containsMatchIn(raw) || yiPattern.containsMatchIn(short) -> TextTokenizer.YI
            isLlama1Or2(short) -> TextTokenizer.LLAMA_1_2
            else -> null
        }
    }

    private val llama3Or4Pattern = Regex("""llama[-_]?[34](?!\d)""")

    private fun isLlama1Or2(short: String): Boolean {
        if (llama3Or4Pattern.containsMatchIn(short)) return false
        return listOf(
            "llama-2", "llama2", "llama-1", "llama1", "codellama", "code-llama",
            "vicuna", "alpaca", "tinyllama", "wizardlm", "mythomax", "noromaid", "goliath",
            "llama-7b", "llama-13b", "llama-30b", "llama-65b",
        ).any { it in short }
    }
}
