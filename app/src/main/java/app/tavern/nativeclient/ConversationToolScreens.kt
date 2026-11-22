package app.tavern.nativeclient

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
internal fun TranslationDialog(chat: Chat, state: TavernState, model: TavernViewModel, close: () -> Unit) {
    val saved = chat.options.translation
    var target by rememberSaveable(saved.target) { mutableStateOf(saved.target) }
    var inputLanguage by rememberSaveable(saved.requestLanguage) { mutableStateOf(saved.requestLanguage) }
    var mode by rememberSaveable(saved.mode) { mutableStateOf(saved.mode) }
    val enabled = !state.busy
    val options = TranslationOptions(target.trim(), inputLanguage.trim(), mode)
    val valid = validTranslationLanguage(options.target) && validTranslationLanguage(options.requestLanguage)
    AlertDialog(onDismissRequest = { if (enabled) close() }, title = { Text("对话翻译") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SelectField("自动翻译", mode, TranslationMode.entries, TranslationMode::label, enabled) { mode = it }
            Field("显示语言", target, { target = it }, placeholder = "zh-CN", enabled = enabled)
            Field("自动发送时使用的语言", inputLanguage, { inputLanguage = it }, placeholder = "en", enabled = enabled)
            val service = state.prefs.translationServices
            val name = service.model?.model?.takeIf { service.source == TranslationSource.MODEL } ?: service.source.label
            Text("翻译来源为 $name，可在「设置 → 翻译服务」调整。自动翻译发送内容时会保留输入原文。",
                style = MaterialTheme.typography.bodySmall)
            TextButton({ model.translateChat(options) }, enabled = enabled && valid && chat.messages.any { it.role != "system" && it.text.isNotBlank() }) {
                Text("翻译全部消息")
            }
            TextButton({ model.clearTranslations() }, enabled = enabled && chat.messages.any { it.translation != null }) { Text("清除显示译文") }
            UtilityProgress(state.utility)
        }
    }, dismissButton = { TextButton(if (state.utility != null) model::stop else close, enabled = enabled || state.utility != null) {
        Text(if (state.utility != null) "停止" else "关闭")
    } }, confirmButton = { Button({ model.saveTranslation(options, close) }, enabled = enabled && valid) { Text("保存") } })
}

