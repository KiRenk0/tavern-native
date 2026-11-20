package app.tavern.nativeclient

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun DraftImageTile(image: ChatImage, file: File, enabled: Boolean, preview: () -> Unit, remove: () -> Unit) {
    Box(Modifier.size(72.dp)) {
        val shape = RoundedCornerShape(12.dp)
        Surface(modifier = Modifier.fillMaxSize().clip(shape).clickable(onClickLabel = "查看图片", onClick = preview)) {
            ChatImageContent(image, file, 200, Modifier.fillMaxSize(), crop = true)
        }
        FilledTonalIconButton(remove, modifier = Modifier.align(Alignment.TopEnd).size(36.dp), enabled = enabled) {
            Icon(Icons.Outlined.Close, "移除 ${image.name}", Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun MessageImage(image: ChatImage, file: File, preview: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Surface(Modifier.padding(top = 6.dp).widthIn(max = 280.dp).fillMaxWidth().clip(shape)
        .clickable(onClickLabel = "查看图片", onClick = preview)) {
        ChatImageContent(image, file, 640, Modifier.fillMaxWidth().heightIn(max = 260.dp))
    }
}

@Composable
internal fun ChatImageContent(image: ChatImage, file: File, maxEdge: Int, modifier: Modifier, crop: Boolean = false,
                             decorative: Boolean = false, alignment: Alignment = Alignment.Center) {
    val loaded = rememberImagePreview(file, maxEdge)
    if (loaded == null && !decorative) {
        Box(modifier.defaultMinSize(minHeight = 64.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.ImageNotSupported, image.name.ifBlank { "图片" }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else if (loaded != null) {
        val graphic = remember(loaded) { loaded.asImageBitmap() }
        Image(graphic, if (decorative) null else image.name.ifBlank { "聊天图片" }, modifier,
            alignment = alignment, contentScale = if (crop) ContentScale.Crop else ContentScale.Fit)
    }
}

@Composable
internal fun rememberImagePreview(file: File, maxEdge: Int): Bitmap? = key(file, maxEdge) {
    produceState<Bitmap?>(null) {
        value = withContext(Dispatchers.IO) { loadPreview(file, maxEdge) }
    }.value
}

@Composable
internal fun ConversationBackground(image: ChatImage, file: File, modifier: Modifier) {
    ChatImageContent(image, file, 1440, modifier.alpha(0.4f), crop = true, decorative = true)
}

@Composable
internal fun ImageGalleryDialog(gallery: ImageGallery, model: TavernViewModel, enabled: Boolean, dismiss: () -> Unit,
                                setBackground: ((ChatImage) -> Unit)? = null) {
    var preview by remember { mutableStateOf<ChatImage?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("${gallery.title}的图库") },
        text = {
            if (gallery.items.isEmpty()) Text("暂无图片")
            else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(gallery.items) { item ->
                    Column {
                        MessageImage(item.image, model.imageFile(item.image)) { preview = item.image }
                        if (item.prompt.isNotBlank()) Text(item.prompt, Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (setBackground != null) TextButton({ setBackground(item.image) }, enabled = enabled) { Text("设为对话背景") }
                    }
                }
            }
        },
        confirmButton = { TextButton(dismiss) { Text("关闭") } },
    )
    preview?.let { ImagePreviewDialog(it, model, enabled, { preview = null }, null) }
}

private fun loadPreview(file: File, maxEdge: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 64_000_000L) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdge * 2) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}

@Composable
internal fun ImagePreviewDialog(image: ChatImage, model: TavernViewModel, enabled: Boolean, dismiss: () -> Unit,
                                useAsReference: (() -> Unit)?, description: String = "", editDescription: (() -> Unit)? = null) {
    val file = model.imageFile(image)
    val export = rememberLauncherForActivityResult(CreateDocument(image.format.mime)) { uri ->
        if (uri != null) model.exportImage(uri, image)
    }
    AlertDialog(onDismissRequest = dismiss,
        title = { Text(image.name.ifBlank { "聊天图片" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (file.isFile) ChatImageContent(image, file, 1500, Modifier.fillMaxWidth().heightIn(max = 360.dp))
                else Text("此对话缺少图片文件。可用含图片的 ZIP 归档重新导入。")
                if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodyMedium)
                if (editDescription != null) TextButton(editDescription, enabled = enabled) { Text("图片描述") }
            }
        },
        dismissButton = { TextButton(dismiss) { Text("关闭") } },
        confirmButton = {
            if (file.isFile) {
                if (useAsReference != null) TextButton(useAsReference, enabled = enabled) { Text("用于绘图") }
                Button({ export.launch(safeFilename(image.displayFilename)) }, enabled = enabled) { Text("保存图片") }
            }
        },
    )
}

@Composable
internal fun NanoBananaDrawingDialog(state: TavernState, chat: Chat, viewModel: TavernViewModel, referenceCount: Int,
                                     dismiss: () -> Unit, advanced: () -> Unit, configure: () -> Unit) {
    val provider = nanoBananaProvider(state.prefs, chat)
    var preset by rememberSaveable(chat.id) { mutableStateOf(NanoBananaPreset.SCENE) }
    var note by rememberSaveable(chat.id) { mutableStateOf("") }
    var includeReferences by rememberSaveable(chat.id) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Nano Banana · 日式二次元") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (provider == null) {
                    Text("请在设置中保存可调用 Nano Banana 2.1 的 Google Gemini API Key。")
                } else {
                    SelectField("画面类型", preset, NanoBananaPreset.entries, { "${it.label} · ${it.shape.ratio}" }, !state.busy) {
                        preset = it
                    }
                    OutlinedTextField(note, { note = it.take(MAX_NANO_BANANA_NOTE) }, Modifier.fillMaxWidth(),
                        label = { Text("补充画面要求（选填）") }, placeholder = { Text("例如：雨夜街灯，暖色光线") },
                        minLines = 2, maxLines = 4, enabled = !state.busy)
                    if (referenceCount > 0) GroupToggle("使用草稿里的 $referenceCount 张参考图", includeReferences, !state.busy) {
                        includeReferences = it
                    }
                }
                TextButton(advanced, enabled = !state.busy) { Text("其他绘图选项") }
            }
        },
        dismissButton = { TextButton(dismiss) { Text("取消") } },
        confirmButton = {
            if (provider == null) Button({ dismiss(); configure() }) { Text("打开设置") }
            else Button({
                if (viewModel.generateNanoBanana(chat.id, preset, note, includeReferences)) dismiss()
            }, enabled = !state.busy) { Text("生成图片") }
        },
    )
}

