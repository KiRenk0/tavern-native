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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun NovelAiSettingsScreen(
    settings: NovelAiSettings, initialModel: NovelAiModel?, back: () -> Unit, apply: (NovelAiSettings) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bundled = remember(context.assets) {
        context.assets.open("novelai-presets.json").bufferedReader().use { JSONObject(it.readText()) }
    }
    var preamble by rememberSaveable { mutableStateOf(settings.preamble) }
    var model by rememberSaveable { mutableStateOf(initialModel ?: NovelAiModel.ERATO) }
    var drafts by remember { mutableStateOf(settings.presets.mapValues { it.value.json().toString(2) }) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val source = drafts[model] ?: NovelAiDefaults.presets.getValue(model).json().toString(2)
    val label = remember(source) { runCatching { JSONObject(source).string("name") }.getOrDefault("") }
    val names = remember(bundled, model) {
        bundled.keys().asSequence().filter { it.startsWith(model.label + "-") || it.endsWith("-" + model.label) }
            .sorted().toList()
    }
    fun update(target: NovelAiModel, value: String?) {
        drafts = if (value == null) drafts - target else drafts + (target to value)
        error = null
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
                error = e.message ?: "无法读取或保存预设"
            } finally {
                busy = false
            }
        }
    }
    val importer = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) fileAction {
            val target = model
            val preset = withContext(Dispatchers.IO) {
                val name = context.documentName(uri).substringBeforeLast('.').take(120)
                val bytes = requireNotNull(context.contentResolver.openInputStream(uri)) { "无法打开文件" }
                    .use { it.readLimited(NOVEL_AI_PRESET_LIMIT) }
                NovelAiPreset.parse(JSONObject(bytes.decodeToString().removePrefix("\uFEFF")), name)
            }
            update(target, preset.json().toString(2))
        }
    }
    val exporter = rememberLauncherForActivityResult(CreateDocument("application/json")) { uri ->
        if (uri != null) fileAction {
            val bytes = NovelAiPreset.parse(JSONObject(source)).json().toString(2).encodeToByteArray()
            withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "无法写入文件" }.use { it.write(bytes) }
            }
        }
    }

    BackHandler { if (!busy) back() }
    Form {
        TextButton(back, enabled = !busy) { Text("返回提供商") }
        FormHeading("NovelAI 采样预设", "为各模型保存独立预设。聊天中切换模型时会一起切换采样参数。")
        Field("聊天前导文本", preamble, { preamble = it.take(16_384) }, lines = 3, enabled = !busy)
        SelectField("模型", model, NovelAiModel.entries, NovelAiModel::label, !busy) { model = it; error = null }
        SelectField("原版预设", label.takeIf { it in names }.orEmpty(), listOf("") + names,
            { it.ifEmpty { "选择预设" } }, !busy) { name ->
            if (name.isNotEmpty()) {
                val preset = NovelAiPreset.parse(bundled.getJSONObject(name), name)
                update(model, preset.json().toString(2))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                enabled = !busy) { Text("导入 JSON") }
            OutlinedButton({ exporter.launch(label.ifBlank { model.id } + ".json") }, enabled = !busy) { Text("导出 JSON") }
        }
        TextButton({ advanced = !advanced }, enabled = !busy) {
            Text(if (advanced) "收起采样参数" else "编辑采样参数 JSON")
        }
        if (advanced) Field("采样参数", source, { update(model, it.take(NOVEL_AI_PRESET_LIMIT)) },
            lines = 10, enabled = !busy)
        if (model in drafts) {
            TextButton({ update(model, null) }, enabled = !busy) { Text("恢复此模型默认值") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button({
            runCatching {
                drafts.mapValues { (id, value) ->
                    runCatching { NovelAiPreset.parse(JSONObject(value)) }.getOrElse {
                        error("${id.label} · ${it.message ?: "预设无法解析"}")
                    }
                }
            }.onSuccess { apply(settings.copy(preamble = preamble, presets = it)) }
                .onFailure { error = it.message }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("应用到提供商") }
    }
}
