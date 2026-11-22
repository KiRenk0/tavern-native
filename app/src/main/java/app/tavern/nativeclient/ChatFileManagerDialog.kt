package app.tavern.nativeclient

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun ChatFileManagerDialog(state: TavernState, model: TavernViewModel, close: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var menuId by remember { mutableStateOf<String?>(null) }
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var requestedTitle by rememberSaveable { mutableStateOf("") }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    val busy = state.busy || state.generation != null || state.scriptRunning
    val matches = remember(state.history, query) { state.history.filter { it.title.contains(query.trim(), true) } }
    fun export(uri: Uri?, format: ChatExportFormat) {
        val id = exportId
        exportId = null
        if (uri != null && id != null) model.exportChatFile(uri, id, format)
    }
    val exportJson = rememberLauncherForActivityResult(CreateDocument(ChatExportFormat.JSONL.mime)) { export(it, ChatExportFormat.JSONL) }
    val exportText = rememberLauncherForActivityResult(CreateDocument(ChatExportFormat.TEXT.mime)) { export(it, ChatExportFormat.TEXT) }
    val exportArchive = rememberLauncherForActivityResult(CreateDocument(ChatExportFormat.ARCHIVE.mime)) { export(it, ChatExportFormat.ARCHIVE) }

    val rename = state.history.firstOrNull { it.id == renameId }
    val deleting = state.history.firstOrNull { it.id == deleteId }
    when {
        rename != null -> {
            val title = requestedTitle.trim()
            val conflict = state.history.any { it.id != rename.id && it.title.equals(title, true) }
            AlertDialog(onDismissRequest = { if (!busy) renameId = null }, title = { Text("重命名聊天") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Field("聊天名称", requestedTitle, { requestedTitle = it }, enabled = !busy)
                    if (conflict) Text("聊天名称已存在", color = MaterialTheme.colorScheme.error)
                }
            }, confirmButton = {
                TextButton({ model.renameChatFile(rename.id, title) { renameId = null } }, enabled = !busy && title.isNotBlank() && !conflict) {
                    Text("保存")
                }
            }, dismissButton = { TextButton({ renameId = null }, enabled = !busy) { Text("取消") } })
        }
        deleting != null -> Confirm("删除聊天？", "将删除「${deleting.title}」。", { deleteId = null }) {
            deleteId = null
            model.removeChat(deleting)
        }
        else -> AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text("管理聊天文件") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.history.isNotEmpty()) Field("搜索聊天名称", query, { query = it }, enabled = !busy)
                if (matches.isEmpty()) Text(if (state.history.isEmpty()) "暂无聊天记录" else "找不到匹配的聊天")
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(matches, key = ChatSummary::id) { entry ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { close(); model.openChat(entry.id) }, modifier = Modifier.weight(1f), enabled = !busy) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    val date = chatHistoryDate(entry)
                                    Text(if (entry.id == state.chat?.id) "当前聊天 · $date" else date,
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Box {
                                ActionIcon(Icons.Outlined.MoreVert, "管理 ${entry.title}", !busy) { menuId = entry.id }
                                DropdownMenu(menuId == entry.id, { menuId = null }) {
                                    MenuItem("重命名聊天", Icons.Outlined.Edit) {
                                        menuId = null
                                        requestedTitle = entry.title
                                        renameId = entry.id
                                    }
                                    for (format in ChatExportFormat.entries) MenuItem(format.label, Icons.Outlined.FileUpload) {
                                        menuId = null
                                        exportId = entry.id
                                        val filename = "${safeFilename(entry.title)}.${format.suffix}"
                                        when (format) {
                                            ChatExportFormat.JSONL -> exportJson.launch(filename)
                                            ChatExportFormat.TEXT -> exportText.launch(filename)
                                            ChatExportFormat.ARCHIVE -> exportArchive.launch(filename)
                                        }
                                    }
                                    MenuItem("删除聊天", Icons.Outlined.DeleteOutline) {
                                        menuId = null
                                        deleteId = entry.id
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(close, enabled = !busy) { Text("关闭") } })
    }
}
