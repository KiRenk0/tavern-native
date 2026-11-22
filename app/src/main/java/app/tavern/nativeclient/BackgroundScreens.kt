package app.tavern.nativeclient

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tavern.nativeclient.chat.TextDialog

@Composable
internal fun BackgroundLibraryDialog(state: TavernState, model: TavernViewModel, chat: Chat?, dismiss: () -> Unit) {
    val library = state.prefs.backgrounds
    val locked = chat?.options?.background
    val busy = state.busy || state.generation != null || state.scriptRunning
    var search by rememberSaveable { mutableStateOf("") }
    var rename by rememberSaveable { mutableStateOf<String?>(null) }
    var remove by rememberSaveable { mutableStateOf<String?>(null) }
    var preview by rememberSaveable { mutableStateOf<String?>(null) }
    val load = rememberLauncherForActivityResult(OpenMultipleDocuments()) { if (it.isNotEmpty()) model.importBackgrounds(it) }
    val displayed = remember(library.items, search) {
        library.items.filter { it.title.contains(search.trim(), true) }.sortedByDescending(BackgroundEntry::added)
    }
    AlertDialog(
        onDismissRequest = { if (!busy) dismiss() },
        title = { Text("背景库") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 510.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilledTonalButton({ load.launch(arrayOf("image/*")) }, enabled = !busy) {
                            Icon(Icons.Outlined.AddPhotoAlternate, null)
                            Spacer(Modifier.width(8.dp))
                            Text("批量导入背景")
                        }
                        state.utility?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        val default = library.selected?.name.orEmpty().ifBlank { "无" }
                        Text("默认背景 · $default", style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (library.selected != null) TextButton({ model.selectLibraryBackground(null) }, enabled = !busy) {
                            Text("清除默认背景")
                        }
                        if (locked != null && chat != null) {
                            Text("对话已锁定 · ${locked.name}", style = MaterialTheme.typography.bodyMedium)
                            TextButton({ model.clearDrawingBackground(chat.id) }, enabled = !busy) { Text("解除背景锁定") }
                            if (library.items.none { it.image.id == locked.id }) {
                                TextButton({ model.addLibraryBackground(locked) }, enabled = !busy) { Text("将锁定背景加入背景库") }
                            }
                        }
                        SelectField("图片适配", library.fit, BackgroundFit.entries, BackgroundFit::label, !busy, model::setBackgroundFit)
                        if (library.items.isNotEmpty()) Field("搜索背景", search, { search = it }, enabled = !busy)
                        if (displayed.isEmpty()) Text(if (library.items.isEmpty()) "背景库为空" else "没有匹配的背景")
                    }
                }
                items(displayed, key = { it.image.id }) { entry ->
                    val image = entry.image
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            TextButton({ preview = image.id }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                ChatImageContent(image, model.backgroundFile(image), 320, Modifier.fillMaxWidth().height(105.dp), crop = true)
                            }
                            var menu by remember { mutableStateOf(false) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(entry.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleSmall)
                                Box {
                                    ActionIcon(Icons.Outlined.MoreHoriz, "背景操作", !busy) { menu = true }
                                    DropdownMenu(menu, { menu = false }) {
                                        DropdownMenuItem(text = { Text("重命名") }, onClick = { rename = image.id; menu = false })
                                        DropdownMenuItem(text = { Text("从背景库移除") }, onClick = { remove = image.id; menu = false })
                                    }
                                }
                            }
                            if (image.id != library.selectedId) {
                                TextButton({ model.selectLibraryBackground(image.id) }, enabled = !busy) { Text("设为默认背景") }
                            }
                            if (chat != null && locked?.id != image.id) {
                                TextButton({ model.setDrawingBackground(chat.id, image) }, enabled = !busy) { Text("锁定到当前对话") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(dismiss, enabled = !busy) { Text("关闭") } },
    )
    rename?.let { id ->
        library.items.firstOrNull { it.image.id == id }?.let { entry ->
            TextDialog("重命名背景", "背景名称", entry.title, { rename = null }) {
                model.renameLibraryBackground(id, it)
                rename = null
            }
        }
    }
    remove?.let { id ->
        AlertDialog(onDismissRequest = { remove = null }, title = { Text("移除背景") },
            text = { Text("将这张图片从背景库移除？已锁定的对话仍会使用它。") },
            confirmButton = {
                TextButton({ model.removeLibraryBackground(id); remove = null }, enabled = !busy) { Text("移除") }
            },
            dismissButton = { TextButton({ remove = null }) { Text("取消") } })
    }
    preview?.let { id ->
        library.items.firstOrNull { it.image.id == id }?.let { entry ->
            AlertDialog(onDismissRequest = { preview = null }, title = { Text(entry.title) },
                text = { ChatImageContent(entry.image, model.backgroundFile(entry.image), 1440,
                    Modifier.fillMaxWidth().heightIn(max = 420.dp)) },
                confirmButton = { TextButton({ preview = null }) { Text("关闭") } })
        }
    }
}
