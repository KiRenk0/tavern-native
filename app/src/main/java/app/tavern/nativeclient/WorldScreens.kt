package app.tavern.nativeclient

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun ChatWorldPicker(
    current: WorldBook?, inherited: WorldBook?, missing: Boolean, books: List<WorldBook>,
    isGroup: Boolean, isTemporary: Boolean, busy: Boolean,
    dismiss: () -> Unit, save: (WorldBook?) -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(current?.id) }
    var picked by rememberSaveable { mutableStateOf(false) }
    val book = books.firstOrNull { it.id == selected }
    val inheritedLabel = if (inherited == null) "沿用角色世界书" else "沿用角色世界书 · ${inherited.name}"
    AlertDialog(
        onDismissRequest = { if (!busy) dismiss() },
        title = { Text("对话世界书") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectField("绑定世界书", book, listOf<WorldBook?>(null) + books,
                    { it?.name ?: inheritedLabel },
                    !busy) { selected = it?.id; picked = true }
                Text(when {
                    missing && !picked -> "此前绑定的世界书已找不到。保存后将沿用角色世界书，也可以重新选择。"
                    isTemporary -> "临时聊天可使用世界书条目。联通角色记忆仅用于已保存的聊天。"
                    (book ?: inherited)?.sharedMemory == true -> "已开启联通角色记忆。同一角色可在这本世界书下的单聊和群聊之间召回经历。"
                    book != null || inherited != null -> "联通角色记忆已关闭。可在这本世界书的详情页开启。"
                    isGroup -> "群聊根据当前发言角色绑定的世界书召回记忆。"
                    else -> "请在这里或角色编辑页绑定世界书。"
                })
            }
        },
        confirmButton = { TextButton({ save(book) }, enabled = !busy) { Text("保存") } },
        dismissButton = { TextButton(dismiss, enabled = !busy) { Text("取消") } },
    )
}

