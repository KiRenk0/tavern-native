package app.tavern.nativeclient

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun TokenBiasScreen(provider: Provider, back: () -> Unit, apply: (TokenBiasSettings) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(provider.tokenBias) }
    var target by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<TokenBiasPreset?>(null) }
    var managing by remember { mutableStateOf(draft.presets.firstOrNull()?.id.orEmpty()) }
    var exporting by remember { mutableStateOf<TokenBiasPreset?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<String?>(null) }
    val preset = draft.presets.firstOrNull { it.id == managing }
    val modelIds = (provider.choices.map(String::trim).filter(String::isNotEmpty) + draft.byModel.keys).distinct()
    val selection = target?.let { draft.byModel[it] } ?: draft.default
    val targetModel = target ?: provider.defaultModel.trim()

    fun change(value: TokenBiasSettings) {
        draft = value
        preview = null
        error = null
    }

    fun runAction(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.localizedMessage ?: "无法处理词元偏置"
            } finally {
                busy = false
            }
        }
    }

    val importFile = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) runAction {
            val imported = withContext(Dispatchers.IO) {
                val name = context.documentName(uri).substringBeforeLast('.').trim().take(100).ifBlank { "导入的词元偏置" }
                val bytes = requireNotNull(context.contentResolver.openInputStream(uri)) { "无法打开预设文件" }
                    .use { it.readLimited(1024 * 1024) }
                TokenBiasPreset.fromOriginal(bytes.decodeToString(), name)
            }
            editing = imported
        }
    }
    val exportFile = rememberLauncherForActivityResult(CreateDocument("application/json")) { uri ->
        val value = exporting
        exporting = null
        if (uri != null && value != null) runAction {
            withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openOutputStream(uri)) { "无法写入预设文件" }.use {
                    it.write(value.originalJson().toString(2).encodeToByteArray())
                }
            }
        }
    }
    editing?.let { active ->
        key(active.id) {
            TokenBiasPresetEditor(active, provider.usesTextCompletion, close = { editing = null }, save = {
                val presets = if (draft.presets.any { row -> row.id == it.id }) {
                    draft.presets.map { row -> if (row.id == it.id) it else row }
                } else draft.presets + it
                change(draft.copy(presets = presets))
                managing = it.id
                editing = null
            }, delete = {
                change(draft.remove(active.id))
                managing = draft.presets.firstOrNull()?.id.orEmpty()
                editing = null
            }.takeIf { draft.presets.any { it.id == active.id } })
        }
        return
    }

    BackHandler { if (!busy) back() }
    Form {
        TextButton(back, enabled = !busy) { Text("返回提供商设置") }
        FormHeading("词元偏置", "负值降低词元出现的概率，正值提高概率。模型切换后会使用对应的预设和分词器。")
        SelectField("作用范围", target, listOf(null) + modelIds, { it ?: "提供商默认" }, !busy) {
            target = it
            preview = null
            error = null
        }
        val binding = if (target != null && target !in draft.byModel) null else selection.presetId
        val choices = buildList {
            if (target != null) add(null)
            add("")
            addAll(draft.presets.map(TokenBiasPreset::id))
        }
        SelectField("生效预设", binding, choices, {
            when (it) {
                null -> "继承提供商默认"
                "" -> "关闭"
                else -> draft.presets.firstOrNull { preset -> preset.id == it }?.name ?: "预设已删除"
            }
        }, !busy) {
            change(draft.select(target, it?.let { id -> TokenBiasSelection(id, selection.tokenizer) }))
        }
        if (selection.presetId.isNotEmpty()) {
            SelectField("分词器", selection.tokenizer, BiasTokenizer.entries, BiasTokenizer::label, !busy && binding != null) {
                change(draft.select(target, selection.copy(tokenizer = it)))
            }
            OutlinedButton({
                preview = null
                runAction {
                    val connection = ModelConnection(provider.copy(tokenBias = draft), targetModel)
                    val result = connection.compileTokenBias(selection)
                    preview = when {
                        result == null -> "此预设没有生效的 Token ID"
                        result.length() > 40 -> "已生成 ${result.length()} 个 Token ID"
                        else -> result.keys().asSequence().joinToString("   ") { id -> "$id → ${result.get(id)}" }
                    }
                }
            }, enabled = !busy && targetModel.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text("预览 $targetModel 的 Token ID")
            }
        }
        if (preview != null) Text(preview.orEmpty(), style = MaterialTheme.typography.bodySmall)
        if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
        HorizontalDivider()
        FormHeading("管理预设")
        if (preset != null) {
            SelectField("待编辑预设", preset, draft.presets, TokenBiasPreset::name, !busy) { managing = it.id }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ editing = preset }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("编辑") }
                OutlinedButton({
                    exporting = preset
                    exportFile.launch("${safeFilename(preset.name)}.json")
                }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("导出 JSON") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ editing = TokenBiasPreset(newId(), "", listOf(TokenBiasRow(newId()))) },
                enabled = !busy, modifier = Modifier.weight(1f)) { Text("新建预设") }
            OutlinedButton({ importFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                enabled = !busy, modifier = Modifier.weight(1f)) { Text("导入 JSON") }
        }
        Button({ apply(draft) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("应用词元偏置") }
    }
}

