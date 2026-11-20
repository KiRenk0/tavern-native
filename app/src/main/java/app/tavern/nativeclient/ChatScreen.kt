package app.tavern.nativeclient

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun ChatScreen(chat: Chat, state: TavernState, model: TavernViewModel, configure: () -> Unit) {
    var draft by rememberSaveable { mutableStateOf("") }
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
            model.consumeDraft(it)
        }
    }
    LaunchedEffect(messages.size, reply?.replacedMessages, reply == null) {
        if (count > 1 && !searchOpen) list.scrollToItem(count - 1)
    }
    LaunchedEffect(searchOpen, currentMatch) {
        if (searchOpen && currentMatch != null) list.scrollToItem(currentMatch)
    }
    LaunchedEffect(reply?.text) {
        if (reply != null && !searchOpen && !list.isScrollInProgress &&
            (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= count - 2) {
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
                            if (canContinue) MenuItem("续写最后一条回复", Icons.Outlined.PlayArrow) {
                                optionsMenu = false; keyboard?.hide(); model.continueReply()
                            }
                            if (configured && (group == null || members.isNotEmpty())) {
                                MenuItem("代写我的消息", Icons.Outlined.EditNote) {
                                    optionsMenu = false; keyboard?.hide(); model.impersonate()
                                }
                            }
                            if (parent != null) MenuItem("返回父对话", Icons.AutoMirrored.Outlined.Undo) {
                                optionsMenu = false; model.openChat(parent.id)
                            }
                            if (chat.messages.isNotEmpty()) {
                                MenuItem("查找消息", Icons.Outlined.Search) {
                                    optionsMenu = false; searchOpen = true
                                }
                                if (!chat.isTemporary) MenuItem("保存检查点", Icons.Outlined.Flag) {
                                    optionsMenu = false; checkpoint = chat.messages.lastIndex
                                }
                                MenuItem("批量删除消息", Icons.Outlined.DeleteSweep) {
                                    optionsMenu = false; searchOpen = false; deleteToolCalls = true; selected = emptySet()
                                }
                            }
                            MenuItem("对话人设", Icons.Outlined.Person) { optionsMenu = false; personaPicker = true }
                            MenuItem("作者注释", Icons.AutoMirrored.Outlined.StickyNote2) { optionsMenu = false; noteEditor = true }
                            MenuItem("场景与提示覆盖", Icons.Outlined.Landscape) { optionsMenu = false; scenarioEditor = true }
                            MenuItem("对话世界书", Icons.AutoMirrored.Outlined.MenuBook) { optionsMenu = false; worldPicker = true }
                            MenuItem("提示词预设", Icons.Outlined.Tune) { optionsMenu = false; promptPicker = true }
                            MenuItem("对话翻译", Icons.Outlined.Translate) { optionsMenu = false; translationTools = true }
                            if (attachedImages.size < MAX_CHAT_IMAGES) MenuItem("从文件生成图片描述", Icons.Outlined.ImageSearch) {
                                optionsMenu = false
                                pickCaptionImage.launch(arrayOf("image/*"))
                            }
                            if (draft.isNotBlank()) MenuItem("翻译输入", Icons.Outlined.Translate) {
                                optionsMenu = false; model.translateDraft(draft) { draft = it }
                            }
                            MenuItem("摘要记忆", Icons.Outlined.Summarize) { optionsMenu = false; memoryTools = true }
                            if (!chat.isTemporary) {
                                MenuItem("向量索引", Icons.AutoMirrored.Outlined.ManageSearch) { optionsMenu = false; vectorTools = true }
                                MenuItem("资料库", Icons.AutoMirrored.Outlined.Article) { optionsMenu = false; dataBank = true }
                                MenuItem("图库", Icons.Outlined.PhotoLibrary) { optionsMenu = false; model.showImageGallery() }
                                MenuItem("角色表情", Icons.Outlined.Mood) { optionsMenu = false; expressions = true }
                            }
                            MenuItem(if (visualNovel) "关闭视觉小说模式" else "开启视觉小说模式", Icons.Outlined.AutoStories) {
                                optionsMenu = false; model.setVisualNovel(!visualNovel)
                            }
                            MenuItem("从文件设置对话背景", Icons.Outlined.AddPhotoAlternate) {
                                optionsMenu = false; pickBackground.launch(arrayOf("image/*"))
                            }
                            if (chat.options.background != null) MenuItem("清除对话背景", Icons.Outlined.HideImage) {
                                optionsMenu = false; model.clearDrawingBackground(chat.id)
                            }
                            if (quickConfig.sets.isNotEmpty()) MenuItem("快捷回复集", Icons.Outlined.Bolt) {
                                optionsMenu = false; quickPicker = true
                            }
                            if (capturedPrompt != null) MenuItem("查看最近请求的提示词", Icons.AutoMirrored.Outlined.ListAlt) {
                                optionsMenu = false; promptInspector = true
                            }
                            MenuItem("重命名聊天", Icons.Outlined.Edit) { optionsMenu = false; renaming = true }
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

            if (!visualNovel) ExpressionStrip(state, chat, model)

            ConversationLayout(chat, state, model, showStage, reply?.characterId, Modifier.weight(1f)) {
                if (messages.isEmpty() && reply == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("发送一条消息来开始对话。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize(), state = list,
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
                                        imageFile = model::imageFile, previewImage = { previewImage = CaptionImageTarget(it, index) },
                                        previewDocument = { previewDocument = OpenChatDocument(it, index) })
                                }
                            } else {
                                ChatBubble(message, enabled = !busy, swipe = { model.swipe(chat, index, it) }, avatar = avatar,
                                    openAlternatives = { alternativePicker = index },
                                    regex = regex, depth = depths[index],
                                    imageFile = model::imageFile, previewImage = { previewImage = CaptionImageTarget(it, index) },
                                    previewDocument = { previewDocument = OpenChatDocument(it, index) },
                                    previousDrawing = { model.previousDrawing(chat.id, index) },
                                    nextDrawing = { model.nextDrawing(chat.id, index) },
                                    highlighted = searchOpen && currentMatch == index,
                                    generateAlternative = if (index == chat.messages.lastIndex && canContinue) model::nextAlternative else null,
                                    actions = buildList {
                                        if (state.prefs.voice.enabled && message.text.isNotBlank()) add(MessageAction.NARRATE)
                                        if (message.text.isNotBlank() && message.role != "system") {
                                            add(if (message.translation == null) MessageAction.TRANSLATE else MessageAction.SHOW_ORIGINAL)
                                        }
                                        if (message.role != "system" && message.documents.size < MAX_CHAT_DOCUMENTS) add(MessageAction.ATTACH_DOCUMENT)
                                        if (message.text.isNotBlank() && message.drawing == null) add(MessageAction.DRAW)
                                        add(MessageAction.EDIT)
                                        add(if (message.hidden || message.role == "system" && message.toolTurn == null)
                                            MessageAction.UNHIDE else MessageAction.HIDE)
                                        if (message.role == "assistant") add(MessageAction.EDIT_REASONING)
                                        if (message.role == "assistant" && message.variants.size > 1) add(MessageAction.ALTERNATIVES)
                                        add(MessageAction.DUPLICATE)
                                        if (index > 0) add(MessageAction.UP)
                                        if (index < chat.messages.lastIndex) add(MessageAction.DOWN)
                                        if (!chat.isTemporary) {
                                            add(MessageAction.BRANCH)
                                            add(MessageAction.CHECKPOINT)
                                        }
                                        if (openCheckpoint != null) add(MessageAction.OPEN_CHECKPOINT)
                                        if (linkedBranches.isNotEmpty()) add(MessageAction.OPEN_BRANCHES)
                                        add(MessageAction.DELETE)
                                    }) { action ->
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
                                        regex = regex, depth = 0)
                                }
                            }
                        }
                        item { Spacer(Modifier.height(2.dp)) }
                    }
                }
            }

            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 1.dp) {
                val selectedMessages = selected
                if (selectedMessages != null) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("已选 ${selectedMessages.size} 条", Modifier.weight(1f))
                        TextButton({ selected = null }) { Text("取消") }
                        Button({ deleting = selectedMessages }, enabled = selectedMessages.isNotEmpty()) { Text("删除") }
                    }
                } else {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                        if (quickButtons.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(quickButtons, key = { (set, item) -> "${set.id}:${item.id}" }) { (set, item) ->
                                AssistChip(
                                    onClick = { if (model.runQuickReply(set, item, draft)) keyboard?.hide() },
                                    enabled = !busy && dictation == null,
                                    label = { Text(item.label) },
                                )
                            }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("以 ${state.persona.name} 发言", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (group != null) FilterChip(selected = auto, onClick = model::toggleAuto,
                                enabled = auto || (!busy && dictation == null && configured && group.participants(state.cards).isNotEmpty()),
                                label = { Text(if (auto) "关闭自动发言" else "自动发言") })
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            IconButton({ pickImages.launch(arrayOf("image/*")) }, enabled = !busy && dictation == null && attachedImages.size < MAX_CHAT_IMAGES) {
                                Icon(Icons.Outlined.AddPhotoAlternate, "添加图片")
                            }
                            IconButton({ pickDocumentsForMessage = null; pickDocuments.launch(arrayOf("*/*")) },
                                enabled = !busy && dictation == null && attachedDocuments.size < MAX_CHAT_DOCUMENTS) {
                                Icon(Icons.Outlined.AttachFile, "添加文档")
                            }
                            IconButton({ keyboard?.hide(); animeDrawing = true }, enabled = !busy && dictation == null) {
                                Icon(Icons.Outlined.AutoAwesome, "生成图片")
                            }
                            if (state.prefs.voice.dictationEnabled) DictationButton(state.prefs.voice, !busy,
                                pausedPlayback = { model.stopAuto(); model.stopNarration(); keyboard?.hide() },
                                onTranscript = { recognized ->
                                    val result = state.prefs.voice.dictated(recognized, draft)
                                    if (result.send && model.send(result.text, interpretCommands = result.shortcut)) {
                                        draft = ""
                                    } else draft = result.text
                                }, onError = model::notice, onProgress = { dictation = it })
                            Spacer(Modifier.weight(1f))
                            if (state.speaking) TextButton(model::stopNarration) {
                                Icon(Icons.Outlined.Stop, null)
                                Text("停止朗读")
                            }
                        }
                        dictation?.let { progress ->
                            Text(progress.partial.ifBlank { if (progress.finishing) "正在转写…" else "正在听…" },
                                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(bottom = 8.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        if (attachedImages.isNotEmpty() || attachedDocuments.isNotEmpty()) LazyRow(Modifier.padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            items(attachedImages, key = { "image:${it.id}" }) { image ->
                                DraftImageTile(image, model.imageFile(image), !busy, { previewImage = CaptionImageTarget(image) },
                                    { model.removeDraftImage(chat.id, image) })
                            }
                            items(attachedDocuments, key = { "document:${it.id}" }) { document ->
                                ChatDocumentChip(document, { previewDocument = OpenChatDocument(document, null) },
                                    { model.removeDraftDocument(chat.id, document) }, !busy)
                            }
                        }
                        if (state.generatingImage) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("正在生成图片…", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 4.dp))
                        }
                        (state.utility ?: state.toolProgress?.takeUnless { state.generatingImage })?.let {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(it, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 4.dp))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val input = generation?.text?.takeIf { impersonating && it.isNotBlank() } ?: draft
                            OutlinedTextField(input, { draft = it; model.stopAuto() }, modifier = Modifier.weight(1f),
                                readOnly = impersonating || state.scriptRunning || state.utility != null,
                                placeholder = { Text(if (impersonating) "正在代写…" else "输入消息") },
                                shape = RoundedCornerShape(24.dp), maxLines = 5)
                            if (generation != null || state.scriptRunning || state.generatingImage || state.utility != null) {
                                FilledTonalIconButton(onClick = model::stop, modifier = Modifier.size(48.dp)) {
                                    Icon(Icons.Outlined.Stop, when {
                                        state.scriptRunning -> "停止脚本"
                                        state.generatingImage -> "停止图像生成"
                                        state.utility != null -> "停止当前操作"
                                        else -> "停止生成"
                                    })
                                }
                            } else {
                                FilledIconButton(enabled = (canSend || draft.trimStart().startsWith('/')) && !busy && dictation == null &&
                                    (draft.isNotBlank() || attachedImages.isNotEmpty() || attachedDocuments.isNotEmpty()),
                                    modifier = Modifier.size(48.dp),
                                    onClick = { if (model.send(draft)) { draft = ""; keyboard?.hide() } }) {
                                    Icon(Icons.AutoMirrored.Outlined.Send, "发送")
                                }
                            }
                        }
                    }
                }
            }
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
    if (vectorTools) VectorIndexDialog(state, model, { vectorTools = false }, configure)
    if (dataBank) DataBankDialog(state, model, close = { dataBank = false }, configure = { dataBank = false; configure() })
    if (modelPicker) {
        ChatModelPicker(state, chat, dismiss = { modelPicker = false }, refresh = {
            model.loadModels(it, persistCatalog = true)
        }) { selected ->
            modelPicker = false
            model.switchModel(selected)
        }
    }
    if (quickPicker) ChatQuickReplySets(state, model) { quickPicker = false }

    if (speakerPicker) {
        AlertDialog(onDismissRequest = { speakerPicker = false }, title = { Text("选择发言成员") },
            text = {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(members, key = Card::id) { member ->
                        TextButton(onClick = {
                            speakerPicker = false
                            keyboard?.hide()
                            model.speakAs(member)
                        }, modifier = Modifier.fillMaxWidth()) { Text(member.name, Modifier.weight(1f)) }
                    }
                }
            }, confirmButton = { TextButton({ speakerPicker = false }) { Text("关闭") } })
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
        AlertDialog(onDismissRequest = { branches = null }, title = { Text("消息分支") },
            text = {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(chats, key = ChatSummary::id) { entry ->
                        TextButton({ branches = null; model.openChat(entry.id) }) { Text(entry.title) }
                    }
                }
            }, confirmButton = { TextButton({ branches = null }) { Text("关闭") } })
    }
}

