package app.tavern.nativeclient

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun OrganizedLibrary(
    state: TavernState, catalog: LibraryPreferences, model: TavernViewModel, busy: Boolean, groupsOnly: Boolean,
    createGroup: () -> Unit, assignTags: (Array<String>) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var folderId by rememberSaveable { mutableStateOf<String?>(null) }
    var kind by rememberSaveable { mutableStateOf("") }
    var favorites by rememberSaveable { mutableStateOf(false) }
    var included by rememberSaveable { mutableStateOf(emptyArray<String>()) }
    var excluded by rememberSaveable { mutableStateOf(emptyArray<String>()) }
    var filters by rememberSaveable { mutableStateOf(false) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(emptyArray<String>()) }
    var sortPicker by remember { mutableStateOf(false) }
    val entries = remember(catalog, state.cards, state.groups, groupsOnly, kind) {
        catalog.entities(state.cards, state.groups).filter {
            (!groupsOnly || it.group != null) && (kind != "card" || it.card != null) && (kind != "group" || it.group != null)
        }
    }
    val tagIds = remember(catalog.tags) { catalog.tags.mapTo(hashSetOf(), LibraryTag::id) }
    val include = remember(included, tagIds) { included.filterTo(hashSetOf()) { it in tagIds } }
    val exclude = remember(excluded, tagIds) { excluded.filterTo(hashSetOf()) { it in tagIds } }
    val folder = catalog.tags.firstOrNull { catalog.folders && it.id == folderId && it.folder != TagFolder.NONE }
    val matches = remember(entries, catalog, state.libraryUsage, query, include, exclude, folder, favorites) {
        browseLibrary(entries, catalog, state.libraryUsage, query, include, exclude, folder?.id, favorites)
    }
    val folders = remember(entries, catalog, state.libraryUsage, query, include, exclude, folder, favorites) {
        if (!catalog.folders || folder != null) emptyList() else catalog.tags.mapNotNull { tag ->
            if (tag.folder == TagFolder.NONE || tag.id in include || tag.id in exclude) return@mapNotNull null
            val count = browseLibrary(entries, catalog, state.libraryUsage, query, include, exclude, tag.id, favorites).size
            val searched = query.isNotBlank() && tag.name.contains(query.trim(), true)
            if (count > 0 || searched) tag to count else null
        }
    }
    val chosen = selected.toSet()
    BackHandler(enabled = !busy && (selecting || folder != null)) {
        if (selecting) { selecting = false; selected = emptyArray() } else folderId = null
    }
    val byTag = remember(catalog.tags) { catalog.tags.associateBy(LibraryTag::id) }
    fun open(item: LibraryItem) {
        if (selecting) selected = (if (item.id in chosen) chosen - item.id else chosen + item.id).toTypedArray()
        else {
            item.card?.let(model::openCard)
            item.group?.let(model::openGroup)
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (groupsOnly) item {
                OutlinedButton(createGroup, enabled = !busy && state.cards.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.GroupAdd, null)
                    Spacer(Modifier.width(8.dp))
                    Text("新建群组")
                }
            }
            if (folder != null) item {
                TextButton(onClick = { folderId = null }, enabled = !busy) {
                    Icon(Icons.Outlined.Folder, null)
                    Spacer(Modifier.width(8.dp))
                    Text("返回全部 · ${folder.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            item {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
                    label = { Text(if (groupsOnly) "搜索群组" else "搜索角色或群组") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = if (query.isEmpty()) null else {
                        { ActionIcon(Icons.Outlined.Close, "清除搜索", !busy) { query = "" } }
                    }, shape = RoundedCornerShape(24.dp))
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(favorites, { favorites = !favorites }, enabled = !busy,
                            label = { Text("收藏") }, leadingIcon = { Icon(Icons.Outlined.StarOutline, null) })
                    }
                    if (!groupsOnly) {
                        item {
                            FilterChip(kind == "card", { kind = if (kind == "card") "" else "card" }, enabled = !busy,
                                label = { Text("角色") })
                        }
                        item {
                            FilterChip(kind == "group", { kind = if (kind == "group") "" else "group" }, enabled = !busy,
                                label = { Text("群组") })
                        }
                    }
                    item {
                        val count = include.size + exclude.size
                        FilterChip(count > 0, { filters = true }, enabled = !busy,
                            label = { Text(if (count == 0) "标签" else "标签 ($count)") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Label, null) })
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (selecting) {
                        Text("已选择 ${chosen.size} 项", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        TextButton({ selected = (chosen + matches.map(LibraryItem::id)).toTypedArray() }, enabled = !busy && matches.isNotEmpty()) {
                            Text("全选结果")
                        }
                        TextButton({ assignTags(selected) }, enabled = !busy && chosen.isNotEmpty()) { Text("打标签") }
                        ActionIcon(Icons.Outlined.Close, "结束多选", !busy) { selecting = false; selected = emptyArray() }
                    } else {
                        Box(Modifier.weight(1f)) {
                            TextButton({ sortPicker = true }, enabled = !busy) {
                                Icon(Icons.AutoMirrored.Outlined.Sort, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(catalog.sort.label, maxLines = 1)
                            }
                            DropdownMenu(sortPicker, { sortPicker = false }) {
                                LibrarySort.entries.forEach { value ->
                                    DropdownMenuItem(text = { Text(value.label) }, onClick = {
                                        sortPicker = false
                                        model.sortLibrary(value)
                                    })
                                }
                            }
                        }
                        TextButton({ selecting = true }, enabled = !busy && matches.isNotEmpty()) { Text("批量打标") }
                    }
                }
            }
            val shortcuts = if (!selecting && !favorites && query.isBlank() && folder == null && include.isEmpty() && exclude.isEmpty())
                entries.filter(LibraryItem::favorite) else emptyList()
            if (shortcuts.isNotEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("收藏快捷入口", style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(shortcuts, key = LibraryItem::id) { favorite ->
                            AssistChip({ open(favorite) }, enabled = !busy, label = {
                                Text(favorite.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 128.dp))
                            }, leadingIcon = { Icon(if (favorite.card != null) Icons.Outlined.Person else Icons.Outlined.Groups, null) })
                        }
                    }
                }
            }
            items(folders, key = { (tag, _) -> "folder:${tag.id}" }) { (tag, count) ->
                OutlinedCard(onClick = { folderId = tag.id }, enabled = !busy && !selecting, modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { LibraryTagLabel(tag) },
                        supportingContent = { Text("$count 项") },
                        leadingContent = { Icon(if (tag.folder == TagFolder.CLOSED) Icons.Outlined.FolderOff else Icons.Outlined.FolderOpen, null) },
                        trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
                    )
                }
            }
            if (folders.isEmpty() && matches.isEmpty() && !busy) item {
                Text(if (entries.isEmpty() && groupsOnly) "还没有群组。" else "未找到匹配的项目。",
                    modifier = Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(matches, key = LibraryItem::id) { item ->
                LibraryResult(item, item.tags.mapNotNull(byTag::get).filterNot(LibraryTag::hidden), model, busy, selecting,
                    item.id in chosen) { open(item) }
            }
        }
    }
    if (filters) {
        LibraryFilterDialog(catalog.tags, include, exclude, busy, { filters = false },
            change = { id ->
                when (id) {
                    in include -> { included = included.filterNot { it == id }.toTypedArray(); excluded += id }
                    in exclude -> excluded = excluded.filterNot { it == id }.toTypedArray()
                    else -> included += id
                }
            }, clear = { included = emptyArray(); excluded = emptyArray() })
    }
}

