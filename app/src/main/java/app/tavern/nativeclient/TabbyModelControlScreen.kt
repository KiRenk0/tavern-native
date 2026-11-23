package app.tavern.nativeclient

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Eject
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val tabbyCacheModes = listOf("FP16", "Q8", "Q6", "Q4")

@Composable
internal fun TabbyModelControlDialog(
    provider: Provider,
    onDismiss: () -> Unit,
    onModelLoaded: (modelName: String, maxSeqLen: Int?) -> Unit = { _, _ -> },
) {
    var status by remember { mutableStateOf<TabbyModelStatus?>(null) }
    var availableModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedModel by remember { mutableStateOf(provider.defaultModel.takeUnless { it == "default" }.orEmpty()) }
    var maxSeqLenText by remember { mutableStateOf(provider.contextLimit?.toString().orEmpty()) }
    var cacheMode by remember { mutableStateOf("FP16") }
    var gpuSplitAuto by remember { mutableStateOf(true) }
    var gpuSplitText by remember { mutableStateOf("") }
    var ropeScaleText by remember { mutableStateOf("") }
    var ropeAlphaText by remember { mutableStateOf("") }
    var chunkSizeText by remember { mutableStateOf("") }
    var draftModelName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        if (busy || provider.endpoint.isBlank()) return
        busy = true
        errorText = null
        scope.launch {
            try {
                val current = try {
                    TabbyModelClient.fetchStatus(provider)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    TabbyModelStatus(null)
                }
                status = current
                if (selectedModel.isBlank() && !current.loadedModel.isNullOrBlank()) {
                    selectedModel = current.loadedModel
                }
                val list = TabbyModelClient.listModels(provider)
                availableModels = list
                if (selectedModel.isBlank() && list.isNotEmpty()) {
                    selectedModel = list.first()
                }
                message = "已刷新 TabbyAPI 状态（可用模型 ${list.size} 个）"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorText = e.localizedMessage ?: "无法连接 TabbyAPI 服务"
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(provider.endpoint, provider.apiKey) {
        if (provider.endpoint.isNotBlank()) refresh()
    }

    val maxSeqLen = maxSeqLenText.trim().toIntOrNull()?.takeIf { it in 256..MAX_CONTEXT_TOKENS }
    val ropeScale = ropeScaleText.trim().toDoubleOrNull()?.takeIf { it > 0.0 }
    val ropeAlpha = ropeAlphaText.trim().toDoubleOrNull()?.takeIf { it > 0.0 }
    val chunkSize = chunkSizeText.trim().toIntOrNull()?.takeIf { it > 0 }
    val gpuSplit = gpuSplitText.split(',', '，', ' ')
        .map(String::trim)
        .filter(String::isNotBlank)
        .mapNotNull(String::toFloatOrNull)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Memory, null, tint = MaterialTheme.colorScheme.primary)
                Text("TabbyAPI 模型控制")
            }
        },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("当前已加载模型", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            status?.loadedModel ?: "未加载模型",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        val meta = listOfNotNull(
                            status?.maxSeqLen?.let { "上下文 $it" },
                            status?.cacheMode?.let { "KV 缓存 $it" },
                            status?.loraNames?.takeIf { it.isNotEmpty() }?.let { "LoRA: ${it.joinToString(", ")}" },
                        )
                        if (meta.isNotEmpty()) {
                            Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = ::refresh,
                        enabled = !busy && provider.endpoint.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("刷新状态")
                    }
                    OutlinedButton(
                        onClick = {
                            busy = true
                            errorText = null
                            message = null
                            scope.launch {
                                try {
                                    val res = TabbyModelClient.unloadModel(provider)
                                    status = TabbyModelStatus(null)
                                    message = res
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    errorText = e.localizedMessage ?: "卸载模型失败"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy && provider.endpoint.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.Eject, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("卸载模型")
                    }
                }

                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("正在与 TabbyAPI 通信…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                errorText?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }

                HorizontalDivider()
                Text("加载模型（/v1/model/load）", style = MaterialTheme.typography.titleSmall)

                if (availableModels.isNotEmpty()) {
                    val choices = (availableModels + selectedModel).filter(String::isNotBlank).distinct()
                    SelectField(
                        "从服务端模型列表选择",
                        selectedModel.ifBlank { choices.first() },
                        choices,
                        { it },
                        enabled = !busy,
                    ) { selectedModel = it }
                }
                Field("模型目录 / 名称", selectedModel, { selectedModel = it }, enabled = !busy, placeholder = "例如：Mistral-Nemo-Instruct-exl2")
                Field("最大序列长度 max_seq_len（可选）", maxSeqLenText, { maxSeqLenText = it }, enabled = !busy, placeholder = "留空采用模型默认", type = KeyboardType.Number)
                SelectField("KV 缓存量化（cache_mode）", cacheMode, tabbyCacheModes, { it }, enabled = !busy) { cacheMode = it }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("自动分配显存（gpu_split_auto）", Modifier.weight(1f))
                    Switch(gpuSplitAuto, { gpuSplitAuto = it }, enabled = !busy)
                }
                if (!gpuSplitAuto) {
                    Field("手动显存分配（GB，逗号分隔）", gpuSplitText, { gpuSplitText = it }, enabled = !busy, placeholder = "例如：12, 12")
                }
                Field("RoPE Scale（可选）", ropeScaleText, { ropeScaleText = it }, enabled = !busy, placeholder = "例如：1.0", type = KeyboardType.Decimal)
                Field("RoPE Alpha（可选）", ropeAlphaText, { ropeAlphaText = it }, enabled = !busy, placeholder = "例如：1.0", type = KeyboardType.Decimal)
                Field("Chunk Size（可选）", chunkSizeText, { chunkSizeText = it }, enabled = !busy, placeholder = "例如：2048", type = KeyboardType.Number)
                Field("Draft Model（推测解码，可选）", draftModelName, { draftModelName = it }, enabled = !busy, placeholder = "留空不启用")

                Button(
                    onClick = {
                        busy = true
                        errorText = null
                        message = null
                        scope.launch {
                            try {
                                val req = TabbyLoadRequest(
                                    modelName = selectedModel.trim(),
                                    maxSeqLen = maxSeqLen,
                                    cacheMode = cacheMode,
                                    gpuSplitAuto = gpuSplitAuto,
                                    gpuSplit = gpuSplit,
                                    ropeScale = ropeScale,
                                    ropeAlpha = ropeAlpha,
                                    chunkSize = chunkSize,
                                    draftModelName = draftModelName.trim(),
                                )
                                val res = TabbyModelClient.loadModel(provider, req)
                                val updated = runCatching { TabbyModelClient.fetchStatus(provider) }.getOrNull()
                                    ?: TabbyModelStatus(req.modelName, req.maxSeqLen, req.cacheMode)
                                status = updated
                                message = res
                                onModelLoaded(updated.loadedModel ?: req.modelName, updated.maxSeqLen ?: req.maxSeqLen)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                errorText = e.localizedMessage ?: "加载模型失败"
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy && selectedModel.isNotBlank() && provider.endpoint.isNotBlank() &&
                        (maxSeqLenText.isBlank() || maxSeqLen != null),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.CloudDownload, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("加载选定模型")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭") }
        },
    )
}