@Composable
internal fun WorldBookLibrary(state: TavernState, model: TavernViewModel) {
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingBook by rememberSaveable { mutableStateOf(false) }
    var editingScan by rememberSaveable { mutableStateOf(false) }
    var editingEntry by rememberSaveable { mutableStateOf<Int?>(null) }
    var deletingBook by remember { mutableStateOf(false) }
    var deletingEntry by remember { mutableStateOf<LoreEntry?>(null) }
    var managingMemory by remember { mutableStateOf(false) }
    var memoryCardId by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val open = state.books.firstOrNull { it.id == openId }
    val launchImport = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) model.importBook(uri, context.documentName(uri))
    }
    val launchExport = rememberLauncherForActivityResult(CreateDocument("application/json")) { uri ->
        if (uri != null && open != null) model.exportBook(uri, open)
    }
    fun back() {
        if (state.busy) return
        when {
            editingEntry != null -> editingEntry = null
            editingBook -> editingBook = false
            editingScan -> editingScan = false
            openId != null -> { openId = null; query = "" }
        }
    }
    BackHandler(enabled = editingEntry != null || editingBook || editingScan || openId != null) { back() }

    val entries = remember(open) { open?.entries.orEmpty() }
    val itemId = editingEntry
    when {
        editingScan -> LoreScanSettings(state.prefs.loreSettings, state.busy, ::back) {
            model.saveLoreSettings(it) { editingScan = false }
        }
        editingBook -> {
            val book = open ?: remember { WorldBook() }
            BookSettings(book, state.busy, ::back) {
                model.saveBook(it) { openId = it.id; editingBook = false }
            }
        }
        itemId != null && open != null -> {
            val entry = entries.firstOrNull { it.id == itemId } ?: LoreEntry(itemId)
            key(open.id, itemId) {
                EntryEditor(entry, state.busy, ::back) { value ->
                    val updated = entries.toMutableList()
                    val index = updated.indexOfFirst { it.id == value.id }
                    if (index < 0) updated += value else updated[index] = value
                    model.saveBook(open.withEntries(updated)) { editingEntry = null }
                }
            }
        }
        open != null -> {
            WorldsList {
                item {
                    TextButton(::back, enabled = !state.busy) { Text("返回世界书") }
                    FormHeading(open.name, "${entries.size} 个条目")
                    GroupToggle("用于所有对话", open.enabled, !state.busy) {
                        model.saveBook(open.copy(enabled = it)) {}
                    }
                    GroupToggle("联通角色记忆", open.sharedMemory, !state.busy) {
                        model.saveBook(open.withSharedMemory(it)) {}
                    }
                    if (open.sharedMemory) {
                        Text("绑定本书的单聊和群聊会按角色召回历史。召回片段会随回复请求发给当前模型。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val boundCards = remember(state.cards, open) {
                            state.cards.filter { it.worldName == open.name }.ifEmpty { state.cards }
                        }
                        if (boundCards.isNotEmpty()) {
                            OutlinedButton(
                                onClick = {
                                    if (memoryCardId == null || boundCards.none { it.id == memoryCardId }) {
                                        memoryCardId = boundCards.first().id
                                    }
                                    managingMemory = true
                                },
                                enabled = !state.busy,
                            ) {
                                Icon(Icons.Outlined.Psychology, null)
                                Spacer(Modifier.width(6.dp))
                                Text("管理跨聊角色记忆")
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ActionIcon(Icons.Outlined.Settings, "世界书设置", !state.busy) { editingBook = true }
                        ActionIcon(Icons.Outlined.FileUpload, "导出世界书", !state.busy) {
                            launchExport.launch("${safeFilename(open.name)}.json")
                        }
                        ActionIcon(Icons.Outlined.DeleteOutline, "删除世界书", !state.busy) { deletingBook = true }
                        Spacer(Modifier.weight(1f))
                        FilledTonalButton(onClick = {
                            val used = entries.map(LoreEntry::id).toSet()
                            editingEntry = generateSequence(0) { it + 1 }.first { it !in used }
                        }, enabled = !state.busy) { Text("新建条目") }
                    }
                    if (entries.isNotEmpty()) Field("搜索条目", query, { query = it })
                }
                val matches = entries.sortedByDescending(LoreEntry::priority).filter {
                    (listOf(it.title, it.content) + it.keywords).any { part -> part.contains(query.trim(), true) }
                }
                items(matches, key = LoreEntry::id) { entry ->
                    Card(onClick = { editingEntry = entry.id }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(entry.title.ifBlank { entry.keywords.joinToString("、").ifBlank { "未命名条目" } },
                                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(when {
                                    entry.disabled -> "已禁用"
                                    entry.constant -> "常驻"
                                    entry.vectorized -> listOf("向量匹配", entry.keywords.joinToString("、")).filter(String::isNotBlank).joinToString(" · ")
                                    else -> entry.keywords.joinToString("、")
                                },
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            ActionIcon(Icons.Outlined.DeleteOutline, "删除条目", !state.busy) { deletingEntry = entry }
                        }
                    }
                }
            }
        }
        else -> {
            WorldsList {
                item {
                    FormHeading("世界书", "设定可全局启用，也可在角色编辑页绑定。")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ editingBook = true }, enabled = !state.busy) { Text("新建世界书") }
                        OutlinedButton({ launchImport.launch(arrayOf("*/*")) }, enabled = !state.busy) { Text("导入 JSON") }
                        ActionIcon(Icons.Outlined.Settings, "全局扫描设置", !state.busy) { editingScan = true }
                    }
                    if (state.books.isNotEmpty()) Field("搜索世界书", query, { query = it })
                }
                items(state.books.filter { it.name.contains(query.trim(), true) }, key = WorldBook::id) { book ->
                    Card(onClick = { openId = book.id; query = "" }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        ListItem(headlineContent = { Text(book.name) },
                            supportingContent = {
                                val activation = if (book.enabled) "全局启用" else "仅在关联时启用"
                                Text(if (book.sharedMemory) "$activation · 联通角色记忆" else activation)
                            },
                            leadingContent = { Icon(Icons.AutoMirrored.Outlined.MenuBook, null) },
                            trailingContent = { Icon(Icons.Outlined.ChevronRight, null) })
                    }
                }
            }
        }
    }
    if (deletingBook && open != null) {
        Confirm("删除世界书？", "「${open.name}」的条目及角色绑定将被删除。", { deletingBook = false }) {
            deletingBook = false
            model.deleteBook(open)
            openId = null
        }
    }
    deletingEntry?.let { entry ->
        if (open != null) Confirm("删除此条目？", "此条目将从世界书移除。", { deletingEntry = null }) {
            model.saveBook(open.withEntries(entries.filterNot { it.id == entry.id })) {}
            deletingEntry = null
        }
    }
    if (managingMemory && open != null) {
        val targetCard = state.cards.firstOrNull { it.id == memoryCardId } ?: state.cards.firstOrNull()
        SharedWorldMemoryDialog(
            world = open,
            card = targetCard,
            recall = state.lastSharedMemory,
            busy = state.busy,
            dismiss = { managingMemory = false },
            refresh = { targetCard?.let { model.inspectSharedWorldMemory(open, it) } },
            pinItem = { w, c, m -> model.pinSharedMemory(w, c, m) },
            unpinItem = { w, c, id -> model.unpinSharedMemory(w, c, id) },
            editItem = { w, c, key, txt -> model.editSharedMemory(w, c, key, txt) },
            blockItem = { w, c, key, blk -> model.blockSharedMemory(w, c, key, blk) },
            promoteItem = { w, c, txt, src -> model.promoteSharedMemoryToLore(w, c, txt, src) },
        )
    }
}

