package app.tavern.nativeclient.script

import app.tavern.nativeclient.ApiProtocol
import app.tavern.nativeclient.Chat
import app.tavern.nativeclient.ChatTheme
import app.tavern.nativeclient.InstructTemplate
import app.tavern.nativeclient.ModelSelection
import app.tavern.nativeclient.Preferences
import app.tavern.nativeclient.TavernState
import app.tavern.nativeclient.TextContextTemplate
import app.tavern.nativeclient.TextTokenizer
import app.tavern.nativeclient.resolvedTextTemplates

internal data class ReasoningCollapseState(
    val chatId: String? = null,
    val defaultExpanded: Boolean? = null,
    val perMessage: Map<Int, Boolean> = emptyMap(),
    val revision: Long = 0L,
) {
    fun expandedFor(chatId: String, messageIndex: Int, autoExpandDefault: Boolean): Boolean? {
        if (this.chatId != chatId || revision == 0L) return null
        return perMessage[messageIndex] ?: defaultExpanded ?: autoExpandDefault
    }
}

internal object ScriptConfigCommands {
    val commandNames = setOf(
        "api",
        "context",
        "instruct",
        "tokenizer",
        "vn", "visualnovel", "waifu",
        "theme",
        "reasoning-collapse", "reasoning-expand",
    )

    suspend fun execute(
        command: String,
        named: Map<String, String>,
        text: String,
        state: TavernState,
        saveChat: suspend (Chat) -> Unit,
        savePrefs: suspend (Preferences) -> Unit,
        builtInThemes: suspend () -> List<ChatTheme> = { emptyList() },
        builtInContexts: suspend () -> List<TextContextTemplate> = { emptyList() },
        builtInInstructs: suspend () -> List<InstructTemplate> = { emptyList() },
        updateReasoningCollapse: (ReasoningCollapseState) -> Unit = {},
    ): String? = when (command) {
        "api" -> apiCommand(named, text, state, saveChat, savePrefs)
        "context" -> contextCommand(text, state, savePrefs, builtInContexts)
        "instruct" -> instructCommand(text, state, savePrefs, builtInInstructs)
        "tokenizer" -> tokenizerCommand(text, state, savePrefs)
        "vn", "visualnovel", "waifu" -> visualNovelCommand(named, text, state, savePrefs)
        "theme" -> themeCommand(text, state, savePrefs, builtInThemes)
        "reasoning-collapse", "reasoning-expand" -> reasoningCollapseCommand(command, named, text, state, updateReasoningCollapse)
        else -> null
    }

    private suspend fun apiCommand(
        named: Map<String, String>,
        text: String,
        state: TavernState,
        saveChat: suspend (Chat) -> Unit,
        savePrefs: suspend (Preferences) -> Unit,
    ): String {
        val current = state.prefs.connectionFor(state.chat)
        val query = text.trim()
        if (query.isEmpty()) {
            return if (named["field"].equals("name", true)) current?.provider?.name.orEmpty()
            else current?.provider?.protocol?.wire.orEmpty()
        }
        val protocol = ApiProtocol.from(query.lowercase()) ?: when (query.lowercase()) {
            "openai-compatible", "oai", "chatgpt" -> ApiProtocol.OPENAI_COMPATIBLE
            "claude" -> ApiProtocol.ANTHROPIC
            "google", "makersuite" -> ApiProtocol.GEMINI_NATIVE
            "koboldcpp", "textgenerationwebui", "oobabooga", "llamacpp" -> ApiProtocol.KOBOLD_TEXT
            "nai" -> ApiProtocol.NOVELAI_TEXT
            "aihorde" -> ApiProtocol.HORDE_TEXT
            else -> null
        }
        val provider = state.prefs.providers.firstOrNull { candidate ->
            candidate.ready && (
                candidate.id.equals(query, true) ||
                    candidate.name.equals(query, true) ||
                    (protocol != null && candidate.protocol == protocol)
                )
        } ?: error("找不到已配置的 API 提供商 $query")
        val selection = ModelSelection(provider.id, provider.defaultModel)
        val chat = state.chat
        if (chat != null && !named["global"].equals("true", true)) {
            saveChat(chat.copy(modelOverride = selection))
        } else {
            savePrefs(state.prefs.copy(defaultSelection = selection))
        }
        return provider.protocol.wire
    }

