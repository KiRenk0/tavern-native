package app.tavern.nativeclient

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.tavern.nativeclient.chat.ChatBranchesDialog
import app.tavern.nativeclient.chat.ChatBubble
import app.tavern.nativeclient.chat.ChatComposerPanel
import app.tavern.nativeclient.chat.ChatModelPicker
import app.tavern.nativeclient.chat.ChatOptionsMenuItems
import app.tavern.nativeclient.chat.ChatPromptOverridesDialog
import app.tavern.nativeclient.chat.ChatSearch
import app.tavern.nativeclient.chat.ChatSharedMemorySheet
import app.tavern.nativeclient.chat.ChatSpeakerPickerDialog
import app.tavern.nativeclient.chat.FavoriteChatStrip
import app.tavern.nativeclient.chat.GroupReplyQueue
import app.tavern.nativeclient.chat.MessageAction
import app.tavern.nativeclient.chat.MessageEditorDialog
import app.tavern.nativeclient.chat.TextDialog
import app.tavern.nativeclient.chat.messageActionsFor
import app.tavern.nativeclient.chat.streamingText
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun ChatScreen(chat: Chat, state: TavernState, model: TavernViewModel, editMember: (Card) -> Unit,
                        manageFiles: () -> Unit, closeChat: () -> Unit, configure: () -> Unit) {
    var draft by rememberSaveable(chat.id) { mutableStateOf(model.loadChatDraft(chat)) }
    var draftSelection by remember(chat.id) { mutableStateOf(TextRange(draft.length)) }
    val draftState by rememberUpdatedState(draft)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(chat.id, draft.isNotBlank()) { model.reportDraft(chat.id, draft.isNotBlank()) }
    LaunchedEffect(chat.id, draft, state.prefs.chatHandling.restoreDraft) {
        if (state.prefs.chatHandling.restoreDraft) {
            delay(300)
            model.saveChatDraft(chat, draft)
        }
    }
    DisposableEffect(chat.id, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) model.saveChatDraft(chat, draftState)
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            model.saveChatDraft(chat, draftState)
            model.reportDraft(chat.id, false)
            model.clearGoogleReplies(chat.id)
        }
    }
    var editing by rememberSaveable(chat.id) { mutableStateOf<Int?>(null) }
    var editDraft by rememberSaveable(chat.id) { mutableStateOf("") }
    var savedEditDraft by rememberSaveable(chat.id) { mutableStateOf<String?>(null) }
    var reasoningEditor by rememberSaveable { mutableStateOf<Int?>(null) }
    var alternativePicker by rememberSaveable { mutableStateOf<Int?>(null) }
    var backgroundLibrary by rememberSaveable { mutableStateOf(false) }
    var conversationStats by rememberSaveable { mutableStateOf(false) }
    var tokenCounter by rememberSaveable { mutableStateOf(false) }
    var messageDetails by rememberSaveable { mutableStateOf<Int?>(null) }
    var tokenProbabilities by rememberSaveable { mutableStateOf<Int?>(null) }
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
    var refreshingCheckpoint by rememberSaveable { mutableStateOf<Int?>(null) }
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
    var previewVideo by remember { mutableStateOf<Pair<Int, ChatVideo>?>(null) }
    var removingMedia by remember { mutableStateOf<Pair<Int, String?>?>(null) }
    var videoGeneration by rememberSaveable { mutableStateOf(false) }
    var pickDocumentsForMessage by rememberSaveable { mutableStateOf<Int?>(null) }
    var pickImagesForMessage by rememberSaveable(chat.id) { mutableStateOf<Int?>(null) }
    var pickVideoForMessage by rememberSaveable(chat.id) { mutableStateOf<Int?>(null) }
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
    val pickMessageImages = rememberLauncherForActivityResult(OpenMultipleDocuments()) { uris ->
        val target = pickImagesForMessage
        pickImagesForMessage = null
        if (target != null) model.attachMessageImages(chat.id, target, uris)
    }
    val pickVideo = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        val target = pickVideoForMessage
        pickVideoForMessage = null
        uri?.let { model.importVideo(chat.id, it, target) }
    }
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
    val handling = state.prefs.chatHandling
    val appearance = state.prefs.chatAppearance
    val visualStream = streamingText(generation, handling, appearance.reducedMotion)
    val messages = remember(chat.messages, generation?.replacedMessages) {
        chat.messages.dropLast(generation?.replacedMessages ?: 0)
    }
    val pageSize = handling.historyPageSize
    var earliestRevealed by rememberSaveable(chat.id, pageSize) { mutableIntStateOf(Int.MAX_VALUE) }
    val defaultStart = if (pageSize == 0) 0 else (messages.size - pageSize).coerceAtLeast(0)
    val historyStart = minOf(defaultStart, earliestRevealed)
    val visibleMessages = remember(messages, historyStart) { messages.subList(historyStart, messages.size) }
    val googleReplies = remember(state.googleReplies, chat.id) {
        state.googleReplies.filter { it.chatId == chat.id }
    }
    val groundedByIndex = remember(googleReplies, messages) {
        googleReplies.groupBy { entry ->
            entry.anchorDate?.let { date -> messages.indexOfLast { it.date == date }.takeIf { it >= 0 } }
        }
    }
    val unanchoredReplies = groundedByIndex[null].orEmpty()
    val depths = remember(messages) {
        var visible = 0
        IntArray(messages.size).apply {
            for (index in messages.indices.reversed()) {
                this[index] = visible
                if (!messages[index].excludedFromContext) visible++
            }
        }
    }
    val unanchoredRowCount = if (unanchoredReplies.isEmpty()) 0 else 1
    val olderRowCount = if (historyStart == 0) 0 else 1
    val count = visibleMessages.size + (if (reply == null) 1 else 2) + unanchoredRowCount + olderRowCount
    val connection = state.prefs.connectionFor(chat)
    val configured = connection != null
    val group = state.group
    val members = group?.members(state.cards).orEmpty()
    val canSend = group?.order == ReplyOrder.MANUAL || configured
    val busy = generation != null || state.busy || state.scriptRunning
    val chatForEdit by rememberUpdatedState(chat)
    val editingBusy by rememberUpdatedState(busy)
    LaunchedEffect(editing, editDraft, deleting, handling.autoSaveEdits) {
        val index = editing ?: return@LaunchedEffect
        val pending = editDraft
        if (!handling.autoSaveEdits || deleting != null || pending == savedEditDraft) return@LaunchedEffect
        delay(650)
        val active = chatForEdit
        val message = active.messages.getOrNull(index) ?: return@LaunchedEffect
        if (!editingBusy && message.acceptsEditing(pending)) {
            model.editMessage(active, index, pending) {
                if (editing == index) savedEditDraft = pending
            }
        }
    }
    fun deleteMessages(indices: Set<Int>, includeTools: Boolean) {
        if (busy || indices.isEmpty()) return
        model.deleteMessages(chat, indices, includeTools) {
            deleting = null
            editing = null
            selected = null
        }
    }
    fun requestMessageDeletion(indices: Set<Int>, includeTools: Boolean) {
        if (handling.confirmDelete) {
            deleteToolCalls = includeTools
            deleting = indices
        } else deleteMessages(indices, includeTools)
    }
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
    val historyBoundary = capturedPrompt?.takeIf { it.model == connection?.model && it.providerId == connection.provider.id }
        ?.contextBudget?.takeIf { it.omittedMessages > 0 }?.let { budget ->
            budget.firstHistoryIndex?.takeIf { messages.getOrNull(it)?.date == budget.firstHistoryDate }
        }
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
    val activePreset = state.presetFor(chat)
    val mvuComputation = remember(messages, chat.options.variables, state.card, state.books) {
        val (initial, schema) = app.tavern.nativeclient.script.MvuEngine.resolveInitialStateWithSchema(
            variables = chat.options.variables,
            card = state.card,
            books = state.books,
        )
        app.tavern.nativeclient.script.MvuEngine.recomputeMvuState(
            messages = messages,
            initial = initial,
            schema = schema,
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
            if (draft.isNotBlank()) delay(150)
            val counter = withContext(Dispatchers.Default) { model.tokenCounterFor(connection) }
            tokenEstimate = withContext(Dispatchers.Default) {
                estimateConversationTokens(chat, state.card, state.persona, activePreset, draft, connection, counter, capturedPrompt)
            }
        }
    }
    var userDetached by remember(chat.id) { mutableStateOf(false) }
    val archiveJump = state.archiveJump?.takeIf { it.chatId == chat.id }
    var archiveHighlight by remember(chat.id) { mutableStateOf<Int?>(null) }
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
    fun showMessage(index: Int) {
        val start = if (index < historyStart) {
            (index - pageSize / 2).coerceAtLeast(0).also { earliestRevealed = it }
        } else historyStart
        val beforeMessages = unanchoredRowCount + if (start == 0) 0 else 1
        list.requestScrollToItem(index - start + beforeMessages)
    }

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
    LaunchedEffect(googleReplies.lastOrNull()?.id) {
        if (visualNovel && googleReplies.isNotEmpty()) historyExpanded = true
    }
    val autoScroll = handling.autoScroll
    LaunchedEffect(autoScroll, count, reply?.replacedMessages, reply == null, googleReplies.lastOrNull()?.id) {
        if (autoScroll && count > 1 && archiveJump == null && !searchOpen && !userDetached) list.scrollToItem(count - 1)
    }
    LaunchedEffect(archiveJump) {
        if (archiveJump != null && archiveJump.messageIndex in messages.indices) {
            userDetached = true
            historyExpanded = true
            archiveHighlight = archiveJump.messageIndex
            showMessage(archiveJump.messageIndex)
            model.dismissArchiveJump(archiveJump)
        }
    }
    LaunchedEffect(archiveHighlight) {
        if (archiveHighlight != null) {
            delay(2_500)
            archiveHighlight = null
        }
    }
    LaunchedEffect(searchOpen, currentMatch) {
        if (searchOpen && currentMatch != null) {
            userDetached = true
            showMessage(currentMatch)
        }
    }
    LaunchedEffect(visualStream?.body, visualStream?.reasoning) {
        if (autoScroll && reply != null && !searchOpen && !userDetached && !list.isScrollInProgress && isAtBottom) {
            list.scrollToItem(count - 1)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        if (!visualNovel) state.prefs.backgrounds.effective(chat)?.let {
            ConversationBackground(it, model.backgroundFile(it), Modifier.matchParentSize(), state.prefs.backgrounds.fit)
        }
        val availableWidth = minOf(maxWidth, 820.dp)
        val chatWidth = (availableWidth * (appearance.chatWidthPercent / 100f))
            .coerceAtLeast(minOf(availableWidth, 360.dp))
        val chatTint = appearance.palette.color(ThemeColor.CHAT)
        val transcript = Modifier.width(chatWidth).fillMaxHeight()
        Column(if (chatTint == null) transcript else transcript.background(chatTint)) {
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
                                onManageFiles = manageFiles,
                                onCloseChat = closeChat,
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
                                onGenerateVideo = { videoGeneration = true },
                                onImportVideo = {
                                    pickVideoForMessage = null
                                    pickVideo.launch(arrayOf("video/mp4"))
                                },
                                onTranslateDraft = { draft = it },
                                onOpenMemory = { memoryTools = true },
                                onOpenVector = { vectorTools = true },
                                onOpenSharedMemory = { sharedMemoryPanel = true },
                                onOpenDataBank = { dataBank = true },
                                onOpenExpressions = { expressions = true },
                                onPickBackground = { pickBackground.launch(arrayOf("image/*")) },
                                onOpenBackgroundLibrary = { backgroundLibrary = true },
                                onOpenStatistics = { conversationStats = true },
                                onOpenTokenCounter = { tokenCounter = true },
                                onOpenQuickReplies = { quickPicker = true },
                                onOpenPromptInspector = { promptInspector = true },
                                onRename = { renaming = true },
                            )
                        }
                    }
                    if (group != null) ActionIcon(Icons.Outlined.Groups, "群聊成员",
                        actionsEnabled && members.isNotEmpty()) { model.stopAuto(); speakerPicker = true }
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
                                text = "${if (estimate.fromRequest) "上次请求" else "上下文估算"} ${estimate.usedTokens} / ${estimate.maxTokens} Token · ${estimate.counterLabel}",
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

            if (appearance.favoriteHotswap && selected == null) {
                FavoriteChatStrip(chat, state, model, actionsEnabled) {
                    keyboard?.hide()
                    model.openFavoriteConversation(it)
                }
            }
            if (state.prefs.showGroupQueue) GroupReplyQueue(generation?.groupQueue.orEmpty())
            if (!visualNovel) ExpressionStrip(state, chat, model)

            ConversationLayout(chat, state, model, showStage, reply?.characterId, Modifier.weight(1f)) {
                Box(Modifier.fillMaxSize()) {
                    if (messages.isEmpty() && reply == null && unanchoredReplies.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("发送一条消息来开始对话。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), state = list,
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(when (appearance.layoutMode) {
                                ChatLayoutMode.FLAT -> 4.dp
                                ChatLayoutMode.DOCUMENT -> 10.dp
                                ChatLayoutMode.BUBBLE -> 14.dp
                            })) {
                        if (unanchoredReplies.isNotEmpty()) {
                            item(key = "unanchored-replies") {
                                Column {
                                    unanchoredReplies.forEach { entry ->
                                        key(entry.id) { GoogleGroundedReplyCard(entry, appearance) }
                                    }
                                }
                            }
                        }
                        if (historyStart > 0) {
                            item(key = "older-messages") {
                                OutlinedButton(
                                    onClick = {
                                        userDetached = true
                                        val start = (historyStart - pageSize).coerceAtLeast(0)
                                        earliestRevealed = start
                                        list.requestScrollToItem(unanchoredRowCount + if (start == 0) 0 else 1)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("加载更早的消息 · 还有 $historyStart 条") }
                            }
                        }
                        itemsIndexed(visibleMessages, key = { index, _ -> "message-${historyStart + index}" }) { localIndex, message ->
                            val index = historyStart + localIndex
                            if (index == historyBoundary) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    HorizontalDivider(Modifier.weight(1f))
                                    Text("上次请求的聊天记录从此处开始", style = MaterialTheme.typography.labelSmall)
                                    HorizontalDivider(Modifier.weight(1f))
                                }
                            }
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
                                    ChatBubble(message, avatar = avatar, regex = regex, depth = depths[index], messageIndex = index,
                                        appearance = appearance, autoExpandReasoning = state.prefs.replyFormatting.autoExpand,
                                        reasoningExpandedOverride = state.reasoningCollapse.expandedFor(chat.id, index, state.prefs.replyFormatting.autoExpand),
                                        reasoningRevision = state.reasoningCollapse.revision,
                                        showSwipeControls = handling.showSwipes, repairMarkdown = handling.repairMarkdown,
                                        imageFile = model::imageFile, previewImage = { previewImage = CaptionImageTarget(it, index) },
                                        previewDocument = { previewDocument = OpenChatDocument(it, index) },
                                        previewVideo = { previewVideo = index to it },
                                        cardScriptsEnabled = state.prefs.cardScriptsEnabled,
                                        mvuDeltas = mvuComputation.messageDeltas[index].orEmpty(),
                                        mvuStateJson = mvuStateJson,
                                        onRunScript = { model.runScript(it) })
                                }
                            } else {
                                val opening = group == null && chat.messages.size == 1 && message.openingGreeting
                                ChatBubble(message, enabled = !busy, swipe = { model.swipe(chat, index, it) }, avatar = avatar,
                                    swipeGestures = state.prefs.swipeGestures && index == chat.messages.lastIndex && editing == null,
                                    showSwipeControls = handling.showSwipes, repairMarkdown = handling.repairMarkdown,
                                    openAlternatives = { alternativePicker = index },
                                    regex = regex, depth = depths[index], messageIndex = index,
                                    appearance = appearance, autoExpandReasoning = state.prefs.replyFormatting.autoExpand,
                                    reasoningExpandedOverride = state.reasoningCollapse.expandedFor(chat.id, index, state.prefs.replyFormatting.autoExpand),
                                    reasoningRevision = state.reasoningCollapse.revision,
                                    imageFile = model::imageFile, previewImage = { previewImage = CaptionImageTarget(it, index) },
                                    previewDocument = { previewDocument = OpenChatDocument(it, index) },
                                    previewVideo = { previewVideo = index to it },
                                    previousDrawing = { model.previousDrawing(chat.id, index) },
                                    nextDrawing = { model.nextDrawing(chat.id, index) },
                                    selectMedia = { model.setMessageMedia(chat.id, index, selected = it) },
                                    cardScriptsEnabled = state.prefs.cardScriptsEnabled,
                                    mvuDeltas = mvuComputation.messageDeltas[index].orEmpty(),
                                    mvuStateJson = mvuStateJson,
                                    onRunScript = { model.runScript(it) },
                                    highlighted = (searchOpen && currentMatch == index) || archiveHighlight == index,
                                    loopOpenings = opening,
                                    generateAlternative = if (!opening && index == chat.messages.lastIndex && canContinue) model::nextAlternative else null,
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
                                        MessageAction.DETAILS -> messageDetails = index
                                        MessageAction.TOKEN_PROBABILITIES -> tokenProbabilities = index
                                        MessageAction.NARRATE -> model.narrate(message)
                                        MessageAction.TRANSLATE, MessageAction.SHOW_ORIGINAL -> model.toggleTranslation(index)
                                        MessageAction.ATTACH_DOCUMENT -> {
                                            pickDocumentsForMessage = index
                                            pickDocuments.launch(arrayOf("*/*"))
                                        }
                                        MessageAction.ATTACH_IMAGE -> {
                                            pickImagesForMessage = index
                                            pickMessageImages.launch(arrayOf("image/*"))
                                        }
                                        MessageAction.ATTACH_VIDEO -> {
                                            pickVideoForMessage = index
                                            pickVideo.launch(arrayOf("video/mp4"))
                                        }
                                        MessageAction.SWITCH_MEDIA_DISPLAY -> {
                                            val display = if ((message.mediaDisplay ?: appearance.defaultMediaDisplay) == MessageMediaDisplay.GALLERY)
                                                MessageMediaDisplay.LIST else MessageMediaDisplay.GALLERY
                                            model.setMessageMedia(chat.id, index, display = display)
                                        }
                                        MessageAction.REMOVE_MEDIA -> removingMedia = index to null
                                        MessageAction.DRAW -> {
                                            drawingTarget = index
                                            drawingReference = null
                                            drawing = true
                                        }
                                        MessageAction.EDIT -> {
                                            editDraft = message.text
                                            savedEditDraft = message.text
                                            editing = index
                                        }
                                        MessageAction.HIDE, MessageAction.UNHIDE -> model.toggleMessageContext(chat, index)
                                        MessageAction.EDIT_REASONING -> reasoningEditor = index
                                        MessageAction.ALTERNATIVES -> alternativePicker = index
                                        MessageAction.DUPLICATE -> model.duplicateMessage(chat, index)
                                        MessageAction.UP -> model.moveMessage(chat, index, -1)
                                        MessageAction.DOWN -> model.moveMessage(chat, index, 1)
                                        MessageAction.BRANCH -> model.forkChat(index, checkpoint = false)
                                        MessageAction.CHECKPOINT -> checkpoint = index
                                        MessageAction.OPEN_CHECKPOINT -> openCheckpoint?.let { model.openChat(it.id) }
                                        MessageAction.REFRESH_CHECKPOINT -> refreshingCheckpoint = index
                                        MessageAction.OPEN_BRANCHES -> branches = index
                                        MessageAction.DELETE -> requestMessageDeletion(setOf(index), includeTools = true)
                                    }
                                }
                            }
                            groundedByIndex[index].orEmpty().forEach { entry ->
                                key(entry.id) { GoogleGroundedReplyCard(entry, appearance) }
                            }
                        }
                        if (reply != null && visualStream != null) {
                            item(key = "stream-${reply.id}-${reply.characterId}-${reply.action}") {
                                if (visualStream.body.isBlank() && visualStream.reasoning.isBlank()) {
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
                                    val body = if (handling.collapseNewlines) {
                                        collapseChatNewlines(visualStream.body, reply.hiddenReplyPrefix.orEmpty())
                                    } else visualStream.body
                                    ChatBubble(Message("assistant", reply.speaker, body, reasoning = visualStream.reasoning,
                                        hiddenReplyPrefix = reply.hiddenReplyPrefix), regex = regex, depth = 0,
                                        appearance = appearance, autoExpandReasoning = state.prefs.replyFormatting.autoExpand,
                                        repairMarkdown = handling.repairMarkdown,
                                        streamFadeIn = handling.streamFadeIn && !appearance.reducedMotion)
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
                                scope.launch {
                                    if (appearance.reducedMotion) list.scrollToItem(count - 1)
                                    else list.animateScrollToItem(count - 1)
                                }
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
                canContinue = canContinue,
                generation = if (impersonating && visualStream != null) generation.copy(text = visualStream.body) else generation,
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
                    if (nextDraft.isEmpty()) model.saveChatDraft(chat, "")
                },
                onClearSelection = { selected = null },
                onRequestDeleteSelected = { requestMessageDeletion(it, deleteToolCalls) },
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
        val remove: (() -> Unit)? = target.messageIndex?.let { index ->
            {
                previewImage = null
                removingMedia = index to image.filename
            }
        }
        ImagePreviewDialog(image, model, !busy, { previewImage = null }, reference, state.imageCaption(chat.id, target), remove = remove) {
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
    previewVideo?.let { (index, video) ->
        ChatVideoDialog(video, model, !busy, close = { previewVideo = null }) { model.removeMessageMedia(chat.id, index, video.filename) }
    }
    removingMedia?.let { (index, filename) ->
        val title = if (filename == null) "移除全部媒体？" else "移除这张图片？"
        Confirm(title, "将从这条消息的当前回复中移除所选媒体。", { removingMedia = null }) {
            removingMedia = null
            model.removeMessageMedia(chat.id, index, filename)
        }
    }
    if (videoGeneration) {
        VideoGenerationDialog(state, chat.id, model, draft, attachedImages, { videoGeneration = false }, configure)
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

    if (speakerPicker && group != null) {
        ChatSpeakerPickerDialog(members, group.mutedIds, actionsEnabled, configured, dismiss = { speakerPicker = false },
            edit = { member ->
                speakerPicker = false
                keyboard?.hide()
                editMember(member)
            }) { member ->
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
    if (backgroundLibrary) BackgroundLibraryDialog(state, model, chat) { backgroundLibrary = false }
    if (conversationStats) ConversationStatisticsDialog("当前聊天统计", model, chat = chat) { conversationStats = false }
    if (tokenCounter) TokenCounterDialog(chat, connection, model, draft) { tokenCounter = false }
    messageDetails?.let { index -> chat.messages.getOrNull(index)?.let { message ->
        MessageStatisticsDialog(message) { messageDetails = null }
    } }
    tokenProbabilities?.let { index ->
        val message = chat.messages.getOrNull(index)
        val trace = message?.replyTokens
        if (message != null && trace != null) {
            val canReroll = !busy && !message.hidden && index == chat.messages.lastIndex &&
                connection?.provider?.canRerollToken == true && state.replyingCharacter(message) != null
            ReplyTokenProbabilityDialog(trace, canReroll, dismiss = { tokenProbabilities = null }) { token, candidate ->
                tokenProbabilities = null
                model.rerollToken(chat.id, message, token, candidate)
            }
        }
    }
    alternativePicker?.let { index ->
        chat.messages.getOrNull(index)?.takeIf { it.variants.isNotEmpty() }?.let { message ->
            ReplyAlternativePicker(message, busy, { alternativePicker = null }, select = {
                model.swipe(chat, index, it)
                alternativePicker = null
            }, delete = { model.removeReplyAlternative(chat.id, index, it) },
                confirmDelete = handling.confirmDelete,
                branch = if (chat.isTemporary) null else { option ->
                    alternativePicker = null
                    model.forkChat(index, checkpoint = false, swipe = option)
                })
        }
    }
    if (renaming) TextDialog("重命名聊天", "聊天名称", chat.title, { renaming = false }) {
        renaming = false; model.renameChat(chat, it)
    }
    if (deleting == null) editing?.let { index ->
        val message = chat.messages.getOrNull(index) ?: return@let
        fun saveAndClose() {
            if (busy) return
            if (editDraft == savedEditDraft) editing = null
            else if (message.acceptsEditing(editDraft)) model.editMessage(chat, index, editDraft) { editing = null }
        }
        MessageEditorDialog(
            draft = editDraft,
            changeDraft = { editDraft = it },
            allowBlank = message.acceptsEditing(""),
            autoSave = handling.autoSaveEdits,
            markdownHotkeys = handling.markdownHotkeys,
            enabled = !busy,
            canMoveUp = index > 0,
            canMoveDown = index < chat.messages.lastIndex,
            move = { direction -> model.moveMessage(chat, index, direction) { editing = index + direction } },
            duplicate = { model.duplicateMessage(chat, index, editDraft) { editing = null } },
            delete = { requestMessageDeletion(setOf(index), includeTools = true) },
            save = ::saveAndClose,
            dismiss = {
                if (!busy) {
                    if (handling.autoSaveEdits && message.acceptsEditing(editDraft)) saveAndClose()
                    else editing = null
                }
            },
        )
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
            deleteMessages(indices, deleteToolCalls)
        }
    }
    checkpoint?.let { index ->
        TextDialog("保存检查点", "聊天名称", chat.nextForkTitle(state.history, checkpoint = true),
            { checkpoint = null }, allowBlank = true) {
            checkpoint = null
            model.forkChat(index, checkpoint = true, requestedName = it)
        }
    }
    refreshingCheckpoint?.let { index ->
        val file = chat.messages.getOrNull(index)?.checkpoint?.resolve(state.history)?.takeUnless { it.id == chat.id }
        if (file != null) Confirm("更新检查点？", "用当前聊天截至这条消息的记录替换「${file.title}」中的内容。",
            { refreshingCheckpoint = null }) {
            refreshingCheckpoint = null
            model.refreshCheckpoint(index, file.id)
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
