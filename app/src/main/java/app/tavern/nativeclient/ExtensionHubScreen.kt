package app.tavern.nativeclient

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tavern.nativeclient.script.CardSandboxPolicy
import app.tavern.nativeclient.script.MvuEngine

private enum class ExtensionSubScreen {
    HUB,
    QUICK_PROMPTS,
    STATUS_INSPECTOR,
    CHARACTER_LORE,
    WEB_SEARCH,
    DYNAMIC_AUDIO,
    ASSETS,
    CARD_SANDBOX,
}

@Composable
internal fun ExtensionHubScreen(
    state: TavernState,
    model: TavernViewModel,
    close: () -> Unit,
) {
    var subScreen by rememberSaveable { mutableStateOf(ExtensionSubScreen.HUB) }
    val context = LocalContext.current
    val config = state.prefs.extensions

    val exportJson = rememberLauncherForActivityResult(CreateDocument("application/json")) { uri ->
        if (uri != null) {
            runCatching {
                val payload = config.exportPortableJson().toString(2).toByteArray(Charsets.UTF_8)
                context.contentResolver.openOutputStream(uri)?.use { it.write(payload) }
            }.onSuccess {
                model.notice("已导出扩展配置 JSON")
            }.onFailure {
                model.notice("导出失败：${it.message ?: "写入错误"}")
            }
        }
    }

    val importJson = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val text = context.contentResolver.openInputStream(uri)?.use { it.readLimited(4 * 1024 * 1024).decodeToString() }
                    ?: error("无法读取文件")
                val imported = ExtensionHubConfig.importPortableJson(text, config)
                model.saveExtensions(imported)
            }.onSuccess {
                model.notice("已导入扩展配置")
            }.onFailure {
                model.notice("导入失败：${it.message ?: "格式错误"}")
            }
        }
    }

    when (subScreen) {
        ExtensionSubScreen.WEB_SEARCH -> {
            WebSearchSettingsScreen(state, model) { subScreen = ExtensionSubScreen.HUB }
            return
        }
        ExtensionSubScreen.DYNAMIC_AUDIO -> {
            DynamicAudioScreen(state, model) { subScreen = ExtensionSubScreen.HUB }
            return
        }
        ExtensionSubScreen.ASSETS -> {
            AssetsScreen(state, model) { subScreen = ExtensionSubScreen.HUB }
            return
        }
        ExtensionSubScreen.QUICK_PROMPTS -> {
            QuickPromptTogglesPanel(state, model) { subScreen = ExtensionSubScreen.HUB }
            return
        }
        ExtensionSubScreen.STATUS_INSPECTOR -> {
            ChatVariablesInspectorPanel(state) { subScreen = ExtensionSubScreen.HUB }
            return
        }
        ExtensionSubScreen.CHARACTER_LORE -> {
            CharacterLoreHelperPanel(state) { subScreen = ExtensionSubScreen.HUB }
            return
        }
        ExtensionSubScreen.CARD_SANDBOX -> {
            CardSandboxPermissionsPanel(state, model) { subScreen = ExtensionSubScreen.HUB }
            return
        }
        ExtensionSubScreen.HUB -> Unit
    }

    BackHandler(onBack = close)
    Form {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionIcon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", !state.busy, close)
            Text("扩展中心 (Extensions Hub)", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            "统一管理内置与原生移植的 SillyTavern 社区扩展，配置可随设置快照一起便携导入导出。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { exportJson.launch("tavern-extensions.json") },
                enabled = !state.busy,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Outlined.FileUpload, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("导出配置 JSON")
            }
            OutlinedButton(
                onClick = { importJson.launch(arrayOf("application/json", "*/*")) },
                enabled = !state.busy,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Outlined.FileDownload, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("导入配置 JSON")
            }
        }

        HorizontalDivider()
        BuiltInExtensionId.entries.forEach { ext ->
            val enabled = config.isEnabled(ext)
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(ext.displayName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                ext.category.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = { checked ->
                                model.saveExtensions(config.withEnabled(ext, checked))
                                if (ext == BuiltInExtensionId.CARD_SANDBOX) {
                                    model.setCardScriptsEnabled(checked)
                                }
                            },
                            enabled = !state.busy,
                        )
                    }
                    Text(
                        ext.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            subScreen = when (ext) {
                                BuiltInExtensionId.QUICK_PROMPT_TOGGLES -> ExtensionSubScreen.QUICK_PROMPTS
                                BuiltInExtensionId.CHAT_STATUS_INSPECTOR -> ExtensionSubScreen.STATUS_INSPECTOR
                                BuiltInExtensionId.CHARACTER_LORE_HELPER -> ExtensionSubScreen.CHARACTER_LORE
                                BuiltInExtensionId.WEB_SEARCH -> ExtensionSubScreen.WEB_SEARCH
                                BuiltInExtensionId.DYNAMIC_AUDIO -> ExtensionSubScreen.DYNAMIC_AUDIO
                                BuiltInExtensionId.ASSETS_DOWNLOADER -> ExtensionSubScreen.ASSETS
                                BuiltInExtensionId.CARD_SANDBOX -> ExtensionSubScreen.CARD_SANDBOX
                            }
                        },
                        enabled = !state.busy,
                    ) {
                        Text("配置与详情")
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickPromptTogglesPanel(
    state: TavernState,
    model: TavernViewModel,
    close: () -> Unit,
) {
    BackHandler(onBack = close)
    val config = state.prefs.extensions
    var label by rememberSaveable { mutableStateOf("") }
    var promptText by rememberSaveable { mutableStateOf("") }
    var depth by rememberSaveable { mutableStateOf("4") }

    Form {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionIcon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", !state.busy, close)
            Text("快捷提示词开关", style = MaterialTheme.typography.titleLarge)
        }
        if (config.quickPromptToggles.isEmpty()) {
            Text("暂无快捷提示词开关，可在下方添加。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            config.quickPromptToggles.forEach { item ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.label, style = MaterialTheme.typography.titleSmall)
                            Text(
                                item.promptText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Switch(
                            checked = item.enabled,
                            onCheckedChange = { checked ->
                                val next = config.quickPromptToggles.map { if (it.id == item.id) it.copy(enabled = checked) else it }
                                model.saveExtensions(config.copy(quickPromptToggles = next))
                            },
                        )
                        ActionIcon(Icons.Outlined.Delete, "删除") {
                            model.saveExtensions(config.copy(quickPromptToggles = config.quickPromptToggles.filterNot { it.id == item.id }))
                        }
                    }
                }
            }
        }
        HorizontalDivider()
        Field("开关名称", label, { label = it }, placeholder = "例如：详细心理描写")
        Field("提示词内容", promptText, { promptText = it }, lines = 3)
        Field("注入深度", depth, { depth = it }, type = KeyboardType.Number)
        Button(
            onClick = {
                val item = QuickPromptToggleItem(
                    label = label.trim(),
                    promptText = promptText.trim(),
                    enabled = true,
                    depth = depth.toIntOrNull()?.coerceIn(0, 80) ?: 4,
                )
                model.saveExtensions(config.copy(quickPromptToggles = config.quickPromptToggles + item))
                label = ""
                promptText = ""
            },
            enabled = label.isNotBlank() && promptText.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("添加快捷开关")
        }
    }
}

