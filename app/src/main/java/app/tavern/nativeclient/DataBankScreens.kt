package app.tavern.nativeclient

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private data class DataOwnerChoice(val owner: DataOwner, val label: String) {
    val key get() = "${owner.scope.name}:${owner.id.orEmpty()}"
}

private fun dataBankOwners(state: TavernState, fromChat: Boolean): List<DataOwnerChoice> = buildList {
    add(DataOwnerChoice(DataOwner(DataScope.GLOBAL), "所有聊天 · 全局"))
    if (fromChat) state.chat?.let { add(DataOwnerChoice(DataOwner(DataScope.CHAT, it.id), "当前对话 · ${it.title}")) }
    val cards = if (fromChat) state.group?.members(state.cards) ?: listOfNotNull(state.card) else state.cards
    cards.forEach { add(DataOwnerChoice(DataOwner(DataScope.CHARACTER, it.id), "角色 · ${it.name}")) }
}

@Composable
internal fun DataBankDialog(state: TavernState, model: TavernViewModel, close: () -> Unit, configure: () -> Unit) {
    Dialog(onDismissRequest = { if (!state.busy) close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            DataBankPanel(state, model, fromChat = true, close = close, configure = configure)
        }
    }
}

@Composable
internal fun DataBankPanel(state: TavernState, model: TavernViewModel, fromChat: Boolean,
                           close: () -> Unit, configure: () -> Unit) {
    val choices = dataBankOwners(state, fromChat)
    var selection by rememberSaveable(fromChat) { mutableStateOf(choices.firstOrNull { it.owner.scope == DataScope.CHAT }?.key ?: choices.first().key) }
    val choice = choices.firstOrNull { it.key == selection } ?: choices.first()
    val owner = choice.owner
    val documents = state.dataDocuments.filter { it.owner == owner }
    val enabled = !state.busy
    var draft by rememberSaveable { mutableStateOf<String?>(null) }
    var web by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) model.importDataDocuments(owner, uris)
    }
    val exportPicker = rememberLauncherForActivityResult(CreateDocument("text/plain")) { uri ->
        val document = state.dataDocuments.firstOrNull { it.id == exportId }
        if (uri != null && document != null) model.exportDataDocument(document, uri)
        exportId = null
    }
    val opened = state.openDocument
    LaunchedEffect(model) { model.loadDataBank() }
    DisposableEffect(model) { onDispose { model.closeDataDocument() } }
    BackHandler(enabled = enabled) { close() }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(close, enabled = enabled) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                Text("资料库", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton({ settings = true }, enabled = enabled) { Icon(Icons.Outlined.Settings, "资料检索设置") }
            }
        }
        item {
            SelectField("使用范围", choice, choices, DataOwnerChoice::label, enabled) { selection = it.key }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ draft = newId() }, modifier = Modifier.weight(1f), enabled = enabled) { Text("新建笔记") }
                Button({ picker.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f), enabled = enabled) { Text("导入文件") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ web = true }, modifier = Modifier.weight(1f), enabled = enabled) { Text("导入网页") }
                TextButton({ model.indexDataDocuments(owner) }, modifier = Modifier.weight(1f),
                    enabled = enabled && documents.any(DataDocument::enabled)) { Text("建立索引") }
            }
            state.utility?.let { label ->
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, modifier = Modifier.weight(1f))
                    TextButton(model::stop) { Text("停止") }
                }
            }
            if (fromChat) state.notice?.takeIf(String::isNotBlank)?.let { text ->
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (documents.isEmpty()) item {
            Text("这个范围还没有资料。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(documents, key = DataDocument::id) { document ->
            DataBankRow(document, enabled, open = { model.openDataDocument(document.id) }, toggle = {
                model.saveDataDocument(document.copy(enabled = it), null) {}
            }, export = {
                exportId = document.id
                exportPicker.launch("${safeFilename(document.title.substringBeforeLast('.'))}.txt")
            }, purge = { model.clearDataDocumentIndex(document.id) }, remove = { deleting = document.id })
        }
    }
    if (web) WebDocumentDialog(enabled, close = { web = false }) { url ->
        if (model.importDataUrl(owner, url)) web = false
    }
    if (settings) FileVectorDialog(state, model, close = { settings = false }, configure = { settings = false; configure() })
    val newDocument = draft?.let { DataDocument(it, "", owner) }
    val editor = opened ?: newDocument?.let { DocumentText(it, "") }
    editor?.let { value ->
        DataDocumentEditor(value, choices, enabled, close = { model.closeDataDocument(); draft = null }) { document, text ->
            model.saveDataDocument(document, text) { draft = null; selection = DataOwnerChoice(document.owner, "").key }
        }
    }
    deleting?.let { id ->
        val document = state.dataDocuments.firstOrNull { it.id == id }
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除资料") },
            text = { Text(document?.title.orEmpty()) }, dismissButton = { TextButton({ deleting = null }) { Text("取消") } },
            confirmButton = { Button({ deleting = null; model.deleteDataDocument(id) }, enabled = enabled) { Text("删除") } })
    }
}

