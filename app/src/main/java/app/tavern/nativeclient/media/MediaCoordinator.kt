package app.tavern.nativeclient.media

import android.net.Uri
import app.tavern.nativeclient.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.io.InputStream
import java.util.Locale

private data class QueuedNarration(val chatId: String?, val narration: Narration)

internal class MediaCoordinator(private val host: CoordinatorHost) {
    private val speechPlayer = lazy { SpeechPlayback(host.app, ::stopNarration) }
    private val narrationQueue = ArrayDeque<QueuedNarration>()
    private var narrationJob: Job? = null
    private var narrationOwner: QueuedNarration? = null
    private var voiceForeground = true
    private var imageJob: Job? = null
    private val expressionCache = linkedMapOf<ExpressionInference, String>()

    fun activeNarrationChatId(): String? = narrationOwner?.chatId

    fun cancelImage() {
        imageJob?.cancel()
    }

    fun close() {
        stopNarration()
        if (speechPlayer.isInitialized()) speechPlayer.value.close()
    }

    fun voiceForeground(visible: Boolean) {
        voiceForeground = visible
        if (!visible) stopNarration()
    }

    fun saveVoice(options: VoicePreferences, done: () -> Unit) = host.work {
        when (options.activeBackend) {
            TtsBackend.SYSTEM -> Unit
            TtsBackend.OPENAI_COMPAT -> {
                require(options.backendId.isNotBlank() &&
                    host.current.prefs.providers.any { it.id == options.backendId && it.template == null }) {
                    "请选择已保存的自定义语音服务"
                }
                require(options.backendModel.isNotBlank() && options.backendVoice.isNotBlank()) {
                    "请填写语音模型和默认音色"
                }
            }
            TtsBackend.EDGE_TTS -> Unit
            TtsBackend.ELEVENLABS -> {
                if (options.ttsEndpoint.isNotBlank()) checkedAddress(options.ttsEndpoint)
                require(options.ttsApiKey.isNotBlank() && options.elevenLabsVoiceId.isNotBlank()) {
                    "请填写 ElevenLabs API Key 与 Voice ID"
                }
            }
            TtsBackend.FISH_AUDIO -> {
                if (options.ttsEndpoint.isNotBlank()) checkedAddress(options.ttsEndpoint)
                require(options.ttsApiKey.isNotBlank()) { "请填写 Fish Audio API Key" }
            }
            TtsBackend.GPT_SOVITS -> {
                require(options.ttsEndpoint.isNotBlank()) { "请填写 GPT-SoVITS 服务地址" }
                checkedAddress(options.ttsEndpoint)
                require(options.gptSovitsRefAudio.isNotBlank()) { "请填写 GPT-SoVITS 参考音频路径" }
            }
        }
        if (options.sttBackend == SttBackend.WHISPER_API) {
            require(options.sttEndpoint.isNotBlank()) { "请填写 Whisper STT 接口地址" }
            checkedAddress(options.sttEndpoint)
        }
        if (options.filter.isNotBlank()) {
            val result = regexTest(RegexRule(pattern = options.filter), "朗读测试", RegexNames(null, ""))
            require(result.issue == null) { result.issue ?: "朗读正则无效" }
        }
        if (options.language.isNotBlank()) require(Locale.forLanguageTag(options.language).language.isNotBlank()) {
            "识别语言应使用语言标签，例如 zh-CN"
        }
        stopNarration()
        host.persistPreferences(host.current.prefs.copy(voice = options))
        done()
    }

    suspend fun systemVoices(): List<DeviceVoice> = speechPlayer.value.voices()

    fun previewVoice(options: VoicePreferences, voice: String, line: String) {
        enqueueSpeech(line, null, options, voice, replace = true)
    }

    fun stopNarration() {
        narrationQueue.clear()
        val job = narrationJob
        narrationJob = null
        narrationOwner = null
        job?.cancel()
        if (host.current.speaking) host.mutable.update { it.copy(speaking = false) }
    }

