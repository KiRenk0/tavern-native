package app.tavern.nativeclient

import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun VideoGenerationDialog(
    state: TavernState,
    chatId: String,
    model: TavernViewModel,
    draft: String,
    images: List<ChatImage>,
    close: () -> Unit,
    configure: () -> Unit,
) {
    val saved = state.prefs.drawing.video
    val providers = state.prefs.providers.filter(::supportsVideoGeneration)
    var providerId by rememberSaveable { mutableStateOf(saved.providerId) }
    val provider = providers.firstOrNull { it.id == providerId }
        ?: providers.firstOrNull { it.template == ProviderTemplate.GOOGLE } ?: providers.firstOrNull()
    var prompt by rememberSaveable { mutableStateOf(draft) }
    var aspect by rememberSaveable { mutableStateOf(saved.aspect) }
    var resolution by rememberSaveable { mutableStateOf(saved.resolution) }
    var seconds by rememberSaveable { mutableIntStateOf(saved.seconds) }
    var includeImages by rememberSaveable { mutableStateOf(true) }
    val references = images.take(MAX_VIDEO_REFERENCES)
    val enabled = !state.busy && state.generation == null && !state.scriptRunning

    AlertDialog(
        onDismissRequest = close,
        title = { Text("生成视频") },
        text = {
            if (provider == null) Text("请在设置中保存 Google Gemini API Key。")
            else Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Gemini Omni Flash", style = MaterialTheme.typography.labelLarge)
                if (providers.size > 1) SelectField("Google 连接", provider, providers, Provider::name, enabled) { providerId = it.id }
                Field("视频提示词", prompt, { prompt = it }, lines = 3, enabled = enabled)
                SelectField("画面比例", aspect, VideoAspect.entries, VideoAspect::label, enabled) { aspect = it }
                SelectField("分辨率", resolution, VideoResolution.entries, VideoResolution::wire, enabled) { resolution = it }
                SelectField("时长", seconds, (3..15).toList(), { "$it 秒" }, enabled) { seconds = it }
                if (references.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(includeImages, { includeImages = it }, enabled = enabled)
                    Text("使用输入框中的前 ${references.size} 张图片作为参考")
                }
            }
        },
        confirmButton = {
            if (provider == null) TextButton(onClick = { close(); configure() }) { Text("打开设置") }
            else TextButton(enabled = enabled && prompt.isNotBlank(), onClick = {
                val profile = VideoProfile(provider.id, aspect, resolution, seconds)
                val request = VideoRequest(provider, profile, prompt, references.takeIf { includeImages }.orEmpty())
                if (model.generateVideo(chatId, request)) close()
            }) { Text("生成") }
        },
        dismissButton = { TextButton(onClick = close) { Text("取消") } },
    )
}

@Composable
internal fun ChatVideoDialog(video: ChatVideo, model: TavernViewModel, enabled: Boolean, close: () -> Unit, remove: () -> Unit) {
    val file = remember(video) { model.videoFile(video) }
    val available = file.isFile && file.length() == video.byteCount
    var playbackFailed by remember(video) { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(CreateDocument("video/mp4")) { uri ->
        uri?.let { model.exportVideo(it, video) }
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(video.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            when {
                !available -> Text("找不到本机视频文件。")
                playbackFailed -> Text("此设备无法播放该视频。可以保存后用其他播放器打开。")
                else -> VideoPlayer(file.absolutePath, onError = { playbackFailed = true })
            }
        },
        confirmButton = {
            TextButton(onClick = { save.launch(video.displayFilename) }, enabled = available && enabled) { Text("保存 MP4") }
        },
        dismissButton = {
            TextButton(onClick = { removing = true }, enabled = enabled) { Text("移除") }
            TextButton(onClick = close) { Text("关闭") }
        },
    )
    if (removing) Confirm("移除视频", "从消息中移除这段视频？", { removing = false }) {
        if (enabled) remove()
        close()
    }
}

@Composable
private fun VideoPlayer(path: String, onError: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val controller = remember(context) { MediaController(context) }
    val view = remember(context) { VideoView(context) }
    val reportError by rememberUpdatedState(onError)
    DisposableEffect(lifecycle, view, path) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                view.pause()
                controller.hide()
            }
        }
        lifecycle.addObserver(observer)
        view.setMediaController(controller)
        view.setOnPreparedListener { if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.start() }
        view.setOnErrorListener { _, _, _ -> reportError(); true }
        view.setVideoPath(path)
        onDispose {
            lifecycle.removeObserver(observer)
            controller.hide()
            view.setOnPreparedListener(null)
            view.setOnErrorListener(null)
            view.stopPlayback()
        }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
    }
}
