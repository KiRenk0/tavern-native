package app.tavern.nativeclient

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.tavern.nativeclient.media.WhisperRecorder

internal data class DictationProgress(val finishing: Boolean = false, val partial: String = "")

private class Dictation(private val context: Context) {
    var progress by mutableStateOf<DictationProgress?>(null)
        private set
    private var recognizer: SpeechRecognizer? = null

    fun start(options: VoicePreferences, onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (recognizer != null) return
        try {
            val service = if (options.requireOffline) {
                if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else error("本机语音识别需要 Android 12 和可用的离线识别引擎")
            } else {
                check(SpeechRecognizer.isRecognitionAvailable(context)) { "设备上没有可用的 Android 语音识别服务" }
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            recognizer = service
            progress = DictationProgress()
            service.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onEndOfSpeech() { if (recognizer === service) markFinishing() }
                override fun onPartialResults(partialResults: Bundle?) {
                    if (recognizer !== service) return
                    val partial = best(partialResults) ?: return
                    progress = progress?.copy(partial = partial)
                }
                override fun onResults(results: Bundle?) {
                    if (recognizer !== service) return
                    val text = best(results)
                    release(cancel = false)
                    if (text == null) onError("未识别到语音") else onResult(text)
                }
                override fun onError(code: Int) {
                    if (recognizer !== service) return
                    release(cancel = false)
                    onError(when (code) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未识别到语音"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "语音输入需要麦克风权限"
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别服务网络不可用"
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "识别服务不支持选定语言"
                        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "所选语言的语音包尚未安装"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音识别服务正忙"
                        else -> "语音识别失败（$code）"
                    })
                }
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                if (options.language.isNotBlank()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, options.language)
                if (options.requireOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            service.startListening(intent)
        } catch (e: Exception) {
            release(cancel = true)
            onError(e.localizedMessage ?: "无法开始语音输入")
        }
    }

    private fun best(result: Bundle?) = result?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull { it.isNotBlank() }

    private fun markFinishing() { progress = progress?.copy(finishing = true) }

    fun finish() {
        if (progress?.finishing == false) {
            markFinishing()
            recognizer?.stopListening()
        }
    }

    fun release(cancel: Boolean = true) {
        val active = recognizer
        recognizer = null
        progress = null
        if (active != null) {
            if (cancel) runCatching { active.cancel() }
            active.destroy()
        }
    }
}

@Composable
internal fun DictationButton(options: VoicePreferences, enabled: Boolean, pausedPlayback: () -> Unit,
                             onTranscript: (String) -> Unit, onError: (String) -> Unit,
                             onProgress: (DictationProgress?) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val dictation = remember(context) { Dictation(context.applicationContext) }
    val whisper = remember(context) { WhisperRecorder(context.applicationContext) }
    var whisperProgress by remember { mutableStateOf<DictationProgress?>(null) }
    val deliver by rememberUpdatedState(onTranscript)
    val error by rememberUpdatedState(onError)
    val progress = if (options.sttBackend == SttBackend.WHISPER_API) whisperProgress else dictation.progress
    LaunchedEffect(progress) { onProgress(progress) }
    DisposableEffect(lifecycle, dictation, whisper) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                dictation.release()
                whisper.release()
                whisperProgress = null
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            dictation.release()
            whisper.release()
            whisperProgress = null
            onProgress(null)
        }
    }
    fun start() {
        pausedPlayback()
        if (options.sttBackend == SttBackend.WHISPER_API) {
            whisperProgress = DictationProgress(finishing = false, partial = "准备录音…")
            whisper.start(
                options,
                onPartial = { status -> whisperProgress = DictationProgress(finishing = status.contains("上传"), partial = status) },
                onResult = { text ->
                    whisperProgress = null
                    deliver(text)
                },
                onError = { msg ->
                    whisperProgress = null
                    error(msg)
                },
            )
        } else {
            dictation.start(options, { deliver(it) }, { error(it) })
        }
    }
    val permission = rememberLauncherForActivityResult(RequestPermission()) { granted ->
        if (granted && enabled) start()
        else if (!granted) error("语音输入需要麦克风权限")
    }
    IconButton(onClick = {
        if (options.sttBackend == SttBackend.WHISPER_API) {
            if (whisperProgress?.finishing == true) {
                whisper.release()
                whisperProgress = null
            } else if (whisperProgress != null) {
                whisperProgress = whisperProgress?.copy(finishing = true, partial = "正在转写…")
                whisper.finish()
            } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                start()
            } else permission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            if (progress?.finishing == true) dictation.release()
            else if (progress != null) dictation.finish()
            else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
            else permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }, enabled = enabled || progress != null) {
        Icon(if (progress != null) Icons.Outlined.Stop else Icons.Outlined.MicNone, when {
            progress?.finishing == true -> "取消语音识别"
            progress != null -> "结束录音"
            else -> "语音输入"
        })
    }
}
