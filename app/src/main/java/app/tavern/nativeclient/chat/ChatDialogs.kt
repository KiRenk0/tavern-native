package app.tavern.nativeclient.chat

import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tavern.nativeclient.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal enum class MessageAction(val label: String, val icon: ImageVector) {
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
    ALTERNATIVES("查看备选回复", Icons.AutoMirrored.Outlined.CompareArrows),
    DELETE("删除", Icons.Outlined.Delete),
}

internal fun messageActionsFor(
    message: Message,
    index: Int,
    chat: Chat,
    voiceEnabled: Boolean,
    hasCheckpoint: Boolean,
    hasBranches: Boolean,
): List<MessageAction> = buildList {
    if (voiceEnabled && message.text.isNotBlank()) add(MessageAction.NARRATE)
    if (message.text.isNotBlank() && message.role != "system") {
        add(if (message.translation == null) MessageAction.TRANSLATE else MessageAction.SHOW_ORIGINAL)
    }
    if (message.role != "system" && message.documents.size < MAX_CHAT_DOCUMENTS) add(MessageAction.ATTACH_DOCUMENT)
    if (message.text.isNotBlank() && message.drawing == null) add(MessageAction.DRAW)
    add(MessageAction.EDIT)
    add(if (message.hidden || message.role == "system" && message.toolTurn == null) MessageAction.UNHIDE else MessageAction.HIDE)
    if (message.role == "assistant") add(MessageAction.EDIT_REASONING)
    if (message.role == "assistant" && message.variants.size > 1) add(MessageAction.ALTERNATIVES)
    add(MessageAction.DUPLICATE)
    if (index > 0) add(MessageAction.UP)
    if (index < chat.messages.lastIndex) add(MessageAction.DOWN)
    if (!chat.isTemporary) {
        add(MessageAction.BRANCH)
        add(MessageAction.CHECKPOINT)
    }
    if (hasCheckpoint) add(MessageAction.OPEN_CHECKPOINT)
    if (hasBranches) add(MessageAction.OPEN_BRANCHES)
    add(MessageAction.DELETE)
}

