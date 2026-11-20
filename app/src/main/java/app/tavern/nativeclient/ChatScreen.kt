package app.tavern.nativeclient

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tavern.nativeclient.chat.ChatBranchesDialog
import app.tavern.nativeclient.chat.ChatBubble
import app.tavern.nativeclient.chat.ChatComposerPanel
import app.tavern.nativeclient.chat.ChatModelPicker
import app.tavern.nativeclient.chat.ChatOptionsMenuItems
import app.tavern.nativeclient.chat.ChatPromptOverridesDialog
import app.tavern.nativeclient.chat.ChatSearch
import app.tavern.nativeclient.chat.ChatSharedMemorySheet
import app.tavern.nativeclient.chat.ChatSpeakerPickerDialog
import app.tavern.nativeclient.chat.MessageAction
import app.tavern.nativeclient.chat.TextDialog
import app.tavern.nativeclient.chat.messageActionsFor
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ChatScreen(chat: Chat, state: TavernState, model: TavernViewModel, configure: () -> Unit) {
    var draft by rememberSaveable { mutableStateOf("") }
    var draftSelection by remember { mutableStateOf(TextRange.Zero) }
    LaunchedEffect(chat.id, draft.isNotBlank()) { model.reportDraft(chat.id, draft.isNotBlank()) }
    DisposableEffect(chat.id) { onDispose { model.reportDraft(chat.id, false) } }
    var editing by remember { mutableStateOf<Int?>(null) }
    var reasoningEditor by rememberSaveable { mutableStateOf<Int?>(null) }
    var alternativePicker by rememberSaveable { mutableStateOf<Int?>(null) }
    var quickPicker by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Set<Int>?>(null) }
    var selected by remember { mutableStateOf<Set<Int>?>(null) }
    var deleteToolCalls by remember { mutableStateOf(true) }
    LaunchedEffect(state.messageSelection, state.scriptRunning, state.generation, state.busy) {
        val request = state.messageSelection
        if (request?.chatId == chat.id && !state.scriptRunning && state.generation == null && !state.busy) {
            deleteToolCalls = request.includeTools
            selected = emptySet()
            model.dismissMessageSelection()
        }
    }
    var checkpoint by rememberSaveable { mutableStateOf<Int?>(null) }
    var branches by rememberSaveable { mutableStateOf<Int?>(null) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val visualNovel = state.prefs.visualNovel
    var historyExpanded by rememberSaveable(visualNovel) { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchIndex by rememberSaveable { mutableIntStateOf(0) }
    var renaming by remember { mutableStateOf(false) }
    var modelPicker by rememberSaveable { mutableStateOf(false) }
    var speakerPicker by remember { mutableStateOf(false) }
    var optionsMenu by remember { mutableStateOf(false) }
    var personaPicker by rememberSaveable { mutableStateOf(false) }
    var noteEditor by rememberSaveable { mutableStateOf(false) }
    var scenarioEditor by rememberSaveable { mutableStateOf(false) }
    var expressions by rememberSaveable { mutableStateOf(false) }
    var worldPicker by rememberSaveable { mutableStateOf(false) }
    var promptPicker by rememberSaveable { mutableStateOf(false) }
    var promptInspector by rememberSaveable { mutableStateOf(false) }
    var previewImage by remember { mutableStateOf<CaptionImageTarget?>(null) }
    var captionImage by remember { mutableStateOf<CaptionImageTarget?>(null) }
    var previewDocument by remember { mutableStateOf<OpenChatDocument?>(null) }
    var pickDocumentsForMessage by rememberSaveable { mutableStateOf<Int?>(null) }
    var drawing by rememberSaveable { mutableStateOf(false) }
    var animeDrawing by rememberSaveable { mutableStateOf(false) }
    var drawingTarget by rememberSaveable { mutableStateOf<Int?>(null) }
    var drawingReference by remember { mutableStateOf<ChatImage?>(null) }
    var translationTools by rememberSaveable { mutableStateOf(false) }
    var memoryTools by rememberSaveable { mutableStateOf(false) }
    var sharedMemoryPanel by rememberSaveable { mutableStateOf(false) }
    var vectorTools by rememberSaveable { mutableStateOf(false) }
    var dataBank by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.openDataBank, state.scriptRunning, state.busy, state.generation != null) {
        if (state.openDataBank && !state.scriptRunning && !state.busy && state.generation == null) {
            dataBank = true
            model.dismissDataBankRequest()
        }
    }
    var dictation by remember { mutableStateOf<DictationProgress?>(null) }
    val attachedImages = state.draftImages[chat.id].orEmpty()
    val attachedDocuments = state.draftDocuments[chat.id].orEmpty()
    val pickImages = rememberLauncherForActivityResult(OpenMultipleDocuments()) { uris -> model.attachImages(chat.id, uris) }
    val pickCaptionImage = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) model.attachImages(chat.id, listOf(uri)) { captionImage = CaptionImageTarget(it) }
    }
    val pickBackground = rememberLauncherForActivityResult(OpenDocument()) { uri -> uri?.let { model.importChatBackground(chat.id, it) } }
    val pickDocuments = rememberLauncherForActivityResult(OpenMultipleDocuments()) { uris ->
        val target = pickDocumentsForMessage
        pickDocumentsForMessage = null
        model.attachChatDocuments(chat.id, target, uris)
    }
    val list = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val generation = state.generation?.takeIf { it.chatId == chat.id }
    val impersonating = generation?.action == ReplyAction.IMPERSONATE
    val reply = generation?.takeUnless { impersonating }
    val messages = chat.messages.dropLast(generation?.replacedMessages ?: 0)
    val depths = remember(messages) {
        var visible = 0
        IntArray(messages.size).apply {
            for (index in messages.indices.reversed()) {
                this[index] = visible
                if (!messages[index].excludedFromContext) visible++
            }
        }
    }
    val count = messages.size + if (reply == null) 1 else 2
    val connection = state.prefs.connectionFor(chat)
    val configured = connection != null
    val group = state.group
    val members = group?.members(state.cards).orEmpty()
    val canSend = group?.order == ReplyOrder.MANUAL || configured
    val busy = generation != null || state.busy || state.scriptRunning
    val actionsEnabled = !busy && selected == null
    val auto = state.autoChatId == chat.id
    val quickConfig = state.prefs.quickReplies
    val quickButtons = if (!quickConfig.enabled) emptyList() else quickConfig.selected(chat).flatMap { set ->
        set.items.filter { !it.hidden && it.message.isNotBlank() }.map { set to it }
    }
    val canContinue = configured && !busy && chat.messages.lastOrNull()
        ?.takeIf { !it.hidden && it.text.isNotBlank() }?.let(state::replyingCharacter) != null
    val parent = chat.options.parent?.resolve(state.history)?.takeUnless { it.id == chat.id }
    val capturedPrompt = state.lastPrompt?.takeIf { it.chatId == chat.id }
    val searchMatches = remember(searchQuery, messages) {
        val query = searchQuery.trim()
        if (query.isEmpty()) emptyList() else messages.mapIndexedNotNull { index, message ->
            index.takeIf { message.text.contains(query, ignoreCase = true) || message.displayText.contains(query, ignoreCase = true) ||
                message.name.contains(query, ignoreCase = true) }
        }
    }
    val matchPosition = searchIndex.coerceIn(0, maxOf(searchMatches.lastIndex, 0))
    val currentMatch = searchMatches.getOrNull(matchPosition)
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val showStage = visualNovel && !historyExpanded && selected == null && !searchOpen && !keyboardVisible
    val appearance = state.prefs.chatAppearance
    val activePreset = state.presetFor(chat)
    val mvuComputation = remember(messages, chat.options.variables) {
        app.tavern.nativeclient.script.MvuEngine.recomputeMvuState(
            messages = messages,
            initial = app.tavern.nativeclient.script.MvuEngine.initialStateFrom(chat.options.variables),
        )
    }
    val mvuStateJson = remember(mvuComputation) { mvuComputation.statData.toString() }
    var tokenEstimate by remember { mutableStateOf<ContextUsageEstimate?>(null) }
    LaunchedEffect(
        chat,
        state.card,
        state.persona,
        activePreset,
        draft,
        connection,
        capturedPrompt,
        appearance.showTokenBar,
    ) {
        if (!appearance.showTokenBar) {
            tokenEstimate = null
        } else {
            val counter = withContext(Dispatchers.Default) { model.tokenCounterFor(connection) }
            tokenEstimate = withContext(Dispatchers.Default) {
                estimateConversationTokens(chat, state.card, state.persona, activePreset, draft, connection, counter, capturedPrompt)
            }
        }
    }
    var userDetached by remember(chat.id) { mutableStateOf(false) }
    val isAtBottom by remember(count) {
        derivedStateOf {
            val lastVisible = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: (count - 1)
            lastVisible >= (count - 2).coerceAtLeast(0)
        }
    }
    LaunchedEffect(list.isScrollInProgress, isAtBottom) {
        if (list.isScrollInProgress && !isAtBottom) userDetached = true
        else if (isAtBottom) userDetached = false
    }
    val scope = rememberCoroutineScope()

    BackHandler(enabled = selected != null || searchOpen || visualNovel && historyExpanded) {
        when {
            selected != null -> selected = null
            searchOpen -> searchOpen = false
            else -> historyExpanded = false
        }
    }
    LaunchedEffect(state.suggestedDraft) {
        state.suggestedDraft?.takeIf { it.chatId == chat.id }?.let {
            draft = it.text
            draftSelection = TextRange(it.text.length)
            model.consumeDraft(it)
        }
    }
    LaunchedEffect(messages.size, reply?.replacedMessages, reply == null) {
        if (count > 1 && !searchOpen && !userDetached) list.scrollToItem(count - 1)
    }
    LaunchedEffect(searchOpen, currentMatch) {
        if (searchOpen && currentMatch != null) {
            userDetached = true
            list.scrollToItem(currentMatch)
        }
    }
    LaunchedEffect(reply?.text, reply?.reasoning) {
        if (reply != null && !searchOpen && !userDetached && !list.isScrollInProgress && isAtBottom) {
            list.scrollToItem(count - 1)
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        if (!visualNovel) chat.options.background?.let {
            ConversationBackground(it, model.imageFile(it), Modifier.matchParentSize())
        }
        Column(Modifier.widthIn(max = 820.dp).fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(chat.title, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        AssistChip(
                            onClick = {
                                model.stopAuto()
                                if (configured) modelPicker = true else configure()
                            },
                            enabled = actionsEnabled,
                            label = {
                                Text(connection?.let { "${it.provider.name} · ${it.model}" } ?: "选择模型",
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, "切换模型", Modifier.size(18.dp)) },
                        )
                    }
                    if (visualNovel) {
                        val label = if (historyExpanded) "返回视觉小说场景" else "展开聊天记录"
                        val icon = if (historyExpanded) Icons.Outlined.Landscape else Icons.AutoMirrored.Outlined.ListAlt
                        ActionIcon(icon, label, selected == null && !searchOpen) {
                            keyboard?.hide()
                            historyExpanded = !historyExpanded
                        }
                    }
                    Box {
                        ActionIcon(Icons.Outlined.MoreVert, "对话设置", actionsEnabled) {
                            model.stopAuto()
                            optionsMenu = true
                        }
                        DropdownMenu(optionsMenu, onDismissRequest = { optionsMenu = false }) {
                            ChatOptionsMenuItems(
                                chat = chat,
                                state = state,
                                model = model,
                                keyboard = keyboard,
                                configured = configured,
                                group = group,
                                members = members,
                                canContinue = canContinue,
                                parent = parent,
                                draft = draft,
                                attachedImagesCount = attachedImages.size,
                                appearance = appearance,
                                capturedPrompt = capturedPrompt,
                                closeMenu = { optionsMenu = false },
                                onContinue = model::continueReply,
                                onImpersonate = model::impersonate,
                                onOpenSearch = { searchOpen = true },
                                onSaveCheckpoint = { checkpoint = it },
                                onBatchDelete = { searchOpen = false; deleteToolCalls = true; selected = emptySet() },
                                onOpenPersona = { personaPicker = true },
                                onOpenNote = { noteEditor = true },
                                onOpenScenario = { scenarioEditor = true },
                                onOpenWorld = { worldPicker = true },
                                onOpenPrompt = { promptPicker = true },
                                onOpenTranslation = { translationTools = true },
                                onPickCaptionImage = { pickCaptionImage.launch(arrayOf("image/*")) },
                                onTranslateDraft = { draft = it },
                                onOpenMemory = { memoryTools = true },
                                onOpenVector = { vectorTools = true },
                                onOpenSharedMemory = { sharedMemoryPanel = true },
                                onOpenDataBank = { dataBank = true },
                                onOpenExpressions = { expressions = true },
                                onPickBackground = { pickBackground.launch(arrayOf("image/*")) },
                                onOpenQuickReplies = { quickPicker = true },
                                onOpenPromptInspector = { promptInspector = true },
                                onRename = { renaming = true },
                            )
                        }
                    }
                    if (group != null) ActionIcon(Icons.Outlined.RecordVoiceOver, "选择发言成员",
                        configured && actionsEnabled && members.isNotEmpty()) { model.stopAuto(); speakerPicker = true }
                    ActionIcon(Icons.Outlined.Refresh, if (chat.messages.isEmpty()) "开始群聊" else "重新生成回复",
                        configured && actionsEnabled && (chat.messages.isNotEmpty() || group?.participants(state.cards)?.isNotEmpty() == true)) {
                        keyboard?.hide(); model.regenerate()
                    }
                }
            }

            if (searchOpen) ChatSearch(
                searchQuery, { searchQuery = it; searchIndex = 0 }, searchMatches.size, matchPosition,
                navigate = { searchIndex = it }, close = { searchOpen = false },
            )

            if (appearance.showTokenBar) tokenEstimate?.let { estimate ->
                val scheme = MaterialTheme.colorScheme
                val barColor = when {
                    estimate.fraction > 0.90f -> scheme.error
                    estimate.fraction > 0.75f -> scheme.tertiary
                    else -> scheme.primary
                }
                Surface(
                    color = scheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().clickable(enabled = capturedPrompt != null) {
                        promptInspector = true
                    },
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "上下文 ~${estimate.usedTokens} / ${estimate.maxTokens} tok · ${estimate.counterLabel}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (estimate.fraction > 0.90f) scheme.error else scheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (capturedPrompt != null) {
                                Text("查看提示词", style = MaterialTheme.typography.labelSmall, color = scheme.primary)
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        LinearProgressIndicator(
                            progress = { estimate.fraction },
                            color = barColor,
                            trackColor = scheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                        )
                    }
                }
            }

            if (!visualNovel) ExpressionStrip(state, chat, model)

            ConversationLayout(chat, state, model, showStage, reply?.characterId, Modifier.weight(1f)) {
                Box(Modifier.fillMaxSize()) {
                    if (messages.isEmpty() && reply == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("发送一条消息来开始对话。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), state = list,
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(if (appearance.layoutMode == ChatLayoutMode.DOCUMENT) 10.dp else 14.dp)) {
                        itemsIndexed(messages) { index, message ->
                            val character = state.regexCard(message)
                            val appliedPreset = state.presetFor(chat)
                            val regex = remember(state.prefs.regex, character, state.persona.name, appliedPreset, state.group, state.cards) {
                                state.textRules(chat, character)
                            }
                            val avatar = if (message.role == "user") {
                                state.prefs.personas.firstOrNull { it.id == message.personaId }?.let(model::personaAvatar)
                            } else state.cards.firstOrNull { it.id == message.characterId && it.hasAvatar }?.let(model::portrait)
                            val selectedMessages = selected
                            val openCheckpoint = message.checkpoint?.resolve(state.history)?.takeUnless { it.id == chat.id }
                            val linkedBranches = message.branches.mapNotNull { it.resolve(state.history) }.filter { it.id != chat.id }
                            if (selectedMessages != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(index in selectedMessages,
                                        onCheckedChange = { selected = if (it) selectedMessages + index else selectedMessages - index },
                                        modifier = Modifier.semantics { contentDescription = "选择第 ${index + 1} 条消息" })
                                    ChatBubble(message, avatar = avatar, regex = regex, depth = depths[index],
                                        appearance = appearance,
                                        imageFile = model::imageFile, previewImage = { previewImage = CaptionImageTarget(it, index) },
                                        previewDocument = { previewDocument = OpenChatDocument(it, index) },
                                        cardScriptsEnabled = state.prefs.cardScriptsEnabled,
                                        mvuDeltas = mvuComputation.messageDeltas[index].orEmpty(),
                                        mvuStateJson = mvuStateJson,
                                        onRunScript = { model.runScript(it) })
                                }
                            } else {
                                ChatBubble(message, enabled = !busy, swipe = { model.swipe(chat, index, it) }, avatar = avatar,
                                    openAlternatives = { alternativePicker = index },
                                    regex = regex, depth = depths[index],
                                    appearance = appearance,
                                    imageFile = model::imageFile, previewImage = { previewImage = CaptionImageTarget(it, index) },
                                    previewDocument = { previewDocument = OpenChatDocument(it, index) },
                                    previousDrawing = { model.previousDrawing(chat.id, index) },
                                    nextDrawing = { model.nextDrawing(chat.id, index) },
                                    cardScriptsEnabled = state.prefs.cardScriptsEnabled,
                                    mvuDeltas = mvuComputation.messageDeltas[index].orEmpty(),
                                    mvuStateJson = mvuStateJson,
                                    onRunScript = { model.runScript(it) },
                                    highlighted = searchOpen && currentMatch == index,
                                    generateAlternative = if (index == chat.messages.lastIndex && canContinue) model::nextAlternative else null,
                                    actions = messageActionsFor(
                                        message = message,
                                        index = index,
                                        chat = chat,
                                        voiceEnabled = state.prefs.voice.enabled,
                                        hasCheckpoint = openCheckpoint != null,
                                        hasBranches = linkedBranches.isNotEmpty(),
                                    )) { action ->
                                    model.stopAuto()
                                    when (action) {
                                        MessageAction.NARRATE -> model.narrate(message)
                                        MessageAction.TRANSLATE, MessageAction.SHOW_ORIGINAL -> model.toggleTranslation(index)
                                        MessageAction.ATTACH_DOCUMENT -> {
                                            pickDocumentsForMessage = index
                                            pickDocuments.launch(arrayOf("*/*"))
                                        }
                                        MessageAction.DRAW -> {
                                            drawingTarget = index
                                            drawingReference = null
                                            drawing = true
                                        }
                                        MessageAction.EDIT -> editing = index
                                        MessageAction.HIDE, MessageAction.UNHIDE -> model.toggleMessageContext(chat, index)
                                        MessageAction.EDIT_REASONING -> reasoningEditor = index
                                        MessageAction.ALTERNATIVES -> alternativePicker = index
                                        MessageAction.DUPLICATE -> model.duplicateMessage(chat, index)
                                        MessageAction.UP -> model.moveMessage(chat, index, -1)
                                        MessageAction.DOWN -> model.moveMessage(chat, index, 1)
                                        MessageAction.BRANCH -> model.forkChat(index, checkpoint = false)
                                        MessageAction.CHECKPOINT -> checkpoint = index
                                        MessageAction.OPEN_CHECKPOINT -> openCheckpoint?.let { model.openChat(it.id) }
                                        MessageAction.OPEN_BRANCHES -> branches = index
                                        MessageAction.DELETE -> { deleteToolCalls = true; deleting = setOf(index) }
                                    }
                                }
                            }
                        }
                        if (reply != null) {
                            item {
                                if (reply.text.isBlank() && reply.reasoning.isBlank()) {
                                    Row(verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                        Text("${reply.speaker} 正在回复…", style = MaterialTheme.typography.bodyMedium)
                                    }
                                } else {
                                    val speaker = state.cards.firstOrNull { it.id == reply.characterId }
                                    val appliedPreset = state.presetFor(chat)
                                    val regex = remember(state.prefs.regex, speaker, state.persona.name, appliedPreset, state.group, state.cards) {
                                        state.textRules(chat, speaker)
                                    }
                                    ChatBubble(Message("assistant", reply.speaker, reply.text, reasoning = reply.reasoning),
                                        regex = regex, depth = 0, appearance = appearance)
                                }
                            }
                        }
                        item { Spacer(Modifier.height(2.dp)) }
                    }
                    }
                    if ((userDetached || !isAtBottom) && count > 1 && !searchOpen) {
                        SmallFloatingActionButton(
                            onClick = {
                                userDetached = false
                                scope.launch { list.animateScrollToItem(count - 1) }
                            },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ) {
                            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "回到底部")
                        }
                    }
                }
            }

            ChatComposerPanel(
                chat = chat,
                state = state,
                model = model,
                keyboard = keyboard,
                selected = selected,
                busy = busy,
                group = group,
                auto = auto,
                configured = configured,
                canSend = canSend,
                generation = generation,
                impersonating = impersonating,
                quickConfig = quickConfig,
                quickButtons = quickButtons,
                attachedImages = attachedImages,
                attachedDocuments = attachedDocuments,
                dictation = dictation,
                draft = draft,
                draftSelection = draftSelection,
                onDraftChange = { nextDraft, nextSel ->
                    draft = nextDraft
                    draftSelection = nextSel
                },
                onClearSelection = { selected = null },
                onRequestDeleteSelected = { deleting = it },
                onPickImages = { pickImages.launch(arrayOf("image/*")) },
                onPickDocuments = { pickDocumentsForMessage = null; pickDocuments.launch(arrayOf("*/*")) },
                onOpenDrawing = { keyboard?.hide(); animeDrawing = true },
                onDictationProgress = { dictation = it },
                onPreviewImage = { previewImage = CaptionImageTarget(it) },
                onPreviewDocument = { previewDocument = OpenChatDocument(it, null) },
                onBeforeSend = { userDetached = false },
            )
        }
    }

    previewImage?.let { target ->
        val image = target.image
        val reference: (() -> Unit)? = if (state.prefs.providers.any { provider ->
                ImageProtocol.entries.any { it.supportsReferences && it.available(provider) }
            }) {
            { drawingReference = image; drawingTarget = null; previewImage = null; drawing = true }
        } else null
        ImagePreviewDialog(image, model, !busy, { previewImage = null }, reference, state.imageCaption(chat.id, target)) {
            previewImage = null
            captionImage = target
        }
    }
    captionImage?.let { target ->
        ImageCaptionEditor(state, model, chat.id, target, { captionImage = null }, { captionImage = null; configure() })
    }
    previewDocument?.let { opened ->
        val remove: (() -> Unit)? = opened.messageIndex?.let { index ->
            { model.removeMessageDocument(chat.id, index, opened.document) }
        }
        ChatDocumentDialog(opened.document, model, !busy, close = { previewDocument = null }, remove = remove)
    }
    if (animeDrawing) {
        NanoBananaDrawingDialog(state, chat, model, attachedImages.size, dismiss = { animeDrawing = false },
            advanced = { animeDrawing = false; drawingReference = null; drawingTarget = null; drawing = true },
            configure = configure)
    }
    if (drawing) {
        val target = drawingTarget
        val source = target?.let(chat.messages::getOrNull)
        val references = drawingReference?.let(::listOf) ?: source?.displayImages ?: attachedImages
        ImageGenerationDialog(state, chat, model, source?.text ?: draft, references, target, { drawing = false }, configure)
    }
    if (translationTools) TranslationDialog(chat, state, model) { translationTools = false }
    if (memoryTools) MemoryDialog(chat, state, model) { memoryTools = false }
    if (sharedMemoryPanel) {
        ChatSharedMemorySheet(state, chat, members, busy, model) { sharedMemoryPanel = false }
    }
    if (vectorTools) VectorIndexDialog(state, model, { vectorTools = false }, configure)
    if (dataBank) DataBankDialog(state, model, close = { dataBank = false }, configure = { dataBank = false; configure() })
    if (modelPicker) {
        ChatModelPicker(
            state = state,
            chat = chat,
            dismiss = { modelPicker = false },
            refresh = { model.loadModels(it, persistCatalog = true) },
            selectProfile = { profileId ->
                modelPicker = false
                model.setChatConnectionProfile(profileId)
            },
        ) { selected ->
            modelPicker = false
            model.switchModel(selected)
        }
    }
    if (quickPicker) ChatQuickReplySets(state, model) { quickPicker = false }

    if (speakerPicker) {
        ChatSpeakerPickerDialog(members, dismiss = { speakerPicker = false }) { member ->
            speakerPicker = false
            keyboard?.hide()
            model.speakAs(member)
        }
    }

    if (personaPicker) ChatPersonaDialog(state, model) { personaPicker = false }
    if (noteEditor) AuthorNoteEditor(chat.options.authorNote ?: AuthorNote(), "作者注释", state.busy, { noteEditor = false }) {
        model.setChatAuthorNote(it) { noteEditor = false }
    }
    if (scenarioEditor) ChatPromptOverridesDialog(chat.options, busy, { scenarioEditor = false }) { scenario, examples, system ->
        model.setPromptOverrides(scenario, examples, system) { scenarioEditor = false }
    }
    if (expressions) ExpressionDialog(group?.let { members } ?: listOfNotNull(state.card), chat, model, state, busy) { expressions = false }
    if (worldPicker) {
        val explicit = chat.options.worldBook(state.books)
        val inherited = state.card?.worldName?.let { name -> state.books.firstOrNull { it.name == name } }
        ChatWorldPicker(explicit, inherited, chat.options.hasWorldBookOverride && explicit == null,
            state.books, group != null, chat.isTemporary, busy, { worldPicker = false }) {
            model.linkChatBook(it) { worldPicker = false }
        }
    }
    if (promptPicker) ChatPromptPresetPicker(state, chat, { promptPicker = false }) {
        model.switchPromptPreset(it) { promptPicker = false }
    }
    if (promptInspector && capturedPrompt != null) PromptInspector(capturedPrompt) { promptInspector = false }
    alternativePicker?.let { index ->
        chat.messages.getOrNull(index)?.takeIf { it.variants.isNotEmpty() }?.let { message ->
            ReplyAlternativePicker(message, busy, { alternativePicker = null }, select = {
                model.swipe(chat, index, it)
                alternativePicker = null
            }, delete = { model.removeReplyAlternative(chat.id, index, it) })
        }
    }
    if (renaming) TextDialog("重命名聊天", "聊天名称", chat.title, { renaming = false }) {
        renaming = false; model.renameChat(chat, it)
    }
    editing?.let { index ->
        TextDialog("编辑消息", "消息", chat.messages[index].text, { editing = null }, lines = 3) {
            editing = null; model.editMessage(chat, index, it)
        }
    }
    reasoningEditor?.let { index ->
        val message = chat.messages.getOrNull(index)
        if (message != null) TextDialog("编辑推理", "推理内容", message.reasoning.orEmpty(),
            { reasoningEditor = null }, lines = 4, allowBlank = true) {
            reasoningEditor = null
            model.editReasoning(chat, index, it)
        }
    }
    deleting?.let { indices ->
        val count = chat.messages.deletionIndices(indices, deleteToolCalls).size
        Confirm("删除消息？", "将从当前对话删除 ${count} 条消息及其备选回复。", { deleting = null }) {
            deleting = null
            selected = null
            model.deleteMessages(chat, indices, deleteToolCalls)
        }
    }
    checkpoint?.let { index ->
        TextDialog("保存检查点", "聊天名称", chat.nextForkTitle(state.history, checkpoint = true),
            { checkpoint = null }, allowBlank = true) {
            checkpoint = null
            model.forkChat(index, checkpoint = true, requestedName = it)
        }
    }
    branches?.let { index ->
        val chats = chat.messages.getOrNull(index)?.branches.orEmpty().mapNotNull { it.resolve(state.history) }
            .filter { it.id != chat.id }.distinctBy(ChatSummary::id)
        ChatBranchesDialog(chats, dismiss = { branches = null }) { id ->
            branches = null
            model.openChat(id)
        }
    }
}
