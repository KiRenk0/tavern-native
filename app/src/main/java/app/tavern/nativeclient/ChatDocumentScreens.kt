package app.tavern.nativeclient

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

internal data class OpenChatDocument(val document: ChatDocument, val messageIndex: Int?)

@Composable
internal fun ChatDocumentChip(document: ChatDocument, open: () -> Unit, remove: (() -> Unit)? = null, enabled: Boolean = true) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.widthIn(max = 260.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(open, modifier = Modifier.weight(1f, fill = false)) {
                Icon(Icons.AutoMirrored.Outlined.Article, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(document.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (remove != null) IconButton(remove, enabled = enabled) { Icon(Icons.Outlined.Close, "移除 ${document.name}") }
        }
    }
}

@Composable
internal fun ChatDocumentDialog(document: ChatDocument, model: TavernViewModel, enabled: Boolean,
                                close: () -> Unit, remove: (() -> Unit)?) {
    val previewLimit = 12_000
    var content by remember(document.id) { mutableStateOf<String?>(null) }
    var loading by remember(document.id) { mutableStateOf(true) }
    var confirmRemove by remember(document.id) { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(CreateDocument("text/plain")) { uri ->
        if (uri != null) model.exportChatDocument(document, uri)
    }
    LaunchedEffect(document.id) {
        try {
            content = model.readChatDocument(document, previewLimit + 1)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            content = null
        } finally {
            loading = false
        }
    }
    AlertDialog(onDismissRequest = close, title = { Text(document.name, maxLines = 2, overflow = TextOverflow.Ellipsis) }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if (content == null) Text("此对话缺少文档内容。可用含附件的 ZIP 重新导入。")
            else content?.let { value ->
                SelectionContainer { Text(value.take(previewLimit)) }
                if (value.length > previewLimit) Text("预览较长文档时，可导出完整文本。", style = MaterialTheme.typography.bodySmall)
            }
            if (remove != null) TextButton({ confirmRemove = true }, enabled = enabled) { Text("从消息移除文档") }
        }
    }, dismissButton = { TextButton(close) { Text("关闭") } }, confirmButton = {
        Button({ export.launch("${safeFilename(document.name.substringBeforeLast('.'))}.txt") }, enabled = enabled && content != null) { Text("导出文本") }
    })
    if (confirmRemove && remove != null) AlertDialog(onDismissRequest = { confirmRemove = false }, title = { Text("从消息移除文档？") },
        text = { Text(document.name) }, dismissButton = { TextButton({ confirmRemove = false }) { Text("取消") } },
        confirmButton = { Button({ confirmRemove = false; remove(); close() }) { Text("移除") } })
}
