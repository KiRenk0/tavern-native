package app.tavern.nativeclient

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.*
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
    var currentFolder by rememberSaveable { mutableStateOf("") }
    var gridView by rememberSaveable { mutableStateOf(true) }
    var search by rememberSaveable { mutableStateOf("") }
    var creatingFolder by rememberSaveable { mutableStateOf(false) }
    var renamingFolder by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingFolder by rememberSaveable { mutableStateOf<String?>(null) }
    var movingItem by rememberSaveable { mutableStateOf<String?>(null) }
    var rename by rememberSaveable { mutableStateOf<String?>(null) }
    var remove by rememberSaveable { mutableStateOf<String?>(null) }
    var preview by rememberSaveable { mutableStateOf<String?>(null) }
    val load = rememberLauncherForActivityResult(OpenMultipleDocuments()) {
        if (it.isNotEmpty()) model.importBackgrounds(it, currentFolder)
    }
    val allFolders = remember(library) { library.allFolders() }
    LaunchedEffect(allFolders, currentFolder) {
        if (currentFolder.isNotEmpty() && currentFolder !in allFolders) {
            currentFolder = backgroundParentFolder(currentFolder)
        }
    }
    val breadcrumbs = remember(library, currentFolder) { library.breadcrumbs(currentFolder) }
    val query = search.trim()
    val childFolders = remember(library, currentFolder, query) {
        if (query.isNotEmpty()) emptyList() else library.childFolders(currentFolder)
    }
    val displayed = remember(library, currentFolder, query) {
        val base = if (query.isNotEmpty()) library.items else library.itemsInFolder(currentFolder)
        base.filter { query.isEmpty() || it.title.contains(query, true) || it.fullPath.contains(query, true) }
            .sortedByDescending(BackgroundEntry::added)
    }
    val gridRows = remember(displayed) { displayed.chunked(2) }
    AlertDialog(
        onDismissRequest = { if (!busy) dismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("背景库", Modifier.weight(1f))
                ActionIcon(
                    if (gridView) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView,
                    if (gridView) "切换为列表视图" else "切换为网格视图",
                    !busy,
                ) { gridView = !gridView }
            }
        },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilledTonalButton({ load.launch(arrayOf("image/*")) }, enabled = !busy) {
                                Icon(Icons.Outlined.AddPhotoAlternate, null)
                                Spacer(Modifier.width(6.dp))
                                Text("导入背景")
                            }
                            OutlinedButton({ creatingFolder = true }, enabled = !busy) {
                                Icon(Icons.Outlined.CreateNewFolder, null)
                                Spacer(Modifier.width(6.dp))
                                Text("新建文件夹")
                            }
                        }
                        state.utility?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        val default = library.selected?.name.orEmpty().ifBlank { "无" }
                        Text("默认背景 · $default", style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (library.selected != null) TextButton({ model.selectLibraryBackground(null) }, enabled = !busy) {
                            Text("清除默认背景")
                        }
                        if (locked != null) {
                            Text("对话已锁定 · ${locked.name}", style = MaterialTheme.typography.bodyMedium)
                            chat?.let { activeChat ->
                                TextButton({ model.clearDrawingBackground(activeChat.id) }, enabled = !busy) { Text("解除背景锁定") }
                            }
                            if (library.items.none { it.image.id == locked.id }) {
                                TextButton({ model.addLibraryBackground(locked) }, enabled = !busy) { Text("将锁定背景加入背景库") }
                            }
                        }
                        SelectField("图片适配", library.fit, BackgroundFit.entries, BackgroundFit::label, !busy, model::setBackgroundFit)
                        if (library.items.isNotEmpty() || allFolders.isNotEmpty()) {
                            Field("搜索背景或路径", search, { search = it }, enabled = !busy)
                        }
                        if (query.isEmpty() && (allFolders.isNotEmpty() || currentFolder.isNotEmpty())) {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                items(breadcrumbs.withIndex().toList(), key = { it.value.first }) { (idx, crumb) ->
                                    if (idx > 0) Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp))
                                    FilterChip(
                                        selected = crumb.first == currentFolder,
                                        onClick = { currentFolder = crumb.first },
                                        label = { Text(crumb.second, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        enabled = !busy,
                                    )
                                }
                            }
                        }
                        if (displayed.isEmpty() && childFolders.isEmpty()) {
                            Text(if (library.items.isEmpty() && allFolders.isEmpty()) "背景库为空" else "此文件夹暂无背景")
                        }
                    }
                }
                if (childFolders.isNotEmpty()) {
                    items(childFolders, key = { "folder:$it" }) { folderPath ->
                        val folderLabel = backgroundFolderName(folderPath)
                        val count = library.folderItemCount(folderPath)
                        var folderMenu by remember { mutableStateOf(false) }
                        OutlinedCard(Modifier.fillMaxWidth().clickable(enabled = !busy) { currentFolder = folderPath }) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f)) {
                                    Text(folderLabel, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("$count 张背景", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Box {
                                    ActionIcon(Icons.Outlined.MoreHoriz, "文件夹操作", !busy) { folderMenu = true }
                                    DropdownMenu(folderMenu, { folderMenu = false }) {
                                        DropdownMenuItem(text = { Text("重命名文件夹") }, onClick = {
                                            renamingFolder = folderPath; folderMenu = false
                                        })
                                        DropdownMenuItem(text = { Text("删除文件夹") }, onClick = {
                                            deletingFolder = folderPath; folderMenu = false
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
                if (gridView) {
                    items(gridRows, key = { row -> row.joinToString(":") { it.image.id } }) { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { entry ->
                                Box(Modifier.weight(1f)) {
                                    BackgroundEntryCard(
                                        entry = entry,
                                        library = library,
                                        chat = chat,
                                        locked = locked,
                                        model = model,
                                        busy = busy,
                                        compact = true,
                                        showFolderPath = query.isNotEmpty(),
                                        onPreview = { preview = entry.image.id },
                                        onRename = { rename = entry.image.id },
                                        onMove = { movingItem = entry.image.id },
                                        onRemove = { remove = entry.image.id },
                                    )
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                } else {
                    items(displayed, key = { it.image.id }) { entry ->
                        BackgroundEntryCard(
                            entry = entry,
                            library = library,
                            chat = chat,
                            locked = locked,
                            model = model,
                            busy = busy,
                            compact = false,
                            showFolderPath = query.isNotEmpty(),
                            onPreview = { preview = entry.image.id },
                            onRename = { rename = entry.image.id },
                            onMove = { movingItem = entry.image.id },
                            onRemove = { remove = entry.image.id },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(dismiss, enabled = !busy) { Text("关闭") } },
    )
    if (creatingFolder) {
        TextDialog("新建文件夹", "文件夹名称（支持用 / 创建多级路径）", "", { creatingFolder = false }) { name ->
            val path = if (currentFolder.isEmpty()) name else "$currentFolder/$name"
            model.createBackgroundFolder(path)
            creatingFolder = false
        }
    }
    renamingFolder?.let { path ->
        TextDialog("重命名文件夹", "文件夹名称", backgroundFolderName(path), { renamingFolder = null }) { next ->
            model.renameBackgroundFolder(path, next)
            renamingFolder = null
        }
    }
    deletingFolder?.let { path ->
        AlertDialog(
            onDismissRequest = { deletingFolder = null },
            title = { Text("删除文件夹") },
            text = { Text("删除「${backgroundFolderName(path)}」？其中的背景图片会保留并移动到上级目录。") },
            confirmButton = {
                TextButton({
                    if (currentFolder == path || currentFolder.startsWith("$path/")) {
                        currentFolder = backgroundParentFolder(path)
                    }
                    model.deleteBackgroundFolder(path)
                    deletingFolder = null
                }, enabled = !busy) { Text("删除") }
            },
            dismissButton = { TextButton({ deletingFolder = null }) { Text("取消") } },
        )
    }
    movingItem?.let { id ->
        library.items.firstOrNull { it.image.id == id }?.let { entry ->
            var target by rememberSaveable(id) { mutableStateOf(entry.normalizedFolder) }
            val destinations = remember(allFolders) { listOf("") + allFolders }
            AlertDialog(
                onDismissRequest = { movingItem = null },
                title = { Text("移动到文件夹") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("背景：${entry.title}", style = MaterialTheme.typography.bodySmall)
                        SelectField(
                            "目标文件夹",
                            target,
                            destinations,
                            { it.ifEmpty { "根目录" } },
                            !busy,
                        ) { target = it }
                        Field("或输入新路径", target, { target = it }, placeholder = "例如：场景/夜晚", enabled = !busy)
                    }
                },
                confirmButton = {
                    TextButton({
                        model.moveLibraryBackground(id, target)
                        movingItem = null
                    }, enabled = !busy) { Text("移动") }
                },
                dismissButton = { TextButton({ movingItem = null }) { Text("取消") } },
            )
        }
    }
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
            AlertDialog(onDismissRequest = { preview = null }, title = { Text(entry.fullPath) },
                text = { ChatImageContent(entry.image, model.backgroundFile(entry.image), 1440,
                    Modifier.fillMaxWidth().heightIn(max = 420.dp)) },
                confirmButton = { TextButton({ preview = null }) { Text("关闭") } })
        }
    }
}

@Composable
private fun BackgroundEntryCard(
    entry: BackgroundEntry,
    library: BackgroundLibrary,
    chat: Chat?,
    locked: ChatImage?,
    model: TavernViewModel,
    busy: Boolean,
    compact: Boolean,
    showFolderPath: Boolean,
    onPreview: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onRemove: () -> Unit,
) {
    val image = entry.image
    var menu by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(if (compact) 8.dp else 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.fillMaxWidth().height(if (compact) 86.dp else 105.dp).clickable(enabled = !busy, onClick = onPreview)) {
                ChatImageContent(image, model.backgroundFile(image), if (compact) 240 else 320, Modifier.fillMaxSize(), crop = true)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.title,
                        maxLines = if (compact) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (showFolderPath && entry.normalizedFolder.isNotEmpty()) {
                        Text(
                            entry.normalizedFolder,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Box {
                    ActionIcon(Icons.Outlined.MoreHoriz, "背景操作", !busy) { menu = true }
                    DropdownMenu(menu, { menu = false }) {
                        if (image.id != library.selectedId) {
                            DropdownMenuItem(text = { Text("设为默认背景") }, onClick = {
                                model.selectLibraryBackground(image.id); menu = false
                            })
                        }
                        if (chat != null && locked?.id != image.id) {
                            DropdownMenuItem(text = { Text("锁定到当前对话") }, onClick = {
                                model.setDrawingBackground(chat.id, image); menu = false
                            })
                        }
                        DropdownMenuItem(
                            text = { Text("移动到文件夹") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null) },
                            onClick = { onMove(); menu = false },
                        )
                        DropdownMenuItem(text = { Text("重命名") }, onClick = { onRename(); menu = false })
                        DropdownMenuItem(text = { Text("从背景库移除") }, onClick = { onRemove(); menu = false })
                    }
                }
            }
            if (!compact) {
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
