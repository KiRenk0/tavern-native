package app.tavern.nativeclient

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

@Composable
internal fun AdvancedFormattingScreen(state: TavernState, model: TavernViewModel, close: () -> Unit) {
    var section by rememberSaveable { mutableStateOf("") }
    val back = { if (section.isBlank()) close() else section = "" }
    BackHandler { if (!state.busy) back() }
    when (section) {
        "system" -> TextSystemScreen(state.prefs.textSystem, state.busy, back, model::saveTextSystem)
        "stops" -> CustomStopsScreen(state.prefs.customStops, state.busy, back, model::saveCustomStops)
        "prefix" -> ReplyPrefixScreen(state.prefs.replyFormatting, state.busy, back) { value ->
            model.saveReplyFormatting(value, null, back)
        }
        "reasoning" -> ReasoningFormattingScreen(state.prefs, state.busy, back) { value, include ->
            model.saveReplyFormatting(value, include, back)
        }
        else -> Form {
            TextButton(close, enabled = !state.busy) { Text("返回设置") }
            FormHeading("高级格式化")
            SelectField("对话示例", state.prefs.exampleBudget, ExampleBudget.entries, ExampleBudget::label, !state.busy) {
                model.savePrefs(state.prefs.copy(exampleBudget = it))
            }
            OutlinedButton({ section = "system" }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text("文本补全系统提示")
            }
            OutlinedButton({ section = "stops" }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text("自定义停止序列 · ${state.prefs.customStops.values.size} 条")
            }
            OutlinedButton({ section = "prefix" }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text("回复开头预填")
            }
            OutlinedButton({ section = "reasoning" }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text("推理格式 · ${state.prefs.replyFormatting.reasoning.name}")
            }
        }
    }
}

@Composable
private fun ReplyPrefixScreen(settings: ReplyFormatting, busy: Boolean, back: () -> Unit, save: (ReplyFormatting) -> Unit) {
    var prefix by rememberSaveable { mutableStateOf(settings.prefix) }
    var show by rememberSaveable { mutableStateOf(settings.showPrefix) }
    Form {
        TextButton(back, enabled = !busy) { Text("返回高级格式化") }
        FormHeading("回复开头预填", "用于新回复和重新生成，支持当前角色及人设等宏。")
        Field("在回复开头填入", prefix, { if (it.length <= REPLY_PREFIX_LIMIT) prefix = it }, lines = 4, enabled = !busy)
        GroupToggle("在聊天中显示预填的开头", show, !busy) { show = it }
        Button({ save(settings.copy(prefix = prefix, showPrefix = show)) }, enabled = !busy,
            modifier = Modifier.fillMaxWidth()) { Text("保存回复开头") }
    }
}