@Composable
private fun LoreScanSettings(settings: LoreSettings, busy: Boolean, back: () -> Unit, save: (LoreSettings) -> Unit) {
    var recursive by rememberSaveable { mutableStateOf(settings.recursive) }
    var maxPasses by rememberSaveable { mutableStateOf(settings.maxPasses.toString()) }
    var minimum by rememberSaveable { mutableStateOf(settings.minimum.toString()) }
    var maxDepth by rememberSaveable { mutableStateOf(settings.minimumDepthMax.toString()) }
    var caseSensitive by rememberSaveable { mutableStateOf(settings.caseSensitive) }
    var wholeWords by rememberSaveable { mutableStateOf(settings.wholeWords) }
    var groupScoring by rememberSaveable { mutableStateOf(settings.groupScoring) }
    var budgetPercent by rememberSaveable { mutableStateOf(settings.budgetPercent.toString()) }
    var budgetCap by rememberSaveable { mutableStateOf(settings.budgetCap.toString()) }
    var overflowAlert by rememberSaveable { mutableStateOf(settings.overflowAlert) }
    val passes = maxPasses.toIntOrNull()?.takeIf { it >= 0 }
    val minimumCount = minimum.toIntOrNull()?.takeIf { it >= 0 }
    val depth = maxDepth.toIntOrNull()?.takeIf { it in 0..80 }
    val percent = budgetPercent.toIntOrNull()?.takeIf { it in 0..100 }
    val cap = budgetCap.toIntOrNull()?.takeIf { it in 0..MAX_CONTEXT_TOKENS }
    Form {
        TextButton(back, enabled = !busy) { Text("返回") }
        FormHeading("世界书扫描设置")
        Field("占模型上下文的百分比（0–100）", budgetPercent, { budgetPercent = it },
            enabled = !busy, type = KeyboardType.Number)
        Field("世界书 Token 上限（0 为不限）", budgetCap, { budgetCap = it }, enabled = !busy, type = KeyboardType.Number)
        GroupToggle("超过世界书额度时提示", overflowAlert, !busy) { overflowAlert = it }
        GroupToggle("递归扫描已触发条目的内容", recursive, !busy) { recursive = it }
        Field("最多扫描轮数（0 为不限）", maxPasses, { maxPasses = it }, enabled = !busy, type = KeyboardType.Number)
        if (passes == 0) {
            Field("最少激活条目数（0 为关闭）", minimum, { minimum = it }, enabled = !busy, type = KeyboardType.Number)
            if (minimumCount != null && minimumCount > 0) {
                Field("扩展扫描深度上限（0 为默认）", maxDepth, { maxDepth = it }, enabled = !busy, type = KeyboardType.Number)
            }
        }
        GroupToggle("默认区分大小写", caseSensitive, !busy) { caseSensitive = it }
        GroupToggle("默认匹配整个词", wholeWords, !busy) { wholeWords = it }
        GroupToggle("按匹配关键词数量参与分组竞争", groupScoring, !busy) { groupScoring = it }
        Button(onClick = { save(LoreSettings(recursive, requireNotNull(passes), requireNotNull(minimumCount),
            requireNotNull(depth), caseSensitive, wholeWords, groupScoring, requireNotNull(percent), requireNotNull(cap), overflowAlert)) },
            enabled = !busy && passes != null && minimumCount != null && depth != null && percent != null && cap != null,
            modifier = Modifier.fillMaxWidth()) { Text("保存扫描设置") }
    }
}