@Composable
internal fun ImageGenerationDialog(state: TavernState, chat: Chat, viewModel: TavernViewModel, initialPrompt: String,
                                  references: List<ChatImage>, target: Int?, dismiss: () -> Unit, configure: () -> Unit) {
    val providers = state.prefs.providers.filter { provider -> ImageProtocol.entries.any { it.available(provider) } }
    if (providers.isEmpty()) {
        AlertDialog(onDismissRequest = dismiss, title = { Text("生成图片") },
            text = { Text("请在设置中填写 Google Gemini API Key，或添加自己的图像服务。") },
            dismissButton = { TextButton(dismiss) { Text("取消") } },
            confirmButton = { Button({ dismiss(); configure() }) { Text("打开设置") } })
        return
    }
    val saved = state.prefs.drawing.defaultProfile(providers)
    var selected by rememberSaveable {
        mutableStateOf(saved?.providerId ?: chat.modelOverride?.providerId ?: state.prefs.defaultSelection?.providerId)
    }
    val provider = providers.firstOrNull { it.id == selected } ?: providers.first()
    val choices = ImageProtocol.entries.filter { it.available(provider) }
    var pickedProtocol by rememberSaveable { mutableStateOf(saved?.protocol?.name ?: ImageProtocol.COMPATIBLE.name) }
    val protocol = choices.firstOrNull { it.name == pickedProtocol } ?: choices.first()
    var text by rememberSaveable { mutableStateOf(initialPrompt) }
    var mode by rememberSaveable { mutableStateOf(DrawingMode.FREE) }
    var shape by rememberSaveable { mutableStateOf(saved?.shape ?: ImageShape.SQUARE) }
    var model by rememberSaveable(provider.id, protocol.name) {
        mutableStateOf(saved?.takeIf { it.providerId == provider.id && it.protocol == protocol }?.model ?: protocol.initialModel(provider))
    }
    var includeImage by rememberSaveable { mutableStateOf(references.isNotEmpty()) }
    var rememberService by rememberSaveable { mutableStateOf(true) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var editSettings by rememberSaveable { mutableStateOf(false) }
    var manageWorkflows by rememberSaveable { mutableStateOf(false) }
    var workflowId by rememberSaveable { mutableStateOf(saved?.workflowId ?: state.prefs.drawing.comfyWorkflowId) }
    val workflow = state.comfyWorkflow(workflowId) ?: defaultComfyWorkflow
    val keys = key(workflow, protocol) {
        produceState<Set<String>?>(null) {
            if (protocol.usesWorkflow) value = withContext(Dispatchers.Default) { parseComfyWorkflow(workflow.source).keys }
        }.value
    }
    var availableParameters by remember(provider.id) { mutableStateOf<ComfyCatalog?>(null) }
    val comfyCatalog = availableParameters.takeIf { protocol == ImageProtocol.COMFY }
    val supportsReference = !protocol.usesWorkflow || keys?.any { it.startsWith("reference_image") } == true
    val tuningDefaults = saved?.takeIf { it.providerId == provider.id && it.protocol == protocol }?.tuning ?: ImageTuning()
    var negative by rememberSaveable(provider.id, protocol) { mutableStateOf(tuningDefaults.negative) }
    var width by rememberSaveable(provider.id, protocol) { mutableStateOf(tuningDefaults.width?.toString().orEmpty()) }
    var height by rememberSaveable(provider.id, protocol) { mutableStateOf(tuningDefaults.height?.toString().orEmpty()) }
    var seed by rememberSaveable(provider.id, protocol) { mutableStateOf(tuningDefaults.seed?.toString().orEmpty()) }
    var steps by rememberSaveable(provider.id, protocol) { mutableStateOf(tuningDefaults.steps?.toString().orEmpty()) }
    var cfg by rememberSaveable(provider.id, protocol) { mutableStateOf(tuningDefaults.cfg?.toString().orEmpty()) }
    var sampler by rememberSaveable(provider.id, protocol) { mutableStateOf(tuningDefaults.sampler) }
    var scheduler by rememberSaveable(provider.id, protocol) { mutableStateOf(tuningDefaults.scheduler) }
    var skip by rememberSaveable(provider.id, protocol) { mutableStateOf(tuningDefaults.skip?.toString().orEmpty()) }
    var vae by rememberSaveable(provider.id, protocol) { mutableStateOf(tuningDefaults.vae) }
    var denoise by rememberSaveable(provider.id, protocol) { mutableStateOf(tuningDefaults.denoise?.toString().orEmpty()) }
    val arguments = buildMap {
        put("negative", negative)
        if (protocol != ImageProtocol.GEMINI) {
            put("width", width)
            put("height", height)
        }
        if (protocol.usesSamplerParameters) {
            put("seed", seed)
            put("steps", steps)
            put("cfg", cfg)
            put("sampler", sampler)
            put("scheduler", scheduler)
            put("skip", skip)
            put("vae", vae)
        }
        if (protocol.usesWorkflow) put("denoise", denoise)
    }.filterValues(String::isNotBlank)
    val tuning = runCatching { ImageTuning().override(arguments) }
    if (editSettings) {
        DrawingSettingsDialog(state, viewModel) { editSettings = false }
        return
    }
    if (manageWorkflows) {
        ComfyWorkflowDialog(state, viewModel, close = { manageWorkflows = false }, select = {
            workflowId = it
            manageWorkflows = false
        })
        return
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text("生成图片") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("提供商", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    providers.forEach { candidate ->
                        FilterChip(candidate.id == provider.id, { selected = candidate.id }, label = { Text(candidate.name) })
                    }
                }
                if (choices.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.forEach { candidate ->
                        FilterChip(candidate == protocol, { pickedProtocol = candidate.name }, label = { Text(candidate.label) })
                    }
                }
                if (protocol.usesWorkflow) {
                    SelectField("工作流", workflow, state.comfyWorkflows, ComfyWorkflow::name, !state.busy) { workflowId = it.id }
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton({ manageWorkflows = true }, enabled = !state.busy) { Text("管理工作流") }
                        TextButton({ viewModel.testComfy(provider, protocol) }, enabled = !state.busy) { Text("测试连接") }
                        if (protocol == ImageProtocol.COMFY) TextButton({
                            viewModel.loadComfyOptions(provider) { availableParameters = it }
                        }, enabled = !state.busy) { Text("读取服务参数") }
                    }
                    state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                if (!protocol.usesWorkflow || keys?.contains("model") != false) {
                    val label = when {
                        protocol == ImageProtocol.DIFFUSION -> "检查点（留空使用服务当前模型）"
                        protocol.usesWorkflow -> "检查点 / UNet 文件名"
                        else -> "图像模型 ID"
                    }
                    DrawingInput(label, model, comfyCatalog?.checkpoints.orEmpty()) { model = it }
                }
                if (target == null) SelectField("提示词来源", mode, DrawingMode.entries, DrawingMode::label, !state.busy) {
                    if (it != mode) text = if (it == DrawingMode.FREE) initialPrompt else ""
                    mode = it
                }
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("绘图提示词") }, minLines = 3, maxLines = 6)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (state.utility != null) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        TextButton(viewModel::stop) { Text("停止") }
                    } else if (mode != DrawingMode.FREE || text.isNotBlank()) {
                        TextButton({ viewModel.draftDrawingPrompt(chat, mode, text) { text = it } }, enabled = !state.busy) {
                            Text(if (mode == DrawingMode.FREE) "扩写提示词" else "构建提示词")
                        }
                    }
                    TextButton({ editSettings = true }, enabled = !state.busy) {
                        Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("绘图设置")
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImageShape.entries.forEach { candidate ->
                        FilterChip(candidate == (mode.forcedShape ?: shape), { shape = candidate },
                            enabled = mode.forcedShape == null && !state.busy, label = { Text(candidate.label) })
                    }
                }
                if (protocol.supportsReferences && supportsReference && references.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(includeImage, { includeImage = it })
                    Text("使用 ${references.size} 张参考图片")
                }
                TextButton({ advanced = !advanced }) { Text(if (advanced) "收起生成参数" else "生成参数") }
                if (advanced) {
                    DrawingInput("负面提示词", negative) { negative = it }
                    if (protocol != ImageProtocol.GEMINI) {
                        DrawingInput("宽度（像素）", width) { width = it }
                        DrawingInput("高度（像素）", height) { height = it }
                    }
                    if (protocol.usesSamplerParameters) {
                        DrawingInput("随机种子", seed) { seed = it }
                        DrawingInput("采样步数", steps) { steps = it }
                        DrawingInput("CFG", cfg) { cfg = it }
                        DrawingInput("采样器", sampler, comfyCatalog?.samplers.orEmpty()) { sampler = it }
                        DrawingInput("调度器", scheduler, comfyCatalog?.schedulers.orEmpty()) { scheduler = it }
                        DrawingInput("Clip Skip", skip) { skip = it }
                        DrawingInput("VAE", vae, comfyCatalog?.vaes.orEmpty()) { vae = it }
                    }
                    if (protocol.usesWorkflow) DrawingInput("去噪强度（0–1）", denoise) { denoise = it }
                }
                tuning.exceptionOrNull()?.message?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(rememberService, { rememberService = it })
                    Text("设为默认绘图服务")
                }
            }
        },
        dismissButton = { TextButton(dismiss) { Text("取消") } },
        confirmButton = {
            Button({
                val input = if (protocol.supportsReferences && supportsReference && includeImage) references else emptyList()
                val request = ImageRequest(provider, protocol, model.trim(), shape, text.trim(), input, tuning.getOrThrow(),
                    workflow.id.takeIf { protocol.usesWorkflow })
                if (viewModel.generateImage(chat.id, request, mode, target, rememberService)) dismiss()
            }, enabled = !state.busy && text.isNotBlank() && tuning.isSuccess &&
                (model.isNotBlank() || protocol == ImageProtocol.DIFFUSION || protocol.usesWorkflow && keys?.contains("model") == false)) { Text("生成") }
        },
    )
}