@Composable
internal fun ChatOptionsMenuItems(
    chat: Chat,
    state: TavernState,
    model: TavernViewModel,
    keyboard: SoftwareKeyboardController?,
    configured: Boolean,
    group: TavernGroup?,
    members: List<Card>,
    canContinue: Boolean,
    parent: ChatSummary?,
    draft: String,
    attachedImagesCount: Int,
    appearance: ChatAppearance,
    capturedPrompt: PromptCapture?,
    closeMenu: () -> Unit,
    onContinue: () -> Unit,
    onImpersonate: () -> Unit,
    onOpenSearch: () -> Unit,
    onSaveCheckpoint: (Int) -> Unit,
    onBatchDelete: () -> Unit,
    onOpenPersona: () -> Unit,
    onOpenNote: () -> Unit,
    onOpenScenario: () -> Unit,
    onOpenWorld: () -> Unit,
    onOpenPrompt: () -> Unit,
    onOpenTranslation: () -> Unit,
    onPickCaptionImage: () -> Unit,
    onTranslateDraft: (String) -> Unit,
    onOpenMemory: () -> Unit,
    onOpenVector: () -> Unit,
    onOpenSharedMemory: () -> Unit,
    onOpenDataBank: () -> Unit,
    onOpenExpressions: () -> Unit,
    onPickBackground: () -> Unit,
    onOpenQuickReplies: () -> Unit,
    onOpenPromptInspector: () -> Unit,
    onRename: () -> Unit,
) {
    val visualNovel = state.prefs.visualNovel
    val quickConfig = state.prefs.quickReplies
    if (canContinue) MenuItem("续写最后一条回复", Icons.Outlined.PlayArrow) {
        closeMenu(); keyboard?.hide(); onContinue()
    }
    if (configured && (group == null || members.isNotEmpty())) {
        MenuItem("代写我的消息", Icons.Outlined.EditNote) {
            closeMenu(); keyboard?.hide(); onImpersonate()
        }
    }
    if (parent != null) MenuItem("返回父对话", Icons.AutoMirrored.Outlined.Undo) {
        closeMenu(); model.openChat(parent.id)
    }
    if (chat.messages.isNotEmpty()) {
        MenuItem("查找消息", Icons.Outlined.Search) {
            closeMenu(); onOpenSearch()
        }
        if (!chat.isTemporary) MenuItem("保存检查点", Icons.Outlined.Flag) {
            closeMenu(); onSaveCheckpoint(chat.messages.lastIndex)
        }
        MenuItem("批量删除消息", Icons.Outlined.DeleteSweep) {
            closeMenu(); onBatchDelete()
        }
    }
    MenuItem("对话人设", Icons.Outlined.Person) { closeMenu(); onOpenPersona() }
    MenuItem("作者注释", Icons.AutoMirrored.Outlined.StickyNote2) { closeMenu(); onOpenNote() }
    MenuItem("场景与提示覆盖", Icons.Outlined.Landscape) { closeMenu(); onOpenScenario() }
    MenuItem("对话世界书", Icons.AutoMirrored.Outlined.MenuBook) { closeMenu(); onOpenWorld() }
    MenuItem("提示词预设", Icons.Outlined.Tune) { closeMenu(); onOpenPrompt() }
    MenuItem("对话翻译", Icons.Outlined.Translate) { closeMenu(); onOpenTranslation() }
    if (attachedImagesCount < MAX_CHAT_IMAGES) MenuItem("从文件生成图片描述", Icons.Outlined.ImageSearch) {
        closeMenu(); onPickCaptionImage()
    }
    if (draft.isNotBlank()) MenuItem("翻译输入", Icons.Outlined.Translate) {
        closeMenu(); model.translateDraft(draft, onTranslateDraft)
    }
    MenuItem("摘要记忆", Icons.Outlined.Summarize) { closeMenu(); onOpenMemory() }
    if (!chat.isTemporary) {
        MenuItem("向量索引", Icons.AutoMirrored.Outlined.ManageSearch) { closeMenu(); onOpenVector() }
        MenuItem("跨聊角色记忆", Icons.Outlined.Psychology) { closeMenu(); onOpenSharedMemory() }
        MenuItem("资料库", Icons.AutoMirrored.Outlined.Article) { closeMenu(); onOpenDataBank() }
        MenuItem("图库", Icons.Outlined.PhotoLibrary) { closeMenu(); model.showImageGallery() }
        MenuItem("角色表情", Icons.Outlined.Mood) { closeMenu(); onOpenExpressions() }
    }
    MenuItem(if (visualNovel) "关闭视觉小说模式" else "开启视觉小说模式", Icons.Outlined.AutoStories) {
        closeMenu(); model.setVisualNovel(!visualNovel)
    }
    MenuItem(
        if (appearance.layoutMode == ChatLayoutMode.BUBBLE) "切换为文档小说模式" else "切换为气泡模式",
        Icons.AutoMirrored.Outlined.Article,
    ) {
        closeMenu()
        val nextMode = if (appearance.layoutMode == ChatLayoutMode.BUBBLE) {
            ChatLayoutMode.DOCUMENT
        } else ChatLayoutMode.BUBBLE
        model.setChatAppearance(appearance.copy(layoutMode = nextMode))
    }
    MenuItem("从文件设置对话背景", Icons.Outlined.AddPhotoAlternate) {
        closeMenu(); onPickBackground()
    }
    if (chat.options.background != null) MenuItem("清除对话背景", Icons.Outlined.HideImage) {
        closeMenu(); model.clearDrawingBackground(chat.id)
    }
    if (quickConfig.sets.isNotEmpty()) MenuItem("快捷回复集", Icons.Outlined.Bolt) {
        closeMenu(); onOpenQuickReplies()
    }
    if (capturedPrompt != null) MenuItem("查看最近请求的提示词", Icons.AutoMirrored.Outlined.ListAlt) {
        closeMenu(); onOpenPromptInspector()
    }
    MenuItem("重命名聊天", Icons.Outlined.Edit) { closeMenu(); onRename() }
}

@Composable
internal fun ChatPromptOverridesDialog(
    options: ConversationOptions,
    busy: Boolean,
    close: () -> Unit,
    save: (String, String, String) -> Unit,
) {
    var scenario by rememberSaveable { mutableStateOf(options.scenario) }
    var examples by rememberSaveable { mutableStateOf(options.exampleOverride) }
    var system by rememberSaveable { mutableStateOf(options.systemPromptOverride) }
    AlertDialog(
        onDismissRequest = { if (!busy) close() },
        title = { Text("场景与提示覆盖") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("留空使用角色卡的设置。")
                OutlinedTextField(
                    scenario, { scenario = it }, Modifier.fillMaxWidth(),
                    label = { Text("场景") }, enabled = !busy, minLines = 2, maxLines = 4,
                )
                OutlinedTextField(
                    examples, { examples = it }, Modifier.fillMaxWidth(),
                    label = { Text("对话示例") }, enabled = !busy, minLines = 2, maxLines = 4,
                )
                OutlinedTextField(
                    system, { system = it }, Modifier.fillMaxWidth(),
                    label = { Text("系统提示") }, enabled = !busy, minLines = 2, maxLines = 4,
                )
            }
        },
        dismissButton = { TextButton(close, enabled = !busy) { Text("取消") } },
        confirmButton = { Button({ save(scenario, examples, system) }, enabled = !busy) { Text("保存") } },
    )
}

