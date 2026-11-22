package app.tavern.nativeclient.backup

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tavern.nativeclient.ActionIcon
import app.tavern.nativeclient.TavernState
import app.tavern.nativeclient.TavernViewModel
import app.tavern.nativeclient.chat.TextDialog
import app.tavern.nativeclient.safeFilename
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val displayDate = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

@Composable
internal fun SettingsSnapshotsScreen(state: TavernState, model: TavernViewModel, back: () -> Unit) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var restoring by rememberSaveable { mutableStateOf<String?>(null) }
    var exporting by rememberSaveable { mutableStateOf<String?>(null) }
    val busy = state.busy || state.generation != null || state.scriptRunning
    val result by produceState<Result<SettingsSnapshotIndex>?>(null, busy) {
        if (!busy) value = try {
            Result.success(model.settingsSnapshots())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    val index = result?.getOrNull()
    val snapshots = index?.items.orEmpty()
    val importFile = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) model.importSettingsSnapshot(uri)
    }
    val exportFile = rememberLauncherForActivityResult(CreateDocument("application/json")) { uri ->
        val id = exporting
        exporting = null
        if (uri != null && id != null) model.exportSettingsSnapshot(id, uri)
    }
    BackHandler { if (!busy) back() }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(back, enabled = !busy) { Text("返回设置") }
                Text("设置快照", style = MaterialTheme.typography.headlineSmall)
                Text("恢复会先保存当前设置。JSON 导出省略 API Key 和同步密码。角色卡、聊天与附件可用账户 ZIP 迁移。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton({ creating = true }, enabled = !busy) { Text("新建") }
                    OutlinedButton({ importFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                        enabled = !busy) { Text("导入 JSON") }
                }
                val unresolved = result
                when {
                    unresolved == null || busy -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    unresolved.isFailure -> Text("无法读取设置快照")
                    snapshots.isEmpty() -> Text("还没有设置快照")
                }
                if (index != null && index.unreadable > 0) Text("有 ${index.unreadable} 份快照无法读取。")
            }
        }
        items(snapshots, key = { it.id }) { item ->
            var menu by remember { mutableStateOf(false) }
            OutlinedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (item.created > 0) Text(displayDate.format(Instant.ofEpochMilli(item.created).atZone(ZoneId.systemDefault())),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton({ restoring = item.id }, enabled = !busy) { Text("恢复") }
                    Box {
                        ActionIcon(Icons.Outlined.MoreHoriz, "快照操作", !busy) { menu = true }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("导出 JSON") }, onClick = {
                                menu = false
                                exporting = item.id
                                exportFile.launch("${safeFilename(item.name)}.json")
                            })
                            DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; renaming = item.id })
                            DropdownMenuItem(text = { Text("删除快照") }, onClick = { menu = false; deleting = item.id })
                        }
                    }
                }
            }
        }
    }
    if (creating) TextDialog("新建设置快照", "名称", "", { creating = false }) {
        model.createSettingsSnapshot(it)
        creating = false
    }
    snapshots.firstOrNull { it.id == renaming }?.let { item ->
        TextDialog("重命名设置快照", "名称", item.name, { renaming = null }) {
            model.renameSettingsSnapshot(item.id, it)
            renaming = null
        }
    }
    snapshots.firstOrNull { it.id == restoring }?.let { item ->
        AlertDialog(onDismissRequest = { restoring = null }, title = { Text("恢复设置快照") },
            text = { Text("恢复「${item.name}」中的配置？应用会先保存还原前的设置。") },
            confirmButton = {
                TextButton({ restoring = null; model.restoreSettingsSnapshot(item.id) }, enabled = !busy) { Text("恢复") }
            }, dismissButton = { TextButton({ restoring = null }) { Text("取消") } })
    }
    snapshots.firstOrNull { it.id == deleting }?.let { item ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除设置快照") },
            text = { Text("确定删除「${item.name}」？") },
            confirmButton = {
                TextButton({ deleting = null; model.deleteSettingsSnapshot(item.id) }, enabled = !busy) { Text("删除") }
            }, dismissButton = { TextButton({ deleting = null }) { Text("取消") } })
    }
}
