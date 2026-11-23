package app.tavern.nativeclient

import app.tavern.nativeclient.backup.SyncSettingsScreen
import app.tavern.nativeclient.backup.SettingsSnapshotsScreen
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal val LocalSelectInputOnFocus = compositionLocalOf { false }

@Composable
internal fun Form(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
internal fun Field(
    label: String, value: String, change: (String) -> Unit, lines: Int = 1,
    placeholder: String? = null, enabled: Boolean = true, secret: Boolean = false,
    type: KeyboardType = KeyboardType.Text,
) {
    var visible by remember { mutableStateOf(false) }
    val autoSelect = LocalSelectInputOnFocus.current && !secret && lines == 1
    var focused by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(TextFieldValue(value)) }
    val field = if (draft.text == value) draft else TextFieldValue(value, draft.selection)
    val currentText by rememberUpdatedState(value)
    LaunchedEffect(focused, autoSelect) {
        if (focused && autoSelect) {
            withFrameNanos { }
            draft = TextFieldValue(currentText, TextRange(0, currentText.length))
        }
    }
    OutlinedTextField(field, { next ->
        draft = next
        if (next.text != value) change(next.text)
    }, modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        enabled = enabled, singleLine = lines == 1, minLines = lines,
        maxLines = if (lines == 1) 1 else maxOf(8, lines),
        label = { Text(label) },
        placeholder = if (placeholder != null) ({ Text(placeholder) }) else null,
        visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else type),
        trailingIcon = if (secret) ({
            ActionIcon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                if (visible) "隐藏$label" else "显示$label") { visible = !visible }
        }) else null)
}

