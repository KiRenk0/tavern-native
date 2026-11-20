package app.tavern.nativeclient

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun ReplyAutomationDialog(current: ReplyAutomation, busy: Boolean, dismiss: () -> Unit,
                                   save: (ReplyAutomation) -> Unit) {
    var continuing by rememberSaveable { mutableStateOf(current.autoContinue) }
    var target by rememberSaveable { mutableStateOf(current.targetTokens.toString()) }
    var swiping by rememberSaveable { mutableStateOf(current.autoSwipe) }
    var minimum by rememberSaveable { mutableStateOf(current.minimumCharacters.toString()) }
    var words by rememberSaveable { mutableStateOf(current.blockedWords.joinToString("\n")) }
    var threshold by rememberSaveable { mutableStateOf(current.blockedWordThreshold.toString()) }
    var limit by rememberSaveable { mutableStateOf(current.extraRequests) }
    val tokens = target.toIntOrNull()?.takeIf { it in 0..1024 }
    val characters = minimum.toIntOrNull()?.takeIf { it in 0..4000 }
    val hits = threshold.toIntOrNull()?.takeIf { it in 0..100 }
    AlertDialog(
        onDismissRequest = { if (!busy) dismiss() },
        title = { Text("回复自动化") },
        text = {
            Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GroupToggle("自动续写", continuing, !busy) { continuing = it }
                if (continuing) {
                    Field("目标长度（0–1024 Token）", target, { target = it },
                        enabled = !busy, type = KeyboardType.Number)
                    Text("正文未达到目标长度时，接着写入同一条回复。填写 0 可停止自动续写。",
                        style = MaterialTheme.typography.bodySmall)
                }
                GroupToggle("自动生成备选回复", swiping, !busy) { swiping = it }
                if (swiping) {
                    Field("最少字数（0–4000）", minimum, { minimum = it },
                        enabled = !busy, type = KeyboardType.Number)
                    OutlinedTextField(words, { words = it }, label = { Text("屏蔽词") }, modifier = Modifier.fillMaxWidth(),
                        enabled = !busy, maxLines = 4, placeholder = { Text("以换行、逗号或顿号分隔") })
                    Field("触发所需的屏蔽词出现次数（0–100）", threshold, { threshold = it },
                        enabled = !busy, type = KeyboardType.Number)
                    Text("字数过少或屏蔽词达到指定次数时，生成下一条备选。0 表示关闭对应条件。",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (continuing || swiping) {
                    SelectField("每条回复的额外请求上限", limit, (1..5).toList(), Int::toString, !busy) { limit = it }
                }
            }
        },
        confirmButton = {
            TextButton({
                val entries = words.split('\n', '，', ',', '、')
                save(ReplyAutomation(continuing, tokens ?: current.targetTokens, swiping,
                    characters ?: current.minimumCharacters, ReplyAutomation.words(entries),
                    hits ?: current.blockedWordThreshold, limit))
            }, enabled = !busy && (!continuing || tokens != null) && (!swiping || characters != null && hits != null)) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(dismiss, enabled = !busy) { Text("取消") } },
    )
}

@Composable
internal fun ReplyAlternativePicker(
    message: Message, busy: Boolean, dismiss: () -> Unit, select: (Int) -> Unit, delete: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf<Int?>(null) }
    var deleting by rememberSaveable { mutableStateOf<Int?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text("备选回复") }, text = {
        LazyColumn(Modifier.heightIn(max = 455.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(message.variants.size) { option ->
                val selected = option == message.index
                val text = message.variants[option]
                val open = expanded == option
                OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("第 ${option + 1} 条" + if (selected) " · 当前" else "",
                                Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                            ActionIcon(Icons.Outlined.DeleteOutline, "删除第 ${option + 1} 条备选回复",
                                !busy && message.variants.size > 1) { deleting = option }
                        }
                        SelectionContainer {
                            Text(text.ifBlank { "（空消息）" }, maxLines = if (open) Int.MAX_VALUE else 5,
                                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        }
                        Row {
                            if (text.length > 100 || text.count { it == '\n' } >= 5) {
                                TextButton({ expanded = if (open) null else option }) { Text(if (open) "收起" else "展开") }
                            }
                            if (!selected) TextButton({ select(option) }, enabled = !busy) { Text("切换到此回复") }
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(dismiss, enabled = !busy) { Text("关闭") } })
    deleting?.let { option ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除备选回复") },
            text = { Text("确定删除第 ${option + 1} 条备选回复？") },
            confirmButton = {
                TextButton({
                    deleting = null
                    expanded = null
                    delete(option)
                }, enabled = !busy && option in message.variants.indices && message.variants.size > 1) { Text("删除") }
            },
            dismissButton = { TextButton({ deleting = null }) { Text("取消") } })
    }
}