@Composable
internal fun ChatSearch(
    query: String,
    change: (String) -> Unit,
    count: Int,
    position: Int,
    navigate: (Int) -> Unit,
    close: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            OutlinedTextField(
                query, change, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("搜索消息或发言者") },
                trailingIcon = { ActionIcon(Icons.Outlined.Close, "关闭搜索", action = close) },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (count == 0) "0 条匹配" else "${position + 1} / $count",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                )
                ActionIcon(Icons.Outlined.KeyboardArrowUp, "上一条匹配", position > 0) { navigate(position - 1) }
                ActionIcon(Icons.Outlined.KeyboardArrowDown, "下一条匹配", position < count - 1) { navigate(position + 1) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatModelPicker(
    state: TavernState,
    chat: Chat,
    dismiss: () -> Unit,
    refresh: (Provider) -> Unit,
    selectProfile: (String?) -> Unit,
    select: (ModelSelection?) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val active = state.prefs.connectionFor(chat)
    val default = state.prefs.connectionFor()
    val activeProfile = state.prefs.activeProfile(chat)
    val profiles = state.prefs.connectionProfiles
    val providers = state.prefs.providers.filter { it.ready }
    ModalBottomSheet(onDismissRequest = dismiss) {
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 540.dp),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        ) {
            item {
                Text("切换此对话的模型", style = MaterialTheme.typography.titleLarge)
                if (profiles.isNotEmpty()) {
                    Text(
                        "连接配置档案",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (chat.options.connectionProfileId != null) {
                            item(key = "profile:clear") {
                                FilterChip(
                                    selected = false,
                                    onClick = { selectProfile(null) },
                                    enabled = !state.busy,
                                    label = { Text("恢复默认档案") },
                                )
                            }
                        }
                        items(profiles, key = ConnectionProfile::id) { profile ->
                            val isSelected = activeProfile?.id == profile.id && chat.modelOverride == null
                            FilterChip(
                                selected = isSelected,
                                onClick = { selectProfile(profile.id) },
                                enabled = !state.busy,
                                label = { Text(profile.name) },
                            )
                        }
                    }
                }
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
                    TextButton(
                        { select(ModelSelection(provider.id, candidate)) },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(candidate, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (selected) Icon(Icons.Outlined.Check, "当前模型")
                    }
                }
            }
        }
    }
}

@Composable
internal fun TextDialog(
    title: String,
    label: String,
    initial: String,
    dismiss: () -> Unit,
    lines: Int = 1,
    allowBlank: Boolean = false,
    save: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = { Field(label, text, { text = it }, lines = lines) },
        dismissButton = { TextButton(dismiss) { Text("取消") } },
        confirmButton = { TextButton({ save(text) }, enabled = allowBlank || text.isNotBlank()) { Text("保存") } },
    )
}

@Composable
internal fun ChatSpeakerPickerDialog(
    members: List<Card>,
    dismiss: () -> Unit,
    select: (Card) -> Unit,
) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("选择发言成员") },
        text = {
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(members, key = Card::id) { member ->
                    TextButton(onClick = { select(member) }, modifier = Modifier.fillMaxWidth()) {
                        Text(member.name, Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = { TextButton(dismiss) { Text("关闭") } },
    )
}

@Composable
internal fun ChatBranchesDialog(
    chats: List<ChatSummary>,
    dismiss: () -> Unit,
    open: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("消息分支") },
        text = {
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(chats, key = ChatSummary::id) { entry ->
                    TextButton({ open(entry.id) }) { Text(entry.title) }
                }
            }
        },
        confirmButton = { TextButton(dismiss) { Text("关闭") } },
    )
}

@Composable
internal fun ChatSharedMemorySheet(
    state: TavernState,
    chat: Chat,
    members: List<Card>,
    busy: Boolean,
    model: TavernViewModel,
    dismiss: () -> Unit,
) {
    val activeCard = state.card ?: members.firstOrNull()
    val activeWorld = activeCard?.let { chat.memoryWorld(it, state.books) }
    SharedWorldMemoryDialog(
        world = activeWorld,
        card = activeCard,
        recall = state.lastSharedMemory,
        busy = busy,
        dismiss = dismiss,
        refresh = {
            if (activeCard != null) {
                val targetWorld = chat.memoryWorld(activeCard, state.books)
                if (targetWorld != null) model.inspectSharedWorldMemory(targetWorld, activeCard, chat)
            }
        },
        pinItem = { w, c, m -> model.pinSharedMemory(w, c, m) },
        unpinItem = { w, c, id -> model.unpinSharedMemory(w, c, id) },
        editItem = { w, c, key, txt -> model.editSharedMemory(w, c, key, txt) },
        blockItem = { w, c, key, blk -> model.blockSharedMemory(w, c, key, blk) },
        promoteItem = { w, c, txt, src -> model.promoteSharedMemoryToLore(w, c, txt, src) },
    )
}

