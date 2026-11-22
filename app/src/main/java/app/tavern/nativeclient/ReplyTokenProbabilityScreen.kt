package app.tavern.nativeclient

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.NumberFormat

@Composable
internal fun ReplyTokenProbabilityDialog(
    trace: ReplyTokenTrace, canReroll: Boolean, dismiss: () -> Unit,
    reroll: (Int, ReplyTokenCandidate) -> Unit,
) {
    var selected by rememberSaveable(trace) { mutableIntStateOf(0) }
    val current = trace.tokens.getOrNull(selected)
    val format = remember { NumberFormat.getPercentInstance().apply { maximumFractionDigits = 2 } }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Token 概率") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (trace.prefix.isNotBlank()) {
                    Text("续写前缀", style = MaterialTheme.typography.labelMedium)
                    Text(trace.prefix, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(trace.tokens) { index, token ->
                        FilterChip(selected == index, { selected = index }, label = {
                            Text(token.chosen.displayToken(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }, modifier = Modifier.widthIn(max = 120.dp))
                    }
                }
                if (current != null) {
                    Text("第 ${selected + 1} / ${trace.tokens.size} 个 Token", style = MaterialTheme.typography.labelLarge)
                    Text("已选 ${current.chosen.displayToken()} · ${current.chosen.probability?.let(format::format) ?: "无概率数据"}")
                    val alternatives = current.alternatives.sortedByDescending { it.logProbability ?: Double.NEGATIVE_INFINITY }
                    if (alternatives.isEmpty()) Text("模型未返回其他候选。")
                    for (candidate in alternatives) {
                        val enabled = canReroll && trace.replacement(selected, candidate) != null
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(candidate.displayToken(), Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            candidate.probability?.let { Text(format.format(it), style = MaterialTheme.typography.labelMedium) }
                            if (enabled) TextButton({ reroll(selected, candidate) }) { Text("从这里续写") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(dismiss) { Text("关闭") } },
    )
}

private fun ReplyTokenCandidate.displayToken(): String =
    text.replace(" ", "·").replace("\n", "↵").replace("\r", "␍").replace("\t", "⇥").ifEmpty {
        bytes.joinToString(" ") { it.toString(16).padStart(2, '0') }
    }