@Composable
private fun LibraryResult(item: LibraryItem, tags: List<LibraryTag>, model: TavernViewModel, busy: Boolean,
                          selecting: Boolean, checked: Boolean, open: () -> Unit) {
    Card(onClick = open, enabled = !busy, modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (selecting) Checkbox(checked, { open() }, enabled = !busy)
            else if (item.card != null) Portrait(item.card, model.portrait(item.card), 54.dp)
            else Box(Modifier.size(54.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Groups, null) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item.detail.isNotBlank()) Text(item.detail, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, overflow = TextOverflow.Ellipsis)
                if (tags.isNotEmpty()) Text(tags.joinToString(" · ", transform = LibraryTag::name),
                    style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.primary)
            }
            if (!selecting) LibraryFavorite(item.favorite, busy) { model.toggleLibraryFavorite(item.id) }
        }
    }
}

@Composable
internal fun LibraryFavorite(favorite: Boolean, busy: Boolean, toggle: () -> Unit) {
    IconToggleButton(favorite, { toggle() }, enabled = !busy) {
        Icon(if (favorite) Icons.Outlined.Star else Icons.Outlined.StarOutline,
            if (favorite) "取消收藏" else "收藏",
            tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LibraryFilterDialog(tags: List<LibraryTag>, included: Set<String>, excluded: Set<String>, busy: Boolean,
                                close: () -> Unit, change: (String) -> Unit, clear: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = close, title = { Text("标签筛选") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("查找标签", query, { query = it }, enabled = !busy)
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    val found = tags.filter { it.name.contains(query.trim(), true) }
                    if (found.isEmpty()) item { Text("没有匹配的标签") }
                    items(found, key = LibraryTag::id) { tag ->
                        val state = when (tag.id) { in included -> "包含"; in excluded -> "排除"; else -> "不限" }
                        ListItem(headlineContent = { LibraryTagLabel(tag) }, trailingContent = { Text(state) },
                            modifier = Modifier.clickable(enabled = !busy, onClickLabel = "切换标签筛选") { change(tag.id) })
                    }
                }
            }
        }, confirmButton = { TextButton(close) { Text("完成") } },
        dismissButton = { TextButton(clear, enabled = !busy && (included.isNotEmpty() || excluded.isNotEmpty())) { Text("清除") } })
}

