package app.tavern.nativeclient

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

@Composable
internal fun DynamicAudioScreen(
    state: TavernState,
    model: TavernViewModel,
    close: () -> Unit,
) {
    BackHandler(onBack = close)
    val context = LocalContext.current
    val config = state.prefs.extensions.dynamicAudio
    val playback by model.audioEngine.state.collectAsStateWithLifecycle()

    var newTrackName by rememberSaveable { mutableStateOf("") }
    var newTrackUri by rememberSaveable { mutableStateOf("") }
    var newTrackChannel by rememberSaveable { mutableStateOf(AudioChannel.BGM) }
    var newTrackLoop by rememberSaveable { mutableStateOf(true) }

    var newRuleName by rememberSaveable { mutableStateOf("") }
    var newRuleType by rememberSaveable { mutableStateOf(AudioRuleTriggerType.KEYWORD) }
    var newRulePattern by rememberSaveable { mutableStateOf("") }
    var newRuleRegex by rememberSaveable { mutableStateOf(false) }
    var newRuleTrackId by rememberSaveable { mutableStateOf(config.tracks.firstOrNull()?.id.orEmpty()) }

    fun updateAudio(next: DynamicAudioConfig) {
        model.saveExtensions(
            state.prefs.extensions
                .copy(dynamicAudio = next)
                .withEnabled(BuiltInExtensionId.DYNAMIC_AUDIO, next.enabled),
        )
        model.audioEngine.applyConfigVolumes(next)
    }

    val pickAudio = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            newTrackUri = uri.toString()
            if (newTrackName.isBlank()) {
                newTrackName = context.documentName(uri).substringBeforeLast('.')
            }
        }
    }

    Form {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionIcon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", !state.busy, close)
            Text("动态音频（BGM 与环境音）", style = MaterialTheme.typography.titleLarge)
        }

        GroupToggle("启用动态音频系统", config.enabled, !state.busy) { enabled ->
            if (!enabled) model.audioEngine.stopAll(300)
            updateAudio(config.copy(enabled = enabled))
        }
        GroupToggle("应用切至后台时自动静音/暂停", config.muteInBackground, !state.busy) {
            updateAudio(config.copy(muteInBackground = it))
        }

        Text("总音量 · ${(config.masterVolume * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = config.masterVolume,
            onValueChange = { updateAudio(config.copy(masterVolume = it)) },
            valueRange = 0f..1f,
            enabled = !state.busy,
        )
        Text("BGM 音量 · ${(config.bgmVolume * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = config.bgmVolume,
            onValueChange = { updateAudio(config.copy(bgmVolume = it)) },
            valueRange = 0f..1f,
            enabled = !state.busy,
        )
        Text("环境音音量 · ${(config.ambientVolume * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = config.ambientVolume,
            onValueChange = { updateAudio(config.copy(ambientVolume = it)) },
            valueRange = 0f..1f,
            enabled = !state.busy,
        )
        Text("淡入淡出时长 · ${config.crossfadeMs} ms", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = config.crossfadeMs.toFloat(),
            onValueChange = { updateAudio(config.copy(crossfadeMs = it.roundToInt())) },
            valueRange = 0f..5000f,
            enabled = !state.busy,
        )

        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("当前播放状态", style = MaterialTheme.typography.titleSmall)
                AudioChannel.entries.forEach { ch ->
                    val chState = playback.channelState(ch)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${ch.label}：${if (chState.playing) chState.trackName else "未播放"}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (chState.playing) {
                            ActionIcon(Icons.Outlined.Stop, "停止") {
                                model.audioEngine.stop(ch, config.crossfadeMs)
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider()
        FormHeading("曲目库", "支持本地音频文件或音频 URL，并可通过 /bgm 与 /ambient 命令即时切换。")
        if (config.tracks.isEmpty()) {
            Text("尚未添加任何曲目。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            config.tracks.forEach { track ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(track.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${track.channel.label} · ${if (track.loop) "循环" else "单次"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        ActionIcon(Icons.Outlined.PlayArrow, "试听") {
                            model.audioEngine.play(track.channel, track, config)
                        }
                        ActionIcon(Icons.Outlined.Delete, "删除") {
                            updateAudio(
                                config.copy(
                                    tracks = config.tracks.filterNot { it.id == track.id },
                                    rules = config.rules.filterNot { it.trackId == track.id },
                                ),
                            )
                        }
                    }
                }
            }
        }

        Field("新曲目名称", newTrackName, { newTrackName = it }, placeholder = "例如：酒馆夜晚")
        Field("音频 URI / URL", newTrackUri, { newTrackUri = it }, placeholder = "选择本地音频或填入音频链接")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ pickAudio.launch(arrayOf("audio/*")) }, modifier = Modifier.weight(1f)) {
                Text("选择本地音频")
            }
            SelectField("声道", newTrackChannel, AudioChannel.entries, AudioChannel::label) { newTrackChannel = it }
        }
        GroupToggle("循环播放", newTrackLoop) { newTrackLoop = it }
        Button(
            onClick = {
                val track = AudioTrack(
                    name = newTrackName.trim(),
                    uri = newTrackUri.trim(),
                    channel = newTrackChannel,
                    loop = newTrackLoop,
                )
                updateAudio(config.copy(tracks = config.tracks + track))
                if (newRuleTrackId.isBlank()) newRuleTrackId = track.id
                newTrackName = ""
                newTrackUri = ""
            },
            enabled = newTrackName.isNotBlank() && newTrackUri.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.Add, null)
            Spacer(Modifier.width(6.dp))
            Text("添加到曲目库")
        }

        HorizontalDivider()
        FormHeading("自动切歌规则", "根据角色表情、背景名称或消息关键词自动切换 BGM 与环境音。")
        config.rules.forEach { rule ->
            val trackName = config.tracks.firstOrNull { it.id == rule.trackId }?.name ?: "未知曲目"
            OutlinedCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(rule.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${rule.triggerType.label}「${rule.pattern}」→ $trackName (${rule.channel.wire})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = rule.enabled,
                        onCheckedChange = { checked ->
                            updateAudio(
                                config.copy(
                                    rules = config.rules.map { if (it.id == rule.id) it.copy(enabled = checked) else it },
                                ),
                            )
                        },
                    )
                    ActionIcon(Icons.Outlined.Delete, "删除规则") {
                        updateAudio(config.copy(rules = config.rules.filterNot { it.id == rule.id }))
                    }
                }
            }
        }

        if (config.tracks.isNotEmpty()) {
            val selectedTrack = config.tracks.firstOrNull { it.id == newRuleTrackId } ?: config.tracks.first()
            Field("规则名称", newRuleName, { newRuleName = it }, placeholder = "例如：下雨触发雨声")
            SelectField("触发源", newRuleType, AudioRuleTriggerType.entries, AudioRuleTriggerType::label) { newRuleType = it }
            Field("匹配关键词 / 表情 / 正则", newRulePattern, { newRulePattern = it }, placeholder = "例如：rain, 下雨, 暴雨")
            GroupToggle("使用正则表达式", newRuleRegex) { newRuleRegex = it }
            SelectField("触发后播放曲目", selectedTrack, config.tracks, { "${it.name} (${it.channel.wire})" }) {
                newRuleTrackId = it.id
            }
            Button(
                onClick = {
                    val rule = AudioAutoRule(
                        name = newRuleName.trim().ifBlank { newRulePattern.trim() },
                        triggerType = newRuleType,
                        pattern = newRulePattern.trim(),
                        trackId = selectedTrack.id,
                        channel = selectedTrack.channel,
                        isRegex = newRuleRegex,
                    )
                    updateAudio(config.copy(rules = config.rules + rule))
                    newRuleName = ""
                    newRulePattern = ""
                },
                enabled = newRulePattern.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("添加自动规则")
            }
        }
    }
}
