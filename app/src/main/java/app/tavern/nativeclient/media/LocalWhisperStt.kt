package app.tavern.nativeclient.media

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import app.tavern.nativeclient.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

internal const val WHISPER_SAMPLE_RATE = 16_000
internal const val WHISPER_CHUNK_SECONDS = 30
internal const val WHISPER_CHUNK_SAMPLES = WHISPER_SAMPLE_RATE * WHISPER_CHUNK_SECONDS // 480_000
internal const val WHISPER_N_FFT = 400
internal const val WHISPER_HOP_LENGTH = 160
internal const val WHISPER_N_MELS = 80
internal const val WHISPER_FRAMES = WHISPER_CHUNK_SAMPLES / WHISPER_HOP_LENGTH // 3_000

private const val WHISPER_SOT = 50_258
private const val WHISPER_EOT = 50_257
private const val WHISPER_TRANSCRIBE = 50_359
private const val WHISPER_NO_TIMESTAMPS = 50_363
private const val MAX_WHISPER_DECODE_TOKENS = 224
private const val MAX_WHISPER_WEIGHT_BYTES = 256L * 1024L * 1024L
private const val MAX_WHISPER_VOCAB_BYTES = 16L * 1024L * 1024L

internal enum class WhisperWeights(val filename: String, val label: String, val maxBytes: Long) {
    ENCODER("encoder.onnx", "编码器 (encoder.onnx)", MAX_WHISPER_WEIGHT_BYTES),
    DECODER("decoder.onnx", "解码器 (decoder.onnx)", MAX_WHISPER_WEIGHT_BYTES),
    VOCAB("vocab.json", "词表 (vocab.json)", MAX_WHISPER_VOCAB_BYTES);

    companion object {
        fun matchArchiveEntry(entryName: String): WhisperWeights? {
            val base = entryName.substringAfterLast('/').lowercase(Locale.ROOT)
            return when {
                base == "encoder.onnx" || base == "encoder_model.onnx" || base == "encoder_model_quantized.onnx" -> ENCODER
                base == "decoder.onnx" || base == "decoder_model.onnx" || base == "decoder_model_quantized.onnx" ||
                    base == "decoder_model_merged_quantized.onnx" -> DECODER
                base == "vocab.json" || base == "tokenizer.json" -> VOCAB
                else -> null
            }
        }
    }
}

internal data class WhisperModelStatus(
    val installed: Boolean,
    val totalBytes: Long,
    val missingParts: List<WhisperWeights>,
)

internal fun localWhisperDir(context: Context): File = File(context.noBackupFilesDir, "whisper-model")

internal class LocalWhisperStt(private val directory: File) {
    fun file(part: WhisperWeights): File = File(directory, part.filename)

    fun installed(part: WhisperWeights): Boolean = file(part).let { it.isFile && it.length() > 0L }

    fun installed(): Boolean = WhisperWeights.entries.all(::installed)

    fun status(): WhisperModelStatus {
        val missing = WhisperWeights.entries.filterNot(::installed)
        val total = WhisperWeights.entries.sumOf { f -> file(f).takeIf(File::isFile)?.length() ?: 0L }
        return WhisperModelStatus(missing.isEmpty(), total, missing)
    }

