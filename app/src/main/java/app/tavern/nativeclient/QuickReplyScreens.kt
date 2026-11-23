package app.tavern.nativeclient

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tavern.nativeclient.script.DebugAction
import app.tavern.nativeclient.script.ScriptDebugSnapshot
import app.tavern.nativeclient.script.StScriptTokenKind
import app.tavern.nativeclient.script.tokenizeStScript

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
            QuickSetEditor(
                set = opened,
                busy = state.busy,
                dismiss = ::back,
                export = saved?.let { { export.launch("${safeFilename(it.name)}.json") } },
                debugItem = { set, item -> model.debugQuickReply(set, item) },
            ) {
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
private fun QuickSetEditor(
    set: QuickReplySet,
    busy: Boolean,
    dismiss: () -> Unit,
    export: (() -> Unit)?,
    debugItem: (QuickReplySet, QuickReply) -> Unit,
    save: (QuickReplySet) -> Unit,
) {
    var draft by remember { mutableStateOf(set) }
    var itemId by rememberSaveable { mutableStateOf<Int?>(null) }
    fun back() { if (!busy) { if (itemId == null) dismiss() else itemId = null } }
    BackHandler(enabled = itemId != null) { back() }
    val selected = itemId
    if (selected != null) {
        val item = draft.items.firstOrNull { it.id == selected } ?: QuickReply(selected)
        key(selected) {
            QuickItemEditor(
                item = item,
                busy = busy,
                back = ::back,
                debug = { candidate -> debugItem(draft, candidate) },
                remove = {
                    draft = draft.copy(items = draft.items.filterNot { it.id == selected })
                    itemId = null
                },
            ) { updating ->
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
                if (item.message.trimStart().startsWith('/')) {
                    ActionIcon(Icons.Outlined.BugReport, "单步调试 ${item.label}", !busy) { debugItem(draft, item) }
                }
                ActionIcon(Icons.Outlined.ArrowUpward, "上移 ${item.label}", !busy && index > 0) { move(-1) }
                ActionIcon(Icons.Outlined.ArrowDownward, "下移 ${item.label}", !busy && index < draft.items.lastIndex) { move(1) }
            }
        }
    }
}

@Composable
internal fun rememberStScriptHighlighter(): VisualTransformation {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.30f
    return remember(scheme, dark) {
        val commandColor = if (dark) Color(0xFF7DD3FC) else Color(0xFF0369A1)
        val pipeColor = if (dark) Color(0xFFF472B6) else Color(0xFFBE185D)
        val closureColor = if (dark) Color(0xFFC084FC) else Color(0xFF7E22CE)
        val macroColor = if (dark) Color(0xFFFBBF24) else Color(0xFFB45309)
        val namedArgColor = if (dark) Color(0xFF86EFAC) else Color(0xFF15803D)
        val stringColor = if (dark) Color(0xFFFDA4AF) else Color(0xFF9F1239)
        val commentColor = scheme.onSurfaceVariant
        VisualTransformation { raw ->
            val annotated = highlightStScript(
                source = raw.text,
                commandColor = commandColor,
                pipeColor = pipeColor,
                closureColor = closureColor,
                macroColor = macroColor,
                namedArgColor = namedArgColor,
                stringColor = stringColor,
                commentColor = commentColor,
            )
            TransformedText(annotated, OffsetMapping.Identity)
        }
    }
}

internal fun highlightStScript(
    source: String,
    commandColor: Color,
    pipeColor: Color,
    closureColor: Color,
    macroColor: Color,
    namedArgColor: Color,
    stringColor: Color,
    commentColor: Color,
): AnnotatedString = buildAnnotatedString {
    append(source)
    for (token in tokenizeStScript(source)) {
        val style = when (token.kind) {
            StScriptTokenKind.COMMAND -> SpanStyle(color = commandColor, fontWeight = FontWeight.Bold)
            StScriptTokenKind.PIPE -> SpanStyle(color = pipeColor, fontWeight = FontWeight.Bold)
            StScriptTokenKind.CLOSURE -> SpanStyle(color = closureColor, fontWeight = FontWeight.Bold)
            StScriptTokenKind.MACRO -> SpanStyle(color = macroColor, fontWeight = FontWeight.SemiBold)
            StScriptTokenKind.NAMED_ARG -> SpanStyle(color = namedArgColor)
            StScriptTokenKind.STRING -> SpanStyle(color = stringColor)
            StScriptTokenKind.COMMENT -> SpanStyle(color = commentColor, fontStyle = FontStyle.Italic)
        }
        addStyle(style, token.start, token.end)
    }
}

@Composable
private fun QuickItemEditor(
    item: QuickReply,
    busy: Boolean,
    back: () -> Unit,
    debug: (QuickReply) -> Unit,
    remove: () -> Unit,
    save: (QuickReply) -> Unit,
) {
    var label by rememberSaveable { mutableStateOf(item.label) }
    var text by rememberSaveable { mutableStateOf(item.message) }
    var title by rememberSaveable { mutableStateOf(item.title) }
    var hidden by rememberSaveable { mutableStateOf(item.hidden) }
    var preventNested by rememberSaveable { mutableStateOf(item.preventAutoExecute) }
    var automationId by rememberSaveable { mutableStateOf(item.automationId) }
    var events by rememberSaveable { mutableStateOf<List<String>>(item.events.map(QuickEvent::name)) }
    val syntaxHighlighter = rememberStScriptHighlighter()
    fun buildDraft() = item.copy(
        label = label.trim(),
        message = text,
        hidden = hidden,
        title = title,
        preventAutoExecute = preventNested,
        automationId = automationId.trim(),
        events = QuickEvent.entries.filter { it.name in events }.toSet(),
    )
    Form {
        TextButton(back) { Text("返回回复集") }
        FormHeading("编辑快捷回复")
        Field("按钮文字", label, { label = it })
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            minLines = 5,
            maxLines = 12,
            label = { Text("消息或 STscript（支持语法高亮）") },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            visualTransformation = syntaxHighlighter,
        )
        Text(
            "高亮显示：/命令 · | 管道 · {: :} 闭包 · {{宏}} · key= 参数",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Field("说明（可选）", title, { title = it })
        GroupToggle("隐藏按钮", hidden) { hidden = it }
        FormHeading("自动执行")
        QuickEvent.entries.forEach { event ->
            GroupToggle(event.label, event.name in events) { events = if (it) events + event.name else events - event.name }
        }
        Field("世界书自动化 ID（可选）", automationId, { automationId = it })
        GroupToggle("阻止触发其他自动快捷回复", preventNested) { preventNested = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ save(buildDraft()) }, enabled = !busy && label.isNotBlank() && text.isNotBlank()) { Text("保存快捷回复") }
            if (text.trimStart().startsWith('/')) {
                OutlinedButton({ debug(buildDraft()) }, enabled = !busy && text.isNotBlank()) {
                    Icon(Icons.Outlined.BugReport, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("单步调试")
                }
            }
        }
        if (item.label.isNotBlank()) TextButton(remove) { Text("从回复集移除") }
    }
}

