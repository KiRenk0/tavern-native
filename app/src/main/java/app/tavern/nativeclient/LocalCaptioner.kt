package app.tavern.nativeclient

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.zip.ZipInputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val CAPTION_REPOSITORY = "Xenova/vit-gpt2-image-captioning/resolve/077550ae0729f9af280c9d885ea9b0380fc8e815/onnx"
private const val IMAGE_EDGE = 224
private const val GPT2_END = 50_256
private const val GPT2_VOCAB = 50_257
private const val MAX_CAPTION_TOKENS = 32

internal enum class CaptionWeights(val filename: String, val remote: String, val size: Long, val sha256: String) {
    ENCODER("encoder.onnx", "encoder_model_quantized.onnx", 87_453_213,
        "6f6e2e27c11303cf533682184543333e7ecb930a734197a0272a1e408aba2766"),
    DECODER("decoder.onnx", "decoder_model_merged_quantized.onnx", 158_599_996,
        "1f3ec53b5fc3614c0b54ee786755a7ec3007841f57543f8e241499edfadfa98f");

    val path get() = "$CAPTION_REPOSITORY/$remote?download=true"
}

internal class LocalCaptioner(private val directory: File, asset: () -> InputStream) {
    private val vocabulary by lazy { asset().bufferedReader().use { it.readLines() }.also { check(it.size == GPT2_VOCAB) } }
    private fun file(part: CaptionWeights) = File(directory, "${part.sha256.take(8)}-${part.filename}")
    fun installed(part: CaptionWeights) = file(part).let { it.isFile && it.length() == part.size }
    fun installed() = CaptionWeights.entries.all(::installed)