@Composable
private fun BookSettings(book: WorldBook, busy: Boolean, back: () -> Unit, save: (WorldBook) -> Unit) {
    var name by rememberSaveable { mutableStateOf(book.name) }
    var depth by rememberSaveable { mutableStateOf(book.scanDepth.toString()) }
    var enabled by rememberSaveable { mutableStateOf(book.enabled) }
    var sharedMemory by rememberSaveable { mutableStateOf(book.sharedMemory) }
    val scanDepth = depth.toIntOrNull()?.takeIf { it in 0..80 }
    Form {
        TextButton(back, enabled = !busy) { Text("返回") }
        FormHeading(if (book.name.isBlank()) "新建世界书" else "世界书设置")
        Field("名称", name, { name = it }, enabled = !busy)
        Field("扫描最近多少条消息", depth, { depth = it }, enabled = !busy, type = KeyboardType.Number)
        GroupToggle("用于所有对话", enabled, !busy) { enabled = it }
        GroupToggle("联通角色记忆", sharedMemory, !busy) { sharedMemory = it }
        Button({ save(book.copy(name = name, enabled = enabled).withScanDepth(requireNotNull(scanDepth)).withSharedMemory(sharedMemory)) },
            enabled = !busy && name.isNotBlank() && scanDepth != null, modifier = Modifier.fillMaxWidth()) {
            Text("保存世界书")
        }
    }
}

private enum class EntryActivation(val label: String) {
    KEYWORD("关键词"), VECTOR("向量匹配和关键词"), CONSTANT("常驻");
}

