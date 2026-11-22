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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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

private data class BundledTextSampler(val source: String, val preset: TextSamplerPreset)

@Composable
internal fun TextSamplingScreen(provider: Provider, back: () -> Unit, apply: (TextSamplingSettings) -> Unit) {
    val settings = provider.textSampling
    var target by remember { mutableStateOf<String?>(null) }
    val targets = (provider.choices.map(String::trim).filter(String::isNotBlank) + settings.byModel.keys).distinct()
    val modelId = target
    val stored = if (modelId == null) settings.default else settings.byModel[modelId]
    key(modelId) {
        TextSamplerEditor(modelId, targets, stored, settings.default, back, { target = it },
            apply = { preset ->
                val next = if (modelId == null) settings.copy(default = preset)
                    else if (preset != null) settings.copy(byModel = settings.byModel + (modelId to preset))
                    else settings.copy(byModel = settings.byModel - modelId)
                apply(next)
            })
    }
}

@Composable
private fun TextSamplerEditor(
    target: String?, targets: List<String>, stored: TextSamplerPreset?, fallback: TextSamplerPreset?,
    back: () -> Unit, chooseTarget: (String?) -> Unit, apply: (TextSamplerPreset?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bundled = remember(context.assets) {
        context.assets.open("text-sampling-presets.json").bufferedReader().use { reader ->
            JSONArray(reader.readText()).objects().map {
                BundledTextSampler(it.getString("source"), TextSamplerPreset.read(it.getJSONObject("preset")))
            }
        }
    }
    var draft by remember { mutableStateOf(stored ?: TextSamplerPreset("自定义采样预设")) }
    var selected by remember { mutableStateOf<BundledTextSampler?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var advanced by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
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
                error = e.message ?: "无法读取或保存采样预设"
            } finally {
                busy = false
            }
        }
    }
    val importFile = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) fileAction {
            draft = withContext(Dispatchers.IO) {
                val filename = context.documentName(uri).substringBeforeLast('.').take(100).ifBlank { "导入的采样预设" }
                val bytes = requireNotNull(context.contentResolver.openInputStream(uri)) { "无法打开预设文件" }
                    .use { it.readLimited(TEXT_SAMPLER_FILE_LIMIT) }
                TextSamplerPreset.read(JSONObject(bytes.decodeToString().removePrefix("\uFEFF")), filename)
            }
            selected = null
        }
    }
    val exportFile = rememberLauncherForActivityResult(CreateDocument("application/json")) { uri ->
        if (uri != null) fileAction {
            val bytes = draft.json().toString(2).encodeToByteArray()
            withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "无法写入预设文件" }
                    .use { it.write(bytes) }
            }
        }
    }
    BackHandler { if (!busy) back() }
    Form {
        TextButton(back, enabled = !busy) { Text("返回提供商") }
        FormHeading("采样预设", "为提供商或指定模型设置采样参数。应用预设后，请保存提供商。")
        SelectField("应用范围", target, listOf(null) + targets, { it ?: "提供商默认" }, !busy, chooseTarget)
        if (target != null && stored == null) {
            Text(fallback?.let { "当前继承提供商预设「${it.name}」" } ?: "当前使用常规生成设置")
            if (fallback != null) TextButton(onClick = { draft = fallback; error = null }, enabled = !busy) {
                Text("复制提供商默认预设")
            }
        }
        SelectField("载入内置预设", selected, listOf(null) + bundled,
            { it?.let { preset -> "${preset.source} · ${preset.preset.name}" } ?: "选择预设" }, !busy) {
            selected = it
            if (it != null) draft = it.preset
            error = null
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ importFile.launch(arrayOf("*/*")) }, enabled = !busy) { Text("导入 JSON") }
            OutlinedButton({
                runCatching { draft.json() }.onSuccess { exportFile.launch("${safeFilename(draft.name)}.json") }
                    .onFailure { error = it.message }
            }, enabled = !busy && draft.fields.isNotEmpty()) { Text("导出 JSON") }
        }
        Field("预设名称", draft.name, { draft = draft.copy(name = it.take(100)); error = null }, enabled = !busy)
        val common = TextSamplerField.entries.takeWhile { it != TextSamplerField.TOP_A }
        val fields = if (advanced) TextSamplerField.entries else common
        fields.forEach { field ->
            val value = draft[field]
            fun update(input: String) {
                draft = draft.copy(fields = if (input.isBlank()) draft.fields - field
                    else draft.fields + (field to input))
                error = null
            }
            if (field.kind == SamplerValue.BOOLEAN) {
                SelectField(field.label, value, listOf("", "true", "false"), {
                    when (it) {
                        "true" -> "启用"
                        "false" -> "关闭"
                        else -> "服务端默认"
                    }
                }, !busy, ::update)
            } else {
                val multiline = field.kind in setOf(SamplerValue.ARRAY, SamplerValue.OBJECT, SamplerValue.TEXT)
                Field(field.label, value, { update(it.take(TEXT_SAMPLER_FILE_LIMIT)) }, enabled = !busy,
                    lines = if (multiline) 3 else 1, placeholder = "留空沿用原设置",
                    type = if (field.kind == SamplerValue.DECIMAL) KeyboardType.Decimal else KeyboardType.Text)
            }
        }
        TextButton({ advanced = !advanced }, enabled = !busy) {
            Text(if (advanced) "收起高级采样参数" else "显示高级采样参数")
        }
        Text("连接配置和全局生成设置中填写的数值优先。高级参数按后端支持情况发送。",
            style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button({
            runCatching { TextSamplerPreset.read(draft.json()) }.onSuccess { apply(it) }.onFailure { error = it.message }
        }, modifier = Modifier.fillMaxWidth(), enabled = !busy && draft.fields.isNotEmpty()) { Text("应用此预设") }
        if (stored != null) TextButton({ remove = true }, enabled = !busy) { Text("移除当前预设") }
    }
    if (remove) Confirm("移除当前采样预设？", "生成时会继续使用适用的默认设置。",
        { remove = false }, { apply(null) })
}
