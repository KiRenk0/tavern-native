package app.tavern.nativeclient.chat

import android.icu.text.BreakIterator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import app.tavern.nativeclient.ChatHandling
import app.tavern.nativeclient.Generation
import app.tavern.nativeclient.ReplyAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.math.ceil

internal data class StreamingText(val body: String, val reasoning: String)

@Composable
internal fun streamingText(generation: Generation?, handling: ChatHandling, reducedMotion: Boolean): StreamingText? {
    if (generation == null) return null
    val raw = StreamingText(generation.text, generation.reasoning)
    return key(generation.id, generation.characterId, generation.action) {
        val source by rememberUpdatedState(raw)
        val smooth = handling.smoothStreaming && !reducedMotion
        val start = if (generation.action == ReplyAction.CONTINUE) raw else StreamingText("", "")
        val presented by produceState(start, handling.streamingFps, smooth, handling.smoothStreamingSpeed, handling.immediateReasoning) {
            val breaker = if (smooth) BreakIterator.getCharacterInstance() else null
            val fps = handling.streamingFps.coerceIn(5, 100)
            val frameMillis = ceil(1000.0 / fps).toLong()
            val charactersPerFrame = (12 + handling.smoothStreamingSpeed * 1.28) * frameMillis / 1000
            var budget = 0.0
            while (true) {
                snapshotFlow { source }.first { it != value }
                delay(frameMillis)
                val latest = source
                budget += charactersPerFrame
                val steps = budget.toInt()
                budget -= steps
                value = StreamingText(
                    breaker.reveal(value.body, latest.body, steps),
                    if (handling.immediateReasoning) latest.reasoning else breaker.reveal(value.reasoning, latest.reasoning, steps),
                )
            }
        }
        presented
    }
}

private fun BreakIterator?.reveal(previous: String, latest: String, steps: Int): String {
    if (this == null || !latest.startsWith(previous) || latest == previous) return latest
    if (steps == 0) return previous
    setText(latest)
    var end = previous.length
    repeat(steps) {
        end = following(end)
        if (end == BreakIterator.DONE || end == latest.length) return latest
    }
    return latest.substring(0, end)
}

@Composable
internal fun StreamingFadeText(text: AnnotatedString, style: TextStyle, enabled: Boolean) {
    if (!enabled) {
        Text(text, style = style)
        return
    }
    val alpha = remember { Animatable(1f) }
    var previous by remember { mutableStateOf("") }
    var fadeStart by remember { mutableIntStateOf(text.length) }
    LaunchedEffect(text.text) {
        fadeStart = if (text.text.startsWith(previous)) previous.length else text.length
        previous = text.text
        if (fadeStart < text.length) {
            alpha.snapTo(0.48f)
            alpha.animateTo(1f, tween(durationMillis = 155))
        }
    }
    val start = fadeStart.coerceAtMost(text.length)
    val color = style.color.takeUnless { it == Color.Unspecified }
    val rendered = if (start < text.length && color != null && alpha.value < 1f) buildAnnotatedString {
        append(text)
        addStyle(SpanStyle(color = color.copy(alpha = color.alpha * alpha.value)), start, text.length)
    } else text
    Text(rendered, style = style)
}
