package app.tavern.nativeclient

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

@Composable
internal fun InstructTemplateScreen(
    current: InstructTemplate?, back: () -> Unit, apply: (InstructTemplate?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val presets = remember(context.assets) {
        context.assets.open("instruct-presets.json").bufferedReader().use { reader ->
            JSONArray(reader.readText()).objects().map { InstructTemplate.fromJson(it) }
                .sortedBy { it.name.lowercase() }
        }
    }
    var selected by remember { mutableStateOf<InstructTemplate?>(null) }
    var draft by remember { mutableStateOf(current ?: InstructTemplate("自定义指令模板")) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var advanced by remember { mutableStateOf(false) }
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
    val import = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) fileAction {
            draft = withContext(Dispatchers.IO) {
                val filename = context.documentName(uri).substringBeforeLast('.').take(80).ifBlank { "导入的指令模板" }
                val bytes = requireNotNull(context.contentResolver.openInputStream(uri)) { "无法打开模板文件" }
                    .use { it.readLimited(INSTRUCT_TEMPLATE_LIMIT) }
                InstructTemplate.parse(bytes.decodeToString(), filename)
            }
            selected = null
        }
    }
    val export = rememberLauncherForActivityResult(CreateDocument("application/json")) { uri ->
        if (uri != null) fileAction {
            val bytes = draft.validated().json().toString(2).encodeToByteArray()
            withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "无法写入模板文件" }
                    .use { it.write(bytes) }
            }
        }
    }
    BackHandler { if (!busy) back() }
    Form {
        TextButton(back, enabled = !busy) { Text("返回提供商") }
        FormHeading("文本补全指令模板", "选择与服务端模型对应的格式。应用模板后保存提供商。")
        SelectField("载入内置预设", selected, listOf(null) + presets, { it?.name ?: "选择预设" }, !busy) {
            selected = it
            if (it != null) draft = it
            error = null
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ import.launch(arrayOf("*/*")) }, enabled = !busy) { Text("导入 JSON") }
            OutlinedButton({ export.launch("${safeFilename(draft.name)}.json") }, enabled = !busy && draft.name.isNotBlank()) {
                Text("导出 JSON")
            }
        }
        Field("模板名称", draft.name, { draft = draft.copy(name = it.take(80)) }, enabled = !busy)
        GroupToggle("换行分隔序列与正文", draft.wrap, !busy) { draft = draft.copy(wrap = it) }
        GroupToggle("展开序列中的宏", draft.macros, !busy) { draft = draft.copy(macros = it) }
        SelectField("保留发言者名称", draft.names, InstructNames.entries, InstructNames::label, !busy) {
            draft = draft.copy(names = it)
        }
        val common = listOf(InstructSequence.STORY_PREFIX, InstructSequence.STORY_SUFFIX, InstructSequence.USER_PREFIX,
            InstructSequence.USER_SUFFIX, InstructSequence.ASSISTANT_PREFIX, InstructSequence.ASSISTANT_SUFFIX,
            InstructSequence.STOP)
        TextButton({ advanced = !advanced }, enabled = !busy) { Text(if (advanced) "收起高级设置" else "显示高级设置") }
        if (advanced) {
            GroupToggle("把消息前缀也用作停止序列", draft.prefixesAsStops, !busy) { draft = draft.copy(prefixesAsStops = it) }
            GroupToggle("系统消息沿用用户格式", draft.systemAsUser, !busy) { draft = draft.copy(systemAsUser = it) }
            GroupToggle("跳过对话示例", draft.skipExamples, !busy) { draft = draft.copy(skipExamples = it) }
        }
        val fields = if (advanced) InstructSequence.entries else common
        fields.forEach { sequence ->
            Field(sequence.label, draft[sequence], { value ->
                if (value.length <= INSTRUCT_SEQUENCE_LIMIT) {
                    draft = draft.copy(sequences = draft.sequences + (sequence to value))
                }
            }, lines = 2, enabled = !busy)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button({
            runCatching { draft.validated() }.onSuccess { apply(it) }
                .onFailure { error = it.message ?: "模板设置无效" }
        }, enabled = !busy && draft.name.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("应用此模板")
        }
        if (current != null) TextButton({ apply(null) }, enabled = !busy) { Text("停用指令模板") }
    }
}
