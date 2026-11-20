package app.tavern.nativeclient

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONObject
import java.util.Locale

private val voiceSaver = Saver<VoicePreferences, String>(save = { it.json().toString() }, restore = { VoicePreferences.from(JSONObject(it)) })
private const val SAMPLE = "你好，欢迎来到酒馆。"

@Composable
internal fun VoiceSettings(state: TavernState, model: TavernViewModel, close: () -> Unit) {
    val current = state.prefs.voice
    var options by rememberSaveable(current, stateSaver = voiceSaver) { mutableStateOf(current) }
    var shortcutRows by rememberSaveable(current.shortcuts) { mutableStateOf(current.shortcuts.entries.joinToString("\n") { "${it.key} = ${it.value}" }) }
    var voiceTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var actor by rememberSaveable { mutableStateOf("") }
    val providers = state.prefs.providers.filter { it.template == null }
    val people = listOf("" to "使用默认音色") + state.cards.map { "character:${it.id}" to "角色 · ${it.name}" } +
        state.prefs.personas.map { "persona:${it.id}" to "人设 · ${it.name}" }
    val shortcutPairs = shortcutRows.lineSequence().map(String::trim).filter(String::isNotBlank).map { line ->
        line.substringBefore('=', "").trim() to line.substringAfter('=', "").trim()
    }.toList()
    val shortcutsValid = shortcutPairs.all { (phrase, replacement) -> phrase.isNotBlank() && replacement.isNotBlank() } &&
        shortcutPairs.distinctBy { it.first.lowercase(Locale.ROOT) }.size == shortcutPairs.size
    val enabled = !state.busy
    fun leave() { model.stopNarration(); close() }
    BackHandler(onBack = ::leave)
    Form {
        FormHeading("语音朗读")
        GroupToggle("启用朗读", options.enabled, enabled) { options = options.copy(enabled = it) }
        GroupToggle("自动朗读新回复", options.automatic, enabled && options.enabled) { options = options.copy(automatic = it) }
        GroupToggle("也朗读我发送的消息", options.includeUser, enabled && options.enabled && options.automatic) { options = options.copy(includeUser = it) }
        SelectField("朗读引擎", options.backendId, listOf("") + providers.map(Provider::id), {
            if (it.isBlank()) "Android 系统朗读" else providers.firstOrNull { provider -> provider.id == it }?.name ?: "服务已删除"
        }, enabled) { options = options.copy(backendId = it) }
        if (options.backendId.isEmpty()) {
            OutlinedButton({ voiceTarget = "" }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text("默认音色 · ${options.systemVoice.ifBlank { "系统默认" }}")
            }
        } else {
            Field("语音模型 ID", options.backendModel, { options = options.copy(backendModel = it) }, enabled = enabled)
            Field("默认音色 ID", options.backendVoice, { options = options.copy(backendVoice = it) }, enabled = enabled)
            Text("聊天文本会发送到选定的自定义语音服务。", style = MaterialTheme.typography.bodySmall)
        }
        if (people.size > 1) {
            SelectField("角色与人设音色", actor, people.map { it.first }, { id -> people.firstOrNull { it.first == id }?.second ?: "使用默认音色" }, enabled) { actor = it }
            if (actor.isNotBlank()) {
                val override = options.voices["${options.backendId}:$actor"].orEmpty()
                if (options.backendId.isEmpty()) {
                    OutlinedButton({ voiceTarget = actor }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                        Text(override.ifBlank { "使用默认音色" })
                    }
                } else Field("专属音色 ID", override, { options = options.withVoice(actor, it) }, enabled = enabled,
                    placeholder = "留空使用默认音色")
            }
        }
        val playing = state.speaking
        OutlinedButton({
            if (playing) model.stopNarration() else model.previewVoice(options, options.actorVoice(actor.takeIf(String::isNotBlank)), SAMPLE)
        }, enabled = enabled && (options.backendId.isBlank() || options.backendModel.isNotBlank() && options.backendVoice.isNotBlank())) {
            Icon(if (playing) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.VolumeUp, null)
            Spacer(Modifier.width(8.dp))
            Text(if (playing) "停止试听" else "试听选定音色")
        }
        VoiceSlider("语速", options.speed, enabled) { options = options.copy(speed = it) }
        if (options.backendId.isEmpty()) VoiceSlider("音高", options.pitch, enabled) { options = options.copy(pitch = it) }
        GroupToggle("有引号时只读引号中的文字", options.quoteOnly, enabled) { options = options.copy(quoteOnly = it) }
        GroupToggle("跳过星号内的动作描写", options.skipActions, enabled) { options = options.copy(skipActions = it) }
        GroupToggle("跳过代码块", options.skipCode, enabled) { options = options.copy(skipCode = it) }
        GroupToggle("有译文时优先朗读译文", options.translatedText, enabled) { options = options.copy(translatedText = it) }
        GroupToggle("跳过成对标签内的文字", options.skipTags, enabled) { options = options.copy(skipTags = it) }
        Field("略读内容的正则（可选）", options.filter, { options = options.copy(filter = it) }, enabled = enabled)
        HorizontalDivider()
        FormHeading("语音输入")
        GroupToggle("显示麦克风按钮", options.dictationEnabled, enabled) { options = options.copy(dictationEnabled = it) }
        SelectField("识别结果", options.dictationMode, DictationMode.entries, DictationMode::label, enabled) { options = options.copy(dictationMode = it) }
        Field("识别语言标签（可选）", options.language, { options = options.copy(language = it) },
            placeholder = "留空随系统，例如 zh-CN", enabled = enabled)
        GroupToggle("仅使用设备上的离线识别", options.requireOffline, enabled) { options = options.copy(requireOffline = it) }
        Text("系统识别可联网。离线模式需要 Android 12 和已安装的语言包。", style = MaterialTheme.typography.bodySmall)
        Field("语音快捷词（每行一条）", shortcutRows, { shortcutRows = it }, lines = 3, enabled = enabled,
            placeholder = "说出的短语 = 预设文字或斜杠命令")
        if (!shortcutsValid) Text("请用等号分隔短语和替换内容，并避免重复的短语。", color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(::leave, enabled = enabled) { Text("取消") }
            Button({ model.saveVoice(options.copy(language = options.language.trim(), filter = options.filter.trim(),
                backendModel = options.backendModel.trim(), backendVoice = options.backendVoice.trim(), shortcuts = shortcutPairs.toMap()), ::leave) },
                enabled = enabled && shortcutsValid) { Text("保存语音设置") }
        }
    }
    voiceTarget?.let { target ->
        SystemVoicePicker(model, options, target, state.speaking, { voiceTarget = null }) { voice ->
            options = if (target.isEmpty()) options.copy(systemVoice = voice) else options.withVoice(target, voice)
            voiceTarget = null
        }
    }
}