@Composable
private fun DataBankRow(document: DataDocument, enabled: Boolean, open: () -> Unit, toggle: (Boolean) -> Unit,
                        export: () -> Unit, purge: () -> Unit, remove: () -> Unit) {
    var actions by remember { mutableStateOf(false) }
    Card(onClick = open, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.Article, null)
            Column(Modifier.weight(1f)) {
                Text(document.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${document.size} 字符${if (document.enabled) "" else " · 已禁用"}", style = MaterialTheme.typography.bodySmall)
            }
            Box {
                TextButton({ actions = true }, enabled = enabled) { Text("操作") }
                DropdownMenu(actions, onDismissRequest = { actions = false }) {
                    DropdownMenuItem(text = { Text(if (document.enabled) "禁用" else "启用") }, onClick = { actions = false; toggle(!document.enabled) })
                    DropdownMenuItem(text = { Text("导出文本") }, onClick = { actions = false; export() })
                    DropdownMenuItem(text = { Text("清除索引") }, onClick = { actions = false; purge() })
                    DropdownMenuItem(text = { Text("删除") }, onClick = { actions = false; remove() })
                }
            }
        }
    }
}

@Composable
private fun DataDocumentEditor(value: DocumentText, choices: List<DataOwnerChoice>, enabled: Boolean,
                               close: () -> Unit, save: (DataDocument, String?) -> Unit) {
    val source = value.document
    var title by rememberSaveable(source.id) { mutableStateOf(source.title) }
    var text by remember(source.id) { mutableStateOf(value.text) }
    var ownerKey by rememberSaveable(source.id) { mutableStateOf(DataOwnerChoice(source.owner, "").key) }
    val choice = choices.firstOrNull { it.key == ownerKey } ?: choices.first()
    var active by rememberSaveable(source.id) { mutableStateOf(source.enabled) }
    AlertDialog(onDismissRequest = { if (enabled) close() }, title = { Text(if (source.title.isBlank()) "新建笔记" else "编辑资料") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("资料名称", title, { title = it }, enabled = enabled)
            SelectField("使用范围", choice, choices, DataOwnerChoice::label, enabled) { ownerKey = it.key }
            GroupToggle("启用资料", active, enabled) { active = it }
            if (source.source.isNotBlank()) Text(source.source, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (value.truncated) {
                Text("篇幅较长。可在资料列表导出全文。", style = MaterialTheme.typography.bodySmall)
                Text(value.text.take(3500), style = MaterialTheme.typography.bodyMedium)
            } else {
                Field("正文", text, { if (it.length <= DOCUMENT_EDITOR_LIMIT) text = it }, lines = 6, enabled = enabled)
            }
        }
    }, dismissButton = { TextButton(close, enabled = enabled) { Text("取消") } }, confirmButton = {
        Button({ save(source.copy(title = title, owner = choice.owner, enabled = active), if (value.truncated) null else text) },
            enabled = enabled && title.isNotBlank() && (value.truncated || text.isNotBlank())) { Text("保存") }
    })
}