@Composable
private fun ReasoningFormattingScreen(
    prefs: Preferences, saving: Boolean, back: () -> Unit, save: (ReplyFormatting, Boolean) -> Unit,
) {
    val settings = prefs.replyFormatting
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val presets = remember(context.assets) {
        context.assets.open("reasoning-presets.json").bufferedReader().use { reader ->
            JSONArray(reader.readText()).objects().map { ReasoningFormat.from(it) }.sortedBy { it.name.lowercase() }
        }
    }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf(settings.reasoning.name) }
    var prefix by rememberSaveable { mutableStateOf(settings.reasoning.prefix) }
    var suffix by rememberSaveable { mutableStateOf(settings.reasoning.suffix) }
    var separator by rememberSaveable { mutableStateOf(settings.reasoning.separator) }
    var parsing by rememberSaveable { mutableStateOf(settings.parseReasoning) }
    var include by rememberSaveable { mutableStateOf(prefs.includeReasoning) }
    var count by rememberSaveable { mutableStateOf(settings.maxReasoning.toString()) }
    var expand by rememberSaveable { mutableStateOf(settings.autoExpand) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val busy = saving || working
    val limit = count.toIntOrNull()?.takeIf { it in 0..80 }
    fun load(value: ReasoningFormat) {
        name = value.name
        prefix = value.prefix
        suffix = value.suffix
        separator = value.separator
        error = null
    }
    fun fileAction(action: suspend () -> Unit) {
        if (busy) return
        working = true
        error = null
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "无法读取或保存推理格式"
            } finally {
                working = false
            }
        }
    }
    val import = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) fileAction {
            val value = withContext(Dispatchers.IO) {
                val filename = context.documentName(uri).substringBeforeLast('.').take(80).ifBlank { "导入的推理格式" }
                val bytes = requireNotNull(context.contentResolver.openInputStream(uri)) { "无法打开推理格式" }
                    .use { it.readLimited(INSTRUCT_TEMPLATE_LIMIT) }
                ReasoningFormat.from(boundedJsonObject(bytes.decodeToString().trimStart('\uFEFF'), "无法读取推理格式 JSON"), filename)
            }
            selected = null
            load(value)
        }
    }
    val export = rememberLauncherForActivityResult(CreateDocument("application/json")) { uri ->
        if (uri != null) fileAction {
            val bytes = ReasoningFormat(name, prefix, suffix, separator).validated().json().toString(2).encodeToByteArray()
            withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "无法写入推理格式" }.use { it.write(bytes) }
            }
        }
    }
    BackHandler { if (!busy) back() }
    Form {
        TextButton(back, enabled = !busy) { Text("返回高级格式化") }
        FormHeading("推理格式", "将回复开头的推理标记分到独立区块，根据需要把已保存的推理带入后续请求。")
        SelectField("载入内置预设", selected, listOf(null) + presets.map(ReasoningFormat::name),
            { it ?: "选择预设" }, !busy) { choice ->
            selected = choice
            presets.firstOrNull { it.name == choice }?.let(::load)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ import.launch(arrayOf("*/*")) }, enabled = !busy) { Text("导入 JSON") }
            OutlinedButton({ export.launch("${safeFilename(name)}.json") }, enabled = !busy && name.isNotBlank()) { Text("导出 JSON") }
        }
        Field("格式名称", name, { name = it.take(80) }, enabled = !busy)
        Field("推理前缀", prefix, { if (it.length <= REASONING_MARKER_LIMIT) prefix = it }, lines = 2, enabled = !busy)
        Field("推理后缀", suffix, { if (it.length <= REASONING_MARKER_LIMIT) suffix = it }, lines = 2, enabled = !busy)
        Field("推理与正文间的分隔符", separator, { if (it.length <= REASONING_MARKER_LIMIT) separator = it },
            lines = 2, enabled = !busy)
        GroupToggle("从返回的文字中自动提取推理", parsing, !busy) { parsing = it }
        GroupToggle("自动展开聊天中的推理", expand, !busy) { expand = it }
        GroupToggle("在后续请求中附带已保存的推理", include, !busy) { include = it }
        if (include) Field("最多附带的推理条数", count, { count = it.take(2) }, enabled = !busy, type = KeyboardType.Number)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button({
            runCatching {
                require(!parsing || prefix.isNotEmpty() && suffix.isNotEmpty()) { "自动提取需要推理前缀和后缀" }
                settings.copy(reasoning = ReasoningFormat(name, prefix, suffix, separator).validated(),
                    parseReasoning = parsing, maxReasoning = limit ?: settings.maxReasoning, autoExpand = expand)
            }.onSuccess { save(it, include) }.onFailure { error = it.message }
        }, enabled = !busy && (!include || limit != null), modifier = Modifier.fillMaxWidth()) { Text("保存推理格式") }
    }
}

