package app.tavern.nativeclient

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun QuickReplyLibrary(state: TavernState, model: TavernViewModel, dismiss: () -> Unit) {
    val config = state.prefs.quickReplies
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<QuickReplySet?>(null) }
    val saved = config.sets.firstOrNull { it.id == selected }
    val opened = saved ?: selected?.takeIf { adding }?.let { QuickReplySet(id = it) }
    fun back() {
        if (state.busy) return
        if (opened == null) dismiss() else { selected = null; adding = false }
    }
    BackHandler { back() }
    val picker = rememberLauncherForActivityResult(OpenDocument()) { uri -> if (uri != null) model.importQuickReplySet(uri) }
    val export = rememberLauncherForActivityResult(CreateDocument("application/json")) { uri ->
        if (uri != null && saved != null) model.exportQuickReplySet(uri, saved)
    }
    if (opened != null) {
        key(opened.id) {
            QuickSetEditor(opened, state.busy, ::back, saved?.let { { export.launch("${safeFilename(it.name)}.json") } }) {
                model.saveQuickReplySet(it) { selected = null; adding = false }
            }
        }
    } else Form {
        TextButton(::back, enabled = !state.busy) { Text("返回设置") }
        FormHeading("快捷回复")
        GroupToggle("启用快捷回复", config.enabled, !state.busy, model::enableQuickReplies)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ adding = true; selected = newId() }, enabled = !state.busy) { Text("新建回复集") }
            OutlinedButton({ picker.launch(arrayOf("*/*")) }, enabled = !state.busy) { Text("导入 JSON") }
        }
        config.sets.forEach { set ->
            Card({ selected = set.id; adding = false }, enabled = !state.busy) {
                ListItem(headlineContent = { Text(set.name) },
                    supportingContent = { Text("${set.items.size} 条快捷回复") },
                    leadingContent = {
                        Checkbox(set.id in config.active, { model.activateQuickReplySet(set.id, it) }, enabled = !state.busy)
                    },
                    trailingContent = { ActionIcon(Icons.Outlined.DeleteOutline, "删除 ${set.name}", !state.busy) { deleting = set } })
            }
        }
    }
    deleting?.let { set ->
        Confirm("删除快捷回复集？", "「${set.name}」及其中的回复将被删除。", { deleting = null }) {
            model.removeQuickReplySet(set) { deleting = null }
        }
    }
}

@Composable
private fun QuickSetEditor(set: QuickReplySet, busy: Boolean, dismiss: () -> Unit,
                           export: (() -> Unit)?, save: (QuickReplySet) -> Unit) {
    var draft by remember { mutableStateOf(set) }
    var itemId by rememberSaveable { mutableStateOf<Int?>(null) }
    fun back() { if (!busy) { if (itemId == null) dismiss() else itemId = null } }
    BackHandler(enabled = itemId != null) { back() }
    val selected = itemId
    if (selected != null) {
        val item = draft.items.firstOrNull { it.id == selected } ?: QuickReply(selected)
        key(selected) {
            QuickItemEditor(item, ::back, {
                draft = draft.copy(items = draft.items.filterNot { it.id == selected })
                itemId = null
            }) { updating ->
                val rows = draft.items.toMutableList()
                val index = rows.indexOfFirst { it.id == selected }
                if (index < 0) rows.add(updating) else rows[index] = updating
                draft = draft.copy(items = rows)
                itemId = null
            }
        }
        return
    }
    Form {
        TextButton(::back, enabled = !busy) { Text("返回回复集") }
        FormHeading("编辑快捷回复集")
        Field("名称", draft.name, { draft = draft.copy(name = it) }, enabled = !busy)
        GroupToggle("只填入输入框", draft.disableSend, !busy) { draft = draft.copy(disableSend = it) }
        GroupToggle("与当前草稿合并", draft.injectInput, !busy) { draft = draft.copy(injectInput = it) }
        if (draft.injectInput) GroupToggle("把快捷回复放在草稿前面", draft.placeBeforeInput, !busy) {
            draft = draft.copy(placeBeforeInput = it)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ save(draft) }, enabled = !busy && draft.name.isNotBlank()) { Text("保存回复集") }
            ActionIcon(Icons.Outlined.Add, "添加快捷回复", !busy) { itemId = (draft.items.maxOfOrNull(QuickReply::id) ?: 0) + 1 }
            if (export != null) ActionIcon(Icons.Outlined.FileUpload, "导出已保存的回复集", !busy, export)
        }
        draft.items.forEachIndexed { index, item ->
            fun move(offset: Int) {
                draft = draft.copy(items = draft.items.toMutableList().apply { add(index + offset, removeAt(index)) })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton({ itemId = item.id }, modifier = Modifier.weight(1f), enabled = !busy) { Text(item.label) }
                ActionIcon(Icons.Outlined.ArrowUpward, "上移 ${item.label}", !busy && index > 0) { move(-1) }
                ActionIcon(Icons.Outlined.ArrowDownward, "下移 ${item.label}", !busy && index < draft.items.lastIndex) { move(1) }
            }
        }
    }
}