    fun install(part: CaptionWeights, input: InputStream, progress: (Long) -> Unit) {
        val target = file(part)
        installVerifiedWeights(target, part.size, part.sha256, input, progress) { verified ->
            progress(part.size)
            Files.move(verified.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }
    }

    fun importArchive(input: InputStream, progress: (Long) -> Unit) {
        check(directory.isDirectory || directory.mkdirs()) { "无法创建本地模型目录" }
        val staged = Files.createTempDirectory(directory.toPath(), "caption-").toFile()
        var received = 0L
        val complete = hashSetOf<CaptionWeights>()
        var hasLicense = false
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "LICENSE" && !entry.isDirectory && !hasLicense) {
                        zip.readLimited(16 * 1024)
                        zip.closeEntry()
                        hasLicense = true
                        continue
                    }
                    val part = CaptionWeights.entries.firstOrNull { it.filename == entry.name }
                    require(part != null && !entry.isDirectory && complete.add(part)) { "本地图片描述模型包包含无效文件" }
                    val verified = File(staged, part.filename)
                    installVerifiedWeights(verified, part.size, part.sha256, zip, { progress(received + it) }) { temporary ->
                        Files.move(temporary.toPath(), verified.toPath(), ATOMIC_MOVE)
                    }
                    received += part.size
                    zip.closeEntry()
                }
            }
            require(complete.size == CaptionWeights.entries.size) { "模型包缺少编码器或解码器" }
            progress(received)
            for (part in CaptionWeights.entries) {
                Files.move(File(staged, part.filename).toPath(), file(part).toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            }
        } finally {
            for (part in CaptionWeights.entries) File(staged, part.filename).delete()
            staged.delete()
        }
    }

    fun remove() {
        for (part in CaptionWeights.entries) {
            val weights = file(part)
            check(!weights.exists() || weights.delete()) { "无法移除本地图片描述模型" }
        }
    }

    suspend fun caption(bytes: ByteArray): String {
        val original = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "无法解码图片" }
        val pixels = try {
            val packed = IntArray(IMAGE_EDGE * IMAGE_EDGE)
            val scaled = Bitmap.createScaledBitmap(original, IMAGE_EDGE, IMAGE_EDGE, true)
            try {
                scaled.getPixels(packed, 0, IMAGE_EDGE, 0, 0, IMAGE_EDGE, IMAGE_EDGE)
            } finally {
                if (scaled !== original) scaled.recycle()
            }
            FloatArray(3 * packed.size) { index ->
                val shift = 16 - (index / packed.size) * 8
                ((packed[index % packed.size] ushr shift) and 255) / 127.5f - 1f
            }
        } finally {
            original.recycle()
        }
        return captionPixels(pixels)
    }

    suspend fun captionPixels(pixels: FloatArray): String {
        check(installed()) { "请先安装本地图片描述模型" }
        require(pixels.size == 3 * IMAGE_EDGE * IMAGE_EDGE)
        val context = currentCoroutineContext()
        return try {
            val environment = OrtEnvironment.getEnvironment()
            val hidden = session(environment, CaptionWeights.ENCODER).use { encoder ->
                OnnxTensor.createTensor(environment, FloatBuffer.wrap(pixels),
                    longArrayOf(1, 3, IMAGE_EDGE.toLong(), IMAGE_EDGE.toLong())).use { input ->
                    encoder.run(mapOf("pixel_values" to input), setOf("last_hidden_state")).use { result ->
                        val output = result[0] as OnnxTensor
                        val values = output.floatBuffer
                        FloatArray(values.remaining()).also { values.get(it) } to output.info.shape
                    }
                }
            }
            context.ensureActive()
            val tokens = session(environment, CaptionWeights.DECODER).use { decoder ->
                OnnxTensor.createTensor(environment, FloatBuffer.wrap(hidden.first), hidden.second).use { state ->
                    OnnxTensor.createTensor(environment, FloatBuffer.allocate(0), longArrayOf(1, 12, 0, 64)).use { empty ->
                        OnnxTensor.createTensor(environment, booleanArrayOf(false)).use { noCache ->
                            val inputs = mutableMapOf("encoder_hidden_states" to state, "use_cache_branch" to noCache)
                            decoder.inputNames.filter { it.startsWith("past_key_values.") }.forEach { inputs[it] = empty }
                            val sequence = mutableListOf(GPT2_END)
                            for (step in 0 until MAX_CAPTION_TOKENS) {
                                context.ensureActive()
                                val next = nextToken(environment, decoder, inputs, sequence)
                                if (next == GPT2_END) break
                                sequence += next
                            }
                            sequence.drop(1)
                        }
                    }
                }
            }
            decodeCaptionTokens(tokens, vocabulary).trim().also { check(it.isNotBlank()) { "本地模型没有返回图片描述" } }
        } catch (e: LinkageError) {
            throw IllegalStateException("设备无法加载本地图片描述运行库", e)
        }
    }

    private fun session(environment: OrtEnvironment, part: CaptionWeights): OrtSession =
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            environment.createSession(file(part).absolutePath, options)
        }

    private fun nextToken(environment: OrtEnvironment, decoder: OrtSession, inputs: Map<String, OnnxTensor>,
                          sequence: List<Int>): Int {
        val ids = LongArray(sequence.size) { sequence[it].toLong() }
        return OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { tokens ->
            decoder.run(inputs + ("input_ids" to tokens), setOf("logits")).use { result ->
                val values = (result[0] as OnnxTensor).floatBuffer
                check(values.remaining() == ids.size * GPT2_VOCAB) { "图片描述模型返回无效结果" }
                values.position((ids.size - 1) * GPT2_VOCAB)
                var best = Float.NEGATIVE_INFINITY
                var index = GPT2_END
                for (token in 0 until GPT2_VOCAB) {
                    val value = values.get()
                    if (value > best) {
                        index = token
                        best = value
                    }
                }
                index
            }
        }
    }
}

internal fun decodeCaptionTokens(ids: List<Int>, vocabulary: List<String>): String {
    // GPT-2 stores each byte as a printable Unicode code point.
    val letters = (33..126).toList() + (161..172).toList() + (174..255).toList()
    val extended = (0..255).filterNot(letters::contains)
    val alphabet = (letters + extended).zip(letters + extended.indices.map { 256 + it }).associate { (byte, char) -> char to byte }
    return ByteArrayOutputStream().use { text ->
        for (id in ids) {
            for (char in vocabulary[id]) text.write(alphabet.getValue(char.code))
        }
        text.toByteArray().decodeToString()
    }
}