@Composable
private fun TextSystemScreen(
    settings: TextSystemSettings, saving: Boolean, back: () -> Unit, save: (TextSystemSettings, () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val builtIns = remember(context.assets) {
        context.assets.open("system-prompt-presets.json").bufferedReader().use { reader ->
            JSONArray(reader.readText()).objects().map { TextSystemPreset.from(it) }
        }
    }
    val presets = remember(settings.custom, builtIns) {
        (settings.custom + builtIns).distinctBy(TextSystemPreset::name).sortedBy { it.name.lowercase() }
    }
    var selected by rememberSaveable { mutableStateOf(settings.selected?.name) }
    var name by rememberSaveable { mutableStateOf(settings.selected?.name.orEmpty()) }
    var content by rememberSaveable { mutableStateOf(settings.selected?.content.orEmpty()) }
    var post by rememberSaveable { mutableStateOf(settings.selected?.postHistory.orEmpty()) }
    var enabled by rememberSaveable { mutableStateOf(settings.enabled) }
    var preferMain by rememberSaveable { mutableStateOf(settings.preferCharacterPrompt) }
    var preferPost by rememberSaveable { mutableStateOf(settings.preferCharacterPostHistory) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var remove by remember { mutableStateOf(false) }
    val blocked = busy || saving
    fun load(value: TextSystemPreset?) {
        selected = value?.name
        name = value?.name.orEmpty()
        content = value?.content.orEmpty()
        post = value?.postHistory.orEmpty()
        error = null
    }
    fun fileAction(action: suspend () -> Unit) {
        if (blocked) return
        busy = true
        error = null
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "无法读取或保存预设"
            } finally {
                busy = false
            }
        }
    }
    val import = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) fileAction {
            val imported = withContext(Dispatchers.IO) {
                val filename = context.documentName(uri).substringBeforeLast('.').take(80).ifBlank { "导入的系统提示" }
                val bytes = requireNotNull(context.contentResolver.openInputStream(uri)) { "无法打开预设文件" }
                    .use { it.readLimited(INSTRUCT_TEMPLATE_LIMIT) }
                TextSystemPreset.from(boundedJsonObject(bytes.decodeToString().trimStart('\uFEFF'), "无法读取系统提示 JSON"), filename)
            }
            load(imported)
        }
    }
    val export = rememberLauncherForActivityResult(CreateDocument("application/json")) { uri ->
        if (uri != null) fileAction {
            val bytes = TextSystemPreset(name, content, post).validated().json().toString(2).encodeToByteArray()
            withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "无法写入预设文件" }.use { it.write(bytes) }
            }
        }
    }
    fun configured() = settings.copy(enabled = enabled, preferCharacterPrompt = preferMain, preferCharacterPostHistory = preferPost)
    BackHandler { if (!blocked) back() }
    Form {
        TextButton(back, enabled = !blocked) { Text("返回高级格式化") }
        FormHeading("文本补全系统提示", "用于 KoboldCPP 和 Ollama 文本补全。启用上下文模板后，系统提示通过 {{system}} 进入故事正文。")
        GroupToggle("启用系统提示和历史后指令", enabled, !blocked) { enabled = it }
        GroupToggle("优先使用角色卡的系统提示", preferMain, !blocked && enabled) { preferMain = it }
        GroupToggle("优先使用角色卡的历史后指令", preferPost, !blocked && enabled) { preferPost = it }
        val names = (presets.map(TextSystemPreset::name) + listOfNotNull(selected)).distinct()
        SelectField("系统提示预设", selected, listOf(null) + names, { it ?: "沿用现有提示词设置" }, !blocked) { choice ->
            load(presets.firstOrNull { it.name == choice } ?: if (choice == null) null else TextSystemPreset(name, content, post))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ import.launch(arrayOf("*/*")) }, enabled = !blocked) { Text("导入 JSON") }
            OutlinedButton({ export.launch("${safeFilename(name)}.json") }, enabled = !blocked && name.isNotBlank()) {
                Text("导出 JSON")
            }
        }
        Field("预设名称", name, { name = it.take(80) }, enabled = !blocked)
        Field("系统提示", content, { if (it.length <= SYSTEM_PROMPT_LIMIT) content = it }, lines = 5, enabled = !blocked)
        Field("历史后指令", post, { if (it.length <= SYSTEM_PROMPT_LIMIT) post = it }, lines = 3, enabled = !blocked)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button({
            runCatching {
                val draft = if (name.isBlank() && content.isBlank() && post.isBlank()) configured().copy(selected = null)
                    else {
                        val chosen = TextSystemPreset(name, content, post).validated()
                        if (chosen in builtIns && settings.custom.none { it.name == chosen.name }) configured().copy(selected = chosen)
                        else configured().saving(chosen)
                    }
                save(draft, back)
            }.onFailure { error = it.message }
        }, enabled = !blocked, modifier = Modifier.fillMaxWidth()) { Text("保存系统提示设置") }
        if (settings.custom.any { it.name == selected }) {
            TextButton({ remove = true }, enabled = !blocked) { Text("删除此自定义预设") }
        }
    }
    val removeName = selected
    if (remove && removeName != null) {
        Confirm("删除自定义预设？", "将删除「$removeName」保存的自定义版本。", { remove = false }) {
            remove = false
            val builtIn = builtIns.firstOrNull { it.name == removeName }
            save(configured().copy(
                selected = if (settings.selected?.name == removeName) builtIn else settings.selected,
                custom = settings.custom.filterNot { it.name == removeName },
            )) { load(builtIn) }
        }
    }
}

@Composable
private fun CustomStopsScreen(
    settings: CustomStoppingStrings, busy: Boolean, back: () -> Unit, save: (CustomStoppingStrings, () -> Unit) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(JSONArray(settings.values).toString(2)) }
    var macros by rememberSaveable { mutableStateOf(settings.macros) }
    var error by remember { mutableStateOf<String?>(null) }
    Form {
        TextButton(back, enabled = !busy) { Text("返回高级格式化") }
        FormHeading("自定义停止序列", "适用于聊天补全和文本补全。匹配的序列及其后续内容会从回复中裁去。")
        Field("停止序列 JSON", text, { text = it; error = null }, lines = 5, enabled = !busy,
            placeholder = """["<END>", "\n{{user}}:"]""")
        GroupToggle("展开停止序列中的宏", macros, !busy) { macros = it }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button({
            runCatching { CustomStoppingStrings.parse(text, macros) }
                .onSuccess { save(it, back) }.onFailure { error = it.message }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("保存停止序列") }
    }
}