    fun installPart(
        part: WhisperWeights,
        input: InputStream,
        expectedBytes: Long? = null,
        expectedSha256: String? = null,
        progress: (Long) -> Unit = {},
    ): String {
        val target = file(part)
        if (expectedBytes != null && !expectedSha256.isNullOrBlank()) {
            installVerifiedWeights(target, expectedBytes, expectedSha256, input, progress) { verified ->
                Files.move(verified.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            }
            return expectedSha256
        }
        check(directory.isDirectory || directory.mkdirs()) { "无法创建本地 Whisper 模型目录" }
        val candidate = File.createTempFile(target.nameWithoutExtension, ".part", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var received = 0L
            candidate.outputStream().use { out ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    received += count
                    require(received <= part.maxBytes) { "${part.label} 文件过大" }
                    digest.update(buffer, 0, count)
                    out.write(buffer, 0, count)
                    progress(received)
                }
                out.fd.sync()
            }
            require(received > 0L) { "${part.label} 文件为空" }
            if (part == WhisperWeights.VOCAB) {
                validateWhisperVocabJson(candidate.readText(Charsets.UTF_8))
            }
            Files.move(candidate.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        } finally {
            candidate.delete()
        }
    }

    fun importArchive(input: InputStream, progress: (Long) -> Unit = {}) {
        check(directory.isDirectory || directory.mkdirs()) { "无法创建本地 Whisper 模型目录" }
        val staged = Files.createTempDirectory(directory.toPath(), "whisper-").toFile()
        var received = 0L
        val found = hashSetOf<WhisperWeights>()
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val clean = entry.name.replace('\\', '/').trimEnd('/')
                    require(safeOriginalPath(clean)) { "模型压缩包包含不安全的路径" }
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }
                    val part = WhisperWeights.matchArchiveEntry(clean)
                    if (part == null || !found.add(part)) {
                        zip.closeEntry()
                        continue
                    }
                    val dest = File(staged, part.filename)
                    val buffer = ByteArray(64 * 1024)
                    var partBytes = 0L
                    dest.outputStream().use { out ->
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            partBytes += n
                            require(partBytes <= part.maxBytes) { "${part.label} 超过大小限制" }
                            out.write(buffer, 0, n)
                            received += n
                            progress(received)
                        }
                    }
                    require(partBytes > 0L) { "${part.label} 内容为空" }
                    if (part == WhisperWeights.VOCAB) {
                        validateWhisperVocabJson(dest.readText(Charsets.UTF_8))
                    }
                    zip.closeEntry()
                }
            }
            val missing = WhisperWeights.entries.filterNot(found::contains)
            require(missing.isEmpty()) {
                "本地 Whisper 模型包缺少必要文件：${missing.joinToString("、", transform = WhisperWeights::filename)}"
            }
            for (part in WhisperWeights.entries) {
                Files.move(File(staged, part.filename).toPath(), file(part).toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            }
        } finally {
            staged.deleteRecursively()
        }
    }

    fun remove() {
        for (part in WhisperWeights.entries) {
            val target = file(part)
            check(!target.exists() || target.delete()) { "无法移除本地 Whisper 模型文件" }
        }
    }

    suspend fun transcribeWav(wavBytes: ByteArray, language: String = ""): String {
        val status = status()
        check(status.installed) {
            val names = status.missingParts.joinToString("、", transform = WhisperWeights::filename)
            "本机离线 Whisper 模型未安装（缺少：$names）。请先在语音设置中导入 ONNX 模型包。"
        }
        val samples = decodeWavPcm16(wavBytes)
        require(samples.isNotEmpty()) { "录音时长太短" }
        val padded = padOrTrimAudio(samples, WHISPER_CHUNK_SAMPLES)
        val features = computeLogMelSpectrogram(padded)
        return runOnnxInference(features, language)
    }

    internal suspend fun runOnnxInference(logMel: FloatArray, language: String): String {
        check(installed()) { "本机离线 Whisper 模型未安装，请先在语音设置中导入 ONNX 模型包。" }
        require(logMel.size == WHISPER_N_MELS * WHISPER_FRAMES) { "梅尔频谱输入尺寸无效" }
        val vocab = parseWhisperVocabJson(file(WhisperWeights.VOCAB).readText(Charsets.UTF_8))
        val context = currentCoroutineContext()
        return try {
            val environment = OrtEnvironment.getEnvironment()
            val hidden = createSession(environment, WhisperWeights.ENCODER).use { encoder ->
                val inputName = encoder.inputNames.firstOrNull() ?: "input_features"
                val outputName = encoder.outputNames.firstOrNull() ?: "last_hidden_state"
                OnnxTensor.createTensor(
                    environment,
                    FloatBuffer.wrap(logMel),
                    longArrayOf(1, WHISPER_N_MELS.toLong(), WHISPER_FRAMES.toLong()),
                ).use { melTensor ->
                    encoder.run(mapOf(inputName to melTensor), setOf(outputName)).use { result ->
                        val out = result[0] as OnnxTensor
                        val buf = out.floatBuffer
                        FloatArray(buf.remaining()).also(buf::get) to out.info.shape
                    }
                }
            }
            context.ensureActive()
            val generated = createSession(environment, WhisperWeights.DECODER).use { decoder ->
                OnnxTensor.createTensor(environment, FloatBuffer.wrap(hidden.first), hidden.second).use { encState ->
                    val prompt = buildWhisperPromptTokens(language, vocab)
                    val sequence = prompt.toMutableList()
                    val encInputName = decoder.inputNames.firstOrNull { "encoder" in it } ?: "encoder_hidden_states"
                    val idsInputName = decoder.inputNames.firstOrNull { "input_ids" in it } ?: "input_ids"
                    val logitsOutName = decoder.outputNames.firstOrNull { "logits" in it } ?: decoder.outputNames.first()
                    val vocabSize = vocab.idToToken.size.coerceAtLeast(51_865)
                    for (step in 0 until MAX_WHISPER_DECODE_TOKENS) {
                        context.ensureActive()
                        val ids = LongArray(sequence.size) { sequence[it].toLong() }
                        val nextId = OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong()))
                            .use { idsTensor ->
                                decoder.run(mapOf(idsInputName to idsTensor, encInputName to encState), setOf(logitsOutName))
                                    .use { res ->
                                        val buf = (res[0] as OnnxTensor).floatBuffer
                                        val stepVocab = buf.remaining() / ids.size
                                        require(stepVocab > 0) { "本地 Whisper 解码器输出无效" }
                                        buf.position((ids.size - 1) * stepVocab)
                                        var bestVal = Float.NEGATIVE_INFINITY
                                        var bestIdx = WHISPER_EOT
                                        for (t in 0 until min(stepVocab, vocabSize)) {
                                            val v = buf.get()
                                            if (v > bestVal) {
                                                bestVal = v
                                                bestIdx = t
                                            }
                                        }
                                        bestIdx
                                    }
                            }
                        if (nextId == WHISPER_EOT) break
                        sequence += nextId
                    }
                    sequence.drop(prompt.size)
                }
            }
            decodeWhisperTokens(generated, vocab.idToToken).trim().also {
                check(it.isNotEmpty()) { "本地 Whisper 未识别到有效文字" }
            }
        } catch (e: LinkageError) {
            throw IllegalStateException("设备无法加载本地 Whisper ONNX 运行库", e)
        }
    }

    private fun createSession(environment: OrtEnvironment, part: WhisperWeights): OrtSession =
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            environment.createSession(file(part).absolutePath, options)
        }
}