    private suspend fun contextCommand(
        text: String,
        state: TavernState,
        savePrefs: suspend (Preferences) -> Unit,
        builtInContexts: suspend () -> List<TextContextTemplate>,
    ): String {
        val connection = state.prefs.connectionFor(state.chat) ?: error("请先配置模型提供商")
        val provider = connection.provider
        val query = text.trim()
        if (query.isEmpty()) {
            return (provider.textContext ?: connection.resolvedTextTemplates().context)?.name.orEmpty()
        }
        val updatedProvider = if (query.equals("none", true) || query.equals("off", true) || query.equals("default", true)) {
            provider.copy(textContext = null)
        } else {
            val pool = buildList {
                provider.textContext?.let(::add)
                provider.modelFormatting.rules.mapNotNull { it.templates.context }.let(::addAll)
                addAll(builtInContexts())
            }
            val matched = pool.firstOrNull { it.name.equals(query, ignoreCase = true) }
                ?: pool.firstOrNull { it.name.startsWith(query, ignoreCase = true) }
                ?: error("找不到上下文模板 $query")
            provider.copy(textContext = matched)
        }
        savePrefs(state.prefs.copy(providers = state.prefs.providers.map { if (it.id == provider.id) updatedProvider else it }))
        return updatedProvider.textContext?.name.orEmpty()
    }

    private suspend fun instructCommand(
        text: String,
        state: TavernState,
        savePrefs: suspend (Preferences) -> Unit,
        builtInInstructs: suspend () -> List<InstructTemplate>,
    ): String {
        val connection = state.prefs.connectionFor(state.chat) ?: error("请先配置模型提供商")
        val provider = connection.provider
        val query = text.trim()
        if (query.isEmpty()) {
            return (provider.instruct ?: connection.resolvedTextTemplates().instruct)?.name.orEmpty()
        }
        val updatedProvider = if (query.equals("none", true) || query.equals("off", true) || query.equals("default", true)) {
            provider.copy(instruct = null)
        } else {
            val pool = buildList {
                provider.instruct?.let(::add)
                provider.modelFormatting.rules.mapNotNull { it.templates.instruct }.let(::addAll)
                addAll(builtInInstructs())
            }
            val matched = pool.firstOrNull { it.name.equals(query, ignoreCase = true) }
                ?: pool.firstOrNull { it.name.startsWith(query, ignoreCase = true) }
                ?: error("找不到指令模板 $query")
            provider.copy(instruct = matched)
        }
        savePrefs(state.prefs.copy(providers = state.prefs.providers.map { if (it.id == provider.id) updatedProvider else it }))
        return updatedProvider.instruct?.name.orEmpty()
    }

    private suspend fun tokenizerCommand(
        text: String,
        state: TavernState,
        savePrefs: suspend (Preferences) -> Unit,
    ): String {
        val connection = state.prefs.connectionFor(state.chat) ?: error("请先配置模型提供商")
        val provider = connection.provider
        val query = text.trim()
        if (query.isEmpty()) return provider.textTokenizer.name.lowercase()
        val matched = matchTokenizer(query) ?: error("找不到分词器 $query")
        val updatedProvider = provider.copy(textTokenizer = matched)
        savePrefs(state.prefs.copy(providers = state.prefs.providers.map { if (it.id == provider.id) updatedProvider else it }))
        return matched.name.lowercase()
    }

    internal fun matchTokenizer(raw: String): TextTokenizer? {
        val needle = raw.trim().lowercase()
        return when (needle) {
            "auto", "default", "model" -> TextTokenizer.MODEL
            "deepseek", "deepseek_41", "deepseek-41", "deepseek41" -> TextTokenizer.DEEPSEEK_41
            "o200k", "o200k_base", "gpt4o" -> TextTokenizer.O200K
            "cl100k", "cl100k_base", "gpt4", "openai" -> TextTokenizer.CL100K
            "p50k", "p50k_base" -> TextTokenizer.P50K
            "p50k_edit" -> TextTokenizer.P50K_EDIT
            "r50k", "r50k_base", "gpt2", "gpt-2" -> TextTokenizer.R50K
            "estimate", "none", "approx" -> TextTokenizer.ESTIMATE
            else -> TextTokenizer.entries.firstOrNull {
                it.name.equals(needle, true) || it.label.equals(raw.trim(), true)
            }
        }
    }