@Composable
private fun ChatPromptOverridesDialog(options: ConversationOptions, busy: Boolean, close: () -> Unit,
                                      save: (String, String, String) -> Unit) {
    var scenario by rememberSaveable { mutableStateOf(options.scenario) }
    var examples by rememberSaveable { mutableStateOf(options.exampleOverride) }
    var system by rememberSaveable { mutableStateOf(options.systemPromptOverride) }
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text("场景与提示覆盖") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("留空使用角色卡的设置。")
                OutlinedTextField(scenario, { scenario = it }, Modifier.fillMaxWidth(), label = { Text("场景") },
                    enabled = !busy, minLines = 2, maxLines = 4)
                OutlinedTextField(examples, { examples = it }, Modifier.fillMaxWidth(), label = { Text("对话示例") },
                    enabled = !busy, minLines = 2, maxLines = 4)
                OutlinedTextField(system, { system = it }, Modifier.fillMaxWidth(), label = { Text("系统提示") },
                    enabled = !busy, minLines = 2, maxLines = 4)
            }
        },
        dismissButton = { TextButton(close, enabled = !busy) { Text("取消") } },
        confirmButton = { Button({ save(scenario, examples, system) }, enabled = !busy) { Text("保存") } },
    )
}

@Composable
private fun ChatSearch(query: String, change: (String) -> Unit, count: Int, position: Int,
                       navigate: (Int) -> Unit, close: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            OutlinedTextField(query, change, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("搜索消息或发言者") },
                trailingIcon = { ActionIcon(Icons.Outlined.Close, "关闭搜索", action = close) })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (count == 0) "0 条匹配" else "${position + 1} / $count", Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium)
                ActionIcon(Icons.Outlined.KeyboardArrowUp, "上一条匹配", position > 0) { navigate(position - 1) }
                ActionIcon(Icons.Outlined.KeyboardArrowDown, "下一条匹配", position < count - 1) { navigate(position + 1) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatModelPicker(state: TavernState, chat: Chat, dismiss: () -> Unit,
                            refresh: (Provider) -> Unit, select: (ModelSelection?) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val active = state.prefs.connectionFor(chat)
    val default = state.prefs.connectionFor()
    val providers = state.prefs.providers.filter { it.ready }
    ModalBottomSheet(onDismissRequest = dismiss) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 540.dp),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            item {
                Text("切换此对话的模型", style = MaterialTheme.typography.titleLarge)
                Field("搜索提供商或模型", query, { query = it })
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (chat.modelOverride != null && default != null) {
                    TextButton({ select(null) }, enabled = !state.busy) {
                        Text("使用默认模型 · ${default.model}")
                    }
                }
            }
            providers.forEach { provider ->
                val options = provider.choices.filter { it.contains(query.trim(), true) || provider.name.contains(query.trim(), true) }
                if (options.isEmpty()) return@forEach
                item(key = "provider:${provider.id}") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(provider.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        ActionIcon(Icons.Outlined.Refresh, "刷新 ${provider.name} 模型", !state.busy) { refresh(provider) }
                    }
                }
                items(options, key = { "model:${provider.id}:$it" }) { candidate ->
                    val selected = active?.provider?.id == provider.id && active.model == candidate
                    TextButton({ select(ModelSelection(provider.id, candidate)) },
                        enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(candidate, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (selected) Icon(Icons.Outlined.Check, "当前模型")
                    }
                }
            }
        }
    }
}

