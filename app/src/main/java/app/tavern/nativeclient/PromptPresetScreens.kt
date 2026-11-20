package app.tavern.nativeclient

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun PromptPresetLibrary(state: TavernState, model: TavernViewModel, dismiss: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<PromptPreset?>(null) }
    val context = LocalContext.current
    val active = state.presets.firstOrNull { it.id == selected }
    val open = active ?: selected?.takeIf { creating }?.let { remember(it) { PromptPreset(id = it, name = "") } }
    fun back() {
        if (state.busy) return
        if (open == null) dismiss() else { selected = null; creating = false }
    }
    BackHandler { back() }
    val import = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) model.importPromptPreset(uri, context.documentName(uri))
    }
    val export = rememberLauncherForActivityResult(CreateDocument("application/json")) { uri ->
        if (uri != null && active != null) model.exportPromptPreset(uri, active)
    }
    if (open != null) {
        key(open.id) {
            PromptPresetEditor(open, state.busy, ::back, export = active?.let { { export.launch("${safeFilename(it.name)}.json") } },
                remove = active?.let { { deleting = it } }) { preset ->
                model.savePromptPreset(preset) { creating = false; selected = null }
            }
        }
    } else {
        Form {
            TextButton(::back, enabled = !state.busy) { Text("返回设置") }
            FormHeading("提示词预设")
            SelectField("默认预设", state.presetFor(null)?.id, listOf<String?>(null) + state.presets.map(PromptPreset::id),
                { id -> state.presets.firstOrNull { it.id == id }?.name ?: "内置基础提示词" },
                !state.busy, model::setDefaultPromptPreset)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ creating = true; selected = newId() }, enabled = !state.busy) { Text("新建预设") }
                OutlinedButton({ import.launch(arrayOf("*/*")) }, enabled = !state.busy) { Text("导入 JSON") }
            }
            state.presets.forEach { preset ->
                Card(onClick = { selected = preset.id; creating = false }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    ListItem(headlineContent = { Text(preset.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text("${preset.prompts.count { it.included && it.enabled }} 条已启用提示") },
                        leadingContent = { Icon(Icons.Outlined.Tune, null) },
                        trailingContent = { Icon(Icons.Outlined.ChevronRight, null) })
                }
            }
        }
    }
    deleting?.let { preset ->
        Confirm("删除提示词预设？", "「${preset.name}」将从本机移除。", { deleting = null }) {
            model.deletePromptPreset(preset) { deleting = null; selected = null; creating = false }
        }
    }
}