    private suspend fun visualNovelCommand(
        named: Map<String, String>,
        text: String,
        state: TavernState,
        savePrefs: suspend (Preferences) -> Unit,
    ): String {
        val current = state.prefs.visualNovel
        val arg = (named["state"] ?: text).trim().lowercase()
        if (arg == "get" || arg == "status") return current.toString()
        val next = when (arg) {
            "", "toggle" -> !current
            "on", "true", "1", "yes", "enable" -> true
            "off", "false", "0", "no", "disable" -> false
            else -> error("未知的 /vn 状态参数 $arg")
        }
        if (next != current) savePrefs(state.prefs.copy(visualNovel = next))
        return next.toString()
    }

    private suspend fun themeCommand(
        text: String,
        state: TavernState,
        savePrefs: suspend (Preferences) -> Unit,
        builtInThemes: suspend () -> List<ChatTheme>,
    ): String {
        val prefs = state.prefs
        val query = text.trim()
        if (query.isEmpty()) {
            val active = prefs.chatThemes.items.firstOrNull { it.id == prefs.chatThemes.activeId }
            return active?.name ?: prefs.theme
        }
        when (query.lowercase()) {
            "light", "dark", "system" -> {
                savePrefs(prefs.copy(theme = query.lowercase()))
                return query.lowercase()
            }
            "high-contrast", "highcontrast" -> {
                val next = !prefs.chatAppearance.highContrast
                savePrefs(prefs.copy(chatAppearance = prefs.chatAppearance.copy(highContrast = next)))
                return if (next) "high-contrast" else prefs.theme
            }
        }
        val custom = prefs.chatThemes.items.firstOrNull { it.id == query || it.name.equals(query, true) }
        val theme = custom ?: builtInThemes().firstOrNull { it.id == query || it.name.equals(query, true) }
            ?: error("找不到主题 $query")
        savePrefs(prefs.copy(
            chatAppearance = theme.appearance,
            theme = theme.mode,
            dynamicColor = theme.dynamicColor,
            visualNovel = theme.visualNovel,
            chatThemes = prefs.chatThemes.add(theme).copy(activeId = theme.id),
        ))
        return theme.name
    }

    private fun reasoningCollapseCommand(
        command: String,
        named: Map<String, String>,
        text: String,
        state: TavernState,
        updateReasoningCollapse: (ReasoningCollapseState) -> Unit,
    ): String {
        val chat = requireNotNull(state.chat) { "请先打开对话" }
        val current = state.reasoningCollapse
        val defaultExpandedNow = current.takeIf { it.chatId == chat.id }?.defaultExpanded
            ?: state.prefs.replyFormatting.autoExpand
        val rawMesId = (named["mesId"] ?: named["mesid"] ?: named["at"] ?: named["id"])?.trim().orEmpty()
        val rawState = (named["state"] ?: text).trim().lowercase()
        val collapseDefault = command != "reasoning-expand"

        fun targetCollapsed(currentlyCollapsed: Boolean): Boolean = when (rawState) {
            "" -> collapseDefault
            "toggle" -> !currentlyCollapsed
            "true", "on", "1", "yes", "collapse", "collapsed" -> true
            "false", "off", "0", "no", "expand", "expanded" -> false
            else -> error("未知的折叠状态 $rawState，应为 true/false/toggle")
        }

        val revision = current.revision + 1L
        if (rawMesId.isNotEmpty() && !rawMesId.equals("all", true)) {
            val parsed = rawMesId.toIntOrNull() ?: error("无效消息编号 $rawMesId")
            val index = if (parsed < 0) chat.messages.size + parsed else parsed
            require(index in chat.messages.indices) { "消息编号超出范围 $rawMesId" }
            val currentExpanded = current.expandedFor(chat.id, index, state.prefs.replyFormatting.autoExpand)
                ?: state.prefs.replyFormatting.autoExpand
            val nextCollapsed = targetCollapsed(!currentExpanded)
            val baseMap = if (current.chatId == chat.id) current.perMessage else emptyMap()
            updateReasoningCollapse(
                ReasoningCollapseState(
                    chatId = chat.id,
                    defaultExpanded = if (current.chatId == chat.id) current.defaultExpanded else null,
                    perMessage = baseMap + (index to !nextCollapsed),
                    revision = revision,
                ),
            )
            return nextCollapsed.toString()
        }

        val nextCollapsed = targetCollapsed(!defaultExpandedNow)
        updateReasoningCollapse(
            ReasoningCollapseState(
                chatId = chat.id,
                defaultExpanded = !nextCollapsed,
                perMessage = emptyMap(),
                revision = revision,
            ),
        )
        return nextCollapsed.toString()
    }
}