@Composable
private fun TextDialog(
    title: String, label: String, initial: String, dismiss: () -> Unit, lines: Int = 1,
    allowBlank: Boolean = false, save: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) },
        text = { Field(label, text, { text = it }, lines = lines) },
        dismissButton = { TextButton(dismiss) { Text("取消") } },
        confirmButton = { TextButton({ save(text) }, enabled = allowBlank || text.isNotBlank()) { Text("保存") } })
}

private enum class MessageAction(val label: String, val icon: ImageVector) {
    NARRATE("朗读", Icons.AutoMirrored.Outlined.VolumeUp),
    TRANSLATE("翻译消息", Icons.Outlined.Translate), SHOW_ORIGINAL("显示原文", Icons.Outlined.Translate),
    ATTACH_DOCUMENT("为消息附加文档", Icons.Outlined.AttachFile),
    DRAW("用这条消息生成图片", Icons.Outlined.Brush),
    EDIT("编辑", Icons.Outlined.Edit), EDIT_REASONING("编辑推理", Icons.Outlined.EditNote),
    HIDE("从模型上下文隐藏", Icons.Outlined.VisibilityOff),
    UNHIDE("加入模型上下文", Icons.Outlined.Visibility),
    DUPLICATE("在下方复制一条", Icons.Outlined.Add),
    UP("上移", Icons.Outlined.ArrowUpward), DOWN("下移", Icons.Outlined.ArrowDownward),
    BRANCH("从这里创建分支", Icons.AutoMirrored.Outlined.CallSplit), CHECKPOINT("保存检查点", Icons.Outlined.Flag),
    OPEN_CHECKPOINT("打开检查点", Icons.Outlined.BookmarkBorder), OPEN_BRANCHES("查看已有分支", Icons.Outlined.AccountTree),
    ALTERNATIVES("查看备选回复", Icons.Outlined.CompareArrows),
    DELETE("删除", Icons.Outlined.Delete),
}

