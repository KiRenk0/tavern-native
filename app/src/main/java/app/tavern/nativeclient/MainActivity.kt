@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.tavern.nativeclient

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private val pendingShareIntent = mutableStateOf<Intent?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (ShareImportHandler.isShareIntent(intent)) {
            setIntent(intent)
            pendingShareIntent.value = intent
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ShareImportHandler.isShareIntent(intent)) {
            pendingShareIntent.value = intent
        }
        enableEdgeToEdge()
        setContent {
            val model: TavernViewModel = viewModel { TavernViewModel(application) }
            val state by model.state.collectAsStateWithLifecycle()
            val shareIntent by pendingShareIntent
            LaunchedEffect(shareIntent) {
                val incoming = shareIntent ?: return@LaunchedEffect
                pendingShareIntent.value = null
                val plan = ShareImportHandler.buildImportPlan(
                    intent = incoming,
                    resolveName = { uri -> applicationContext.documentName(uri) },
                    resolveMime = { uri -> contentResolver.getType(uri) },
                )
                model.handleSharedImport(plan)
            }
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            DisposableEffect(lifecycle, model) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> {
                            model.voiceForeground(true)
                            model.audioEngine.setAppForeground(true, model.current.prefs.extensions.dynamicAudio)
                        }
                        Lifecycle.Event.ON_STOP -> {
                            model.stopAuto()
                            model.voiceForeground(false)
                            model.audioEngine.setAppForeground(false, model.current.prefs.extensions.dynamicAudio)
                        }
                        else -> Unit
                    }
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }
            val systemDark = isSystemInDarkTheme()
            val dark = when (state.prefs.theme) {
                "dark" -> true
                "light" -> false
                else -> systemDark
            }
            TavernTheme(dark, state.prefs.dynamicColor, state.prefs.chatAppearance.palette) {
                val lightBars = MaterialTheme.colorScheme.surface.luminance() >= 0.30f
                SideEffect {
                    androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = lightBars
                        isAppearanceLightNavigationBars = lightBars
                    }
                }
                CompositionLocalProvider(LocalSelectInputOnFocus provides state.prefs.chatHandling.selectInputOnFocus) {
                    TavernApp(model, state)
                }
            }
        }
    }
}

private val light = lightColorScheme(
    primary = Color(0xFF28664B), onPrimary = Color.White,
    primaryContainer = Color(0xFFAFEFCC), onPrimaryContainer = Color(0xFF073824),
    secondary = Color(0xFF50645A), secondaryContainer = Color(0xFFD3E9DB),
    onSecondaryContainer = Color(0xFF23372E), tertiary = Color(0xFF7A581C),
    tertiaryContainer = Color(0xFFFFDFAB), onTertiaryContainer = Color(0xFF442C00),
    background = Color(0xFFF7FAF5), onBackground = Color(0xFF181D19),
    surface = Color(0xFFF7FAF5), onSurface = Color(0xFF181D19),
    surfaceVariant = Color(0xFFDCE6DD), onSurfaceVariant = Color(0xFF404A43),
    outline = Color(0xFF707B72), outlineVariant = Color(0xFFBFCAC0),
    surfaceContainerLow = Color(0xFFF0F4EE), surfaceContainer = Color(0xFFEAF0E9),
    surfaceContainerHigh = Color(0xFFE3EAE2), surfaceContainerHighest = Color(0xFFDEE6DD),
)

private val darkPalette = darkColorScheme(
    primary = Color(0xFF94D3B1), onPrimary = Color(0xFF003825),
    primaryContainer = Color(0xFF154E37), onPrimaryContainer = Color(0xFFAFEFCC),
    secondary = Color(0xFFB7CDC0), secondaryContainer = Color(0xFF35493E),
    onSecondaryContainer = Color(0xFFD3E9DB), tertiary = Color(0xFFEDC177),
    tertiaryContainer = Color(0xFF60410B), onTertiaryContainer = Color(0xFFFFDFAB),
    background = Color(0xFF111713), onBackground = Color(0xFFE0E8E0),
    surface = Color(0xFF111713), onSurface = Color(0xFFE0E8E0),
    surfaceVariant = Color(0xFF3F4A42), onSurfaceVariant = Color(0xFFBFCAC0),
    outline = Color(0xFF89958C), outlineVariant = Color(0xFF3F4A42),
    surfaceContainerLow = Color(0xFF181E1A), surfaceContainer = Color(0xFF1C241E),
    surfaceContainerHigh = Color(0xFF263028), surfaceContainerHighest = Color(0xFF303B32),
)

@Composable
private fun TavernTheme(dark: Boolean, dynamic: Boolean, palette: ThemePalette, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val defaultSurface = if (dark) darkPalette.surface else light.surface
    val materialDark = palette.color(ThemeColor.PANEL)?.compositeOver(defaultSurface)?.let { it.luminance() < 0.30f } ?: dark
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 ->
            if (materialDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        materialDark -> darkPalette
        else -> light
    }
    MaterialTheme(colorScheme = scheme.withThemePalette(palette), content = content)
}

