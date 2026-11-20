package app.tavern.nativeclient

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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

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
    OutlinedTextField(value, change, modifier = Modifier.fillMaxWidth(),
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
internal fun CardEditor(card: Card, busy: Boolean, books: List<WorldBook>, save: (Card) -> Unit) {
    var name by rememberSaveable { mutableStateOf(card.name) }
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
    var authorNote by rememberSaveable { mutableStateOf(card.authorNote.text) }
    var authorOrder by rememberSaveable { mutableStateOf(card.authorNote.order) }
    var embeddedTags by rememberSaveable { mutableStateOf(card.tags.joinToString("\n")) }
    val depth = promptDepth.toIntOrNull()?.takeIf { it in 0..80 }
    Form {
        Field("角色名称", name, { name = it }, enabled = !busy)
        Field("角色描述", description, { description = it }, lines = 3, enabled = !busy)
        Field("角色卡内嵌标签", embeddedTags, { embeddedTags = it }, lines = 2, enabled = !busy, placeholder = "每行一个标签")
        Field("性格", personality, { personality = it }, lines = 2, enabled = !busy)
        Field("场景", scenario, { scenario = it }, lines = 2, enabled = !busy)
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
        Field("角色专属作者注释（可选）", authorNote, { authorNote = it }, lines = 2, enabled = !busy)
        if (authorNote.isNotBlank()) SelectField("和对话注释组合", authorOrder, CharacterNoteOrder.entries,
            CharacterNoteOrder::label, !busy) { authorOrder = it }
        if (books.isNotEmpty() || world.isNotBlank()) {
            SelectField("角色的世界书", world, (listOf("") + books.map(WorldBook::name) + world).distinct(),
                { it.ifBlank { "未绑定" } }, enabled = !busy) { world = it }
        }
        Button(enabled = name.isNotBlank() && !busy && (depthPrompt.isBlank() || depth != null),
            modifier = Modifier.fillMaxWidth(), onClick = {
            save(card.edited(mapOf("name" to name.trim(), "description" to description, "personality" to personality,
                "scenario" to scenario, "first_mes" to greeting, "mes_example" to examples,
                "system_prompt" to system, "post_history_instructions" to post),
                alternates.split(Regex("(?m)^===$")).map(String::trim).filter(String::isNotBlank))
                .withWorld(world).withAuthorNote(CharacterNote(authorNote, authorOrder))
                .withCreatorTags(embeddedTags.lineSequence().map(String::trim).filter(String::isNotBlank).distinct().toList())
                .withDepthPrompt(depthPrompt, depth ?: card.depthPromptDepth, promptRole))
        }) { Text("保存角色") }
    }
}

@Composable
internal fun SettingsScreen(state: TavernState, model: TavernViewModel) {
    val prefs = state.prefs
    var editor by rememberSaveable { mutableStateOf<String?>(null) }
    var draftId by rememberSaveable { mutableStateOf("") }
    var personaSettings by rememberSaveable { mutableStateOf(false) }
    var promptSettings by rememberSaveable { mutableStateOf(false) }
    var regexSettings by rememberSaveable { mutableStateOf(false) }
    var quickSettings by rememberSaveable { mutableStateOf(false) }
    var voiceSettings by rememberSaveable { mutableStateOf(false) }
    var vectorSettings by rememberSaveable { mutableStateOf(false) }
    var captionSettings by rememberSaveable { mutableStateOf(false) }
    var translationSettings by rememberSaveable { mutableStateOf(false) }
    var dataBank by rememberSaveable { mutableStateOf(false) }
    var noteSettings by rememberSaveable { mutableStateOf(false) }
    var replyAutomation by rememberSaveable { mutableStateOf(false) }
    var comfyWorkflows by rememberSaveable { mutableStateOf(false) }
    var temperature by rememberSaveable(prefs.temperature) { mutableStateOf(prefs.temperature) }
    var maxTokens by rememberSaveable(prefs.maxTokens) { mutableStateOf(prefs.maxTokens) }
    var reasoning by rememberSaveable(prefs.includeReasoning) { mutableStateOf(prefs.includeReasoning) }
    var toolCalling by rememberSaveable(prefs.toolCalling) { mutableStateOf(prefs.toolCalling) }
    var theme by rememberSaveable(prefs.theme) { mutableStateOf(prefs.theme) }
    var dynamic by rememberSaveable(prefs.dynamicColor) { mutableStateOf(prefs.dynamicColor) }
    val selected = editor
    if (personaSettings) {
        PersonaSettings(state, model) { personaSettings = false }
        return
    }
    if (promptSettings) {
        PromptPresetLibrary(state, model) { promptSettings = false }
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
    if (dataBank) {
        DataBankPanel(state, model, fromChat = false, close = { dataBank = false }, configure = {
            dataBank = false; vectorSettings = true
        })
        return
    }
    if (selected != null) {
        val saved = prefs.providers.firstOrNull { it.id == selected }
        val draft = saved ?: ProviderTemplate.entries.firstOrNull { it.id == selected }?.profile() ?: Provider(draftId)
        fun close() { model.clearModels(); editor = null }
        androidx.activity.compose.BackHandler { if (!state.busy) close() }
        key(selected) {
            ProviderEditor(draft, saved != null, state, model, ::close)
        }
        return
    }
    Form {
        FormHeading("模型提供商", "内置模板填入 API Key 即可使用。自定义项支持 OpenAI 兼容服务。")
        val defaults = prefs.connectionFor()
        ProviderTemplate.entries.forEach { template ->
            val saved = prefs.providers.firstOrNull { it.id == template.id }
            ProviderRow(saved ?: template.profile(), saved != null, defaults?.provider?.id == template.id,
                state.busy, open = { editor = template.id }, makeDefault = { saved?.let(model::setDefaultProvider) })
        }
        prefs.providers.filter { it.template == null }.forEach { provider ->
            ProviderRow(provider, true, defaults?.provider?.id == provider.id, state.busy,
                open = { editor = provider.id }, makeDefault = { model.setDefaultProvider(provider) })
        }
        OutlinedButton(onClick = { draftId = newId(); editor = draftId }, enabled = !state.busy) {
            Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("添加自定义提供商")
        }
        HorizontalDivider()
        FormHeading("生成参数")
        OutlinedButton({ promptSettings = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Tune, null)
            Spacer(Modifier.width(8.dp))
            Text("提示词预设 · ${state.presetFor(null)?.name ?: "内置基础"}")
        }
        Field("温度（可选）", temperature, { temperature = it }, placeholder = "留空使用服务默认值",
            enabled = !state.busy, type = KeyboardType.Decimal)
        Field("最大输出 Token（可选）", maxTokens, { maxTokens = it }, placeholder = "留空使用服务默认值",
            enabled = !state.busy, type = KeyboardType.Number)
        GroupToggle("在后续请求中附带已保存的推理", reasoning, !state.busy) { reasoning = it }
        GroupToggle("允许对话模型调用函数工具", toolCalling, !state.busy) { toolCalling = it }
        OutlinedButton({ comfyWorkflows = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text("ComfyUI 工作流")
        }
        HorizontalDivider()
        FormHeading("对话")
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
        OutlinedButton({ dataBank = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Article, null)
            Spacer(Modifier.width(8.dp))
            Text("资料库")
        }
        HorizontalDivider()
        FormHeading("外观")
        GroupToggle("视觉小说模式", prefs.visualNovel, !state.busy, model::setVisualNovel)
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
                toolCalling = toolCalling))
        }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("保存设置") }
    }
    if (noteSettings) AuthorNoteEditor(prefs.defaultAuthorNote, "默认作者注释", state.busy, { noteSettings = false }) {
        model.setDefaultAuthorNote(it) { noteSettings = false }
    }
    if (replyAutomation) ReplyAutomationDialog(prefs.replyAutomation, state.busy, { replyAutomation = false }) {
        model.setReplyAutomation(it) { replyAutomation = false }
    }
    if (comfyWorkflows) ComfyWorkflowDialog(state, model, close = { comfyWorkflows = false })
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
    var advanced by rememberSaveable { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    val template = provider.template
    val context = contextLimit.trim().toIntOrNull()?.takeIf { it in 1..MAX_CONTEXT_TOKENS }
    fun draft() = provider.copy(name = name, endpoint = endpoint, apiKey = apiKey, defaultModel = defaultModel,
        models = knownModels.lineSequence().map(String::trim).filter(String::isNotBlank).toList(), streaming = streaming,
        contextLimit = context, modelContexts = contexts.takeIf { endpoint.trim() == contextEndpoint }.orEmpty())
    val canSave = if (template == null) name.isNotBlank() && endpoint.isNotBlank() && defaultModel.isNotBlank()
        else apiKey.isNotBlank()
    Form {
        TextButton(close, enabled = !state.busy) { Text("返回提供商列表") }
        FormHeading(if (template != null) template.label else if (saved) "编辑自定义提供商" else "添加自定义提供商",
            template?.let { "默认模型为 ${it.defaultModel}。填写 Key 后即可保存。" })
        if (template == null) {
            Field("提供商名称", name, { name = it }, enabled = !state.busy)
            Field("API 地址", endpoint, { endpoint = it }, placeholder = "https://api.example.com/v1",
                enabled = !state.busy, type = KeyboardType.Uri)
        }
        Field(if (template == null) "API Key（可选）" else "API Key",
            apiKey, { apiKey = it }, secret = true, enabled = !state.busy)
        if (template != null) {
            TextButton(onClick = { advanced = !advanced }, enabled = !state.busy) {
                Text(if (advanced) "收起模型选项" else "模型选项")
            }
        }
        if (template == null || advanced) {
            Field("默认模型 ID", defaultModel, { defaultModel = it }, enabled = !state.busy)
            OutlinedButton({ model.loadModels(draft()) }, enabled = !state.busy && endpoint.isNotBlank()) {
                Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(8.dp)); Text("读取模型列表")
            }
            Field("快捷切换的模型（每行一个）", knownModels, { knownModels = it }, lines = 3, enabled = !state.busy)
            Field("上下文 Token 上限（选填）", contextLimit, { contextLimit = it }, enabled = !state.busy,
                placeholder = "自动采用模型额度", type = KeyboardType.Number)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("流式回复", Modifier.weight(1f))
                Switch(streaming, { streaming = it }, enabled = !state.busy)
            }
        }
        Button({ model.saveProvider(draft(), close) }, enabled = canSave && !state.busy && (contextLimit.isBlank() || context != null),
            modifier = Modifier.fillMaxWidth()) { Text("保存提供商") }
        if (saved) TextButton({ remove = true }, enabled = !state.busy) { Text("移除此提供商") }
    }

    val catalog = state.catalog?.takeIf { it.providerId == provider.id }
    if (catalog != null && catalog.models.isNotEmpty()) {
        ModelCatalogDialog(catalog.models, model::clearModels) { choice ->
            knownModels = (draft().models + catalog.models).distinct().joinToString("\n")
            contexts = HashMap(catalog.contexts)
            contextEndpoint = catalog.endpoint
            defaultModel = choice
            model.clearModels()
        }
    }
    if (remove) Confirm("移除提供商？", "使用该提供商的对话会改用应用默认模型。", { remove = false }) {
        remove = false
        model.deleteProvider(provider, close)
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
                        Text(item, maxLines = 2, overflow = TextOverflow.Ellipsis)
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