@Composable
private fun PromptPresetEditor(
    preset: PromptPreset, busy: Boolean, back: () -> Unit, export: (() -> Unit)?, remove: (() -> Unit)?,
    save: (PromptPreset) -> Unit,
) {
    var draft by remember { mutableStateOf(preset) }
    var edited by rememberSaveable { mutableStateOf<String?>(null) }
    var utilities by rememberSaveable { mutableStateOf(false) }
    val editingId = edited
    if (editingId != null) {
        val row = draft.prompts.firstOrNull { it.id == editingId } ?: PresetPrompt(id = editingId)
        BackHandler { if (!busy) edited = null }
        key(editingId) {
            PromptEntryEditor(row, busy, { edited = null }, remove = if (row.section != null || row !in draft.prompts) null else ({
                draft = draft.copy(prompts = draft.prompts.filterNot { it.id == row.id })
                edited = null
            })) { changed ->
                val idx = draft.prompts.indexOfFirst { it.id == changed.id }
                val rows = draft.prompts.toMutableList().apply { if (idx < 0) add(changed) else set(idx, changed) }
                draft = draft.copy(prompts = rows)
                edited = null
            }
        }
        return
    }
    val ordered = draft.prompts.filter(PresetPrompt::included)
    val inactive = draft.prompts.filterNot(PresetPrompt::included)
    fun update(row: PresetPrompt) {
        draft = draft.copy(prompts = draft.prompts.map { if (it.id == row.id) row else it })
    }
    fun move(index: Int, offset: Int) {
        val moved = ordered.toMutableList().apply { add(index + offset, removeAt(index)) }
        draft = draft.copy(prompts = moved + inactive)
    }
    Form {
        TextButton(back, enabled = !busy) { Text("返回预设列表") }
        FormHeading(if (preset.name.isBlank()) "新建提示词预设" else "编辑提示词预设")
        Field("名称", draft.name, { draft = draft.copy(name = it) }, enabled = !busy)
        val validSampling = draft.sampling.all { (key, value) -> value.isBlank() || key.number(value) != null }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button({ save(draft) }, enabled = !busy && draft.name.isNotBlank() && validSampling) { Text("保存预设") }
            ActionIcon(Icons.Outlined.Add, "添加提示词", !busy) { edited = newId() }
            if (export != null) ActionIcon(Icons.Outlined.FileUpload, "导出已保存的预设", !busy, export)
            if (remove != null) ActionIcon(Icons.Outlined.DeleteOutline, "删除预设", !busy, remove)
        }
        FormHeading("发送顺序")
        ordered.forEachIndexed { i, prompt ->
            PresetPromptRow(prompt, !busy, edit = { edited = prompt.id }, toggle = { update(prompt.copy(enabled = it)) },
                up = if (i > 0) ({ move(i, -1) }) else null,
                down = if (i < ordered.lastIndex) ({ move(i, 1) }) else null)
        }
        if (inactive.isNotEmpty()) {
            FormHeading("未加入发送顺序")
            inactive.forEach { prompt ->
                PresetPromptRow(prompt, !busy, edit = { edited = prompt.id }, append = { update(prompt.copy(included = true)) })
            }
        }
        OutlinedButton({ utilities = !utilities }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (utilities) "收起生成参数和模板" else "生成参数和模板")
        }
        if (utilities) {
            PresetSampling.entries.forEach { option ->
                val value = draft.sampling[option].orEmpty()
                val integer = option == PresetSampling.MAX_TOKENS || option == PresetSampling.SEED
                OutlinedTextField(value, {
                    draft = draft.copy(sampling = draft.sampling + (option to it))
                }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
                    label = { Text(option.label) }, isError = value.isNotBlank() && option.number(value) == null,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType =
                        if (integer) KeyboardType.Number else KeyboardType.Decimal))
            }
            GroupToggle("续写时用助手消息预填充", draft.continuePrefill, !busy) { draft = draft.copy(continuePrefill = it) }
            GroupToggle("合并相邻系统消息", draft.squashSystem, !busy) { draft = draft.copy(squashSystem = it) }
            PresetUtility.entries.forEach { utility ->
                Field(utility.label, draft.text(utility), { draft = draft.copy(utilities = draft.utilities + (utility to it)) },
                    lines = 2, enabled = !busy)
            }
            Button({ save(draft) }, enabled = !busy && draft.name.isNotBlank() && validSampling, modifier = Modifier.fillMaxWidth()) {
                Text("保存预设")
            }
        }
    }
}

@Composable
private fun PresetPromptRow(
    prompt: PresetPrompt, enabled: Boolean, edit: () -> Unit, toggle: ((Boolean) -> Unit)? = null,
    up: (() -> Unit)? = null, down: (() -> Unit)? = null, append: (() -> Unit)? = null,
) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (toggle != null) Checkbox(prompt.enabled, onCheckedChange = toggle, enabled = enabled)
            TextButton(edit, enabled = enabled, modifier = Modifier.weight(1f)) {
                Column(Modifier.fillMaxWidth()) {
                    Text(prompt.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (prompt.atDepth) "${prompt.role.label} · 深度 ${prompt.depth}" else prompt.role.label,
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (append != null) ActionIcon(Icons.Outlined.Add, "加入 ${prompt.name}", enabled, append)
            if (up != null) ActionIcon(Icons.Outlined.ArrowUpward, "上移 ${prompt.name}", enabled, up)
            if (down != null) ActionIcon(Icons.Outlined.ArrowDownward, "下移 ${prompt.name}", enabled, down)
        }
    }
}