internal data class WhisperVocab(
    val idToToken: Map<Int, String>,
    val tokenToId: Map<String, Int>,
)

internal fun validateWhisperVocabJson(raw: String) {
    val parsed = parseWhisperVocabJson(raw)
    require(parsed.idToToken.isNotEmpty()) { "Whisper 词表为空或格式无效" }
}

internal fun parseWhisperVocabJson(raw: String): WhisperVocab {
    val root = JSONObject(raw)
    val vocabObj = root.optJSONObject("model")?.optJSONObject("vocab") ?: root
    val idToToken = mutableMapOf<Int, String>()
    val tokenToId = mutableMapOf<String, Int>()
    for (key in vocabObj.keys()) {
        val id = vocabObj.optInt(key, -1)
        if (id >= 0) {
            idToToken[id] = key
            tokenToId[key] = id
        }
    }
    val added = root.optJSONArray("added_tokens")
    if (added != null) {
        for (i in 0 until added.length()) {
            val item = added.optJSONObject(i) ?: continue
            val id = item.optInt("id", -1)
            val content = item.string("content")
            if (id >= 0 && content.isNotEmpty()) {
                idToToken[id] = content
                tokenToId[content] = id
            }
        }
    }
    return WhisperVocab(idToToken, tokenToId)
}

internal fun buildWhisperPromptTokens(language: String, vocab: WhisperVocab): List<Int> {
    val sot = vocab.tokenToId["<|startoftranscript|>"] ?: WHISPER_SOT
    val transcribe = vocab.tokenToId["<|transcribe|>"] ?: WHISPER_TRANSCRIBE
    val noTimestamps = vocab.tokenToId["<|notimestamps|>"] ?: WHISPER_NO_TIMESTAMPS
    val langCode = language.trim().lowercase(Locale.ROOT).substringBefore('-')
    val langToken = langCode.takeIf(String::isNotBlank)?.let { vocab.tokenToId["<|$it|>"] }
    return buildList {
        add(sot)
        if (langToken != null) add(langToken)
        add(transcribe)
        add(noTimestamps)
    }
}

private val whisperByteAlphabet: Map<Int, Int> by lazy {
    val letters = (33..126).toList() + (161..172).toList() + (174..255).toList()
    val extended = (0..255).filterNot(letters::contains)
    (letters + extended).zip(letters + extended.indices.map { 256 + it }).associate { (byte, ch) -> ch to byte }
}