@Composable
internal fun ChatBubble(
    message: Message,
    enabled: Boolean = false,
    swipe: (Int) -> Unit = {},
    avatar: File? = null,
    openAlternatives: (() -> Unit)? = null,
    generateAlternative: (() -> Unit)? = null,
    actions: List<MessageAction> = emptyList(),
    highlighted: Boolean = false,
    regex: RegexRuntime = RegexRuntime.EMPTY,
    depth: Int? = null,
    appearance: ChatAppearance = ChatAppearance(),
    imageFile: ((ChatImage) -> File)? = null,
    previewImage: (ChatImage) -> Unit = {},
    previewDocument: (ChatDocument) -> Unit = {},
    previousDrawing: (() -> Unit)? = null,
    nextDrawing: (() -> Unit)? = null,
    onAction: (MessageAction) -> Unit = {},
) {
    val mine = message.role == "user"
    val documentMode = appearance.layoutMode == ChatLayoutMode.DOCUMENT
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val background = when {
        documentMode && mine -> scheme.primaryContainer.copy(alpha = 0.55f)
        documentMode -> scheme.surfaceContainerLow
        mine -> scheme.primaryContainer
        else -> scheme.surfaceContainer
    }
    val foreground = if (mine) scheme.onPrimaryContainer else scheme.onSurface
    val shape = when {
        documentMode -> RoundedCornerShape(14.dp)
        mine -> RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp)
        else -> RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp)
    }
    val display = regexDisplay(message.displayText, message.regexSite, regex, depth)
    val reasoning = regexDisplay(message.reasoning.orEmpty(), RegexSite.REASONING, regex, depth)
    var openReasoning by rememberSaveable { mutableStateOf(false) }
    var openTools by rememberSaveable { mutableStateOf(false) }

    BoxWithConstraints(
        Modifier.fillMaxWidth(),
        contentAlignment = if (documentMode || !mine) Alignment.TopStart else Alignment.TopEnd,
    ) {
        val cardModifier = if (documentMode) Modifier.fillMaxWidth()
        else Modifier.widthIn(max = minOf(maxWidth * 0.91f, 640.dp))
        Surface(
            shape = shape,
            color = background,
            contentColor = foreground,
            border = when {
                highlighted -> BorderStroke(2.dp, scheme.primary)
                documentMode -> BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.6f))
                else -> null
            },
            modifier = cardModifier,
        ) {
            Column(Modifier.padding(start = 15.dp, end = 13.dp, top = 6.dp, bottom = 13.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (avatar != null) AvatarBadge(message.name, avatar, 28.dp, true)
                    Text(
                        message.name.ifBlank { if (message.role == "system") "系统" else if (mine) "我" else "角色" },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).padding(top = 6.dp, bottom = 6.dp),
                    )
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
                if (message.hidden) Text(
                    "已从模型上下文隐藏",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
                if (display?.isEmpty() == true && reasoning.isNullOrBlank() && message.displayImages.isEmpty() && message.documents.isEmpty()) {
                    Text("（空消息）", style = MaterialTheme.typography.bodyMedium)
                } else if (!display.isNullOrEmpty()) {
                    RichMessageContent(display, appearance, textColor = foreground)
                }
                message.toolTurn?.let { turn ->
                    TextButton({ openTools = !openTools }) { Text(if (openTools) "收起调用详情" else "查看调用详情") }
                    if (openTools) SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (turn.content.isNotBlank()) Text(turn.content, style = MaterialTheme.typography.bodyMedium)
                            turn.invocations.forEach { invocation ->
                                Text(invocation.label, style = MaterialTheme.typography.labelLarge)
                                if (invocation.call.arguments.isNotBlank())
                                    Text(invocation.call.arguments, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    invocation.result,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (invocation.failed) scheme.error else scheme.onSurfaceVariant,
                                )
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
                        ActionIcon(
                            Icons.Outlined.ChevronRight,
                            if (savedNext) "下一条备选回复" else "生成备选回复",
                            enabled && (savedNext || generateAlternative != null),
                        ) {
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
