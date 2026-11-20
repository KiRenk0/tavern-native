package app.tavern.nativeclient

import java.io.DataInputStream
import java.io.InputStream
import java.util.PriorityQueue

internal class LoreTokenizers(private val open: (String) -> InputStream) {
    private val deepSeek: TextTokenCounter? by lazy {
        try {
            val tokenizer = DeepSeekTokens.read(open("tokenizers/deepseek-v41.bpe"))
            TextTokenCounter("DeepSeek V4.1 本机分词", tokenizer::count)
        } catch (_: Exception) {
            null
        }
    }

    fun counterFor(connection: ModelConnection): TextTokenCounter {
        val id = connection.model.lowercase()
        val matches = id == "deepseek-flash" || id == "deepseek-pro" || id.startsWith("deepseek-v4.1") || id.startsWith("deepseek-v41")
        return if (connection.provider.template == ProviderTemplate.DEEPSEEK && matches) deepSeek ?: estimatedTokens
            else estimatedTokens
    }
}

internal class DeepSeekTokens private constructor(
    private val initial: IntArray,
    private val mergeKeys: LongArray,
    private val mergeRanks: IntArray,
    private val mergedIds: IntArray,
    private val splitters: List<Regex>,
    private val special: Regex,
) {
    fun count(text: String): Int {
        var count = 0
        var next = 0
        for (match in special.findAll(text)) {
            count += countSplit(text.substring(next, match.range.first), 0) + 1
            next = match.range.last + 1
        }
        return count + countSplit(text.substring(next), 0)
    }

    private fun countSplit(text: String, depth: Int): Int {
        if (text.isEmpty()) return 0
        val splitter = splitters.getOrNull(depth) ?: return countMerges(text.encodeToByteArray())
        var count = 0
        var next = 0
        for (match in splitter.findAll(text)) {
            if (match.range.first > next) count += countSplit(text.substring(next, match.range.first), depth + 1)
            count += countSplit(match.value, depth + 1)
            next = match.range.last + 1
        }
        return count + countSplit(text.substring(next), depth + 1)
    }

    private data class Merge(val left: Int, val right: Int, val row: Int, val rank: Int)

    private fun countMerges(bytes: ByteArray): Int {
        if (bytes.size < 2) return bytes.size
        val size = bytes.size
        val tokens = IntArray(size) { initial[bytes[it].toInt() and 255] }
        val previous = IntArray(size) { it - 1 }
        val next = IntArray(size) { it + 1 }
        val pending = PriorityQueue(compareBy<Merge> { it.rank }.thenBy { it.left })
        fun enqueue(left: Int) {
            if (left < 0 || next[left] == size) return
            val right = next[left]
            val row = mergeKeys.binarySearch(key(tokens[left], tokens[right]))
            if (row >= 0) pending.add(Merge(left, right, row, mergeRanks[row]))
        }
        for (left in 0 until size - 1) enqueue(left)
        var count = size
        while (pending.isNotEmpty()) {
            val merge = pending.remove()
            val left = merge.left
            val right = merge.right
            if (tokens[left] < 0 || tokens[right] < 0 || next[left] != right ||
                key(tokens[left], tokens[right]) != mergeKeys[merge.row]) continue
            tokens[left] = mergedIds[merge.row]
            tokens[right] = -1
            next[left] = next[right]
            if (next[left] != size) previous[next[left]] = left
            count--
            enqueue(previous[left])
            enqueue(left)
        }
        return count
    }

    companion object {
        private fun key(left: Int, right: Int) = (left.toLong() shl 32) or right.toLong()

        fun read(input: InputStream): DeepSeekTokens = DataInputStream(input.buffered()).use { data ->
            check(data.readInt() == 0x44534231) { "无法读取 DeepSeek 分词表" }
            fun text(): String = ByteArray(data.readUnsignedShort()).also(data::readFully).decodeToString()
            fun count(maximum: Int): Int = data.readInt().also { require(it in 1..maximum) }
            val splitters = List(count(8)) { text().toRegex() }
            val special = List(count(4096)) { text() }.sortedByDescending(String::length)
                .joinToString("|") { Regex.escape(it) }.toRegex()
            val initial = IntArray(256) { data.readInt() }
            val size = count(500_000)
            val keys = LongArray(size)
            val ranks = IntArray(size)
            val merged = IntArray(size)
            for (index in 0 until size) {
                keys[index] = data.readLong()
                ranks[index] = data.readInt()
                merged[index] = data.readInt()
            }
            DeepSeekTokens(initial, keys, ranks, merged, splitters, special)
        }
    }
}
