package app.tavern.nativeclient

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.GptBytePairEncodingParams
import com.knuddels.jtokkit.api.IntArrayList
import java.io.DataInputStream
import java.io.InputStream
import java.util.PriorityQueue
import java.util.regex.Pattern

private fun InputStream.tokenTable() = DataInputStream(buffered())
private fun DataInputStream.bytes() = ByteArray(readUnsignedShort()).also(::readFully)

/** Llama 3 uses the vocabulary IDs as its byte-pair merge ranks. */
internal fun llama3TokenCounter(input: InputStream): TextTokenCounter {
    val (params, specialIds) = input.tokenTable().use { data ->
        check(data.readInt() == 0x4e414932) { "无法读取 Llama 3 分词表" }
        val regex = data.bytes().decodeToString()
        val pattern = try {
            Pattern.compile(regex, Pattern.UNICODE_CHARACTER_CLASS)
        } catch (_: IllegalArgumentException) {
            // Android uses Unicode character classes by default and rejects the explicit flag.
            Pattern.compile(regex)
        }
        val size = data.readInt().also { check(it in 256..150_000) }
        val vocabulary = HashMap<ByteArray, Int>(size)
        repeat(size) { vocabulary[data.bytes()] = it }
        val special = buildMap {
            repeat(data.readInt().also { check(it in 0..1024) }) { put(data.bytes().decodeToString(), data.readInt()) }
        }
        GptBytePairEncodingParams("novelai-llama3", pattern, vocabulary, special) to special
    }
    val encoder = Encodings.newLazyEncodingRegistry().registerGptBytePairEncoding(params)
        .getEncoding(params.name).get()
    val specials = specialIds.keys.sortedByDescending(String::length).joinToString("|") { Regex.escape(it) }.toRegex()
    val specialTexts = specialIds.entries.associate { it.value to it.key }
    val label = "Llama 3 本机分词"
    fun encode(text: String): IntArray {
        val ids = IntArrayList()
        var start = 0
        for (match in specials.findAll(text)) {
            encoder.encodeOrdinary(text.substring(start, match.range.first)).toArray().forEach(ids::add)
            ids.add(specialIds.getValue(match.value))
            start = match.range.last + 1
        }
        encoder.encodeOrdinary(text.substring(start)).toArray().forEach(ids::add)
        return ids.toArray()
    }
    return TextTokenCounter(label, { text ->
        if (specials.containsMatchIn(text)) encode(text).size else encoder.countTokensOrdinary(text)
    }, ::encode) { text ->
        val ids = encode(text)
        val one = IntArrayList(1).apply { add(0) }
        TokenPreview(label, ids.size, ids.take(TOKEN_PREVIEW_PIECES).map { id ->
            one[0] = id
            TokenPiece(id, specialTexts[id] ?: tokenPieceText(encoder.decodeBytes(one)))
        })
    }
}

/** Nerdstash is a SentencePiece BPE model with an identity normalizer and byte fallback. */
internal class NerdstashTokens private constructor(
    private val pieces: Array<String>,
    private val normal: Map<String, Piece>,
    private val specials: Regex,
    private val specialIds: Map<String, Int>,
    private val bytes: IntArray,
) {
    private data class Piece(val id: Int, val score: Float)
    private data class Merge(val left: Int, val right: Int, val end: Int, val piece: Piece)

    fun encode(text: String): IntArray {
        val input = text.replace(' ', '▁')
        if (input.isEmpty()) return IntArray(0)
        val starts = IntArray(input.length)
        val ends = IntArray(input.length)
        val ids = IntArray(input.length)
        val frozen = BooleanArray(input.length)
        val symbols = specials.findAll(input).iterator()
        var special = if (symbols.hasNext()) symbols.next() else null
        var offset = 0
        var size = 0
        while (offset < input.length) {
            starts[size] = offset
            if (special?.range?.first == offset) {
                ids[size] = specialIds.getValue(special.value)
                offset = special.range.last + 1
                frozen[size] = true
                special = if (symbols.hasNext()) symbols.next() else null
            } else {
                offset += Character.charCount(input.codePointAt(offset))
                ids[size] = normal[input.substring(starts[size], offset)]?.id ?: -1
            }
            ends[size++] = offset
        }

        val next = IntArray(size) { it + 1 }
        val previous = IntArray(size) { it - 1 }
        val pending = PriorityQueue(compareByDescending<Merge> { it.piece.score }.thenBy { it.left })
        val longest = longestPiece
        fun enqueue(left: Int) {
            if (left < 0 || frozen[left]) return
            val right = next[left]
            if (right == size || frozen[right] || ends[right] - starts[left] > longest) return
            val piece = normal[input.substring(starts[left], ends[right])] ?: return
            pending.add(Merge(left, right, ends[right], piece))
        }
        for (left in 0 until size - 1) enqueue(left)
        while (pending.isNotEmpty()) {
            val merge = pending.remove()
            val left = merge.left
            val right = merge.right
            if (next[left] != right || ends[left] == -1 || ends[right] != merge.end) continue
            ids[left] = merge.piece.id
            ends[left] = ends[right]
            ends[right] = -1
            next[left] = next[right]
            if (next[left] != size) previous[next[left]] = left
            enqueue(previous[left])
            enqueue(left)
        }

        val output = IntArrayList(size)
        var index = 0
        while (index < size) {
            val id = ids[index]
            if (id >= 0) output.add(id)
            else input.substring(starts[index], ends[index]).encodeToByteArray().forEach { output.add(bytes[it.toInt() and 255]) }
            index = next[index]
        }
        return output.toArray()
    }

    private val longestPiece = normal.keys.maxOf(String::length)

    fun counter(label: String) = TextTokenCounter(label, { encode(it).size }, ::encode) { text ->
        val ids = encode(text)
        TokenPreview(label, ids.size, ids.take(TOKEN_PREVIEW_PIECES).map { id -> TokenPiece(id, pieces[id].replace('▁', ' ')) })
    }

    companion object {
        fun read(input: InputStream): NerdstashTokens = input.tokenTable().use { data ->
            check(data.readInt() == 0x4e414931) { "无法读取 Nerdstash 分词表" }
            val size = data.readInt().also { check(it in 256..80_000) }
            val normal = HashMap<String, Piece>(size)
            val special = mutableMapOf<String, Int>()
            val bytes = IntArray(256) { -1 }
            val pieces = Array(size) { id ->
                val text = data.bytes().decodeToString()
                val type = data.readUnsignedByte()
                val score = data.readFloat()
                when (type) {
                    1 -> normal[text] = Piece(id, score)
                    4 -> special[text] = id
                    6 -> bytes[text.substring(3, 5).toInt(16)] = id
                }
                text
            }
            check(bytes.none { it < 0 })
            val pattern = special.keys.sortedByDescending(String::length).joinToString("|") { Regex.escape(it) }.toRegex()
            NerdstashTokens(pieces, normal, pattern, special, bytes)
        }
    }
}