private data class BiasRowDraft(val id: String, val text: String = "", val weight: String = "0")

@Composable
private fun TokenBiasPresetEditor(
    preset: TokenBiasPreset,
    textCompletion: Boolean,
    close: () -> Unit,
    save: (TokenBiasPreset) -> Unit,
    delete: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(preset.name) }
    var rows by remember { mutableStateOf(preset.rows.map { BiasRowDraft(it.id, it.text, it.value.toString()) }) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun change(index: Int, row: BiasRowDraft) {
        rows = rows.toMutableList().apply { this[index] = row }
        error = null
    }
    fun move(index: Int, delta: Int) {
        rows = rows.toMutableList().apply { add(index + delta, removeAt(index)) }
    }

    BackHandler(onBack = close)
    Form {
        TextButton(close) { Text("返回词元偏置") }
        FormHeading("编辑词元偏置预设", if (textCompletion) "普通词语会在前面加一个空格。用 {文本} 原样输入，或用 [123, 456] 指定 Token ID。"
            else "保留文本中的空格。也可以用 [123, 456] 指定 Token ID。")
        Field("预设名称", name, { name = it; error = null })
        rows.forEachIndexed { index, row ->
            key(row.id) {
                HorizontalDivider()
                Field("文本或 Token ID", row.text, { change(index, row.copy(text = it)) })
                Field("偏置（−100 至 100）", row.weight, { change(index, row.copy(weight = it)) })
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton({ move(index, -1) }, enabled = index > 0) { Text("上移") }
                    TextButton({ move(index, 1) }, enabled = index < rows.lastIndex) { Text("下移") }
                    TextButton({ rows = rows.filterNot { it.id == row.id }; error = null }) { Text("移除规则") }
                }
            }
        }
        OutlinedButton({ rows = rows + BiasRowDraft(newId()) }, enabled = rows.size < MAX_TOKEN_BIAS_ROWS,
            modifier = Modifier.fillMaxWidth()) { Text("添加规则") }
        if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
        Button({
            runCatching {
                preset.copy(name = name, rows = rows.filter { it.text.isNotEmpty() }.map {
                    TokenBiasRow(it.id, it.text, requireNotNull(it.weight.trim().toDoubleOrNull()) { "偏置应为数字" })
                }).validate()
            }.fold(save, { error = it.localizedMessage ?: "规则无效" })
        }, modifier = Modifier.fillMaxWidth()) { Text("保存预设") }
        if (delete != null) TextButton({ confirmDelete = true }) { Text("删除预设") }
    }
    if (confirmDelete && delete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除 ${preset.name}？") },
            text = { Text("使用此预设的模型将关闭词元偏置。") },
            confirmButton = { TextButton(delete) { Text("删除") } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("取消") } },
        )
    }
}
