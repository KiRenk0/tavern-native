package app.tavern.nativeclient

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.tavern.nativeclient.backup.BackupCoordinator
import app.tavern.nativeclient.chat.ChatGenerationCoordinator
import app.tavern.nativeclient.chat.ChatManagementCoordinator
import app.tavern.nativeclient.library.LibraryCoordinator
import app.tavern.nativeclient.media.MediaCoordinator
import app.tavern.nativeclient.provider.ProviderCoordinator
import app.tavern.nativeclient.script.ScriptCoordinator
import app.tavern.nativeclient.world.WorldMemoryCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import kotlin.coroutines.EmptyCoroutineContext

internal data class Generation(
    val chatId: String, val id: String = newId(), val text: String = "",
    val replacedMessages: Int = 0, val speaker: String = "", val action: ReplyAction = ReplyAction.NORMAL,
    val characterId: String? = null, val reasoning: String = "",
)

internal data class ModelCatalog(val providerId: String, val endpoint: String, val models: List<String>, val contexts: Map<String, Int>)
internal data class PromptCapture(val chatId: String, val model: String, val messages: JSONArray, val loreBudget: LoreBudgetUsage? = null)

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
    val lastSharedMemory: SharedMemoryRecallResult? = null,
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
        prefs.activeProfile(chat)?.presetId?.let { pid ->
            presets.firstOrNull { it.id == pid }?.let { return it }
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

internal class TavernViewModel(application: Application) : AndroidViewModel(application), CoordinatorHost {
    override val app: Application = application
    override val scope: CoroutineScope get() = viewModelScope
    override val store = TavernStore(application)
    override val resolver: ContentResolver = application.contentResolver
    override val savedImages = ChatImageStore(File(application.filesDir, "chat-images"))
    override val savedDocuments = ChatDocumentStore(File(application.filesDir, "chat-documents"))
    override var temporaryMedia: TemporaryChatMedia? = null
    override val imageStore get() = temporaryMedia?.images ?: savedImages
    override val documentStore get() = temporaryMedia?.documents ?: savedDocuments
    override val embedded = EmbeddedLibrary(File(application.noBackupFilesDir, "sillytavern/data/default-user"), savedDocuments, savedImages)
    val canImportEmbedded get() = embedded.exists
    override val imageService = ImageService()
    override val translationService = TranslationService()
    override val localExpressions = LocalExpressions(File(application.noBackupFilesDir, "expression-model")) {
        application.assets.open("expression-model/$it")
    }
    override val localEmbeddings = LocalEmbeddings(File(application.noBackupFilesDir, "embedding-models")) {
        application.assets.open("embedding-models/$it")
    }
    override val localCaptioner = LocalCaptioner(File(application.noBackupFilesDir, "caption-model")) {
        application.assets.open("caption-model/vocab.txt")
    }
    override val loreTokenizers = LoreTokenizers(application.assets::open)
    override val embeddingService = EmbeddingService(local = localEmbeddings)
    override val vectorSearch by lazy { VectorSearch(store, embeddingService) }
    override val mutable = MutableStateFlow(TavernState(busy = false))
    val state = mutable.asStateFlow()
    override val current get() = mutable.value
    @Volatile override var draftInProgress: String? = null
    private var utilityJob: Job? = null

    override val media = MediaCoordinator(this)
    override val provider = ProviderCoordinator(this)
    override val library = LibraryCoordinator(this)
    override val world = WorldMemoryCoordinator(this)
    override val script = ScriptCoordinator(this)
    override val chats = ChatManagementCoordinator(this)
    override val generation = ChatGenerationCoordinator(this)
    override val backup = BackupCoordinator(this)

    init {
        viewModelScope.launch {
            state.map { it.chat?.id }.distinctUntilChanged().collect { id ->
                val reading = media.activeNarrationChatId()
                if (reading != null && reading != id) media.stopNarration()
            }
        }
        work {
            val loaded = withContext(Dispatchers.IO) {
                TemporaryChatMedia.cleanPreviousProcess(application.cacheDir)
                val prefs = store.prefs()
                val last = store.lastChatId().takeIf { prefs.welcome.autoOpen }?.let { id ->
                    runCatching { store.chat(id) }.getOrNull()
                }
                TavernState(
                    cards = store.cards(), prefs = prefs, groups = store.groups(), books = store.books(),
                    cardId = last?.cardId, groupId = last?.groupId, chat = last,
                    history = last?.let { store.chats(it.cardId, it.groupId) }.orEmpty(),
                    recentChats = store.recentChats(prefs.welcome.pinnedIds),
                    presets = store.promptPresets(), comfyWorkflows = builtInComfyWorkflows + store.comfyWorkflows(),
                    localExpressionInstalled = localExpressions.installed(),
                    installedEmbeddingModels = LocalEmbeddingModel.entries.filterTo(mutableSetOf(), localEmbeddings::installed),
                    localCaptionInstalled = localCaptioner.installed(), busy = true,
                )
            }
            mutable.update { loaded }
            script.quickEvent(QuickEvent.STARTUP)
            if (current.chat != null) script.quickEvent(QuickEvent.CHAT)
        }
    }

    override fun onCleared() {
        media.close()
        val tempMedia = temporaryMedia
        Dispatchers.IO.dispatch(EmptyCoroutineContext) {
            tempMedia?.close()
            localExpressions.close()
            localEmbeddings.close()
        }
        super.onCleared()
    }

    override fun notice(text: String?) = mutable.update { it.copy(notice = text) }
    fun clearModels() = provider.clearModels()
    fun portrait(card: Card) = store.portraitFile(card.id)
    fun personaAvatar(persona: PersonaProfile) = persona.avatar?.let(store::personaAvatar)
    fun imageFile(image: ChatImage) = imageStore.file(image)

    override fun work(completed: () -> Unit, action: suspend () -> Unit) {
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

    override fun utilityWork(label: String, action: suspend () -> Unit): Boolean {
        if (current.busy || current.generation != null || current.scriptRunning) return false
        generation.stopAuto()
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

    override fun utilityConnection(chat: Chat): ModelConnection =
        requireNotNull(current.prefs.connectionFor(chat)) { "请先配置对话模型" }

    override fun cancelUtility() {
        utilityJob?.cancel()
    }

    override fun writeUri(uri: Uri, bytes: ByteArray) {
        requireNotNull(resolver.openOutputStream(uri, "wt")) { "无法写入所选文件" }.use { it.write(bytes) }
    }

    override suspend fun persist(chat: Chat, drawing: GalleryImage?, scriptGlobals: Map<String, String>?) {
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

    override suspend fun persistPreferences(prefs: Preferences) {
        withContext(Dispatchers.IO) { store.save(prefs) }
        mutable.update { it.copy(prefs = prefs) }
    }

    override suspend fun persistMacroVariables(chatId: String?, changes: MacroVariableChanges) {
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

    override suspend fun refreshRecentChats() {
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

    override suspend fun refreshLibraryUsage() {
        try {
            val usage = withContext(Dispatchers.IO) { store.libraryUsage() }
            mutable.update { it.copy(libraryUsage = usage) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            notice("无法读取角色库的聊天统计")
        }
    }

    override suspend fun refreshDataBank() {
        val documents = withContext(Dispatchers.IO) { store.dataDocuments() }
        mutable.update { it.copy(dataDocuments = documents) }
    }

    override suspend fun refreshInstalledEmbeddingModels() {
        val installed = withContext(NonCancellable + Dispatchers.IO) {
            LocalEmbeddingModel.entries.filterTo(mutableSetOf(), localEmbeddings::installed)
        }
        mutable.update { it.copy(installedEmbeddingModels = installed, localEmbeddingProgress = null) }
    }

    override fun clearDraftAttachments(chatId: String) = mutable.update {
        it.copy(draftImages = it.draftImages - chatId, draftImageCaptions = it.draftImageCaptions - chatId, draftDocuments = it.draftDocuments - chatId)
    }

    override fun discardTemporaryChat() {
        val media = temporaryMedia ?: return
        val id = current.chat?.takeIf(Chat::isTemporary)?.id
        temporaryMedia = null
        viewModelScope.launch(Dispatchers.IO) { media.close() }
        if (id != null) mutable.update {
            it.copy(
                draftImages = it.draftImages - id,
                draftImageCaptions = it.draftImageCaptions - id,
                draftDocuments = it.draftDocuments - id,
                suggestedDraft = it.suggestedDraft?.takeUnless { draft -> draft.chatId == id },
                lastPrompt = it.lastPrompt?.takeUnless { prompt -> prompt.chatId == id },
                manualExpressions = it.manualExpressions.filterValues { expression -> expression.chatId != id },
                messageSelection = it.messageSelection?.takeUnless { request -> request.chatId == id },
            )
        }
    }

    override fun showChat(chat: Chat, history: List<ChatSummary>, resetPersona: Boolean) {
        if (!chat.isTemporary) discardTemporaryChat()
        mutable.update {
            it.copy(
                cardId = chat.cardId.takeUnless { chat.isTemporary },
                groupId = chat.groupId,
                chat = chat,
                history = history,
                temporaryPersonaId = it.temporaryPersonaId.takeUnless { resetPersona },
            )
        }
    }

    // --- Chat documents ---
    fun attachChatDocuments(chatId: String, messageIndex: Int?, uris: List<Uri>) = chats.attachChatDocuments(chatId, messageIndex, uris)
    fun removeDraftDocument(chatId: String, document: ChatDocument) = chats.removeDraftDocument(chatId, document)
    fun removeMessageDocument(chatId: String, index: Int, document: ChatDocument) = chats.removeMessageDocument(chatId, index, document)
    suspend fun readChatDocument(document: ChatDocument, limit: Int): String? = chats.readChatDocument(document, limit)
    fun exportChatDocument(document: ChatDocument, uri: Uri) = chats.exportChatDocument(document, uri)

    // --- Media: Voice, Images, Captions, Drawing, Expressions ---
    fun voiceForeground(visible: Boolean) = media.voiceForeground(visible)
    fun saveVoice(options: VoicePreferences, done: () -> Unit) = media.saveVoice(options, done)
    suspend fun systemVoices(): List<DeviceVoice> = media.systemVoices()
    fun previewVoice(options: VoicePreferences, voice: String, line: String) = media.previewVoice(options, voice, line)
    fun stopNarration() = media.stopNarration()
    fun narrate(message: Message, automatic: Boolean = false) = media.narrate(message, automatic)
    fun attachImages(chatId: String, documents: List<Uri>, ready: ((ChatImage) -> Unit)? = null) = media.attachImages(chatId, documents, ready)
    fun removeDraftImage(chatId: String, image: ChatImage) = media.removeDraftImage(chatId, image)
    fun saveCaptionSettings(settings: CaptionSettings, done: () -> Unit) = media.saveCaptionSettings(settings, done)
    fun generateImageCaption(image: ChatImage, prompt: String, done: (String) -> Unit) = media.generateImageCaption(image, prompt, done)
    fun saveImageCaption(chatId: String, target: CaptionImageTarget, text: String, done: () -> Unit) = media.saveImageCaption(chatId, target, text, done)
    fun downloadCaptionModel() = media.downloadCaptionModel()
    fun importCaptionModel(uri: Uri) = media.importCaptionModel(uri)
    fun removeCaptionModel() = media.removeCaptionModel()
    fun exportImage(uri: Uri, image: ChatImage) = media.exportImage(uri, image)
    fun generateImage(chatId: String, request: ImageRequest, mode: DrawingMode, target: Int?, remember: Boolean): Boolean =
        media.generateImage(chatId, request, mode, target, remember)
    fun generateNanoBanana(chatId: String, preset: NanoBananaPreset, note: String, includeReferences: Boolean): Boolean =
        media.generateNanoBanana(chatId, preset, note, includeReferences)
    fun draftDrawingPrompt(chat: Chat, mode: DrawingMode, text: String, done: (String) -> Unit): Boolean =
        media.draftDrawingPrompt(chat, mode, text, done)
    fun saveDrawingSettings(settings: DrawingSettings, done: () -> Unit) = media.saveDrawingSettings(settings, done)
    fun readComfyWorkflow(uri: Uri, done: (ComfyWorkflow) -> Unit) = media.readComfyWorkflow(uri, done)
    fun saveComfyWorkflow(workflow: ComfyWorkflow, done: (ComfyWorkflow) -> Unit) = media.saveComfyWorkflow(workflow, done)
    fun deleteComfyWorkflow(workflow: ComfyWorkflow, done: () -> Unit) = media.deleteComfyWorkflow(workflow, done)
    fun exportComfyWorkflow(uri: Uri, workflow: ComfyWorkflow) = media.exportComfyWorkflow(uri, workflow)
    fun testComfy(provider: Provider, protocol: ImageProtocol) = media.testComfy(provider, protocol)
    fun loadComfyOptions(provider: Provider, done: (ComfyCatalog) -> Unit) = media.loadComfyOptions(provider, done)
    fun showImageGallery() = media.showImageGallery()
    suspend fun expressionSprites(card: Card): List<ExpressionSprite> = media.expressionSprites(card)
    suspend fun expressionFrame(chatId: String, subject: ExpressionSubject, forScene: Boolean = false): ExpressionFrame? =
        media.expressionFrame(chatId, subject, forScene)
    fun downloadExpressionModel() = media.downloadExpressionModel()
    fun importExpressionModel(uri: Uri) = media.importExpressionModel(uri)
    fun removeExpressionModel() = media.removeExpressionModel()
    fun saveExpressionSettings(settings: ExpressionSettings) = media.saveExpressionSettings(settings)
    fun importExpressionSprites(cardId: String, files: List<Uri>, pack: Boolean, label: String) =
        media.importExpressionSprites(cardId, files, pack, label)
    fun removeExpressionSprite(cardId: String, sprite: ExpressionSprite) = media.removeExpressionSprite(cardId, sprite)
    fun showExpression(cardId: String, sprite: ExpressionSprite, done: () -> Unit = {}) = media.showExpression(cardId, sprite, done)
    fun closeImageGallery() = media.closeImageGallery()
    fun clearDrawingBackground(chatId: String) = media.clearDrawingBackground(chatId)
    fun setDrawingBackground(chatId: String, image: ChatImage) = media.setDrawingBackground(chatId, image)
    fun importChatBackground(chatId: String, uri: Uri) = media.importChatBackground(chatId, uri)
    fun previousDrawing(chatId: String, index: Int) = media.previousDrawing(chatId, index)
    fun nextDrawing(chatId: String, index: Int): Boolean = media.nextDrawing(chatId, index)

    // --- Providers, Appearance, Presets, Profiles & Regex ---
    fun savePrefs(prefs: Preferences) = provider.savePrefs(prefs)
    fun setVisualNovel(enabled: Boolean) = provider.setVisualNovel(enabled)
    fun setChatAppearance(appearance: ChatAppearance) = provider.setChatAppearance(appearance)
    fun tokenCounterFor(connection: ModelConnection?): TextTokenCounter = provider.tokenCounterFor(connection)
    fun setWelcomeAssistant(card: Card?) = provider.setWelcomeAssistant(card)
    fun setAutoOpenLastChat(enabled: Boolean) = provider.setAutoOpenLastChat(enabled)
    fun setReplyAutomation(options: ReplyAutomation, done: () -> Unit) = provider.setReplyAutomation(options, done)
    fun reportDraft(chatId: String, present: Boolean) = provider.reportDraft(chatId, present)
    fun setRecentChats(visible: Boolean, count: Int) = provider.setRecentChats(visible, count)
    fun toggleRecentPin(id: String) = provider.toggleRecentPin(id)
    fun setDefaultAuthorNote(note: AuthorNote, done: () -> Unit) = provider.setDefaultAuthorNote(note, done)
    fun saveLoreSettings(settings: LoreSettings, done: () -> Unit) = provider.saveLoreSettings(settings, done)
    fun setChatAuthorNote(note: AuthorNote, done: () -> Unit) = provider.setChatAuthorNote(note, done)
    fun setPromptOverrides(scenario: String, examples: String, system: String, done: () -> Unit) =
        provider.setPromptOverrides(scenario, examples, system, done)
    fun linkChatBook(book: WorldBook?, done: () -> Unit) = provider.linkChatBook(book, done)
    fun switchPromptPreset(id: String?, done: () -> Unit) = provider.switchPromptPreset(id, done)
    fun setDefaultPromptPreset(id: String?) = provider.setDefaultPromptPreset(id)
    fun savePromptPreset(preset: PromptPreset, done: () -> Unit) = provider.savePromptPreset(preset, done)
    fun deletePromptPreset(preset: PromptPreset, done: () -> Unit) = provider.deletePromptPreset(preset, done)
    fun importPromptPreset(uri: Uri, displayName: String) = provider.importPromptPreset(uri, displayName)
    fun exportPromptPreset(uri: Uri, preset: PromptPreset) = provider.exportPromptPreset(uri, preset)
    fun allowRegex(target: RegexTarget, enabled: Boolean) = provider.allowRegex(target, enabled)
    fun saveRegexRules(target: RegexTarget, rules: List<RegexRule>, done: () -> Unit = {}) = provider.saveRegexRules(target, rules, done)
    fun importRegexRules(uri: Uri, target: RegexTarget) = provider.importRegexRules(uri, target)
    fun exportRegexRules(uri: Uri, rules: List<RegexRule>) = provider.exportRegexRules(uri, rules)
    fun saveProvider(draft: Provider, done: () -> Unit) = provider.saveProvider(draft, done)
    fun setDefaultProvider(provider: Provider) = this.provider.setDefaultProvider(provider)
    fun deleteProvider(provider: Provider, done: () -> Unit) = this.provider.deleteProvider(provider, done)
    fun loadModels(provider: Provider, persistCatalog: Boolean = false) = this.provider.loadModels(provider, persistCatalog)
    fun switchModel(selection: ModelSelection?) = provider.switchModel(selection)
    fun saveConnectionProfile(draft: ConnectionProfile, done: () -> Unit) = provider.saveConnectionProfile(draft, done)
    fun deleteConnectionProfile(profileId: String, done: () -> Unit) = provider.deleteConnectionProfile(profileId, done)
    fun setActiveProfile(profileId: String?) = provider.setActiveProfile(profileId)
    fun setChatConnectionProfile(profileId: String?) = provider.setChatConnectionProfile(profileId)

    // --- Quick replies & STscript ---
    fun saveQuickReplySet(set: QuickReplySet, done: () -> Unit = {}) = script.saveQuickReplySet(set, done)
    fun removeQuickReplySet(set: QuickReplySet, done: () -> Unit) = script.removeQuickReplySet(set, done)
    fun importQuickReplySet(uri: Uri) = script.importQuickReplySet(uri)
    fun exportQuickReplySet(uri: Uri, set: QuickReplySet) = script.exportQuickReplySet(uri, set)
    fun enableQuickReplies(enabled: Boolean) = script.enableQuickReplies(enabled)
    fun activateQuickReplySet(id: String, enabled: Boolean, forChat: Boolean = false) = script.activateQuickReplySet(id, enabled, forChat)
    fun toggleMessageContext(chat: Chat, index: Int) = chats.toggleMessageContext(chat, index)
    fun dismissMessageSelection() = chats.dismissMessageSelection()
    fun runScript(source: String, input: String = ""): Boolean = script.runScript(source, input)
    fun runQuickReply(set: QuickReplySet, item: QuickReply, input: String): Boolean = script.runQuickReply(set, item, input)
    fun completeScriptDialog(id: String, response: String?) = script.completeScriptDialog(id, response)

    // --- Library: Personas, Cards, Tags & Groups ---
    fun savePersona(profile: PersonaProfile, photo: Uri?, done: () -> Unit) = library.savePersona(profile, photo, done)
    fun importPersonas(uri: Uri, done: () -> Unit = {}) = library.importPersonas(uri, done)
    fun personaFromCard(card: Card) = library.personaFromCard(card)
    fun setDefaultPersona(persona: PersonaProfile) = library.setDefaultPersona(persona)
    fun removePersona(persona: PersonaProfile) = library.removePersona(persona)
    fun usePersonaTemporarily(id: String) = library.usePersonaTemporarily(id)
    fun pinPersona(id: String?) = library.pinPersona(id)
    fun bindSubjectPersona(id: String?) = library.bindSubjectPersona(id)
    fun saveCard(card: Card, done: () -> Unit) = library.saveCard(card, done)
    fun sortLibrary(sort: LibrarySort) = library.sortLibrary(sort)
    fun showLibraryFolders(show: Boolean) = library.showLibraryFolders(show)
    fun toggleLibraryFavorite(id: String) = library.toggleLibraryFavorite(id)
    fun saveLibraryTag(tag: LibraryTag, done: () -> Unit) = library.saveLibraryTag(tag, done)
    fun moveLibraryTag(id: String, direction: Int) = library.moveLibraryTag(id, direction)
    fun removeLibraryTag(id: String) = library.removeLibraryTag(id)
    fun assignLibraryTags(owners: Set<String>, changes: Map<String, Boolean>, newName: String, done: () -> Unit) =
        library.assignLibraryTags(owners, changes, newName, done)
    fun exportLibraryTags(uri: Uri) = library.exportLibraryTags(uri)
    fun importLibraryTags(uri: Uri) = library.importLibraryTags(uri)
    fun addExample() = library.addExample()
    fun importCard(uri: Uri) = library.importCard(uri)
    fun importCards(uris: List<Uri>) = library.importCards(uris)
    fun importExternalContent(addresses: String): Boolean = library.importExternalContent(addresses)
    fun updateCardFromLink(card: Card, address: String): Boolean = library.updateCardFromLink(card, address)
    fun updateCardFromFile(card: Card, uri: Uri) = library.updateCardFromFile(card, uri)
    fun duplicateCard(card: Card) = library.duplicateCard(card)
    fun deleteCard(card: Card) = library.deleteCard(card)
    fun exportCard(uri: Uri, card: Card) = library.exportCard(uri, card)
    fun openCard(card: Card) = library.openCard(card)
    fun closeChat() = chats.closeChat()
    fun closeSubject() = library.closeSubject()
    fun saveGroup(group: TavernGroup, done: () -> Unit) = library.saveGroup(group, done)
    fun openGroup(group: TavernGroup) = library.openGroup(group)
    fun deleteGroup(group: TavernGroup) = library.deleteGroup(group)
    fun newGroupChat(group: TavernGroup) = library.newGroupChat(group)

    // --- Backup & Remote ---
    fun importEmbedded() = backup.importEmbedded()
    fun importOriginalBackup(uri: Uri) = backup.importOriginalBackup(uri)
    fun exportOriginalBackup(uri: Uri) = backup.exportOriginalBackup(uri)
    fun listRemote(address: String, handle: String, password: String, basicUser: String, basicPassword: String) =
        backup.listRemote(address, handle, password, basicUser, basicPassword)
    fun clearRemote() = backup.clearRemote()
    fun importRemote(card: RemoteCard, withChats: Boolean) = backup.importRemote(card, withChats)

    // --- World Books, Shared Memory 2.0, Vectors & Data Bank ---
    fun saveBook(book: WorldBook, done: () -> Unit) = world.saveBook(book, done)
    fun deleteBook(book: WorldBook) = world.deleteBook(book)
    fun importBook(uri: Uri, displayName: String) = world.importBook(uri, displayName)
    fun exportBook(uri: Uri, book: WorldBook) = world.exportBook(uri, book)
    fun saveVectorPreferences(options: VectorPreferences, done: () -> Unit) = world.saveVectorPreferences(options, done)
    fun testVectorPreferences(options: VectorPreferences) = world.testVectorPreferences(options)
    fun downloadEmbeddingModel(model: LocalEmbeddingModel) = world.downloadEmbeddingModel(model)
    fun importEmbeddingModel(model: LocalEmbeddingModel, uri: Uri) = world.importEmbeddingModel(model, uri)
    fun removeEmbeddingModel(model: LocalEmbeddingModel) = world.removeEmbeddingModel(model)
    fun loadDataBank() = world.loadDataBank()
    fun dismissDataBankRequest() = world.dismissDataBankRequest()
    fun openDataDocument(id: String) = world.openDataDocument(id)
    fun closeDataDocument() = world.closeDataDocument()
    fun saveDataDocument(document: DataDocument, text: String?, done: () -> Unit) = world.saveDataDocument(document, text, done)
    fun importDataDocuments(owner: DataOwner, uris: List<Uri>) = world.importDataDocuments(owner, uris)
    fun importDataUrl(owner: DataOwner, url: String) = world.importDataUrl(owner, url)
    fun exportDataDocument(document: DataDocument, uri: Uri) = world.exportDataDocument(document, uri)
    fun deleteDataDocument(id: String) = world.deleteDataDocument(id)
    fun indexDataDocuments(owner: DataOwner) = world.indexDataDocuments(owner)
    fun clearDataDocumentIndex(id: String) = world.clearDataDocumentIndex(id)
    fun inspectSharedWorldMemory(world: WorldBook, card: Card, chat: Chat? = current.chat) =
        this.world.inspectSharedWorldMemory(world, card, chat)
    fun pinSharedMemory(world: WorldBook, card: Card, item: RecalledWorldMemory) = this.world.pinSharedMemory(world, card, item)
    fun unpinSharedMemory(world: WorldBook, card: Card, pinId: String) = this.world.unpinSharedMemory(world, card, pinId)
    fun editSharedMemory(world: WorldBook, card: Card, replyKey: String, newText: String) =
        this.world.editSharedMemory(world, card, replyKey, newText)
    fun blockSharedMemory(world: WorldBook, card: Card, replyKey: String, block: Boolean) =
        this.world.blockSharedMemory(world, card, replyKey, block)
    fun promoteSharedMemoryToLore(world: WorldBook, card: Card, memoryText: String, sourceTitle: String) =
        this.world.promoteSharedMemoryToLore(world, card, memoryText, sourceTitle)
    fun indexVectors() = world.indexVectors()
    fun showVectorCounts() = world.showVectorCounts()
    fun clearVectorIndex(world: Boolean) = this.world.clearVectorIndex(world)
    fun clearChatAttachmentIndex() = world.clearChatAttachmentIndex()

    // --- Chat Management & Generation ---
    fun newChat(card: Card, greeting: String) = chats.newChat(card, greeting)
    fun startWelcomeChat(message: String, opened: () -> Unit) = chats.startWelcomeChat(message, opened)
    fun openChat(id: String) = chats.openChat(id)
    fun openTemporaryChat(uri: Uri? = null, title: String = "临时聊天") = chats.openTemporaryChat(uri, title)
    fun renameChat(chat: Chat, name: String) = chats.renameChat(chat, name)
    fun forkChat(position: Int, checkpoint: Boolean, requestedName: String = "") = chats.forkChat(position, checkpoint, requestedName)
    fun convertToGroup(done: () -> Unit) = chats.convertToGroup(done)
    fun copyChatTo(ownerId: String, toGroup: Boolean, done: () -> Unit) = chats.copyChatTo(ownerId, toGroup, done)
    fun loadChatImportHistory(ownerId: String, fromGroup: Boolean, loaded: (List<ChatSummary>) -> Unit) =
        chats.loadChatImportHistory(ownerId, fromGroup, loaded)
    fun importLocalChat(sourceOwnerId: String, chatId: String, done: () -> Unit) = chats.importLocalChat(sourceOwnerId, chatId, done)
    fun removeChat(chat: ChatSummary) = chats.removeChat(chat)
    fun importChat(uri: Uri, title: String) = chats.importChat(uri, title)
    fun exportChat(uri: Uri, chat: Chat, subject: String, withAttachments: Boolean = false) = chats.exportChat(uri, chat, subject, withAttachments)
    fun exportChatText(uri: Uri, chat: Chat) = chats.exportChatText(uri, chat)
    fun editMessage(chat: Chat, index: Int, text: String) = chats.editMessage(chat, index, text)
    fun editReasoning(chat: Chat, index: Int, text: String) = chats.editReasoning(chat, index, text)
    fun deleteMessages(chat: Chat, indices: Set<Int>, includeTools: Boolean = true) = chats.deleteMessages(chat, indices, includeTools)
    fun duplicateMessage(chat: Chat, index: Int) = chats.duplicateMessage(chat, index)
    fun moveMessage(chat: Chat, index: Int, direction: Int) = chats.moveMessage(chat, index, direction)
    fun swipe(chat: Chat, messageIndex: Int, option: Int) = chats.swipe(chat, messageIndex, option)
    fun removeReplyAlternative(chatId: String, messageIndex: Int, option: Int) = chats.removeReplyAlternative(chatId, messageIndex, option)
    fun saveTranslation(options: TranslationOptions, done: () -> Unit) = chats.saveTranslation(options, done)
    fun saveTranslationServices(settings: TranslationServices, done: () -> Unit) = chats.saveTranslationServices(settings, done)
    fun testTranslationService(settings: TranslationServices) = chats.testTranslationService(settings)
    fun translateDraft(text: String, done: (String) -> Unit) = chats.translateDraft(text, done)
    fun toggleTranslation(index: Int) = chats.toggleTranslation(index)
    fun translateChat(options: TranslationOptions) = chats.translateChat(options)
    fun clearTranslations() = chats.clearTranslations()
    fun saveMemory(options: MemoryOptions, text: String, done: () -> Unit) = chats.saveMemory(options, text, done)
    fun restoreMemory() = chats.restoreMemory()
    fun summarizeChat(options: MemoryOptions) = chats.summarizeChat(options)
    fun send(text: String, interpretCommands: Boolean = true): Boolean = generation.send(text, interpretCommands)
    fun regenerate() = generation.regenerate()
    fun continueReply() = generation.continueReply()
    fun nextAlternative() = generation.nextAlternative()
    fun impersonate() = generation.impersonate()
    fun consumeDraft(suggestion: DraftSuggestion) = generation.consumeDraft(suggestion)
    fun speakAs(character: Card) = generation.speakAs(character)
    fun toggleAuto() = generation.toggleAuto()
    fun stopAuto() = generation.stopAuto()
    fun stop() = generation.stop()
}
