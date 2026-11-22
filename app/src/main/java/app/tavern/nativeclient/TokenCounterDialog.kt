package app.tavern.nativeclient

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun TokenCounterDialog(
    chat: Chat,
    connection: ModelConnection?,
    model: TavernViewModel,
    initialText: String,
    dismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }
    var selection by remember { mutableStateOf(TextTokenizer.MODEL) }
    var remoteAttempt by remember(text, selection, connection) { mutableStateOf(0) }
    var fullChat by remember(chat.id, chat.messages, selection, connection) { mutableStateOf<Result<TokenPreview>?>(null) }
    var countingChat by remember(chat.id, chat.messages, selection, connection) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val local by produceState<Result<TokenPreview>?>(null, text, selection, connection) {
        value = null
        delay(200)
        value = readTokenPreview { model.inspectTokens(connection, text, selection, remote = false) }
    }
    val remote by produceState<Result<TokenPreview>?>(null, text, selection, connection, remoteAttempt) {
        value = null
        if (remoteAttempt > 0) value = readTokenPreview { model.inspectTokens(connection, text, selection, remote = true) }
    }
    val result = remote?.getOrNull() ?: local?.getOrNull()
    val pendingRemote = remoteAttempt > 0 && remote == null
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Token 计数器") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SelectField("分词器", selection, TextTokenizer.entries, TextTokenizer::label) { selection = it }
                Field("要分词的文本", text, { text = it }, lines = 4)
                if (selection == TextTokenizer.MODEL && connection?.supportsRemoteTokenCount == true) {
                    OutlinedButton({ remoteAttempt++ }, modifier = Modifier.fillMaxWidth(),
                        enabled = text.isNotEmpty() && text.length <= TOKEN_INSPECT_TEXT_LIMIT && !pendingRemote) {
                        Text(if (pendingRemote) "正在请求服务端…" else "用当前模型服务分词")
                    }
                }
                val error = remote?.exceptionOrNull() ?: local?.exceptionOrNull()
                if (error != null) Text(error.localizedMessage ?: "统计失败", color = MaterialTheme.colorScheme.error)
                if (result != null) {
                    TokenCountDetails(result)
                } else if (local == null && text.isNotEmpty()) {
                    Text("正在分词…", style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
                OutlinedButton({
                    countingChat = true
                    scope.launch {
                        try {
                            fullChat = readTokenPreview { model.countChatTokens(chat, connection, selection) }
                        } finally {
                            countingChat = false
                        }
                    }
                }, enabled = !countingChat, modifier = Modifier.fillMaxWidth()) { Text("统计当前聊天正文") }
                fullChat?.getOrNull()?.let { Text("${it.count} Token · ${it.source}", style = MaterialTheme.typography.bodySmall) }
                fullChat?.exceptionOrNull()?.let { Text(it.localizedMessage ?: "统计失败", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(dismiss) { Text("关闭") } },
    )
}

private suspend fun readTokenPreview(block: suspend () -> TokenPreview): Result<TokenPreview> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TokenCountDetails(value: TokenPreview) {
    Text("${value.count} Token · ${value.source}", style = MaterialTheme.typography.titleSmall)
    if (value.pieces.isEmpty()) return
    val idText = remember(value.pieces) { value.pieces.joinToString(", ", "[", "]") { it.id.toString() } }
    Text(
        if (value.count > value.pieces.size) "前 ${value.pieces.size} 个 Token ID" else "Token ID",
        style = MaterialTheme.typography.labelLarge,
    )
    SelectionContainer { Text(idText, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
    if (value.pieces.none { it.text != null }) return
    val colors = MaterialTheme.colorScheme
    val palette = listOf(colors.primaryContainer to colors.onPrimaryContainer,
        colors.secondaryContainer to colors.onSecondaryContainer, colors.tertiaryContainer to colors.onTertiaryContainer)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        value.pieces.forEachIndexed { index, piece ->
            val (background, foreground) = palette[index % palette.size]
            Surface(shape = MaterialTheme.shapes.extraSmall, color = background, contentColor = foreground) {
                Column(Modifier.padding(horizontal = 6.dp, vertical = 3.dp)) {
                    Text(piece.text.orEmpty().replace(" ", "·").replace("\n", "↵").replace("\r", "␍").replace("\t", "⇥"),
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text(piece.id.toString(), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
