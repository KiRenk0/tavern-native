package app.tavern.nativeclient

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import app.tavern.nativeclient.media.synthesizeBackendAudio
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class DeviceVoice(val id: String, val language: String, val network: Boolean)
internal data class Narration(val text: String, val options: VoicePreferences, val voice: String, val backend: Provider? = null)

private class SpeechCompletion(private val id: String, private val waiting: CancellableContinuation<Unit>) : UtteranceProgressListener() {
    private val completed = AtomicBoolean()

    fun finish(utteranceId: String?, error: String? = null) {
        if (utteranceId != id || !waiting.isActive || !completed.compareAndSet(false, true)) return
        if (error == null) waiting.resume(Unit) else waiting.resumeWithException(IllegalStateException(error))
    }

    override fun onStart(utteranceId: String?) = Unit
    override fun onDone(utteranceId: String?) = finish(utteranceId)
    @Deprecated("Android still requires this callback")
    override fun onError(utteranceId: String?) = finish(utteranceId, "系统朗读失败")
    override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId, "系统朗读失败（$errorCode）")
    override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId)
}

private class SpeechAudioData(private val bytes: ByteArray) : MediaDataSource() {
    override fun getSize() = bytes.size.toLong()
    override fun close() = Unit
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= bytes.size || position < 0) return -1
        val start = position.toInt()
        val count = minOf(size, bytes.size - start)
        bytes.copyInto(buffer, offset, start, start + count)
        return count
    }
}

internal suspend fun synthesizeSpeech(provider: Provider, model: String, voice: String, text: String, speed: Float): ByteArray {
    require(provider.template == null) { "请选择自定义语音服务" }
    require(model.isNotBlank() && voice.isNotBlank()) { "请填写语音模型和音色" }
    val endpoint = checkedAddress(provider.endpoint).toString().trimEnd('/').removeSuffix("/chat/completions").removeSuffix("/audio/speech")
    val headers = buildMap {
        put("Accept", "audio/mpeg")
        if (provider.apiKey.isNotBlank()) put("Authorization", "Bearer ${provider.apiKey.trim()}")
    }
    val payload = jsonOf("input" to text, "model" to model, "voice" to voice, "speed" to speed, "response_format" to "mp3")
    return request("$endpoint/audio/speech", payload, headers) { input, type ->
        check(!type.startsWith("application/json", ignoreCase = true)) { "语音服务返回的数据不是音频" }
        input.readLimited(12 * 1024 * 1024).also { check(it.isNotEmpty()) { "语音服务未返回音频" } }
    }
}

internal class SpeechPlayback(private val context: Context, private val interrupted: () -> Unit) {
    private val serial = Mutex()
    private val audio = requireNotNull(context.getSystemService(AudioManager::class.java))
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var engine: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null

    private suspend fun system(): TextToSpeech {
        if (engine == null) {
            val result = CompletableDeferred<Boolean>()
            ready = result
            engine = TextToSpeech(context) { status -> result.complete(status == TextToSpeech.SUCCESS) }
        }
        val pending = requireNotNull(ready)
        val available = withTimeoutOrNull(8_000) { pending.await() }
        if (available != true) {
            if (ready === pending) close()
            error(if (available == null) "系统朗读引擎响应超时" else "系统朗读引擎无法启动")
        }
        return requireNotNull(engine)
    }

    suspend fun voices(): List<DeviceVoice> = system().voices.orEmpty()
        .map { DeviceVoice(it.name, it.locale.getDisplayName(it.locale), it.isNetworkConnectionRequired) }
        .sortedWith(compareBy(DeviceVoice::language, DeviceVoice::network, DeviceVoice::id))

    suspend fun play(narration: Narration) = serial.withLock {
        if (narration.options.activeBackend == TtsBackend.SYSTEM) {
            val tts = system()
            val voice = if (narration.voice.isBlank()) tts.defaultVoice else tts.voices.orEmpty().firstOrNull { it.name == narration.voice }
            if (voice != null) check(tts.setVoice(voice) == TextToSpeech.SUCCESS) { "系统无法启用此音色" }
            else check(narration.voice.isBlank()) { "此音色未安装，请在语音设置选择可用音色" }
            tts.setAudioAttributes(attributes)
            tts.setPitch(narration.options.pitch)
            tts.setSpeechRate(narration.options.speed)
            withFocus {
                for (part in speechChunks(narration.text, TextToSpeech.getMaxSpeechInputLength().coerceAtLeast(8))) {
                    speakSegment(tts, part)
                }
            }
        } else {
            val limit = when (narration.options.activeBackend) {
                TtsBackend.NOVELAI -> 1_000
                TtsBackend.STYLE_BERT_VITS2 -> 400
                else -> 3_000
            }
            for (part in speechChunks(narration.text, limit)) {
                val bytes = synthesizeBackendAudio(narration.copy(text = part))
                withFocus { playBytes(bytes) }
            }
        }
    }

    private suspend fun withFocus(play: suspend () -> Unit) {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes).setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ if (it < 0) interrupted() }, Handler(Looper.getMainLooper())).build()
        check(audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "暂时无法播放语音" }
        try {
            play()
        } finally {
            audio.abandonAudioFocusRequest(request)
        }
    }

    private suspend fun speakSegment(tts: TextToSpeech, text: String) {
        try {
            withTimeout(360_000) {
                suspendCancellableCoroutine { waiting ->
                    val id = newId()
                    val completion = SpeechCompletion(id, waiting)
                    tts.setOnUtteranceProgressListener(completion)
                    if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) completion.onError(id, TextToSpeech.ERROR)
                }
            }
        } finally {
            tts.setOnUtteranceProgressListener(null)
            tts.stop()
        }
    }

    private suspend fun playBytes(bytes: ByteArray) {
        val player = MediaPlayer()
        try {
            withTimeout(180_000) {
                suspendCancellableCoroutine { waiting ->
                    player.setAudioAttributes(attributes)
                    player.setOnCompletionListener { if (waiting.isActive) waiting.resume(Unit) }
                    player.setOnErrorListener { _, _, _ ->
                        if (waiting.isActive) waiting.resumeWithException(IllegalStateException("无法播放语音服务返回的音频"))
                        true
                    }
                    player.setOnPreparedListener {
                        if (waiting.isActive) try {
                            it.start()
                        } catch (e: IllegalStateException) {
                            waiting.resumeWithException(e)
                        }
                    }
                    player.setDataSource(SpeechAudioData(bytes))
                    player.prepareAsync()
                }
            }
        } finally {
            player.release()
        }
    }

    fun close() {
        engine?.shutdown()
        engine = null
        ready = null
    }
}