@Composable
private fun DrawingInput(label: String, value: String, choices: List<String> = emptyList(), change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(value, change, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
            trailingIcon = if (choices.isEmpty()) null else ({
                IconButton({ expanded = true }) { Icon(Icons.Outlined.ArrowDropDown, "选择$label") }
            }))
        if (choices.isNotEmpty()) DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 300.dp)) {
            choices.forEach { item ->
                DropdownMenuItem(text = { Text(item) }, onClick = { expanded = false; change(item) })
            }
        }
    }
}

@Composable
private fun DrawingSettingsDialog(state: TavernState, model: TavernViewModel, dismiss: () -> Unit) {
    val saved = state.prefs.drawing
    var draft by remember { mutableStateOf(saved) }
    var styleId by remember { mutableStateOf(saved.activeStyle) }
    var creatingStyle by remember { mutableStateOf(false) }
    var styleName by remember { mutableStateOf("") }
    var newPrefix by remember { mutableStateOf("") }
    var newNegative by remember { mutableStateOf("") }
    var template by remember { mutableStateOf(DrawingMode.SCENE) }
    val style = draft.styles.firstOrNull { it.name == styleId }
    val card = state.card
    val availableName = styleName.trim().isNotEmpty() && draft.styles.none { it.name.equals(styleName.trim(), true) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("绘图设置") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GroupToggle("识别对话中的自然语言绘图要求", draft.interactive) { draft = draft.copy(interactive = it) }
                GroupToggle("允许模型调用绘图工具", draft.functionTool) { draft = draft.copy(functionTool = it) }
                if (draft.functionTool) {
                    if (!state.prefs.toolCalling) Text("同时在应用设置中打开「允许对话模型调用函数工具」。",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(draft.templates["tool"].orEmpty(), { value ->
                        val templates = if (value.isBlank()) draft.templates - "tool" else draft.templates + ("tool" to value)
                        draft = draft.copy(templates = templates)
                    }, Modifier.fillMaxWidth(), label = { Text("绘图工具提示词说明") }, minLines = 2, maxLines = 4)
                }
                GroupToggle("自动扩写自定义提示词", draft.extendFree) { draft = draft.copy(extendFree = it) }
                SelectField("使用风格", styleId, listOf("") + draft.styles.map(ImageStyle::name),
                    { it.ifBlank { "无" } }, !state.busy) { styleId = it }
                if (style != null) {
                    DrawingInput("风格提示词前缀", style.prefix) { changed ->
                        draft = draft.copy(styles = draft.styles.map { if (it.name == styleId) it.copy(prefix = changed) else it })
                    }
                    DrawingInput("风格负面提示词", style.negative) { changed ->
                        draft = draft.copy(styles = draft.styles.map { if (it.name == styleId) it.copy(negative = changed) else it })
                    }
                    TextButton({
                        draft = draft.copy(styles = draft.styles.filterNot { it.name == styleId })
                        styleId = ""
                    }) { Text("移除此风格") }
                }
                if (creatingStyle) {
                    DrawingInput("风格名称", styleName) { styleName = it }
                    DrawingInput("风格提示词前缀", newPrefix) { newPrefix = it }
                    DrawingInput("风格负面提示词", newNegative) { newNegative = it }
                } else TextButton({ creatingStyle = true }) { Text("新建风格") }
                if (card != null) DrawingInput("角色提示词前缀", draft.characterPrefixes[card.id].orEmpty()) { value ->
                    val prefixes = if (value.isBlank()) draft.characterPrefixes - card.id else draft.characterPrefixes + (card.id to value)
                    draft = draft.copy(characterPrefixes = prefixes)
                }
                SelectField("提示词模板", template, DrawingMode.entries.filter { it != DrawingMode.RAW_LAST }, DrawingMode::label,
                    !state.busy) { template = it }
                OutlinedTextField(draft.templates[template.trigger].orEmpty(), { value ->
                    val templates = if (value.isBlank()) draft.templates - template.trigger else draft.templates + (template.trigger to value)
                    draft = draft.copy(templates = templates)
                }, Modifier.fillMaxWidth(), label = { Text("提示词模板指令") }, placeholder = { Text(template.instruction) },
                    minLines = 2, maxLines = 5)
            }
        },
        dismissButton = { TextButton(dismiss) { Text("取消") } },
        confirmButton = {
            Button({
                val result = if (creatingStyle) {
                    val added = ImageStyle(styleName.trim(), newPrefix, newNegative)
                    draft.copy(styles = draft.styles + added, activeStyle = added.name)
                } else draft.copy(activeStyle = styleId)
                model.saveDrawingSettings(result, dismiss)
            }, enabled = !state.busy && (!creatingStyle || availableName)) { Text("保存") }
        },
    )
}