@Composable
private fun PromptEntryEditor(
    prompt: PresetPrompt, busy: Boolean, back: () -> Unit, remove: (() -> Unit)?, save: (PresetPrompt) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(prompt.name) }
    var text by rememberSaveable { mutableStateOf(prompt.text) }
    var role by rememberSaveable { mutableStateOf(prompt.role) }
    var included by rememberSaveable { mutableStateOf(prompt.included) }
    var enabled by rememberSaveable { mutableStateOf(prompt.enabled) }
    var atDepth by rememberSaveable { mutableStateOf(prompt.atDepth) }
    var depth by rememberSaveable { mutableStateOf(prompt.depth.toString()) }
    var priority by rememberSaveable { mutableStateOf(prompt.priority.toString()) }
    var triggers by rememberSaveable { mutableStateOf<List<String>>(prompt.triggers) }
    var forbid by rememberSaveable { mutableStateOf(prompt.forbidOverrides) }
    val parsedDepth = depth.toIntOrNull()?.takeIf { it in 0..10_000 }
    val parsedPriority = priority.toIntOrNull()
    val canInject = prompt.section !in setOf(PromptSection.HISTORY, PromptSection.EXAMPLES)
    val marker = prompt.section?.marker == true
    Form {
        TextButton(back, enabled = !busy) { Text("返回预设") }
        FormHeading(if (prompt.name.isBlank()) "添加提示词" else "编辑提示词")
        Field("名称", name, { name = it }, enabled = !busy)
        if (marker) Text("从当前对话读取${prompt.section?.label}。", style = MaterialTheme.typography.bodyMedium)
        else Field("正文", text, { text = it }, lines = 4, enabled = !busy)
        GroupToggle("加入发送顺序", included, !busy) { included = it }
        GroupToggle("启用", enabled, !busy && included) { enabled = it }
        if (canInject) {
            SelectField("消息角色", role, PromptRole.entries, PromptRole::label, !busy) { role = it }
            GroupToggle("按聊天历史深度插入", atDepth, !busy) { atDepth = it }
            if (atDepth) {
                Field("注入深度（0 为历史末尾）", depth, { depth = it }, enabled = !busy, type = KeyboardType.Number)
                Field("同深度优先级", priority, { priority = it }, enabled = !busy, type = KeyboardType.Number)
            }
        }
        if (prompt.section == PromptSection.MAIN || prompt.section == PromptSection.AFTER) {
            GroupToggle("锁定正文，以预设为准", forbid, !busy) { forbid = it }
        }
        FormHeading("生成触发条件", "全部清空时适用于所有生成类型。")
        val triggerLabels = listOf("normal" to "普通回复", "regenerate" to "重新生成", "swipe" to "备选回复",
            "continue" to "续写", "impersonate" to "代写", "quiet" to "后台生成")
        triggerLabels.forEach { (trigger, label) ->
            GroupToggle(label, trigger in triggers, !busy) { use ->
                triggers = if (use) triggers + trigger else triggers - trigger
            }
        }
        Button(onClick = {
            save(prompt.copy(name = name.trim(), text = text, role = role, included = included, enabled = enabled,
                atDepth = canInject && atDepth, depth = parsedDepth ?: prompt.depth,
                priority = parsedPriority ?: prompt.priority, triggers = triggers, forbidOverrides = forbid))
        }, enabled = !busy && name.isNotBlank() && (!atDepth || (parsedDepth != null && parsedPriority != null)),
            modifier = Modifier.fillMaxWidth()) { Text("保存提示词") }
        if (remove != null) TextButton(remove, enabled = !busy) { Text("移除此提示词") }
    }
}

@Composable
internal fun ChatPromptPresetPicker(state: TavernState, chat: Chat, dismiss: () -> Unit, save: (String?) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(chat.options.promptPresetId) }
    val options = listOf<String?>(null, "") + state.presets.map(PromptPreset::id)
    val selection = selected.takeIf { it in options }
    val automatic = state.presetFor(chat.copy(options = chat.options.copy(promptPresetId = null)))?.name
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("对话提示词预设") },
        text = {
            SelectField("使用预设", selection, options, { id ->
                when (id) {
                    null -> "自动选择 · ${automatic ?: "内置基础提示词"}"
                    "" -> "内置基础提示词"
                    else -> state.presets.firstOrNull { it.id == id }?.name.orEmpty()
                }
            }, !state.busy) { selected = it }
        },
        confirmButton = { TextButton({ save(selection) }, enabled = !state.busy) { Text("保存") } },
        dismissButton = { TextButton(dismiss, enabled = !state.busy) { Text("取消") } },
    )
}

@Composable
internal fun PromptInspector(capture: PromptCapture, dismiss: () -> Unit) {
    val entries = remember(capture) { capture.messages.objects() }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copyFailed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("最近请求的提示词") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text(capture.model, style = MaterialTheme.typography.labelLarge) }
                capture.loreBudget?.let { budget ->
                    item {
                        Text("世界书额度 ${budget.used} / ${budget.limit} Token · ${budget.counter}",
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (copyFailed) item { Text("无法复制完整提示词", color = MaterialTheme.colorScheme.error) }
                items(entries) { row ->
                    val role = PromptRole.entries.firstOrNull { it.wire == row.string("role") }
                    Column {
                        Text(role?.label ?: row.string("role"), style = MaterialTheme.typography.titleSmall)
                        val text = row.string("content")
                        SelectionContainer {
                            Text(if (text.length <= 16_000) text else text.take(16_000) + "\n…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton({
                scope.launch {
                    copyFailed = runCatching {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("提示词 JSON", capture.messages.toString(2))))
                    }.isFailure
                }
            }) { Text("复制 JSON") }
        },
        confirmButton = { TextButton(dismiss) { Text("关闭") } },
    )
}