@Composable
internal fun AssignLibraryTags(
    catalog: LibraryPreferences, owners: Set<String>, busy: Boolean, close: () -> Unit,
    save: (Map<String, Boolean>, String) -> Unit,
) {
    var search by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var added by rememberSaveable { mutableStateOf(emptyArray<String>()) }
    var removed by rememberSaveable { mutableStateOf(emptyArray<String>()) }
    AlertDialog(onDismissRequest = { if (!busy) close() },
        title = { Text(if (owners.size == 1) "编辑标签" else "为 ${owners.size} 项打标签") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("添加新标签", name, { name = it }, enabled = !busy)
                if (catalog.tags.isNotEmpty()) {
                    Field("查找已有标签", search, { search = it }, enabled = !busy)
                    LazyColumn(Modifier.heightIn(max = 270.dp)) {
                        items(catalog.tags.filter { it.name.contains(search.trim(), true) }, key = LibraryTag::id) { tag ->
                            val used = owners.count { tag.id in catalog.assignments[it].orEmpty() }
                            val state = when {
                                tag.id in added -> ToggleableState.On
                                tag.id in removed -> ToggleableState.Off
                                used == 0 -> ToggleableState.Off
                                used == owners.size -> ToggleableState.On
                                else -> ToggleableState.Indeterminate
                            }
                            fun toggle() {
                                added = added.filterNot { it == tag.id }.toTypedArray()
                                removed = removed.filterNot { it == tag.id }.toTypedArray()
                                if (state == ToggleableState.On) removed += tag.id else added += tag.id
                            }
                            ListItem(headlineContent = { LibraryTagLabel(tag) }, leadingContent = {
                                TriStateCheckbox(state, onClick = { toggle() }, enabled = !busy)
                            }, modifier = Modifier.clickable(enabled = !busy, onClickLabel = "更改标签") { toggle() })
                        }
                    }
                }
            }
        }, confirmButton = {
            TextButton({ save(added.associateWith { true } + removed.associateWith { false }, name) },
                enabled = !busy && (name.isNotBlank() || added.isNotEmpty() || removed.isNotEmpty())) { Text("保存") }
        }, dismissButton = { TextButton(close, enabled = !busy) { Text("取消") } })
}