internal fun decodeWhisperTokens(ids: List<Int>, idToToken: Map<Int, String>): String {
    val out = ByteArrayOutputStream()
    for (id in ids) {
        if (id >= WHISPER_EOT) continue
        val token = idToToken[id] ?: continue
        if (token.startsWith("<|") && token.endsWith("|>")) continue
        for (ch in token) {
            val mapped = whisperByteAlphabet[ch.code]
            if (mapped != null) out.write(mapped)
            else out.write(ch.toString().toByteArray(Charsets.UTF_8))
        }
    }
    return out.toByteArray().decodeToString()
}

internal fun decodeWavPcm16(wavBytes: ByteArray): FloatArray {
    require(wavBytes.size >= 44) { "WAV 音频头部不完整" }
    require(String(wavBytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
        String(wavBytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "仅支持 PCM16 WAV 音频" }
    val buf = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN)
    var pos = 12
    var audioFormat = 0
    var channels = 0
    var sampleRate = 0
    var bitsPerSample = 0
    var dataOffset = -1
    var dataSize = 0
    while (pos + 8 <= wavBytes.size) {
        val chunkId = String(wavBytes, pos, 4, Charsets.US_ASCII)
        val chunkSize = buf.getInt(pos + 4)
        pos += 8
        if (chunkSize < 0 || pos + chunkSize > wavBytes.size) break
        when (chunkId) {
            "fmt " -> if (chunkSize >= 16) {
                audioFormat = buf.getShort(pos).toInt() and 0xFFFF
                channels = buf.getShort(pos + 2).toInt() and 0xFFFF
                sampleRate = buf.getInt(pos + 4)
                bitsPerSample = buf.getShort(pos + 14).toInt() and 0xFFFF
            }
            "data" -> {
                dataOffset = pos
                dataSize = chunkSize
            }
        }
        pos += chunkSize + (chunkSize and 1)
    }
    require(audioFormat == 1 && bitsPerSample == 16 && channels in 1..2 && sampleRate in 8_000..96_000) {
        "WAV 音频格式不受支持（需 16-bit PCM）"
    }
    require(dataOffset >= 0 && dataSize >= 2 * channels) { "WAV 音频数据为空" }
    val frameCount = dataSize / (2 * channels)
    val mono = FloatArray(frameCount)
    var readPos = dataOffset
    for (i in 0 until frameCount) {
        var sum = 0f
        for (c in 0 until channels) {
            sum += buf.getShort(readPos).toFloat() / 32768f
            readPos += 2
        }
        mono[i] = (sum / channels).coerceIn(-1f, 1f)
    }
    return if (sampleRate == WHISPER_SAMPLE_RATE) mono else resampleLinear(mono, sampleRate, WHISPER_SAMPLE_RATE)
}

internal fun resampleLinear(samples: FloatArray, fromRate: Int, toRate: Int): FloatArray {
    if (samples.isEmpty() || fromRate == toRate) return samples
    val ratio = fromRate.toDouble() / toRate.toDouble()
    val outLength = max(1, (samples.size / ratio).toInt())
    val out = FloatArray(outLength)
    for (i in 0 until outLength) {
        val src = i * ratio
        val idx = src.toInt()
        val frac = (src - idx).toFloat()
        val a = samples[min(idx, samples.lastIndex)]
        val b = samples[min(idx + 1, samples.lastIndex)]
        out[i] = a + (b - a) * frac
    }
    return out
}

internal fun padOrTrimAudio(samples: FloatArray, targetLength: Int = WHISPER_CHUNK_SAMPLES): FloatArray {
    if (samples.size == targetLength) return samples.copyOf()
    val out = FloatArray(targetLength)
    val copyLen = min(samples.size, targetLength)
    samples.copyInto(out, 0, 0, copyLen)
    return out
}

private val whisperHannWindow: FloatArray by lazy {
    FloatArray(WHISPER_N_FFT) { n ->
        (0.5 * (1.0 - cos(2.0 * PI * n / WHISPER_N_FFT))).toFloat()
    }
}

private val whisperMelFilters: Array<FloatArray> by lazy {
    buildMelFilterbank(WHISPER_SAMPLE_RATE, WHISPER_N_FFT, WHISPER_N_MELS)
}

internal fun buildMelFilterbank(sampleRate: Int, nFft: Int, nMels: Int): Array<FloatArray> {
    val nFreqs = nFft / 2 + 1
    fun hzToMel(hz: Double) = 2595.0 * log10(1.0 + hz / 700.0)
    fun melToHz(mel: Double) = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)
    val melMin = hzToMel(0.0)
    val melMax = hzToMel(sampleRate / 2.0)
    val melPoints = DoubleArray(nMels + 2) { i -> melMin + (melMax - melMin) * i / (nMels + 1) }
    val hzPoints = DoubleArray(nMels + 2) { i -> melToHz(melPoints[i]) }
    val fftFreqs = DoubleArray(nFreqs) { i -> i.toDouble() * sampleRate / nFft }
    return Array(nMels) { m ->
        val left = hzPoints[m]
        val center = hzPoints[m + 1]
        val right = hzPoints[m + 2]
        val enorm = 2.0 / (right - left)
        FloatArray(nFreqs) { k ->
            val f = fftFreqs[k]
            val weight = when {
                f in left..center && center > left -> (f - left) / (center - left)
                f in center..right && right > center -> (right - f) / (right - center)
                else -> 0.0
            }
            (weight * enorm).toFloat()
        }
    }
}

