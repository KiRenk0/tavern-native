package app.tavern.nativeclient

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun RegexLibrary(state: TavernState, model: TavernViewModel, dismiss: () -> Unit) {
    var scope by rememberSaveable { mutableStateOf(RegexScope.GLOBAL) }
    var cardId by rememberSaveable { mutableStateOf<String?>(null) }
    var presetId by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    val card = state.cards.firstOrNull { it.id == cardId } ?: state.cards.firstOrNull()
    val preset = state.presets.firstOrNull { it.id == presetId } ?: state.presets.firstOrNull()
    val target = when (scope) {
        RegexScope.GLOBAL -> RegexTarget()
        RegexScope.CARD -> card?.let { RegexTarget(scope, it.id) }
        RegexScope.PRESET -> preset?.let { RegexTarget(scope, it.id) }
    }
    val rules = target?.let(state::regexRules).orEmpty()
    fun back() {
        if (state.busy) return
        if (editing != null) editing = null else dismiss()
    }
    BackHandler { back() }
    val openJson = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null && target != null) model.importRegexRules(uri, target)
    }
    val saveJson = rememberLauncherForActivityResult(CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val rows = exportId?.let { id -> rules.filter { it.id == id } } ?: rules
            if (rows.isNotEmpty()) model.exportRegexRules(uri, rows)
        }
    }
    fun move(index: Int, offset: Int) {
        val owner = target ?: return
        val moved = rules.toMutableList().apply { add(index + offset, removeAt(index)) }
        model.saveRegexRules(owner, moved)
    }
    val id = editing
    if (id != null && target != null) {
        val existing = rules.firstOrNull { it.id == id }
        key(target, id) {
            val actor = if (scope == RegexScope.CARD) card else state.regexCard()
            val names = RegexNames(actor, state.persona.name, state.group?.members(state.cards).orEmpty().map(Card::name))
            RegexRuleEditor(existing ?: RegexRule(id = id), names, state.busy, ::back,
                export = existing?.let { rule ->
                    { exportId = rule.id; saveJson.launch("${safeFilename(rule.name)}.json") }
                }) { changed ->
                val index = rules.indexOfFirst { it.id == changed.id }
                val updated = rules.toMutableList().apply { if (index < 0) add(changed) else set(index, changed) }
                model.saveRegexRules(target, updated) { editing = null }
            }
        }
    } else {
        Form {
            TextButton(::back, enabled = !state.busy) { Text("返回设置") }
            FormHeading("正则扩展")
            SelectField("规则范围", scope, RegexScope.entries, RegexScope::label, !state.busy) { scope = it }
            if (scope == RegexScope.CARD && card != null) {
                SelectField("角色卡", card, state.cards, Card::name, !state.busy) { cardId = it.id }
            }
            if (scope == RegexScope.PRESET && preset != null) {
                SelectField("提示词预设", preset, state.presets, PromptPreset::name, !state.busy) { presetId = it.id }
            }
            if (target == null) {
                Text(if (scope == RegexScope.CARD) "还没有角色卡。" else "还没有提示词预设。")
            } else {
                GroupToggle(if (scope == RegexScope.GLOBAL) "启用正则扩展" else "允许使用此范围的规则",
                    state.regexAllowed(target), !state.busy) { model.allowRegex(target, it) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button({ editing = newId() }, enabled = !state.busy) { Text("新建规则") }
                    OutlinedButton({ openJson.launch(arrayOf("*/*")) }, enabled = !state.busy) { Text("导入 JSON") }
                    ActionIcon(Icons.Outlined.FileUpload, "导出此范围的规则", !state.busy && rules.isNotEmpty()) {
                        exportId = null
                        saveJson.launch("regex-${scope.name.lowercase()}.json")
                    }
                }
                if (rules.isEmpty()) Text("此范围暂无规则。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                rules.forEachIndexed { index, rule ->
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(!rule.disabled, onCheckedChange = { enabled ->
                                    model.saveRegexRules(target, rules.map { if (it.id == rule.id) it.copy(disabled = !enabled) else it })
                                }, enabled = !state.busy)
                                TextButton({ editing = rule.id }, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(rule.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(rule.sites.joinToString("、", transform = RegexSite::label),
                                            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                ActionIcon(Icons.Outlined.ArrowUpward, "上移 ${rule.name}", !state.busy && index > 0) { move(index, -1) }
                                ActionIcon(Icons.Outlined.ArrowDownward, "下移 ${rule.name}", !state.busy && index < rules.lastIndex) {
                                    move(index, 1)
                                }
                                ActionIcon(Icons.Outlined.DeleteOutline, "删除 ${rule.name}", !state.busy) { deleting = rule.id }
                            }
                        }
                    }
                }
            }
        }
    }
    val rule = rules.firstOrNull { it.id == deleting }
    if (rule != null && target != null) {
        Confirm("删除正则规则？", "「${rule.name}」将从此范围移除。", { deleting = null }) {
            model.saveRegexRules(target, rules.filterNot { it.id == rule.id }) { deleting = null }
        }
    }
}

