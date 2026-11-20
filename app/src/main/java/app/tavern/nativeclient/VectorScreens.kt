package app.tavern.nativeclient

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.json.JSONObject

private val vectorSaver = Saver<VectorPreferences, String>(save = { it.json().toString() }, restore = { VectorPreferences.from(JSONObject(it)) })

@Composable
internal fun VectorSettings(state: TavernState, model: TavernViewModel, close: () -> Unit) {
    val current = state.prefs.vectors
    var options by rememberSaveable(current, stateSaver = vectorSaver) { mutableStateOf(current) }
    var queries by rememberSaveable(current.queryMessages) { mutableStateOf(current.queryMessages.toString()) }
    var threshold by rememberSaveable(current.threshold) { mutableStateOf(current.threshold.toString()) }
    var worldLimit by rememberSaveable(current.worldLimit) { mutableStateOf(current.worldLimit.toString()) }
    var chatLimit by rememberSaveable(current.chatLimit) { mutableStateOf(current.chatLimit.toString()) }
    var retained by rememberSaveable(current.retained) { mutableStateOf(current.retained.toString()) }
    var chunkLength by rememberSaveable(current.chunkLength) { mutableStateOf(current.chunkLength.toString()) }
    var depth by rememberSaveable(current.depth) { mutableStateOf(current.depth.toString()) }
    var attachmentThreshold by rememberSaveable(current.messageFiles.thresholdKb) { mutableStateOf(current.messageFiles.thresholdKb.toString()) }
    var attachmentChunk by rememberSaveable(current.messageFiles.chunkLength) { mutableStateOf(current.messageFiles.chunkLength.toString()) }
    var attachmentOverlap by rememberSaveable(current.messageFiles.overlap) { mutableStateOf(current.messageFiles.overlap.toString()) }
    var attachmentLimit by rememberSaveable(current.messageFiles.limit) { mutableStateOf(current.messageFiles.limit.toString()) }
    val enabled = !state.busy
    val providers = state.prefs.providers.filter(::supportsEmbeddings)
    val connection = state.vectorSource(options)
    val localSelected = options.providerId == LOCAL_EMBEDDING_SOURCE_ID
    var requestedModel by rememberSaveable { mutableStateOf<LocalEmbeddingModel?>(null) }
    val importModel = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        val selected = requestedModel
        requestedModel = null
        if (uri != null && selected != null) model.importEmbeddingModel(selected, uri)
    }
    val attachmentReady = !options.messageFiles.enabled || (attachmentThreshold.toIntOrNull() in 0..8 &&
        attachmentChunk.toIntOrNull() in 80..1600 && attachmentOverlap.toIntOrNull() in 0..50 && attachmentLimit.toIntOrNull() in 1..20)
    val ready = attachmentReady && queries.toIntOrNull() in 1..10 && threshold.toFloatOrNull()?.let { it in 0f..1f } == true &&
        worldLimit.toIntOrNull() in 1..30 && chatLimit.toIntOrNull() in 1..20 && retained.toIntOrNull() in 0..80 &&
        chunkLength.toIntOrNull() in 80..1600 && depth.toIntOrNull() in 0..80
    BackHandler { if (enabled) close() }
    Form {
        FormHeading("向量检索")
        SelectField("向量服务", options.providerId, listOf("", LOCAL_EMBEDDING_SOURCE_ID) + providers.map(Provider::id), { id ->
            when (id) {
                "" -> "选择向量服务"
                LOCAL_EMBEDDING_SOURCE_ID -> "手机本地（ONNX）"
                else -> providers.firstOrNull { it.id == id }?.name ?: "服务不可用"
            }
        }, enabled) { id -> options = options.copy(providerId = id, model = "") }
        if (localSelected) {
            val selected = (options.source(providers) as? EmbeddingSource.Local)?.model ?: LocalEmbeddingModel.JINA_EN
            val installed = selected in state.installedEmbeddingModels
            SelectField("本地嵌入模型", selected, LocalEmbeddingModel.entries, LocalEmbeddingModel::label, enabled) {
                options = options.copy(model = it.id)
            }
            Text(if (installed) "模型已安装。向量计算在手机上离线完成，召回片段会随聊天请求交给当前对话模型。"
                else "下载或导入模型文件即可离线计算向量，支持世界书、聊天和文档。", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ model.downloadEmbeddingModel(selected) }, enabled = enabled) {
                    Text(if (installed) "重新下载" else "下载模型")
                }
                OutlinedButton({
                    requestedModel = selected
                    importModel.launch(arrayOf("*/*"))
                }, enabled = enabled) { Text("从文件导入") }
            }
            if (installed) TextButton({ model.removeEmbeddingModel(selected) }, enabled = enabled) { Text("移除模型") }
        } else {
            val google = providers.firstOrNull { it.id == options.providerId }?.template == ProviderTemplate.GOOGLE
            Field("向量模型 ID", options.model, { options = options.copy(model = it) }, enabled = enabled,
                placeholder = if (google) DEFAULT_EMBEDDING_MODEL else "服务支持的 embedding 模型")
            if (providers.isEmpty()) Text("可选择手机本地模型，或配置 Google Gemini 及支持 /embeddings 的服务。",
                style = MaterialTheme.typography.bodyMedium)
            Text("启用远程向量服务后，用于查询和索引的文本会发送给该服务。", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton({ model.testVectorPreferences(options) }, enabled = enabled && connection != null) { Text("测试向量服务") }
        state.utility?.let { label ->
            val percent = state.localEmbeddingProgress
            if (percent == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
            Text(if (percent == null) label else "$label $percent%")
            TextButton(model::stop) { Text("停止") }
        }
        Field("查询使用最近几条消息（1–10）", queries, { queries = it }, enabled = enabled, type = KeyboardType.Number)
        Field("最低相似度（0–1）", threshold, { threshold = it }, enabled = enabled, type = KeyboardType.Decimal)
        Field("片段长度（80–1600 字符）", chunkLength, { chunkLength = it }, enabled = enabled, type = KeyboardType.Number)
        Field("自定义切分符（可选）", options.boundary, { options = options.copy(boundary = it) }, enabled = enabled)
        HorizontalDivider()
        FormHeading("世界书")
        GroupToggle("启用世界书向量匹配", options.worldEnabled, enabled) { options = options.copy(worldEnabled = it) }
        if (options.worldEnabled) {
            GroupToggle("匹配所有合适的世界书条目", options.worldAll, enabled) { options = options.copy(worldAll = it) }
            Field("最多匹配条目数（1–30）", worldLimit, { worldLimit = it }, enabled = enabled, type = KeyboardType.Number)
        }
        HorizontalDivider()
        FormHeading("聊天召回")
        GroupToggle("启用较早消息召回", options.chatEnabled, enabled) { options = options.copy(chatEnabled = it) }
        if (options.chatEnabled) {
            Field("最多召回消息数（1–20）", chatLimit, { chatLimit = it }, enabled = enabled, type = KeyboardType.Number)
            Field("保留在原位的最近消息数（0–80）", retained, { retained = it }, enabled = enabled, type = KeyboardType.Number)
            SelectField("注入位置", options.position, MemoryPosition.entries, MemoryPosition::label, enabled) { options = options.copy(position = it) }
            if (options.position == MemoryPosition.DEPTH) {
                SelectField("注入角色", options.role, PromptRole.entries, PromptRole::label, enabled) { options = options.copy(role = it) }
                Field("注入深度（0–80）", depth, { depth = it }, enabled = enabled, type = KeyboardType.Number)
            }
            Field("注入模板（使用 {{text}}）", options.template, { options = options.copy(template = it) }, lines = 2, enabled = enabled)
        }
        HorizontalDivider()
        FormHeading("消息文档")
        GroupToggle("检索较大文档中的相关片段", options.messageFiles.enabled, enabled) {
            options = options.copy(messageFiles = options.messageFiles.copy(enabled = it))
        }
        if (options.messageFiles.enabled) {
            Field("超过多大时检索（0–8 KB）", attachmentThreshold, { attachmentThreshold = it }, enabled = enabled, type = KeyboardType.Number)
            Field("片段长度（80–1600 字符）", attachmentChunk, { attachmentChunk = it }, enabled = enabled, type = KeyboardType.Number)
            Field("相邻片段重叠（0–50%）", attachmentOverlap, { attachmentOverlap = it }, enabled = enabled, type = KeyboardType.Number)
            Field("每份文档召回片段数（1–20）", attachmentLimit, { attachmentLimit = it }, enabled = enabled, type = KeyboardType.Number)
        }
        Button({ model.saveVectorPreferences(options.copy(model = options.model.trim(), queryMessages = queries.toInt(),
            threshold = threshold.toFloat(), worldLimit = worldLimit.toInt(), chatLimit = chatLimit.toInt(), retained = retained.toInt(),
            chunkLength = chunkLength.toInt(), depth = depth.toInt(), messageFiles = if (options.messageFiles.enabled) {
                MessageFileSettings(true, attachmentThreshold.toInt(), attachmentChunk.toInt(), attachmentOverlap.toInt(), attachmentLimit.toInt())
            } else options.messageFiles), close) },
            enabled = enabled && ready && (!options.enabled || connection != null), modifier = Modifier.fillMaxWidth()) { Text("保存向量设置") }
    }
}

@Composable
internal fun VectorIndexDialog(state: TavernState, model: TavernViewModel, close: () -> Unit, settings: () -> Unit) {
    val config = state.prefs.vectors
    val configured = config.enabled && state.vectorSource() != null
    val enabled = !state.busy
    AlertDialog(onDismissRequest = { if (enabled) close() }, title = { Text("向量索引") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!config.enabled) Text("请在设置中开启聊天召回或世界书向量匹配。")
            else if (!configured) Text("请在设置中配置向量服务或安装本地嵌入模型。")
            else TextButton(model::showVectorCounts, enabled = enabled) { Text("查看索引数量") }
            TextButton({ model.clearVectorIndex(world = false) }, enabled = enabled) { Text("清除当前聊天索引") }
            if (state.chat?.messages?.any { it.documents.isNotEmpty() } == true) {
                TextButton(model::clearChatAttachmentIndex, enabled = enabled) { Text("清除消息文档索引") }
            }
            TextButton({ model.clearVectorIndex(world = true) }, enabled = enabled) { Text("清除世界书索引") }
            state.utility?.let { label ->
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(label)
            }
        }
    }, dismissButton = { TextButton(if (state.utility != null) model::stop else close, enabled = enabled || state.utility != null) {
        Text(if (state.utility != null) "停止" else "关闭")
    } }, confirmButton = {
        if (configured) Button(model::indexVectors, enabled = enabled) { Text("建立索引") }
        else Button({ close(); settings() }, enabled = enabled) { Text("打开设置") }
    })
}