internal fun computeLogMelSpectrogram(
    samples: FloatArray,
    nMels: Int = WHISPER_N_MELS,
    nFft: Int = WHISPER_N_FFT,
    hopLength: Int = WHISPER_HOP_LENGTH,
): FloatArray {
    require(samples.isNotEmpty()) { "音频样本为空" }
    val frames = samples.size / hopLength
    val nFreqs = nFft / 2 + 1
    val pad = nFft / 2
    val fftSize = 512 // next power of 2 >= 400
    val window = if (nFft == WHISPER_N_FFT) whisperHannWindow else FloatArray(nFft) { n ->
        (0.5 * (1.0 - cos(2.0 * PI * n / nFft))).toFloat()
    }
    val filters = if (nMels == WHISPER_N_MELS && nFft == WHISPER_N_FFT) whisperMelFilters
    else buildMelFilterbank(WHISPER_SAMPLE_RATE, nFft, nMels)

    val out = FloatArray(nMels * frames)
    val real = DoubleArray(fftSize)
    val imag = DoubleArray(fftSize)
    val power = FloatArray(nFreqs)
    var maxLog = Float.NEGATIVE_INFINITY

    for (frame in 0 until frames) {
        val center = frame * hopLength
        real.fill(0.0)
        imag.fill(0.0)
        for (n in 0 until nFft) {
            val idx = reflectIndex(center - pad + n, samples.size)
            real[n] = (samples[idx] * window[n]).toDouble()
        }
        fftRadix2(real, imag)
        for (k in 0 until nFreqs) {
            val re = real[k]
            val im = imag[k]
            power[k] = (re * re + im * im).toFloat()
        }
        for (m in 0 until nMels) {
            val weights = filters[m]
            var melEnergy = 0f
            for (k in 0 until nFreqs) melEnergy += weights[k] * power[k]
            val logVal = log10(max(melEnergy, 1e-10f))
            out[m * frames + frame] = logVal
            if (logVal > maxLog) maxLog = logVal
        }
    }
    val floor = maxLog - 8.0f
    for (i in out.indices) {
        out[i] = (max(out[i], floor) + 4.0f) / 4.0f
    }
    return out
}

private fun reflectIndex(index: Int, length: Int): Int {
    if (length <= 1) return 0
    var idx = index
    while (idx < 0 || idx >= length) {
        if (idx < 0) idx = -idx
        if (idx >= length) idx = 2 * length - 2 - idx
    }
    return idx.coerceIn(0, length - 1)
}

private fun fftRadix2(real: DoubleArray, imag: DoubleArray) {
    val n = real.size
    var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while (j and bit != 0) {
            j = j xor bit
            bit = bit shr 1
        }
        j = j xor bit
        if (i < j) {
            val tr = real[i]; real[i] = real[j]; real[j] = tr
            val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
        }
    }
    var len = 2
    while (len <= n) {
        val half = len shr 1
        val angle = -2.0 * PI / len
        val wLenR = cos(angle)
        val wLenI = sin(angle)
        var i = 0
        while (i < n) {
            var wR = 1.0
            var wI = 0.0
            for (k in 0 until half) {
                val uR = real[i + k]
                val uI = imag[i + k]
                val vR = real[i + k + half] * wR - imag[i + k + half] * wI
                val vI = real[i + k + half] * wI + imag[i + k + half] * wR
                real[i + k] = uR + vR
                imag[i + k] = uI + vI
                real[i + k + half] = uR - vR
                imag[i + k + half] = uI - vI
                val nextR = wR * wLenR - wI * wLenI
                wI = wR * wLenI + wI * wLenR
                wR = nextR
            }
            i += len
        }
        len = len shl 1
    }
}