@Composable
private fun RegexRuleEditor(rule: RegexRule, names: RegexNames, busy: Boolean, back: () -> Unit,
                            export: (() -> Unit)?, save: (RegexRule) -> Unit) {
    var name by rememberSaveable { mutableStateOf(rule.name) }
    var pattern by rememberSaveable { mutableStateOf(rule.pattern) }
    var replacement by rememberSaveable { mutableStateOf(rule.replacement) }
    var trims by rememberSaveable { mutableStateOf(rule.trims.joinToString("\n")) }
    var sites by rememberSaveable { mutableStateOf<List<String>>(rule.sites.map(RegexSite::name)) }
    var disabled by rememberSaveable { mutableStateOf(rule.disabled) }
    var displayOnly by rememberSaveable { mutableStateOf(rule.displayOnly) }
    var requestOnly by rememberSaveable { mutableStateOf(rule.requestOnly) }
    var onEdit by rememberSaveable { mutableStateOf(rule.onEdit) }
    var macros by rememberSaveable { mutableStateOf(rule.macros) }
    var minDepth by rememberSaveable { mutableStateOf(rule.minDepth?.toString().orEmpty()) }
    var maxDepth by rememberSaveable { mutableStateOf(rule.maxDepth?.toString().orEmpty()) }
    var showTest by rememberSaveable { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    val min = minDepth.toIntOrNull()?.takeIf { it >= -1 }
    val max = maxDepth.toIntOrNull()?.takeIf { it >= 0 }
    val validDepth = (minDepth.isBlank() || min != null) && (maxDepth.isBlank() || max != null) &&
        (min == null || max == null || min <= max)
    val draft = rule.copy(name = name.trim(), pattern = pattern, replacement = replacement,
        trims = trims.lineSequence().filter(String::isNotEmpty).toList(), sites = RegexSite.entries.filter { it.name in sites },
        disabled = disabled, displayOnly = displayOnly, requestOnly = requestOnly, onEdit = onEdit, macros = macros,
        minDepth = min, maxDepth = max)
    val testResult by produceState<RegexTestResult?>(null, draft, input, names, showTest) {
        if (showTest && pattern.isNotEmpty() && input.isNotEmpty()) {
            delay(120)
            value = withContext(Dispatchers.Default) { regexTest(draft, input, names) }
        } else value = null
    }
    Form {
        TextButton(back, enabled = !busy) { Text("返回规则列表") }
        FormHeading(if (rule.name.isBlank()) "新建规则" else "编辑正则规则")
        Field("规则名称", name, { name = it }, enabled = !busy)
        Field("查找表达式", pattern, { pattern = it }, lines = 2, placeholder = "/例子/gi", enabled = !busy)
        Field("替换为", replacement, { replacement = it }, lines = 2, placeholder = "{{match}}", enabled = !busy)
        Field("从匹配中删去的文本（每行一项）", trims, { trims = it }, lines = 2, enabled = !busy)
        SelectField("查找表达式中的宏", macros, RegexMacros.entries, RegexMacros::label, !busy) { macros = it }
        FormHeading("应用位置")
        RegexSite.entries.forEach { site ->
            GroupToggle(site.label, site.name in sites, !busy) { use -> sites = if (use) sites + site.name else sites - site.name }
        }
        FormHeading("生效阶段", "两项均未勾选时，规则会修改新消息保存的正文。")
        GroupToggle("仅修改显示", displayOnly, !busy) { displayOnly = it }
        GroupToggle("仅修改发给模型的提示词", requestOnly, !busy) { requestOnly = it }
        GroupToggle("编辑消息时运行", onEdit, !busy) { onEdit = it }
        GroupToggle("启用规则", !disabled, !busy) { disabled = !it }
        Field("最小深度（-1 包含续写原文）", minDepth, { minDepth = it }, enabled = !busy)
        Field("最大深度（留空为不限）", maxDepth, { maxDepth = it }, enabled = !busy, type = KeyboardType.Number)
        OutlinedButton({ showTest = !showTest }, enabled = !busy) { Text(if (showTest) "收起测试" else "测试替换") }
        if (showTest) {
            Field("测试文本", input, { input = it }, lines = 3, enabled = !busy)
            testResult?.let { result ->
                if (result.issue != null) Text(result.issue, color = MaterialTheme.colorScheme.error)
                SelectionContainer { Text(result.text.ifEmpty { "（空结果）" }) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ save(draft) }, enabled = !busy && name.isNotBlank() && pattern.isNotEmpty() && validDepth) {
                Text("保存规则")
            }
            if (export != null) OutlinedButton(export, enabled = !busy) { Text("导出 JSON") }
        }
    }
}