@Composable
private fun VoiceSlider(label: String, value: Float, enabled: Boolean, change: (Float) -> Unit) {
    Column {
        Text("$label · ${String.format(Locale.ROOT, "%.2f", value)}×", style = MaterialTheme.typography.labelLarge)
        Slider(value, change, enabled = enabled, valueRange = 0.5f..2f, steps = 29)
    }
}

@Composable
private fun SystemVoicePicker(model: TavernViewModel, options: VoicePreferences, actor: String, playing: Boolean,
                              close: () -> Unit, select: (String) -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var voices by remember { mutableStateOf<List<DeviceVoice>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(model) {
        try {
            voices = model.systemVoices()
        } catch (_: TimeoutCancellationException) {
            error = "系统朗读引擎响应超时"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.localizedMessage ?: "无法读取系统音色"
        } finally {
            loading = false
        }
    }
    val visible = voices.filter { it.id.contains(query.trim(), true) || it.language.contains(query.trim(), true) }
    AlertDialog(onDismissRequest = close, title = { Text("选择系统音色") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("查找语言或音色", query, { query = it })
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                item { TextButton({ select("") }) { Text(if (actor.isEmpty()) "系统默认" else "跟随默认音色") } }
                items(visible, key = DeviceVoice::id) { voice ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton({ select(voice.id) }, Modifier.weight(1f)) {
                            Column {
                                Text(voice.language + if (voice.network) " · 联网" else " · 本机")
                                Text(voice.id, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        IconButton({
                            if (playing) model.stopNarration() else model.previewVoice(options, voice.id, SAMPLE)
                        }) { Icon(if (playing) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.VolumeUp, "试听 ${voice.id}") }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(close) { Text("关闭") } })
}