@Composable
private fun ChatBubble(
    message: Message, enabled: Boolean = false, swipe: (Int) -> Unit = {}, avatar: File? = null,
    openAlternatives: (() -> Unit)? = null,
    generateAlternative: (() -> Unit)? = null, actions: List<MessageAction> = emptyList(), highlighted: Boolean = false,
    regex: RegexRuntime = RegexRuntime.EMPTY, depth: Int? = null,
    imageFile: ((ChatImage) -> File)? = null, previewImage: (ChatImage) -> Unit = {},
    previewDocument: (ChatDocument) -> Unit = {},
    previousDrawing: (() -> Unit)? = null, nextDrawing: (() -> Unit)? = null,
    onAction: (MessageAction) -> Unit = {},
) {
    val mine = message.role == "user"
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val background = if (mine) scheme.primaryContainer else scheme.surfaceContainer
    val foreground = if (mine) scheme.onPrimaryContainer else scheme.onSurface
    val bubble = if (mine) RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp)
        else RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp)
    val display = regexDisplay(message.displayText, message.regexSite, regex, depth)
    val reasoning = regexDisplay(message.reasoning.orEmpty(), RegexSite.REASONING, regex, depth)
    val rendered = remember(display) { display?.let(::formatted) }
    var openReasoning by rememberSaveable { mutableStateOf(false) }
    var openTools by rememberSaveable { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.TopEnd else Alignment.TopStart) {
        Surface(shape = bubble, color = background, contentColor = foreground,
            border = if (highlighted) BorderStroke(2.dp, scheme.primary) else null,
            modifier = Modifier.widthIn(max = minOf(maxWidth * 0.91f, 640.dp))) {
            Column(Modifier.padding(start = 15.dp, end = 13.dp, top = 6.dp, bottom = 13.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (avatar != null) AvatarBadge(message.name, avatar, 28.dp, true)
                    Text(message.name.ifBlank { if (message.role == "system") "系统" else if (mine) "我" else "角色" },
                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).padding(top = 6.dp, bottom = 6.dp))
                    if (actions.isNotEmpty()) {
                        Box {
                            ActionIcon(Icons.Outlined.MoreHoriz, "消息操作", enabled) { menu = true }
                            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                MenuItem("复制", Icons.Outlined.ContentCopy) {
                                    menu = false
                                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("消息", display.orEmpty()))) }
                                }
                                actions.forEach { action ->
                                    MenuItem(action.label, action.icon) { menu = false; onAction(action) }
                                }
                            }
                        }
                    }
                }
                if (!reasoning.isNullOrBlank()) {
                    TextButton({ openReasoning = !openReasoning }) { Text(if (openReasoning) "收起推理" else "查看推理") }
                    if (openReasoning) SelectionContainer {
                        Text(reasoning, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    }
                }
                if (message.hidden) Text("已从模型上下文隐藏", style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant)
                if (display?.isEmpty() == true && reasoning.isNullOrBlank() && message.displayImages.isEmpty() && message.documents.isEmpty()) {
                    Text("（空消息）", style = MaterialTheme.typography.bodyMedium)
                } else if (!rendered.isNullOrEmpty()) SelectionContainer { Text(rendered, style = MaterialTheme.typography.bodyLarge) }
                message.toolTurn?.let { turn ->
                    TextButton({ openTools = !openTools }) { Text(if (openTools) "收起调用详情" else "查看调用详情") }
                    if (openTools) SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (turn.content.isNotBlank()) Text(turn.content, style = MaterialTheme.typography.bodyMedium)
                            turn.invocations.forEach { invocation ->
                                Text(invocation.label, style = MaterialTheme.typography.labelLarge)
                                if (invocation.call.arguments.isNotBlank())
                                    Text(invocation.call.arguments, style = MaterialTheme.typography.bodySmall)
                                Text(invocation.result, style = MaterialTheme.typography.bodySmall,
                                    color = if (invocation.failed) scheme.error else scheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (imageFile != null) message.displayImages.forEach { image ->
                    MessageImage(image, imageFile(image)) { previewImage(image) }
                    message.captionFor(image)?.let { caption ->
                        Text(caption, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                }
                val drawing = message.drawing
                if (drawing != null && previousDrawing != null && nextDrawing != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val hasNext = drawing.selected < drawing.images.lastIndex
                        ActionIcon(Icons.Outlined.ChevronLeft, "上一张备选图片", enabled && drawing.selected > 0, previousDrawing)
                        Text("${drawing.selected + 1} / ${drawing.images.size}", style = MaterialTheme.typography.labelMedium)
                        ActionIcon(Icons.Outlined.ChevronRight, if (hasNext) "下一张备选图片" else "生成备选图片", enabled, nextDrawing)
                    }
                }
                if (message.documents.isNotEmpty()) Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    message.documents.forEach { document -> ChatDocumentChip(document, { previewDocument(document) }) }
                }
                if (message.variants.size > 1 || generateAlternative != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ActionIcon(Icons.Outlined.ChevronLeft, "上一条备选回复", enabled && message.index > 0) {
                            swipe(message.index - 1)
                        }
                        val count = "${message.index + 1} / ${maxOf(message.variants.size, 1)}"
                        if (openAlternatives != null && message.variants.size > 1) {
                            TextButton(openAlternatives, enabled = enabled) { Text(count) }
                        } else Text(count, style = MaterialTheme.typography.labelMedium)
                        val savedNext = message.index < message.variants.lastIndex
                        ActionIcon(Icons.Outlined.ChevronRight, if (savedNext) "下一条备选回复" else "生成备选回复",
                            enabled && (savedNext || generateAlternative != null)) {
                            if (savedNext) swipe(message.index + 1) else generateAlternative?.invoke()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun regexDisplay(text: String, site: RegexSite?, rules: RegexRuntime, depth: Int?): String? {
    if (text.isEmpty() || site == null || !rules.applies(site, RegexStage.DISPLAY)) return text
    return key(text, site, rules, depth) {
        val rendered by produceState<String?>(null) {
            value = withContext(Dispatchers.Default) { rules.transform(text, site, RegexStage.DISPLAY, depth) }
        }
        rendered
    }
}

private val inline = Regex("""(```[\s\S]*?```|`[^`\n]+`|\*\*[^*\n]+\*\*|\*[^*\n]+\*)""")

private fun formatted(text: String) = buildAnnotatedString {
    var cursor = 0
    inline.findAll(text).forEach { match ->
        append(text.substring(cursor, match.range.first))
        val part = match.value
        when {
            part.startsWith("```") -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                append(part.removeSurrounding("```").trim('\n'))
            }
            part.startsWith("`") -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(part.removeSurrounding("`")) }
            part.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part.removeSurrounding("**")) }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(part.removeSurrounding("*")) }
        }
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}
