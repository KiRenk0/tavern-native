package app.tavern.nativeclient.provider

import android.net.Uri
import app.tavern.nativeclient.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal class ProviderCoordinator(private val host: CoordinatorHost) {
    private data class ProbeKey(val provider: String, val endpoint: String, val account: Int, val model: String)
    private data class HordeProbeKey(val endpoint: String, val account: Int, val trustedOnly: Boolean)
    private val templateCatalog by lazy { ModelFormatCatalog.load(host.app.assets) }
    private val shippedThemes by lazy {
        host.app.assets.open("st-theme-presets.json").use { readBuiltInChatThemes(JSONArray(it.readLimited(MAX_THEME_JSON).decodeToString())) }
    }
    private val probes = linkedMapOf<ProbeKey, Pair<Long, BackendModelFormatting?>>()
    private val hordeProbes = linkedMapOf<HordeProbeKey, Pair<Long, HordeWorkerLimits?>>()

    private suspend fun inspect(connection: ModelConnection): BackendModelFormatting? = withTimeoutOrNull(4_500) {
        ProviderProtocols.modelProperties(connection, withContext(Dispatchers.IO) { templateCatalog })
    }

    fun loadFormattingMetadata(provider: Provider, model: String, done: (BackendModelFormatting) -> Unit) = host.work {
        require(model.isNotBlank()) { "请输入模型 ID" }
        val result = inspect(ModelConnection(provider, model.trim())) ?: error("读取模型属性超时")
        done(result)
        host.notice(if (result.hash.isEmpty() && result.contextTokens == null) "服务未报告模板或上下文长度" else "已读取模型属性")
    }

    suspend fun formattedConnection(connection: ModelConnection): ModelConnection {
        val provider = connection.provider
        if (provider.protocol == ApiProtocol.HORDE_TEXT) return refreshHordeLimits(connection)
        val config = provider.modelFormatting
        if (provider.protocol != ApiProtocol.KOBOLD_TEXT || !config.usesMetadata ||
            config.backend[connection.model]?.current() == true) return connection
        val key = ProbeKey(provider.id, provider.endpoint, provider.apiKey.hashCode(), connection.model)
        val now = System.currentTimeMillis()
        val cached = synchronized(probes) { probes[key]?.takeIf { (time, _) -> now - time in 0..300_000L } }
        val metadata = if (cached != null) cached.second else {
            val checked = try {
                inspect(connection)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            synchronized(probes) {
                if (probes.size >= 32) probes.remove(probes.keys.first())
                probes[key] = now to checked
            }
            checked
        }
        if (metadata == null) return connection
        return connection.copy(provider = provider.copy(modelFormatting = config.withBackend(connection.model, metadata)))
    }

    private suspend fun refreshHordeLimits(connection: ModelConnection): ModelConnection {
        val provider = connection.provider
        if (!provider.horde.adjustContext && !provider.horde.adjustOutput) return connection
        val key = HordeProbeKey(provider.endpoint, provider.apiKey.hashCode(), provider.horde.trustedOnly)
        val now = System.currentTimeMillis()
        val cached = synchronized(hordeProbes) { hordeProbes[key]?.takeIf { (time, _) -> now - time in 0..30_000L } }
        val limits = if (cached != null) cached.second else {
            val found = withTimeoutOrNull(3_500L) { Horde(provider).workerLimits() }
            synchronized(hordeProbes) {
                if (hordeProbes.size >= 16) hordeProbes.remove(hordeProbes.keys.first())
                hordeProbes[key] = now to found
            }
            found
        } ?: return connection
        return connection.copy(provider = provider.copy(modelContexts = limits.contexts,
            horde = provider.horde.copy(modelOutputs = limits.outputs)))
    }

    fun clearModels() = host.mutable.update { it.copy(catalog = null) }

    fun savePrefs(prefs: Preferences) = host.work {
        val valid = ModelService.validated(prefs)
        val checked = valid.copy(server = valid.server.trim().also { if (it.isNotBlank()) checkedAddress(it) })
        host.persistPreferences(checked)
        host.notice("设置已保存")
    }

    fun saveTextSystem(settings: TextSystemSettings, done: () -> Unit) = host.work {
        host.persistPreferences(host.current.prefs.copy(textSystem = settings))
        host.notice("系统提示设置已保存")
        done()
    }

    fun saveCustomStops(settings: CustomStoppingStrings, done: () -> Unit) = host.work {
        host.persistPreferences(host.current.prefs.copy(customStops = settings))
        host.notice("停止序列已保存")
        done()
    }
    fun saveReplyFormatting(value: ReplyFormatting, includeReasoning: Boolean?, done: () -> Unit) = host.work {
        val current = host.current.prefs
        host.persistPreferences(current.copy(replyFormatting = value, includeReasoning = includeReasoning ?: current.includeReasoning))
        host.notice("回复格式已保存")
        done()
    }

    fun setVisualNovel(enabled: Boolean) = host.work {
        host.persistPreferences(host.current.prefs.copy(visualNovel = enabled))
    }

    fun setCardScriptsEnabled(enabled: Boolean) = host.work {
        host.persistPreferences(host.current.prefs.copy(cardScriptsEnabled = enabled))
    }

    fun setChatAppearance(appearance: ChatAppearance) = host.work {
        host.persistPreferences(host.current.prefs.copy(chatAppearance = appearance.clamped()))
    }

    fun setSwipeGestures(enabled: Boolean) = host.work {
        host.persistPreferences(host.current.prefs.copy(swipeGestures = enabled))
    }

    fun setShowGroupQueue(enabled: Boolean) = host.work {
        host.persistPreferences(host.current.prefs.copy(showGroupQueue = enabled))
    }

    fun setChatHandling(handling: ChatHandling) = host.work {
        host.persistPreferences(host.current.prefs.copy(chatHandling = handling))
    }

    suspend fun builtInChatThemes(): List<ChatTheme> = withContext(Dispatchers.IO) { shippedThemes }

    fun saveChatTheme(name: String?, existingId: String? = null) = host.work {
        val prefs = host.current.prefs
        val library = prefs.chatThemes
        val existing = existingId?.let { id -> requireNotNull(library.items.firstOrNull { it.id == id }) { "找不到主题" } }
        val title = themeName(name ?: existing?.name.orEmpty())
        val source = existing ?: library.items.firstOrNull { it.id == library.activeId }
        val theme = ChatTheme(existingId ?: UUID.randomUUID().toString(), title, prefs.chatAppearance,
            prefs.theme, prefs.dynamicColor, prefs.visualNovel, source?.originalExtras)
        val updated = if (existing == null) library.add(theme) else
            library.copy(items = library.items.map { if (it.id == existing.id) theme else it })
        host.persistPreferences(prefs.copy(chatThemes = updated.copy(activeId = theme.id)))
        host.notice("主题已保存")
    }

    fun applyChatTheme(id: String) = host.work {
        val prefs = host.current.prefs
        val theme = requireNotNull(prefs.chatThemes.items.firstOrNull { it.id == id }
            ?: builtInChatThemes().firstOrNull { it.id == id }) { "找不到主题" }
        host.persistPreferences(prefs.copy(chatAppearance = theme.appearance, theme = theme.mode,
            dynamicColor = theme.dynamicColor, visualNovel = theme.visualNovel,
            chatThemes = prefs.chatThemes.add(theme).copy(activeId = id)))
    }

    fun deleteChatTheme(id: String) = host.work {
        val prefs = host.current.prefs
        val library = prefs.chatThemes
        host.persistPreferences(prefs.copy(chatThemes = library.copy(items = library.items.filterNot { it.id == id },
            activeId = library.activeId?.takeUnless { it == id })))
    }

    fun importChatTheme(uri: Uri) = host.work {
        val theme = withContext(Dispatchers.IO) {
            val raw = requireNotNull(host.resolver.openInputStream(uri)) { "无法打开主题文件" }
                .use { it.readLimited(MAX_THEME_JSON).decodeToString().trimStart('\uFEFF') }
            val filename = host.app.documentName(uri).substringBeforeLast('.').takeUnless { it == "导入的聊天" }.orEmpty()
            ChatTheme.read(JSONObject(raw), filename)
        }
        val prefs = host.current.prefs
        host.persistPreferences(prefs.copy(chatThemes = prefs.chatThemes.add(theme)))
        host.notice("主题已导入")
    }

    fun exportChatTheme(uri: Uri, id: String) = host.work {
        val theme = requireNotNull(host.current.prefs.chatThemes.items.firstOrNull { it.id == id }) { "找不到主题" }
        withContext(Dispatchers.IO) { host.writeUri(uri, theme.portable().toString(2).encodeToByteArray()) }
        host.notice("主题已导出")
    }

    fun tokenCounterFor(connection: ModelConnection?): TextTokenCounter =
        host.chatTokenizers.counterFor(connection)

    fun setWelcomeAssistant(card: Card?) = host.work {
        val preferences = host.current.prefs.welcome
        host.persistPreferences(host.current.prefs.copy(welcome = preferences.copy(assistantId = card?.id)))
    }

    fun setAutoOpenLastChat(enabled: Boolean) = host.work {
        host.persistPreferences(host.current.prefs.copy(welcome = host.current.prefs.welcome.copy(autoOpen = enabled)))
    }

    fun setReplyAutomation(options: ReplyAutomation, done: () -> Unit) = host.work {
        host.persistPreferences(host.current.prefs.copy(replyAutomation = ReplyAutomation.from(options.json())))
        done()
    }

    fun reportDraft(chatId: String, present: Boolean) {
        if (present) host.draftInProgress = chatId
        else if (host.draftInProgress == chatId) host.draftInProgress = null
    }

    fun setRecentChats(visible: Boolean, count: Int) = host.work {
        val welcome = host.current.prefs.welcome.copy(showRecent = visible, recentCount = count.coerceIn(1, RECENT_CHAT_LIMIT))
        host.persistPreferences(host.current.prefs.copy(welcome = welcome))
    }

    fun toggleRecentPin(id: String) = host.work {
        val welcome = host.current.prefs.welcome
        val existing = withContext(Dispatchers.IO) { host.store.recentChats(welcome.pinnedIds) }
            .mapTo(hashSetOf()) { it.summary.id }
        val validPins = welcome.pinnedIds.filter { it in existing }
        val pins = if (id in validPins) validPins - id else {
            require(id in existing) { "找不到聊天记录" }
            require(validPins.size < RECENT_CHAT_LIMIT) { "最多置顶 $RECENT_CHAT_LIMIT 个聊天" }
            validPins + id
        }
        host.persistPreferences(host.current.prefs.copy(welcome = welcome.copy(pinnedIds = pins)))
        host.refreshRecentChats()
    }

    fun setDefaultAuthorNote(note: AuthorNote, done: () -> Unit) = host.work {
        host.persistPreferences(host.current.prefs.copy(defaultAuthorNote = note))
        done()
    }

    fun saveLoreSettings(settings: LoreSettings, done: () -> Unit) = host.work {
        host.persistPreferences(host.current.prefs.copy(loreSettings = settings))
        done()
    }

    fun setChatAuthorNote(note: AuthorNote, done: () -> Unit) = host.work {
        val chat = host.current.chat ?: return@work
        host.persist(chat.copy(options = chat.options.copy(authorNote = note)))
        done()
    }

    fun setPromptOverrides(scenario: String, examples: String, system: String, done: () -> Unit) = host.work {
        val chat = host.current.chat ?: return@work
        host.persist(chat.copy(options = chat.options.copy(
            scenario = scenario.trim(),
            exampleOverride = examples.trim(),
            systemPromptOverride = system.trim(),
        )))
        done()
    }

    fun linkChatBook(book: WorldBook?, done: () -> Unit) = host.work {
        val chat = host.current.chat ?: return@work
        host.persist(chat.copy(options = chat.options.copy(worldBookId = book?.id.orEmpty(), worldBookName = book?.name.orEmpty())))
        done()
    }

    fun switchPromptPreset(id: String?, done: () -> Unit) = host.work {
        val chat = host.current.chat ?: return@work
        require(id.isNullOrEmpty() || host.current.presets.any { it.id == id }) { "找不到该提示词预设" }
        host.generation.stopAuto()
        host.persist(chat.copy(options = chat.options.copy(promptPresetId = id)))
        done()
    }

    fun setDefaultPromptPreset(id: String?) = host.work {
        require(id == null || host.current.presets.any { it.id == id }) { "找不到该提示词预设" }
        host.persistPreferences(host.current.prefs.copy(defaultPresetId = id))
    }

    fun savePromptPreset(preset: PromptPreset, done: () -> Unit) = host.work {
        val name = preset.name.trim()
        require(name.isNotEmpty()) { "请输入预设名称" }
        require(host.current.presets.none { it.id != preset.id && it.name.equals(name, true) }) { "预设名称已存在" }
        val presets = withContext(Dispatchers.IO) {
            host.store.save(preset.copy(name = name))
            host.store.promptPresets()
        }
        host.mutable.update { it.copy(presets = presets) }
        done()
    }

    fun deletePromptPreset(preset: PromptPreset, done: () -> Unit) = host.work {
        val presets = withContext(Dispatchers.IO) {
            host.store.deletePreset(preset.id)
            host.store.promptPresets()
        }
        host.mutable.update { it.copy(presets = presets) }
        if (host.current.prefs.defaultPresetId == preset.id) {
            host.persistPreferences(host.current.prefs.copy(defaultPresetId = null))
        }
        done()
    }

    fun importPromptPreset(uri: Uri, displayName: String) = host.work {
        val known = host.current.presets.mapTo(mutableSetOf()) { it.name.lowercase() }
        val presets = withContext(Dispatchers.IO) {
            val raw = requireNotNull(host.resolver.openInputStream(uri)) { "无法打开预设文件" }
                .use { it.readLimited().decodeToString().trimStart('\uFEFF') }
            val imported = PromptPreset.read(JSONObject(raw), displayName.removeSuffix(".json"))
            val title = generateSequence(1) { it + 1 }
                .map { if (it == 1) imported.name else "${imported.name} ($it)" }
                .first { it.lowercase() !in known }
            host.store.save(imported.copy(name = title))
            host.store.promptPresets()
        }
        host.mutable.update { it.copy(presets = presets) }
        host.notice("提示词预设已导入")
    }

    fun exportPromptPreset(uri: Uri, preset: PromptPreset) = host.work {
        withContext(Dispatchers.IO) { host.writeUri(uri, preset.json().toString(2).encodeToByteArray()) }
        host.notice("提示词预设已导出")
    }

    fun allowRegex(target: RegexTarget, enabled: Boolean) = host.work {
        val config = host.current.prefs.regex
        fun update(ids: Set<String>) = if (enabled) ids + target.id else ids - target.id
        val regex = when (target.scope) {
            RegexScope.GLOBAL -> config.copy(enabled = enabled)
            RegexScope.CARD -> config.copy(allowedCards = update(config.allowedCards))
            RegexScope.PRESET -> config.copy(allowedPresets = update(config.allowedPresets))
        }
        host.persistPreferences(host.current.prefs.copy(regex = regex))
    }

    fun saveRegexRules(target: RegexTarget, rules: List<RegexRule>, done: () -> Unit = {}) = host.work {
        persistRegexRules(target, rules)
        done()
    }

    fun importRegexRules(uri: Uri, target: RegexTarget) = host.work {
        val rules = withContext(Dispatchers.IO) {
            requireNotNull(host.resolver.openInputStream(uri)) { "无法打开规则文件" }
                .use { RegexRule.import(it.readLimited().decodeToString()) }
        }
        val merged = host.current.regexRules(target).associateBy(RegexRule::id).toMutableMap()
        rules.forEach { merged[it.id] = it }
        persistRegexRules(target, merged.values.toList())
        host.notice("已导入 ${rules.size} 条正则规则")
    }

    fun exportRegexRules(uri: Uri, rules: List<RegexRule>) = host.work {
        withContext(Dispatchers.IO) {
            val value = if (rules.size == 1) rules.single().json().toString(2) else JSONArray(rules.map(RegexRule::json)).toString(2)
            host.writeUri(uri, value.encodeToByteArray())
        }
        host.notice("正则规则已导出")
    }

    private suspend fun persistRegexRules(target: RegexTarget, rules: List<RegexRule>) {
        when (target.scope) {
            RegexScope.GLOBAL -> host.persistPreferences(host.current.prefs.copy(regex = host.current.prefs.regex.copy(global = rules)))
            RegexScope.CARD -> {
                val card = host.current.cards.firstOrNull { it.id == target.id } ?: error("找不到该角色")
                val cards = withContext(Dispatchers.IO) {
                    host.store.save(card.withRegexRules(rules))
                    host.store.cards()
                }
                host.mutable.update { it.copy(cards = cards) }
            }
            RegexScope.PRESET -> {
                val preset = host.current.presets.firstOrNull { it.id == target.id } ?: error("找不到该提示词预设")
                val presets = withContext(Dispatchers.IO) {
                    host.store.save(preset.copy(regexRules = rules))
                    host.store.promptPresets()
                }
                host.mutable.update { it.copy(presets = presets) }
            }
        }
    }

    fun saveProvider(draft: Provider, done: () -> Unit) = host.work {
        val provider = draft.validated()
        val prefs = host.current.prefs
        val providers = prefs.providers.toMutableList()
        val index = providers.indexOfFirst { it.id == provider.id }
        if (index >= 0) providers[index] = provider else providers.add(provider)
        val currentDefault = prefs.connectionFor()
        val nextDefault =
            if (currentDefault == null || currentDefault.provider.id == provider.id) provider.defaultSelection
            else prefs.defaultSelection
        host.persistPreferences(prefs.copy(providers = providers, defaultSelection = nextDefault))
        clearModels()
        done()
        host.notice("提供商已保存")
    }

    fun setDefaultProvider(provider: Provider) = host.work {
        require(provider.ready) { "请先配置此提供商" }
        host.persistPreferences(host.current.prefs.copy(defaultSelection = provider.defaultSelection))
        host.notice("默认模型已切换为 ${provider.defaultModel}")
    }

    fun deleteProvider(provider: Provider, done: () -> Unit) = host.work {
        val prefs = host.current.prefs
        val providers = prefs.providers.filterNot { it.id == provider.id }
        val nextDefault = prefs.defaultSelection?.takeUnless { it.providerId == provider.id }
            ?: providers.firstOrNull { it.ready }?.defaultSelection
        host.persistPreferences(prefs.copy(providers = providers, defaultSelection = nextDefault))
        clearModels()
        done()
        host.notice("提供商已移除")
    }

    fun loadModels(provider: Provider, persistCatalog: Boolean = false) = host.work {
        require(!provider.requiresApiKey || provider.apiKey.isNotBlank()) { "请填写 API Key" }
        val discovered = ModelService.discoverModels(provider)
        if (persistCatalog) {
            val prefs = host.current.prefs
            host.persistPreferences(prefs.copy(providers = prefs.providers.map {
                if (it.id == provider.id) it.copy(models = discovered.ids, modelContexts = discovered.contexts,
                    novelAi = it.novelAi.copy(tier = discovered.novelAiTier ?: it.novelAi.tier),
                    horde = it.horde.copy(modelOutputs = discovered.hordeOutputs ?: it.horde.modelOutputs)) else it
            }))
        } else host.mutable.update {
            it.copy(catalog = ModelCatalog(provider.id, provider.endpoint.trim(), discovered.ids, discovered.contexts,
                discovered.novelAiTier, discovered.hordeOutputs))
        }
        if (discovered.ids.isEmpty()) host.notice("连接成功，服务没有返回模型 ID")
    }

    fun switchModel(selection: ModelSelection?) = host.work {
        val chat = host.current.chat ?: return@work
        require(selection == null || host.current.prefs.resolve(selection) != null) { "请先配置此提供商" }
        host.persist(chat.copy(modelOverride = selection))
    }

    fun saveConnectionProfile(draft: ConnectionProfile, done: () -> Unit) = host.work {
        val profile = draft.validated()
        val prefs = host.current.prefs
        val list = prefs.connectionProfiles.toMutableList()
        val idx = list.indexOfFirst { it.id == profile.id }
        if (idx >= 0) list[idx] = profile else list += profile
        host.persistPreferences(prefs.copy(connectionProfiles = list))
        done()
        host.notice("连接配置档案已保存")
    }

    fun deleteConnectionProfile(profileId: String, done: () -> Unit) = host.work {
        val prefs = host.current.prefs
        val remaining = prefs.connectionProfiles.filterNot { it.id == profileId }
        val active = prefs.activeProfileId?.takeUnless { it == profileId }
        host.persistPreferences(prefs.copy(connectionProfiles = remaining, activeProfileId = active))
        done()
        host.notice("连接配置档案已删除")
    }

    fun setActiveProfile(profileId: String?) = host.work {
        val prefs = host.current.prefs
        require(profileId == null || prefs.connectionProfiles.any { it.id == profileId }) { "找不到该配置档案" }
        host.persistPreferences(prefs.copy(activeProfileId = profileId))
    }

    fun setChatConnectionProfile(profileId: String?) = host.work {
        val chat = host.current.chat ?: return@work
        require(profileId == null || profileId.isEmpty() || host.current.prefs.connectionProfiles.any { it.id == profileId }) {
            "找不到该配置档案"
        }
        host.persist(chat.copy(modelOverride = null, options = chat.options.copy(connectionProfileId = profileId)))
    }
}
