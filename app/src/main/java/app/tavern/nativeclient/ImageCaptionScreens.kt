package app.tavern.nativeclient

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

private val captionSaver = Saver<CaptionSettings, String>(save = { it.json().toString() },
    restore = { CaptionSettings.from(JSONObject(it)) })

@Composable
internal fun CaptionSettingsScreen(state: TavernState, model: TavernViewModel, close: () -> Unit) {
    val saved = state.prefs.captions
    var options by rememberSaveable(saved, stateSaver = captionSaver) { mutableStateOf(saved) }
    val enabled = !state.busy
    val providers = state.prefs.providers.filter(::supportsImageCaptions)
    val importModel = rememberLauncherForActivityResult(OpenDocument()) { uri -> uri?.let { model.importCaptionModel(it) } }
    BackHandler { if (enabled) close() }
    Form {
        FormHeading("图片描述")
        SelectField("描述来源", options.source, CaptionSource.entries, CaptionSource::label, enabled) {
            options = options.copy(source = it)
        }
        if (options.source == CaptionSource.LOCAL) {
            Text(if (state.localCaptionInstalled) "本地模型已安装。可离线生成英文描述。"
                else "安装约 246 MB 的模型，即可离线生成英文描述。")
            if (state.localCaptionInstalled) {
                TextButton({ model.removeCaptionModel() }, enabled = enabled) { Text("移除模型") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ model.downloadCaptionModel() }, enabled = enabled) { Text("下载模型") }
                    OutlinedButton({ importModel.launch(arrayOf("application/zip", "application/octet-stream")) },
                        enabled = enabled) { Text("导入模型 ZIP") }
                }
            }
        } else {
            SelectField("图片描述提供商", options.selection?.providerId.orEmpty(), listOf("") + providers.map(Provider::id), { id ->
                if (id.isEmpty()) "自动（当前对话或 Google Gemini）" else providers.firstOrNull { it.id == id }?.name ?: "服务不可用"
            }, enabled) { id -> options = options.copy(selection = providers.firstOrNull { it.id == id }?.defaultSelection) }
            options.selection?.let { selection ->
                val provider = providers.firstOrNull { it.id == selection.providerId }
                if (provider != null && provider.choices.size > 1) {
                    SelectField("已知模型", selection.model, (provider.choices + selection.model).distinct(),
                        { it.ifEmpty { "填写模型 ID" } }, enabled) {
                        options = options.copy(selection = selection.copy(model = it))
                    }
                }
                Field("视觉模型 ID", selection.model, { options = options.copy(selection = selection.copy(model = it)) },
                    enabled = enabled)
            }
            val connection = options.connection(state.prefs, state.chat)
            val status = connection?.let { "${it.provider.name} · ${it.model}" }
                ?: "请先保存 Gemini 或支持图片输入的 OpenAI 兼容提供商。"
            Text(status, style = MaterialTheme.typography.bodySmall)
            Field("默认描述提示词", options.prompt, { options = options.copy(prompt = it.take(MAX_IMAGE_CAPTION)) },
                lines = 3, enabled = enabled)
            GroupToggle("生成前询问提示词", options.ask, enabled) { options = options.copy(ask = it) }
            Text("生成描述时，图片会发给所选的多模态服务。", style = MaterialTheme.typography.bodySmall)
        }
        state.utility?.let { label ->
            state.localCaptionProgress?.let { percent ->
                LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                Text("$label $percent%")
            } ?: Text(label)
            TextButton(model::stop) { Text("停止") }
        }
        HorizontalDivider()
        Field("描述注入模板", options.template, { options = options.copy(template = it.take(MAX_IMAGE_CAPTION)) },
            lines = 2, enabled = enabled, placeholder = DEFAULT_CAPTION_TEMPLATE)
        Text("可用 {{caption}}、{{user}} 和 {{char}}。", style = MaterialTheme.typography.bodySmall)
        GroupToggle("发送附图时自动生成描述", options.automatic, enabled) { options = options.copy(automatic = it) }
        GroupToggle("应用自动描述前允许编辑", options.refine, enabled) { options = options.copy(refine = it) }
        GroupToggle("聊天模型仅接收图片文字描述", options.textOnly, enabled) { options = options.copy(textOnly = it) }
        Text("开启后，聊天请求用文字代替原图，适用于只接收文本的聊天模型。", style = MaterialTheme.typography.bodySmall)
        Button({ model.saveCaptionSettings(options.copy(selection = options.selection?.let { it.copy(model = it.model.trim()) }), close) },
            enabled = enabled && (options.source == CaptionSource.LOCAL || options.selection?.model?.isBlank() != true),
            modifier = Modifier.fillMaxWidth()) { Text("保存图片描述设置") }
    }
}

@Composable
internal fun ImageCaptionEditor(state: TavernState, model: TavernViewModel, chatId: String,
                                target: CaptionImageTarget, close: () -> Unit, configure: () -> Unit) {
    val options = state.prefs.captions
    val ready = state.captionReady()
    val enabled = !state.busy && state.generation == null && !state.scriptRunning
    val image = target.image
    val file = model.imageFile(image)
    var description by rememberSaveable(image.id, target.messageIndex) { mutableStateOf(state.imageCaption(chatId, target)) }
    var instruction by rememberSaveable(image.id) { mutableStateOf(options.prompt) }
    AlertDialog(onDismissRequest = { if (enabled) close() }, title = { Text("图片描述") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (file.isFile) ChatImageContent(image, file, 480, Modifier.fillMaxWidth().heightIn(max = 132.dp))
                Field("描述", description, { description = it.take(MAX_IMAGE_CAPTION) }, lines = 3, enabled = enabled)
                if (ready && options.source == CaptionSource.MULTIMODAL) {
                    Field("本次提示词", instruction, { instruction = it.take(MAX_IMAGE_CAPTION) }, lines = 2, enabled = enabled)
                }
                if (ready && file.isFile) {
                    OutlinedButton({ model.generateImageCaption(image, instruction) { description = it } },
                        enabled = enabled) { Text("生成描述") }
                } else if (!ready) {
                    TextButton(configure, enabled = enabled) { Text("打开设置配置描述模型") }
                }
                state.utility?.let { label ->
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(label)
                }
            }
        },
        dismissButton = {
            TextButton(if (state.utility != null) model::stop else close,
                enabled = state.utility != null || enabled) { Text(if (state.utility != null) "停止" else "取消") }
        },
        confirmButton = {
            Button({ model.saveImageCaption(chatId, target, description, close) }, enabled = enabled) { Text("保存描述") }
        },
    )
}

@Composable
internal fun CaptionFileQuestion(title: String, result: (String?) -> Unit) {
    val picker = rememberLauncherForActivityResult(OpenDocument()) { uri -> result(uri?.toString()) }
    AlertDialog(onDismissRequest = { result(null) }, title = { Text(title) },
        confirmButton = { Button({ picker.launch(arrayOf("image/*")) }) { Text("打开文件") } },
        dismissButton = { TextButton({ result(null) }) { Text("取消") } })
}