@Composable
private fun EntryEditor(entry: LoreEntry, busy: Boolean, back: () -> Unit, save: (LoreEntry) -> Unit) {
    var title by rememberSaveable { mutableStateOf(entry.title) }
    var content by rememberSaveable { mutableStateOf(entry.content) }
    var keywords by rememberSaveable { mutableStateOf(entry.keywords.joinToString("\n")) }
    var secondary by rememberSaveable { mutableStateOf(entry.secondary.joinToString("\n")) }
    var activation by rememberSaveable {
        mutableStateOf(when {
            entry.constant -> EntryActivation.CONSTANT
            entry.vectorized -> EntryActivation.VECTOR
            else -> EntryActivation.KEYWORD
        })
    }
    var disabled by rememberSaveable { mutableStateOf(entry.disabled) }
    var caseSensitive by rememberSaveable { mutableStateOf(entry.caseSensitive) }
    var wholeWords by rememberSaveable { mutableStateOf(entry.wholeWords) }
    var logic by rememberSaveable { mutableStateOf(entry.logic) }
    var priority by rememberSaveable { mutableStateOf(entry.priority.toString()) }
    var probability by rememberSaveable { mutableStateOf(entry.probability.toString()) }
    var scanDepth by rememberSaveable { mutableStateOf(entry.scanDepth?.toString().orEmpty()) }
    var depth by rememberSaveable { mutableStateOf(entry.depth.toString()) }
    var position by rememberSaveable { mutableStateOf(entry.position) }
    var role by rememberSaveable { mutableStateOf(entry.role) }
    var outlet by rememberSaveable { mutableStateOf(entry.rules.outlet) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var groups by rememberSaveable { mutableStateOf(entry.rules.groups) }
    var groupOverride by rememberSaveable { mutableStateOf(entry.rules.groupOverride) }
    var groupWeight by rememberSaveable { mutableStateOf(entry.rules.groupWeight.toString()) }
    var groupScoring by rememberSaveable { mutableStateOf(entry.rules.groupScoring) }
    var excludeRecursion by rememberSaveable { mutableStateOf(entry.rules.excludeRecursion) }
    var preventRecursion by rememberSaveable { mutableStateOf(entry.rules.preventRecursion) }
    var recursionLevel by rememberSaveable { mutableStateOf(entry.rules.recursionLevel.toString()) }
    var sticky by rememberSaveable { mutableStateOf(entry.rules.sticky.toString()) }
    var cooldown by rememberSaveable { mutableStateOf(entry.rules.cooldown.toString()) }
    var delay by rememberSaveable { mutableStateOf(entry.rules.delay.toString()) }
    var ignoreBudget by rememberSaveable { mutableStateOf(entry.rules.ignoreBudget) }
    var automationId by rememberSaveable { mutableStateOf(entry.automationId) }
    var sources by rememberSaveable { mutableStateOf<List<String>>(entry.rules.sources.map(LoreSource::name)) }
    val numericPriority = priority.toIntOrNull()
    val numericProbability = probability.toIntOrNull()?.takeIf { it in 0..100 }
    val numericScanDepth = scanDepth.toIntOrNull()?.takeIf { it in 0..80 }
    val numericDepth = depth.toIntOrNull()?.takeIf { it in 0..80 }
    val weight = groupWeight.toIntOrNull()?.takeIf { it in 0..10_000 }
    val level = recursionLevel.toIntOrNull()?.takeIf { it >= 0 }
    val stickyCount = sticky.toIntOrNull()?.takeIf { it in 0..10_000 }
    val cooldownCount = cooldown.toIntOrNull()?.takeIf { it in 0..10_000 }
    val delayCount = delay.toIntOrNull()?.takeIf { it in 0..10_000 }
    val validAdvanced = weight != null && level != null && stickyCount != null && cooldownCount != null && delayCount != null
    fun keys(input: String) = input.lineSequence().map(String::trim).filter(String::isNotBlank).distinct().toList()

    Form {
        TextButton(back, enabled = !busy) { Text("返回") }
        FormHeading("世界书条目")
        Field("条目名称（可选）", title, { title = it }, enabled = !busy)
        Field("内容", content, { content = it }, lines = 4, enabled = !busy)
        GroupToggle("启用条目", !disabled, !busy) { disabled = !it }
        SelectField("触发方式", activation, EntryActivation.entries, EntryActivation::label, !busy) { activation = it }
        if (activation != EntryActivation.CONSTANT) {
            Field("主关键词（每行一个）", keywords, { keywords = it }, lines = 2, enabled = !busy)
            Field("次关键词（每行一个）", secondary, { secondary = it }, lines = 2, enabled = !busy)
            if (secondary.isNotBlank()) SelectField("次关键词逻辑", logic, LoreLogic.entries, LoreLogic::label, !busy) { logic = it }
            LoreOverride("区分大小写", caseSensitive, !busy) { caseSensitive = it }
            LoreOverride("匹配整个词", wholeWords, !busy) { wholeWords = it }
            Field("条目扫描深度（可选）", scanDepth, { scanDepth = it }, enabled = !busy, type = KeyboardType.Number)
        }
        Field("排序优先级", priority, { priority = it }, enabled = !busy, type = KeyboardType.Number)
        Field("触发概率（0–100）", probability, { probability = it }, enabled = !busy, type = KeyboardType.Number)
        SelectField("注入位置", position, LorePosition.entries, LorePosition::label, !busy) { position = it }
        if (position == LorePosition.DEPTH) {
            Field("注入深度", depth, { depth = it }, enabled = !busy, type = KeyboardType.Number)
            SelectField("注入身份", role, listOf("system", "user", "assistant"), {
                when (it) { "user" -> "用户"; "assistant" -> "助手"; else -> "系统" }
            }, !busy) { role = it }
        }
        if (position == LorePosition.OUTLET) {
            Field("锚点名称", outlet, { outlet = it }, placeholder = "用 {{outlet::名称}} 引用", enabled = !busy)
        }
        TextButton({ advanced = !advanced }, enabled = !busy) { Text(if (advanced) "收起高级规则" else "高级规则") }
        if (advanced) {
            FormHeading("递归与定时")
            GroupToggle("排除递归轮次", excludeRecursion, !busy) { excludeRecursion = it }
            GroupToggle("本条目内容不参与下一轮递归", preventRecursion, !busy) { preventRecursion = it }
            Field("递归激活层级（0 为不限）", recursionLevel, { recursionLevel = it }, enabled = !busy, type = KeyboardType.Number)
            Field("保持激活的消息数", sticky, { sticky = it }, enabled = !busy, type = KeyboardType.Number)
            Field("冷却消息数", cooldown, { cooldown = it }, enabled = !busy, type = KeyboardType.Number)
            Field("开场延迟消息数", delay, { delay = it }, enabled = !busy, type = KeyboardType.Number)
            FormHeading("互斥分组")
            Field("分组名称（逗号分隔）", groups, { groups = it }, enabled = !busy)
            if (groups.isNotBlank()) {
                GroupToggle("该条目优先竞争", groupOverride, !busy) { groupOverride = it }
                Field("分组权重", groupWeight, { groupWeight = it }, enabled = !busy, type = KeyboardType.Number)
                LoreOverride("参与关键词计分", groupScoring, !busy) { groupScoring = it }
            }
            FormHeading("扫描位置")
            LoreSource.entries.forEach { source ->
                GroupToggle(source.label, source.name in sources, !busy) { enabled ->
                    sources = if (enabled) sources + source.name else sources - source.name
                }
            }
            GroupToggle("忽略世界书注入额度", ignoreBudget, !busy) { ignoreBudget = it }
            Field("快捷回复自动化 ID（可选）", automationId, { automationId = it }, enabled = !busy)
        }
        Button(onClick = {
            save(entry.copy(title = title.trim(), content = content, keywords = keys(keywords), secondary = keys(secondary),
                logic = logic, constant = activation == EntryActivation.CONSTANT, vectorized = activation == EntryActivation.VECTOR,
                disabled = disabled, caseSensitive = caseSensitive, wholeWords = wholeWords,
                priority = requireNotNull(numericPriority), scanDepth = numericScanDepth,
                probability = requireNotNull(numericProbability), position = position, depth = numericDepth ?: entry.depth, role = role,
                automationId = automationId.trim(),
                rules = entry.rules.copy(outlet = outlet.trim(), groups = groups.trim(), groupOverride = groupOverride,
                    groupWeight = requireNotNull(weight), groupScoring = groupScoring, excludeRecursion = excludeRecursion,
                    preventRecursion = preventRecursion, recursionLevel = requireNotNull(level), sticky = requireNotNull(stickyCount),
                    cooldown = requireNotNull(cooldownCount), delay = requireNotNull(delayCount), ignoreBudget = ignoreBudget,
                    sources = LoreSource.entries.filter { it.name in sources }.toSet())))
        }, enabled = !busy && content.isNotBlank() && numericPriority != null && numericProbability != null &&
            (scanDepth.isBlank() || numericScanDepth != null) && (position != LorePosition.DEPTH || numericDepth != null) &&
            (position != LorePosition.OUTLET || outlet.isNotBlank()) && validAdvanced,
            modifier = Modifier.fillMaxWidth()) { Text("保存条目") }
    }
}

@Composable
private fun LoreOverride(label: String, value: Boolean?, enabled: Boolean, change: (Boolean?) -> Unit) {
    SelectField(label, value, listOf<Boolean?>(null, true, false),
        { when (it) { true -> "开启"; false -> "关闭"; null -> "跟随全局设置" } }, enabled, change)
}

@Composable
private fun WorldsList(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 740.dp).fillMaxWidth(), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