    private fun enqueueSpeech(text: String, chatId: String?, options: VoicePreferences, voice: String, replace: Boolean) {
        if (!voiceForeground || text.isBlank()) return
        if (chatId != null && chatId != host.current.chat?.id) return
        val backend = if (options.activeBackend != TtsBackend.OPENAI_COMPAT) null else {
            host.current.prefs.providers.firstOrNull { it.id == options.backendId && it.template == null }
                ?: return host.notice("请重新选择朗读使用的语音服务")
        }
        if (replace) stopNarration()
        if (narrationQueue.size >= 20) narrationQueue.removeFirst()
        narrationQueue.addLast(QueuedNarration(chatId, Narration(text, options, voice, backend)))
        if (narrationJob?.isActive == true) return
        val job = host.scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (narrationQueue.isNotEmpty()) {
                    val next = narrationQueue.removeFirst()
                    if (next.chatId != null && next.chatId != host.current.chat?.id) continue
                    narrationOwner = next
                    val narration = next.narration
                    val spoken = withContext(Dispatchers.Default) { narration.options.speechText(narration.text) }
                    if (spoken.isNotEmpty()) speechPlayer.value.play(narration.copy(text = spoken))
                }
            } catch (_: TimeoutCancellationException) {
                host.notice("语音服务响应超时")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                host.notice(e.localizedMessage ?: "无法朗读消息")
            } finally {
                if (narrationJob === coroutineContext[Job]) {
                    narrationQueue.clear()
                    narrationJob = null
                    narrationOwner = null
                    host.mutable.update { it.copy(speaking = false) }
                }
            }
        }
        narrationJob = job
        host.mutable.update { it.copy(speaking = true) }
        job.start()
    }

    private fun voiceActor(message: Message): String? = when (message.role) {
        "user" -> (message.personaId ?: host.current.prefs.personas.firstOrNull { it.name == message.name }?.id
            ?: host.current.persona.id).let { "persona:$it" }
        "assistant" -> (message.characterId ?: host.current.replyingCharacter(message)?.id)?.let { "character:$it" }
        else -> null
    }

    fun narrate(message: Message, automatic: Boolean = false) {
        val chat = host.current.chat ?: return
        val options = host.current.prefs.voice
        if (!options.enabled) return
        if (automatic && (!options.automatic || message.role == "user" && !options.includeUser)) return
        val source = if (options.translatedText) message.displayText else message.text
        val text = host.current.regexCard(message)?.let { expand(source, it, host.current.persona.name) } ?: source
        enqueueSpeech(text, chat.id, options, options.actorVoice(voiceActor(message)), replace = !automatic)
    }

    internal fun scriptNarrate(text: String, actor: String?) {
        val options = host.current.prefs.voice
        val chat = host.current.chat ?: return
        if (!options.enabled) return
        val key = if (actor.isNullOrBlank()) host.current.regexCard()?.id?.let { "character:$it" }
        else host.current.cards.firstOrNull { it.name.equals(actor, true) }?.id?.let { "character:$it" }
            ?: host.current.prefs.personas.firstOrNull { it.name.equals(actor, true) }?.id?.let { "persona:$it" }
        require(actor.isNullOrBlank() || key != null) { "找不到指定的朗读角色或人设" }
        enqueueSpeech(text, chat.id, options, options.actorVoice(key), replace = false)
    }

    fun attachImages(chatId: String, documents: List<Uri>, ready: ((ChatImage) -> Unit)? = null) {
        if (host.current.chat?.id != chatId || documents.isEmpty()) return
        val existing = host.current.draftImages[chatId].orEmpty()
        host.work {
            var lastFailure: String? = null
            val imported = withContext(Dispatchers.IO) {
                documents.take((MAX_CHAT_IMAGES - existing.size).coerceAtLeast(0)).mapNotNull { uri ->
                    val name = host.app.documentName(uri).takeUnless { it == "导入的聊天" } ?: "图片"
                    try {
                        importCaptionableImage(uri, name)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        lastFailure = "无法添加 $name · ${e.localizedMessage ?: "无法读取图片"}"
                        null
                    }
                }
            }
            host.mutable.update { it.copy(draftImages = it.draftImages + (chatId to (existing + imported).distinctBy(ChatImage::id))) }
            imported.singleOrNull()?.let { ready?.invoke(it) }
            lastFailure?.let(host::notice)
            if (lastFailure == null && documents.size > MAX_CHAT_IMAGES - existing.size) host.notice("每条消息最多附带 $MAX_CHAT_IMAGES 张图片")
        }
    }

    fun removeDraftImage(chatId: String, image: ChatImage) {
        if (host.current.busy || host.current.generation != null || host.current.scriptRunning) return
        host.mutable.update { state ->
            val captions = state.draftImageCaptions[chatId].orEmpty() - image.id
            state.copy(
                draftImages = state.draftImages + (chatId to state.draftImages[chatId].orEmpty().filterNot { it.id == image.id }),
                draftImageCaptions = state.draftImageCaptions + (chatId to captions),
            )
        }
    }

    internal fun importCaptionableImage(uri: Uri, name: String): ChatImage {
        val bytes = requireNotNull(host.resolver.openInputStream(uri)) { "无法打开图片" }.use { it.readLimited(MAX_IMAGE_SOURCE) }
        return host.imageStore.import(bytes, name)
    }

    fun saveCaptionSettings(settings: CaptionSettings, done: () -> Unit) = host.work {
        host.persistPreferences(host.current.prefs.copy(captions = CaptionSettings.from(settings.json())))
        done()
    }

    fun generateImageCaption(image: ChatImage, prompt: String, done: (String) -> Unit) =
        host.utilityWork("正在生成图片描述…") {
            done(describeImage(image, prompt))
        }

    fun saveImageCaption(chatId: String, target: CaptionImageTarget, text: String, done: () -> Unit) = host.work {
        val chat = host.current.chat?.takeIf { it.id == chatId } ?: return@work
        fun updated(captions: Map<String, String>): Map<String, String> {
            val value = text.trim().take(MAX_IMAGE_CAPTION)
            return if (value.isEmpty()) captions - target.image.id else captions + (target.image.id to value)
        }
        val index = target.messageIndex
        if (index == null) {
            check(host.current.draftImages[chatId].orEmpty().any { it.id == target.image.id }) { "草稿中已没有这张图片" }
            host.mutable.update {
                it.copy(draftImageCaptions = it.draftImageCaptions + (chatId to updated(it.draftImageCaptions[chatId].orEmpty())))
            }
        } else {
            val message = chat.messages.getOrNull(index)
            check(message != null && message.displayImages.any { it.id == target.image.id }) { "消息中已没有这张图片" }
            val messages = chat.messages.toMutableList()
            messages[index] = message.copy(imageCaptions = updated(message.imageCaptions))
            host.persist(chat.copy(messages = messages, updated = System.currentTimeMillis()))
        }
        done()
    }

    private suspend fun describeImage(image: ChatImage, prompt: String, settings: CaptionSettings = host.current.prefs.captions): String {
        val bytes = withContext(Dispatchers.IO) { checkNotNull(host.imageStore.bytes(image)) { "找不到图片文件" } }
        return when (settings.source) {
            CaptionSource.LOCAL -> withContext(Dispatchers.Default) { host.localCaptioner.caption(bytes) }
            CaptionSource.MULTIMODAL -> {
                val connection = checkNotNull(settings.connection(host.current.prefs, host.current.chat)) { "请先配置图片描述模型" }
                try {
                    captionMultimodal(connection, image, bytes, prompt.trim().take(MAX_IMAGE_CAPTION).ifBlank { settings.prompt })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error(e.localizedMessage.orEmpty().redactValue(connection.provider.apiKey).ifBlank { "无法生成图片描述" })
                }
            }
        }
    }

    internal suspend fun askImageDescription(image: ChatImage, prompt: String = "", options: CaptionSettings = host.current.prefs.captions): String? {
        val instruction = if (options.source == CaptionSource.MULTIMODAL && options.ask && prompt.isBlank()) {
            host.script.requestScriptInput(ScriptDialog(kind = "input", title = "图片描述提示词", text = image.name, initial = options.prompt))
                ?: return null
        } else prompt
        val result = describeImage(image, instruction, options)
        val approved = if (options.refine) {
            host.script.requestScriptInput(ScriptDialog(kind = "input", title = "确认图片描述", text = image.name, initial = result))
        } else result
        return approved?.trim()?.take(MAX_IMAGE_CAPTION)?.takeIf(String::isNotBlank)
    }

    internal suspend fun prepareImageCaptions(chatId: String, images: List<ChatImage>): Map<String, String> {
        val prepared = host.current.draftImageCaptions[chatId].orEmpty().filterKeys { id -> images.any { it.id == id } }.toMutableMap()
        val options = host.current.prefs.captions
        if (!options.automatic || images.all { it.id in prepared }) return prepared
        if (!host.current.captionReady(options)) {
            host.notice("请先配置自动图片描述的模型")
            return prepared
        }
        for (image in images) {
            if (image.id in prepared) continue
            try {
                askImageDescription(image, options = options)?.let { prepared[image.id] = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                host.notice("图片描述生成失败 · ${e.localizedMessage ?: image.name}")
            }
        }
        return prepared
    }

    fun downloadCaptionModel() = host.utilityWork("正在下载本地图片描述模型…") {
        installCaptionModel { progress ->
            var completed = 0L
            for (part in CaptionWeights.entries) {
                if (!host.localCaptioner.installed(part)) {
                    downloadModelWeights(part.path, { input -> host.localCaptioner.install(part, input) { progress(completed + it) } }) {
                        host.mutable.update { it.copy(utility = "正在从镜像下载本地图片描述模型…") }
                    }
                }
                completed += part.size
                progress(completed)
            }
        }
    }

    fun importCaptionModel(uri: Uri) = host.utilityWork("正在导入本地图片描述模型…") {
        installCaptionModel { progress ->
            withContext(Dispatchers.IO) {
                requireNotNull(host.resolver.openInputStream(uri)) { "无法打开模型包" }.use { host.localCaptioner.importArchive(it, progress) }
            }
        }
    }

    private suspend fun installCaptionModel(read: suspend ((Long) -> Unit) -> Unit) {
        val context = currentCoroutineContext()
        val total = CaptionWeights.entries.sumOf(CaptionWeights::size)
        host.mutable.update { it.copy(localCaptionProgress = 0) }
        var last = 0
        try {
            read { bytes ->
                context.ensureActive()
                val percent = (bytes * 100 / total).toInt()
                if (percent != last) {
                    last = percent
                    host.mutable.update { it.copy(localCaptionProgress = percent) }
                }
            }
            host.notice("本地图片描述模型已就绪")
        } finally {
            host.mutable.update { it.copy(localCaptionInstalled = host.localCaptioner.installed(), localCaptionProgress = null) }
        }
    }

    fun removeCaptionModel() = host.work {
        withContext(Dispatchers.IO) { host.localCaptioner.remove() }
        host.mutable.update { it.copy(localCaptionInstalled = false) }
    }

    fun exportImage(uri: Uri, image: ChatImage) = host.work {
        withContext(Dispatchers.IO) {
            host.writeUri(uri, requireNotNull(host.imageStore.bytes(image)) { "图片文件不可用" })
        }
        host.notice("图片已保存")
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

    fun showImageGallery() = host.work { loadImageGallery() }

    suspend fun expressionSprites(card: Card): List<ExpressionSprite> = withContext(Dispatchers.IO) {
        val saved = card.expressionSprites ?: host.store.gallery(card.id, null).mapNotNull { item ->
            val label = item.prompt.takeIf { it.startsWith("表情 · ") }?.removePrefix("表情 · ")?.let(::spriteLabel)
            label?.let { ExpressionSprite(it, item.image) }
        }
        saved.distinctBy { it.label to it.image.id }.take(MAX_EXPRESSION_SPRITES).filter { host.imageStore.file(it.image).isFile }
    }

    suspend fun expressionFrame(chatId: String, subject: ExpressionSubject, forScene: Boolean = false): ExpressionFrame? {
        val snapshot = host.current
        val chat = snapshot.chat?.takeIf { it.id == chatId } ?: return null
        val settings = chat.options.expressions
        if (settings.mode == ExpressionMode.OFF && !forScene) return null
        val sprites = expressionSprites(subject.card)
        val seed = (subject.reply?.date ?: chatId).hashCode() + (subject.reply?.index ?: 0)
        if (settings.mode == ExpressionMode.OFF) return chooseExpression(sprites, null, "neutral", seed)
        if (sprites.isEmpty() && settings.fallback != EXPRESSION_EMOJI) return null
        val manual = snapshot.manualExpressions[subject.card.id]?.takeIf { it.chatId == chatId && it.reply == subject.reply }
        val reply = subject.reply?.text
        val connection = snapshot.prefs.connectionFor(chat)
        val label = manual?.label ?: when {
            settings.mode == ExpressionMode.LOCAL && reply != null && snapshot.localExpressionInstalled -> inferLocalExpression(reply)
            settings.mode == ExpressionMode.MAIN_API && reply != null && connection != null -> {
                val labels = (sprites.map(ExpressionSprite::label) + if (settings.fallback == EXPRESSION_EMOJI) expressionEmoji.keys else emptySet())
                    .distinct().take(MAX_EXPRESSION_SPRITES)
                inferExpression(connection, settings, labels, reply)
            }
            else -> null
        }
        return chooseExpression(sprites, label, settings.fallback, seed, manual?.imageId)
    }

    private suspend fun inferExpression(connection: ModelConnection, settings: ExpressionSettings, labels: List<String>, reply: String): String? {
        val text = reply.takeLast(3000)
        val key = ExpressionInference(connection.provider.id, connection.model, settings.instruction, labels, text)
        return cachedExpression(key) {
            val answer = try {
                withTimeoutOrNull(18_000) { ModelService.utility(connection, expressionPrompt(settings, labels, text), null) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            answer?.let { expressionAnswer(it, labels) }
        }
    }

    private suspend fun inferLocalExpression(reply: String): String? {
        val text = reply.takeLast(3000)
        val key = ExpressionInference("", EXPRESSION_MODEL_URL, "", emptyList(), text)
        return cachedExpression(key) {
            try {
                withContext(Dispatchers.Default) { host.localExpressions.classify(text) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                host.mutable.update { it.copy(localExpressionFailure = "本地表情模型读取失败，可以重新下载或导入模型。") }
                null
            } catch (_: LinkageError) {
                host.mutable.update { it.copy(localExpressionFailure = "设备无法加载本地表情运行库。") }
                null
            }
        }
    }

    private suspend fun cachedExpression(key: ExpressionInference, classify: suspend () -> String?): String? {
        synchronized(expressionCache) { expressionCache[key] }?.let { return it }
        val label = classify() ?: return null
        synchronized(expressionCache) {
            if (expressionCache.size >= 128) expressionCache.remove(expressionCache.keys.first())
            expressionCache[key] = label
        }
        return label
    }

    fun downloadExpressionModel() = host.utilityWork("正在下载本地表情模型…") {
        installExpressionModel { install ->
            request(
                EXPRESSION_MODEL_URL,
                headers = mapOf("Accept" to "application/octet-stream"),
                readTimeout = 45_000,
                followRedirects = true,
            ) { input, _ -> install(input) }
        }
    }

    fun importExpressionModel(uri: Uri) = host.utilityWork("正在导入本地表情模型…") {
        installExpressionModel { install ->
            withContext(Dispatchers.IO) {
                requireNotNull(host.resolver.openInputStream(uri)) { "无法打开模型文件" }.use(install)
            }
        }
    }

    private suspend fun installExpressionModel(read: suspend ((InputStream) -> Unit) -> Unit) {
        val context = currentCoroutineContext()
        host.mutable.update { it.copy(localExpressionProgress = 0, localExpressionFailure = null) }
        var lastPercent = 0
        try {
            read { input ->
                host.localExpressions.install(input) { received ->
                    context.ensureActive()
                    val percent = (received * 100 / EXPRESSION_MODEL_BYTES).toInt()
                    if (lastPercent != percent) {
                        lastPercent = percent
                        host.mutable.update { it.copy(localExpressionProgress = percent) }
                    }
                }
            }
            host.notice("本地表情模型已就绪")
        } finally {
            host.mutable.update { it.copy(localExpressionProgress = null, localExpressionInstalled = host.localExpressions.installed()) }
        }
    }

    fun removeExpressionModel() = host.work {
        withContext(Dispatchers.IO) { host.localExpressions.remove() }
        host.mutable.update { it.copy(localExpressionInstalled = false, localExpressionFailure = null) }
        host.notice("已移除本地表情模型")
    }

    fun saveExpressionSettings(settings: ExpressionSettings) = host.work {
        val chat = host.current.chat ?: return@work
        host.persist(chat.copy(options = chat.options.copy(expressions = settings.copy(instruction = settings.instruction.trim().take(2000)))))
    }

    fun importExpressionSprites(cardId: String, files: List<Uri>, pack: Boolean, label: String) = host.work {
        if (files.isEmpty()) return@work
        val card = requireNotNull(host.current.cards.firstOrNull { it.id == cardId }) { "找不到角色" }
        val explicit = if (label.isBlank() || pack) null else requireNotNull(expressionLabel(label)) { "表情标签无效" }
        val pictures = withContext(Dispatchers.IO) {
            files.distinct().flatMap { uri ->
                currentCoroutineContext().ensureActive()
                requireNotNull(host.resolver.openInputStream(uri)) { "无法打开表情图片" }.use { input ->
                    if (pack) readSpritePack(input, host.imageStore::import) else {
                        val name = host.app.documentName(uri).takeUnless { it == "导入的聊天" } ?: "neutral"
                        val key = explicit ?: requireNotNull(spriteLabel(name)) { "表情文件名无效" }
                        listOf(ExpressionSprite(key, host.imageStore.import(input.readLimited(MAX_IMAGE_SOURCE), name)))
                    }
                }
            }
        }
        val merged = (expressionSprites(card) + pictures).distinctBy { it.label to it.image.id }
        require(merged.size <= MAX_EXPRESSION_SPRITES) { "每个角色最多保存 $MAX_EXPRESSION_SPRITES 张表情" }
        saveExpressionSprites(card, merged)
        host.notice("已导入 ${pictures.size} 张表情")
    }

    fun removeExpressionSprite(cardId: String, sprite: ExpressionSprite) = host.work {
        val card = host.current.cards.firstOrNull { it.id == cardId } ?: return@work
        saveExpressionSprites(card, expressionSprites(card).filterNot { it == sprite })
    }

    private suspend fun saveExpressionSprites(card: Card, sprites: List<ExpressionSprite>) {
        val cards = withContext(Dispatchers.IO) { host.store.save(card.withExpressionSprites(sprites)); host.store.cards() }
        host.mutable.update { it.copy(cards = cards) }
    }

    fun showExpression(cardId: String, sprite: ExpressionSprite, done: () -> Unit = {}) = host.work {
        setManualExpression(cardId, sprite.label, sprite.image.id)
        done()
    }

    private suspend fun setManualExpression(cardId: String, label: String, imageId: String? = null): String {
        val chat = requireNotNull(host.current.chat) { "请先打开对话" }
        val subject = requireNotNull(host.current.expressionSubjects(chat).firstOrNull { it.card.id == cardId }) { "该角色不在对话中" }
        val settings = chat.options.expressions
        check(
            expressionSprites(subject.card).any { it.label == label && (imageId == null || it.image.id == imageId) } ||
                (settings.fallback == EXPRESSION_EMOJI && label in expressionEmoji),
        ) { "找不到表情 $label" }
        if (settings.mode == ExpressionMode.OFF) host.persist(chat.copy(options = chat.options.copy(expressions = settings.copy(mode = ExpressionMode.MANUAL))))
        host.mutable.update { state ->
            val choices = state.manualExpressions.filterValues { it.chatId == chat.id }
            state.copy(manualExpressions = choices + (cardId to ManualExpression(chat.id, subject.reply, label, imageId)))
        }
        return label
    }

    internal suspend fun scriptExpression(command: String, named: Map<String, String>, input: String): String {
        val chat = requireNotNull(host.current.chat) { "请先打开对话" }
        val subjects = host.current.expressionSubjects(chat)
        val requested = named["name"]
        val subject = if (requested == null) host.current.regexCard(chat.messages.lastOrNull { it.role == "assistant" && !it.hidden })
            ?.let { actor -> subjects.firstOrNull { it.card.id == actor.id } }
        else subjects.singleOrNull { it.card.name.equals(requested, true) || it.card.id == requested }
        val card = requireNotNull(subject) { "找不到表情对应的角色" }.card
        if (command == "list") return JSONArray(expressionSprites(card).map(ExpressionSprite::label).distinct()).toString()
        return setManualExpression(card.id, requireNotNull(expressionLabel(input)) { "请填写表情标签" })
    }

    fun closeImageGallery() = host.mutable.update { it.copy(imageGallery = null) }

    internal suspend fun loadImageGallery(): String {
        val card = host.current.card
        val group = host.current.group
        val title = card?.name ?: group?.name ?: error("请先打开角色或群组")
        val items = withContext(Dispatchers.IO) { host.store.gallery(card?.id, group?.id) }
        host.mutable.update { it.copy(imageGallery = ImageGallery(title, items)) }
        return items.size.toString()
    }

    fun clearDrawingBackground(chatId: String) = host.work {
        val chat = host.current.chat?.takeIf { it.id == chatId } ?: return@work
        host.persist(chat.copy(options = chat.options.copy(background = null)))
    }

    fun setDrawingBackground(chatId: String, image: ChatImage) = host.work {
        val chat = host.current.chat?.takeIf { it.id == chatId } ?: return@work
        require(host.imageStore.file(image).isFile) { "找不到图库中的图片" }
        host.persist(chat.copy(options = chat.options.copy(background = image)))
        closeImageGallery()
        host.notice("已应用对话背景")
    }

    fun importChatBackground(chatId: String, uri: Uri) = host.work {
        val chat = host.current.chat?.takeIf { it.id == chatId } ?: return@work
        host.generation.stopAuto()
        val image = withContext(Dispatchers.IO) {
            val name = host.app.documentName(uri).takeUnless { it == "导入的聊天" } ?: "对话背景"
            requireNotNull(host.resolver.openInputStream(uri)) { "无法打开背景图片" }.use {
                host.imageStore.import(it.readLimited(MAX_IMAGE_SOURCE), name)
            }
        }
        host.persist(chat.copy(options = chat.options.copy(background = image)))
        host.notice("已应用对话背景")
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

    internal fun launchDrawing(chatId: String, action: suspend (Chat) -> Unit): Boolean {
        val chat = host.current.chat?.takeIf { it.id == chatId } ?: return false
        if (host.current.busy || host.current.generation != null || host.current.scriptRunning) return false
        host.generation.stopAuto()
        host.mutable.update { it.copy(busy = true, generatingImage = true) }
        val job = host.scope.launch(start = CoroutineStart.LAZY) {
            try {
                action(chat)
            } catch (e: CancellationException) {
                host.notice("已停止图像生成")
                throw e
            } catch (e: Exception) {
                host.notice(e.localizedMessage ?: "图像生成失败，请重试")
            } finally {
                host.mutable.update { it.copy(busy = false, generatingImage = false) }
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