@Composable
internal fun QuickReplyDebuggerDialog(
    snapshot: ScriptDebugSnapshot,
    onAction: (DebugAction) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val lines = remember(snapshot.source) { snapshot.source.lines() }
    AlertDialog(
        onDismissRequest = { onAction(DebugAction.ABORT) },
        icon = { Icon(Icons.Outlined.BugReport, contentDescription = null) },
        title = {
            Text(
                "${snapshot.label} · ${if (snapshot.pausedReason == "breakpoint") "断点暂停" else "单步暂停"}（第 ${snapshot.currentLine} 行）",
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("当前命令：${snapshot.currentCommand.trim()}", style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
                Text("步数：${snapshot.stepCount} · {{pipe}} = ${snapshot.pipe.ifEmpty { "（空）" }}", style = MaterialTheme.typography.bodySmall)
                Surface(color = scheme.surfaceContainerLow, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        lines.forEachIndexed { idx, lineText ->
                            val lineNo = idx + 1
                            val active = lineNo == snapshot.currentLine
                            val rowBg = if (active) scheme.primaryContainer else Color.Transparent
                            val rowFg = if (active) scheme.onPrimaryContainer else scheme.onSurface
                            Row(
                                Modifier.fillMaxWidth().background(rowBg, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    "%2d".format(lineNo),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (active) rowFg else scheme.onSurfaceVariant,
                                )
                                Text(
                                    lineText,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = rowFg,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                if (snapshot.frameVariables.isNotEmpty()) {
                    Text("脚本帧变量", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    snapshot.frameVariables.entries.take(12).forEach { (k, v) ->
                        Text("$k = $v", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
                if (snapshot.chatVariables.isNotEmpty()) {
                    Text("对话局部变量", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    snapshot.chatVariables.entries.take(8).forEach { (k, v) ->
                        Text("$k = $v", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        },
        dismissButton = {
            TextButton({ onAction(DebugAction.ABORT) }) { Text("中止") }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ onAction(DebugAction.STEP_OVER) }) { Text("单步") }
                Button({ onAction(DebugAction.CONTINUE) }) { Text("继续") }
            }
        },
    )
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

internal fun scriptDialogIcon(name: String): ImageVector? = when (name.trim().lowercase().removePrefix("fa-").removePrefix("fa-solid ")) {
    "info", "circle-info", "info-circle" -> Icons.Outlined.Info
    "warn", "warning", "triangle-exclamation", "exclamation" -> Icons.Outlined.WarningAmber
    "error", "danger", "circle-xmark", "times" -> Icons.Outlined.ErrorOutline
    "check", "ok", "success", "circle-check" -> Icons.Outlined.CheckCircle
    "help", "question", "circle-question" -> Icons.AutoMirrored.Outlined.HelpOutline
    "edit", "pen", "pencil", "input" -> Icons.Outlined.Edit
    "search", "magnifying-glass" -> Icons.Outlined.Search
    "star", "favorite" -> Icons.Outlined.StarOutline
    "bolt", "zap", "flash", "script" -> Icons.Outlined.Bolt
    "settings", "gear", "cog" -> Icons.Outlined.Settings
    "book", "lore", "world" -> Icons.AutoMirrored.Outlined.MenuBook
    "chat", "message", "comment" -> Icons.Outlined.ChatBubbleOutline
    "person", "user", "persona" -> Icons.Outlined.Person
    "image", "photo", "picture" -> Icons.Outlined.Image
    "delete", "trash" -> Icons.Outlined.DeleteOutline
    "lock" -> Icons.Outlined.Lock
    "bug", "debug" -> Icons.Outlined.BugReport
    else -> null
}

@Composable
internal fun ScriptQuestion(dialog: ScriptDialog, resolve: (String?) -> Unit) {
    var input by rememberSaveable(dialog.id) { mutableStateOf(dialog.initial) }
    val resolvedIcon = remember(dialog.icon) { scriptDialogIcon(dialog.icon) }
    AlertDialog(
        onDismissRequest = { resolve(null) },
        icon = if (dialog.icon.isNotBlank()) ({
            if (resolvedIcon != null) Icon(resolvedIcon, contentDescription = dialog.tooltip.ifBlank { dialog.icon })
            else Text(dialog.icon.take(4), style = MaterialTheme.typography.headlineSmall)
        }) else null,
        title = { Text(dialog.title) },
        text = {
            val body = Modifier.heightIn(max = 480.dp)
            val popup = dialog.kind == "popup"
            Column(if (popup) body.verticalScroll(rememberScrollState()) else body, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (dialog.tooltip.isNotBlank()) {
                    Text(
                        dialog.tooltip,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (dialog.text.isNotBlank()) {
                    if (popup) SelectionContainer { Text(dialog.text) } else Text(dialog.text)
                } else if (popup && dialog.placeholder.isNotBlank()) {
                    Text(
                        dialog.placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (dialog.kind == "input") {
                    Field("输入", input, { input = it }, placeholder = dialog.placeholder.takeIf(String::isNotBlank))
                }
                if (dialog.kind == "buttons") {
                    val itemsList = dialog.buttonItems.ifEmpty { dialog.buttons.map { ScriptDialogButton(it) } }
                    LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(itemsList) { button ->
                            val btnIcon = remember(button.icon) { scriptDialogIcon(button.icon) }
                            TextButton({ resolve(button.label) }, modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    if (btnIcon != null) {
                                        Icon(btnIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                                    } else if (button.icon.isNotBlank()) {
                                        Text(button.icon.take(4))
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(button.label)
                                        if (button.tooltip.isNotBlank()) {
                                            Text(
                                                button.tooltip,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        dismissButton = { if (dialog.kind != "popup") TextButton({ resolve(null) }) { Text("取消") } },
        confirmButton = {
            if (dialog.kind != "buttons") TextButton({ resolve(if (dialog.kind == "input") input else dialog.text) }) {
                Text(if (dialog.kind == "confirm-delete") "删除" else "确定")
            }
        },
    )
}
