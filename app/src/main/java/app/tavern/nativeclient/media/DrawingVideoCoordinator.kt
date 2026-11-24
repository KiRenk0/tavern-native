package app.tavern.nativeclient.media

import android.net.Uri
import app.tavern.nativeclient.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

internal class DrawingVideoCoordinator(
    private val host: CoordinatorHost,
    private val captions: MediaCaptionCoordinator,
) {
    private var imageJob: Job? = null

    fun cancelImage() {
        imageJob?.cancel()
    }

    fun generateVideo(chatId: String, request: VideoRequest): Boolean = launchDrawing(chatId, video = true) { chat ->
        val profile = request.profile.copy(providerId = request.provider.id)
        val store = host.videoStore
        val referenceStore = host.imageStore
        val video = withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            GeminiVideoService().generate(request, referenceStore::bytes, progress = { label ->
                host.mutable.update { it.copy(utility = label) }
            }) { input -> store.import(input, "Gemini 视频 ${profile.seconds} 秒.mp4") { context.ensureActive() } }
        }
        val prefs = host.current.prefs
        host.persistPreferences(prefs.copy(drawing = prefs.drawing.copy(video = profile)))
        captions.appendVideo(chat, video, "视频生成")
    }

    fun generateImage(chatId: String, request: ImageRequest, mode: DrawingMode, target: Int?, remember: Boolean): Boolean =
        launchDrawing(chatId) { chat ->
            val settings = host.current.prefs.drawing
            if (remember) {
                val profile = request.recipe().profile
                host.persistPreferences(host.current.prefs.copy(drawing = settings.copy(
                    profile = profile,
                    comfyWorkflowId = if (profile.protocol.usesWorkflow) profile.workflowId else settings.comfyWorkflowId,
                )))
            }
            renderDrawing(chat, prepareDrawing(request, chat, mode, target), target = target, background = mode == DrawingMode.BACKGROUND)
        }

    fun generateNanoBanana(chatId: String, preset: NanoBananaPreset, note: String, includeReferences: Boolean): Boolean =
        launchDrawing(chatId) { chat ->
            val provider = requireNotNull(nanoBananaProvider(host.current.prefs, chat)) { "请先保存 Google Gemini API Key" }
            val references = if (includeReferences) host.current.draftImages[chat.id].orEmpty() else emptyList()
            val request = nanoBananaRequest(provider, preset, chat, drawingCard(chat), host.current.persona, note, references)
            renderDrawing(chat, request, background = preset == NanoBananaPreset.BACKGROUND, caption = preset.label)
        }

    fun draftDrawingPrompt(chat: Chat, mode: DrawingMode, text: String, done: (String) -> Unit): Boolean =
        host.utilityWork("正在编写绘图提示词…") { done(drawingPrompt(chat, mode, text, extendFree = mode == DrawingMode.FREE)) }

    fun saveDrawingSettings(settings: DrawingSettings, done: () -> Unit) = host.work {
        val previous = host.current.prefs.drawing
        host.persistPreferences(host.current.prefs.copy(drawing = settings))
        if (previous.functionTool != settings.functionTool || previous.templates["tool"] != settings.templates["tool"]) {
            host.script.resetNativeDrawingTool()
        }
        done()
    }

    fun readComfyWorkflow(uri: Uri, done: (ComfyWorkflow) -> Unit) = host.work {
        val imported = withContext(Dispatchers.IO) {
            val name = host.app.documentName(uri).substringBeforeLast('.').ifBlank { "导入的工作流" }
            val source = requireNotNull(host.resolver.openInputStream(uri)) { "无法打开工作流文件" }
                .use { it.readLimited(MAX_COMFY_WORKFLOW_BYTES) }.toString(Charsets.UTF_8)
            ComfyWorkflow(newId(), name, source).validated()
        }
        done(imported)
    }

    fun saveComfyWorkflow(workflow: ComfyWorkflow, done: (ComfyWorkflow) -> Unit) = host.work {
        require(!workflow.isBuiltIn) { "请复制内置工作流后编辑" }
        val saved = withContext(Dispatchers.IO) { workflow.validated() }
        require(host.current.comfyWorkflows.none { it.id != saved.id && it.name.equals(saved.name, true) }) { "已有同名工作流" }
        updateComfyWorkflows { saveComfyWorkflow(saved) }
        done(saved)
    }

    fun deleteComfyWorkflow(workflow: ComfyWorkflow, done: () -> Unit) = host.work {
        require(!workflow.isBuiltIn) { "内置工作流可以复制" }
        updateComfyWorkflows { deleteComfyWorkflow(workflow.id) }
        val settings = host.current.prefs.drawing
        val profile = settings.profile
        if (settings.comfyWorkflowId == workflow.id || profile?.workflowId == workflow.id) {
            host.persistPreferences(host.current.prefs.copy(drawing = settings.copy(
                comfyWorkflowId = settings.comfyWorkflowId.takeUnless { it == workflow.id },
                profile = if (profile?.workflowId == workflow.id) profile.copy(workflowId = null) else profile,
            )))
        }
        done()
    }

    private suspend fun updateComfyWorkflows(change: TavernStore.() -> Unit) {
        val workflows = withContext(Dispatchers.IO) { host.store.change(); host.store.comfyWorkflows() }
        host.mutable.update { it.copy(comfyWorkflows = builtInComfyWorkflows + workflows) }
    }

    fun exportComfyWorkflow(uri: Uri, workflow: ComfyWorkflow) = host.work {
        withContext(Dispatchers.IO) { host.writeUri(uri, workflow.source.toByteArray(Charsets.UTF_8)) }
        host.notice("工作流 JSON 已导出")
    }

    fun testComfy(provider: Provider, protocol: ImageProtocol) = host.utilityWork("正在检查绘图服务…") {
        ComfyService().test(provider, protocol == ImageProtocol.COMFY_RUNPOD)
        host.notice("${protocol.label} 已连接")
    }

    fun loadComfyOptions(provider: Provider, done: (ComfyCatalog) -> Unit) = host.utilityWork("正在读取 ComfyUI 参数…") {
        done(ComfyService().catalog(provider))
    }

    fun loadHordeImageModels(provider: Provider, done: (List<String>) -> Unit) = host.utilityWork("正在读取 AI Horde 图像模型…") {
        val models = Horde(provider).imageModels()
        if (models.isEmpty()) host.notice("AI Horde 当前没有在线绘图模型")
        done(models)
    }

    fun showImageGallery() = host.work { loadImageGallery() }

    fun closeImageGallery() = host.mutable.update { it.copy(imageGallery = null) }

    internal suspend fun loadImageGallery(): String {
        val card = host.current.card
        val group = host.current.group
        val title = card?.name ?: group?.name ?: error("请先打开角色或群组")
        val items = withContext(Dispatchers.IO) { host.store.gallery(card?.id, group?.id) }
        host.mutable.update { it.copy(imageGallery = ImageGallery(title, items)) }
        return items.size.toString()
    }

    fun previousDrawing(chatId: String, index: Int) = host.work {
        val chat = host.current.chat?.takeIf { it.id == chatId } ?: return@work
        val series = chat.messages.getOrNull(index)?.drawing ?: return@work
        if (series.selected > 0) saveDrawingSelection(chat, index, series.copy(selected = series.selected - 1))
    }

    fun nextDrawing(chatId: String, index: Int): Boolean {
        val chat = host.current.chat?.takeIf { it.id == chatId } ?: return false
        val series = chat.messages.getOrNull(index)?.drawing ?: return false
        if (host.current.busy || host.current.generation != null || host.current.scriptRunning) return false
        if (series.selected < series.images.lastIndex) {
            host.work { saveDrawingSelection(chat, index, series.copy(selected = series.selected + 1)) }
            return true
        }
        return launchDrawing(chatId) {
            renderDrawing(
                it, series.recipe.request(host.current.prefs, reroll = true), index,
                gallery = series.addToGallery, previous = series,
            )
        }
    }

    internal fun launchDrawing(chatId: String, video: Boolean = false, action: suspend (Chat) -> Unit): Boolean {
        val chat = host.current.chat?.takeIf { it.id == chatId } ?: return false
        if (host.current.busy || host.current.generation != null || host.current.scriptRunning) return false
        host.generation.stopAuto()
        host.mutable.update { it.copy(busy = true, generatingImage = true, utility = if (video) "正在准备视频请求…" else it.utility) }
        val job = host.scope.launch(start = CoroutineStart.LAZY) {
            try {
                action(chat)
            } catch (e: CancellationException) {
                host.notice(if (video) "已停止等待视频" else "已停止图像生成")
                throw e
            } catch (e: Exception) {
                host.notice(e.localizedMessage ?: if (video) "视频生成失败，请重试" else "图像生成失败，请重试")
            } finally {
                host.mutable.update { it.copy(busy = false, generatingImage = false, utility = it.utility.takeUnless { video }) }
                imageJob = null
            }
        }
        imageJob = job
        job.start()
        return true
    }

    internal fun drawingCard(chat: Chat, target: Int? = null): Card? {
        val message = target?.let(chat.messages::getOrNull)
            ?: chat.messages.lastOrNull { it.role == "assistant" }
        return host.current.regexCard(message)
    }

    internal suspend fun drawingPrompt(chat: Chat, mode: DrawingMode, text: String, extendFree: Boolean): String = when {
        mode == DrawingMode.RAW_LAST -> chat.messages.lastOrNull { !it.excludedFromContext && it.text.isNotBlank() }?.text
            ?: error("当前没有可用于绘图的消息")
        mode == DrawingMode.FREE && !extendFree -> text
        else -> ModelService.utility(
            host.utilityConnection(chat),
            drawingPromptContext(mode, text, chat, drawingCard(chat), host.current.persona, host.current.prefs.drawing),
            400,
        )
    }

    internal fun prepareDrawing(
        request: ImageRequest,
        chat: Chat,
        mode: DrawingMode,
        target: Int?,
        card: Card? = drawingCard(chat, target),
    ): ImageRequest {
        val settings = host.current.prefs.drawing
        require(request.prompt.isNotBlank()) { "请输入绘图提示词" }
        return request.copy(
            shape = mode.forcedShape ?: request.shape,
            prompt = prepareDrawingPrompt(
                request.prompt, settings, card, host.current.persona,
                mode, singleCharacter = chat.cardId != null,
            ),
            tuning = request.tuning.copy(negative = combineImagePrompts(settings.style?.negative.orEmpty(), request.tuning.negative)),
        )
    }

    private suspend fun saveDrawingSelection(chat: Chat, index: Int, series: DrawnImageSeries) {
        val messages = chat.messages.toMutableList()
        messages[index] = messages[index].copy(drawing = series)
        host.persist(chat.copy(messages = messages, updated = System.currentTimeMillis()))
    }

    internal suspend fun renderDrawing(
        chat: Chat,
        request: ImageRequest,
        target: Int? = null,
        quiet: Boolean = false,
        gallery: Boolean = true,
        background: Boolean = false,
        previous: DrawnImageSeries? = null,
        card: Card? = drawingCard(chat, target),
        groupTurnId: String? = null,
        caption: String = request.prompt,
    ): ChatImage {
        require(target == null || target in chat.messages.indices) { "找不到绘图对应的消息" }
        val comfy = if (!request.protocol.usesWorkflow) null else {
            val workflow = requireNotNull(host.current.comfyWorkflow(request.workflowId)) { "找不到原来使用的 ComfyUI 工作流" }
            val persona = host.current.persona
            val userAvatar = persona.avatar?.let(host.store::personaAvatar)
            val characterAvatar = card?.takeIf(Card::hasAvatar)?.let { host.store.portraitFile(it.id) }
            fun readAvatar(file: java.io.File?) = file?.takeIf { it.isFile && it.length() <= MAX_IMAGE_SOURCE }
                ?.inputStream()?.use { it.readLimited(MAX_IMAGE_SOURCE) }
            ComfyInputs(workflow, { readAvatar(userAvatar) }, { readAvatar(characterAvatar) }) { value ->
                if (card == null) value.replace("{{user}}", persona.name) else expand(value, card, persona.name)
            }
        }
        val image = withContext(Dispatchers.IO) {
            val bytes = host.imageService.generate(request, comfy, host.imageStore::bytes)
            host.imageStore.import(bytes, "绘图-${newId().take(8)}")
        }
        withContext(NonCancellable) {
            val entry = if (gallery) GalleryImage(image, request.prompt) else null
            if (!quiet || background) {
                val messages = chat.messages.toMutableList()
                if (!quiet) {
                    val series = if (previous == null) DrawnImageSeries(request.recipe(), listOf(image), addToGallery = gallery)
                    else previous.copy(images = previous.images + image, selected = previous.images.size)
                    if (target == null) messages.add(
                        Message("system", "图像生成", caption, drawing = series, groupTurnId = groupTurnId),
                    )
                    else messages[target] = messages[target].copy(drawing = series)
                }
                val options = if (background) chat.options.copy(background = image) else chat.options
                host.persist(chat.copy(messages = messages, options = options, updated = System.currentTimeMillis()), entry)
            } else if (entry != null && !chat.isTemporary) withContext(Dispatchers.IO) { host.store.addDrawing(chat, entry) }
        }
        return image
    }

    internal fun drawingTool(prefs: Preferences, chat: Chat, card: Card?): ModelTool? {
        val settings = prefs.drawing
        if (!settings.functionTool || host.script.nativeDrawingToolRemoved) return null
        val profile = settings.defaultProfile(prefs.providers) ?: return null
        val request = profile.resolve(prefs.providers, "") ?: return null
        if (request.protocol.usesWorkflow && host.current.comfyWorkflow(request.workflowId) == null) return null
        val instruction = settings.templates["tool"] ?: "Describe the requested subject, setting, composition and visual style in a text prompt for an image model."
        val parameters = jsonOf(
            "type" to "object",
            "properties" to jsonOf(
                "prompt" to jsonOf("type" to "string", "description" to instruction),
            ),
            "required" to JSONArray(listOf("prompt")),
        )
        return ModelTool(
            DRAW_IMAGE_TOOL,
            "生成图片",
            "Generate an image when the user requests a picture, illustration, scene or selfie.",
            parameters,
        ) { args ->
            val prompt = (args.opt("prompt") as? String)?.trim()
            require(!prompt.isNullOrBlank() && prompt.length <= 16_384) { "绘图工具需要有效的 prompt" }
            val active = host.current.chat?.takeIf { it.id == chat.id } ?: error("对话已关闭")
            val references = if (request.protocol.supportsReferences)
                active.messages.lastOrNull { it.role == "user" && !it.hidden }?.displayImages.orEmpty() else emptyList()
            val imageRequest = prepareDrawing(request.copy(prompt = prompt, references = references), active, DrawingMode.FREE, null, card)
            host.mutable.update { it.copy(generatingImage = true) }
            try {
                renderDrawing(
                    active, imageRequest, card = card,
                    groupTurnId = host.current.generation?.id.takeIf { active.groupId != null },
                ).drawingAddress()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error(e.localizedMessage.orEmpty().redactValue(request.provider.apiKey).ifBlank { "图像生成失败" })
            } finally {
                host.mutable.update { it.copy(generatingImage = false) }
            }
        }
    }
}