@Composable
private fun WebDocumentDialog(enabled: Boolean, close: () -> Unit, import: (String) -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = close, title = { Text("导入网页") }, text = {
        Field("网页地址", url, { url = it }, placeholder = "https://example.com/article", type = KeyboardType.Uri, enabled = enabled)
    }, dismissButton = { TextButton(close) { Text("取消") } }, confirmButton = {
        Button({ import(url.trim()) }, enabled = enabled && url.isNotBlank()) { Text("导入") }
    })
}

@Composable
private fun FileVectorDialog(state: TavernState, model: TavernViewModel, close: () -> Unit, configure: () -> Unit) {
    val saved = state.prefs.vectors.files
    var enabledFiles by rememberSaveable(saved.enabled) { mutableStateOf(saved.enabled) }
    var threshold by rememberSaveable(saved.thresholdKb) { mutableStateOf(saved.thresholdKb.toString()) }
    var chunk by rememberSaveable(saved.chunkLength) { mutableStateOf(saved.chunkLength.toString()) }
    var overlap by rememberSaveable(saved.overlap) { mutableStateOf(saved.overlap.toString()) }
    var limit by rememberSaveable(saved.limit) { mutableStateOf(saved.limit.toString()) }
    var position by rememberSaveable(saved.position) { mutableStateOf(saved.position) }
    var role by rememberSaveable(saved.role) { mutableStateOf(saved.role) }
    var depth by rememberSaveable(saved.depth) { mutableStateOf(saved.depth.toString()) }
    var template by rememberSaveable(saved.template) { mutableStateOf(saved.template) }
    var lore by rememberSaveable(saved.scanLore) { mutableStateOf(saved.scanLore) }
    val enabled = !state.busy
    val sourceReady = state.vectorSource() != null
    val valid = threshold.toIntOrNull() in 0..8 && chunk.toIntOrNull() in 80..1600 && overlap.toIntOrNull() in 0..50 &&
        limit.toIntOrNull() in 1..20 && depth.toIntOrNull() in 0..80
    AlertDialog(onDismissRequest = { if (enabled) close() }, title = { Text("资料检索设置") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GroupToggle("在模型请求中检索资料库", enabledFiles, enabled) { enabledFiles = it }
            if (!sourceReady) TextButton(configure, enabled = enabled) { Text("打开设置配置向量服务") }
            Field("超过多大时分段（0–8 KB）", threshold, { threshold = it }, type = KeyboardType.Number, enabled = enabled)
            Field("片段长度（80–1600 字符）", chunk, { chunk = it }, type = KeyboardType.Number, enabled = enabled)
            Field("相邻片段重叠（0–50%）", overlap, { overlap = it }, type = KeyboardType.Number, enabled = enabled)
            Field("最多召回片段数（1–20）", limit, { limit = it }, type = KeyboardType.Number, enabled = enabled)
            SelectField("注入位置", position, MemoryPosition.entries, MemoryPosition::label, enabled) { position = it }
            if (position == MemoryPosition.DEPTH) {
                SelectField("注入角色", role, PromptRole.entries, PromptRole::label, enabled) { role = it }
                Field("注入深度（0–80）", depth, { depth = it }, type = KeyboardType.Number, enabled = enabled)
            }
            Field("注入模板（使用 {{text}}）", template, { template = it }, lines = 2, enabled = enabled)
            GroupToggle("用召回内容参与世界书扫描", lore, enabled) { lore = it }
        }
    }, dismissButton = { TextButton(close, enabled = enabled) { Text("关闭") } }, confirmButton = {
        Button({ model.saveVectorPreferences(state.prefs.vectors.copy(files = FileVectorSettings(enabledFiles, threshold.toInt(),
            chunk.toInt(), overlap.toInt(), limit.toInt(), position, role, depth.toInt(), template, lore)), close) },
            enabled = enabled && valid && (!enabledFiles || sourceReady)) { Text("保存") }
    })
}