@Composable
internal fun FormHeading(title: String, body: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(top = 6.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun CardEditor(card: Card, busy: Boolean, books: List<WorldBook>, spoilerFree: Boolean, save: (Card) -> Unit) {
    var name by rememberSaveable { mutableStateOf(card.name) }
    var creator by rememberSaveable { mutableStateOf(card.creator) }
    var version by rememberSaveable { mutableStateOf(card.version) }
    var creatorNotes by rememberSaveable { mutableStateOf(card.creatorNotes) }
    var description by rememberSaveable { mutableStateOf(card.description) }
    var personality by rememberSaveable { mutableStateOf(card.personality) }
    var scenario by rememberSaveable { mutableStateOf(card.scenario) }
    var greeting by rememberSaveable { mutableStateOf(card.greeting) }
    var alternates by rememberSaveable { mutableStateOf(card.alternateGreetings.joinToString("\n===\n")) }
    var examples by rememberSaveable { mutableStateOf(card.examples) }
    var system by rememberSaveable { mutableStateOf(card.instructions) }
    var post by rememberSaveable { mutableStateOf(card.postHistory) }
    var depthPrompt by rememberSaveable { mutableStateOf(card.depthPrompt) }
    var promptDepth by rememberSaveable { mutableStateOf(card.depthPromptDepth.toString()) }
    var promptRole by rememberSaveable { mutableStateOf(card.depthPromptRole) }
    var world by rememberSaveable { mutableStateOf(card.worldName) }
    var additionalBooks by rememberSaveable { mutableStateOf(card.additionalWorldBookIds) }
    var chooseAdditionalBooks by rememberSaveable { mutableStateOf(false) }
    var authorNote by rememberSaveable { mutableStateOf(card.authorNote.text) }
    var authorOrder by rememberSaveable { mutableStateOf(card.authorNote.order) }
    var embeddedTags by rememberSaveable { mutableStateOf(card.tags.joinToString("\n")) }
    var revealDefinitions by rememberSaveable(card.id) { mutableStateOf(false) }
    val showDefinitions = !spoilerFree || revealDefinitions || card.name.isBlank()
    val initialTalkativeness = card.talkativeness.toFloat().coerceIn(0f, 1f)
    var talkativeness by rememberSaveable { mutableFloatStateOf(initialTalkativeness) }
    val talkativenessPercent = (talkativeness * 100 + 0.5f).toInt()
    val depth = promptDepth.toIntOrNull()?.takeIf { it in 0..80 }
    val primaryId = books.firstOrNull { it.name == world }?.id
    Form {
        Field("角色名称", name, { name = it }, enabled = !busy)
        if (spoilerFree && card.name.isNotBlank()) {
            TextButton({ revealDefinitions = !revealDefinitions }) {
                Text(if (showDefinitions) "隐藏角色设定" else "显示角色设定和开场白")
            }
        }
        if (showDefinitions) Field("角色描述", description, { description = it }, lines = 3, enabled = !busy)
        Field("角色卡内嵌标签", embeddedTags, { embeddedTags = it }, lines = 2, enabled = !busy, placeholder = "每行一个标签")
        if (showDefinitions) {
            Field("性格", personality, { personality = it }, lines = 2, enabled = !busy)
            Field("场景", scenario, { scenario = it }, lines = 2, enabled = !busy)
        }
        Text("自然群聊中的发言积极度 · $talkativenessPercent%", style = MaterialTheme.typography.labelLarge)
        Slider(value = talkativeness, onValueChange = { talkativeness = it }, enabled = !busy, steps = 99)
        if (showDefinitions) {
            FormHeading("开场与提示词", "可在开场白和提示词里用 {{char}} 和 {{user}} 引用双方名称。")
            Field("开场白", greeting, { greeting = it }, lines = 3, enabled = !busy)
            Field("备选开场白", alternates, { alternates = it }, lines = 2, enabled = !busy,
                placeholder = "在单独一行填写 === 来分隔开场白")
            Field("对话示例", examples, { examples = it }, lines = 3, enabled = !busy)
            Field("系统提示", system, { system = it }, lines = 2, enabled = !busy)
            Field("聊天历史之后的指令", post, { post = it }, lines = 2, enabled = !busy)
            Field("角色深度提示（可选）", depthPrompt, { depthPrompt = it }, lines = 2, enabled = !busy)
            if (depthPrompt.isNotBlank()) {
                Field("注入深度", promptDepth, { promptDepth = it }, enabled = !busy, type = KeyboardType.Number)
                SelectField("注入身份", promptRole, PromptRole.entries, PromptRole::label, !busy) { promptRole = it }
            }
        }
        Field("角色专属作者注释（可选）", authorNote, { authorNote = it }, lines = 2, enabled = !busy)
        if (authorNote.isNotBlank()) SelectField("和对话注释组合", authorOrder, CharacterNoteOrder.entries,
            CharacterNoteOrder::label, !busy) { authorOrder = it }
        if (books.isNotEmpty() || world.isNotBlank()) {
            SelectField("角色主世界书", world, (listOf("") + books.map(WorldBook::name) + world).distinct(),
                { it.ifBlank { "未绑定" } }, enabled = !busy) { world = it }
        }
        if (books.isNotEmpty() || additionalBooks.isNotEmpty()) {
            OutlinedButton({ chooseAdditionalBooks = true }, enabled = !busy) {
                Text("附加世界书 · ${additionalBooks.size} 本")
            }
        }
        FormHeading("创作者资料")
        Field("创作者", creator, { creator = it }, enabled = !busy)
        Field("角色卡版本", version, { version = it }, enabled = !busy)
        Field("创作者备注", creatorNotes, { creatorNotes = it }, lines = 3, enabled = !busy)
        Button(enabled = name.isNotBlank() && !busy && (depthPrompt.isBlank() || depth != null),
            modifier = Modifier.fillMaxWidth(), onClick = {
            save(card.edited(mapOf("name" to name.trim(), "description" to description, "personality" to personality,
                "scenario" to scenario, "first_mes" to greeting, "mes_example" to examples,
                "system_prompt" to system, "post_history_instructions" to post,
                "creator" to creator.trim(), "character_version" to version.trim(), "creator_notes" to creatorNotes),
                alternates.split(Regex("(?m)^===$")).map(String::trim).filter(String::isNotBlank))
                .withWorld(world).withAdditionalWorldBooks(additionalBooks.filterNot { it == primaryId })
                .withAuthorNote(CharacterNote(authorNote, authorOrder))
                .withCreatorTags(embeddedTags.lineSequence().map(String::trim).filter(String::isNotBlank).distinct().toList())
                .let { if (talkativeness == initialTalkativeness) it else it.withTalkativeness(talkativenessPercent / 100.0) }
                .withDepthPrompt(depthPrompt, depth ?: card.depthPromptDepth, promptRole))
        }) { Text("保存角色") }
    }
    if (chooseAdditionalBooks) LoreSelectionDialog("附加世界书", books.filter { it.name != world }.map { it.id to it.name },
        additionalBooks.filterNot { it == primaryId }, { chooseAdditionalBooks = false }) {
        additionalBooks = it
        chooseAdditionalBooks = false
    }
}

@Composable
internal fun SettingsScreen(state: TavernState, model: TavernViewModel) {
    val prefs = state.prefs
    var editor by rememberSaveable { mutableStateOf<String?>(null) }
    var draftId by rememberSaveable { mutableStateOf("") }
    var presetPickerOpen by rememberSaveable { mutableStateOf(false) }
    var presetDraft by remember { mutableStateOf<Provider?>(null) }
    var personaSettings by rememberSaveable { mutableStateOf(false) }
    var promptSettings by rememberSaveable { mutableStateOf(false) }
    var formattingSettings by rememberSaveable { mutableStateOf(false) }
    var regexSettings by rememberSaveable { mutableStateOf(false) }
    var quickSettings by rememberSaveable { mutableStateOf(false) }
    var voiceSettings by rememberSaveable { mutableStateOf(false) }
    var vectorSettings by rememberSaveable { mutableStateOf(false) }
    var captionSettings by rememberSaveable { mutableStateOf(false) }
    var translationSettings by rememberSaveable { mutableStateOf(false) }
    var summaryExtras by rememberSaveable { mutableStateOf(false) }
    var dataBank by rememberSaveable { mutableStateOf(false) }
    var noteSettings by rememberSaveable { mutableStateOf(false) }
    var replyAutomation by rememberSaveable { mutableStateOf(false) }
    var backgroundLibrary by rememberSaveable { mutableStateOf(false) }
    var conversationStats by rememberSaveable { mutableStateOf(false) }
    var comfyWorkflows by rememberSaveable { mutableStateOf(false) }
    var profileSettings by rememberSaveable { mutableStateOf(false) }
    var syncSettings by rememberSaveable { mutableStateOf(false) }
    var snapshotsOpen by rememberSaveable { mutableStateOf(false) }
    var themesOpen by rememberSaveable { mutableStateOf(false) }
    var temperature by rememberSaveable(prefs.temperature) { mutableStateOf(prefs.temperature) }
    var maxTokens by rememberSaveable(prefs.maxTokens) { mutableStateOf(prefs.maxTokens) }
    var reasoning by rememberSaveable(prefs.includeReasoning) { mutableStateOf(prefs.includeReasoning) }
    var toolCalling by rememberSaveable(prefs.toolCalling) { mutableStateOf(prefs.toolCalling) }
    var tokenProbabilities by rememberSaveable(prefs.requestTokenProbabilities) { mutableStateOf(prefs.requestTokenProbabilities) }
    var theme by rememberSaveable(prefs.theme) { mutableStateOf(prefs.theme) }
    var dynamic by rememberSaveable(prefs.dynamicColor) { mutableStateOf(prefs.dynamicColor) }
    val selected = editor
    if (themesOpen) {
        ChatThemeSettingsScreen(state, model) { themesOpen = false }
        return
    }
    if (snapshotsOpen) {
        SettingsSnapshotsScreen(state, model) { snapshotsOpen = false }
        return
    }
    if (personaSettings) {
        PersonaSettings(state, model) { personaSettings = false }
        return
    }
    if (promptSettings) {
        PromptPresetLibrary(state, model) { promptSettings = false }
        return
    }
    if (formattingSettings) {
        AdvancedFormattingScreen(state, model) { formattingSettings = false }
        return
    }
    if (regexSettings) {
        RegexLibrary(state, model) { regexSettings = false }
        return
    }
    if (quickSettings) {
        QuickReplyLibrary(state, model) { quickSettings = false }
        return
    }
    if (voiceSettings) {
        VoiceSettings(state, model) { voiceSettings = false }
        return
    }
    if (vectorSettings) {
        VectorSettings(state, model) { vectorSettings = false }
        return
    }
    if (captionSettings) {
        CaptionSettingsScreen(state, model) { captionSettings = false }
        return
    }
    if (translationSettings) {
        TranslationServiceSettings(state, model) { translationSettings = false }
        return
    }
    if (summaryExtras) {
        SummaryExtrasScreen(state, model) { summaryExtras = false }
        return
    }
    if (dataBank) {
        DataBankPanel(state, model, fromChat = false, close = { dataBank = false }, configure = {
            dataBank = false; vectorSettings = true
        })
        return
    }
    if (profileSettings) {
        ConnectionProfilesScreen(state, model) { profileSettings = false }
        return
    }
    if (syncSettings) {
        SyncSettingsScreen(state, model) { syncSettings = false }
        return
    }
    if (selected != null) {
        val saved = prefs.providers.firstOrNull { it.id == selected }
        val draft = saved ?: presetDraft?.takeIf { it.id == selected }
            ?: ProviderTemplate.builtIn.firstOrNull { it.id == selected }?.profile() ?: Provider(draftId)
        fun close() { model.clearModels(); presetDraft = null; editor = null }
        androidx.activity.compose.BackHandler { if (!state.busy) close() }
        key(selected) {
            ProviderEditor(draft, saved != null, state, model, ::close)
        }
        return
    }
    Form {
        FormHeading("模型提供商", "DeepSeek 和 Google Gemini 填入 API Key 即可使用。其他服务可自定义地址和通信协议。")
        val defaults = prefs.connectionFor()
        ProviderTemplate.builtIn.forEach { template ->
            val saved = prefs.providers.firstOrNull { it.id == template.id }
            ProviderRow(saved ?: template.profile(), saved != null, defaults?.provider?.id == template.id,
                state.busy, open = { editor = template.id }, makeDefault = { saved?.let(model::setDefaultProvider) })
        }
        prefs.providers.filter { it.template == null }.forEach { provider ->
            ProviderRow(provider, true, defaults?.provider?.id == provider.id, state.busy,
                open = { editor = provider.id }, makeDefault = { model.setDefaultProvider(provider) })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { presetPickerOpen = true },
                enabled = !state.busy,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Outlined.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text("从预设模板添加")
            }
            OutlinedButton(
                onClick = { draftId = newId(); presetDraft = null; editor = draftId },
                enabled = !state.busy,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(6.dp)); Text("自定义提供商")
            }
        }
        OutlinedButton(onClick = { profileSettings = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Tune, null)
            Spacer(Modifier.width(8.dp))
            Text("连接配置档案 · ${prefs.activeProfile()?.name ?: "未指定默认档案"}")
        }
        HorizontalDivider()
        FormHeading("生成参数")
        OutlinedButton({ promptSettings = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Tune, null)
            Spacer(Modifier.width(8.dp))
            Text("提示词预设 · ${state.presetFor(null)?.name ?: "内置基础"}")
        }
        OutlinedButton({ formattingSettings = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text("高级格式化")
        }
        Field("温度（可选）", temperature, { temperature = it }, placeholder = "留空使用服务默认值",
            enabled = !state.busy, type = KeyboardType.Decimal)
        Field("最大输出 Token（可选）", maxTokens, { maxTokens = it }, placeholder = "留空使用服务默认值",
            enabled = !state.busy, type = KeyboardType.Number)
        GroupToggle("在后续请求中附带已保存的推理", reasoning, !state.busy) { reasoning = it }
        GroupToggle("允许对话模型调用函数工具", toolCalling, !state.busy) { toolCalling = it }
        GroupToggle("请求 Token 概率", tokenProbabilities, !state.busy) { tokenProbabilities = it }
        OutlinedButton({ comfyWorkflows = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text("ComfyUI 工作流")
        }
        HorizontalDivider()
        FormHeading("对话与同步")
        val handling = prefs.chatHandling
        SelectField("按回车键", handling.enterToSend, EnterToSend.entries, { it.label }, !state.busy) {
            model.setChatHandling(handling.copy(enterToSend = it))
        }
        GroupToggle("单聊空输入时发送键用于续写", handling.continueOnSend, !state.busy) {
            model.setChatHandling(handling.copy(continueOnSend = it))
        }
        GroupToggle("在输入栏显示续写快捷键", handling.quickContinue, !state.busy) {
            model.setChatHandling(handling.copy(quickContinue = it))
        }
        GroupToggle("在输入栏显示代写快捷键", handling.quickImpersonate, !state.busy) {
            model.setChatHandling(handling.copy(quickImpersonate = it))
        }
        GroupToggle("新回复自动滚动到底部", handling.autoScroll, !state.busy) {
            model.setChatHandling(handling.copy(autoScroll = it))
        }
        GroupToggle("重新打开聊天时恢复草稿", handling.restoreDraft, !state.busy) {
            model.setChatHandling(handling.copy(restoreDraft = it))
        }
        GroupToggle("群聊回复允许包含其他成员的台词", handling.relaxGroupTrimming, !state.busy) {
            model.setChatHandling(handling.copy(relaxGroupTrimming = it))
        }
        GroupToggle("压缩回复和文本补全中的连续空行", handling.collapseNewlines, !state.busy) {
            model.setChatHandling(handling.copy(collapseNewlines = it))
        }
        GroupToggle("设置输入框获得焦点时选中文字", handling.selectInputOnFocus, !state.busy) {
            model.setChatHandling(handling.copy(selectInputOnFocus = it))
        }
        GroupToggle("启用 Markdown 键盘快捷键", handling.markdownHotkeys, !state.busy) {
            model.setChatHandling(handling.copy(markdownHotkeys = it))
        }
        ChatDisplaySettings(handling, !state.busy, model::setChatHandling)
        GroupToggle("自动保存消息编辑", handling.autoSaveEdits, !state.busy) {
            model.setChatHandling(handling.copy(autoSaveEdits = it))
        }
        GroupToggle("删除消息前确认", handling.confirmDelete, !state.busy) {
            model.setChatHandling(handling.copy(confirmDelete = it))
        }
        GroupToggle("显示备选回复箭头", handling.showSwipes, !state.busy) {
            model.setChatHandling(handling.copy(showSwipes = it))
        }
        GroupToggle("自动修复回复中的 Markdown", handling.repairMarkdown, !state.busy) {
            model.setChatHandling(handling.copy(repairMarkdown = it))
        }
        GroupToggle("回复完成时播放提示音", handling.replySound, !state.busy) {
            model.setChatHandling(handling.copy(replySound = it))
        }
        if (handling.replySound) {
            GroupToggle("仅在应用处于后台时播放", handling.replySoundInBackgroundOnly, !state.busy) {
                model.setChatHandling(handling.copy(replySoundInBackgroundOnly = it))
            }
            TextButton(model::previewReplySound, enabled = !state.busy) { Text("试听提示音") }
        }
        OutlinedButton({ snapshotsOpen = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text("设置快照")
        }
        OutlinedButton({ conversationStats = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text("聊天统计")
        }
        OutlinedButton({ syncSettings = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.CloudSync, null)
            Spacer(Modifier.width(8.dp))
            Text(if (prefs.sync.configured) "WebDAV 云同步与跨设备联动 · 已配置" else "WebDAV 云同步与跨设备联动")
        }
        GroupToggle("启用角色卡扩展脚本（MVU 面板 / 离线沙盒桥接）", prefs.cardScriptsEnabled, !state.busy, model::setCardScriptsEnabled)
        OutlinedButton({ replyAutomation = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Bolt, null)
            Spacer(Modifier.width(8.dp))
            Text("回复自动化")
        }
        GroupToggle("启动时打开上次聊天", prefs.welcome.autoOpen, !state.busy, model::setAutoOpenLastChat)
        GroupToggle("首页显示最近聊天", prefs.welcome.showRecent, !state.busy) { visible ->
            model.setRecentChats(visible, prefs.welcome.recentCount)
        }
        if (prefs.welcome.showRecent) {
            SelectField("首页显示的聊天数", prefs.welcome.recentCount, (1..RECENT_CHAT_LIMIT).toList(), Int::toString, !state.busy) { count ->
                model.setRecentChats(true, count)
            }
        }
        OutlinedButton({ personaSettings = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Person, null)
            Spacer(Modifier.width(8.dp))
            Text("人设管理 · ${prefs.personaFor().name}")
        }
        OutlinedButton({ noteSettings = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.AutoMirrored.Outlined.StickyNote2, null)
            Spacer(Modifier.width(8.dp))
            Text("新对话的默认作者注释")
        }
        OutlinedButton({ regexSettings = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.FindReplace, null)
            Spacer(Modifier.width(8.dp))
            Text("正则扩展")
        }
        OutlinedButton({ quickSettings = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Bolt, null)
            Spacer(Modifier.width(8.dp))
            Text("快捷回复")
        }
        OutlinedButton({ voiceSettings = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.MicNone, null)
            Spacer(Modifier.width(8.dp))
            Text("语音朗读与输入")
        }
        OutlinedButton({ vectorSettings = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.AutoMirrored.Outlined.ManageSearch, null)
            Spacer(Modifier.width(8.dp))
            Text("向量检索")
        }
        OutlinedButton({ captionSettings = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.ImageSearch, null)
            Spacer(Modifier.width(8.dp))
            Text("图片描述")
        }
        OutlinedButton({ translationSettings = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Translate, null)
            Spacer(Modifier.width(8.dp))
            Text("翻译服务")
        }
        OutlinedButton({ summaryExtras = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text("摘要服务")
        }
        OutlinedButton({ dataBank = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Article, null)
            Spacer(Modifier.width(8.dp))
            Text("资料库")
        }
        HorizontalDivider()
        FormHeading("角色库")
        val library = prefs.characterLibrary
        SelectField("角色名称下方显示", library.subtitle, CharacterSubtitle.entries, CharacterSubtitle::label, !state.busy) {
            model.setCharacterLibrary(library.copy(subtitle = it))
        }
        SelectField("导入角色卡标签", library.tagImport, CardTagImport.entries, CardTagImport::label, !state.busy) {
            model.setCharacterLibrary(library.copy(tagImport = it))
        }
        GroupToggle("模糊搜索角色设定", library.advancedSearch, !state.busy) {
            model.setCharacterLibrary(library.copy(advancedSearch = it))
        }
        GroupToggle("防剧透模式", library.spoilerFree, !state.busy) {
            model.setCharacterLibrary(library.copy(spoilerFree = it))
        }
        GroupToggle("显示头像文件名", library.showAvatarFilename, !state.busy) {
            model.setCharacterLibrary(library.copy(showAvatarFilename = it))
        }
        GroupToggle("导入角色时保留头像原尺寸", library.keepAvatarSize, !state.busy) {
            model.setCharacterLibrary(library.copy(keepAvatarSize = it))
        }
        GroupToggle("导入角色卡后询问是否提取世界书", library.askToImportLore, !state.busy) {
            model.setCharacterLibrary(library.copy(askToImportLore = it))
        }
        val textSystem = prefs.textSystem
        GroupToggle("优先使用角色卡的系统提示", textSystem.preferCharacterPrompt, !state.busy) {
            model.saveTextSystem(textSystem.copy(preferCharacterPrompt = it))
        }
        GroupToggle("优先使用角色卡的历史后指令", textSystem.preferCharacterPostHistory, !state.busy) {
            model.saveTextSystem(textSystem.copy(preferCharacterPostHistory = it))
        }
        HorizontalDivider()
        FormHeading("外观")
        OutlinedButton({ themesOpen = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Palette, null)
            Spacer(Modifier.width(8.dp))
            Text("聊天主题")
        }
        OutlinedButton({ backgroundLibrary = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text("背景库")
        }
        GroupToggle("视觉小说模式", prefs.visualNovel, !state.busy, model::setVisualNovel)
        val appearance = prefs.chatAppearance
        SelectField("对话排版模式", appearance.layoutMode, ChatLayoutMode.entries, { it.label }, !state.busy) {
            model.setChatAppearance(appearance.copy(layoutMode = it))
        }
        GroupToggle("隐藏聊天头像", appearance.hideAvatars, !state.busy) {
            model.setChatAppearance(appearance.copy(hideAvatars = it))
        }
        if (!appearance.hideAvatars) {
            SelectField("聊天头像形状", appearance.avatarStyle, ChatAvatarStyle.entries, { it.label }, !state.busy) {
                model.setChatAppearance(appearance.copy(avatarStyle = it))
            }
        }
        var chatWidth by rememberSaveable(appearance.chatWidthPercent) { mutableIntStateOf(appearance.chatWidthPercent) }
        Text("聊天宽度 · $chatWidth%", style = MaterialTheme.typography.labelLarge)
        Slider(chatWidth.toFloat(), { chatWidth = kotlin.math.round(it).toInt() },
            onValueChangeFinished = { model.setChatAppearance(appearance.copy(chatWidthPercent = chatWidth)) },
            valueRange = 25f..100f, enabled = !state.busy)
        Text("正文字号 · ${appearance.fontSizeSp.toInt()} sp", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = appearance.fontSizeSp,
            onValueChange = { model.setChatAppearance(appearance.copy(fontSizeSp = kotlin.math.round(it))) },
            valueRange = 12f..24f,
            steps = 11,
            enabled = !state.busy,
        )
        Text(
            "行距倍数 · ${String.format(java.util.Locale.US, "%.2f", appearance.lineHeightMultiplier)}×",
            style = MaterialTheme.typography.labelLarge,
        )
        Slider(
            value = appearance.lineHeightMultiplier,
            onValueChange = {
                val snapped = (kotlin.math.round(it * 20f) / 20f)
                model.setChatAppearance(appearance.copy(lineHeightMultiplier = snapped))
            },
            valueRange = 1.15f..2.0f,
            enabled = !state.busy,
        )
        GroupToggle("对话引号主题色高亮", appearance.highlightQuotes, !state.busy) {
            model.setChatAppearance(appearance.copy(highlightQuotes = it))
        }
        GroupToggle("渲染角色卡 HTML/CSS 状态面板", appearance.renderHtmlCards, !state.busy) {
            model.setChatAppearance(appearance.copy(renderHtmlCards = it))
        }
        GroupToggle("显示实时上下文 Token 占用条", appearance.showTokenBar, !state.busy) {
            model.setChatAppearance(appearance.copy(showTokenBar = it))
        }
        GroupToggle("展开消息操作", appearance.expandMessageActions, !state.busy) {
            model.setChatAppearance(appearance.copy(expandMessageActions = it))
        }
        GroupToggle("显示消息时间", appearance.showMessageTime, !state.busy) {
            model.setChatAppearance(appearance.copy(showMessageTime = it))
        }
        GroupToggle("在回复旁显示模型标记", appearance.showModelIcon, !state.busy) {
            model.setChatAppearance(appearance.copy(showModelIcon = it))
        }
        GroupToggle("聊天中显示收藏角色和群组", appearance.favoriteHotswap, !state.busy) {
            model.setChatAppearance(appearance.copy(favoriteHotswap = it))
        }
        GroupToggle("显示群聊发言队列", prefs.showGroupQueue, !state.busy, model::setShowGroupQueue)
        GroupToggle("横滑最新回复切换或生成备选", prefs.swipeGestures, !state.busy, model::setSwipeGestures)
        GroupToggle("显示消息编号", appearance.showMessageIds, !state.busy) {
            model.setChatAppearance(appearance.copy(showMessageIds = it))
        }
        GroupToggle("显示每条消息的备选计数", appearance.showAllSwipeCounts, !state.busy) {
            model.setChatAppearance(appearance.copy(showAllSwipeCounts = it))
        }
        GroupToggle("轻点消息正文进入编辑", appearance.clickToEdit, !state.busy) {
            model.setChatAppearance(appearance.copy(clickToEdit = it))
        }
        GroupToggle("紧凑输入栏", appearance.compactInput, !state.busy) {
            model.setChatAppearance(appearance.copy(compactInput = it))
        }
        SelectField("默认媒体显示", appearance.defaultMediaDisplay, MessageMediaDisplay.entries, { it.label }, !state.busy) {
            model.setChatAppearance(appearance.copy(defaultMediaDisplay = it))
        }
        GroupToggle("减少聊天滚动动画", appearance.reducedMotion, !state.busy) {
            model.setChatAppearance(appearance.copy(reducedMotion = it))
        }
        SelectField("应用内提示位置", appearance.noticePosition, NoticePosition.entries, { it.label }, !state.busy) {
            model.setChatAppearance(appearance.copy(noticePosition = it))
        }
        GroupToggle("显示回复生成用时", appearance.showGenerationTimer, !state.busy) {
            model.setChatAppearance(appearance.copy(showGenerationTimer = it))
        }
        GroupToggle("显示每条消息的正文 Token", appearance.showMessageTokens, !state.busy) {
            model.setChatAppearance(appearance.copy(showMessageTokens = it))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("system" to "随系统", "light" to "浅色", "dark" to "深色").forEach { (value, label) ->
                FilterChip(theme == value, onClick = { theme = value }, label = { Text(label) }, enabled = !state.busy)
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("使用系统动态配色", modifier = Modifier.weight(1f))
                Switch(dynamic, { dynamic = it }, enabled = !state.busy)
            }
        }
        Button(onClick = {
            model.savePrefs(prefs.copy(temperature = temperature,
                maxTokens = maxTokens, theme = theme, dynamicColor = dynamic, includeReasoning = reasoning,
                toolCalling = toolCalling, requestTokenProbabilities = tokenProbabilities))
        }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("保存设置") }
    }
    if (noteSettings) AuthorNoteEditor(prefs.defaultAuthorNote, "默认作者注释", state.busy, { noteSettings = false }) {
        model.setDefaultAuthorNote(it) { noteSettings = false }
    }
    if (replyAutomation) ReplyAutomationDialog(prefs.replyAutomation, state.busy, { replyAutomation = false }) {
        model.setReplyAutomation(it) { replyAutomation = false }
    }
    if (backgroundLibrary) BackgroundLibraryDialog(state, model, null) { backgroundLibrary = false }
    if (conversationStats) ConversationStatisticsDialog("全部聊天统计", model) { conversationStats = false }
    if (comfyWorkflows) ComfyWorkflowDialog(state, model, close = { comfyWorkflows = false })
    if (presetPickerOpen) {
        ProviderPresetPickerDialog(onDismiss = { presetPickerOpen = false }) { picked ->
            presetDraft = picked
            draftId = picked.id
            editor = picked.id
            presetPickerOpen = false
        }
    }
}

@Composable
private fun ProviderRow(provider: Provider, saved: Boolean, isDefault: Boolean, busy: Boolean,
                        open: () -> Unit, makeDefault: () -> Unit) {
    Card(onClick = open, enabled = !busy, modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Dns, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(provider.name, style = MaterialTheme.typography.titleMedium)
                Text(when {
                    !saved -> "添加 API Key"
                    !provider.ready -> "配置未完成"
                    isDefault -> "默认 · ${provider.defaultModel}"
                    else -> provider.defaultModel
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (saved && provider.ready && !isDefault) {
                ActionIcon(Icons.Outlined.StarOutline, "设为默认提供商", !busy, makeDefault)
            } else Icon(Icons.Outlined.ChevronRight, null)
        }
    }
}

@Composable
private fun ProviderEditor(provider: Provider, saved: Boolean, state: TavernState,
                           model: TavernViewModel, close: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(provider.name) }
    var endpoint by rememberSaveable { mutableStateOf(provider.endpoint) }
    var apiKey by remember { mutableStateOf(provider.apiKey) }
    var defaultModel by rememberSaveable { mutableStateOf(provider.defaultModel) }
    var knownModels by rememberSaveable { mutableStateOf(provider.models.joinToString("\n")) }
    var streaming by rememberSaveable { mutableStateOf(provider.streaming) }
    var contextLimit by rememberSaveable { mutableStateOf(provider.contextLimit?.toString().orEmpty()) }
    var contexts by rememberSaveable { mutableStateOf(HashMap(provider.modelContexts)) }
    var contextEndpoint by rememberSaveable { mutableStateOf(provider.endpoint.trim()) }
    var protocol by rememberSaveable { mutableStateOf(provider.protocol) }
    var promptCaching by rememberSaveable { mutableStateOf(provider.promptCaching) }
    var thinkingBudget by rememberSaveable { mutableStateOf(provider.thinkingBudget?.toString().orEmpty()) }
    var geminiSafety by rememberSaveable { mutableStateOf(provider.geminiSafety) }
    var openRouterProviders by rememberSaveable { mutableStateOf(provider.openRouterProviders.joinToString(", ")) }
    var openRouterFallbacks by rememberSaveable { mutableStateOf(provider.openRouterAllowFallbacks) }
    var openRouterEffort by rememberSaveable { mutableStateOf(provider.openRouterReasoningEffort) }
    var textCompletion by rememberSaveable { mutableStateOf(provider.textCompletion) }
    var instructJson by rememberSaveable { mutableStateOf(provider.instruct?.json()?.toString()) }
    var editingInstruct by rememberSaveable { mutableStateOf(false) }
    var contextJson by rememberSaveable { mutableStateOf(provider.textContext?.json()?.toString()) }
    var editingTextContext by rememberSaveable { mutableStateOf(false) }
    var editingModelFormatting by rememberSaveable { mutableStateOf(false) }
    var modelFormatting by remember { mutableStateOf(provider.modelFormatting) }
    var editingTextSampling by remember { mutableStateOf(false) }
    var textSampling by remember { mutableStateOf(provider.textSampling) }
    var editingTokenBias by remember { mutableStateOf(false) }
    var tokenBias by remember { mutableStateOf(provider.tokenBias) }
    var textTokenizer by rememberSaveable { mutableStateOf(provider.textTokenizer) }
    var requestOptions by remember { mutableStateOf(provider.requestOptions) }
    var editingRequestOptions by remember { mutableStateOf(false) }
    var novelAi by remember { mutableStateOf(provider.novelAi) }
    var novelAiRoute by remember { mutableStateOf(provider.endpoint.trim() to provider.apiKey.trim().hashCode()) }
    var editingNovelAi by remember { mutableStateOf(false) }
    var horde by remember { mutableStateOf(provider.horde) }
    var hordeCatalogTrust by remember { mutableStateOf(provider.horde.trustedOnly) }
    var multiKeys by remember { mutableStateOf(provider.multiKeys) }
    var editingMultiKeys by rememberSaveable { mutableStateOf(false) }
    var azureDeployment by rememberSaveable { mutableStateOf(provider.azureConfig.deployment) }
    var azureApiVersion by rememberSaveable { mutableStateOf(provider.azureConfig.apiVersion) }
    var tabbyControlOpen by rememberSaveable { mutableStateOf(false) }
    var formattingRoute by remember { mutableStateOf(Triple(provider.endpoint.trim(), provider.protocol, provider.apiKey.trim().hashCode())) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    val template = provider.template
    val context = contextLimit.trim().toIntOrNull()?.takeIf { it in 1..MAX_CONTEXT_TOKENS }
    val thinking = thinkingBudget.trim().toIntOrNull()?.takeIf { it in 1..128_000 }
    val route = Triple(endpoint.trim(), protocol, apiKey.trim().hashCode())
    val novelRoute = endpoint.trim() to apiKey.trim().hashCode()
    val instruct = remember(instructJson) { instructJson?.let { InstructTemplate.parse(it) } }
    val textContext = remember(contextJson) {
        contextJson?.let { TextContextTemplate.fromJson(org.json.JSONObject(it), checkStory = false) }
    }
    if (editingTextContext) {
        TextContextTemplateScreen(textContext, { editingTextContext = false }) { context, matchingInstruct ->
            contextJson = context?.json()?.toString()
            if (matchingInstruct != null) instructJson = matchingInstruct.json().toString()
            editingTextContext = false
        }
        return
    }
    if (editingInstruct) {
        InstructTemplateScreen(instruct, { editingInstruct = false }) {
            instructJson = it?.json()?.toString()
            editingInstruct = false
        }
        return
    }
    fun draft() = provider.copy(
        name = name,
        endpoint = endpoint,
        apiKey = apiKey,
        defaultModel = defaultModel,
        models = knownModels.lineSequence().map(String::trim).filter(String::isNotBlank).toList(),
        streaming = streaming,
        contextLimit = context,
        modelContexts = contexts.takeIf { endpoint.trim() == contextEndpoint &&
            (protocol != ApiProtocol.HORDE_TEXT || horde.trustedOnly == hordeCatalogTrust) }.orEmpty(),
        protocol = protocol,
        promptCaching = promptCaching,
        thinkingBudget = thinking,
        geminiSafety = geminiSafety,
        openRouterProviders = openRouterProviders.split(',', '，', '\n').map(String::trim).filter(String::isNotBlank).distinct(),
        openRouterAllowFallbacks = openRouterFallbacks,
        openRouterReasoningEffort = openRouterEffort,
        textCompletion = textCompletion,
        instruct = instruct,
        textContext = textContext,
        modelFormatting = if (route == formattingRoute) modelFormatting else modelFormatting.copy(backend = emptyMap()),
        textSampling = textSampling,
        requestOptions = requestOptions,
        tokenBias = tokenBias,
        textTokenizer = textTokenizer,
        novelAi = novelAi.copy(tier = novelAi.tier.takeIf { novelRoute == novelAiRoute }),
        horde = horde.copy(modelOutputs = horde.modelOutputs.takeIf {
            endpoint.trim() == contextEndpoint && horde.trustedOnly == hordeCatalogTrust
        }.orEmpty()),
        multiKeys = multiKeys,
        azureConfig = AzureOpenAiConfig(azureDeployment, azureApiVersion),
    )
    if (editingNovelAi) {
        NovelAiSettingsScreen(novelAi, NovelAiModel.from(defaultModel.trim()), { editingNovelAi = false }) {
            novelAi = it
            editingNovelAi = false
        }
        return
    }
    if (editingTokenBias) {
        TokenBiasScreen(draft(), { editingTokenBias = false }) {
            tokenBias = it
            editingTokenBias = false
        }
        return
    }
    if (editingRequestOptions) {
        ProviderRequestOptionsScreen(requestOptions, { editingRequestOptions = false }) {
            requestOptions = it
            editingRequestOptions = false
        }
        return
    }
    if (editingTextSampling) {
        TextSamplingScreen(draft(), { editingTextSampling = false }) {
            textSampling = it
            editingTextSampling = false
        }
        return
    }
    if (editingModelFormatting) {
        ModelFormattingScreen(draft(), model, state.busy, { editingModelFormatting = false }) {
            modelFormatting = it
            formattingRoute = route
            editingModelFormatting = false
        }
        return
    }
    val needsKey = template != null || protocol == ApiProtocol.NOVELAI_TEXT
    val hasAnyKey = apiKey.isNotBlank() || multiKeys.enabledKeys.isNotEmpty()
    val canSave = if (template == null) name.isNotBlank() && endpoint.isNotBlank() && defaultModel.isNotBlank() &&
        (!needsKey || hasAnyKey)
        else hasAnyKey
    Form {
        TextButton(close, enabled = !state.busy) { Text("返回提供商列表") }
        FormHeading(if (template != null) template.label else if (saved) "编辑自定义提供商" else "添加自定义提供商",
            template?.let { "默认模型为 ${it.defaultModel}。填写 Key 后即可保存。" })
        if (template == null) {
            Field("提供商名称", name, { name = it }, enabled = !state.busy)
            SelectField("通信协议", protocol, ApiProtocol.entries, ApiProtocol::label, !state.busy) { choice ->
                protocol = choice
                if (choice == ApiProtocol.NOVELAI_TEXT) {
                    if (endpoint.isBlank()) endpoint = NOVEL_AI_ENDPOINT
                    if (defaultModel.isBlank()) defaultModel = NovelAiModel.ERATO.id
                }
                if (choice == ApiProtocol.HORDE_TEXT) {
                    if (endpoint.isBlank()) endpoint = HORDE_ENDPOINT
                    if (defaultModel.isBlank()) defaultModel = HORDE_AUTO_MODEL
                    streaming = false
                }
                if (choice == ApiProtocol.AZURE_OPENAI) {
                    if (endpoint.isBlank()) endpoint = ChatProviderPreset.AZURE_OPENAI.baseUrl
                    if (defaultModel.isBlank()) defaultModel = ChatProviderPreset.AZURE_OPENAI.defaultModel
                }
                if (choice == ApiProtocol.VERTEX_AI) {
                    if (endpoint.isBlank()) endpoint = ChatProviderPreset.VERTEX_AI.baseUrl
                    if (defaultModel.isBlank()) defaultModel = ChatProviderPreset.VERTEX_AI.defaultModel
                }
                AdditionalTextBackends.specFor(choice)?.let { spec ->
                    if (endpoint.isBlank()) endpoint = spec.defaultEndpoint
                    if (defaultModel.isBlank()) defaultModel = spec.defaultModel
                }
            }
            Field("API 地址", endpoint, { endpoint = it }, placeholder = when (protocol) {
                ApiProtocol.ANTHROPIC -> "https://api.anthropic.com"
                ApiProtocol.GEMINI_NATIVE -> "https://generativelanguage.googleapis.com/v1beta"
                ApiProtocol.AZURE_OPENAI -> ChatProviderPreset.AZURE_OPENAI.baseUrl
                ApiProtocol.VERTEX_AI -> ChatProviderPreset.VERTEX_AI.baseUrl
                ApiProtocol.OLLAMA -> "http://127.0.0.1:11434"
                ApiProtocol.KOBOLD_TEXT -> "http://127.0.0.1:5001"
                ApiProtocol.NOVELAI_TEXT -> NOVEL_AI_ENDPOINT
                ApiProtocol.HORDE_TEXT -> HORDE_ENDPOINT
                else -> AdditionalTextBackends.defaultEndpoint(protocol) ?: "https://api.example.com/v1"
            }, enabled = !state.busy, type = KeyboardType.Uri)
        } else if (template.allowedProtocols().size > 1) {
            SelectField("通信协议", protocol, template.allowedProtocols(), ApiProtocol::label, !state.busy) { protocol = it }
        }
        Field(if (needsKey) "API Key" else "API Key（可选）",
            apiKey, { apiKey = it }, secret = true, enabled = !state.busy)
        OutlinedButton({ editingMultiKeys = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Key, null); Spacer(Modifier.width(8.dp))
            Text(
                if (multiKeys.enabledKeys.isEmpty()) "多密钥轮换 / 故障切换"
                else "多密钥轮换 · ${multiKeys.enabledKeys.size} 个启用 Key",
            )
        }
        if (protocol == ApiProtocol.AZURE_OPENAI) {
            Field("Azure 部署名 (Deployment)", azureDeployment, { azureDeployment = it }, enabled = !state.busy,
                placeholder = "留空默认使用模型 ID")
            Field("Azure API 版本 (api-version)", azureApiVersion, { azureApiVersion = it }, enabled = !state.busy,
                placeholder = DEFAULT_AZURE_API_VERSION)
        }
        if (protocol == ApiProtocol.TABBY_TEXT) {
            OutlinedButton(
                { tabbyControlOpen = true },
                enabled = !state.busy && endpoint.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Memory, null); Spacer(Modifier.width(8.dp))
                Text("TabbyAPI 模型加载 / 卸载")
            }
        }
        if (template == null && protocol in setOf(ApiProtocol.OPENAI_COMPATIBLE, ApiProtocol.OPENROUTER, ApiProtocol.AZURE_OPENAI)) {
            OutlinedButton({ editingRequestOptions = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (requestOptions.isEmpty) "附加请求参数" else "附加请求参数 · 已配置")
            }
        }
        if (template != null) {
            TextButton(onClick = { advanced = !advanced }, enabled = !state.busy) {
                Text(if (advanced) "收起模型选项" else "模型选项")
            }
        }
        if (template == null || advanced) {
            Field("默认模型 ID", defaultModel, { defaultModel = it }, enabled = !state.busy)
            OutlinedButton({ model.loadModels(draft()) },
                enabled = !state.busy && endpoint.isNotBlank() && (!needsKey || hasAnyKey)) {
                Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(8.dp)); Text("读取模型列表")
            }
            Field("快捷切换的模型（每行一个）", knownModels, { knownModels = it }, lines = 3, enabled = !state.busy)
            Field("上下文 Token 上限（选填）", contextLimit, { contextLimit = it }, enabled = !state.busy,
                placeholder = "自动采用模型额度", type = KeyboardType.Number)
            SelectField("本机分词器", textTokenizer, TextTokenizer.entries, TextTokenizer::label, !state.busy) {
                textTokenizer = it
            }
            if (protocol != ApiProtocol.HORDE_TEXT) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("流式回复", Modifier.weight(1f))
                Switch(streaming, { streaming = it }, enabled = !state.busy)
            }
            if (protocol == ApiProtocol.HORDE_TEXT) {
                Text("Key 留空可匿名提交。模型选为 horde:auto 时由节点自动分配。",
                    style = MaterialTheme.typography.bodySmall)
                GroupToggle("只使用受信任节点", horde.trustedOnly, !state.busy) { horde = horde.copy(trustedOnly = it) }
                GroupToggle("按节点缩短输出", horde.adjustOutput, !state.busy) { horde = horde.copy(adjustOutput = it) }
                GroupToggle("按节点调整上下文", horde.adjustContext, !state.busy) { horde = horde.copy(adjustContext = it) }
            }
            if (protocol == ApiProtocol.ANTHROPIC || protocol == ApiProtocol.GEMINI_NATIVE) {
                GroupToggle("启用 Prompt / Context 缓存", promptCaching, !state.busy) { promptCaching = it }
            }
            if (protocol in setOf(ApiProtocol.ANTHROPIC, ApiProtocol.GEMINI_NATIVE, ApiProtocol.OPENROUTER)) {
                Field("思考预算 Token（可选）", thinkingBudget, { thinkingBudget = it }, enabled = !state.busy,
                    placeholder = "留空按模型默认", type = KeyboardType.Number)
            }
            if (protocol == ApiProtocol.GEMINI_NATIVE) {
                SelectField("安全过滤级别", geminiSafety, GeminiSafetyLevel.entries, GeminiSafetyLevel::label, !state.busy) {
                    geminiSafety = it
                }
            }
            if (protocol == ApiProtocol.OPENROUTER) {
                Field("优先提供商路由（逗号分隔，可选）", openRouterProviders, { openRouterProviders = it }, enabled = !state.busy,
                    placeholder = "例如：anthropic, google-vertex")
                GroupToggle("允许 OpenRouter 自动回退其他节点", openRouterFallbacks, !state.busy) { openRouterFallbacks = it }
                SelectField(
                    "OpenRouter 思考强度",
                    openRouterEffort,
                    listOf("", "low", "medium", "high"),
                    { when (it) { "low" -> "低"; "medium" -> "中"; "high" -> "高"; else -> "默认" } },
                    !state.busy,
                ) { openRouterEffort = it }
            }
            if (protocol == ApiProtocol.OLLAMA) {
                GroupToggle("使用 Text Completion（/api/generate）", textCompletion, !state.busy) { textCompletion = it }
            }
            if (draft().supportsTextSampling) {
                OutlinedButton({ editingTextSampling = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("采样预设 · ${textSampling.default?.name ?: "提供商未设默认"}")
                }
            }
            if (protocol == ApiProtocol.NOVELAI_TEXT) {
                val selected = NovelAiModel.from(defaultModel.trim())
                OutlinedButton({ editingNovelAi = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("NovelAI 采样预设 · ${selected?.let { novelAi.preset(it).name } ?: "按模型设置"}")
                }
            }
            if (draft().supportsTokenBias) {
                val selected = tokenBias.forModel(defaultModel.trim()).presetId
                OutlinedButton({ editingTokenBias = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("词元偏置 · ${tokenBias.presets.firstOrNull { it.id == selected }?.name ?: "关闭"}")
                }
            }
            if (draft().usesTextCompletion) {
                OutlinedButton({ editingModelFormatting = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("按模型切换模板")
                }
                OutlinedButton({ editingTextContext = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("文本补全上下文模板 · ${textContext?.name ?: "未启用"}")
                }
                OutlinedButton({ editingInstruct = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("文本补全指令模板 · ${instruct?.name ?: "未启用"}")
                }
            }
        }
        Button({ model.saveProvider(draft(), close) },
            enabled = canSave && !state.busy && (contextLimit.isBlank() || context != null) &&
                (thinkingBudget.isBlank() || thinking != null),
            modifier = Modifier.fillMaxWidth()) { Text("保存提供商") }
        if (saved) TextButton({ remove = true }, enabled = !state.busy) { Text("移除此提供商") }
    }

    val catalog = state.catalog?.takeIf { it.providerId == provider.id }
    if (catalog != null && catalog.models.isNotEmpty()) {
        ModelCatalogDialog(catalog.models, model::clearModels) { choice ->
            knownModels = (draft().models + catalog.models).distinct().joinToString("\n")
            contexts = HashMap(catalog.contexts)
            contextEndpoint = catalog.endpoint
            catalog.novelAiTier?.let { tier ->
                novelAi = novelAi.copy(tier = tier)
                novelAiRoute = novelRoute
            }
            catalog.hordeOutputs?.let { outputs ->
                horde = horde.copy(modelOutputs = outputs)
                hordeCatalogTrust = horde.trustedOnly
            }
            defaultModel = choice
            model.clearModels()
        }
    }
    if (remove) Confirm("移除提供商？", "使用该提供商的对话会改用应用默认模型。", { remove = false }) {
        remove = false
        model.deleteProvider(provider, close)
    }
    if (editingMultiKeys) {
        MultiKeyManagerDialog(
            provider = draft(),
            initialConfig = multiKeys,
            primaryApiKey = apiKey,
            onDismiss = { editingMultiKeys = false },
            onSave = { updated ->
                multiKeys = updated
                if (apiKey.isBlank()) {
                    updated.enabledKeys.firstOrNull()?.secret?.let { apiKey = it }
                }
                editingMultiKeys = false
            },
        )
    }
    if (tabbyControlOpen) {
        TabbyModelControlDialog(
            provider = draft(),
            onDismiss = { tabbyControlOpen = false },
            onModelLoaded = { loadedName, maxSeq ->
                if (loadedName.isNotBlank()) {
                    defaultModel = loadedName
                    val updatedKnown = (knownModels.lineSequence().map(String::trim).filter(String::isNotBlank).toList() + loadedName).distinct()
                    knownModels = updatedKnown.joinToString("\n")
                    if (maxSeq != null && maxSeq in 1..MAX_CONTEXT_TOKENS) {
                        val copy = HashMap(contexts)
                        copy[loadedName] = maxSeq
                        contexts = copy
                        contextEndpoint = endpoint.trim()
                    }
                }
            },
        )
    }
}

@Composable
private fun ModelCatalogDialog(models: List<String>, close: () -> Unit, select: (String) -> Unit) {
    var filter by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = close, title = { Text("选择默认模型") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("筛选模型", filter, { filter = it })
            LazyColumn(Modifier.heightIn(max = 280.dp)) {
                items(models.filter { it.contains(filter, true) }) { item ->
                    TextButton(onClick = { select(item) }) {
                        Text(if (item == HORDE_AUTO_MODEL) "AI Horde 自动分配" else item,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }, confirmButton = { TextButton(close) { Text("取消") } })
}

@Composable
internal fun ServerScreen(state: TavernState, model: TavernViewModel) {
    var address by rememberSaveable { mutableStateOf(state.prefs.server.ifBlank { state.prefs.fullTavernAddress }) }
    var handle by rememberSaveable { mutableStateOf(state.prefs.handle) }
    var password by remember { mutableStateOf("") }
    var basicUser by rememberSaveable { mutableStateOf("") }
    var basicPassword by remember { mutableStateOf("") }
    var showBasic by rememberSaveable { mutableStateOf(false) }
    var withChats by rememberSaveable { mutableStateOf(true) }
    val pickBackup = rememberLauncherForActivityResult(OpenDocument()) { uri -> uri?.let { model.importOriginalBackup(it) } }
    val saveBackup = rememberLauncherForActivityResult(CreateDocument("application/zip")) { uri ->
        uri?.let { model.exportOriginalBackup(it) }
    }
    val cards = state.remoteCards
    if (cards == null) {
        Form {
            FilledTonalButton({ pickBackup.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.FolderZip, null)
                Spacer(Modifier.width(8.dp))
                Text("导入原版账户备份 ZIP")
            }
            FilledTonalButton({ saveBackup.launch("Tavern-Native-${java.time.LocalDate.now()}.zip") },
                enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.FileUpload, null)
                Spacer(Modifier.width(8.dp))
                Text("导出本机账户备份 ZIP")
            }
            Text("角色、聊天和关联资料随备份保存。提供商 API Key 和连接密码由设备单独管理。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.utility?.let { progress ->
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(progress)
                TextButton(model::stop) { Text("停止") }
            }
            HorizontalDivider()
            FormHeading("连接现有实例", "选择要复制到本机的角色，也可以带上该角色的聊天记录。")
            if (model.canImportEmbedded) {
                FilledTonalButton(model::importEmbedded, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.FileDownload, null)
                    Spacer(Modifier.width(8.dp))
                    Text("导入本机预览版酒馆数据")
                }
                HorizontalDivider()
            }
            Field("SillyTavern 地址", address, { address = it }, placeholder = "http://192.168.1.20:8000",
                enabled = !state.busy, type = KeyboardType.Uri)
            Field("账号名称（可选）", handle, { handle = it }, enabled = !state.busy)
            if (handle.isNotBlank()) Field("账号密码", password, { password = it }, secret = true, enabled = !state.busy)
            TextButton({ showBasic = !showBasic }, enabled = !state.busy) {
                Text(if (showBasic) "收起 HTTP Basic Auth" else "HTTP Basic Auth（可选）")
            }
            if (showBasic) {
                Field("Basic Auth 用户名", basicUser, { basicUser = it }, enabled = !state.busy)
                Field("Basic Auth 密码", basicPassword, { basicPassword = it }, secret = true, enabled = !state.busy)
            }
            Button(onClick = {
                model.listRemote(address, handle, password, basicUser.takeIf { showBasic }.orEmpty(),
                    basicPassword.takeIf { showBasic }.orEmpty())
            }, enabled = !state.busy && address.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Link, null); Spacer(Modifier.width(8.dp)); Text("连接")
            }
            OutlinedButton(onClick = {
                model.syncLanServer(
                    address = address,
                    handle = handle,
                    password = password,
                    basicUser = basicUser.takeIf { showBasic }.orEmpty(),
                    basicPass = basicPassword.takeIf { showBasic }.orEmpty(),
                    includeChats = withChats,
                )
            }, enabled = !state.busy && address.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Sync, null); Spacer(Modifier.width(8.dp)); Text("双向同步角色与聊天")
            }
        }
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 700.dp).fillMaxWidth(), contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    FormHeading("选择角色", "${cards.size} 个角色 · 再次导入会补充新的聊天")
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("同时导入聊天记录", modifier = Modifier.weight(1f))
                        Switch(withChats, { withChats = it }, enabled = !state.busy)
                    }
                    TextButton(model::clearRemote, enabled = !state.busy) { Text("更换连接") }
                }
                if (cards.isEmpty()) item { Text("这个实例还没有角色。") }
                items(cards, key = RemoteCard::avatar) { card ->
                    Card(onClick = { model.importRemote(card, withChats) }, enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Outlined.PersonOutline, null, tint = MaterialTheme.colorScheme.primary)
                            Text(card.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Icon(Icons.Outlined.FileDownload, "导入")
                        }
                    }
                }
            }
        }
    }
}

