package app.tavern.nativeclient

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.coroutines.EmptyCoroutineContext

internal data class Generation(
    val chatId: String, val id: String = newId(), val text: String = "",
    val replacedMessages: Int = 0, val speaker: String = "", val action: ReplyAction = ReplyAction.NORMAL,
    val characterId: String? = null, val reasoning: String = "",
)
internal data class ModelCatalog(val providerId: String, val endpoint: String, val models: List<String>, val contexts: Map<String, Int>)
internal data class PromptCapture(val chatId: String, val model: String, val messages: JSONArray, val loreBudget: LoreBudgetUsage? = null)
private data class QueuedNarration(val chatId: String?, val narration: Narration)
internal data class TavernState(
    val cards: List<Card> = emptyList(),
    val groups: List<TavernGroup> = emptyList(),
    val books: List<WorldBook> = emptyList(),
    val presets: List<PromptPreset> = emptyList(),
    val history: List<ChatSummary> = emptyList(),
    val cardId: String? = null,
    val groupId: String? = null,
    val chat: Chat? = null,
    val prefs: Preferences = Preferences(),
    val busy: Boolean = true,
    val generation: Generation? = null,
    val autoChatId: String? = null,
    val temporaryPersonaId: String? = null,
    val suggestedDraft: DraftSuggestion? = null,
    val lastPrompt: PromptCapture? = null,
    val scriptRunning: Boolean = false,
    val scriptDialog: ScriptDialog? = null,
    val toolProgress: String? = null,
    val messageSelection: MessageSelectionRequest? = null,
    val draftImages: Map<String, List<ChatImage>> = emptyMap(),
    val draftImageCaptions: Map<String, Map<String, String>> = emptyMap(),
    val draftDocuments: Map<String, List<ChatDocument>> = emptyMap(),
    val generatingImage: Boolean = false,
    val imageGallery: ImageGallery? = null,
    val manualExpressions: Map<String, ManualExpression> = emptyMap(),
    val localExpressionInstalled: Boolean = false,
    val localExpressionProgress: Int? = null,
    val localExpressionFailure: String? = null,
    val installedEmbeddingModels: Set<LocalEmbeddingModel> = emptySet(),
    val localEmbeddingProgress: Int? = null,
    val localCaptionInstalled: Boolean = false,
    val localCaptionProgress: Int? = null,
    val comfyWorkflows: List<ComfyWorkflow> = builtInComfyWorkflows,
    val speaking: Boolean = false,
    val utility: String? = null,
    val dataDocuments: List<DataDocument> = emptyList(),
    val openDocument: DocumentText? = null,
    val openDataBank: Boolean = false,
    val notice: String? = null,
    val catalog: ModelCatalog? = null,
    val remoteCards: List<RemoteCard>? = null,
    val recentChats: List<RecentChat> = emptyList(),
    val libraryUsage: Map<String, LibraryUsage> = emptyMap(),
) {
    val card get() = if (chat?.isTemporary == true) temporaryAssistant else cards.firstOrNull { it.id == cardId }
    val welcomeAssistant get() = cards.firstOrNull { it.id == prefs.welcome.assistantId }
        ?: cards.firstOrNull { it.id == defaultWelcomeAssistant.id } ?: defaultWelcomeAssistant
    val group get() = groups.firstOrNull { it.id == groupId }
    val persona get() = prefs.personaFor(chat, cardId ?: groupId, temporaryPersonaId)
    fun comfyWorkflow(id: String?) = comfyWorkflows.firstOrNull { it.id == (id ?: DEFAULT_COMFY_WORKFLOW) }

    fun vectorSource(options: VectorPreferences = prefs.vectors) =
        options.source(prefs.providers)?.takeIf { it !is EmbeddingSource.Local || it.model in installedEmbeddingModels }

    fun captionReady(options: CaptionSettings = prefs.captions) = when (options.source) {
        CaptionSource.MULTIMODAL -> options.connection(prefs, chat) != null
        CaptionSource.LOCAL -> localCaptionInstalled
    }

    fun imageCaption(chatId: String, target: CaptionImageTarget): String = if (target.messageIndex == null) {
        draftImageCaptions[chatId]?.get(target.image.id).orEmpty()
    } else chat?.takeIf { it.id == chatId }?.messages?.getOrNull(target.messageIndex)?.captionFor(target.image).orEmpty()

    fun presetFor(chat: Chat?): PromptPreset? {
        val selected = chat?.options?.promptPresetId
        if (selected != null) {
            if (selected.isEmpty()) return null
            presets.firstOrNull { it.id == selected }?.let { return it }
        }
        if (chat != null) {
            val name = card?.name ?: group?.name
            presets.firstOrNull { it.name == name }?.let { return it }
        }
        return presets.firstOrNull { it.id == prefs.defaultPresetId }
    }

    fun replyingCharacter(message: Message): Card? {
        if (message.role != "assistant") return null
        val members = group?.members(cards)
            ?: return card?.takeIf { message.groupTurnId == null || message.isBy(it) }
        if (message.characterId != null && cards.any { it.id == message.characterId }) {
            return members.firstOrNull { it.id == message.characterId }
        }
        return members.singleOrNull { it.name.equals(message.name, true) }
    }

    fun regexCard(message: Message? = null): Card? =
        message?.let(::replyingCharacter) ?: card ?: group?.participants(cards)?.firstOrNull()

    fun textRules(chat: Chat?, character: Card? = regexCard()): RegexRuntime =
        prefs.regex.runtime(character, persona.name, presetFor(chat), group?.members(cards).orEmpty().map(Card::name))

    fun regexRules(target: RegexTarget): List<RegexRule> = when (target.scope) {
        RegexScope.GLOBAL -> prefs.regex.global
        RegexScope.CARD -> cards.firstOrNull { it.id == target.id }?.regexRules.orEmpty()
        RegexScope.PRESET -> presets.firstOrNull { it.id == target.id }?.regexRules.orEmpty()
    }

    fun regexAllowed(target: RegexTarget): Boolean = when (target.scope) {
        RegexScope.GLOBAL -> prefs.regex.enabled
        RegexScope.CARD -> target.id in prefs.regex.allowedCards
        RegexScope.PRESET -> target.id in prefs.regex.allowedPresets
    }
}

internal class TavernViewModel(application: Application) : AndroidViewModel(application) {
    private val store = TavernStore(application)
    private val resolver = application.contentResolver
    private val savedImages = ChatImageStore(java.io.File(application.filesDir, "chat-images"))
    private val savedDocuments = ChatDocumentStore(java.io.File(application.filesDir, "chat-documents"))
    private var temporaryMedia: TemporaryChatMedia? = null
    private val imageStore get() = temporaryMedia?.images ?: savedImages
    private val documentStore get() = temporaryMedia?.documents ?: savedDocuments
    private val embedded = EmbeddedLibrary(java.io.File(application.noBackupFilesDir, "sillytavern/data/default-user"), savedDocuments, savedImages)
    val canImportEmbedded get() = embedded.exists
    private val imageService = ImageService()
    private val translationService = TranslationService()
    private val localExpressions = LocalExpressions(java.io.File(application.noBackupFilesDir, "expression-model")) {
        application.assets.open("expression-model/$it")
    }
    private val localEmbeddings = LocalEmbeddings(java.io.File(application.noBackupFilesDir, "embedding-models")) {
        application.assets.open("embedding-models/$it")
    }
    private val localCaptioner = LocalCaptioner(java.io.File(application.noBackupFilesDir, "caption-model")) {
        application.assets.open("caption-model/vocab.txt")
    }
    private val expressionCache = linkedMapOf<ExpressionInference, String>()
    private val loreTokenizers = LoreTokenizers(application.assets::open)
    private val embeddingService = EmbeddingService(local = localEmbeddings)
    private val vectorSearch by lazy { VectorSearch(store, embeddingService) }
    private val mutable = MutableStateFlow(TavernState(busy = false))
    val state = mutable.asStateFlow()
    private val current get() = mutable.value
    private var generator: Job? = null
    @Volatile private var draftInProgress: String? = null
    private var autoTimer: Job? = null
    private var server: TavernServer? = null
    private var scriptJob: Job? = null
    private var imageJob: Job? = null
    private var utilityJob: Job? = null
    private val speechPlayer = lazy { SpeechPlayback(application, ::stopNarration) }
    private val narrationQueue = ArrayDeque<QueuedNarration>()
    private var narrationJob: Job? = null
    private var narrationOwner: QueuedNarration? = null
    private var voiceForeground = true
    private var scriptAnswer: CompletableDeferred<String?>? = null
    private val customTools = linkedMapOf<String, ModelTool>()
    private var nativeDrawingToolRemoved = false
    private var preventNestedQuickReplies = false
    private var quickReplyDepth = 0

    init {
        viewModelScope.launch {
            state.map { it.chat?.id }.distinctUntilChanged().collect { id ->
                val reading = narrationOwner?.chatId
                if (reading != null && reading != id) stopNarration()
            }
        }
        work {
            val loaded = withContext(Dispatchers.IO) {
                TemporaryChatMedia.cleanPreviousProcess(application.cacheDir)
                val prefs = store.prefs()
                val last = store.lastChatId().takeIf { prefs.welcome.autoOpen }?.let { id ->
                    runCatching { store.chat(id) }.getOrNull()
                }
                TavernState(cards = store.cards(), prefs = prefs, groups = store.groups(), books = store.books(),
                    cardId = last?.cardId, groupId = last?.groupId, chat = last,
                    history = last?.let { store.chats(it.cardId, it.groupId) }.orEmpty(),
                    recentChats = store.recentChats(prefs.welcome.pinnedIds),
                    presets = store.promptPresets(), comfyWorkflows = builtInComfyWorkflows + store.comfyWorkflows(),
                    localExpressionInstalled = localExpressions.installed(),
                    installedEmbeddingModels = LocalEmbeddingModel.entries.filterTo(mutableSetOf(), localEmbeddings::installed),
                    localCaptionInstalled = localCaptioner.installed(), busy = true)
            }
            mutable.update { loaded }
            quickEvent(QuickEvent.STARTUP)
            if (current.chat != null) quickEvent(QuickEvent.CHAT)
        }
    }

    fun notice(text: String?) = mutable.update { it.copy(notice = text) }
    fun clearModels() = mutable.update { it.copy(catalog = null) }
    fun portrait(card: Card) = store.portraitFile(card.id)
    fun personaAvatar(persona: PersonaProfile) = persona.avatar?.let(store::personaAvatar)
    fun imageFile(image: ChatImage) = imageStore.file(image)

    fun attachChatDocuments(chatId: String, messageIndex: Int?, uris: List<Uri>) {
        val chat = current.chat?.takeIf { it.id == chatId } ?: return
        if (uris.isEmpty()) return
        val target = messageIndex?.let { chat.messages.getOrNull(it) ?: return }
        val existing = target?.documents ?: current.draftDocuments[chatId].orEmpty()
        utilityWork("正在读取聊天文档…") {
            val capacity = (MAX_CHAT_DOCUMENTS - existing.size).coerceAtLeast(0)
            var lastFailure: String? = null
            val app = getApplication<Application>()
            val documents = withContext(Dispatchers.IO) {
                uris.take(capacity).mapNotNull { uri ->
                    currentCoroutineContext().ensureActive()
                    val name = app.documentName(uri)
                    try {
                        val bytes = requireNotNull(resolver.openInputStream(uri)) { "无法打开文件" }
                            .use { it.readLimited(MAX_DOCUMENT_INPUT_BYTES) }
                        documentStore.import(openDataDocument(name, resolver.getType(uri), bytes, app.cacheDir))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        lastFailure = "无法添加 $name · ${e.localizedMessage.orEmpty()}"
                        null
                    }
                }
            }
            if (documents.isNotEmpty()) {
                val combined = (existing + documents).distinctBy(ChatDocument::id)
                if (target == null) {
                    mutable.update { it.copy(draftDocuments = it.draftDocuments + (chatId to combined)) }
                } else {
                    val messages = chat.messages.toMutableList()
                    messages[requireNotNull(messageIndex)] = target.copy(documents = combined)
                    persist(chat.copy(messages = messages, updated = System.currentTimeMillis()))
                }
            }
            lastFailure?.let(::notice)
            if (lastFailure == null && uris.size > capacity) notice("每条消息最多附带 $MAX_CHAT_DOCUMENTS 份文档")
        }
    }

    fun removeDraftDocument(chatId: String, document: ChatDocument) {
        if (current.busy || current.generation != null || current.scriptRunning) return
        mutable.update { state ->
            val keep = state.draftDocuments[chatId].orEmpty().filterNot { it.id == document.id }
            state.copy(draftDocuments = if (keep.isEmpty()) state.draftDocuments - chatId else state.draftDocuments + (chatId to keep))
        }
    }

    fun removeMessageDocument(chatId: String, index: Int, document: ChatDocument) = work {
        val chat = current.chat?.takeIf { it.id == chatId } ?: return@work
        val target = chat.messages.getOrNull(index) ?: return@work
        val messages = chat.messages.toMutableList()
        messages[index] = target.copy(documents = target.documents.filterNot { it.id == document.id })
        persist(chat.copy(messages = messages, updated = System.currentTimeMillis()))
    }

    suspend fun readChatDocument(document: ChatDocument, limit: Int): String? = withContext(Dispatchers.IO) {
        documentStore.text(document)?.take(limit)
    }

    fun exportChatDocument(document: ChatDocument, uri: Uri) = work {
        withContext(Dispatchers.IO) {
            write(uri, requireNotNull(documentStore.bytes(document)) { "找不到附件文本" })
        }
        notice("文档已导出")
    }

    fun voiceForeground(visible: Boolean) {
        voiceForeground = visible
        if (!visible) stopNarration()
    }

    fun saveVoice(options: VoicePreferences, done: () -> Unit) = work {
        require(options.backendId.isBlank() || current.prefs.providers.any { it.id == options.backendId && it.template == null }) {
            "请选择已保存的自定义语音服务"
        }
        require(options.backendId.isBlank() || options.backendModel.isNotBlank() && options.backendVoice.isNotBlank()) {
            "请填写语音模型和默认音色"
        }
        if (options.filter.isNotBlank()) {
            val result = regexTest(RegexRule(pattern = options.filter), "朗读测试", RegexNames(null, ""))
            require(result.issue == null) { result.issue ?: "朗读正则无效" }
        }
        if (options.language.isNotBlank()) require(java.util.Locale.forLanguageTag(options.language).language.isNotBlank()) {
            "识别语言应使用语言标签，例如 zh-CN"
        }
        stopNarration()
        persistPreferences(current.prefs.copy(voice = options))
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
        if (current.speaking) mutable.update { it.copy(speaking = false) }
    }

