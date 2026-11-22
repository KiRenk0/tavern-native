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
import androidx.compose.runtime.saveable.Saver
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
import org.json.JSONObject

private val contextSaver = Saver<TextContextTemplate, String>(
    save = { it.json().toString() },
    restore = { TextContextTemplate.fromJson(JSONObject(it), "自定义上下文", checkStory = false) },
)

@Composable
internal fun TextContextTemplateScreen(
    current: TextContextTemplate?, back: () -> Unit, apply: (TextContextTemplate?, InstructTemplate?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val presets = remember(context.assets) {
        context.assets.open("context-presets.json").bufferedReader().use { reader ->
            JSONArray(reader.readText()).objects().map { TextContextTemplate.fromJson(it, checkStory = false) }
                .sortedBy { it.name.lowercase() }
        }
    }
    val instructs = remember(context.assets) {
        context.assets.open("instruct-presets.json").bufferedReader().use { reader ->
            JSONArray(reader.readText()).objects().associateBy { it.string("name") }
        }
    }
    var draft by rememberSaveable(stateSaver = contextSaver) {
        mutableStateOf(current ?: presets.first { it.name == "Default" })
    }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var depth by rememberSaveable { mutableStateOf(draft.depth.toString()) }
    var matchInstruct by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val depthValue = depth.toIntOrNull()?.takeIf { it in 0..CONTEXT_DEPTH_LIMIT }
    val match = instructs[selected ?: draft.name]
    fun load(value: TextContextTemplate, presetName: String?) {
        draft = value
        depth = value.depth.toString()
        selected = presetName
        matchInstruct = false
    }
    fun fileAction(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "无法读取或保存模板"
            } finally {
                busy = false
            }
        }
    }
    var pendingExport by remember { mutableStateOf<TextContextTemplate?>(null) }
    val import = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) fileAction {
            val value = withContext(Dispatchers.IO) {
                val name = context.documentName(uri).substringBeforeLast('.').take(80).ifBlank { "导入的上下文模板" }
                val bytes = requireNotNull(context.contentResolver.openInputStream(uri)) { "无法打开模板文件" }
                    .use { it.readLimited(INSTRUCT_TEMPLATE_LIMIT) }
                TextContextTemplate.parse(bytes.decodeToString(), name)
            }
            load(value, null)
        }
    }
    val export = rememberLauncherForActivityResult(CreateDocument("application/json")) { uri ->
        val value = pendingExport
        pendingExport = null
        if (uri != null && value != null) fileAction {
            withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "无法写入模板文件" }
                    .use { it.write(value.json().toString(2).encodeToByteArray()) }
            }
        }
    }
    fun checked(onSuccess: (TextContextTemplate) -> Unit) = fileAction {
        val value = withContext(Dispatchers.Default) {
            draft.copy(depth = requireNotNull(depthValue) { "请输入有效的注入深度" }).validated()
        }
        onSuccess(value)
    }
    BackHandler { if (!busy) back() }
    Form {
        TextButton(back, enabled = !busy) { Text("返回提供商") }
        FormHeading("文本补全上下文模板", "设置角色设定、世界书、对话示例及其注入位置。应用模板后保存提供商。")
        SelectField("载入内置预设", selected, listOf(null) + presets.map(TextContextTemplate::name),
            { it ?: "选择预设" }, !busy) { name ->
            presets.firstOrNull { it.name == name }?.let { load(it, name) }
            if (name == null) selected = null
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ import.launch(arrayOf("*/*")) }, enabled = !busy) { Text("导入 JSON") }
            OutlinedButton({
                checked {
                    pendingExport = it
                    export.launch("${safeFilename(it.name)}.json")
                }
            }, enabled = !busy && depthValue != null && draft.name.isNotBlank()) { Text("导出 JSON") }
        }
        Field("模板名称", draft.name, { draft = draft.copy(name = it.take(80)) }, enabled = !busy)
        Field("故事正文模板", draft.story, { if (it.length <= CONTEXT_STORY_LIMIT) draft = draft.copy(story = it) },
            lines = 6, enabled = !busy)
        GroupToggle("将故事正文放在聊天历史内", draft.inChat, !busy) { draft = draft.copy(inChat = it) }
        if (draft.inChat) {
            Field("故事正文的注入深度（0 是最新）", depth, { depth = it.take(5) }, enabled = !busy, type = KeyboardType.Number)
            SelectField("故事正文的消息角色", draft.role, PromptRole.entries, PromptRole::label, !busy) {
                draft = draft.copy(role = it)
            }
        }
        Field("示例对话分隔符", draft.exampleSeparator, {
            if (it.length <= CONTEXT_SEPARATOR_LIMIT) draft = draft.copy(exampleSeparator = it)
        }, lines = 2, enabled = !busy)
        Field("聊天开始分隔符", draft.chatStart, {
            if (it.length <= CONTEXT_SEPARATOR_LIMIT) draft = draft.copy(chatStart = it)
        }, lines = 2, enabled = !busy)
        GroupToggle("使用分隔符作为停止序列", draft.separatorsAsStops, !busy) { draft = draft.copy(separatorsAsStops = it) }
        GroupToggle("使用其他发言者的名称作为停止序列", draft.namesAsStops, !busy) { draft = draft.copy(namesAsStops = it) }
        GroupToggle("纯文本补全时在末尾加上角色名", draft.forceCharacterName, !busy) {
            draft = draft.copy(forceCharacterName = it)
        }
        GroupToggle("省略故事正文之外的对话示例", draft.skipExamples, !busy) { draft = draft.copy(skipExamples = it) }
        GroupToggle("单行回复", draft.singleLine, !busy) { draft = draft.copy(singleLine = it) }
        GroupToggle("回复结束后裁去未写完的句子", draft.trimSentences, !busy) { draft = draft.copy(trimSentences = it) }
        if (match != null) GroupToggle("同时应用同名指令模板", matchInstruct, !busy) { matchInstruct = it }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button({
            checked { value -> apply(value, match?.takeIf { matchInstruct }?.let { InstructTemplate.fromJson(it) }) }
        }, enabled = !busy && depthValue != null && draft.name.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("应用此模板")
        }
        if (current != null) TextButton({ apply(null, null) }, enabled = !busy) { Text("停用上下文模板") }
    }
}