@Composable
internal fun MemoryDialog(chat: Chat, state: TavernState, model: TavernViewModel, close: () -> Unit) {
    val saved = chat.options.memory
    val currentSummary = remember(chat.messages) { chat.messages.currentSummary()?.summary?.text.orEmpty() }
    var enabledMemory by rememberSaveable(saved.enabled) { mutableStateOf(saved.enabled) }
    var paused by rememberSaveable(saved.paused) { mutableStateOf(saved.paused) }
    var position by rememberSaveable(saved.position) { mutableStateOf(saved.position) }
    var role by rememberSaveable(saved.role) { mutableStateOf(saved.role) }
    var depth by rememberSaveable(saved.depth) { mutableStateOf(saved.depth.toString()) }
    var interval by rememberSaveable(saved.interval) { mutableStateOf(saved.interval.toString()) }
    var triggerWords by rememberSaveable(saved.triggerWords) { mutableStateOf(saved.triggerWords.toString()) }
    var targetWords by rememberSaveable(saved.targetWords) { mutableStateOf(saved.targetWords.toString()) }
    var maxMessages by rememberSaveable(saved.maxMessages) { mutableStateOf(saved.maxMessages.toString()) }
    var scanLore by rememberSaveable(saved.scanLore) { mutableStateOf(saved.scanLore) }
    var source by rememberSaveable(saved.source) { mutableStateOf(saved.source) }
    var maxTokens by rememberSaveable(saved.maxTokens) { mutableStateOf(saved.maxTokens.toString()) }
    var template by rememberSaveable(saved.template) { mutableStateOf(saved.template) }
    var instruction by rememberSaveable(saved.instruction) { mutableStateOf(saved.instruction) }
    var providerId by rememberSaveable(saved.model) { mutableStateOf(saved.model?.providerId) }
    var modelId by rememberSaveable(saved.model) { mutableStateOf(saved.model?.model.orEmpty()) }
    var text by rememberSaveable(currentSummary) { mutableStateOf(currentSummary) }
    val enabled = !state.busy
    val depthValue = depth.toIntOrNull()?.takeIf { it in 0..80 }
    val intervalValue = interval.toIntOrNull()?.takeIf { it in 0..250 }
    val triggerValue = triggerWords.toIntOrNull()?.takeIf { it in 0..10_000 }
    val targetValue = targetWords.toIntOrNull()?.takeIf { it in 0..1000 }
    val maxMessageValue = maxMessages.toIntOrNull()?.takeIf { it in 0..250 }
    val tokenValue = maxTokens.toIntOrNull()?.takeIf { it in 64..4096 }
    val selection = providerId?.let { ModelSelection(it, modelId.trim()) }
    val usesModel = source == SummarySource.MODEL
    val ready = usesModel || state.prefs.summaryExtras.endpoint.isNotBlank()
    val modelFieldsValid = !usesModel || (tokenValue != null && targetValue != null && selection?.model?.isBlank() != true)
    val options = if (intervalValue == null || triggerValue == null || maxMessageValue == null || !modelFieldsValid ||
        depthValue == null && position == MemoryPosition.DEPTH) null
        else saved.copy(enabled = enabledMemory, paused = paused, interval = intervalValue, position = position,
            role = role, depth = depthValue ?: saved.depth, template = template,
            instruction = instruction.trim().ifBlank { DEFAULT_MEMORY_PROMPT }, maxTokens = tokenValue ?: saved.maxTokens,
            model = selection, source = source, triggerWords = triggerValue, targetWords = targetValue ?: saved.targetWords,
            maxMessages = maxMessageValue, scanLore = scanLore)
    AlertDialog(onDismissRequest = { if (enabled) close() }, title = { Text("摘要记忆") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("当前摘要", text, { text = it }, lines = 4, enabled = enabled)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ options?.let(model::summarizeChat) }, enabled = enabled && ready && options != null && chat.messages.isNotEmpty()) {
                    Text("更新摘要")
                }
                TextButton(model::restoreMemory, enabled = enabled && currentSummary.isNotBlank()) { Text("恢复上一版") }
            }
            UtilityProgress(state.utility)
            SelectField("摘要来源", source, SummarySource.entries, SummarySource::label, enabled) { source = it }
            if (usesModel) {
                UtilityModelSelector("摘要", state.prefs.providers, selection, enabled) {
                    providerId = it?.providerId
                    modelId = it?.model.orEmpty()
                }
            } else if (!ready) {
                Text("请先在「设置 → 摘要服务」填写 Extras 服务地址。", style = MaterialTheme.typography.bodySmall)
            }
            GroupToggle("将摘要注入模型请求", enabledMemory, enabled) { enabledMemory = it }
            GroupToggle("让最新摘要参与世界书扫描", scanLore, enabled) { scanLore = it }
            GroupToggle("暂停自动摘要", paused, enabled) { paused = it }
            Field("新消息触发条数（0 为关闭）", interval, { interval = it }, type = KeyboardType.Number, enabled = enabled)
            Field("新消息触发词数（0 为关闭）", triggerWords, { triggerWords = it }, type = KeyboardType.Number, enabled = enabled)
            Field("每次最多处理的消息条数（0 为自动）", maxMessages, { maxMessages = it }, type = KeyboardType.Number, enabled = enabled)
            SelectField("注入位置", position, MemoryPosition.entries, MemoryPosition::label, enabled) { position = it }
            if (position == MemoryPosition.DEPTH) {
                SelectField("注入角色", role, PromptRole.entries, PromptRole::label, enabled) { role = it }
                Field("注入深度", depth, { depth = it }, type = KeyboardType.Number, enabled = enabled)
            }
            Field("注入模板（使用 {{summary}}）", template, { template = it }, lines = 2, enabled = enabled)
            if (usesModel) {
                Field("摘要目标词数（0 使用指令）", targetWords, { targetWords = it }, type = KeyboardType.Number, enabled = enabled)
                Field("最大摘要 Token（64–4096）", maxTokens, { maxTokens = it }, type = KeyboardType.Number, enabled = enabled)
                Field("摘要指令", instruction, { instruction = it }, lines = 3, enabled = enabled)
            }
        }
    }, dismissButton = { TextButton(if (state.utility != null) model::stop else close, enabled = enabled || state.utility != null) {
        Text(if (state.utility != null) "停止" else "关闭")
    } }, confirmButton = { Button({ options?.let { model.saveMemory(it, text, close) } }, enabled = enabled && options != null) { Text("保存") } })
}

@Composable
internal fun SummaryExtrasScreen(state: TavernState, model: TavernViewModel, close: () -> Unit) {
    val saved = state.prefs.summaryExtras
    var endpoint by rememberSaveable(saved.endpoint) { mutableStateOf(saved.endpoint) }
    var key by remember(saved.apiKey) { mutableStateOf(saved.apiKey) }
    val enabled = !state.busy
    BackHandler { if (enabled) close() }
    Form {
        FormHeading("摘要服务", "在对话的摘要记忆中选择 SillyTavern Extras，即可把摘要请求发送到此服务。")
        Field("Extras 服务地址", endpoint, { endpoint = it }, enabled = enabled, type = KeyboardType.Uri,
            placeholder = "http://192.168.1.20:5100")
        Field("API Key（如无鉴权可留空）", key, { key = it }, secret = true, enabled = enabled)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(close, enabled = enabled) { Text("取消") }
            Button({ model.saveSummaryExtras(SummaryExtrasSettings(endpoint, key), close) },
                enabled = enabled && (endpoint.isNotBlank() || key.isBlank())) { Text("保存") }
        }
    }
}

