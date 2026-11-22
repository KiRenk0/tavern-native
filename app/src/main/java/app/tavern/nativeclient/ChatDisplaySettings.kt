package app.tavern.nativeclient

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlin.math.roundToInt

@Composable
internal fun ChatDisplaySettings(handling: ChatHandling, enabled: Boolean, save: (ChatHandling) -> Unit) {
    ChatDisplaySlider("每次显示的聊天消息", handling.historyPageSize, 0..1000, enabled,
        format = { if (it == 0) "全部" else "$it 条" }) { save(handling.copy(historyPageSize = it)) }
    ChatDisplaySlider("流式刷新上限", handling.streamingFps, 5..100, enabled,
        format = { "$it FPS" }) { save(handling.copy(streamingFps = it)) }
    GroupToggle("平滑显示流式回复", handling.smoothStreaming, enabled) { save(handling.copy(smoothStreaming = it)) }
    if (handling.smoothStreaming) {
        ChatDisplaySlider("流式文字显示速度", handling.smoothStreamingSpeed, 0..100, enabled,
            format = { "$it%" }) { save(handling.copy(smoothStreamingSpeed = it)) }
        GroupToggle("推理内容即时显示", handling.immediateReasoning, enabled) { save(handling.copy(immediateReasoning = it)) }
    }
    GroupToggle("流式文字淡入", handling.streamFadeIn, enabled) { save(handling.copy(streamFadeIn = it)) }
}

@Composable
private fun ChatDisplaySlider(
    label: String, saved: Int, range: IntRange, enabled: Boolean, format: (Int) -> String, save: (Int) -> Unit,
) {
    var value by rememberSaveable(saved) { mutableIntStateOf(saved) }
    Text("$label · ${format(value)}", style = MaterialTheme.typography.labelLarge)
    Slider(value.toFloat(), { value = it.roundToInt() },
        valueRange = range.first.toFloat()..range.last.toFloat(), enabled = enabled,
        onValueChangeFinished = { if (value != saved) save(value) })
}
