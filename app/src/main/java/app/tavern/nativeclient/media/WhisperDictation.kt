package app.tavern.nativeclient.media

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import app.tavern.nativeclient.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

internal fun encodePcm16Wav(pcm: ByteArray, sampleRate: Int = 16_000, channels: Int = 1): ByteArray {
    val byteRate = sampleRate * channels * 2
    val blockAlign = channels * 2
    val out = ByteArrayOutputStream(44 + pcm.size)
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray(Charsets.US_ASCII))
    header.putInt(36 + pcm.size)
    header.put("WAVE".toByteArray(Charsets.US_ASCII))
    header.put("fmt ".toByteArray(Charsets.US_ASCII))
    header.putInt(16) // PCM subchunk size
    header.putShort(1.toShort()) // AudioFormat = 1 (PCM)
    header.putShort(channels.toShort())
    header.putInt(sampleRate)
    header.putInt(byteRate)
    header.putShort(blockAlign.toShort())
    header.putShort(16.toShort()) // BitsPerSample
    header.put("data".toByteArray(Charsets.US_ASCII))
    header.putInt(pcm.size)
    out.write(header.array())
    out.write(pcm)
    return out.toByteArray()
}

internal suspend fun transcribeWhisper(
    endpoint: String,
    apiKey: String,
    model: String,
    language: String,
    wavBytes: ByteArray,
): String {
    require(endpoint.isNotBlank()) { "请填写 Whisper STT 接口地址" }
    require(wavBytes.size > 44) { "录音时长太短" }
    val root = checkedAddress(endpoint).toString().trimEnd('/').removeSuffix("/audio/transcriptions")
    val url = "$root/audio/transcriptions"
    val boundary = "----TavernWhisper${UUID.randomUUID().toString().replace("-", "")}"
    val body = ByteArrayOutputStream().apply {
        fun field(name: String, value: String) {
            write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
            write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(Charsets.UTF_8))
            write(value.toByteArray(Charsets.UTF_8))
            write("\r\n".toByteArray(Charsets.UTF_8))
        }
        field("model", model.trim().ifBlank { "whisper-1" })
        val lang = language.trim().substringBefore('-')
        if (lang.isNotBlank()) field("language", lang)
        write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
        write("Content-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\n".toByteArray(Charsets.UTF_8))
        write("Content-Type: audio/wav\r\n\r\n".toByteArray(Charsets.UTF_8))
        write(wavBytes)
        write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
    }.toByteArray()

    val headers = buildMap {
        put("Accept", "application/json")
        val key = apiKey.trim()
        if (key.isNotBlank()) put("Authorization", "Bearer $key")
    }
    return request(
        url = url,
        headers = headers,
        readTimeout = 60_000,
        upload = HttpUpload("multipart/form-data; boundary=$boundary", body),
    ) { input, _ ->
        val raw = input.readLimited().toString(Charsets.UTF_8)
        JSONObject(raw).string("text").trim().also {
            check(it.isNotEmpty()) { "Whisper 未识别到有效文字" }
        }
    }
}

internal class WhisperRecorder(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recorder: AudioRecord? = null
    private var captureJob: Job? = null
    @Volatile
    private var recording = false
    private val pcmBuffer = ByteArrayOutputStream()

    @SuppressLint("MissingPermission")
    fun start(
        options: VoicePreferences,
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (recording || captureJob?.isActive == true) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onError("语音输入需要麦克风权限")
            return
        }
        val sampleRate = 16_000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)
        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
            )
        } catch (e: SecurityException) {
            onError("语音输入需要麦克风权限")
            return
        }
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            onError("无法初始化麦克风录音")
            return
        }
        synchronized(pcmBuffer) { pcmBuffer.reset() }
        recorder = audioRecord
        recording = true
        try {
            audioRecord.startRecording()
        } catch (e: Exception) {
            release()
            onError(e.localizedMessage ?: "无法启动录音")
            return
        }
        onPartial("正在录音，点击停止以发送 Whisper 转写…")
        captureJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val chunk = ByteArray(minBuf)
                    val maxPcmBytes = 10 * 1024 * 1024 // ~5 min at 16kHz mono
                    while (recording) {
                        val read = audioRecord.read(chunk, 0, chunk.size)
                        if (read > 0) {
                            synchronized(pcmBuffer) {
                                if (pcmBuffer.size() + read <= maxPcmBytes) pcmBuffer.write(chunk, 0, read)
                                else recording = false
                            }
                        } else if (read < 0) break
                    }
                }
                val wav = withContext(Dispatchers.Default) {
                    val pcm = synchronized(pcmBuffer) { pcmBuffer.toByteArray() }
                    encodePcm16Wav(pcm, sampleRate, 1)
                }
                if (wav.size <= 44) {
                    release()
                    onError("未录到声音")
                    return@launch
                }
                onPartial("正在上传 Whisper 识别…")
                val text = transcribeWhisper(
                    endpoint = options.sttEndpoint,
                    apiKey = options.sttApiKey,
                    model = options.sttModel,
                    language = options.language,
                    wavBytes = wav,
                )
                release()
                onResult(text)
            } catch (e: Exception) {
                release()
                onError(e.localizedMessage ?: "Whisper 语音识别失败")
            }
        }
    }

    fun finish() {
        recording = false
        runCatching { recorder?.stop() }
    }

    fun release() {
        recording = false
        captureJob?.cancel()
        captureJob = null
        val rec = recorder
        recorder = null
        if (rec != null) {
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
    }
}