@Composable
internal fun LibraryTagManager(catalog: LibraryPreferences, model: TavernViewModel, busy: Boolean) {
    var edit by rememberSaveable { mutableStateOf<String?>(null) }
    var remove by rememberSaveable { mutableStateOf<String?>(null) }
    val restore = rememberLauncherForActivityResult(OpenDocument()) { uri -> uri?.let(model::importLibraryTags) }
    val backup = rememberLauncherForActivityResult(CreateDocument("application/json")) { uri -> uri?.let(model::exportLibraryTags) }
    val tag = if (edit == "") LibraryTag(id = rememberSaveable { newId() }) else catalog.tags.firstOrNull { it.id == edit }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxWidth(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                GroupToggle("显示标签文件夹", catalog.folders, !busy, model::showLibraryFolders)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton({ edit = "" }, enabled = !busy) { Text("新建") }
                    TextButton({ restore.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !busy) {
                        Text("导入备份")
                    }
                    TextButton({ backup.launch("tavern-tags.json") }, enabled = !busy) { Text("导出备份") }
                }
            }
            itemsIndexed(catalog.tags, key = { _, value -> value.id }) { index, value ->
                OutlinedCard(onClick = { edit = value.id }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LibraryTagLabel(value)
                            Text(value.folder.label, style = MaterialTheme.typography.labelMedium)
                        }
                        ActionIcon(Icons.Outlined.KeyboardArrowUp, "上移标签", !busy && index > 0) { model.moveLibraryTag(value.id, -1) }
                        ActionIcon(Icons.Outlined.KeyboardArrowDown, "下移标签", !busy && index < catalog.tags.lastIndex) {
                            model.moveLibraryTag(value.id, 1)
                        }
                        ActionIcon(Icons.Outlined.DeleteOutline, "删除标签", !busy) { remove = value.id }
                    }
                }
            }
        }
    }
    if (tag != null) key(tag.id) { EditLibraryTag(tag, busy, { edit = null }) { changed ->
        model.saveLibraryTag(changed) { edit = null }
    } }
    catalog.tags.firstOrNull { it.id == remove }?.let { value ->
        Confirm("删除标签", "将从角色和群组中移除「${value.name}」。", { remove = null }) {
            remove = null
            model.removeLibraryTag(value.id)
        }
    }
}

@Composable
private fun EditLibraryTag(tag: LibraryTag, busy: Boolean, close: () -> Unit, save: (LibraryTag) -> Unit) {
    var name by rememberSaveable { mutableStateOf(tag.name) }
    var folder by rememberSaveable { mutableStateOf(tag.folder) }
    var color by rememberSaveable { mutableStateOf(tag.color) }
    var textColor by rememberSaveable { mutableStateOf(tag.textColor) }
    var hidden by rememberSaveable { mutableStateOf(tag.hidden) }
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text(if (tag.name.isEmpty()) "新建标签" else "编辑标签") },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Field("名称", name, { name = it }, enabled = !busy) }
                item { SelectField("文件夹显示方式", folder, TagFolder.entries, TagFolder::label, !busy) { folder = it } }
                item { Field("背景色", color, { color = it }, enabled = !busy, placeholder = "#28664B") }
                item { Field("文字颜色", textColor, { textColor = it }, enabled = !busy, placeholder = "#FFFFFF") }
                item { GroupToggle("在角色或群组卡片上隐藏标签", hidden, !busy) { hidden = it } }
                if (name.isNotBlank()) item { LibraryTagLabel(tag.copy(name = name, color = color, textColor = textColor)) }
            }
        }, confirmButton = {
            TextButton({ save(tag.copy(name = name, folder = folder, color = color, textColor = textColor, hidden = hidden)) },
                enabled = !busy && name.isNotBlank()) { Text("保存") }
        }, dismissButton = { TextButton(close, enabled = !busy) { Text("取消") } })
}

@Composable
internal fun LibraryTagLabel(tag: LibraryTag) {
    val theme = MaterialTheme.colorScheme
    val color = libraryTagColor(tag.color)?.let(::Color)
    val fill = color?.compositeOver(theme.surface) ?: theme.secondaryContainer
    val text = libraryTagColor(tag.textColor)?.let(::Color) ?: when {
        color == null -> theme.onSecondaryContainer
        fill.luminance() > .18f -> Color.Black
        else -> Color.White
    }
    Surface(shape = RoundedCornerShape(8.dp), color = fill, contentColor = text) {
        Text(tag.name, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun LibraryDetailsTags(tags: List<LibraryTag>) {
    if (tags.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(tags, key = LibraryTag::id) { tag ->
            Box(Modifier.widthIn(max = 220.dp)) { LibraryTagLabel(tag) }
        }
    }
}