@Composable
private fun TavernApp(model: TavernViewModel, state: TavernState) {
    var tab by rememberSaveable { mutableStateOf("home") }
    var welcomeDraft by rememberSaveable { mutableStateOf("") }
    var editor by rememberSaveable { mutableStateOf<String?>(null) }
    var groupEditor by rememberSaveable { mutableStateOf<String?>(null) }
    var server by rememberSaveable { mutableStateOf(false) }
    var chatSettings by rememberSaveable { mutableStateOf(false) }
    var showChatBackups by rememberSaveable { mutableStateOf(false) }
    var showChatFiles by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var deleteCard by remember { mutableStateOf(false) }
    var deleteGroup by remember { mutableStateOf(false) }
    var deleteChat by remember { mutableStateOf<ChatSummary?>(null) }
    var transferChat by rememberSaveable { mutableStateOf(false) }
    var chooseChatImport by rememberSaveable { mutableStateOf(false) }
    var expressions by rememberSaveable { mutableStateOf(false) }
    var libraryTagManager by rememberSaveable { mutableStateOf(false) }
    var assignTags by rememberSaveable { mutableStateOf(emptyArray<String>()) }
    var importLinks by rememberSaveable { mutableStateOf(false) }
    var cardLoreTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var replaceCard by rememberSaveable { mutableStateOf(false) }
    var replaceLink by rememberSaveable { mutableStateOf(false) }
    var subjectStatistics by rememberSaveable { mutableStateOf(false) }
    var searchArchive by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val chatUiState = rememberSaveableStateHolder()
    val context = LocalContext.current
    val card = state.card
    val group = state.group
    val subject = card?.name ?: group?.name
    val chat = state.chat
    val busy = state.busy || state.generation != null || state.scriptRunning
    val library = remember(state.prefs.library, state.cards) { state.prefs.library.materialized(state.cards) }
    val libraryOwner = card?.let { libraryCardId(it.id) } ?: group?.let { libraryGroupId(it.id) }
    val ownerTags = remember(library, libraryOwner) {
        val ids = libraryOwner?.let(library.assignments::get).orEmpty()
        library.tags.filter { it.id in ids && !it.hidden }
    }
    state.scriptDialog?.let { dialog -> key(dialog.id) {
        val respond: (String?) -> Unit = { model.completeScriptDialog(dialog.id, it) }
        when {
            dialog.kind == "image-file" -> CaptionFileQuestion(dialog.title, respond)
            dialog.avatarBytes != null -> AvatarCropDialog(dialog.id, dialog.title, { dialog.avatarBytes },
                dismiss = { respond(null) }, accept = { respond(it.dataUrl) })
            dialog.card != null -> ScriptMemberCardDialog(dialog.card, model.portrait(dialog.card)) { respond(null) }
            else -> ScriptQuestion(dialog, respond)
        }
    } }
    state.imageGallery?.let { gallery ->
        ImageGalleryDialog(gallery, model, !busy, model::closeImageGallery,
            setBackground = chat?.let { opened -> { image -> model.setDrawingBackground(opened.id, image) } })
    }
    val editedCard = if (editor == "new") remember { Card.blank() } else state.cards.firstOrNull { it.id == editor }
    val editedGroup = if (groupEditor == "new") remember { TavernGroup() } else state.groups.firstOrNull { it.id == groupEditor }
    val isRoot = editor == null && groupEditor == null && !server && !chatSettings && !libraryTagManager && subject == null
    val tagImportMode = state.prefs.characterLibrary.tagImport
    var pendingCardFiles by rememberSaveable { mutableStateOf(emptyArray<android.net.Uri>()) }
    val importCard = rememberLauncherForActivityResult(OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            if (tagImportMode == CardTagImport.ASK) pendingCardFiles = uris.toTypedArray()
            else model.importCards(uris, tagImportMode)
        }
    }
    val replaceFile = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null && card != null && chat == null) model.updateCardFromFile(card, uri)
    }
    val importChat = rememberLauncherForActivityResult(OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty() && subject != null) model.importChats(uris)
    }
    val loadTemporary = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) model.openTemporaryChat(uri, context.documentName(uri))
    }
    val exportPng = rememberLauncherForActivityResult(CreateDocument("image/png")) { uri ->
        if (uri != null && card != null) model.exportCard(uri, card)
    }
    val exportJson = rememberLauncherForActivityResult(CreateDocument("application/json")) { uri ->
        if (uri != null && card != null) model.exportCard(uri, card)
    }
    val exportChat = rememberLauncherForActivityResult(CreateDocument("application/octet-stream")) { uri ->
        if (uri != null && subject != null && chat != null) model.exportChat(uri, chat, subject)
    }
    val exportArchive = rememberLauncherForActivityResult(CreateDocument("application/zip")) { uri ->
        if (uri != null && subject != null && chat != null) model.exportChat(uri, chat, subject, withAttachments = true)
    }
    val exportChatText = rememberLauncherForActivityResult(CreateDocument("text/plain")) { uri ->
        if (uri != null && chat != null) model.exportChatText(uri, chat)
    }

    fun back() {
        if (busy) return
        when {
            editor != null -> editor = null
            groupEditor != null -> groupEditor = null
            server -> { model.clearRemote(); server = false }
            chatSettings -> chatSettings = false
            showChatBackups -> showChatBackups = false
            libraryTagManager -> libraryTagManager = false
            chat != null -> {
                chatUiState.removeState(chat.id)
                if (tab == "home" || chat.isTemporary) model.closeSubject() else model.closeChat()
            }
            subject != null -> model.closeSubject()
            tab != "home" -> tab = "home"
        }
    }

    BackHandler(enabled = !isRoot || tab != "home") { back() }
    LaunchedEffect(isRoot, tab, state.busy, state.prefs.welcome.showRecent) {
        if (isRoot && tab == "home" && !state.busy && state.prefs.welcome.showRecent) model.refreshRecentChats()
    }
    LaunchedEffect(isRoot, tab) {
        if (isRoot && (tab == "library" || tab == "groups")) model.refreshLibraryUsage()
    }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(it, withDismissAction = true)
            if (model.state.value.notice == it) model.notice(null)
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = {
            if (state.prefs.chatAppearance.noticePosition == NoticePosition.BOTTOM_CENTER) SnackbarHost(snackbar)
        },
        topBar = {
            TopAppBar(
                title = {
                    val title = when {
                        editor == "new" -> "新建角色"
                        editor != null -> "编辑角色"
                        groupEditor == "new" -> "新建群组"
                        groupEditor != null -> "编辑群组"
                        server -> "SillyTavern 数据迁移"
                        chatSettings -> "设置"
                        showChatBackups -> "聊天备份"
                        libraryTagManager -> "管理标签"
                        chat?.isTemporary == true -> "临时聊天"
                        chat != null -> subject.orEmpty()
                        card != null -> "角色详情"
                        group != null -> "群组详情"
                        tab == "groups" -> "群组"
                        tab == "library" -> "角色库"
                        tab == "world" -> "世界书"
                        tab == "settings" -> "设置"
                        else -> "Tavern Native"
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (group != null && chat != null && !chatSettings && !showChatBackups && groupEditor == null) {
                            GroupPortrait(group, state.cards, model::portrait, 34.dp)
                        }
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    }
                },
                navigationIcon = {
                    if (!isRoot) ActionIcon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", !busy, ::back)
                    else if (tab == "home") {
                        Box(Modifier.padding(start = 16.dp, end = 6.dp).size(34.dp)
                            .clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.AutoStories, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                actions = {
                    if (isRoot && tab == "home") {
                        ActionIcon(Icons.Outlined.Search, "搜索聊天记录", !busy) { searchArchive = true }
                    }
                    if (chat == null && (state.scriptRunning || state.utility != null)) {
                        ActionIcon(Icons.Outlined.Stop, "停止当前操作", action = model::stop)
                    }
                    if (editor == null && groupEditor == null && !server && !chatSettings && !showChatBackups && !libraryTagManager &&
                        (tab == "library" || tab == "groups" || subject != null)) {
                        if (isRoot && tab == "library") ActionIcon(Icons.Outlined.FileDownload, "批量导入角色卡", !busy) {
                            importCard.launch(arrayOf("*/*"))
                        }
                        if (chat == null && libraryOwner != null) {
                            val favorite = library.favorites[libraryOwner] ?: (card?.libraryFavorite ?: group?.favorite ?: false)
                            LibraryFavorite(favorite, busy) { model.toggleLibraryFavorite(libraryOwner) }
                        }
                        Box {
                            ActionIcon(Icons.Outlined.MoreVert, "更多操作", !busy) { menu = true }
                            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                if (subject != null && chat?.isTemporary != true) {
                                    MenuItem("管理聊天文件", Icons.Outlined.FolderOpen) {
                                        menu = false
                                        model.stopAuto()
                                        showChatFiles = true
                                    }
                                    MenuItem("搜索历史聊天", Icons.Outlined.Search) {
                                        menu = false
                                        model.stopAuto()
                                        searchArchive = true
                                    }
                                    MenuItem("聊天备份", Icons.Outlined.Backup) {
                                        menu = false
                                        model.stopAuto()
                                        showChatBackups = true
                                    }
                                }
                                if (chat != null) {
                                    MenuItem("导出聊天 JSONL", Icons.Outlined.FileUpload) {
                                        menu = false; exportChat.launch("${safeFilename(chat.title)}.jsonl")
                                    }
                                    if (chat.options.background != null || chat.messages.any {
                                            it.imageAssets().isNotEmpty() || it.documents.isNotEmpty() || it.videos.isNotEmpty()
                                        }) MenuItem("导出聊天及附件 ZIP", Icons.Outlined.Archive) {
                                        menu = false; exportArchive.launch("${safeFilename(chat.title)}.zip")
                                    }
                                    MenuItem("导出聊天文本", Icons.Outlined.Description) {
                                        menu = false; exportChatText.launch("${safeFilename(chat.title)}.txt")
                                    }
                                    if (chat.isTemporary) MenuItem("载入临时聊天", Icons.Outlined.FileDownload) {
                                        menu = false; loadTemporary.launch(arrayOf("*/*"))
                                    }
                                    if (card != null && !chat.isTemporary) MenuItem("复制为群聊", Icons.Outlined.Groups) {
                                        menu = false
                                        model.convertToGroup { tab = "groups" }
                                    }
                                    if (card != null && !chat.isTemporary && state.groups.isNotEmpty()) MenuItem("复制到已有群组", Icons.Outlined.Groups) {
                                        menu = false; transferChat = true
                                    }
                                    if (group != null && state.cards.isNotEmpty()) MenuItem("复制到角色", Icons.Outlined.Person) {
                                        menu = false; transferChat = true
                                    }
                                } else if (card != null) {
                                    MenuItem("编辑角色", Icons.Outlined.Edit) { menu = false; editor = card.id }
                                    if (card.book != null) MenuItem("提取角色卡世界书", Icons.AutoMirrored.Outlined.MenuBook) {
                                        menu = false; cardLoreTarget = card.id
                                    }
                                    MenuItem("编辑标签", Icons.AutoMirrored.Outlined.Label) { menu = false; assignTags = arrayOf(libraryCardId(card.id)) }
                                    MenuItem("图库", Icons.Outlined.PhotoLibrary) { menu = false; model.showImageGallery() }
                                    MenuItem("角色表情", Icons.Outlined.Mood) { menu = false; expressions = true }
                                    MenuItem("单聊统计", Icons.Outlined.Info) { menu = false; subjectStatistics = true }
                                    MenuItem("转为人设", Icons.Outlined.PersonAdd) { menu = false; model.personaFromCard(card) }
                                    if (card.sourceLink != null) MenuItem("打开角色来源", Icons.Outlined.OpenInNew) {
                                        menu = false
                                        try {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(card.sourceLink)))
                                        } catch (_: android.content.ActivityNotFoundException) {
                                            model.notice("找不到可打开链接的应用")
                                        }
                                    }
                                    MenuItem("替换或更新角色卡", Icons.Outlined.Sync) { menu = false; replaceCard = true }
                                    MenuItem("复制角色卡", Icons.Outlined.ContentCopy) { menu = false; model.duplicateCard(card) }
                                    val isAssistant = state.prefs.welcome.assistantId == card.id
                                    MenuItem(if (isAssistant) "取消设为首页助手" else "设为首页助手", Icons.Outlined.Home) {
                                        menu = false; model.setWelcomeAssistant(card.takeUnless { isAssistant })
                                    }
                                    MenuItem("导入聊天记录", Icons.Outlined.FileDownload) {
                                        menu = false; chooseChatImport = true
                                    }
                                    MenuItem("导出角色卡", Icons.Outlined.FileUpload) {
                                        menu = false
                                        val filename = safeFilename(card.name)
                                        if (card.hasAvatar) exportPng.launch("$filename.png") else exportJson.launch("$filename.json")
                                    }
                                    MenuItem("删除角色", Icons.Outlined.Delete) { menu = false; deleteCard = true }
                                } else if (group != null) {
                                    MenuItem("编辑群组", Icons.Outlined.Edit) { menu = false; groupEditor = group.id }
                                    MenuItem("编辑标签", Icons.AutoMirrored.Outlined.Label) { menu = false; assignTags = arrayOf(libraryGroupId(group.id)) }
                                    MenuItem("群聊统计", Icons.Outlined.Info) { menu = false; subjectStatistics = true }
                                    MenuItem("图库", Icons.Outlined.PhotoLibrary) { menu = false; model.showImageGallery() }
                                    MenuItem("导入聊天记录", Icons.Outlined.FileDownload) {
                                        menu = false; chooseChatImport = true
                                    }
                                    MenuItem("删除群组", Icons.Outlined.Delete) { menu = false; deleteGroup = true }
                                } else {
                                    MenuItem("从分享链接导入", Icons.Outlined.Link) { menu = false; importLinks = true }
                                    MenuItem("管理标签", Icons.AutoMirrored.Outlined.Label) { menu = false; libraryTagManager = true }
                                    MenuItem("SillyTavern 数据迁移", Icons.Outlined.CloudDownload) {
                                        menu = false; server = true
                                    }
                                }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            if (isRoot) {
                NavigationBar {
                    NavigationBarItem(tab == "home", { tab = "home" }, enabled = !busy,
                        icon = { Icon(Icons.Outlined.Home, null) }, label = { Text("首页") })
                    NavigationBarItem(tab == "library", { tab = "library" }, enabled = !busy,
                        icon = { Icon(Icons.Outlined.AutoStories, null) }, label = { Text("角色库") })
                    NavigationBarItem(tab == "groups", { tab = "groups" }, enabled = !busy,
                        icon = { Icon(Icons.Outlined.Groups, null) }, label = { Text("群组") })
                    NavigationBarItem(tab == "world", { tab = "world" }, enabled = !busy,
                        icon = { Icon(Icons.AutoMirrored.Outlined.MenuBook, null) }, label = { Text("世界书") })
                    NavigationBarItem(tab == "settings", { tab = "settings" }, enabled = !busy,
                        icon = { Icon(Icons.Outlined.Settings, null) }, label = { Text("设置") })
                }
            }
        },
        floatingActionButton = {
            if (isRoot && tab == "library" && (state.cards.isNotEmpty() || state.groups.isNotEmpty()) && !busy) {
                ExtendedFloatingActionButton(
                    onClick = { editor = "new" },
                    icon = { Icon(Icons.Outlined.Add, null) },
                    text = { Text("新建角色") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            Column(Modifier.fillMaxSize()) {
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                when {
                    editedCard != null -> key(editedCard.id) {
                        CardEditor(editedCard, busy, state.books, state.prefs.characterLibrary.spoilerFree) { value ->
                            model.saveCard(value) { editor = null }
                        }
                    }
                    editedGroup != null -> key(editedGroup.id) {
                        GroupEditor(editedGroup, state, model::portrait) { value -> model.saveGroup(value) { groupEditor = null } }
                    }
                    server -> ServerScreen(state, model)
                    chatSettings -> SettingsScreen(state, model)
                    showChatBackups && subject != null -> ChatBackupScreen(state, model, subject) { showChatBackups = false }
                    libraryTagManager -> LibraryTagManager(library, model, busy)
                    chat != null && subject != null -> chatUiState.SaveableStateProvider(chat.id) {
                        ChatScreen(chat, state, model, editMember = { editor = it.id },
                            manageFiles = { showChatFiles = true }, closeChat = ::back) { chatSettings = true }
                    }
                    group != null -> GroupDetails(group, state, model::portrait, { model.newGroupChat(group) },
                        model::openChat, { deleteChat = it }, { chooseChatImport = true }, ownerTags, editMember = { editor = it.id })
                    card != null -> CardDetails(card, state.history, model.portrait(card), busy, ownerTags, state.prefs.chatAppearance,
                        spoilerFree = state.prefs.characterLibrary.spoilerFree,
                        openChat = model::openChat, remove = { deleteChat = it },
                        importHistory = { chooseChatImport = true },
                        create = { model.newChat(card, card.greeting) },
                    )
                    tab == "settings" -> SettingsScreen(state, model)
                    tab == "groups" -> chatUiState.SaveableStateProvider("group-library") {
                        OrganizedLibrary(state, library, model, busy, true, { groupEditor = "new" }) { assignTags = it }
                    }
                    tab == "world" -> chatUiState.SaveableStateProvider("world-books") { WorldBookLibrary(state, model) }
                    tab == "home" -> chatUiState.SaveableStateProvider("welcome") {
                        WelcomeScreen(state, model, busy, welcomeDraft, { welcomeDraft = it }, configure = { tab = "settings" },
                            loadTemporary = { loadTemporary.launch(arrayOf("*/*")) })
                    }
                    state.cards.isEmpty() && state.groups.isEmpty() -> EmptyLibrary(busy,
                        create = { editor = "new" }, openFiles = { importCard.launch(arrayOf("*/*")) },
                        server = { server = true }, example = model::addExample)
                    else -> chatUiState.SaveableStateProvider("character-library") {
                        OrganizedLibrary(state, library, model, busy, false, { groupEditor = "new" }) { assignTags = it }
                    }
                }
            }
            val appearance = state.prefs.chatAppearance
            if (appearance.noticePosition != NoticePosition.BOTTOM_CENTER) {
                val position = when (appearance.noticePosition) {
                    NoticePosition.TOP_LEFT -> AbsoluteAlignment.TopLeft
                    NoticePosition.TOP_CENTER -> Alignment.TopCenter
                    NoticePosition.TOP_RIGHT -> AbsoluteAlignment.TopRight
                    NoticePosition.BOTTOM_LEFT -> AbsoluteAlignment.BottomLeft
                    NoticePosition.BOTTOM_CENTER -> Alignment.BottomCenter
                    NoticePosition.BOTTOM_RIGHT -> AbsoluteAlignment.BottomRight
                }
                SnackbarHost(snackbar, Modifier.align(position).padding(8.dp).widthIn(max = 340.dp))
            }
        }
    }

    if (assignTags.isNotEmpty()) {
        AssignLibraryTags(library, assignTags.toSet(), busy, { assignTags = emptyArray() }) { changes, name ->
            model.assignLibraryTags(assignTags.toSet(), changes, name) { assignTags = emptyArray() }
        }
    }
    if (searchArchive) {
        ChatArchiveSearchDialog(ChatArchiveScope(card?.id?.takeUnless { chat?.isTemporary == true }, group?.id),
            state, model) { searchArchive = false }
    }
    if (pendingCardFiles.isNotEmpty()) {
        CardTagImportDialog(busy, { pendingCardFiles = emptyArray() }) { mode ->
            val files = pendingCardFiles.toList()
            pendingCardFiles = emptyArray()
            model.importCards(files, mode)
        }
    }
    if (importLinks) {
        CharacterLinkDialog(null, busy, { importLinks = false }, tagImportMode, model::importExternalContent)
    }
    val cardLoreIds = cardLoreTarget?.let(::listOf) ?: state.offeredCardLore
    val loreCards = state.cards.filter { it.id in cardLoreIds }
    if (loreCards.isNotEmpty()) {
        fun dismissLoreImport() {
            cardLoreTarget = null
            model.dismissCardLoreOffer()
        }
        CardLoreImportDialog(loreCards, busy, ::dismissLoreImport) { ids ->
            model.importCardLore(ids, ::dismissLoreImport)
        }
    }
    if (replaceCard && card != null && chat == null) {
        ReplaceCharacterDialog(card, busy, { replaceCard = false },
            file = { replaceCard = false; replaceFile.launch(arrayOf("*/*")) },
            link = { replaceCard = false; replaceLink = true })
    }
    if (subjectStatistics && subject != null && chat == null) {
        val label = if (group == null) "单聊统计" else "群聊统计"
        ConversationStatisticsDialog("$subject · $label", model, cardId = card?.id, groupId = group?.id) {
            subjectStatistics = false
        }
    }
    if (replaceLink && card != null && chat == null) {
        CharacterLinkDialog(card, busy, { replaceLink = false }) { address, _ -> model.updateCardFromLink(card, address) }
    }

    if (expressions && card != null) ExpressionDialog(listOf(card), null, model, state, busy) { expressions = false }
    if (showChatFiles && subject != null && chat?.isTemporary != true) ChatFileManagerDialog(state, model) { showChatFiles = false }
    if (chooseChatImport && subject != null && chat == null) {
        val fromGroup = card != null
        val sources = if (fromGroup) state.groups.map { it.id to it.name } else state.cards.map { it.id to it.name }
        ChatImportDialog(fromGroup, sources, busy,
            load = { id, loaded -> model.loadChatImportHistory(id, fromGroup, loaded) },
            importLocal = { ownerId, chatId -> model.importLocalChat(ownerId, chatId) { chooseChatImport = false } },
            importFile = { chooseChatImport = false; importChat.launch(arrayOf("*/*")) },
            close = { chooseChatImport = false })
    }
    if (transferChat && chat != null) {
        val toGroup = card != null
        val targets = if (toGroup) state.groups.map { it.id to it.name } else state.cards.map { it.id to it.name }
        ChatTransferDialog(if (toGroup) "复制到已有群组" else "复制到角色", targets, busy, { transferChat = false }) { id ->
            model.copyChatTo(id, toGroup) {
                transferChat = false
                tab = if (toGroup) "groups" else "library"
            }
        }
    }

    if (deleteCard && card != null) {
        Confirm("删除角色？", "「${card.name}」及其本地聊天和备份将被删除。", { deleteCard = false }) {
            deleteCard = false; model.deleteCard(card)
        }
    }
    if (deleteGroup && group != null) {
        Confirm("删除群组？", "「${group.name}」及其本地群聊和备份将被删除。", { deleteGroup = false }) {
            deleteGroup = false; model.deleteGroup(group)
        }
    }
    deleteChat?.takeIf { subject != null }?.let { summary ->
        Confirm("删除聊天？", "「${summary.title}」将从聊天列表移除。可在「聊天备份」里查看已保存的版本。", { deleteChat = null }) {
            deleteChat = null; model.removeChat(summary)
        }
    }
}

@Composable
internal fun ActionIcon(icon: ImageVector, label: String, enabled: Boolean = true, action: () -> Unit) {
    IconButton(action, enabled = enabled) { Icon(icon, label) }
}

@Composable
internal fun MenuItem(label: String, icon: ImageVector, enabled: Boolean = true, action: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null) }, onClick = action, enabled = enabled)
}

@Composable
internal fun Confirm(title: String, body: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(body) },
        dismissButton = { TextButton(dismiss) { Text("取消") } },
        confirmButton = { TextButton(confirm) { Text("删除", color = MaterialTheme.colorScheme.error) } })
}

@Composable
internal fun Portrait(card: Card, file: File, size: Dp = 60.dp, round: Boolean = false) {
    AvatarBadge(card.name, file.takeIf { card.hasAvatar }, size, if (round) CircleShape else RoundedCornerShape(22))
}

@Composable
internal fun AvatarBadge(name: String, file: File?, size: Dp, shape: Shape = CircleShape, width: Dp = size) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, file) {
        val path = file?.path
        value = null
        if (path == null) return@produceState
        value = withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 640) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }
    }
    Surface(
        modifier = Modifier.size(width, size), shape = shape,
        color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        val image = bitmap
        if (image != null) Image(image, null, contentScale = ContentScale.Crop)
        else Box(contentAlignment = Alignment.Center) {
            Text(name.trim().take(1).uppercase(), style = if (size > 45.dp)
                MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun ChatTransferDialog(title: String, targets: List<Pair<String, String>>, busy: Boolean,
                               close: () -> Unit, copyTo: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val matches = targets.filter { (_, name) -> name.contains(query.trim(), ignoreCase = true) }
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("搜索目标") },
                    enabled = !busy, singleLine = true)
                if (matches.isEmpty()) Text("没有匹配的目标")
                else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(matches, key = { it.first }) { (id, name) ->
                        TextButton({ copyTo(id) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text(name, Modifier.weight(1f))
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(close, enabled = !busy) { Text("取消") } })
}

@Composable
private fun ChatImportDialog(fromGroup: Boolean, owners: List<Pair<String, String>>, busy: Boolean,
                             load: (String, (List<ChatSummary>) -> Unit) -> Unit,
                             importLocal: (String, String) -> Unit, importFile: () -> Unit, close: () -> Unit) {
    var owner by remember { mutableStateOf<Pair<String, String>?>(null) }
    var chats by remember { mutableStateOf(emptyList<ChatSummary>()) }
    var query by remember { mutableStateOf("") }
    val selected = owner
    val ownerType = if (fromGroup) "群组" else "角色"
    val term = query.trim()
    val matchingOwners = owners.filter { (_, name) -> name.contains(term, ignoreCase = true) }
    val matchingChats = chats.filter { it.title.contains(term, ignoreCase = true) }
    AlertDialog(
        onDismissRequest = { if (!busy) close() },
        title = { Text(selected?.second ?: "导入聊天记录", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selected == null) {
                    OutlinedButton(importFile, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.FileDownload, null)
                        Spacer(Modifier.width(8.dp))
                        Text("选取 JSONL / JSON / ZIP 文件")
                    }
                    Text("从本机${ownerType}导入", style = MaterialTheme.typography.titleSmall)
                } else {
                    Text("导入后会新建一份聊天记录。")
                }
                if ((selected == null && owners.isNotEmpty()) || (selected != null && chats.isNotEmpty())) {
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(),
                        label = { Text(if (selected == null) "搜索$ownerType" else "搜索聊天") },
                        enabled = !busy, singleLine = true)
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (selected == null && matchingOwners.isEmpty()) Text("没有可选的$ownerType")
                if (selected != null && matchingChats.isEmpty()) {
                    Text(if (chats.isEmpty()) "这里还没有聊天记录" else "没有匹配的聊天")
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                    if (selected == null) {
                        items(matchingOwners, key = { it.first }) { source ->
                            TextButton({
                                load(source.first) { loaded ->
                                    owner = source
                                    chats = loaded
                                    query = ""
                                }
                            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                Text(source.second, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Icon(Icons.Outlined.ChevronRight, null)
                            }
                        }
                    } else {
                        items(matchingChats, key = ChatSummary::id) { entry ->
                            TextButton({ importLocal(selected.first, entry.id) }, enabled = !busy,
                                modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f)) {
                                    Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(chatHistoryDate(entry), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(Icons.Outlined.FileDownload, null)
                            }
                        }
                    }
                }
            }
        },
        dismissButton = {
            if (selected != null) TextButton({ owner = null; chats = emptyList(); query = "" }, enabled = !busy) {
                Text("返回")
            }
        },
        confirmButton = { TextButton(close, enabled = !busy) { Text("取消") } },
    )
}

@Composable
private fun EmptyLibrary(busy: Boolean, create: () -> Unit, openFiles: () -> Unit, server: () -> Unit, example: () -> Unit) {
    if (busy) return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxWidth(), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("开启第一段对话", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text("新建角色，或导入 PNG、JSON、CharX 和 BYAF 角色包。")
                        Button(create, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.Add, null)
                            Spacer(Modifier.width(8.dp))
                            Text("新建角色")
                        }
                    }
                }
            }
            item { CardImportButton(openFiles, busy) }
            item {
                TextButton(server, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.CloudDownload, null)
                    Spacer(Modifier.width(8.dp))
                    Text("SillyTavern 数据迁移")
                }
                TextButton(example, modifier = Modifier.fillMaxWidth()) { Text("试用示例角色") }
            }
        }
    }
}