@Composable
private fun ChatVariablesInspectorPanel(
    state: TavernState,
    close: () -> Unit,
) {
    BackHandler(onBack = close)
    val chat = state.chat
    val mvu = remember(chat, state.card, state.books) {
        if (chat == null) null
        else {
            val (initial, schema) = MvuEngine.resolveInitialStateWithSchema(
                variables = chat.options.variables,
                card = state.card,
                books = state.books,
            )
            MvuEngine.recomputeMvuState(chat.messages, initial = initial, schema = schema)
        }
    }

    Form {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionIcon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", true, close)
            Text("对话状态与变量监视器", style = MaterialTheme.typography.titleLarge)
        }
        FormHeading("全局变量 (${state.prefs.scriptVariables.size})")
        if (state.prefs.scriptVariables.isEmpty()) {
            Text("无全局变量", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.prefs.scriptVariables.forEach { (k, v) ->
                        Text("$k = $v", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        FormHeading("当前对话变量 (${chat?.options?.variables?.size ?: 0})")
        if (chat == null || chat.options.variables.isEmpty()) {
            Text("当前未打开对话或无对话变量", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    chat.options.variables.forEach { (k, v) ->
                        Text("$k = $v", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        FormHeading("MVU 状态树 (stat_data)")
        OutlinedCard(Modifier.fillMaxWidth()) {
            Text(
                text = mvu?.statData?.toString(2) ?: "{}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(12.dp),
            )
        }
        if (mvu != null && mvu.validationIssues.isNotEmpty()) {
            FormHeading("MVU 校验告警 (${mvu.validationIssues.size})")
            mvu.validationIssues.forEach { issue ->
                Text("• [${issue.code}] ${issue.path}: ${issue.message}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun CharacterLoreHelperPanel(
    state: TavernState,
    close: () -> Unit,
) {
    BackHandler(onBack = close)
    val card = state.card
    val initVars = remember(card, state.books) { MvuEngine.extractInitVarEntries(card, state.books) }
    val embedded = remember(card) { card?.embeddedWorldBook() }
    val bound = remember(card, state.books) {
        state.books.filter { it.name == card?.worldName || it.id in card?.additionalWorldBookIds.orEmpty() }
    }

    Form {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionIcon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", true, close)
            Text("角色设定与注释助手", style = MaterialTheme.typography.titleLarge)
        }
        if (card == null) {
            Text("请先打开一个角色或对话以查看其绑定世界书与注释。", style = MaterialTheme.typography.bodyMedium)
            return@Form
        }
        FormHeading("当前角色：${card.name}")
        if (card.authorNote.text.isNotBlank()) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("角色专属作者注释", style = MaterialTheme.typography.titleSmall)
                    Text(card.authorNote.text, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (card.depthPrompt.isNotBlank()) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("角色深度提示 (深度 ${card.depthPromptDepth})", style = MaterialTheme.typography.titleSmall)
                    Text(card.depthPrompt, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        FormHeading("[InitVar] 变量初始化条目 (${initVars.size})")
        if (initVars.isEmpty()) {
            Text("未检测到 [InitVar] 条目。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            initVars.forEach { entry ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(entry.title.ifBlank { "#${entry.id}" }, style = MaterialTheme.typography.titleSmall)
                        Text(entry.content, style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        FormHeading("关联世界书 (${(listOfNotNull(embedded) + bound).size})")
        (listOfNotNull(embedded) + bound).forEach { book ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(book.name, style = MaterialTheme.typography.titleSmall)
                    Text("共 ${book.entries.size} 条目", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun CardSandboxPermissionsPanel(
    state: TavernState,
    model: TavernViewModel,
    close: () -> Unit,
) {
    BackHandler(onBack = close)
    val config = state.prefs.extensions
    val cards = state.cards
    var selectedCardId by rememberSaveable { mutableStateOf(state.card?.id ?: cards.firstOrNull()?.id.orEmpty()) }
    val selectedCard = cards.firstOrNull { it.id == selectedCardId }
    val policy = config.policyForCard(selectedCard?.id, state.prefs.cardScriptsEnabled)
    var domainsText by rememberSaveable(selectedCardId, policy.allowedDomains) {
        mutableStateOf(policy.normalizedDomains.joinToString("\n"))
    }

    fun updatePolicy(next: CardSandboxPolicy) {
        if (selectedCard == null) return
        model.saveExtensions(config.withCardPolicy(selectedCard.id, next))
    }

    Form {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionIcon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", !state.busy, close)
            Text("角色卡沙盒与权限", style = MaterialTheme.typography.titleLarge)
        }
        GroupToggle("全局启用角色卡扩展脚本", state.prefs.cardScriptsEnabled, !state.busy, model::setCardScriptsEnabled)
        if (cards.isEmpty()) {
            Text("角色库为空，导入角色后可为每张角色卡单独配置沙盒权限与域名白名单。", style = MaterialTheme.typography.bodyMedium)
            return@Form
        }
        val activeCard = selectedCard ?: cards.first()
        SelectField("选择角色卡", activeCard, cards, Card::name) {
            selectedCardId = it.id
        }
        GroupToggle("允许该角色卡运行脚本", policy.allowScripts) { updatePolicy(policy.copy(allowScripts = it)) }
        GroupToggle("允许作用域内自定义 CSS", policy.allowCustomCss) { updatePolicy(policy.copy(allowCustomCss = it)) }
        GroupToggle("允许写入变量 (replace/insert/delete)", policy.allowVariableWrite) { updatePolicy(policy.copy(allowVariableWrite = it)) }
        GroupToggle("允许修改聊天消息 (set/create/delete)", policy.allowMessageWrite) { updatePolicy(policy.copy(allowMessageWrite = it)) }
        GroupToggle("允许修改世界书 (create/update/delete)", policy.allowWorldbookWrite) { updatePolicy(policy.copy(allowWorldbookWrite = it)) }
        GroupToggle("允许脚本触发模型生成 (generate/generateRaw)", policy.allowGeneration) { updatePolicy(policy.copy(allowGeneration = it)) }
        GroupToggle("允许白名单域名网络访问（默认完全离线）", policy.allowNetwork) { updatePolicy(policy.copy(allowNetwork = it)) }
        if (policy.allowNetwork) {
            Field(
                label = "网络域名白名单（每行一个，如 cdn.example.com）",
                value = domainsText,
                change = { domainsText = it },
                lines = 3,
            )
            Button(
                onClick = {
                    val domains = domainsText.lineSequence()
                        .mapNotNull(CardSandboxPolicy::normalizeDomainEntry)
                        .distinct()
                        .toList()
                    updatePolicy(policy.copy(allowedDomains = domains))
                    model.notice("已更新 ${activeCard.name} 的网络白名单（${domains.size} 个域名）")
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存域名白名单")
            }
        }
    }
}