@Composable
internal fun TranslationServiceSettings(state: TavernState, model: TavernViewModel, close: () -> Unit) {
    val saved = state.prefs.translationServices
    var settings by remember(saved) { mutableStateOf(saved) }
    val source = settings.source
    val profile = settings.profile()
    val enabled = !state.busy
    val modelValid = source != TranslationSource.MODEL || settings.model?.model?.isBlank() != true
    val ready = source.requiresKey.not() || profile.key.isNotBlank()
    val addressReady = source.addressHint == null || source == TranslationSource.LINGVA || profile.endpoint.isNotBlank()
    fun editProfile(updated: TranslationProfile) { settings = settings.copy(profiles = settings.profiles + (source to updated)) }
    BackHandler { if (enabled) close() }
    Form {
        FormHeading("翻译服务")
        SelectField("翻译来源", source, TranslationSource.entries, TranslationSource::label, enabled) {
            settings = settings.copy(source = it)
        }
        if (source == TranslationSource.MODEL) {
            UtilityModelSelector("翻译", state.prefs.providers, settings.model, enabled) { settings = settings.copy(model = it) }
        } else {
            source.addressHint?.let { hint ->
                Field(if (source == TranslationSource.LINGVA) "实例地址（选填）" else "翻译接口地址",
                    profile.endpoint, { editProfile(profile.copy(endpoint = it)) }, placeholder = hint, enabled = enabled)
            }
            if (source.acceptsKey) {
                val label = when {
                    source == TranslationSource.GOOGLE -> "Google Cloud Translation API Key（选填）"
                    source == TranslationSource.YANDEX -> "Yandex Cloud API Key（选填）"
                    source == TranslationSource.MICROSOFT -> "Azure Translator API Key（选填）"
                    source.requiresKey -> "API Key"
                    else -> "API Key（选填）"
                }
                Field(label, profile.key, { editProfile(profile.copy(key = it)) }, secret = true, enabled = enabled)
            }
            if (source == TranslationSource.MICROSOFT && profile.key.isNotBlank()) {
                Field("Azure 资源区域（全球资源可留空）", profile.region, { editProfile(profile.copy(region = it)) },
                    placeholder = "eastasia", enabled = enabled)
            }
            if (source == TranslationSource.MICROSOFT) {
                Text("留空时使用 Bing 翻译。填写密钥后改用 Azure Translator。", style = MaterialTheme.typography.bodySmall)
            }
            if (source == TranslationSource.YANDEX) {
                Text("留空时使用原版兼容的 Yandex 翻译接口。填写服务账号密钥后改用 Yandex Cloud。",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (source == TranslationSource.GOOGLE) {
                Text("留空时使用原版兼容的 Google 翻译接口。填写密钥后改用 Cloud Translation。",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (source == TranslationSource.DEEPL) {
                GroupToggle("DeepL API Pro", profile.pro, enabled) { editProfile(profile.copy(pro = it)) }
                SelectField("正式程度", profile.formality, TranslationFormality.entries, TranslationFormality::label, enabled) {
                    editProfile(profile.copy(formality = it))
                }
            }
        }
        Text("原文会交给所选服务翻译。每段对话可以分别设置显示语言和发送语言。",
            style = MaterialTheme.typography.bodySmall)
        val cleaned = settings.copy(model = settings.model?.let { it.copy(model = it.model.trim()) })
        OutlinedButton({ model.testTranslationService(cleaned) }, enabled = enabled && modelValid && ready && addressReady) {
            Text("测试翻译")
        }
        UtilityProgress(state.utility)
        if (state.utility != null) TextButton(model::stop) { Text("停止") }
        Button({ model.saveTranslationServices(cleaned, close) },
            enabled = enabled && modelValid && ready && addressReady, modifier = Modifier.fillMaxWidth()) {
            Text("保存翻译服务")
        }
    }
}

@Composable
private fun UtilityModelSelector(subject: String, allProviders: List<Provider>, selection: ModelSelection?, enabled: Boolean,
                                 select: (ModelSelection?) -> Unit) {
    val providers = allProviders.filter(Provider::ready)
    val provider = providers.firstOrNull { it.id == selection?.providerId }
    SelectField("${subject}提供商", selection?.providerId.orEmpty(), listOf("") + providers.map(Provider::id), { id ->
        if (id.isEmpty()) "跟随当前对话模型" else providers.firstOrNull { it.id == id }?.name ?: "提供商不可用"
    }, enabled) { id -> select(providers.firstOrNull { it.id == id }?.defaultSelection) }
    if (provider != null && selection != null) {
        val models = (provider.choices + selection.model).distinct()
        if (models.size > 1) {
            SelectField("已知模型", selection.model, models, { it.ifBlank { "填写模型 ID" } }, enabled) {
                select(selection.copy(model = it))
            }
        }
        Field("${subject}模型 ID", selection.model, { select(selection.copy(model = it)) }, enabled = enabled)
    }
}

@Composable
private fun UtilityProgress(label: String?) {
    if (label != null) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}