@Composable
private fun CardImportButton(openFiles: () -> Unit, busy: Boolean) {
    Column {
        OutlinedButton(openFiles, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.FileDownload, null); Spacer(Modifier.width(8.dp)); Text("批量导入角色卡")
        }
        Text("在文件选择器中长按文件可多选。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ScriptMemberCardDialog(card: Card, file: File, close: () -> Unit) {
    AlertDialog(
        onDismissRequest = close,
        title = { Text(card.name) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Portrait(card, file, 80.dp)
                if (card.creator.isNotBlank()) Text(card.creator, style = MaterialTheme.typography.labelLarge)
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf(
                            "角色描述" to card.description, "性格" to card.personality, "场景" to card.scenario,
                            "开场白" to card.greetings.joinToString("\n\n"), "对话示例" to card.examples,
                            "系统提示" to card.instructions, "历史后指令" to card.postHistory,
                            "创作者备注" to card.creatorNotes,
                        ).forEach { (label, content) ->
                            if (content.isNotBlank()) {
                                Text(label, style = MaterialTheme.typography.titleSmall)
                                Text(content, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(close) { Text("关闭") } },
    )
}

@Composable
private fun CardDetails(
    card: Card, history: List<ChatSummary>, file: File, busy: Boolean, tags: List<LibraryTag>,
    appearance: ChatAppearance,
    spoilerFree: Boolean,
    openChat: (String) -> Unit, remove: (ChatSummary) -> Unit, create: () -> Unit, importHistory: () -> Unit,
) {
    var reveal by rememberSaveable(card.id) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 740.dp).fillMaxWidth(), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Portrait(card, file, 84.dp)
                        Text(card.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                        val attribution = listOfNotNull(card.creator.takeIf(String::isNotBlank),
                            card.version.takeIf(String::isNotBlank)?.let { "版本 $it" }).joinToString(" · ")
                        if (attribution.isNotBlank()) Text(attribution, style = MaterialTheme.typography.labelLarge)
                        LibraryDetailsTags(tags)
                        if (card.description.isNotBlank()) {
                            if (!spoilerFree || reveal) Text(card.description, style = MaterialTheme.typography.bodyLarge,
                                maxLines = 5, overflow = TextOverflow.Ellipsis)
                            if (spoilerFree) TextButton({ reveal = !reveal }) {
                                Text(if (reveal) "隐藏角色描述" else "显示角色描述")
                            }
                        }
                        Button(create, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.AddComment, null); Spacer(Modifier.width(8.dp)); Text("开始新聊天")
                        }
                    }
                }
            }
            if (card.creatorNotes.isNotBlank()) item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("创作者备注", style = MaterialTheme.typography.titleMedium)
                        RichMessageContent(card.creatorNotes, appearance)
                    }
                }
            }
            item { ChatHistoryImportButton(busy, importHistory) }
            item { Text("聊天记录", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp)) }
            if (history.isEmpty()) item {
                Text("这个角色还没有聊天记录。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(history, key = ChatSummary::id) { entry ->
                ChatHistoryRow(entry, busy, openChat, remove)
            }
        }
    }
}

@Composable
internal fun ChatHistoryImportButton(busy: Boolean, importHistory: () -> Unit) {
    OutlinedButton(importHistory, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.FileDownload, null); Spacer(Modifier.width(8.dp)); Text("导入聊天记录")
    }
}

internal fun chatHistoryDate(entry: ChatSummary) =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.updated))

@Composable
internal fun ChatHistoryRow(entry: ChatSummary, busy: Boolean, open: (String) -> Unit, remove: (ChatSummary) -> Unit) {
    Card(onClick = { open(entry.id) }, enabled = !busy, modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.ChatBubbleOutline, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(chatHistoryDate(entry),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ActionIcon(Icons.Outlined.DeleteOutline, "删除 ${entry.title}", !busy) { remove(entry) }
        }
    }
}

internal fun safeFilename(value: String): String =
    value.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(90).ifBlank { "tavern" }

internal fun Context.documentName(uri: Uri): String =
    runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor: Cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()?.removeSuffix(".jsonl")?.takeIf(String::isNotBlank) ?: "导入的聊天"
