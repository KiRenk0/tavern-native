package app.tavern.nativeclient

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.nio.LongBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.text.Normalizer

internal const val EXPRESSION_MODEL_BYTES = 67_601_404L
internal const val EXPRESSION_MODEL_URL = "https://huggingface.co/Cohee/distilbert-base-uncased-go-emotions-onnx/resolve/" +
    "d22488bc83be87678f12eee8a3f65a65de94ef85/onnx/model_quantized.onnx"
private const val EXPRESSION_MODEL_SHA256 = "1a4a425c70f9dc0f2da1d0cacd6fc4c6008e85bb8961fe62de46bd9e44cde464"
private const val EXPRESSION_MODEL_FILE = "go-emotions-1a4a425c.onnx"
private const val MAX_BERT_POSITIONS = 512

internal class LocalExpressions(directory: File, private val asset: (String) -> InputStream) : Closeable {
    private val weights = File(directory, EXPRESSION_MODEL_FILE)
    private val tokenizer by lazy { asset("vocab.txt").bufferedReader().use { BertWordPiece(it.readLines()) } }
    private val labels by lazy {
        val metadata = asset("config.json").bufferedReader().use { JSONObject(it.readText()).getJSONObject("id2label") }
        List(metadata.length()) { metadata.getString(it.toString()) }
    }
    private var session: OrtSession? = null
    private var closed = false

    fun installed() = weights.isFile && weights.length() == EXPRESSION_MODEL_BYTES

    fun install(source: InputStream, progress: (Long) -> Unit) {
        installVerifiedWeights(weights, EXPRESSION_MODEL_BYTES, EXPRESSION_MODEL_SHA256, source, progress) { candidate ->
            synchronized(this) {
                check(!closed) { "本地模型已关闭" }
                progress(EXPRESSION_MODEL_BYTES)
                Files.move(candidate.toPath(), weights.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                releaseSession()
            }
        }
    }

    @Synchronized
    fun classify(reply: String): String? {
        if (closed || !installed() || reply.isBlank()) return null
        val environment = OrtEnvironment.getEnvironment()
        val current = session ?: OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            environment.createSession(weights.absolutePath, options)
        }.also { session = it }
        val ids = tokenizer.encode(reply.takeLast(3000))
        val shape = longArrayOf(1, ids.size.toLong())
        OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), shape).use { tokens ->
            OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(ids.size) { 1L }), shape).use { attention ->
                current.run(mapOf("input_ids" to tokens, "attention_mask" to attention)).use { output ->
                    val logits = (output[0].value as? Array<*>)?.singleOrNull() as? FloatArray ?: return null
                    if (logits.size != labels.size) return null
                    return logits.indices.filter { logits[it].isFinite() }.maxByOrNull { logits[it] }?.let(labels::get)
                }
            }
        }
    }

    @Synchronized
    fun remove() {
        releaseSession()
        check(!weights.exists() || weights.delete()) { "无法删除本地表情模型" }
    }

    @Synchronized
    override fun close() {
        closed = true
        releaseSession()
    }

    private fun releaseSession() {
        val previous = session
        session = null
        previous?.close()
    }
}

internal class BertWordPiece(words: List<String>, private val lowercase: Boolean = true) {
    private val vocab = words.withIndex().associate { (id, word) -> word to id.toLong() }
    private val unk = vocab.getValue("[UNK]")
    private val cls = vocab.getValue("[CLS]")
    private val sep = vocab.getValue("[SEP]")

    fun encode(value: String, prefix: String = "", keepEnd: Boolean = false): LongArray {
        val maximum = MAX_BERT_POSITIONS - 2
        val prefixTokens = tokenIds(prefix).take(maximum).toList()
        val tokens = tokenIds(value)
        val remaining = maximum - prefixTokens.size
        val content = if (keepEnd) tokens.toList().takeLast(remaining) else tokens.take(remaining).toList()
        return (listOf(cls) + prefixTokens + content + sep).toLongArray()
    }

    private fun tokenIds(value: String) = basicTokens(value).asSequence().flatMap { pieces(it).asSequence() }

    private fun pieces(word: String): List<Long> {
        if (word.codePointCount(0, word.length) > 100) return listOf(unk)
        val result = mutableListOf<Long>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var id: Long? = null
            while (end > start) {
                val part = word.substring(start, end)
                id = vocab[if (start == 0) part else "##$part"]
                if (id != null) break
                end = word.offsetByCodePoints(end, -1)
            }
            if (id == null) return listOf(unk)
            result += id
            start = end
        }
        return result
    }

    private fun basicTokens(input: String): List<String> = buildList {
        val word = StringBuilder()
        fun flush() {
            if (word.isNotEmpty()) {
                add(word.toString())
                word.clear()
            }
        }
        val text = if (lowercase) Normalizer.normalize(input.lowercase(), Normalizer.Form.NFD) else input
        var index = 0
        while (index < text.length) {
            val cp = text.codePointAt(index)
            index += Character.charCount(cp)
            when (val type = Character.getType(cp)) {
                Character.NON_SPACING_MARK.toInt() -> if (!lowercase) word.appendCodePoint(cp) else Unit
                Character.FORMAT.toInt() -> Unit
                Character.CONTROL.toInt() -> if (cp == 9 || cp == 10 || cp == 13) flush() else Unit
                else -> when {
                    cp == 0xfffd -> Unit
                    Character.isWhitespace(cp) || Character.isSpaceChar(cp) -> flush()
                    isBertPunctuation(cp, type) || isBertCjk(cp) -> {
                        flush()
                        add(String(Character.toChars(cp)))
                    }
                    else -> word.appendCodePoint(cp)
                }
            }
        }
        flush()
    }

    private fun isBertPunctuation(cp: Int, type: Int): Boolean = cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126 ||
        type in Character.DASH_PUNCTUATION.toInt()..Character.OTHER_PUNCTUATION.toInt() ||
        type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() || type == Character.FINAL_QUOTE_PUNCTUATION.toInt()

    private fun isBertCjk(cp: Int): Boolean = cp in 0x4e00..0x9fff || cp in 0x3400..0x4dbf || cp in 0x20000..0x2a6df ||
        cp in 0x2a700..0x2b73f || cp in 0x2b740..0x2b81f || cp in 0x2b820..0x2ceaf || cp in 0xf900..0xfaff || cp in 0x2f800..0x2fa1f
}
