package app.tavern.nativeclient

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ChatBackupScreen(state: TavernState, model: TavernViewModel, subject: String, close: () -> Unit) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    var previewId by rememberSaveable { mutableStateOf<Long?>(null) }
    var restoreId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    val busy = state.busy || state.generation != null || state.scriptRunning
    val library by produceState<Result<ChatBackupLibrary>?>(null, busy) {
        if (!busy) value = loadBackupContent { model.chatBackups() }
    }
    val preview by produceState<Pair<Long, Result<Chat>>?>(null, previewId) {
        previewId?.let { id -> value = id to loadBackupContent { model.previewChatBackup(id) } }
    }
    val index = library?.getOrNull()
    val entries = index?.entries.orEmpty()
    val current = state.chat?.takeUnless(Chat::isTemporary)
    val visible = if (showAll) entries else entries.filter { entry ->
        if (state.groupId != null) entry.groupId == state.groupId else entry.cardId == state.cardId
    }

    BackHandler {
        if (!busy) {
            if (previewId != null) previewId = null else close()
        }
    }
    val selected = entries.firstOrNull { it.id == previewId }
    val selectedContent = preview?.takeIf { it.first == previewId }?.second
    if (previewId != null && selected != null) {
        ChatBackupPreview(selected, selectedContent, busy,
            back = { previewId = null }, restore = { restoreId = selected.id })
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                TextButton(close, enabled = !busy) { Text("返回") }
                Text("聊天备份", style = MaterialTheme.typography.headlineSmall)
                if (index != null) GroupToggle("自动备份聊天", index.automatic, !busy, model::setAutomaticChatBackups)
                if (current != null) OutlinedButton({ model.saveChatBackup(current.id) }, enabled = !busy) {
                    Text("备份当前聊天")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!showAll, { showAll = false }, label = { Text("当前角色或群组") })
                    FilterChip(showAll, { showAll = true }, label = { Text("全部备份") })
                }
                when {
                    library == null || busy -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    library?.isFailure == true -> Text("无法读取聊天备份")
                    visible.isEmpty() -> Text("这里还没有聊天备份。")
                }
            }
            items(visible, key = ChatBackup::id) { entry ->
                val owner = state.cards.firstOrNull { it.id == entry.cardId }?.name
                    ?: state.groups.firstOrNull { it.id == entry.groupId }?.name
                ElevatedCard(onClick = { previewId = entry.id }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium)
                            if (showAll && owner != null) Text(owner, style = MaterialTheme.typography.bodySmall)
                            ChatBackupStamp(entry)
                        }
                        ActionIcon(Icons.Outlined.DeleteOutline, "删除备份", !busy) { deleteId = entry.id }
                    }
                }
            }
        }
    }

    entries.firstOrNull { it.id == restoreId }?.let { entry ->
        AlertDialog(
            onDismissRequest = { restoreId = null },
            title = { Text("恢复聊天备份？") },
            text = { Text("将「${entry.title}」恢复为「$subject」下的一段新聊天。") },
            confirmButton = {
                TextButton({
                    restoreId = null
                    model.restoreChatBackup(entry.id, close)
                }, enabled = !busy) { Text("恢复副本") }
            },
            dismissButton = { TextButton({ restoreId = null }) { Text("取消") } },
        )
    }
    entries.firstOrNull { it.id == deleteId }?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text("永久删除备份？") },
            text = { Text("将删除「${entry.title}」在此时间保存的备份。") },
            confirmButton = {
                TextButton({ deleteId = null; model.deleteChatBackup(entry.id) }, enabled = !busy) { Text("删除备份") }
            },
            dismissButton = { TextButton({ deleteId = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun ChatBackupPreview(
    entry: ChatBackup, content: Result<Chat>?, busy: Boolean, back: () -> Unit, restore: () -> Unit,
) {
    val messages = content?.getOrNull()?.messages.orEmpty()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TextButton(back, enabled = !busy) { Text("返回备份列表") }
            Text(entry.title, style = MaterialTheme.typography.headlineSmall)
            ChatBackupStamp(entry)
            when {
                content == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                content.isFailure -> Text("无法读取此备份。")
                else -> {
                    Text("${messages.size} 条消息", style = MaterialTheme.typography.bodySmall)
                    Button(restore, enabled = !busy) { Text("恢复副本") }
                }
            }
        }
        items(messages) { message ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(message.name, style = MaterialTheme.typography.labelLarge)
                    if (message.text.isNotBlank()) SelectionContainer { Text(message.text) }
                    if (message.variants.size > 1) Text("${message.variants.size} 条备选回复",
                        style = MaterialTheme.typography.bodySmall)
                    if (message.images.isNotEmpty() || message.documents.isNotEmpty() || message.drawing != null) {
                        Text("含附件", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatBackupStamp(entry: ChatBackup) {
    val context = LocalContext.current
    val summary = remember(entry.savedAt, entry.byteCount, context) {
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(entry.savedAt))
        val size = android.text.format.Formatter.formatShortFileSize(context, entry.byteCount)
        "$date · $size"
    }
    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private suspend fun <T> loadBackupContent(read: suspend () -> T): Result<T> = try {
    Result.success(read())
} catch (cancel: CancellationException) {
    throw cancel
} catch (error: Exception) {
    Result.failure(error)
}