@Composable
private fun QuickItemEditor(item: QuickReply, back: () -> Unit, remove: () -> Unit, save: (QuickReply) -> Unit) {
    var label by rememberSaveable { mutableStateOf(item.label) }
    var text by rememberSaveable { mutableStateOf(item.message) }
    var title by rememberSaveable { mutableStateOf(item.title) }
    var hidden by rememberSaveable { mutableStateOf(item.hidden) }
    var preventNested by rememberSaveable { mutableStateOf(item.preventAutoExecute) }
    var automationId by rememberSaveable { mutableStateOf(item.automationId) }
    var events by rememberSaveable { mutableStateOf<List<String>>(item.events.map(QuickEvent::name)) }
    Form {
        TextButton(back) { Text("返回回复集") }
        FormHeading("编辑快捷回复")
        Field("按钮文字", label, { label = it })
        Field("消息或 STscript", text, { text = it }, lines = 5)
        Field("说明（可选）", title, { title = it })
        GroupToggle("隐藏按钮", hidden) { hidden = it }
        FormHeading("自动执行")
        QuickEvent.entries.forEach { event ->
            GroupToggle(event.label, event.name in events) { events = if (it) events + event.name else events - event.name }
        }
        Field("世界书自动化 ID（可选）", automationId, { automationId = it })
        GroupToggle("阻止触发其他自动快捷回复", preventNested) { preventNested = it }
        Button({ save(item.copy(label = label.trim(), message = text, hidden = hidden, title = title,
            preventAutoExecute = preventNested, automationId = automationId.trim(),
            events = QuickEvent.entries.filter { it.name in events }.toSet())) },
            enabled = label.isNotBlank() && text.isNotBlank()) { Text("保存快捷回复") }
        if (item.label.isNotBlank()) TextButton(remove) { Text("从回复集移除") }
    }
}

@Composable
internal fun ChatQuickReplySets(state: TavernState, model: TavernViewModel, dismiss: () -> Unit) {
    val config = state.prefs.quickReplies
    val ids = state.chat?.options?.quickReplySets.orEmpty()
    AlertDialog(onDismissRequest = dismiss, title = { Text("此对话的快捷回复集") }, text = {
        LazyColumn(Modifier.heightIn(max = 380.dp)) {
            items(config.sets, key = QuickReplySet::id) { set ->
                val selected = set.id in ids || set.name in state.chat?.options?.quickReplyNames.orEmpty()
                GroupToggle(set.name, selected, !state.busy) { model.activateQuickReplySet(set.id, it, forChat = true) }
            }
        }
    }, confirmButton = { TextButton(dismiss) { Text("关闭") } })
}

@Composable
internal fun ScriptQuestion(dialog: ScriptDialog, resolve: (String?) -> Unit) {
    var input by rememberSaveable(dialog.id) { mutableStateOf(dialog.initial) }
    AlertDialog(onDismissRequest = { resolve(null) }, title = { Text(dialog.title) }, text = {
        val body = Modifier.heightIn(max = 480.dp)
        val popup = dialog.kind == "popup"
        Column(if (popup) body.verticalScroll(rememberScrollState()) else body) {
            if (dialog.text.isNotBlank()) {
                if (popup) SelectionContainer { Text(dialog.text) } else Text(dialog.text)
            }
            if (dialog.kind == "input") Field("输入", input, { input = it })
            if (dialog.kind == "buttons") LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(dialog.buttons) { button -> TextButton({ resolve(button) }) { Text(button) } }
            }
        }
    }, dismissButton = { if (dialog.kind != "popup") TextButton({ resolve(null) }) { Text("取消") } },
        confirmButton = {
            if (dialog.kind != "buttons") TextButton({ resolve(if (dialog.kind == "input") input else dialog.text) }) {
                Text(if (dialog.kind == "confirm-delete") "删除" else "确定")
            }
        })
}