    private fun enqueueSpeech(text: String, chatId: String?, options: VoicePreferences, voice: String, replace: Boolean) {
        if (!voiceForeground || text.isBlank()) return
        if (chatId != null && chatId != current.chat?.id) return
        val backend = if (options.backendId.isEmpty()) null else {
            current.prefs.providers.firstOrNull { it.id == options.backendId && it.template == null }
                ?: return notice("请重新选择朗读使用的语音服务")
        }
        if (replace) stopNarration()
        if (narrationQueue.size >= 20) narrationQueue.removeFirst()
        narrationQueue.addLast(QueuedNarration(chatId, Narration(text, options, voice, backend)))
        if (narrationJob?.isActive == true) return
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                while (narrationQueue.isNotEmpty()) {
                    val next = narrationQueue.removeFirst()
                    if (next.chatId != null && next.chatId != current.chat?.id) continue
                    narrationOwner = next
                    val narration = next.narration
                    val spoken = withContext(Dispatchers.Default) { narration.options.speechText(narration.text) }
                    if (spoken.isNotEmpty()) speechPlayer.value.play(narration.copy(text = spoken))
                }
            } catch (e: TimeoutCancellationException) {
                notice("语音服务响应超时")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice(e.localizedMessage ?: "无法朗读消息")
            } finally {
                if (narrationJob === coroutineContext[Job]) {
                    narrationQueue.clear()
                    narrationJob = null
                    narrationOwner = null
                    mutable.update { it.copy(speaking = false) }
                }
            }
        }
        narrationJob = job
        mutable.update { it.copy(speaking = true) }
        job.start()
    }

    private fun voiceActor(message: Message): String? = when (message.role) {
        "user" -> (message.personaId ?: current.prefs.personas.firstOrNull { it.name == message.name }?.id
            ?: current.persona.id).let { "persona:$it" }
        "assistant" -> (message.characterId ?: current.replyingCharacter(message)?.id)?.let { "character:$it" }
        else -> null
    }

    fun narrate(message: Message, automatic: Boolean = false) {
        val chat = current.chat ?: return
        val options = current.prefs.voice
        if (!options.enabled) return
        if (automatic && (!options.automatic || message.role == "user" && !options.includeUser)) return
        val source = if (options.translatedText) message.displayText else message.text
        val text = current.regexCard(message)?.let { expand(source, it, current.persona.name) } ?: source
        enqueueSpeech(text, chat.id, options, options.actorVoice(voiceActor(message)), replace = !automatic)
    }

    private fun scriptNarrate(text: String, actor: String?) {
        val options = current.prefs.voice
        val chat = current.chat ?: return
        if (!options.enabled) return
        val key = if (actor.isNullOrBlank()) current.regexCard()?.id?.let { "character:$it" }
            else current.cards.firstOrNull { it.name.equals(actor, true) }?.id?.let { "character:$it" }
                ?: current.prefs.personas.firstOrNull { it.name.equals(actor, true) }?.id?.let { "persona:$it" }
        require(actor.isNullOrBlank() || key != null) { "找不到指定的朗读角色或人设" }
        enqueueSpeech(text, chat.id, options, options.actorVoice(key), replace = false)
    }

    override fun onCleared() {
        stopNarration()
        if (speechPlayer.isInitialized()) speechPlayer.value.close()
        val media = temporaryMedia
        Dispatchers.IO.dispatch(EmptyCoroutineContext) {
            media?.close()
            localExpressions.close()
            localEmbeddings.close()
        }
        super.onCleared()
    }

    fun attachImages(chatId: String, documents: List<Uri>, ready: ((ChatImage) -> Unit)? = null) {
        if (current.chat?.id != chatId || documents.isEmpty()) return
        val existing = current.draftImages[chatId].orEmpty()
        work {
            var lastFailure: String? = null
            val imported = withContext(Dispatchers.IO) {
                documents.take((MAX_CHAT_IMAGES - existing.size).coerceAtLeast(0)).mapNotNull { uri ->
                    val name = getApplication<Application>().documentName(uri).takeUnless { it == "导入的聊天" } ?: "图片"
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
            mutable.update { it.copy(draftImages = it.draftImages + (chatId to (existing + imported).distinctBy(ChatImage::id))) }
            imported.singleOrNull()?.let { ready?.invoke(it) }
            lastFailure?.let(::notice)
            if (lastFailure == null && documents.size > MAX_CHAT_IMAGES - existing.size) notice("每条消息最多附带 $MAX_CHAT_IMAGES 张图片")
        }
    }

    fun removeDraftImage(chatId: String, image: ChatImage) {
        if (current.busy || current.generation != null || current.scriptRunning) return
        mutable.update { state ->
            val captions = state.draftImageCaptions[chatId].orEmpty() - image.id
            state.copy(draftImages = state.draftImages + (chatId to state.draftImages[chatId].orEmpty().filterNot { it.id == image.id }),
                draftImageCaptions = state.draftImageCaptions + (chatId to captions))
        }
    }

    private fun importCaptionableImage(uri: Uri, name: String): ChatImage {
        val bytes = requireNotNull(resolver.openInputStream(uri)) { "无法打开图片" }.use { it.readLimited(MAX_IMAGE_SOURCE) }
        return imageStore.import(bytes, name)
    }

    fun saveCaptionSettings(settings: CaptionSettings, done: () -> Unit) = work {
        persistPreferences(current.prefs.copy(captions = CaptionSettings.from(settings.json())))
        done()
    }

    fun generateImageCaption(image: ChatImage, prompt: String, done: (String) -> Unit) =
        utilityWork("正在生成图片描述…") {
            done(describeImage(image, prompt))
        }

    fun saveImageCaption(chatId: String, target: CaptionImageTarget, text: String, done: () -> Unit) = work {
        val chat = current.chat?.takeIf { it.id == chatId } ?: return@work
        fun updated(captions: Map<String, String>): Map<String, String> {
            val value = text.trim().take(MAX_IMAGE_CAPTION)
            return if (value.isEmpty()) captions - target.image.id else captions + (target.image.id to value)
        }
        val index = target.messageIndex
        if (index == null) {
            check(current.draftImages[chatId].orEmpty().any { it.id == target.image.id }) { "草稿中已没有这张图片" }
            mutable.update {
                it.copy(draftImageCaptions = it.draftImageCaptions + (chatId to updated(it.draftImageCaptions[chatId].orEmpty())))
            }
        } else {
            val message = chat.messages.getOrNull(index)
            check(message != null && message.displayImages.any { it.id == target.image.id }) { "消息中已没有这张图片" }
            val messages = chat.messages.toMutableList()
            messages[index] = message.copy(imageCaptions = updated(message.imageCaptions))
            persist(chat.copy(messages = messages, updated = System.currentTimeMillis()))
        }
        done()
    }

    private suspend fun describeImage(image: ChatImage, prompt: String, settings: CaptionSettings = current.prefs.captions): String {
        val bytes = withContext(Dispatchers.IO) { checkNotNull(imageStore.bytes(image)) { "找不到图片文件" } }
        return when (settings.source) {
            CaptionSource.LOCAL -> withContext(Dispatchers.Default) { localCaptioner.caption(bytes) }
            CaptionSource.MULTIMODAL -> {
                val connection = checkNotNull(settings.connection(current.prefs, current.chat)) { "请先配置图片描述模型" }
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

    private suspend fun askImageDescription(image: ChatImage, prompt: String = "", options: CaptionSettings = current.prefs.captions): String? {
        val instruction = if (options.source == CaptionSource.MULTIMODAL && options.ask && prompt.isBlank()) {
            requestScriptInput(ScriptDialog(kind = "input", title = "图片描述提示词", text = image.name, initial = options.prompt))
                ?: return null
        } else prompt
        val result = describeImage(image, instruction, options)
        val approved = if (options.refine) {
            requestScriptInput(ScriptDialog(kind = "input", title = "确认图片描述", text = image.name, initial = result))
        } else result
        return approved?.trim()?.take(MAX_IMAGE_CAPTION)?.takeIf(String::isNotBlank)
    }

    private suspend fun prepareImageCaptions(chatId: String, images: List<ChatImage>): Map<String, String> {
        val prepared = current.draftImageCaptions[chatId].orEmpty().filterKeys { id -> images.any { it.id == id } }.toMutableMap()
        val options = current.prefs.captions
        if (!options.automatic || images.all { it.id in prepared }) return prepared
        if (!current.captionReady(options)) {
            notice("请先配置自动图片描述的模型")
            return prepared
        }
        for (image in images) {
            if (image.id in prepared) continue
            try {
                askImageDescription(image, options = options)?.let { prepared[image.id] = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice("图片描述生成失败 · ${e.localizedMessage ?: image.name}")
            }
        }
        return prepared
    }

    fun exportImage(uri: Uri, image: ChatImage) = work {
        withContext(Dispatchers.IO) {
            write(uri, requireNotNull(imageStore.bytes(image)) { "图片文件不可用" })
        }
        notice("图片已保存")
    }

    fun generateImage(chatId: String, request: ImageRequest, mode: DrawingMode, target: Int?, remember: Boolean): Boolean =
        launchDrawing(chatId) { chat ->
            val settings = current.prefs.drawing
            if (remember) {
                val profile = request.recipe().profile
                persistPreferences(current.prefs.copy(drawing = settings.copy(profile = profile,
                    comfyWorkflowId = if (profile.protocol.usesWorkflow) profile.workflowId else settings.comfyWorkflowId)))
            }
            renderDrawing(chat, prepareDrawing(request, chat, mode, target), target = target, background = mode == DrawingMode.BACKGROUND)
        }

    fun generateNanoBanana(chatId: String, preset: NanoBananaPreset, note: String, includeReferences: Boolean): Boolean =
        launchDrawing(chatId) { chat ->
            val provider = requireNotNull(nanoBananaProvider(current.prefs, chat)) { "请先保存 Google Gemini API Key" }
            val references = if (includeReferences) current.draftImages[chat.id].orEmpty() else emptyList()
            val request = nanoBananaRequest(provider, preset, chat, drawingCard(chat), current.persona, note, references)
            renderDrawing(chat, request, background = preset == NanoBananaPreset.BACKGROUND, caption = preset.label)
        }

    fun draftDrawingPrompt(chat: Chat, mode: DrawingMode, text: String, done: (String) -> Unit): Boolean =
        utilityWork("正在编写绘图提示词…") { done(drawingPrompt(chat, mode, text, extendFree = mode == DrawingMode.FREE)) }

    fun saveDrawingSettings(settings: DrawingSettings, done: () -> Unit) = work {
        val previous = current.prefs.drawing
        persistPreferences(current.prefs.copy(drawing = settings))
        if (previous.functionTool != settings.functionTool || previous.templates["tool"] != settings.templates["tool"]) {
            nativeDrawingToolRemoved = false
            customTools.remove(DRAW_IMAGE_TOOL)
        }
        done()
    }

    fun readComfyWorkflow(uri: Uri, done: (ComfyWorkflow) -> Unit) = work {
        val app = getApplication<Application>()
        val imported = withContext(Dispatchers.IO) {
            val name = app.documentName(uri).substringBeforeLast('.').ifBlank { "导入的工作流" }
            val source = requireNotNull(resolver.openInputStream(uri)) { "无法打开工作流文件" }
                .use { it.readLimited(MAX_COMFY_WORKFLOW_BYTES) }.toString(Charsets.UTF_8)
            ComfyWorkflow(newId(), name, source).validated()
        }
        done(imported)
    }

    fun saveComfyWorkflow(workflow: ComfyWorkflow, done: (ComfyWorkflow) -> Unit) = work {
        require(!workflow.isBuiltIn) { "请复制内置工作流后编辑" }
        val saved = withContext(Dispatchers.IO) { workflow.validated() }
        require(current.comfyWorkflows.none { it.id != saved.id && it.name.equals(saved.name, true) }) { "已有同名工作流" }
        updateComfyWorkflows { saveComfyWorkflow(saved) }
        done(saved)
    }

    fun deleteComfyWorkflow(workflow: ComfyWorkflow, done: () -> Unit) = work {
        require(!workflow.isBuiltIn) { "内置工作流可以复制" }
        updateComfyWorkflows { deleteComfyWorkflow(workflow.id) }
        val settings = current.prefs.drawing
        val profile = settings.profile
        if (settings.comfyWorkflowId == workflow.id || profile?.workflowId == workflow.id) {
            persistPreferences(current.prefs.copy(drawing = settings.copy(
                comfyWorkflowId = settings.comfyWorkflowId.takeUnless { it == workflow.id },
                profile = if (profile?.workflowId == workflow.id) profile.copy(workflowId = null) else profile,
            )))
        }
        done()
    }

    private suspend fun updateComfyWorkflows(change: TavernStore.() -> Unit) {
        val workflows = withContext(Dispatchers.IO) { store.change(); store.comfyWorkflows() }
        mutable.update { it.copy(comfyWorkflows = builtInComfyWorkflows + workflows) }
    }

    fun exportComfyWorkflow(uri: Uri, workflow: ComfyWorkflow) = work {
        withContext(Dispatchers.IO) { write(uri, workflow.source.toByteArray(Charsets.UTF_8)) }
        notice("工作流 JSON 已导出")
    }

    fun testComfy(provider: Provider, protocol: ImageProtocol) = utilityWork("正在检查绘图服务…") {
        ComfyService().test(provider, protocol == ImageProtocol.COMFY_RUNPOD)
        notice("${protocol.label} 已连接")
    }

    fun loadComfyOptions(provider: Provider, done: (ComfyCatalog) -> Unit) = utilityWork("正在读取 ComfyUI 参数…") {
        done(ComfyService().catalog(provider))
    }

    fun showImageGallery() = work { loadImageGallery() }

    suspend fun expressionSprites(card: Card): List<ExpressionSprite> = withContext(Dispatchers.IO) {
        val saved = card.expressionSprites ?: store.gallery(card.id, null).mapNotNull { item ->
            val label = item.prompt.takeIf { it.startsWith("表情 · ") }?.removePrefix("表情 · ")?.let(::spriteLabel)
            label?.let { ExpressionSprite(it, item.image) }
        }
        saved.distinctBy { it.label to it.image.id }.take(MAX_EXPRESSION_SPRITES).filter { imageStore.file(it.image).isFile }
    }

    suspend fun expressionFrame(chatId: String, subject: ExpressionSubject, forScene: Boolean = false): ExpressionFrame? {
        val snapshot = current
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
                withContext(Dispatchers.Default) { localExpressions.classify(text) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutable.update { it.copy(localExpressionFailure = "本地表情模型读取失败，可以重新下载或导入模型。") }
                null
            } catch (_: LinkageError) {
                mutable.update { it.copy(localExpressionFailure = "设备无法加载本地表情运行库。") }
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

    fun downloadExpressionModel() = utilityWork("正在下载本地表情模型…") {
        installExpressionModel { install ->
            request(EXPRESSION_MODEL_URL, headers = mapOf("Accept" to "application/octet-stream"),
                readTimeout = 45_000, followRedirects = true) { input, _ -> install(input) }
        }
    }

    fun importExpressionModel(uri: Uri) = utilityWork("正在导入本地表情模型…") {
        installExpressionModel { install ->
            withContext(Dispatchers.IO) {
                requireNotNull(resolver.openInputStream(uri)) { "无法打开模型文件" }.use(install)
            }
        }
    }

    private suspend fun installExpressionModel(read: suspend ((InputStream) -> Unit) -> Unit) {
        val context = currentCoroutineContext()
        mutable.update { it.copy(localExpressionProgress = 0, localExpressionFailure = null) }
        var lastPercent = 0
        try {
            read { input -> localExpressions.install(input) { received ->
                context.ensureActive()
                val percent = (received * 100 / EXPRESSION_MODEL_BYTES).toInt()
                if (lastPercent != percent) {
                    lastPercent = percent
                    mutable.update { it.copy(localExpressionProgress = percent) }
                }
            } }
            notice("本地表情模型已就绪")
        } finally {
            mutable.update { it.copy(localExpressionProgress = null, localExpressionInstalled = localExpressions.installed()) }
        }
    }

    fun removeExpressionModel() = work {
        withContext(Dispatchers.IO) { localExpressions.remove() }
        mutable.update { it.copy(localExpressionInstalled = false, localExpressionFailure = null) }
        notice("已移除本地表情模型")
    }

    fun saveExpressionSettings(settings: ExpressionSettings) = work {
        val chat = current.chat ?: return@work
        persist(chat.copy(options = chat.options.copy(expressions = settings.copy(instruction = settings.instruction.trim().take(2000)))))
    }

    fun importExpressionSprites(cardId: String, files: List<Uri>, pack: Boolean, label: String) = work {
        if (files.isEmpty()) return@work
        val card = requireNotNull(current.cards.firstOrNull { it.id == cardId }) { "找不到角色" }
        val explicit = if (label.isBlank() || pack) null else requireNotNull(expressionLabel(label)) { "表情标签无效" }
        val pictures = withContext(Dispatchers.IO) {
            val app = getApplication<Application>()
            files.distinct().flatMap { uri ->
                currentCoroutineContext().ensureActive()
                requireNotNull(resolver.openInputStream(uri)) { "无法打开表情图片" }.use { input ->
                    if (pack) readSpritePack(input, imageStore::import) else {
                        val name = app.documentName(uri).takeUnless { it == "导入的聊天" } ?: "neutral"
                        val key = explicit ?: requireNotNull(spriteLabel(name)) { "表情文件名无效" }
                        listOf(ExpressionSprite(key, imageStore.import(input.readLimited(MAX_IMAGE_SOURCE), name)))
                    }
                }
            }
        }
        val merged = (expressionSprites(card) + pictures).distinctBy { it.label to it.image.id }
        require(merged.size <= MAX_EXPRESSION_SPRITES) { "每个角色最多保存 $MAX_EXPRESSION_SPRITES 张表情" }
        saveExpressionSprites(card, merged)
        notice("已导入 ${pictures.size} 张表情")
    }

    fun removeExpressionSprite(cardId: String, sprite: ExpressionSprite) = work {
        val card = current.cards.firstOrNull { it.id == cardId } ?: return@work
        saveExpressionSprites(card, expressionSprites(card).filterNot { it == sprite })
    }

    private suspend fun saveExpressionSprites(card: Card, sprites: List<ExpressionSprite>) {
        val cards = withContext(Dispatchers.IO) { store.save(card.withExpressionSprites(sprites)); store.cards() }
        mutable.update { it.copy(cards = cards) }
    }

    fun showExpression(cardId: String, sprite: ExpressionSprite, done: () -> Unit = {}) = work {
        setManualExpression(cardId, sprite.label, sprite.image.id)
        done()
    }

    private suspend fun setManualExpression(cardId: String, label: String, imageId: String? = null): String {
        val chat = requireNotNull(current.chat) { "请先打开对话" }
        val subject = requireNotNull(current.expressionSubjects(chat).firstOrNull { it.card.id == cardId }) { "该角色不在对话中" }
        val settings = chat.options.expressions
        check(expressionSprites(subject.card).any { it.label == label && (imageId == null || it.image.id == imageId) } ||
            (settings.fallback == EXPRESSION_EMOJI && label in expressionEmoji)) { "找不到表情 $label" }
        if (settings.mode == ExpressionMode.OFF) persist(chat.copy(options = chat.options.copy(expressions = settings.copy(mode = ExpressionMode.MANUAL))))
        mutable.update { state ->
            val choices = state.manualExpressions.filterValues { it.chatId == chat.id }
            state.copy(manualExpressions = choices + (cardId to ManualExpression(chat.id, subject.reply, label, imageId)))
        }
        return label
    }

    private suspend fun scriptExpression(command: String, named: Map<String, String>, input: String): String {
        val chat = requireNotNull(current.chat) { "请先打开对话" }
        val subjects = current.expressionSubjects(chat)
        val requested = named["name"]
        val subject = if (requested == null) current.regexCard(chat.messages.lastOrNull { it.role == "assistant" && !it.hidden })
            ?.let { actor -> subjects.firstOrNull { it.card.id == actor.id } }
            else subjects.singleOrNull { it.card.name.equals(requested, true) || it.card.id == requested }
        val card = requireNotNull(subject) { "找不到表情对应的角色" }.card
        if (command == "list") return JSONArray(expressionSprites(card).map(ExpressionSprite::label).distinct()).toString()
        return setManualExpression(card.id, requireNotNull(expressionLabel(input)) { "请填写表情标签" })
    }

    fun closeImageGallery() = mutable.update { it.copy(imageGallery = null) }

    private suspend fun loadImageGallery(): String {
        val card = current.card
        val group = current.group
        val title = card?.name ?: group?.name ?: error("请先打开角色或群组")
        val items = withContext(Dispatchers.IO) { store.gallery(card?.id, group?.id) }
        mutable.update { it.copy(imageGallery = ImageGallery(title, items)) }
        return items.size.toString()
    }

    fun clearDrawingBackground(chatId: String) = work {
        val chat = current.chat?.takeIf { it.id == chatId } ?: return@work
        persist(chat.copy(options = chat.options.copy(background = null)))
    }

    fun setDrawingBackground(chatId: String, image: ChatImage) = work {
        val chat = current.chat?.takeIf { it.id == chatId } ?: return@work
        require(imageStore.file(image).isFile) { "找不到图库中的图片" }
        persist(chat.copy(options = chat.options.copy(background = image)))
        closeImageGallery()
        notice("已应用对话背景")
    }

    fun importChatBackground(chatId: String, uri: Uri) = work {
        val chat = current.chat?.takeIf { it.id == chatId } ?: return@work
        stopAuto()
        val image = withContext(Dispatchers.IO) {
            val name = getApplication<Application>().documentName(uri).takeUnless { it == "导入的聊天" } ?: "对话背景"
            requireNotNull(resolver.openInputStream(uri)) { "无法打开背景图片" }.use {
                imageStore.import(it.readLimited(MAX_IMAGE_SOURCE), name)
            }
        }
        persist(chat.copy(options = chat.options.copy(background = image)))
        notice("已应用对话背景")
    }

    fun previousDrawing(chatId: String, index: Int) = work {
        val chat = current.chat?.takeIf { it.id == chatId } ?: return@work
        val series = chat.messages.getOrNull(index)?.drawing ?: return@work
        if (series.selected > 0) saveDrawingSelection(chat, index, series.copy(selected = series.selected - 1))
    }

    fun nextDrawing(chatId: String, index: Int): Boolean {
        val chat = current.chat?.takeIf { it.id == chatId } ?: return false
        val series = chat.messages.getOrNull(index)?.drawing ?: return false
        if (current.busy || current.generation != null || current.scriptRunning) return false
        if (series.selected < series.images.lastIndex) {
            work { saveDrawingSelection(chat, index, series.copy(selected = series.selected + 1)) }
            return true
        }
        return launchDrawing(chatId) {
            renderDrawing(it, series.recipe.request(current.prefs, reroll = true), index,
                gallery = series.addToGallery, previous = series)
        }
    }

    private fun launchDrawing(chatId: String, action: suspend (Chat) -> Unit): Boolean {
        val chat = current.chat?.takeIf { it.id == chatId } ?: return false
        if (current.busy || current.generation != null || current.scriptRunning) return false
        stopAuto()
        mutable.update { it.copy(busy = true, generatingImage = true) }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                action(chat)
            } catch (e: CancellationException) {
                notice("已停止图像生成")
                throw e
            } catch (e: Exception) {
                notice(e.localizedMessage ?: "图像生成失败，请重试")
            } finally {
                mutable.update { it.copy(busy = false, generatingImage = false) }
                imageJob = null
            }
        }
        imageJob = job
        job.start()
        return true
    }

    private fun drawingCard(chat: Chat, target: Int? = null): Card? {
        val message = target?.let(chat.messages::getOrNull)
            ?: chat.messages.lastOrNull { it.role == "assistant" }
        return current.regexCard(message)
    }

    private suspend fun drawingPrompt(chat: Chat, mode: DrawingMode, text: String, extendFree: Boolean): String = when {
        mode == DrawingMode.RAW_LAST -> chat.messages.lastOrNull { !it.excludedFromContext && it.text.isNotBlank() }?.text
            ?: error("当前没有可用于绘图的消息")
        mode == DrawingMode.FREE && !extendFree -> text
        else -> ModelService.utility(utilityConnection(chat),
            drawingPromptContext(mode, text, chat, drawingCard(chat), current.persona, current.prefs.drawing), 400)
    }

    private fun prepareDrawing(request: ImageRequest, chat: Chat, mode: DrawingMode, target: Int?,
                               card: Card? = drawingCard(chat, target)): ImageRequest {
        val settings = current.prefs.drawing
        require(request.prompt.isNotBlank()) { "请输入绘图提示词" }
        return request.copy(
            shape = mode.forcedShape ?: request.shape,
            prompt = prepareDrawingPrompt(request.prompt, settings, card, current.persona,
                mode, singleCharacter = chat.cardId != null),
            tuning = request.tuning.copy(negative = combineImagePrompts(settings.style?.negative.orEmpty(), request.tuning.negative)),
        )
    }

    private suspend fun saveDrawingSelection(chat: Chat, index: Int, series: DrawnImageSeries) {
        val messages = chat.messages.toMutableList()
        messages[index] = messages[index].copy(drawing = series)
        persist(chat.copy(messages = messages, updated = System.currentTimeMillis()))
    }

    private suspend fun renderDrawing(chat: Chat, request: ImageRequest, target: Int? = null, quiet: Boolean = false,
                                      gallery: Boolean = true, background: Boolean = false, previous: DrawnImageSeries? = null,
                                      card: Card? = drawingCard(chat, target), groupTurnId: String? = null,
                                      caption: String = request.prompt): ChatImage {
        require(target == null || target in chat.messages.indices) { "找不到绘图对应的消息" }
        val comfy = if (!request.protocol.usesWorkflow) null else {
            val workflow = requireNotNull(current.comfyWorkflow(request.workflowId)) { "找不到原来使用的 ComfyUI 工作流" }
            val persona = current.persona
            val userAvatar = personaAvatar(persona)
            val characterAvatar = card?.takeIf(Card::hasAvatar)?.let(::portrait)
            fun readAvatar(file: java.io.File?) = file?.takeIf { it.isFile && it.length() <= MAX_IMAGE_SOURCE }
                ?.inputStream()?.use { it.readLimited(MAX_IMAGE_SOURCE) }
            ComfyInputs(workflow, { readAvatar(userAvatar) }, { readAvatar(characterAvatar) }) { value ->
                if (card == null) value.replace("{{user}}", persona.name) else expand(value, card, persona.name)
            }
        }
        val image = withContext(Dispatchers.IO) {
            val bytes = imageService.generate(request, comfy, imageStore::bytes)
            imageStore.import(bytes, "绘图-${newId().take(8)}")
        }
        withContext(NonCancellable) {
            val entry = if (gallery) GalleryImage(image, request.prompt) else null
            if (!quiet || background) {
                val messages = chat.messages.toMutableList()
                if (!quiet) {
                    val series = if (previous == null) DrawnImageSeries(request.recipe(), listOf(image), addToGallery = gallery)
                    else previous.copy(images = previous.images + image, selected = previous.images.size)
                    if (target == null) messages.add(Message("system", "图像生成", caption, drawing = series,
                        groupTurnId = groupTurnId))
                    else messages[target] = messages[target].copy(drawing = series)
                }
                val options = if (background) chat.options.copy(background = image) else chat.options
                persist(chat.copy(messages = messages, options = options, updated = System.currentTimeMillis()), entry)
            } else if (entry != null && !chat.isTemporary) withContext(Dispatchers.IO) { store.addDrawing(chat, entry) }
        }
        return image
    }

    private fun drawingTool(prefs: Preferences, chat: Chat, card: Card?): ModelTool? {
        val settings = prefs.drawing
        if (!settings.functionTool || nativeDrawingToolRemoved) return null
        val profile = settings.defaultProfile(prefs.providers) ?: return null
        val request = profile.resolve(prefs.providers, "") ?: return null
        if (request.protocol.usesWorkflow && current.comfyWorkflow(request.workflowId) == null) return null
        val instruction = settings.templates["tool"] ?: "Describe the requested subject, setting, composition and visual style in a text prompt for an image model."
        val parameters = jsonOf("type" to "object", "properties" to jsonOf(
            "prompt" to jsonOf("type" to "string", "description" to instruction),
        ), "required" to JSONArray(listOf("prompt")))
        return ModelTool(DRAW_IMAGE_TOOL, "生成图片",
            "Generate an image when the user requests a picture, illustration, scene or selfie.", parameters) { args ->
            val prompt = (args.opt("prompt") as? String)?.trim()
            require(!prompt.isNullOrBlank() && prompt.length <= 16_384) { "绘图工具需要有效的 prompt" }
            val active = current.chat?.takeIf { it.id == chat.id } ?: error("对话已关闭")
            val references = if (request.protocol.supportsReferences)
                active.messages.lastOrNull { it.role == "user" && !it.hidden }?.displayImages.orEmpty() else emptyList()
            val imageRequest = prepareDrawing(request.copy(prompt = prompt, references = references), active, DrawingMode.FREE, null, card)
            mutable.update { it.copy(generatingImage = true) }
            try {
                renderDrawing(active, imageRequest, card = card,
                    groupTurnId = current.generation?.id.takeIf { active.groupId != null }).drawingAddress()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error(e.localizedMessage.orEmpty().redactValue(request.provider.apiKey).ifBlank { "图像生成失败" })
            } finally {
                mutable.update { it.copy(generatingImage = false) }
            }
        }
    }

    private fun activeTools(prefs: Preferences, chat: Chat?, card: Card?): List<ModelTool> {
        val drawing = chat?.let { drawingTool(prefs, it, card) }?.takeUnless { it.name in customTools }
        return listOfNotNull(drawing) + customTools.values
    }

    private suspend fun recordToolTurn(chat: Chat, turn: ToolTurn, speaker: Card) {
        val latest = current.chat?.takeIf { it.id == chat.id } ?: chat
        val text = turn.invocations.joinToString(" · ") {
            if (it.failed) "${it.label}（失败）" else it.label
        }
        val record = Message("system", "工具调用", text, characterId = speaker.id, toolTurn = turn,
            groupTurnId = current.generation?.id.takeIf { latest.groupId != null })
        withContext(NonCancellable) {
            persist(latest.copy(messages = latest.messages + record, updated = System.currentTimeMillis()))
        }
    }

    private fun work(completed: () -> Unit = {}, action: suspend () -> Unit) {
        if (current.busy || current.generation != null || current.scriptRunning) return
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            var succeeded = false
            try {
                action()
                succeeded = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice(e.localizedMessage ?: "操作失败，请重试")
            } finally {
                mutable.update { it.copy(busy = false) }
            }
            if (succeeded) completed()
        }
    }

    fun savePrefs(prefs: Preferences) = work {
        val valid = ModelService.validated(prefs)
        val checked = valid.copy(server = valid.server.trim().also { if (it.isNotBlank()) checkedAddress(it) })
        persistPreferences(checked)
        notice("设置已保存")
    }

    fun setVisualNovel(enabled: Boolean) = work {
        persistPreferences(current.prefs.copy(visualNovel = enabled))
    }

    fun setChatAppearance(appearance: ChatAppearance) = work {
        persistPreferences(current.prefs.copy(chatAppearance = appearance.clamped()))
    }

    fun tokenCounterFor(connection: ModelConnection?): TextTokenCounter =
        if (connection == null) estimatedTokens else loreTokenizers.counterFor(connection)

    fun setWelcomeAssistant(card: Card?) = work {
        val preferences = current.prefs.welcome
        persistPreferences(current.prefs.copy(welcome = preferences.copy(assistantId = card?.id)))
    }

    fun setAutoOpenLastChat(enabled: Boolean) = work {
        persistPreferences(current.prefs.copy(welcome = current.prefs.welcome.copy(autoOpen = enabled)))
    }

    fun setReplyAutomation(options: ReplyAutomation, done: () -> Unit) = work {
        persistPreferences(current.prefs.copy(replyAutomation = ReplyAutomation.from(options.json())))
        done()
    }

    fun reportDraft(chatId: String, present: Boolean) {
        if (present) draftInProgress = chatId
        else if (draftInProgress == chatId) draftInProgress = null
    }

    fun setRecentChats(visible: Boolean, count: Int) = work {
        val welcome = current.prefs.welcome.copy(showRecent = visible, recentCount = count.coerceIn(1, RECENT_CHAT_LIMIT))
        persistPreferences(current.prefs.copy(welcome = welcome))
    }

    fun toggleRecentPin(id: String) = work {
        val welcome = current.prefs.welcome
        val existing = withContext(Dispatchers.IO) { store.recentChats(welcome.pinnedIds) }
            .mapTo(hashSetOf()) { it.summary.id }
        val validPins = welcome.pinnedIds.filter { it in existing }
        val pins = if (id in validPins) validPins - id else {
            require(id in existing) { "找不到聊天记录" }
            require(validPins.size < RECENT_CHAT_LIMIT) { "最多置顶 $RECENT_CHAT_LIMIT 个聊天" }
            validPins + id
        }
        persistPreferences(current.prefs.copy(welcome = welcome.copy(pinnedIds = pins)))
        refreshRecentChats()
    }

    suspend fun refreshRecentChats() {
        val pins = current.prefs.welcome.pinnedIds
        try {
            val recent = withContext(Dispatchers.IO) { store.recentChats(pins) }
            mutable.update { if (it.prefs.welcome.pinnedIds == pins) it.copy(recentChats = recent) else it }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            notice("无法读取最近聊天")
        }
    }

    private suspend fun persistPreferences(prefs: Preferences) {
        withContext(Dispatchers.IO) { store.save(prefs) }
        mutable.update { it.copy(prefs = prefs) }
    }

    private suspend fun persistMacroVariables(chatId: String?, changes: MacroVariableChanges) {
        if (changes.isEmpty) return
        val chat = if (changes.local.isEmpty()) null else requireNotNull(current.chat?.takeIf { it.id == chatId }) {
            "找不到要保存变量的对话"
        }
        val globals = changes.global.takeIf { it.isNotEmpty() }?.let { current.prefs.scriptVariables.withVariableChanges(it) }
        withContext(NonCancellable) {
            if (chat != null) {
                val updated = chat.options.variables.withVariableChanges(changes.local)
                persist(chat.copy(options = chat.options.copy(variables = updated)), scriptGlobals = globals)
            } else if (globals != null) {
                withContext(Dispatchers.IO) { store.commitScriptVariables(null, globals) }
                mutable.update { it.copy(prefs = it.prefs.copy(scriptVariables = globals)) }
            }
        }
    }

    fun saveQuickReplySet(set: QuickReplySet, done: () -> Unit = {}) = work {
        val config = current.prefs.quickReplies
        val clean = set.copy(name = set.name.trim())
        require(clean.name.isNotBlank()) { "请输入快捷回复集名称" }
        require(config.sets.none { it.id != clean.id && it.name.equals(clean.name, true) }) { "快捷回复集名称已存在" }
        val rows = config.sets.toMutableList()
        val index = rows.indexOfFirst { it.id == clean.id }
        if (index < 0) rows.add(clean) else rows[index] = clean
        persistPreferences(current.prefs.copy(quickReplies = config.copy(sets = rows)))
        done()
    }

    fun removeQuickReplySet(set: QuickReplySet, done: () -> Unit) = work {
        val config = current.prefs.quickReplies
        persistPreferences(current.prefs.copy(quickReplies = config.copy(active = config.active - set.id,
            sets = config.sets.filterNot { it.id == set.id })))
        done()
    }

    fun importQuickReplySet(uri: Uri) = work {
        val source = withContext(Dispatchers.IO) {
            val raw = requireNotNull(resolver.openInputStream(uri)) { "无法打开快捷回复文件" }
                .use { it.readLimited().decodeToString().trimStart('\uFEFF') }
            QuickReplySet.read(JSONObject(raw))
        }
        val config = current.prefs.quickReplies
        val name = generateSequence(1) { it + 1 }.map { if (it == 1) source.name else "${source.name} ($it)" }
            .first { candidate -> config.sets.none { it.name.equals(candidate, true) } }
        persistPreferences(current.prefs.copy(quickReplies = config.copy(sets = config.sets + source.copy(name = name))))
        notice("快捷回复集已导入")
    }

    fun exportQuickReplySet(uri: Uri, set: QuickReplySet) = work {
        withContext(Dispatchers.IO) { write(uri, set.json().toString(2).encodeToByteArray()) }
        notice("快捷回复集已导出")
    }

    fun enableQuickReplies(enabled: Boolean) = work {
        persistPreferences(current.prefs.copy(quickReplies = current.prefs.quickReplies.copy(enabled = enabled)))
    }

    fun activateQuickReplySet(id: String, enabled: Boolean, forChat: Boolean = false) = work {
        val config = current.prefs.quickReplies
        val set = requireNotNull(config.sets.firstOrNull { it.id == id }) { "找不到快捷回复集" }
        fun change(values: List<String>) = if (enabled) (values + id).distinct() else values - id
        if (forChat) {
            val chat = current.chat ?: return@work
            persist(chat.copy(options = chat.options.copy(quickReplySets = change(chat.options.quickReplySets),
                quickReplyNames = chat.options.quickReplyNames - set.name)))
        } else persistPreferences(current.prefs.copy(quickReplies = config.copy(active = change(config.active))))
    }

    private fun scriptHost(input: String): TavernScriptHost =
        TavernScriptHost({ current }, input, { persist(it) }, ::persistPreferences, ::requestScriptInput,
            ::scriptTrigger, ::scriptGenerate, ::scriptDraft, ::scriptNarrate,
            { narrate(it, automatic = true) }, ::scriptTranslate, ::scriptSummarize, ::scriptDataBank,
            ::scriptDrawing, { text -> parseDrawingMarkup(text) { imageStore.file(it).isFile } },
            ::scriptTools, { customTools[it.name] = it }, ::scriptMessage, ::scriptExpression, ::scriptCaption,
            ::persistMacroVariables) { notice(it) }

    private suspend fun scriptCaption(named: Map<String, String>, prompt: String): String {
        val chat = checkNotNull(current.chat) { "请先打开对话" }
        check(current.captionReady()) { "请先配置图片描述模型" }
        val messageId = named["mesId"] ?: named["mesid"] ?: named["id"]
        val index = named["index"]?.toIntOrNull() ?: 0
        val image = if (messageId != null) {
            val at = messageId.toIntOrNull()?.let { if (it < 0) chat.messages.size + it else it }
            val images = at?.let(chat.messages::getOrNull)?.displayImages.orEmpty()
            checkNotNull(images.getOrNull(index)) { "该消息中找不到指定图片" }
        } else {
            val path = requestScriptInput(ScriptDialog(kind = "image-file", title = "选择要描述的图片", text = ""))
                ?: return ""
            withContext(Dispatchers.IO) {
                val uri = Uri.parse(path)
                importCaptionableImage(uri, getApplication<Application>().documentName(uri))
            }
        }
        val description = askImageDescription(image, prompt) ?: return ""
        if (!named["quiet"].orEmpty().scriptBoolean(default = false)) {
            val active = current.chat?.takeIf { it.id == chat.id } ?: error("对话已切换")
            val persona = current.persona
            val message = Message("user", persona.name, "", personaId = persona.id, images = listOf(image),
                imageCaptions = mapOf(image.id to description))
            persist(active.copy(updated = System.currentTimeMillis(), messages = active.messages + message))
            quickEvent(QuickEvent.USER)
        }
        return description
    }

    private suspend fun scriptMessage(command: String, named: Map<String, String>, input: String): String {
        val chat = requireNotNull(current.chat) { "请先打开对话" }
        val messages = chat.messages
        val text = input.trim()
        val includeTools = named["toolcalls"].orEmpty().scriptBoolean(default = true)
        return when (command) {
            "hide", "unhide" -> {
                val range = scriptMessageRange(text.ifEmpty { "-1" }, messages.size) ?: return ""
                val name = named["name"].orEmpty().trim()
                changeMessageContext(chat, range.toSet(), command == "hide", name)
                ""
            }
            "cut", "del", "delname" -> {
                val selected = when (command) {
                    "cut" -> scriptMessageRange(text, messages.size)?.toSet() ?: return ""
                    "delname" -> {
                        if (text.isEmpty()) return ""
                        val name = current.cards.firstOrNull { it.name.equals(text, true) }?.name ?: text
                        messages.indices.filter { messages[it].name == name }.toSet()
                    }
                    else -> {
                        if (text.isEmpty()) {
                            mutable.update { it.copy(messageSelection = MessageSelectionRequest(chat.id, includeTools)) }
                            return ""
                        }
                        val count = text.toIntOrNull() ?: error("请输入要删除的消息数量")
                        if (count < 1) return ""
                        require(count <= messages.size) { "当前对话只有 ${messages.size} 条消息" }
                        (messages.size - count until messages.size).toSet()
                    }
                }
                removeMessages(chat, selected, command != "delname" && includeTools)
                if (command == "delname") "" else selected.joinToString("") { "${messages[it].requestText}\n" }
            }
            "message-role", "message-name" -> updateScriptAuthor(chat, command, named["at"], text)
            "addswipe", "delswipe", "swipe" -> scriptSwipe(chat, command, named, input)
            else -> ""
        }
    }

    private suspend fun updateScriptAuthor(chat: Chat, command: String, at: String?, text: String): String {
        val index = scriptMessageRange(at ?: "-1", chat.messages.size)?.takeIf { it.first == it.last }?.first ?: return ""
        val original = chat.messages[index]
        if (command == "message-role") {
            val role = text.lowercase()
            if (role !in setOf("system", "assistant", "user")) return original.scriptRole
            if (original.toolTurn != null) return original.scriptRole
            if (role == original.scriptRole) return role
            val character = (current.cards.firstOrNull { it.name == original.name }?.id ?: original.characterId)
                .takeIf { role == "assistant" }
            val persona = (current.prefs.personas.firstOrNull { it.name == original.name }?.id ?: original.personaId)
                .takeIf { role == "user" }
            updateMessage(chat, index, original.withScriptRole(role, character, persona))
            return role
        }
        if (text.isEmpty()) return original.name
        val card = current.cards.firstOrNull { it.name.equals(text, true) && original.scriptRole == "assistant" }
        val persona = current.prefs.personas.firstOrNull { it.name.equals(text, true) && original.role == "user" }
        val name = card?.name ?: persona?.name ?: text
        updateMessage(chat, index, original.copy(name = name, characterId = card?.id, personaId = persona?.id))
        return name
    }

    private suspend fun scriptSwipe(chat: Chat, command: String, named: Map<String, String>, input: String): String {
        val message = chat.messages.lastOrNull()?.takeIf { it.role == "assistant" && !it.hidden } ?: return ""
        val index = chat.messages.lastIndex
        return when (command) {
            "addswipe" -> {
                if (input.isBlank()) return ""
                val updated = message.withScriptAlternative(input, named["switch"].orEmpty().scriptBoolean())
                updateMessage(chat, index, updated)
                updated.variants.lastIndex.toString()
            }
            "delswipe" -> {
                if (message.variants.size < 2) return ""
                val option = input.trim().toIntOrNull()?.minus(1) ?: message.index
                if (option !in message.variants.indices) return ""
                val updated = message.withoutAlternative(option)
                updateMessage(chat, index, updated)
                updated.index.toString()
            }
            else -> {
                check(current.generation == null) { "请等待当前回复完成" }
                val left = named["direction"].equals("left", ignoreCase = true)
                val option = message.index + if (left) -1 else 1
                if (option in message.variants.indices) updateMessage(chat, index, message.select(option))
                else if (!left) {
                    val card = current.replyingCharacter(message) ?: return ""
                    val job = generate(chat, listOf(card), replacedMessages = 1, action = ReplyAction.SWIPE)
                    if (named["await"].orEmpty().scriptBoolean()) job?.join()
                }
                ""
            }
        }
    }

    private suspend fun updateMessage(chat: Chat, index: Int, message: Message) {
        if (message == chat.messages[index]) return
        val updated = chat.messages.toMutableList().apply { this[index] = message }
        persist(chat.copy(messages = updated, updated = System.currentTimeMillis()))
    }

    private suspend fun changeMessageContext(chat: Chat, indices: Set<Int>, hidden: Boolean, name: String = "") {
        val messages = chat.messages.mapIndexed { index, message ->
            if (index !in indices || name.isNotEmpty() && name != message.name) message
            else if (!hidden && message.role == "system" && message.toolTurn == null) message.copy(role = "assistant", hidden = false)
            else message.copy(hidden = hidden)
        }
        if (messages != chat.messages) persist(chat.copy(messages = messages, updated = System.currentTimeMillis()))
    }

    fun toggleMessageContext(chat: Chat, index: Int) = work {
        val message = chat.messages.getOrNull(index) ?: return@work
        stopAuto()
        changeMessageContext(chat, setOf(index), hidden = if (message.toolTurn == null) !message.excludedFromContext else !message.hidden)
    }

    fun dismissMessageSelection() = mutable.update { it.copy(messageSelection = null) }

    private suspend fun scriptTools(command: String, named: Map<String, String>, text: String): String {
        if (command == "unregister") {
            val name = text.trim()
            require(name.isNotBlank()) { "请输入要注销的工具名称" }
            customTools.remove(name)
            if (name == DRAW_IMAGE_TOOL) nativeDrawingToolRemoved = true
            return ""
        }
        val tools = activeTools(current.prefs, current.chat, current.regexCard())
        if (command == "list") {
            val rows = JSONArray(tools.map(ModelTool::json))
            return when (val mode = named["return"]?.trim()?.lowercase() ?: "popup-html") {
                "pipe", "object" -> rows.toString()
                "popup", "popup-html", "popup-text" -> {
                    requestScriptInput(ScriptDialog(kind = "popup", title = "已注册工具", text = toolListText(tools)))
                    ""
                }
                "toast", "toast-html", "toast-text" -> {
                    notice(tools.joinToString(" · ", transform = ModelTool::label).ifBlank { "暂无已注册工具" })
                    ""
                }
                "console" -> "".also { android.util.Log.i("TavernNative", rows.toString()) }
                "none" -> ""
                else -> error("未知的工具列表返回类型 $mode")
            }
        }
        val chosen = requireNotNull(tools.firstOrNull { it.name == text.trim() }) { "工具未注册：$text" }
        val args = toolArguments(named["parameters"] ?: error("请用 parameters= 提供工具参数"))
        return chosen.execute(args)
    }

    private fun toolListText(tools: List<ModelTool>): String = tools.joinToString("\n\n") {
        "${it.label} · ${it.name}\n${it.description}\n${it.parameters.toString(2)}"
    }.ifBlank { "暂无已注册工具" }

    private suspend fun scriptDrawing(command: String, named: Map<String, String>, text: String): String {
        if (command == "gallery") return loadImageGallery()
        val settings = current.prefs.drawing
        val providers = current.prefs.providers
        when (command) {
            "style" -> {
                if (text.isBlank()) return settings.activeStyle
                val style = if (text.equals("none", true)) "" else
                    requireNotNull(settings.styles.firstOrNull { it.name.equals(text, true) }) { "找不到绘图风格 $text" }.name
                persistPreferences(current.prefs.copy(drawing = settings.copy(activeStyle = style)))
                return style
            }
            "source" -> {
                val saved = settings.defaultProfile(providers)
                if (text.isBlank()) return saved?.protocol?.source.orEmpty()
                val protocol = when (text.lowercase()) {
                    "google", "gemini" -> ImageProtocol.GEMINI
                    "openai", "compatible" -> ImageProtocol.COMPATIBLE
                    "auto", "automatic1111", "sd", "forge" -> ImageProtocol.DIFFUSION
                    "comfy", "comfyui" -> ImageProtocol.COMFY
                    "comfyrunpod", "runpod" -> ImageProtocol.COMFY_RUNPOD
                    else -> null
                }
                val providerName = named["provider"] ?: text.takeIf { protocol == null }
                val provider = requireNotNull(providers.sortedByDescending { it.id == saved?.providerId }.firstOrNull { candidate ->
                    (providerName == null || candidate.id == providerName || candidate.name.equals(providerName, true)) &&
                        (protocol?.available(candidate) ?: ImageProtocol.entries.any { it.available(candidate) })
                }) { "请先配置对应的绘图服务" }
                val selected = protocol ?: saved?.protocol?.takeIf { it.available(provider) }
                    ?: ImageProtocol.entries.first { it.available(provider) }
                val model = named["model"] ?: selected.initialModel(provider)
                val profile = if (saved?.providerId == provider.id && saved.protocol == selected && "model" !in named) saved
                    else ImageProfile(provider.id, selected, model, workflowId = settings.comfyWorkflowId.takeIf { selected.usesWorkflow })
                persistPreferences(current.prefs.copy(drawing = settings.copy(profile = profile)))
                return selected.source
            }
            "workflow" -> {
                if (text.isBlank()) return current.comfyWorkflow(settings.comfyWorkflowId)?.name.orEmpty()
                val workflow = findComfyWorkflow(text)
                val profile = settings.profile?.let {
                    if (it.protocol.usesWorkflow) it.copy(workflowId = workflow.id) else it
                }
                persistPreferences(current.prefs.copy(drawing = settings.copy(profile = profile, comfyWorkflowId = workflow.id)))
                return workflow.name
            }
        }
        val chat = requireNotNull(current.chat) { "请先打开对话" }
        val mode = DrawingMode.from(text)
        val profile = requireNotNull(settings.defaultProfile(providers)) { "请先在生成图片窗口保存默认绘图服务" }
        val references = current.draftImages[chat.id].orEmpty().takeIf { profile.protocol.supportsReferences }.orEmpty()
        val request = requireNotNull(profile.resolve(providers, "", references)) { "绘图服务不可用" }
        val truthy = setOf("true", "1", "yes", "on")
        val extend = named["extend"]?.let { it.lowercase() in truthy } ?: settings.extendFree
        var prompt = drawingPrompt(chat, mode, if (mode == DrawingMode.FREE) text else "", extend)
        if (named["edit"]?.lowercase() in truthy) {
            prompt = requestScriptInput(ScriptDialog(kind = "input", title = "编辑绘图提示词", text = "", initial = prompt))
                ?: return ""
        }
        val workflowId = if (profile.protocol.usesWorkflow) {
            named["workflow"]?.let(::findComfyWorkflow)?.id ?: request.workflowId ?: settings.comfyWorkflowId
        } else null
        val options = prepareDrawing(request.copy(prompt = prompt, model = named["model"] ?: request.model, workflowId = workflowId,
            tuning = request.tuning.override(named)), chat, mode, null)
        val quiet = named["quiet"]?.lowercase() in truthy
        val gallery = named["gallery"]?.let { it.lowercase() in truthy } ?: true
        mutable.update { it.copy(generatingImage = true) }
        return try {
            renderDrawing(chat, options, quiet = quiet, gallery = gallery, background = mode == DrawingMode.BACKGROUND).drawingAddress()
        } finally {
            mutable.update { it.copy(generatingImage = false) }
        }
    }

    private fun findComfyWorkflow(name: String): ComfyWorkflow = requireNotNull(current.comfyWorkflows.firstOrNull {
        val short = if (name.endsWith(".json", ignoreCase = true)) name.dropLast(5) else name
        it.id == name || it.name.equals(short, true) ||
            it.id == DEFAULT_COMFY_WORKFLOW && short.equals("Default_Comfy_Workflow", true) ||
            it.id == AVATAR_COMFY_WORKFLOW && short.equals("Char_Avatar_Comfy_Workflow", true)
    }) { "找不到 ComfyUI 工作流 $name" }

    fun runScript(source: String, input: String = ""): Boolean = launchScript { StScript(scriptHost(input)).run(source) }

    fun runQuickReply(set: QuickReplySet, item: QuickReply, input: String): Boolean =
        launchScript { playQuickReply(set, item, input, automatic = false) }

    private fun launchScript(block: suspend () -> Unit): Boolean {
        if (current.busy || current.generation != null || current.scriptRunning) return false
        stopAuto()
        mutable.update { it.copy(scriptRunning = true) }
        val job = viewModelScope.launch {
            trackScript(block)
        }
        if (!job.isCompleted && current.scriptRunning) scriptJob = job
        job.invokeOnCompletion {
            if (scriptJob === job) {
                scriptJob = null
                mutable.update { it.copy(scriptRunning = false, scriptDialog = null) }
            }
        }
        return true
    }

    private suspend fun trackScript(block: suspend () -> Unit) {
        val job = currentCoroutineContext()[Job]
        scriptJob = job
        mutable.update { it.copy(scriptRunning = true) }
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notice(e.localizedMessage ?: "脚本执行失败")
        } finally {
            if (scriptJob === job) scriptJob = null
            mutable.update { it.copy(scriptRunning = false, scriptDialog = null) }
        }
    }

    private suspend fun quickEvent(event: QuickEvent) = runAutomatic { event in it.events }

    private suspend fun quickWorldEvent(ids: Set<String>) = runAutomatic { it.automationId in ids }

    private suspend fun runAutomatic(matches: (QuickReply) -> Boolean) {
        val config = current.prefs.quickReplies
        if (!config.enabled || preventNestedQuickReplies || quickReplyDepth >= 8) return
        val items = config.selected(current.chat).flatMap { set ->
            set.items.filter { matches(it) && it.message.isNotBlank() }.map { set to it }
        }
        if (items.isEmpty()) return
        suspend fun play() {
            quickReplyDepth++
            try {
                for ((set, item) in items) {
                    try {
                        playQuickReply(set, item, "", automatic = true)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        notice(e.localizedMessage ?: "快捷回复执行失败")
                    }
                }
            } finally {
                quickReplyDepth--
            }
        }
        if (current.scriptRunning) play() else trackScript { play() }
    }

    private suspend fun playQuickReply(set: QuickReplySet, item: QuickReply, input: String, automatic: Boolean) {
        val source = if (automatic) item.message else set.textFor(item, input)
        val host = scriptHost(input)
        val engine = StScript(host)
        if (source.isBlank()) return
        val previous = preventNestedQuickReplies
        preventNestedQuickReplies = previous || item.preventAutoExecute
        try {
            if (!set.disableSend && source.trimStart().startsWith('/')) engine.run(source)
            else {
                val message = engine.render(source)
                if (set.disableSend) scriptDraft(message)
                else {
                    val shouldGenerate = current.generation == null && current.group?.order != ReplyOrder.MANUAL
                    if (shouldGenerate && current.prefs.connectionFor(current.chat) == null) {
                        scriptDraft(message)
                        notice("请先配置模型提供商")
                    } else {
                        host.command("send", emptyMap(), message)
                        if (shouldGenerate) scriptTrigger(null)
                    }
                }
            }
        } finally {
            preventNestedQuickReplies = previous
        }
    }

    private fun scriptDraft(value: String) {
        val chat = current.chat ?: return
        mutable.update { it.copy(suggestedDraft = DraftSuggestion(chat.id, value)) }
    }

    private suspend fun requestScriptInput(dialog: ScriptDialog): String? {
        val result = CompletableDeferred<String?>()
        scriptAnswer = result
        mutable.update { it.copy(scriptDialog = dialog) }
        return try { result.await() } finally {
            scriptAnswer = null
            mutable.update { it.copy(scriptDialog = null) }
        }
    }

    fun completeScriptDialog(id: String, response: String?) {
        if (current.scriptDialog?.id == id) scriptAnswer?.complete(response)
    }

    private suspend fun scriptTrigger(name: String?): String {
        val chat = requireNotNull(current.chat) { "请先打开对话" }
        check(current.generation == null) { "已有模型请求在进行" }
        check(current.prefs.connectionFor(chat) != null) { "请先配置模型提供商" }
        val group = current.group
        val speakers = if (group != null && name != null) {
            listOfNotNull(group.members(current.cards).firstOrNull { it.name.equals(name, true) })
        } else group?.nextTurn(current.cards, chat.messages) ?: listOfNotNull(current.card)
        require(speakers.isNotEmpty()) { "找不到发言角色" }
        val generator = requireNotNull(generate(chat, speakers))
        generator.join()
        return current.chat?.messages?.drop(chat.messages.size)?.lastOrNull { it.role == "assistant" }?.text
            ?: error(current.notice ?: "模型没有返回回复")
    }

    private suspend fun scriptGenerate(text: String, named: Map<String, String>): String {
        val chat = requireNotNull(current.chat) { "请先打开对话" }
        val config = current
        val character = requireNotNull(config.regexCard()) { "找不到发言角色" }
        val connection = requireNotNull(config.prefs.connectionFor(chat)) { "请先配置模型提供商" }
        val prefs = config.prefs.copy(maxTokens = named["max"]?.toIntOrNull()?.takeIf { it > 0 }?.toString()
            ?: config.prefs.maxTokens)
        val history = chat.messages + listOfNotNull(text.takeIf(String::isNotBlank)
            ?.let { Message("user", config.persona.name, it) })
        val group = config.group?.let {
            GroupPrompt(it, it.promptMembers(config.cards, character), it.members(config.cards).map(Card::name),
                it.participants(config.cards).map(Card::name))
        }
        val output = StringBuilder()
        val vectors = promptVectors(config.prefs, chat, character, config.persona, config.books, history)
        val sharedMemory = promptSharedWorldMemory(chat, character, history, config.books, config.groups, config.cards)
        val loreCounter = withContext(Dispatchers.Default) { loreTokenizers.counterFor(connection) }
        var loreBudget: LoreBudgetUsage? = null
        val result = ModelService.reply(prefs, connection, character, history, config.books, group, config.persona, chat.options,
            "quiet", config.presetFor(chat), onPrompt = { messages ->
                mutable.update { it.copy(lastPrompt = PromptCapture(chat.id, connection.model, messages, loreBudget)) }
            }, readImage = imageStore::bytes, readDocument = documentStore::text, vectors = vectors,
            onRetry = { output.clear() }, sharedMemory = sharedMemory, loreCounter = loreCounter, onLoreBudget = {
                loreBudget = it
                if (it.blocked && prefs.loreSettings.overflowAlert) notice("世界书额度已达 ${it.limit} Token，部分条目未注入")
            }, chatId = chat.id, onVariables = { persistMacroVariables(chat.id, it) }) { output.append(it) }
        result.incomplete?.let { notice(it) }
        return output.toString().trim()
    }

    fun savePersona(profile: PersonaProfile, photo: Uri?, done: () -> Unit) = work {
        val prefs = current.prefs
        val previous = prefs.personas.firstOrNull { it.id == profile.id }
        val name = profile.name.trim()
        require(name.isNotBlank()) { "请输入人设名称" }
        val avatar = if (photo == null) profile.avatar else newId().also { id ->
            withContext(Dispatchers.IO) { store.writePersonaAvatar(id, decodeAvatar(photo)) }
        }
        val saved = profile.copy(name = name, title = profile.title.trim(), avatar = avatar)
        val index = prefs.personas.indexOfFirst { it.id == saved.id }
        val personas = prefs.personas.toMutableList().apply { if (index < 0) add(saved) else set(index, saved) }
        try {
            withContext(NonCancellable) { persistPreferences(prefs.copy(personas = personas)) }
        } catch (e: Exception) {
            if (photo != null && avatar != null) withContext(NonCancellable + Dispatchers.IO) { store.deletePersonaAvatar(avatar) }
            throw e
        }
        previous?.avatar?.takeUnless { it == avatar }?.let { id -> withContext(Dispatchers.IO) { store.deletePersonaAvatar(id) } }
        done()
    }

    fun importPersonas(uri: Uri, done: () -> Unit = {}) = work {
        val (updated, added) = withContext(Dispatchers.IO) {
            val source = requireNotNull(resolver.openInputStream(uri)) { "无法打开人设 JSON" }
                .use { it.readLimited() }
            readPersonaJson(source, current.books, current.cards, current.groups).merge(current.prefs)
        }
        if (updated != current.prefs) persistPreferences(updated)
        notice(if (added == 0) "文件中的人设已存在" else "已导入 $added 个人设")
        done()
    }

    fun personaFromCard(card: Card) = work {
        val prefs = current.prefs
        val id = newId()
        val avatar = withContext(Dispatchers.IO) {
            val source = store.portraitFile(card.id)
            if (!card.hasAvatar || !source.isFile) null else newId().also { asset ->
                val bytes = source.inputStream().use { it.readLimited() }
                store.writePersonaAvatar(asset, resizeAvatar(bytes))
            }
        }
        val profile = PersonaProfile(id = id, name = card.name, description = swapPersonaMacros(card.description),
            worldId = current.books.firstOrNull { it.name == card.worldName }?.id.orEmpty(), avatar = avatar)
        try {
            withContext(NonCancellable) { persistPreferences(prefs.copy(personas = prefs.personas + profile)) }
        } catch (e: Exception) {
            avatar?.let { withContext(NonCancellable + Dispatchers.IO) { store.deletePersonaAvatar(it) } }
            throw e
        }
        notice("已创建人设 ${profile.name}")
    }

    fun setDefaultPersona(persona: PersonaProfile) = work {
        val prefs = current.prefs
        if (prefs.personas.any { it.id == persona.id }) {
            persistPreferences(prefs.copy(defaultPersonaId = persona.id))
            notice("默认人设已更新")
        }
    }

    fun removePersona(persona: PersonaProfile) = work {
        val prefs = current.prefs
        val remaining = prefs.personas.filterNot { it.id == persona.id }.ifEmpty { listOf(PersonaProfile.legacy()) }
        val defaultId = prefs.defaultPersonaId.takeUnless { it == persona.id } ?: remaining.first().id
        persistPreferences(prefs.copy(personas = remaining, defaultPersonaId = defaultId,
            personaConnections = prefs.personaConnections.filterValues { it != persona.id }))
        mutable.update { it.copy(temporaryPersonaId = it.temporaryPersonaId?.takeUnless { id -> id == persona.id }) }
        persona.avatar?.let { id -> withContext(Dispatchers.IO) { store.deletePersonaAvatar(id) } }
    }

    fun usePersonaTemporarily(id: String) = work {
        if (current.chat != null && current.prefs.personas.any { it.id == id }) {
            mutable.update { it.copy(temporaryPersonaId = id) }
        }
    }

    fun pinPersona(id: String?) = work {
        val chat = current.chat ?: return@work
        require(id == null || current.prefs.personas.any { it.id == id }) { "找不到该人设" }
        persist(chat.copy(options = chat.options.copy(personaId = id)))
        mutable.update { it.copy(temporaryPersonaId = null) }
    }

    fun bindSubjectPersona(id: String?) = work {
        val subject = current.cardId ?: current.groupId ?: return@work
        val prefs = current.prefs
        require(id == null || prefs.personas.any { it.id == id }) { "找不到该人设" }
        val links = if (id == null) prefs.personaConnections - subject else prefs.personaConnections + (subject to id)
        persistPreferences(prefs.copy(personaConnections = links))
        mutable.update { it.copy(temporaryPersonaId = null) }
    }

    fun setDefaultAuthorNote(note: AuthorNote, done: () -> Unit) = work {
        persistPreferences(current.prefs.copy(defaultAuthorNote = note))
        done()
    }

    fun saveLoreSettings(settings: LoreSettings, done: () -> Unit) = work {
        persistPreferences(current.prefs.copy(loreSettings = settings))
        done()
    }

    fun setChatAuthorNote(note: AuthorNote, done: () -> Unit) = work {
        val chat = current.chat ?: return@work
        persist(chat.copy(options = chat.options.copy(authorNote = note)))
        done()
    }

    fun setPromptOverrides(scenario: String, examples: String, system: String, done: () -> Unit) = work {
        val chat = current.chat ?: return@work
        persist(chat.copy(options = chat.options.copy(scenario = scenario.trim(), exampleOverride = examples.trim(),
            systemPromptOverride = system.trim())))
        done()
    }

    fun linkChatBook(book: WorldBook?, done: () -> Unit) = work {
        val chat = current.chat ?: return@work
        persist(chat.copy(options = chat.options.copy(worldBookId = book?.id.orEmpty(), worldBookName = book?.name.orEmpty())))
        done()
    }

    fun switchPromptPreset(id: String?, done: () -> Unit) = work {
        val chat = current.chat ?: return@work
        require(id.isNullOrEmpty() || current.presets.any { it.id == id }) { "找不到该提示词预设" }
        stopAuto()
        persist(chat.copy(options = chat.options.copy(promptPresetId = id)))
        done()
    }

    fun setDefaultPromptPreset(id: String?) = work {
        require(id == null || current.presets.any { it.id == id }) { "找不到该提示词预设" }
        persistPreferences(current.prefs.copy(defaultPresetId = id))
    }

    fun savePromptPreset(preset: PromptPreset, done: () -> Unit) = work {
        val name = preset.name.trim()
        require(name.isNotEmpty()) { "请输入预设名称" }
        require(current.presets.none { it.id != preset.id && it.name.equals(name, true) }) { "预设名称已存在" }
        val presets = withContext(Dispatchers.IO) {
            store.save(preset.copy(name = name))
            store.promptPresets()
        }
        mutable.update { it.copy(presets = presets) }
        done()
    }

    fun deletePromptPreset(preset: PromptPreset, done: () -> Unit) = work {
        val presets = withContext(Dispatchers.IO) {
            store.deletePreset(preset.id)
            store.promptPresets()
        }
        mutable.update { it.copy(presets = presets) }
        if (current.prefs.defaultPresetId == preset.id) {
            persistPreferences(current.prefs.copy(defaultPresetId = null))
        }
        done()
    }

    fun importPromptPreset(uri: Uri, displayName: String) = work {
        val known = current.presets.mapTo(mutableSetOf()) { it.name.lowercase() }
        val presets = withContext(Dispatchers.IO) {
            val raw = requireNotNull(resolver.openInputStream(uri)) { "无法打开预设文件" }
                .use { it.readLimited().decodeToString().trimStart('\uFEFF') }
            val imported = PromptPreset.read(JSONObject(raw), displayName.removeSuffix(".json"))
            val title = generateSequence(1) { it + 1 }
                .map { if (it == 1) imported.name else "${imported.name} ($it)" }
                .first { it.lowercase() !in known }
            store.save(imported.copy(name = title))
            store.promptPresets()
        }
        mutable.update { it.copy(presets = presets) }
        notice("提示词预设已导入")
    }

    fun exportPromptPreset(uri: Uri, preset: PromptPreset) = work {
        withContext(Dispatchers.IO) { write(uri, preset.json().toString(2).encodeToByteArray()) }
        notice("提示词预设已导出")
    }

    fun allowRegex(target: RegexTarget, enabled: Boolean) = work {
        val config = current.prefs.regex
        fun update(ids: Set<String>) = if (enabled) ids + target.id else ids - target.id
        val regex = when (target.scope) {
            RegexScope.GLOBAL -> config.copy(enabled = enabled)
            RegexScope.CARD -> config.copy(allowedCards = update(config.allowedCards))
            RegexScope.PRESET -> config.copy(allowedPresets = update(config.allowedPresets))
        }
        persistPreferences(current.prefs.copy(regex = regex))
    }

    fun saveRegexRules(target: RegexTarget, rules: List<RegexRule>, done: () -> Unit = {}) = work {
        persistRegexRules(target, rules)
        done()
    }

    fun importRegexRules(uri: Uri, target: RegexTarget) = work {
        val rules = withContext(Dispatchers.IO) {
            requireNotNull(resolver.openInputStream(uri)) { "无法打开规则文件" }
                .use { RegexRule.import(it.readLimited().decodeToString()) }
        }
        val merged = current.regexRules(target).associateBy(RegexRule::id).toMutableMap()
        rules.forEach { merged[it.id] = it }
        persistRegexRules(target, merged.values.toList())
        notice("已导入 ${rules.size} 条正则规则")
    }

    fun exportRegexRules(uri: Uri, rules: List<RegexRule>) = work {
        withContext(Dispatchers.IO) {
            val value = if (rules.size == 1) rules.single().json().toString(2) else JSONArray(rules.map(RegexRule::json)).toString(2)
            write(uri, value.encodeToByteArray())
        }
        notice("正则规则已导出")
    }

    private suspend fun persistRegexRules(target: RegexTarget, rules: List<RegexRule>) {
        when (target.scope) {
            RegexScope.GLOBAL -> persistPreferences(current.prefs.copy(regex = current.prefs.regex.copy(global = rules)))
            RegexScope.CARD -> {
                val card = current.cards.firstOrNull { it.id == target.id } ?: error("找不到该角色")
                val cards = withContext(Dispatchers.IO) {
                    store.save(card.withRegexRules(rules))
                    store.cards()
                }
                mutable.update { it.copy(cards = cards) }
            }
            RegexScope.PRESET -> {
                val preset = current.presets.firstOrNull { it.id == target.id } ?: error("找不到该提示词预设")
                val presets = withContext(Dispatchers.IO) {
                    store.save(preset.copy(regexRules = rules))
                    store.promptPresets()
                }
                mutable.update { it.copy(presets = presets) }
            }
        }
    }

    private fun decodeAvatar(uri: Uri) =
        resizeAvatar(requireNotNull(resolver.openInputStream(uri)) { "无法读取头像文件" }.use { it.readLimited() })

    private fun resizeAvatar(image: ByteArray): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "所选文件不是可用的图片" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 640) sample *= 2
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(image, 0, image.size,
            BitmapFactory.Options().apply { inSampleSize = sample })) { "无法解码头像" }
        return try {
            val out = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "无法保存头像" }
            out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    fun saveProvider(draft: Provider, done: () -> Unit) = work {
        val provider = draft.validated()
        val prefs = current.prefs
        val providers = prefs.providers.toMutableList()
        val index = providers.indexOfFirst { it.id == provider.id }
        if (index >= 0) providers[index] = provider else providers.add(provider)
        val currentDefault = prefs.connectionFor()
        val nextDefault =
            if (currentDefault == null || currentDefault.provider.id == provider.id) provider.defaultSelection
            else prefs.defaultSelection
        persistPreferences(prefs.copy(providers = providers, defaultSelection = nextDefault))
        clearModels()
        done()
        notice("提供商已保存")
    }

    fun setDefaultProvider(provider: Provider) = work {
        require(provider.ready) { "请先配置此提供商" }
        persistPreferences(current.prefs.copy(defaultSelection = provider.defaultSelection))
        notice("默认模型已切换为 ${provider.defaultModel}")
    }

    fun deleteProvider(provider: Provider, done: () -> Unit) = work {
        val prefs = current.prefs
        val providers = prefs.providers.filterNot { it.id == provider.id }
        val nextDefault = prefs.defaultSelection?.takeUnless { it.providerId == provider.id }
            ?: providers.firstOrNull { it.ready }?.defaultSelection
        persistPreferences(prefs.copy(providers = providers, defaultSelection = nextDefault))
        clearModels()
        done()
        notice("提供商已移除")
    }

    fun loadModels(provider: Provider, persistCatalog: Boolean = false) = work {
        require(provider.template == null || provider.apiKey.isNotBlank()) { "请填写 API Key" }
        val discovered = ModelService.discoverModels(provider)
        if (persistCatalog) {
            val prefs = current.prefs
            persistPreferences(prefs.copy(providers = prefs.providers.map {
                if (it.id == provider.id) it.copy(models = discovered.ids, modelContexts = discovered.contexts) else it
            }))
        } else mutable.update {
            it.copy(catalog = ModelCatalog(provider.id, provider.endpoint.trim(), discovered.ids, discovered.contexts))
        }
        if (discovered.ids.isEmpty()) notice("连接成功，服务没有返回模型 ID")
    }

    fun switchModel(selection: ModelSelection?) = work {
        val chat = current.chat ?: return@work
        require(selection == null || current.prefs.resolve(selection) != null) { "请先配置此提供商" }
        persist(chat.copy(modelOverride = selection))
    }

    fun importEmbedded() = work {
        val message = withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            embedded.import(store) { context.ensureActive() }
        }
        refreshImportedLibrary()
        notice(message)
    }

    fun importOriginalBackup(uri: Uri) = utilityWork("正在读取 SillyTavern 备份…") {
        val app = getApplication<Application>()
        val context = currentCoroutineContext()
        try {
            val reports = withContext(Dispatchers.IO) {
                val filename = app.documentName(uri)
                requireNotNull(resolver.openInputStream(uri)) { "无法打开备份 ZIP" }.use { input ->
                    readOriginalBackup(input, app.cacheDir, filename, { context.ensureActive() }) { account, source ->
                        mutable.update { it.copy(utility = "正在导入账户 $account…") }
                        EmbeddedLibrary(source, savedDocuments, savedImages, account).import(store) { context.ensureActive() }
                    }
                }
            }
            notice(reports.joinToString("\n"))
        } finally {
            refreshImportedLibrary()
        }
    }

    fun exportOriginalBackup(uri: Uri) = utilityWork("正在打包本机数据…") {
        val app = getApplication<Application>()
        val result = withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            val archive = java.io.File.createTempFile("tavern-backup-", ".zip", app.cacheDir)
            try {
                val exported = archive.outputStream().buffered().use { output ->
                    writeOriginalBackup(output, store, savedDocuments, savedImages) { context.ensureActive() }
                }
                context.ensureActive()
                withContext(NonCancellable) {
                    mutable.update { it.copy(utility = "正在保存备份 ZIP…") }
                    requireNotNull(resolver.openOutputStream(uri, "wt")) { "无法写入所选文件" }.use { output ->
                        archive.inputStream().buffered().use { it.copyTo(output) }
                    }
                }
                exported
            } finally {
                archive.delete()
            }
        }
        val missing = if (result.missing == 0) "" else "。${result.missing} 个本机附件已缺失"
        notice("已导出 ${result.cards} 个角色、${result.groups} 个群组、${result.chats} 段聊天和 ${result.books} 本世界书$missing")
    }

    private suspend fun refreshImportedLibrary() {
        val library = withContext(NonCancellable + Dispatchers.IO) {
            val prefs = store.prefs()
            TavernState(cards = store.cards(), groups = store.groups(), books = store.books(), prefs = prefs,
                presets = store.promptPresets(), recentChats = store.recentChats(prefs.welcome.pinnedIds), dataDocuments = store.dataDocuments())
        }
        mutable.update { it.copy(cards = library.cards, groups = library.groups, books = library.books, prefs = library.prefs,
            presets = library.presets, recentChats = library.recentChats, dataDocuments = library.dataDocuments) }
    }

    fun saveCard(card: Card, done: () -> Unit) = work {
        require(card.name.isNotBlank()) { "请输入角色名称" }
        val cards = withContext(Dispatchers.IO) { store.save(card); store.cards() }
        mutable.update { it.copy(cards = cards) }
        done()
    }

    suspend fun refreshLibraryUsage() {
        try {
            val usage = withContext(Dispatchers.IO) { store.libraryUsage() }
            mutable.update { it.copy(libraryUsage = usage) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            notice("无法读取角色库的聊天统计")
        }
    }

    private suspend fun persistLibrary(library: LibraryPreferences) {
        if (library != current.prefs.library) persistPreferences(current.prefs.copy(library = library))
    }

    fun sortLibrary(sort: LibrarySort) = work { persistLibrary(current.prefs.library.copy(sort = sort)) }

    fun showLibraryFolders(show: Boolean) = work { persistLibrary(current.prefs.library.copy(folders = show)) }

    fun toggleLibraryFavorite(id: String) = work {
        val library = current.prefs.library
        val item = library.entities(current.cards, current.groups).firstOrNull { it.id == id } ?: return@work
        persistLibrary(library.copy(favorites = library.favorites + (id to !item.favorite)))
    }

    fun saveLibraryTag(tag: LibraryTag, done: () -> Unit) = work {
        val library = current.prefs.library.materialized(current.cards)
        val name = tag.name.trim()
        require(name.isNotEmpty()) { "请输入标签名称" }
        require(library.tags.none { it.id != tag.id && it.name.equals(name, true) }) { "同名标签已存在" }
        val value = tag.copy(name = name, color = tag.color.trim(), textColor = tag.textColor.trim())
        val tags = library.tags.toMutableList()
        val index = tags.indexOfFirst { it.id == value.id }
        val createsFolder = value.folder != TagFolder.NONE && (tags.getOrNull(index)?.folder ?: TagFolder.NONE) == TagFolder.NONE
        if (index < 0) tags += value else tags[index] = value
        persistLibrary(library.copy(tags = tags, folders = library.folders || createsFolder))
        done()
    }

    fun moveLibraryTag(id: String, direction: Int) = work {
        val library = current.prefs.library.materialized(current.cards)
        val tags = library.tags.toMutableList()
        val index = tags.indexOfFirst { it.id == id }
        val target = index + direction
        if (index < 0 || target !in tags.indices) return@work
        tags.add(target, tags.removeAt(index))
        persistLibrary(library.copy(tags = tags))
    }

    fun removeLibraryTag(id: String) = work {
        val library = current.prefs.library.materialized(current.cards)
        persistLibrary(library.copy(tags = library.tags.filterNot { it.id == id },
            assignments = library.assignments.mapValues { (_, ids) -> ids - id }))
    }

    fun assignLibraryTags(owners: Set<String>, changes: Map<String, Boolean>, newName: String, done: () -> Unit) = work {
        val library = current.prefs.library.materialized(current.cards)
        val targets = library.entities(current.cards, current.groups).mapTo(hashSetOf(), LibraryItem::id).intersect(owners)
        if (targets.isEmpty()) return@work
        val tags = library.tags.toMutableList()
        val tagIds = tags.mapTo(hashSetOf(), LibraryTag::id)
        val add = changes.filterValues { it }.keys.intersect(tagIds).toMutableSet()
        val remove = changes.filterValues { !it }.keys
        newName.trim().takeIf(String::isNotBlank)?.let { name ->
            val tag = tags.firstOrNull { it.name.equals(name, true) } ?: LibraryTag(name = name).also(tags::add)
            add += tag.id
        }
        val links = library.assignments.toMutableMap()
        targets.forEach { id -> links[id] = (links[id].orEmpty() - remove) + add }
        persistLibrary(library.copy(tags = tags, assignments = links))
        done()
    }

    fun exportLibraryTags(uri: Uri) = work {
        val library = current.prefs.library.materialized(current.cards)
        val entries = library.entities(current.cards, current.groups)
        withContext(Dispatchers.IO) { write(uri, library.tagBackup(entries).toString(2).encodeToByteArray()) }
        notice("标签备份已导出")
    }

    fun importLibraryTags(uri: Uri) = work {
        val library = current.prefs.library.materialized(current.cards)
        val existing = library.entities(current.cards, current.groups)
        val byId = existing.associateBy(LibraryItem::id)
        val byTitle = existing.groupBy { (it.card != null) to it.name }
        val backup = withContext(Dispatchers.IO) {
            val bytes = requireNotNull(resolver.openInputStream(uri)) { "无法打开标签备份" }.use { it.readLimited() }
            boundedJsonObject(bytes.decodeToString().trimStart('\uFEFF'), "无法读取标签 JSON")
        }
        require(backup.optJSONArray("tags") != null) { "标签备份缺少 tags 列表" }
        val names = backup.optJSONObject("native_owners")
        val imported = library.mergeTags(backup, ownerFor = { id ->
            byId[id]?.id ?: when {
                names?.has(id) == true -> byTitle[(id.startsWith("card:")) to names.string(id)]?.singleOrNull()?.id
                id.endsWith(".png", true) -> byId[libraryCardId(previewCharacterId(id))]?.id
                else -> byId[libraryGroupId(previewGroupId(id))]?.id
            }
        })
        persistLibrary(imported.preferences.copy(folders = library.folders || backup.optBoolean("folders")))
        notice("已导入 ${imported.added} 个新标签" +
            if (imported.unmatched > 0) "，${imported.unmatched} 个标签关联未找到对应角色或群组" else "")
    }

    fun addExample() = work {
        val example = Card.from(jsonOf(
            "name" to "岚",
            "description" to "经营着海边山坡上的一间旧书店。她会给每本修好的书夹上一张手写的纸条。",
            "personality" to "好奇、敏锐，习惯把重要的事情说得轻描淡写。",
            "scenario" to "暴雨淹没小镇的海堤。书店快打烊了，窗外的路灯忽明忽暗。",
            "first_mes" to "*门铃轻响。岚把摊在账台上的地图折好，抬眼望过来。*\n\n「进来吧，{{user}}。你是来躲雨的，还是在找那本从目录上消失的书？」",
        ))
        val cards = withContext(Dispatchers.IO) { store.save(example); store.cards() }
        mutable.update { it.copy(cards = cards, cardId = example.id, chat = null, history = emptyList()) }
    }

    fun importCard(uri: Uri) = importCards(listOf(uri))

    private fun characterFiles(input: java.io.InputStream, name: String, userName: String,
                               includeExtras: Boolean = true): List<ImportedCharacterArchive> =
        readCharacterFiles(input, name, getApplication<Application>().cacheDir, userName, ::resizeAvatar, imageStore::import, includeExtras)

    private fun externalImporter(userName: String, includeExtras: Boolean = true) = ExternalContentImporter(
        { input, name -> characterFiles(input, name, userName, includeExtras) }, ::resizeAvatar,
    )

    fun importCards(uris: List<Uri>) = work {
        if (uris.isEmpty()) return@work
        val app = getApplication<Application>()
        val userName = current.prefs.personaFor().name
        var failures = 0
        var chats = 0
        var skippedPictures = 0
        var firstFailure: String? = null
        val (cards, count) = withContext(Dispatchers.IO) {
            var added = 0
            for (uri in uris.distinct()) {
                currentCoroutineContext().ensureActive()
                val name = app.documentName(uri).takeUnless { it == "导入的聊天" } ?: "角色卡文件"
                try {
                    val imported = requireNotNull(resolver.openInputStream(uri)) { "无法打开角色卡" }.use { input ->
                        characterFiles(input, name, userName)
                    }
                    if (imported.all { it.card.portrait == null && it.chats.isEmpty() && it.pictures.isEmpty() }) {
                        added += store.importCharacters(imported.map { it.card.card })
                        skippedPictures += imported.sumOf(ImportedCharacterArchive::skippedImages)
                    } else {
                        for (archive in imported) {
                            chats += store.import(archive.card, archive.chats, archive.pictures)
                            skippedPictures += archive.skippedImages
                            added++
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++
                    if (firstFailure == null) {
                        firstFailure = "${name.take(100)} · ${(e.localizedMessage ?: "无法读取角色卡").take(160)}"
                    }
                }
            }
            store.cards() to added
        }
        mutable.update { it.copy(cards = cards) }
        val summary = buildString {
            append("已导入 $count 张角色卡")
            if (chats > 0) append("、$chats 个聊天")
            if (skippedPictures > 0) append("，$skippedPictures 张素材图片未能读取")
        }
        notice(if (failures == 0) summary else "$summary，$failures 个文件失败。$firstFailure")
    }

    fun importExternalContent(addresses: String): Boolean = utilityWork("正在导入分享链接…") {
        val rows = addresses.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        require(rows.isNotEmpty()) { "请输入角色或世界书的分享链接" }
        val importer = externalImporter(current.prefs.personaFor().name)
        val visited = hashSetOf<Pair<ExternalContentSite, String>>()
        var importedCards = 0
        var importedBooks = 0
        var chats = 0
        var skippedImages = 0
        var failures = 0
        var firstFailure: String? = null
        val statusCode = Regex("HTTP [1-5][0-9]{2}")
        try {
            for ((index, line) in rows.withIndex()) {
                currentCoroutineContext().ensureActive()
                try {
                    val source = parseExternalContentSource(line)
                    if (!visited.add(source.site to source.id)) continue
                    val payload = importer.load(source)
                    withContext(Dispatchers.IO) {
                        when (payload) {
                            is ExternalContent.Characters -> payload.cards.forEach { entry ->
                                chats += store.import(entry.card, entry.chats, entry.pictures)
                                importedCards++
                                skippedImages += entry.skippedImages
                            }
                            is ExternalContent.Book -> {
                                saveImportedBook(payload.book, store.books().map(WorldBook::name))
                                importedBooks++
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (error: Exception) {
                    failures++
                    if (firstFailure == null) {
                        val status = statusCode.find(error.message.orEmpty())?.value
                        firstFailure = "第 ${index + 1} 行未能读取" + if (status == null) "" else "（$status）"
                    }
                }
            }
        } finally {
            if (importedCards + importedBooks > 0) withContext(NonCancellable) {
                val (cards, books) = withContext(Dispatchers.IO) { store.cards() to store.books() }
                mutable.update { it.copy(cards = cards, books = books) }
            }
        }
        val summary = buildString {
            append("已导入 $importedCards 张角色卡、$importedBooks 本世界书")
            if (chats > 0) append("、$chats 个聊天")
            if (skippedImages > 0) append("，$skippedImages 张图片未能读取")
            if (failures > 0) append("，$failures 个链接失败。$firstFailure")
        }
        notice(summary)
    }

    fun updateCardFromLink(card: Card, address: String): Boolean = utilityWork("正在更新角色卡…") {
        val source = parseExternalContentSource(address)
        require(source.site != ExternalContentSite.CHUB_BOOK) { "请选择角色卡分享链接" }
        val downloaded = externalImporter(current.persona.name, includeExtras = false).load(source) as ExternalContent.Characters
        updateCardDefinitions(card, downloaded.cards)
    }

    fun updateCardFromFile(card: Card, uri: Uri) = utilityWork("正在读取角色卡…") {
        val name = getApplication<Application>().documentName(uri)
        val persona = current.persona.name
        val cards = withContext(Dispatchers.IO) {
            requireNotNull(resolver.openInputStream(uri)) { "无法打开角色卡" }
                .use { characterFiles(it, name, persona, includeExtras = false) }
        }
        updateCardDefinitions(card, cards)
    }

    private suspend fun updateCardDefinitions(card: Card, entries: List<ImportedCharacterArchive>) {
        val downloaded = entries.singleOrNull() ?: error("选择一张角色卡来更新当前角色")
        val value = downloaded.card
        var updated = value.card.copy(id = card.id, hasAvatar = value.portrait != null || card.hasAvatar)
            .edited(mapOf("name" to card.name), value.card.alternateGreetings)
            .withWorld(card.worldName).withAuthorNote(card.authorNote).withLibraryFavorite(card.libraryFavorite)
        card.expressionSprites?.let { updated = updated.withExpressionSprites(it) }
        if (updated.sourceLink == null) card.sourceLink?.let { updated = updated.withSourceLink(it) }
        withContext(NonCancellable) {
            val cards = withContext(Dispatchers.IO) { store.replaceCard(updated, value.portrait); store.cards() }
            mutable.update { it.copy(cards = cards) }
        }
        notice("角色设定已更新")
    }

    fun duplicateCard(card: Card) = work {
        val names = current.cards.mapTo(hashSetOf()) { it.name.lowercase() }
        val base = "${card.name} · 副本"
        val name = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }
            .first { it.lowercase() !in names }
        val id = newId()
        val cards = withContext(Dispatchers.IO) {
            val portrait = if (card.hasAvatar) store.portraitFile(card.id).inputStream().use { it.readLimited() } else null
            val copy = card.copy(id = id).edited(mapOf("name" to name), card.alternateGreetings)
            store.import(CardImport(copy, portrait), emptyList())
            store.cards()
        }
        val library = current.prefs.library.materialized(current.cards)
        val tags = library.assignments[libraryCardId(card.id)].orEmpty()
        mutable.update { it.copy(cards = cards, cardId = id, groupId = null, chat = null, history = emptyList()) }
        if (tags.isNotEmpty()) persistLibrary(library.copy(assignments = library.assignments + (libraryCardId(id) to tags)))
        notice("已创建角色副本")
    }

    fun deleteCard(card: Card) = work {
        val (cards, groups) = withContext(Dispatchers.IO) { store.deleteCard(card.id); store.cards() to store.groups() }
        mutable.update { it.copy(cards = cards, groups = groups, chat = null, cardId = null, history = emptyList()) }
        persistLibrary(current.prefs.library.removeOwner(libraryCardId(card.id)))
    }

    fun exportCard(uri: Uri, card: Card) = work {
        withContext(Dispatchers.IO) {
            val payload = if (card.hasAvatar) CardFile.png(card, store.portraitFile(card.id).readBytes())
                else card.portableJson().toString(2).toByteArray(Charsets.UTF_8)
            write(uri, payload)
        }
        notice("角色卡已导出")
    }

    fun openCard(card: Card) = work {
        val history = withContext(Dispatchers.IO) { store.chats(card.id) }
        discardTemporaryChat()
        mutable.update { it.copy(cardId = card.id, groupId = null, chat = null, history = history) }
    }

    fun closeChat() {
        if (current.chat?.isTemporary == true) return closeSubject()
        stopAuto()
        mutable.update { it.copy(chat = null, temporaryPersonaId = null) }
    }

    fun closeSubject() {
        stopAuto()
        discardTemporaryChat()
        mutable.update { it.copy(cardId = null, groupId = null, chat = null, history = emptyList(), temporaryPersonaId = null) }
    }

    fun saveGroup(group: TavernGroup, done: () -> Unit) = work {
        val members = group.members(current.cards).map(Card::id)
        val name = group.name.trim()
        require(name.isNotBlank() && members.isNotEmpty()) { "请输入群组名称并选择至少一个角色" }
        val saved = group.copy(name = name, memberIds = members, mutedIds = group.mutedIds.intersect(members.toSet()))
        val groups = withContext(Dispatchers.IO) { store.save(saved); store.groups() }
        mutable.update { it.copy(groups = groups) }
        done()
    }

    fun openGroup(group: TavernGroup) = work {
        val history = withContext(Dispatchers.IO) { store.chats(groupId = group.id) }
        discardTemporaryChat()
        mutable.update { it.copy(cardId = null, groupId = group.id, chat = null, history = history) }
    }

    fun deleteGroup(group: TavernGroup) = work {
        val groups = withContext(Dispatchers.IO) { store.deleteGroup(group.id); store.groups() }
        mutable.update { it.copy(groups = groups, groupId = null, chat = null, history = emptyList()) }
        persistLibrary(current.prefs.library.removeOwner(libraryGroupId(group.id)))
    }

    fun newGroupChat(group: TavernGroup) = work {
        val title = if (current.history.isEmpty()) "新群聊" else "新群聊 · ${current.history.size + 1}"
        persist(Chat(newId(), null, title, groupId = group.id,
            options = ConversationOptions(authorNote = current.prefs.defaultAuthorNote)))
        quickEvent(QuickEvent.NEW)
        quickEvent(QuickEvent.CHAT)
    }

    fun saveBook(book: WorldBook, done: () -> Unit) = work {
        val value = book.copy(name = book.name.trim())
        require(value.name.isNotBlank()) { "请输入世界书名称" }
        require(current.books.none { it.id != value.id && it.name.equals(value.name, true) }) { "世界书名称已存在" }
        val linkedChat = current.chat?.takeIf { chat ->
            !chat.isTemporary && chat.options.hasWorldBookOverride &&
                chat.options.worldBook(current.books)?.let { it.id == value.id && it.name != value.name } == true
        }
        val (books, cards, chat) = withContext(Dispatchers.IO) {
            store.save(value)
            Triple(store.books(), store.cards(), linkedChat?.let { store.chat(it.id) })
        }
        mutable.update { it.copy(books = books, cards = cards, chat = chat?.takeIf { saved -> saved.id == it.chat?.id } ?: it.chat) }
        done()
    }

    fun deleteBook(book: WorldBook) = work {
        val (books, cards) = withContext(Dispatchers.IO) { store.deleteBook(book); store.books() to store.cards() }
        mutable.update { it.copy(books = books, cards = cards) }
    }

    fun importBook(uri: Uri, displayName: String) = work {
        val books = withContext(Dispatchers.IO) {
            val source = requireNotNull(resolver.openInputStream(uri)) { "无法打开世界书文件" }
                .use { it.readLimited().toString(Charsets.UTF_8) }.trimStart('\uFEFF')
            val parsed = WorldBook.import(JSONObject(source), displayName.removeSuffix(".json"))
            saveImportedBook(parsed, store.books().map(WorldBook::name))
            store.books()
        }
        mutable.update { it.copy(books = books) }
        notice("世界书已导入")
    }

    private fun saveImportedBook(book: WorldBook, names: List<String>) {
        val expected = names.mapTo(hashSetOf()) { it.lowercase() }
        val name = generateSequence(1) { it + 1 }.map { if (it == 1) book.name else "${book.name} ($it)" }
            .first { it.lowercase() !in expected }
        store.save(book.copy(name = name))
    }

    fun exportBook(uri: Uri, book: WorldBook) = work {
        withContext(Dispatchers.IO) { write(uri, book.export()) }
        notice("世界书已导出")
    }

    fun newChat(card: Card, greeting: String) = work { startChat(card, greeting) }

    fun startWelcomeChat(message: String, opened: () -> Unit) {
        val input = message.trim()
        work(completed = {
            if (input.isNotBlank() && !send(input, interpretCommands = false)) {
                current.chat?.let { chat -> mutable.update { it.copy(suggestedDraft = DraftSuggestion(chat.id, input)) } }
            }
            opened()
        }) {
            if (input.isNotBlank()) require(current.prefs.connectionFor() != null) { "请先配置模型提供商" }
            val assistant = current.welcomeAssistant
            if (current.cards.none { it.id == assistant.id }) {
                val cards = withContext(Dispatchers.IO) { store.save(assistant); store.cards() }
                mutable.update { it.copy(cards = cards) }
            }
            startChat(assistant, assistant.greetings.firstOrNull().orEmpty())
        }
    }

    private suspend fun startChat(card: Card, greeting: String) {
        val prefs = current.prefs
        val persona = prefs.personaFor(subjectId = card.id)
        val chat = Chat(newId(), card.id, "", options = ConversationOptions(authorNote = current.prefs.defaultAuthorNote))
        val preset = current.copy(cardId = card.id, groupId = null).presetFor(chat)
        val rules = prefs.regex.runtime(card, persona.name, preset)
        val (messages, variables) = if (greeting.isBlank()) emptyList<Message>() to MacroVariableChanges()
            else withContext(Dispatchers.Default) {
                val macros = ConversationMacros(card, persona, options = chat.options, globals = prefs.scriptVariables,
                    model = prefs.connectionFor(chat)?.model.orEmpty(), chatId = chat.id)
                val text = rules.transform(macros.expand(greeting, write = true), RegexSite.REPLY, RegexStage.STORAGE)
                val message = text.takeIf(String::isNotBlank)?.let { Message("assistant", card.name, it, characterId = card.id) }
                listOfNotNull(message) to macros.variables.changes()
            }
        val history = if (current.cardId == card.id) current.history else withContext(Dispatchers.IO) { store.chats(card.id) }
        val title = if (history.isEmpty()) "新对话" else "新对话 · ${history.size + 1}"
        val locals = chat.options.variables.withVariableChanges(variables.local)
        val globals = variables.global.takeIf { it.isNotEmpty() }?.let { current.prefs.scriptVariables.withVariableChanges(it) }
        withContext(NonCancellable) {
            persist(chat.copy(title = title, messages = messages, options = chat.options.copy(variables = locals)), scriptGlobals = globals)
        }
        messages.forEach { narrate(it, automatic = true) }
        quickEvent(QuickEvent.NEW)
        quickEvent(QuickEvent.CHAT)
    }

    fun openChat(id: String) = work {
        stopAuto()
        val (chat, history) = withContext(Dispatchers.IO) {
            val opened = store.chat(id)
            store.rememberLastChat(id)
            opened to store.chats(opened.cardId, opened.groupId)
        }
        showChat(chat, history, resetPersona = true)
        quickEvent(QuickEvent.CHAT)
    }

    fun openTemporaryChat(uri: Uri? = null, title: String = "临时聊天") = work {
        stopAuto()
        val media = withContext(Dispatchers.IO) { TemporaryChatMedia(getApplication<Application>().cacheDir) }
        val chat = try {
            if (uri == null) Chat(newId(), temporaryAssistant.id, title)
            else withContext(Dispatchers.IO) {
                readChatFile(uri, title, temporaryAssistant, null, emptyList(), media.documents, media.images)
            }
        } catch (e: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { media.close() }
            throw e
        }
        discardTemporaryChat()
        temporaryMedia = media
        showChat(chat, emptyList(), resetPersona = true)
        if (uri != null) notice("已载入临时聊天")
    }

    private fun discardTemporaryChat() {
        val media = temporaryMedia ?: return
        val id = current.chat?.takeIf(Chat::isTemporary)?.id
        temporaryMedia = null
        viewModelScope.launch(Dispatchers.IO) { media.close() }
        if (id != null) mutable.update {
            it.copy(draftImages = it.draftImages - id, draftImageCaptions = it.draftImageCaptions - id, draftDocuments = it.draftDocuments - id,
                suggestedDraft = it.suggestedDraft?.takeUnless { draft -> draft.chatId == id },
                lastPrompt = it.lastPrompt?.takeUnless { prompt -> prompt.chatId == id },
                manualExpressions = it.manualExpressions.filterValues { expression -> expression.chatId != id },
                messageSelection = it.messageSelection?.takeUnless { request -> request.chatId == id })
        }
    }

    private fun showChat(chat: Chat, history: List<ChatSummary>, resetPersona: Boolean = false) {
        if (!chat.isTemporary) discardTemporaryChat()
        mutable.update { it.copy(cardId = chat.cardId.takeUnless { chat.isTemporary }, groupId = chat.groupId,
            chat = chat, history = history, temporaryPersonaId = it.temporaryPersonaId.takeUnless { resetPersona }) }
    }

    fun renameChat(chat: Chat, name: String) = work {
        require(name.isNotBlank()) { "请输入聊天名称" }
        persist(chat.copy(title = name.trim()))
    }

    fun forkChat(position: Int, checkpoint: Boolean, requestedName: String = "") = work {
        val chat = current.chat ?: return@work
        require(!chat.isTemporary) { "请先将临时聊天导入角色或群组" }
        stopAuto()
        val title = requestedName.trim().ifBlank { chat.nextForkTitle(current.history, checkpoint) }
        require(current.history.none { it.title == title }) { "聊天名称已存在" }
        val (source, fork) = chat.fork(position, title, checkpoint)
        val history = withContext(Dispatchers.IO) {
            store.saveFork(source, fork)
            if (!checkpoint) store.rememberLastChat(fork.id)
            store.chats(source.cardId, source.groupId)
        }
        mutable.update { it.copy(chat = if (checkpoint) source else fork, history = history,
            temporaryPersonaId = it.temporaryPersonaId.takeIf { checkpoint }) }
        if (checkpoint) notice("检查点已保存")
    }

    fun convertToGroup(done: () -> Unit) = work {
        val original = current.chat ?: return@work
        require(!original.isTemporary) { "请先将临时聊天导入角色或群组" }
        val card = current.card ?: return@work
        stopAuto()
        val used = current.groups.mapTo(mutableSetOf(), TavernGroup::name)
        val base = "${card.name} 群聊"
        val name = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base · $it" }.first { it !in used }
        val group = TavernGroup(name = name, memberIds = listOf(card.id))
        val chat = original.withOwner(null, group, listOf(card), sourceCard = card, asCopy = true)
        val (groups, history) = withContext(Dispatchers.IO) {
            check(store.import(group, listOf(chat)) == 1) { "无法复制群聊" }
            store.rememberLastChat(chat.id)
            store.groups() to store.chats(groupId = group.id)
        }
        mutable.update { it.copy(groups = groups, history = history, cardId = null, groupId = group.id,
            chat = chat, temporaryPersonaId = null) }
        done()
        notice("已创建群聊副本")
    }

    fun copyChatTo(ownerId: String, toGroup: Boolean, done: () -> Unit) = work {
        val original = current.chat ?: return@work
        require(!original.isTemporary) { "请先将临时聊天导入角色或群组" }
        val group = if (toGroup) requireNotNull(current.groups.firstOrNull { it.id == ownerId }) { "找不到目标群组" } else null
        val card = if (toGroup) null else requireNotNull(current.cards.firstOrNull { it.id == ownerId }) { "找不到目标角色" }
        copyHistory(original, card, group)
        done()
        notice("已复制到「${card?.name ?: group?.name}」")
    }

    fun loadChatImportHistory(ownerId: String, fromGroup: Boolean, loaded: (List<ChatSummary>) -> Unit) = work {
        val history = withContext(Dispatchers.IO) {
            if (fromGroup) store.chats(groupId = ownerId) else store.chats(cardId = ownerId)
        }
        loaded(history)
    }

    fun importLocalChat(sourceOwnerId: String, chatId: String, done: () -> Unit) = work {
        val (card, group) = current.card to current.group
        require(card != null || group != null) { "请先选择目标角色或群组" }
        val original = withContext(Dispatchers.IO) { store.chat(chatId) }
        require(if (group != null) original.cardId == sourceOwnerId else original.groupId == sourceOwnerId) {
            "找不到所选的聊天记录"
        }
        copyHistory(original, card, group)
        done()
        notice("聊天已导入")
    }

    private suspend fun copyHistory(original: Chat, card: Card?, group: TavernGroup?) {
        val cards = current.cards
        val sourceCard = cards.firstOrNull { it.id == original.cardId }
        stopAuto()
        val (chat, history) = withContext(Dispatchers.IO) {
            val copy = original.withOwner(card, group, group?.members(cards).orEmpty(), sourceCard,
                asCopy = true, knownCards = cards)
            val titled = copy.copy(title = copy.availableTitle(store.chats(copy.cardId, copy.groupId)))
            store.save(titled)
            store.rememberLastChat(titled.id)
            titled to store.chats(titled.cardId, titled.groupId)
        }
        showChat(chat, history, resetPersona = true)
    }

    fun removeChat(chat: ChatSummary) = work {
        val (cardId, groupId) = current.cardId to current.groupId
        val history = withContext(Dispatchers.IO) { store.deleteChat(chat.id); store.chats(cardId, groupId) }
        mutable.update { it.copy(history = history, chat = it.chat?.takeUnless { open -> open.id == chat.id }) }
    }

    fun importChat(uri: Uri, title: String) = work {
        val (card, group) = current.card to current.group
        require(card != null || group != null) { "请先选择目标角色或群组" }
        require(current.chat?.isTemporary != true) { "请使用载入临时聊天" }
        val cards = current.cards
        stopAuto()
        val chat = withContext(Dispatchers.IO) { readChatFile(uri, title, card, group, cards, savedDocuments, savedImages) }
        persist(chat)
        notice("聊天已导入")
    }

    private suspend fun readChatFile(uri: Uri, title: String, card: Card?, group: TavernGroup?, cards: List<Card>,
                                     documents: ChatDocumentStore, images: ChatImageStore): Chat {
        val members = group?.members(cards).orEmpty()
        fun parse(bytes: ByteArray): Chat {
            val root = bytes.toString(Charsets.UTF_8).trimStart('\uFEFF')
            val rows = root.lineSequence().withIndex().filter { it.value.isNotBlank() }.map { (index, line) ->
                boundedJsonObject(line, "无法读取聊天文件第 ${index + 1} 行")
            }.toList()
            val chatTitle = title.replace(Regex("(?i)\\.(jsonl|zip)$"), "").ifBlank { "导入的聊天" }
            return Chat.import(null, chatTitle, rows).withOwner(card, group, members, knownCards = cards)
        }
        return requireNotNull(resolver.openInputStream(uri)) { "无法打开聊天文件" }.buffered().use { input ->
            input.mark(4)
            val header = ByteArray(4)
            for (index in header.indices) {
                val next = input.read()
                if (next < 0) break
                header[index] = next.toByte()
            }
            val archive = ChatMediaArchive.isArchive(header)
            input.reset()
            if (archive) ChatMediaArchive.read(input, ::parse, documents::restore, images::restore)
            else hydrateOriginalDocuments(parse(input.readLimited()), documents).first
        }
    }

    fun exportChat(uri: Uri, chat: Chat, subject: String, withAttachments: Boolean = false) = work {
        val name = current.persona.name
        val book = chat.options.worldBook(current.books)
        val history = current.history
        val quickNames = chat.options.quickReplyNames + chat.options.quickReplySets.mapNotNull { id ->
            current.prefs.quickReplies.sets.firstOrNull { it.id == id }?.name
        }
        val portable = chat.copy(messages = chat.messages.map { it.portable(history) },
            options = chat.options.copy(worldBookId = "", parent = chat.options.parent?.portable(history),
                quickReplySets = emptyList(), quickReplyNames = quickNames.distinct(),
                worldBookName = book?.name ?: chat.options.worldBookName.takeIf { chat.options.worldBookId.isEmpty() }.orEmpty()))
        withContext(Dispatchers.IO) {
            val transcript = portable.jsonl(subject, name)
            if (withAttachments) requireNotNull(resolver.openOutputStream(uri, "wt")) { "无法写入所选文件" }.use { output ->
                ChatMediaArchive.write(output, transcript, portable, documentStore::bytes, imageStore::bytes)
            } else write(uri, transcript)
        }
        notice("聊天已导出")
    }

    fun exportChatText(uri: Uri, chat: Chat) = work {
        withContext(Dispatchers.IO) { write(uri, chat.plainText()) }
        notice("聊天文本已导出")
    }

    fun editMessage(chat: Chat, index: Int, text: String) = work {
        require(text.isNotBlank()) { "消息不能为空" }
        stopAuto()
        val updated = chat.messages.toMutableList()
        val message = updated[index]
        val rules = current.textRules(chat, current.regexCard(message))
        val replacement = withContext(Dispatchers.Default) {
            message.regexSite?.let { rules.transform(text, it, RegexStage.STORAGE, editing = true) } ?: text
        }
        updated[index] = message.withText(replacement)
        persist(chat.copy(messages = updated, updated = System.currentTimeMillis()))
    }

    fun editReasoning(chat: Chat, index: Int, text: String) = work {
        stopAuto()
        val updated = chat.messages.toMutableList()
        val message = updated[index]
        val rules = current.textRules(chat, current.regexCard(message))
        val value = withContext(Dispatchers.Default) {
            rules.transform(text, RegexSite.REASONING, RegexStage.STORAGE, editing = true)
        }
        updated[index] = message.withReasoning(value.takeIf(String::isNotBlank))
        persist(chat.copy(messages = updated, updated = System.currentTimeMillis()))
    }

    fun deleteMessages(chat: Chat, indices: Set<Int>, includeTools: Boolean = true) = work {
        stopAuto()
        removeMessages(chat, indices, includeTools)
    }

    private suspend fun removeMessages(chat: Chat, indices: Set<Int>, includeTools: Boolean) {
        val removed = chat.messages.deletionIndices(indices, includeTools)
        if (removed.isNotEmpty()) persist(chat.copy(messages = chat.messages.filterIndexed { index, _ -> index !in removed },
            updated = System.currentTimeMillis()))
    }

    fun duplicateMessage(chat: Chat, index: Int) = work {
        stopAuto()
        val original = chat.messages[index]
        val copy = original.copy(checkpoint = null, branches = emptyList())
        persist(chat.copy(messages = chat.messages.toMutableList().apply { add(index + 1, copy) },
            updated = System.currentTimeMillis()))
    }

    fun moveMessage(chat: Chat, index: Int, direction: Int) = work {
        stopAuto()
        val destination = index + direction
        require(destination in chat.messages.indices) { "消息已在对话边界" }
        val moved = chat.messages.toMutableList().apply { add(destination, removeAt(index)) }
        persist(chat.copy(messages = moved, updated = System.currentTimeMillis()))
    }

    fun swipe(chat: Chat, messageIndex: Int, option: Int) = work {
        val active = current.chat?.takeIf { it.id == chat.id } ?: return@work
        val message = active.messages.getOrNull(messageIndex) ?: return@work
        if (option !in message.variants.indices || option == message.index) return@work
        stopAuto()
        updateMessage(active, messageIndex, message.select(option))
    }

    fun removeReplyAlternative(chatId: String, messageIndex: Int, option: Int) = work {
        val active = current.chat?.takeIf { it.id == chatId } ?: return@work
        val message = active.messages.getOrNull(messageIndex) ?: return@work
        if (message.role != "assistant" || message.variants.size < 2 || option !in message.variants.indices) return@work
        stopAuto()
        updateMessage(active, messageIndex, message.withoutAlternative(option))
    }

    private suspend fun persist(chat: Chat, drawing: GalleryImage? = null, scriptGlobals: Map<String, String>? = null) {
        val changedChat = current.chat?.id != chat.id
        val (saved, history) = withContext(Dispatchers.IO) {
            val validated = chat.copy(messages = chat.messages.removeInvalidSummaries())
            val history = if (validated.isTemporary) {
                if (scriptGlobals != null) store.commitScriptVariables(null, scriptGlobals)
                emptyList()
            } else {
                if (scriptGlobals == null) store.save(validated, drawing)
                else store.commitScriptVariables(validated, scriptGlobals)
                if (changedChat) store.rememberLastChat(validated.id)
                store.chats(validated.cardId, validated.groupId)
            }
            validated to history
        }
        if (scriptGlobals != null) mutable.update { it.copy(prefs = it.prefs.copy(scriptVariables = scriptGlobals)) }
        showChat(saved, history, resetPersona = changedChat)
    }

    private fun utilityWork(label: String, action: suspend () -> Unit): Boolean {
        if (current.busy || current.generation != null || current.scriptRunning) return false
        stopAuto()
        mutable.update { it.copy(busy = true, utility = label) }
        utilityJob = viewModelScope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice(e.localizedMessage ?: "操作失败，请重试")
            } finally {
                mutable.update { it.copy(busy = false, utility = null) }
                utilityJob = null
            }
        }
        return true
    }

    private fun utilityConnection(chat: Chat): ModelConnection = requireNotNull(current.prefs.connectionFor(chat)) { "请先配置对话模型" }

    fun saveVectorPreferences(options: VectorPreferences, done: () -> Unit) = work {
        require(!options.enabled || current.vectorSource(options) != null) { "请选择可用的向量服务或安装本地模型" }
        persistPreferences(current.prefs.copy(vectors = options))
        done()
    }

    fun testVectorPreferences(options: VectorPreferences) = utilityWork("正在测试向量服务…") {
        val source = requireNotNull(current.vectorSource(options)) { "请选择可用的向量服务或安装本地模型" }
        val text = if (source is EmbeddingSource.Local && source.model == LocalEmbeddingModel.BGE_ZH) "测试本地向量检索"
            else "Tavern Native embedding test"
        val vector = embeddingService.embed(source, listOf(EmbeddingInput("", text, query = true))).single()
        val label = if (source is EmbeddingSource.Local) "本地模型运行成功" else "向量服务连接成功"
        notice("$label · ${vector.size} 维")
    }

    fun downloadEmbeddingModel(model: LocalEmbeddingModel) = utilityWork("正在下载本地嵌入模型…") {
        installEmbeddingModel(model) { install ->
            downloadModelWeights("${model.downloadPath}?download=true", install) {
                mutable.update { it.copy(utility = "正在从镜像下载本地嵌入模型…") }
            }
        }
    }

    fun downloadCaptionModel() = utilityWork("正在下载本地图片描述模型…") {
        installCaptionModel { progress ->
            var completed = 0L
            for (part in CaptionWeights.entries) {
                if (!localCaptioner.installed(part)) {
                    downloadModelWeights(part.path, { input -> localCaptioner.install(part, input) { progress(completed + it) } }) {
                        mutable.update { it.copy(utility = "正在从镜像下载本地图片描述模型…") }
                    }
                }
                completed += part.size
                progress(completed)
            }
        }
    }

    fun importCaptionModel(uri: Uri) = utilityWork("正在导入本地图片描述模型…") {
        installCaptionModel { progress ->
            withContext(Dispatchers.IO) {
                requireNotNull(resolver.openInputStream(uri)) { "无法打开模型包" }.use { localCaptioner.importArchive(it, progress) }
            }
        }
    }

    private suspend fun installCaptionModel(read: suspend ((Long) -> Unit) -> Unit) {
        val context = currentCoroutineContext()
        val total = CaptionWeights.entries.sumOf(CaptionWeights::size)
        mutable.update { it.copy(localCaptionProgress = 0) }
        var last = 0
        try {
            read { bytes ->
                context.ensureActive()
                val percent = (bytes * 100 / total).toInt()
                if (percent != last) {
                    last = percent
                    mutable.update { it.copy(localCaptionProgress = percent) }
                }
            }
            notice("本地图片描述模型已就绪")
        } finally {
            mutable.update { it.copy(localCaptionInstalled = localCaptioner.installed(), localCaptionProgress = null) }
        }
    }

    fun removeCaptionModel() = work {
        withContext(Dispatchers.IO) { localCaptioner.remove() }
        mutable.update { it.copy(localCaptionInstalled = false) }
    }

    fun importEmbeddingModel(model: LocalEmbeddingModel, uri: Uri) = utilityWork("正在导入本地嵌入模型…") {
        installEmbeddingModel(model) { install ->
            withContext(Dispatchers.IO) {
                requireNotNull(resolver.openInputStream(uri)) { "无法打开模型文件" }.use(install)
            }
        }
    }

    private suspend fun installEmbeddingModel(model: LocalEmbeddingModel, read: suspend ((InputStream) -> Unit) -> Unit) {
        val context = currentCoroutineContext()
        mutable.update { it.copy(localEmbeddingProgress = 0) }
        var lastPercent = 0
        try {
            read { input -> localEmbeddings.install(model, input) { received ->
                context.ensureActive()
                val percent = (received * 100 / model.bytes).toInt()
                if (lastPercent != percent) {
                    lastPercent = percent
                    mutable.update { it.copy(localEmbeddingProgress = percent) }
                }
            } }
            notice("本地嵌入模型已就绪")
        } finally {
            refreshInstalledEmbeddingModels()
        }
    }

    fun removeEmbeddingModel(model: LocalEmbeddingModel) = work {
        withContext(Dispatchers.IO) { localEmbeddings.remove(model) }
        refreshInstalledEmbeddingModels()
        notice("已移除本地嵌入模型")
    }

    private suspend fun refreshInstalledEmbeddingModels() {
        val installed = withContext(NonCancellable + Dispatchers.IO) {
            LocalEmbeddingModel.entries.filterTo(mutableSetOf(), localEmbeddings::installed)
        }
        mutable.update { it.copy(installedEmbeddingModels = installed, localEmbeddingProgress = null) }
    }

    fun loadDataBank() = work { refreshDataBank() }
    fun dismissDataBankRequest() = mutable.update { it.copy(openDataBank = false) }

    private suspend fun refreshDataBank() {
        val documents = withContext(Dispatchers.IO) { store.dataDocuments() }
        mutable.update { it.copy(dataDocuments = documents) }
    }

    fun openDataDocument(id: String) = work {
        val document = current.dataDocuments.firstOrNull { it.id == id } ?: return@work
        val text = withContext(Dispatchers.IO) {
            requireNotNull(store.documentText(id, DOCUMENT_EDITOR_LIMIT)) { "找不到指定资料" }
        }
        mutable.update { it.copy(openDocument = DocumentText(document, text, document.size > DOCUMENT_EDITOR_LIMIT)) }
    }

    fun closeDataDocument() = mutable.update { it.copy(openDocument = null) }

    private fun checkDataOwner(owner: DataOwner) {
        when (owner.scope) {
            DataScope.GLOBAL -> Unit
            DataScope.CHARACTER -> require(current.cards.any { it.id == owner.id }) { "角色已不存在" }
            DataScope.CHAT -> require(current.chat?.id == owner.id) { "该对话已关闭" }
        }
    }

    private suspend fun persistDataDocument(document: DataDocument, text: String?) {
        checkDataOwner(document.owner)
        val title = document.title.trim().take(180)
        require(title.isNotBlank()) { "请输入资料名称" }
        withContext(Dispatchers.IO) {
            store.saveDocument(document.copy(title = title, updated = System.currentTimeMillis()), text?.let(::validatedDocumentText))
        }
        refreshDataBank()
    }

    fun saveDataDocument(document: DataDocument, text: String?, done: () -> Unit) = work {
        persistDataDocument(document, text)
        closeDataDocument()
        done()
    }

    fun importDataDocuments(owner: DataOwner, uris: List<Uri>) = utilityWork("正在导入资料…") {
        checkDataOwner(owner)
        val app = getApplication<Application>()
        var imported = 0
        val failures = mutableListOf<String>()
        try {
            withContext(Dispatchers.IO) {
                for (uri in uris) {
                    currentCoroutineContext().ensureActive()
                    val name = app.documentName(uri)
                    try {
                        val bytes = requireNotNull(resolver.openInputStream(uri)) { "无法打开文件" }
                            .use { it.readLimited(MAX_DOCUMENT_INPUT_BYTES) }
                        val document = openDataDocument(name, resolver.getType(uri), bytes, app.cacheDir)
                        store.saveDocument(DataDocument(title = document.title, owner = owner), document.text)
                        imported++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failures += "$name · ${e.localizedMessage.orEmpty()}"
                    }
                }
            }
        } finally {
            withContext(NonCancellable) { refreshDataBank() }
        }
        notice(if (failures.isEmpty()) "已导入 $imported 份资料" else "已导入 $imported 份资料 · ${failures.first()}")
    }

    fun importDataUrl(owner: DataOwner, url: String) = utilityWork("正在提取网页文本…") {
        checkDataOwner(owner)
        val document = importWebDocument(url)
        withContext(Dispatchers.IO) {
            store.saveDocument(DataDocument(title = document.title, owner = owner, source = url.trim().take(2000)), document.text)
        }
        refreshDataBank()
        notice("已导入网页资料")
    }

    fun exportDataDocument(document: DataDocument, uri: Uri) = work {
        withContext(Dispatchers.IO) {
            val text = requireNotNull(store.documentText(document.id)) { "找不到指定资料" }
            write(uri, text.encodeToByteArray())
        }
        notice("资料已导出")
    }

    fun deleteDataDocument(id: String) = work {
        withContext(Dispatchers.IO) { store.deleteDocument(id) }
        refreshDataBank()
        closeDataDocument()
    }

    fun indexDataDocuments(owner: DataOwner) = utilityWork("正在准备资料索引…") {
        checkDataOwner(owner)
        val snapshot = current
        val prefs = snapshot.prefs.vectors
        val source = requireNotNull(snapshot.vectorSource()) { "请先在向量设置配置服务" }
        val documents = withContext(Dispatchers.IO) { store.dataDocuments(owner).filter(DataDocument::enabled) }
        var chunks = 0
        for ((index, document) in documents.withIndex()) {
            val corpus = withContext(Dispatchers.IO) { store.documentText(document.id)?.let { fileCorpus(document, it, prefs) } } ?: continue
            chunks += vectorSearch.index(source, corpus) { done, total ->
                mutable.update { it.copy(utility = "正在建立资料索引 · ${index + 1}/${documents.size} · $done/$total") }
            }
        }
        notice("资料索引已更新 · $chunks 个片段")
    }

    fun clearDataDocumentIndex(id: String) = work {
        val document = current.dataDocuments.firstOrNull { it.id == id } ?: return@work
        withContext(Dispatchers.IO) { store.clearVectors(document.indexScope) }
        notice("资料索引已清除")
    }

    private suspend fun scriptDataBank(command: String, named: Map<String, String>, input: String): String {
        val chat = requireNotNull(current.chat) { "请先打开对话" }
        require(!chat.isTemporary) { "请先将临时聊天导入角色或群组" }
        if (command == "db") {
            mutable.update { it.copy(openDataBank = true) }
            return ""
        }
        val activeCharacter = current.generation?.characterId?.let { id -> current.cards.firstOrNull { it.id == id } } ?: current.regexCard()
        val defaultScope = if (command in setOf("db-add", "db-update", "db-delete")) "chat" else ""
        val source = named["source"]?.trim()?.lowercase(java.util.Locale.ROOT).orEmpty().ifBlank { defaultScope }
        val owner = when (source) {
            "chat" -> DataOwner(DataScope.CHAT, chat.id)
            "global" -> DataOwner(DataScope.GLOBAL)
            "character" -> DataOwner(DataScope.CHARACTER, requireNotNull(activeCharacter?.id) { "找不到当前角色" })
            "", "all" -> null
            else -> error("未知的资料范围 $source")
        }
        if (command == "db-add") {
            val document = DataDocument(title = named["name"]?.trim()?.takeIf(String::isNotBlank) ?: "资料 ${newId().take(8)}",
                owner = owner ?: DataOwner(DataScope.CHAT, chat.id))
            persistDataDocument(document, input)
            return document.address
        }
        val items = withContext(Dispatchers.IO) {
            if (owner != null) store.dataDocuments(owner) else store.dataDocuments().filter {
                it.owner.scope == DataScope.GLOBAL || it.owner == DataOwner(DataScope.CHAT, chat.id) ||
                    (it.owner.scope == DataScope.CHARACTER && it.owner.id == activeCharacter?.id)
            }
        }
        if (command == "db-list") return JSONArray(items.map {
            when (named["field"]) { "name" -> it.title; "size" -> it.size; "created" -> it.updated; else -> it.address }
        }).toString()
        val reference = if (command == "db-update") named["url"] ?: named["name"] else input.trim()
        val document = items.firstOrNull { it.address == reference || it.id == reference }
            ?: items.firstOrNull { it.title == reference || (it.source.isNotEmpty() && it.source == reference) }
            ?: error("找不到指定资料")
        return when (command) {
            "db-get" -> withContext(Dispatchers.IO) { store.documentText(document.id).orEmpty() }
            "db-update" -> {
                persistDataDocument(document, input)
                document.address
            }
            "db-disable", "db-enable" -> {
                persistDataDocument(document.copy(enabled = command == "db-enable"), null)
                ""
            }
            "db-delete" -> {
                withContext(Dispatchers.IO) { store.deleteDocument(document.id) }
                refreshDataBank()
                ""
            }
            else -> error("未知的资料库命令")
        }
    }

    private suspend fun promptSharedWorldMemory(chat: Chat, card: Card, history: List<Message>,
                                               books: List<WorldBook>, groups: List<TavernGroup>, cards: List<Card>): String {
        val world = chat.memoryWorld(card, books)?.takeIf(WorldBook::sharedMemory) ?: return ""
        val groupById = groups.filter { card.id in it.memberIds }.associateBy(TavernGroup::id)
        return withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            val terms = sharedWorldTerms(history)
            val solo = store.sharedMemoryChats(card, world, chat.id)
            val group = groupById.keys.asSequence().flatMap { groupId ->
                context.ensureActive()
                store.sharedMemoryChats(card, world, chat.id, groupId).asSequence()
            }.sortedByDescending(ChatSummary::updated).take(SHARED_WORLD_CHAT_LIMIT)
            val turns = buildList {
                for (source in solo + group) {
                    context.ensureActive()
                    val previous = try {
                        store.chat(source.id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    if (previous != null && previous.memoryWorld(card, books)?.id == world.id) {
                        addAll(previous.sharedWorldTurns(card, groupById[previous.groupId], cards, terms))
                    }
                }
            }
            sharedWorldPrompt(world, card, chat, turns)
        }
    }

    private suspend fun promptVectors(prefs: Preferences, chat: Chat, card: Card, persona: PersonaProfile,
                                      books: List<WorldBook>, history: List<Message>): VectorContext {
        val config = prefs.vectors
        if (!config.enabled || chat.isTemporary) return VectorContext()
        val source = config.source(prefs.providers) ?: return VectorContext().also { notice("请重新配置向量检索提供商") }
        if (source is EmbeddingSource.Local && source.model !in current.installedEmbeddingModels) {
            notice("请先安装所选本地嵌入模型，已使用普通聊天上下文")
            return VectorContext()
        }
        return try {
            withTimeoutOrNull(18_000) {
                val candidates = if (config.worldEnabled) withContext(Dispatchers.Default) {
                    loreCandidates(card, books, persona, chat.options)
                } else emptyList()
                val documents = if (config.files.enabled) withContext(Dispatchers.IO) {
                    store.availableDocuments(card.id, chat.id)
                } else emptyList()
                vectorSearch.retrieve(source, config, chat.id, history, candidates, documents, documentStore::text) { store.documentText(it) }
            } ?: VectorContext().also { notice("向量检索超时，已使用普通聊天上下文") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notice("向量检索失败，已使用普通聊天上下文 · ${e.localizedMessage.orEmpty()}")
            VectorContext()
        }
    }

    private suspend fun vectorCorpora(snapshot: TavernState, chat: Chat): List<VectorCorpus> = withContext(Dispatchers.Default) {
        val config = snapshot.prefs.vectors
        buildList {
            if (config.chatEnabled) add(chatCorpus(chat.id, chat.messages, config, query = false))
            if (config.worldEnabled) {
                val cards = snapshot.group?.members(snapshot.cards) ?: listOfNotNull(snapshot.card)
                val lore = cards.flatMap { loreCandidates(it, snapshot.books, snapshot.persona, chat.options) }
                    .distinctBy(LoreCandidate::key)
                add(worldCorpus(lore, config))
            }
        }
    }

    private fun indexedAttachments(chat: Chat, config: VectorPreferences): List<ChatDocument> = if (config.messageFiles.enabled) {
        chat.messages.flatMap(Message::documents).distinctBy(ChatDocument::id)
            .filter { it.bytes > config.messageFiles.thresholdKb * 1024 }
    } else emptyList()

    private suspend fun documentVectorCorpus(document: ChatDocument, config: VectorPreferences): VectorCorpus? = withContext(Dispatchers.IO) {
        documentStore.text(document)?.let { attachmentCorpus(document, it, config) }
    }

    fun indexVectors() = utilityWork("正在准备向量索引…") {
        val snapshot = current
        val chat = snapshot.chat ?: return@utilityWork
        val source = requireNotNull(snapshot.vectorSource()) { "请先配置向量检索" }
        var completed = vectorCorpora(snapshot, chat).sumOf { corpus ->
            val label = if (corpus.scope == WORLD_VECTOR_SCOPE) "世界书" else "当前聊天"
            vectorSearch.index(source, corpus) { done, total -> mutable.update { it.copy(utility = "正在建立${label}索引 · $done/$total") } }
        }
        val config = snapshot.prefs.vectors
        for (document in indexedAttachments(chat, config)) {
            val corpus = documentVectorCorpus(document, config) ?: continue
            completed += vectorSearch.index(source, corpus) { done, total ->
                mutable.update { it.copy(utility = "正在建立文档索引 · ${document.name} · $done/$total") }
            }
        }
        notice("向量索引已更新 · $completed 个内容片段")
    }

    fun showVectorCounts() = utilityWork("正在读取向量索引…") {
        val snapshot = current
        val chat = snapshot.chat ?: return@utilityWork
        val source = requireNotNull(snapshot.vectorSource()) { "请先配置向量检索" }
        val summary = vectorCorpora(snapshot, chat).map { corpus ->
            val (indexed, total) = vectorSearch.counts(source, corpus)
            "${if (corpus.scope == WORLD_VECTOR_SCOPE) "世界书" else "聊天"} $indexed/$total"
        }.toMutableList()
        val config = snapshot.prefs.vectors
        if (config.messageFiles.enabled) {
            var indexed = 0
            var total = 0
            for (document in indexedAttachments(chat, config)) {
                val corpus = documentVectorCorpus(document, config) ?: continue
                val (ready, count) = vectorSearch.counts(source, corpus)
                indexed += ready
                total += count
            }
            summary += "消息文档 $indexed/$total"
        }
        notice(summary.joinToString(" · ").ifBlank { "当前没有启用的向量索引" })
    }

    fun clearVectorIndex(world: Boolean) = work {
        val scope = if (world) WORLD_VECTOR_SCOPE else current.chat?.id?.let(::chatVectorScope) ?: return@work
        withContext(Dispatchers.IO) { store.clearVectors(scope) }
        notice(if (world) "世界书向量索引已清除" else "当前聊天的向量索引已清除")
    }

    fun clearChatAttachmentIndex() = work {
        val chat = current.chat ?: return@work
        val scopes = chat.messages.flatMap(Message::documents).map(ChatDocument::vectorScope).distinct()
        withContext(Dispatchers.IO) { scopes.forEach(store::clearVectors) }
        notice("消息文档索引已清除")
    }

    fun saveTranslation(options: TranslationOptions, done: () -> Unit) = work {
        val chat = current.chat ?: return@work
        require(validTranslationLanguage(options.target) && validTranslationLanguage(options.requestLanguage)) {
            "请填写有效的语言标签，例如 zh-CN 或 en"
        }
        val messages = if (options.target.equals(chat.options.translation.target, true)) chat.messages
            else chat.messages.map { it.copy(translation = null) }
        persist(chat.copy(messages = messages, options = chat.options.copy(translation = options)))
        done()
    }

    fun saveTranslationServices(settings: TranslationServices, done: () -> Unit) = work {
        val validated = settings.validated()
        if (settings.source == TranslationSource.MODEL && settings.model != null) {
            current.prefs.utilityModel(current.chat, settings.model, "翻译")
        }
        persistPreferences(current.prefs.copy(translationServices = validated))
        done()
    }

    fun testTranslationService(settings: TranslationServices) = utilityWork("正在测试翻译服务…") {
        val chat = current.chat
        val prefs = current.prefs.copy(translationServices = settings)
        val options = TranslationOptions()
        val result = translationService.translate(prefs, chat, options, "The train will arrive on time.", options.target)
        notice("翻译服务已返回译文 · $result")
    }

    fun translateDraft(text: String, done: (String) -> Unit) = utilityWork("正在翻译输入…") {
        val chat = current.chat ?: return@utilityWork
        val options = chat.options.translation
        done(translationService.translate(current.prefs, chat, options, text, options.target))
    }

    fun toggleTranslation(index: Int) = utilityWork("正在翻译消息…") {
        val chat = current.chat ?: return@utilityWork
        val message = chat.messages.getOrNull(index) ?: return@utilityWork
        val translated = when {
            message.translation != null -> null
            message.wireText != null -> message.wireText
            else -> translationService.translate(current.prefs, chat, chat.options.translation, message.text, chat.options.translation.target)
        }
        val messages = chat.messages.toMutableList().apply { this[index] = message.copy(translation = translated) }
        persist(chat.copy(messages = messages))
    }

    fun translateChat(options: TranslationOptions) = utilityWork("正在翻译聊天…") {
        val chat = current.chat ?: return@utilityWork
        val prefs = current.prefs
        val sameLanguage = options.target.equals(chat.options.translation.target, true)
        val messages = chat.messages.map { if (sameLanguage) it else it.copy(translation = null) }.toMutableList()
        val indices = messages.indices.filter { index ->
            val message = messages[index]
            !message.excludedFromContext && message.text.isNotBlank() && message.translation == null
        }
        var completed = 0
        try {
            for (index in indices) {
                mutable.update { it.copy(utility = "正在翻译聊天 · ${completed + 1}/${indices.size}") }
                val message = messages[index]
                val text = translationService.translate(prefs, chat, options, message.text, options.target)
                messages[index] = message.copy(translation = text.takeIf { it != message.text })
                completed++
            }
        } finally {
            if (completed > 0 || indices.isEmpty()) withContext(NonCancellable) {
                persist(chat.copy(messages = messages, options = chat.options.copy(translation = options)))
            }
        }
    }

    fun clearTranslations() = work {
        val chat = current.chat ?: return@work
        persist(chat.copy(messages = chat.messages.map {
            it.copy(translation = null)
        }))
    }

    private suspend fun scriptTranslate(text: String, target: String?): String {
        val chat = requireNotNull(current.chat) { "请先打开对话" }
        val options = chat.options.translation
        return translationService.translate(current.prefs, chat, options, text, target ?: options.target)
    }

    private suspend fun automaticTranslation(prefs: Preferences, chat: Chat, text: String, language: String): String? {
        if (text.isBlank()) return null
        return try {
            translationService.translate(prefs, chat, chat.options.translation, text, language).takeUnless { it == text }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notice("自动翻译失败，已保留原文 · ${e.localizedMessage.orEmpty()}")
            null
        }
    }

    fun saveMemory(options: MemoryOptions, text: String, done: () -> Unit) = work {
        val chat = current.chat ?: return@work
        val previous = chat.messages.currentSummary()
        val value = text.trim()
        val messages = when {
            value == previous?.summary?.text || (value.isBlank() && previous == null) -> chat.messages
            value.isEmpty() && previous != null -> chat.messages.withSummary(previous.index, "")
            else -> {
                require(chat.messages.isNotEmpty()) { "请先发送一条消息" }
                chat.messages.withSummary(chat.messages.lastIndex, value)
            }
        }
        persist(chat.copy(messages = messages, options = chat.options.copy(memory = options)))
        done()
    }

    fun restoreMemory() = work {
        val chat = current.chat ?: return@work
        val previous = chat.messages.currentSummary() ?: return@work
        persist(chat.copy(messages = chat.messages.withSummary(previous.index, "")))
    }

    private suspend fun refreshMemory(chat: Chat, force: Boolean): String? {
        val batch = withContext(Dispatchers.Default) { chat.summaryBatch(force) } ?: return null
        val options = chat.options.memory
        val result = ModelService.utility(current.prefs.utilityModel(chat, options.model, "摘要"), batch.messages, options.maxTokens)
        val messages = withContext(Dispatchers.Default) { chat.messages.withSummary(batch.lastIndex, result) }
        persist(chat.copy(messages = messages))
        return result
    }

    fun summarizeChat(options: MemoryOptions) = utilityWork("正在摘要聊天…") {
        val chat = current.chat ?: return@utilityWork
        if (refreshMemory(chat.copy(options = chat.options.copy(memory = options)), true) == null) notice("没有尚未摘要的新消息")
    }

    private suspend fun summarizeAutomatically(chat: Chat) {
        if (!chat.options.memory.enabled || chat.options.memory.paused || chat.options.memory.interval == 0) return
        try {
            refreshMemory(chat, false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notice("摘要更新失败 · ${e.localizedMessage.orEmpty()}")
        }
    }

    private suspend fun scriptSummarize(text: String, instruction: String?): String {
        val chat = requireNotNull(current.chat) { "请先打开对话" }
        val options = chat.options.memory
        if (text.isNotBlank()) return ModelService.utility(current.prefs.utilityModel(chat, options.model, "摘要"),
            utilityMessages(instruction ?: options.instruction, text), options.maxTokens)
        return refreshMemory(chat, true) ?: chat.messages.currentSummary()?.summary?.text.orEmpty()
    }

    fun send(text: String, interpretCommands: Boolean = true): Boolean {
        val chat = current.chat ?: return false
        val attachments = current.draftImages[chat.id].orEmpty()
        val documents = current.draftDocuments[chat.id].orEmpty()
        if ((text.isBlank() && attachments.isEmpty() && documents.isEmpty()) || current.busy || current.generation != null || current.scriptRunning) return false
        if (interpretCommands && text.trimStart().startsWith('/')) return runScript(text, text)
        val functionsDraw = current.prefs.toolCalling && current.prefs.connectionFor(chat) != null &&
            drawingTool(current.prefs, chat, drawingCard(chat)) != null
        if (interpretCommands && current.prefs.drawing.interactive && !functionsDraw) {
            val subject = interactiveDrawing(text)
            val request = current.prefs.drawing.defaultProfile(current.prefs.providers)?.resolve(current.prefs.providers, "")
            if (subject != null && request != null) return launchDrawing(chat.id) { active ->
                sendInteractiveDrawing(active, text, subject, request, attachments, documents)
            }
        }
        stopAuto()
        val persona = current.persona
        val group = current.group
        val manual = group?.order == ReplyOrder.MANUAL
        if (!manual && !readyToGenerate()) return false
        val cards = current.cards
        val card = current.card
        if (!manual && (group?.participants(cards)?.isEmpty() ?: (card == null))) {
            notice("群组没有启用的发言角色")
            return false
        }
        val rules = current.textRules(chat)
        var accepted = false
        val submit: suspend () -> Unit = {
            val content = withContext(Dispatchers.Default) {
                val input = text.trim()
                val content = if (interpretCommands && input.startsWith("\\/")) input.drop(1) else input
                rules.transform(content, RegexSite.INPUT, RegexStage.STORAGE)
            }
            val translation = chat.options.translation
            val wire = if (translation.mode.input) automaticTranslation(current.prefs, chat, content, translation.requestLanguage) else null
            val captions = prepareImageCaptions(chat.id, attachments)
            val withUser = chat.copy(updated = System.currentTimeMillis(), messages =
                chat.messages + Message("user", persona.name, content, personaId = persona.id, images = attachments,
                    wireText = wire, documents = documents, imageCaptions = captions))
            if (manual) {
                persist(withUser)
                accepted = true
                clearDraftAttachments(chat.id)
                narrate(withUser.messages.last(), automatic = true)
                quickEvent(QuickEvent.USER)
                current.chat?.let { summarizeAutomatically(it) }
            }
            else {
                val speakers = group?.speakers(cards, withUser.messages) ?: listOfNotNull(card)
                accepted = generate(withUser, speakers, addUser = true) != null
            }
        }
        val autoCaption = current.prefs.captions.automatic &&
            attachments.any { it.id !in current.draftImageCaptions[chat.id].orEmpty() }
        val preparationLabel = when {
            autoCaption -> "正在生成图片描述…"
            chat.options.translation.mode.input && text.isNotBlank() -> "正在翻译输入…"
            else -> null
        }
        if (preparationLabel != null) return utilityWork(preparationLabel) {
            try {
                submit()
            } finally {
                if (!accepted) mutable.update { it.copy(suggestedDraft = DraftSuggestion(chat.id, text)) }
            }
        }
        work(action = submit)
        return true
    }

    private suspend fun sendInteractiveDrawing(chat: Chat, text: String, subject: String, request: ImageRequest,
                                               attachments: List<ChatImage>, documents: List<ChatDocument>) {
        val persona = current.persona
        val rules = current.textRules(chat)
        val content = withContext(Dispatchers.Default) { rules.transform(text.trim(), RegexSite.INPUT, RegexStage.STORAGE) }
        val translation = chat.options.translation
        val wire = if (translation.mode.input) automaticTranslation(current.prefs, chat, content, translation.requestLanguage) else null
        val message = Message("user", persona.name, content, personaId = persona.id, images = attachments, documents = documents, wireText = wire,
            imageCaptions = prepareImageCaptions(chat.id, attachments))
        val withUser = chat.copy(messages = chat.messages + message, updated = System.currentTimeMillis())
        persist(withUser)
        clearDraftAttachments(chat.id)
        narrate(message, automatic = true)
        val mode = DrawingMode.interactive(subject)
        val prompt = drawingPrompt(withUser, mode, subject.takeIf { mode == DrawingMode.FREE }.orEmpty(), current.prefs.drawing.extendFree)
        val references = if (request.protocol.supportsReferences) attachments else emptyList()
        renderDrawing(withUser, prepareDrawing(request.copy(prompt = prompt, references = references), withUser, mode, null),
            background = mode == DrawingMode.BACKGROUND)
        quickEvent(QuickEvent.USER)
    }

    fun regenerate() {
        val chat = current.chat ?: return
        val group = current.group
        if (!readyToGenerate() || (chat.messages.isEmpty() && group == null)) return
        val last = chat.messages.lastOrNull()
        val replacedMessages = when {
            last?.role != "assistant" -> 0
            group == null -> if (current.replyingCharacter(last) == null) 0 else 1
            else -> chat.messages.asReversed().takeWhile {
                it.groupTurnId == last.groupTurnId && (it.role == "assistant" || last.groupTurnId != null && it.role == "system")
            }.size
        }
        val speakers = if (group == null) listOfNotNull(current.card)
        else group.nextTurn(current.cards, chat.messages.dropLast(replacedMessages))
        if (speakers.isEmpty()) {
            notice("请选择发言角色")
            return
        }
        val action = if (group == null) ReplyAction.REGENERATE else ReplyAction.NORMAL
        generate(chat, speakers, replacedMessages = replacedMessages, action = action)
    }

    fun continueReply() = generateForLastReply(ReplyAction.CONTINUE)

    fun nextAlternative() = generateForLastReply(ReplyAction.SWIPE)

    private fun generateForLastReply(action: ReplyAction) {
        val chat = current.chat ?: return
        val last = chat.messages.lastOrNull()?.takeIf { !it.hidden && it.text.isNotBlank() } ?: return
        val character = current.replyingCharacter(last) ?: return notice("找不到这条回复的发言角色")
        if (!readyToGenerate()) return
        stopAuto()
        generate(chat, listOf(character), replacedMessages = 1, action = action)
    }

    fun impersonate() {
        val chat = current.chat ?: return
        val character = (current.card ?: current.group?.members(current.cards)?.randomOrNull())
            ?: return notice("群组还没有发言角色")
        if (!readyToGenerate()) return
        stopAuto()
        generate(chat, listOf(character), action = ReplyAction.IMPERSONATE)
    }

    fun consumeDraft(suggestion: DraftSuggestion) {
        mutable.update { if (it.suggestedDraft == suggestion) it.copy(suggestedDraft = null) else it }
    }

    fun speakAs(character: Card) {
        val chat = current.chat ?: return
        if (current.group?.memberIds?.contains(character.id) != true || !readyToGenerate()) return
        generate(chat, listOf(character))
    }

    private fun readyToGenerate(): Boolean {
        if (current.generation != null || current.busy || current.scriptRunning) return false
        if (current.prefs.connectionFor(current.chat) != null) return true
        notice("请先配置模型提供商")
        return false
    }

    fun toggleAuto() {
        val chat = current.chat ?: return
        if (current.autoChatId == chat.id) {
            stopAuto()
            return
        }
        val group = current.group ?: return
        if (!readyToGenerate()) return
        if (group.participants(current.cards).isEmpty()) {
            notice("群组没有启用的发言角色")
            return
        }
        mutable.update { it.copy(autoChatId = chat.id) }
        scheduleAuto()
    }

    fun stopAuto() {
        autoTimer?.cancel()
        autoTimer = null
        if (current.autoChatId != null) mutable.update { it.copy(autoChatId = null) }
    }

    fun stop() {
        stopAuto()
        scriptJob?.cancel()
        generator?.cancel()
        imageJob?.cancel()
        utilityJob?.cancel()
    }

    private fun clearDraftAttachments(chatId: String) = mutable.update {
        it.copy(draftImages = it.draftImages - chatId, draftImageCaptions = it.draftImageCaptions - chatId, draftDocuments = it.draftDocuments - chatId)
    }

    private fun scheduleAuto() {
        val chatId = current.autoChatId ?: return
        val seconds = current.group?.autoDelaySeconds ?: return
        autoTimer?.cancel()
        autoTimer = viewModelScope.launch {
            delay(seconds * 1000L)
            autoTimer = null
            val chat = current.chat?.takeIf { it.id == chatId && current.autoChatId == chatId } ?: return@launch
            val group = current.group ?: return@launch stopAuto()
            if (current.generation != null) return@launch
            if (current.busy) return@launch scheduleAuto()
            if (!readyToGenerate()) return@launch stopAuto()
            val speakers = group.nextTurn(current.cards, chat.messages)
            if (speakers.isEmpty()) return@launch stopAuto()
            generate(chat, speakers)
        }
    }

    private fun generate(chat: Chat, speakers: List<Card>, addUser: Boolean = false, replacedMessages: Int = 0,
                         action: ReplyAction = ReplyAction.NORMAL): Job? {
        val prefs = current.prefs
        val connection = prefs.connectionFor(chat) ?: return null
        val persona = current.persona
        val group = current.group
        val cards = current.cards
        val books = current.books
        val groups = current.groups
        val preset = current.presetFor(chat)
        val initialText = chat.messages.lastOrNull()?.text.orEmpty().takeIf { action == ReplyAction.CONTINUE }.orEmpty()
        val generation = Generation(chat.id, text = initialText, replacedMessages = replacedMessages,
            characterId = speakers.first().id,
            speaker = if (action == ReplyAction.IMPERSONATE) persona.name else speakers.first().name, action = action)
        autoTimer?.cancel()
        autoTimer = null
        mutable.update { it.copy(generation = generation) }
        generator = viewModelScope.launch {
            var completed = false
            try {
                val loreCounter = withContext(Dispatchers.Default) { loreTokenizers.counterFor(connection) }
                if (addUser) {
                    withContext(NonCancellable) { persist(chat) }
                    clearDraftAttachments(chat.id)
                    narrate(chat.messages.last(), automatic = true)
                    quickEvent(QuickEvent.USER)
                }
                var ongoing = current.chat?.takeIf { it.id == chat.id } ?: chat
                for ((index, card) in speakers.withIndex()) {
                    if (group != null) quickEvent(QuickEvent.GROUP)
                    quickEvent(QuickEvent.BEFORE)
                    ongoing = current.chat?.takeIf { it.id == chat.id } ?: ongoing
                    var passAction = action
                    var replace = replacedMessages.takeIf { index == 0 } ?: 0
                    var followups = 0
                    var toolAttempted = false
                    while (true) {
                        val continued = ongoing.messages.lastOrNull()?.takeIf { passAction == ReplyAction.CONTINUE }
                        val context = ongoing.messages.dropLast(replace)
                        val removed = ongoing.messages.takeLast(replace)
                        val speaker = if (action == ReplyAction.IMPERSONATE) persona.name else card.name
                        mutable.update { it.copy(generation = generation.copy(text = continued?.text.orEmpty(), speaker = speaker,
                            replacedMessages = replace, characterId = card.id, action = passAction)) }
                        val buffer = StringBuilder()
                        val reasoningBuffer = StringBuilder()
                        val scene = group?.let {
                            GroupPrompt(it, it.promptMembers(cards, card), it.members(cards).map(Card::name), it.participants(cards).map(Card::name))
                        }
                        val promptHistory = if (continued == null) context else ongoing.messages
                        val activePrefs = prefs.copy(scriptVariables = current.prefs.scriptVariables)
                        val vectors = promptVectors(activePrefs, ongoing, card, persona, books, promptHistory)
                        val sharedMemory = if (action == ReplyAction.IMPERSONATE) "" else
                            promptSharedWorldMemory(ongoing, card, promptHistory, books, groups, cards)
                        var toolSucceeded = false
                        fun resetReply() {
                            buffer.clear()
                            reasoningBuffer.clear()
                            mutable.update { state ->
                                val running = state.generation
                                if (running?.id == generation.id) state.copy(generation = running.copy(
                                    text = continued?.text.orEmpty(), reasoning = "")) else state
                            }
                        }
                        val tools = if (followups == 0 && activePrefs.toolCalling &&
                            passAction != ReplyAction.IMPERSONATE && passAction != ReplyAction.CONTINUE) {
                            ReplyTools(
                                reload = { activeTools(current.prefs, current.chat ?: ongoing, card) },
                                progress = { label -> mutable.update { it.copy(toolProgress = label) } },
                                finishStealth = { invoked ->
                                    toolAttempted = true
                                    toolSucceeded = true
                                    invoked.firstOrNull { it.failed }?.let { notice(it.result) }
                                },
                            ) { turn ->
                                toolAttempted = true
                                recordToolTurn(ongoing, turn, card)
                                toolSucceeded = toolSucceeded || turn.invocations.any { !it.failed }
                                resetReply()
                            }
                        } else null
                        var loreBudget: LoreBudgetUsage? = null
                        val result = ModelService.reply(activePrefs, connection, card, promptHistory, books, scene,
                            persona, ongoing.options, passAction.trigger, preset,
                            onPrompt = { messages ->
                                mutable.update { it.copy(lastPrompt = PromptCapture(chat.id, connection.model, messages, loreBudget)) }
                            }, onReasoning = { fragment ->
                                reasoningBuffer.append(fragment)
                                val text = reasoningBuffer.toString()
                                mutable.update { state ->
                                    val running = state.generation
                                    if (running?.id == generation.id) state.copy(generation = running.copy(reasoning = text)) else state
                                }
                            }, onLoreActivated = ::quickWorldEvent, readImage = imageStore::bytes, readDocument = documentStore::text,
                            vectors = vectors, tools = tools, onRetry = ::resetReply, sharedMemory = sharedMemory, loreCounter = loreCounter,
                            onLoreBudget = {
                                loreBudget = it
                                if (it.blocked && activePrefs.loreSettings.overflowAlert) {
                                    notice("世界书额度已达 ${it.limit} Token，部分条目未注入")
                                }
                            }, chatId = ongoing.id, onVariables = { persistMacroVariables(ongoing.id, it) }) { fragment ->
                            buffer.append(fragment)
                            val text = if (continued == null) buffer.toString() else joinContinuation(continued.text, buffer.toString())
                            mutable.update { state ->
                                val running = state.generation
                                if (running?.id == generation.id) state.copy(generation = running.copy(text = text)) else state
                            }
                        }
                        val response = buffer.toString().trimEnd()
                        if (response.isBlank() && toolSucceeded) {
                            ongoing = current.chat?.takeIf { it.id == chat.id } ?: ongoing
                            break
                        }
                        check(response.isNotBlank()) { "模型没有返回可显示的回复" }
                        val rules = prefs.regex.runtime(card, persona.name, preset, scene?.roster.orEmpty())
                        val site = if (action == ReplyAction.IMPERSONATE) RegexSite.INPUT else RegexSite.REPLY
                        val (content, reasoning) = withContext(Dispatchers.Default) {
                            val body = joinContinuation(continued?.text.orEmpty(), if (continued == null) response.trimStart() else response) {
                                rules.transform(it, site, RegexStage.STORAGE)
                            }
                            val details = rules.transform(reasoningBuffer.toString().trim(), RegexSite.REASONING, RegexStage.STORAGE)
                            body to details.takeIf(String::isNotBlank)
                        }
                        if (action == ReplyAction.IMPERSONATE) {
                            mutable.update { it.copy(suggestedDraft = DraftSuggestion(chat.id, content)) }
                            if (result.incomplete != null) {
                                notice("${result.incomplete}，已保留收到的草稿")
                                return@launch
                            }
                            break
                        }
                        val old = ongoing.messages.lastOrNull()?.takeIf { replace == 1 }
                        val reply = when {
                            continued != null -> continued.withText(content).withReasoning(
                                listOfNotNull(continued.reasoning, reasoning).joinToString("\n").takeIf(String::isNotBlank))
                            old != null && (group == null || passAction == ReplyAction.SWIPE) -> old.withAlternative(content, reasoning)
                            else -> Message("assistant", card.name, content,
                                groupTurnId = generation.id.takeIf { group != null }, reasoning = reasoning)
                        }
                        val effects = result.loreEffects.takeIf {
                            it.isNotEmpty() || promptHistory.asReversed().firstNotNullOfOrNull(Message::loreEffects)?.isNotEmpty() == true
                        }
                        val latest = current.chat?.takeIf { it.id == ongoing.id } ?: ongoing
                        val retained = if (latest === ongoing) context else latest.messages.filterNot { message ->
                            removed.any { it === message }
                        }
                        ongoing = latest.copy(messages = retained + reply.copy(characterId = card.id, loreEffects = effects),
                            updated = System.currentTimeMillis())
                        withContext(NonCancellable) {
                            persist(ongoing)
                            ongoing = current.chat?.takeIf { it.id == chat.id } ?: ongoing
                            mutable.update { it.copy(generation = generation.copy(text = "", speaker = card.name,
                                replacedMessages = 0, characterId = card.id, action = passAction)) }
                        }
                        val automation = prefs.replyAutomation
                        val next = if ((automation.autoContinue || automation.autoSwipe) && followups < automation.extraRequests &&
                            draftInProgress != chat.id && result.continuable) {
                            withContext(Dispatchers.Default) {
                                when {
                                    passAction != ReplyAction.CONTINUE && !toolAttempted && result.incomplete == null &&
                                        automation.needsSwipe(content) -> ReplyAction.SWIPE
                                    content != continued?.text &&
                                        automation.needsContinuation(content, response, loreCounter) -> ReplyAction.CONTINUE
                                    else -> null
                                }
                            }
                        } else null
                        if (next != null && draftInProgress != chat.id && current.chat?.id == chat.id) {
                            passAction = next
                            replace = 1
                            followups++
                            continue
                        }
                        if (result.incomplete != null) {
                            notice("${result.incomplete}，已保留收到的正文。可使用「续写最后一条回复」")
                            return@launch
                        }
                        val translation = ongoing.options.translation
                        if (translation.mode.replies) {
                            val translated = automaticTranslation(activePrefs, ongoing, content, translation.target)
                            if (translated != null) {
                                val saved = current.chat ?: ongoing
                                val messages = saved.messages.dropLast(1) + saved.messages.last().copy(translation = translated)
                                persist(saved.copy(messages = messages))
                            }
                        }
                        ongoing = current.chat ?: ongoing
                        narrate(ongoing.messages.last(), automatic = true)
                        quickEvent(QuickEvent.AI)
                        break
                    }
                }
                if (action != ReplyAction.IMPERSONATE) current.chat?.let { summarizeAutomatically(it) }
                completed = true
            } catch (e: CancellationException) {
                if (current.generation?.chatId == chat.id) notice("已停止生成")
                throw e
            } catch (e: Exception) {
                notice(e.localizedMessage ?: "生成失败，请重试")
            } finally {
                mutable.update { it.copy(generation = null) }
                if (completed && current.autoChatId == chat.id) scheduleAuto()
                else if (!completed) stopAuto()
            }
        }
        return generator
    }

    fun listRemote(address: String, handle: String, password: String, basicUser: String, basicPassword: String) = work {
        val remote = TavernServer(address.trim(), handle.trim(), basicUser.trim(), basicPassword)
        val cards = remote.list(password)
        server = remote
        val prefs = current.prefs.copy(server = address.trim(), handle = handle.trim())
        persistPreferences(prefs)
        mutable.update { it.copy(remoteCards = cards, prefs = prefs) }
    }

    fun clearRemote() {
        server = null
        mutable.update { it.copy(remoteCards = null) }
    }

    fun importRemote(card: RemoteCard, withChats: Boolean) = work {
        val remote = requireNotNull(server) { "请重新连接 SillyTavern" }
        val (download, chats) = remote.download(card, withChats)
        val (cards, count, missing) = withContext(Dispatchers.IO) {
            val existing = store.chats(download.card.id).mapTo(mutableSetOf(), ChatSummary::id)
            val uploaded = mutableMapOf<String, ChatDocument?>()
            var failures = 0
            val hydrated = chats.filterNot { it.id in existing }.map { chat ->
                val (result, missing) = hydrateOriginalDocuments(chat) { file, title ->
                    val document = if (uploaded.containsKey(file)) uploaded[file] else {
                        try {
                            documentStore.import(ImportedDocument(title, decodeDocumentText(remote.documentFile(file))))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }.also { uploaded[file] = it }
                    }
                    document?.copy(name = title.take(180))
                }
                failures += missing
                result
            }
            val imported = store.import(download, hydrated)
            Triple(store.cards(), imported, failures)
        }
        mutable.update { it.copy(cards = cards) }
        notice("角色已导入 · 新增 $count 个聊天" + if (missing > 0) " · $missing 份文档无法读取" else "")
    }

    private suspend fun hydrateOriginalDocuments(chat: Chat, destination: ChatDocumentStore = documentStore,
        fetch: suspend (String, String) -> ChatDocument? = { _, _ -> null }): Pair<Chat, Int> {
        var failures = 0
        val messages = chat.messages.map { message ->
            val resolved = mutableMapOf<Int, ChatDocument>()
            for (reference in message.originalChatFiles()) {
                currentCoroutineContext().ensureActive()
                val attachment = try {
                    val inline = reference.inline
                    if (inline != null) destination.import(ImportedDocument(reference.name, inline))
                    else reference.file?.let { fetch(it, reference.name) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (attachment != null) resolved[reference.index] = attachment else failures++
            }
            message.withOriginalDocuments(resolved)
        }
        return chat.copy(messages = messages) to failures
    }

    private fun write(uri: Uri, bytes: ByteArray) {
        requireNotNull(resolver.openOutputStream(uri, "wt")) { "无法写入所选文件" }.use { it.write(bytes) }
    }
}
