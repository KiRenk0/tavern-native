package app.tavern.nativeclient

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

internal class TavernScriptHost(
    private val snapshot: () -> TavernState,
    private var input: String,
    private val saveChat: suspend (Chat) -> Unit,
    private val savePrefs: suspend (Preferences) -> Unit,
    private val ask: suspend (ScriptDialog) -> String?,
    private val trigger: suspend (String?, Boolean) -> Unit,
    private val quiet: suspend (String, Map<String, String>) -> String,
    private val draft: (String) -> Unit,
    private val speak: (String, String?) -> Unit = { _, _ -> },
    private val posted: (Message) -> Unit = {},
    private val translate: suspend (String, String?) -> String = { _, _ -> "" },
    private val summarize: suspend (String, Map<String, String>) -> String = { _, _ -> "" },
    private val dataBank: suspend (String, Map<String, String>, String) -> String = { _, _, _ -> "" },
    private val drawing: suspend (String, Map<String, String>, String) -> String = { _, _, _ -> "" },
    private val drawingMarkup: (String) -> ImageMarkup = { ImageMarkup(it, emptyList()) },
    private val tools: suspend (String, Map<String, String>, String) -> String = { _, _, _ -> "" },
    private val addTool: (ModelTool) -> Unit = { error("当前脚本环境不支持工具注册") },
    private val messageCommand: suspend (String, Map<String, String>, String) -> String = { _, _, _ -> "" },
    private val expression: suspend (String, Map<String, String>, String) -> String = { _, _, _ -> "" },
    private val caption: suspend (Map<String, String>, String) -> String = { _, _ -> "" },
    private val saveVariables: suspend (String?, MacroVariableChanges) -> Unit,
    private val saveBook: suspend (WorldBook) -> Unit = { error("当前脚本环境不支持修改世界书") },
    private val clearVariables: suspend (Boolean) -> Unit = {},
    private val galleryImages: suspend (Chat) -> List<GalleryImage> = { emptyList() },
    private val tokenizers: ChatTokenizers? = null,
    private val generateRaw: suspend (String, Map<String, String>) -> String = { _, _ -> error("当前脚本环境不支持原始生成") },
    private val askCharacter: suspend (String, String) -> Message? = { _, _ -> error("当前脚本环境不支持指定角色发言") },
    private val updateGroup: suspend (String, (TavernGroup) -> TavernGroup) -> Boolean =
        { _, _ -> error("当前脚本环境不支持修改群成员") },
    private val editCharacter: suspend (ScriptCharacterCommand, Map<String, String>) -> String =
        { _, _ -> error("当前脚本环境不支持编辑角色") },
    private val checkpointCommand: suspend (String, Map<String, String>, String) -> String =
        { _, _, _ -> error("当前脚本环境不支持聊天分支") },
    private val updateWorldActivation: suspend (List<WorldBook>) -> Unit = { error("当前脚本环境不支持切换全局世界书") },
    private val createBoundBook: suspend (WorldBook, app.tavern.nativeclient.script.ScriptWorldBinding) -> Unit =
        { _, _ -> error("当前脚本环境不支持创建关联世界书") },
    private val chatAction: suspend (ScriptChatAction, Chat, String) -> Unit =
        { _, _, _ -> error("当前脚本环境不支持操作聊天档案") },
    private val setTemporaryPersona: (String?) -> Unit = {},
    private val openTemporaryChat: suspend (String) -> String = { error("当前脚本环境不支持打开临时聊天") },
    private val builtInThemes: suspend () -> List<ChatTheme> = { emptyList() },
    private val builtInContexts: suspend () -> List<TextContextTemplate> = { emptyList() },
    private val builtInInstructs: suspend () -> List<InstructTemplate> = { emptyList() },
    private val updateReasoningCollapse: (app.tavern.nativeclient.script.ReasoningCollapseState) -> Unit = {},
    private val audioCommand: ((String, String, Map<String, String>) -> String)? = null,
    private val webSearchCommand: (suspend (String) -> String)? = null,
    private val notice: (String) -> Unit,
) : StScriptHost {
    private val state get() = snapshot()
    private fun chat() = requireNotNull(state.chat) { "请先打开对话" }

    override fun variable(key: String, global: Boolean): String? =
        if (global) state.prefs.scriptVariables[key] else state.chat?.options?.variables?.get(key)

    override suspend fun variable(key: String, value: String?, global: Boolean) {
        val patch = mapOf(key to value)
        commitVariables(if (global) MacroVariableChanges(global = patch) else MacroVariableChanges(local = patch))
    }

    override suspend fun commitVariables(changes: MacroVariableChanges) = saveVariables(state.chat?.id, changes)

    override fun macroSeed(): String {
        val chat = state.chat
        return "${chat?.id.orEmpty()}:${chat?.options?.pickRerollSeed.orEmpty()}"
    }

    override fun macro(name: String): String? = macro(name, emptyList())

    override fun macro(name: String, args: List<String>): String? {
        if (name.equals("input", true)) return input
        val config = state
        val chat = config.chat
        if (name.equals("lastmessageid", true)) return (chat?.messages?.lastIndex ?: -1).toString()
        val group = config.group?.let {
            GroupPrompt(it, emptyList(), it.members(config.cards).map(Card::name), it.participants(config.cards).map(Card::name))
        }
        val activeCard = config.regexCard()
        val effective = config.prefs.effectiveConfig(chat)
        val connection = effective.connection
        val preset = config.presetFor(chat)
        val contextLimit = connection?.contextTokens(presetLimit = preset.chatCompletionFor(connection)?.contextLimit)
        val configuredOutput = (effective.sampling.maxTokens.takeIf(String::isNotBlank) ?: config.prefs.maxTokens).toIntOrNull()
            ?: preset?.sampling?.get(PresetSampling.MAX_TOKENS)?.toIntOrNull()
        val responseLimit = connection?.outputReserve(configuredOutput, contextLimit ?: DEFAULT_CONTEXT_TOKENS)
            ?: configuredOutput
        val lastExpr = activeCard?.id?.let { id -> config.manualExpressions[id]?.takeIf { it.chatId == chat?.id }?.label }
        val macros = ConversationMacros(
            activeCard, config.persona, chat?.messages.orEmpty(), group,
            chat?.options ?: ConversationOptions(), config.prefs.scriptVariables, connection?.model.orEmpty(),
            chat?.id.orEmpty(), allowHidden = true,
            maxContextTokens = contextLimit,
            maxResponseTokens = responseLimit,
            lastExpression = lastExpr,
        )
        return macros.read(name, args)
    }

    override fun quickReply(name: String): String? = state.prefs.quickReplies.find(name, state.chat)?.message
    override fun registerTool(tool: ModelTool) = addTool(tool)
    override fun onAbort(message: String) = notice(message)

    override suspend fun command(name: String, named: Map<String, String>, text: String): String? = when (name) {
        "flat", "default", "bubble", "bubbles", "single", "story" -> {
            val layout = when (name) {
                "flat", "default" -> ChatLayoutMode.FLAT
                "bubble", "bubbles" -> ChatLayoutMode.BUBBLE
                else -> ChatLayoutMode.DOCUMENT
            }
            val prefs = state.prefs
            if (prefs.chatAppearance.layoutMode != layout) {
                savePrefs(prefs.copy(chatAppearance = prefs.chatAppearance.copy(layoutMode = layout)))
            }
            ""
        }
        "count" -> {
            val chat = chat()
            val connection = state.prefs.connectionFor(chat)
            val counted = tokenizers?.countChat(chat, connection, TextTokenizer.MODEL) ?: withContext(Dispatchers.Default) {
                TokenPreview(estimatedTokens.label, estimatedTokens.count(chatTokenText(chat)))
            }
            notice("${counted.count} Token · ${counted.source}")
            counted.count.toString()
        }
        "trimtokens" -> {
            val limit = (named["limit"] ?: named["tokens"])?.trim()?.toIntOrNull()
                ?: error("请用 limit= 指定 Token 上限")
            val connection = state.prefs.connectionFor(state.chat)
            withContext(Dispatchers.Default) {
                val counter = tokenizers?.counterFor(connection) ?: estimatedTokens
                trimTokenText(text, limit, named["direction"].equals("start", true), counter)
            }
        }
        "echo", "toast" -> text.also(notice)
        "send", "sendas", "sys", "comment" -> append(name, named, text).text
        "trigger" -> "".also {
            trigger(named["name"] ?: text.takeIf(String::isNotBlank), named["await"].orEmpty().scriptBoolean())
        }
        "ask" -> {
            val returnType = scriptReturnType(named, fallback = "pipe")
            val actor = named["name"]?.takeIf(String::isNotBlank)
            if (actor == null) "".also { notice("请用 name= 指定要询问的角色") }
            else askCharacter(actor, text)?.let { scriptReturn(it, returnType) }.orEmpty()
        }
        "gen" -> quiet(text, named)
        "getchatname" -> state.chat?.title.orEmpty()
        "renamechat" -> runChatAction(ScriptChatAction.RENAME, text.trim())
        "closechat" -> runChatAction(ScriptChatAction.CLOSE)
        "chat-reload" -> runChatAction(ScriptChatAction.RELOAD)
        "chat-jump", "chat-scrollto", "floor-teleport" -> runChatAction(ScriptChatAction.JUMP, text)
        "genraw" -> if (text.isBlank()) "" else generateRaw(text, named)
        "sysgen" -> {
            if (text.isBlank()) "" else {
                val returnType = scriptReturnType(named, fallback = "none")
                val generated = quiet(text, named - "name")
                val message = append("sys", named, generated, narratorPosition = true)
                scriptReturn(message, returnType)
            }
        }
        "speak", "narrate", "tts" -> "".also { speak(text, named["voice"] ?: named["name"]) }
        "translate" -> translate(text, named["target"])
        "summarize" -> summarize(text, named)
        "db", "databank", "data-bank" -> dataBank("db", named, text)
        "sd", "img", "image", "imagine" -> drawing("draw", named, text)
        "sd-source", "img-source", "imagine-source" -> drawing("source", named, text)
        "sd-style", "img-style", "imagine-style" -> drawing("style", named, text)
        "imagine-comfy-workflow", "icw" -> drawing("workflow", named, text)
        "show-gallery" -> drawing("gallery", named, text)
        "expression-set", "expression" -> expression("set", named, text)
        "expression-list" -> expression("list", named, text)
        "branch-create", "checkpoint-create", "checkpoint-go", "checkpoint-exit", "checkpoint-parent",
        "checkpoint-get", "checkpoint-list" -> checkpointCommand(name, named, text)
        "caption" -> caption(named, text)
        "tools-list", "tool-list" -> tools("list", named, text)
        "tools-invoke", "tool-invoke" -> tools("invoke", named, text)
        "tools-unregister", "tool-unregister" -> tools("unregister", named, text)
        "setinput" -> text.also { input = it; draft(it) }
        "popup", "input", "buttons" -> {
            val buttonItems = named["labels"]?.let { labels ->
                val raw = variable(labels, false) ?: variable(labels, true) ?: labels
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull { idx ->
                    when (val item = array.opt(idx)) {
                        is String -> ScriptDialogButton(item)
                        is org.json.JSONObject -> item.string("label").takeIf(String::isNotBlank)?.let {
                            ScriptDialogButton(it, item.string("icon"), item.string("tooltip"))
                        }
                        else -> null
                    }
                }
            }.orEmpty()
            ask(ScriptDialog(
                kind = name,
                title = named["title"] ?: if (name == "input") "脚本输入" else "脚本提示",
                text = text,
                buttons = buttonItems.map(ScriptDialogButton::label),
                initial = named["default"].orEmpty(),
                placeholder = named["placeholder"].orEmpty(),
                tooltip = named["tooltip"].orEmpty(),
                icon = named["icon"].orEmpty(),
                buttonItems = buttonItems,
            )).orEmpty()
        }
        "regex" -> {
            val rule = named["name"] ?: error("请用 name= 指定规则")
            val regex = state.textRules(state.chat)
            withContext(Dispatchers.Default) { regex.named(rule, text) }
        }
        "message", "messages", "getmessage" -> {
            val messages = chat().messages
            if (name == "messages" && text.isBlank() && named.isEmpty()) JSONArray(messages.map(Message::text)).toString()
            else {
                val range = scriptMessageRange(text.ifBlank { "-1" }, messages.size)
                val role = named["role"]?.lowercase()
                require(role == null || role in setOf("system", "assistant", "user")) { "role 应为 system、assistant 或 user" }
                val names = named["names"].orEmpty().scriptBoolean()
                val hidden = named["hidden"].orEmpty().scriptBoolean(default = true)
                range?.asSequence()?.map(messages::get)?.filter {
                    (role == null || it.scriptRole == role) && (hidden || !it.excludedFromContext)
                }?.joinToString("\n\n") {
                    val value = if (name == "getmessage") it.text else it.requestText
                    if (names) "${it.name}: $value" else value
                }.orEmpty()
            }
        }
        "message-role", "message-name", "hide", "unhide", "cut", "delname", "addswipe", "delswipe", "swipe" ->
            messageCommand(name, named, text)
        "del", "delete", "delmode" -> messageCommand("del", named, text)
        "model" -> {
            val conversation = chat()
            if (text.isBlank()) state.prefs.connectionFor(conversation)?.model.orEmpty() else {
                val provider = state.prefs.connectionFor(conversation)?.provider ?: error("请先配置模型提供商")
                saveChat(conversation.copy(modelOverride = ModelSelection(provider.id, text)))
                text
            }
        }
        "profile" -> {
            val conversation = chat()
            val trimmed = text.trim()
            if (trimmed.isEmpty()) {
                state.prefs.activeProfile(conversation)?.name.orEmpty()
            } else if (trimmed.equals("off", true) || trimmed.equals("default", true) || trimmed == "默认") {
                saveChat(conversation.copy(modelOverride = null, options = conversation.options.copy(connectionProfileId = "")))
                ""
            } else {
                val profile = state.prefs.connectionProfiles.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
                    ?: error("找不到连接配置档案 $trimmed")
                saveChat(conversation.copy(modelOverride = null, options = conversation.options.copy(connectionProfileId = profile.id)))
                profile.name
            }
        }
        "reroll-pick" -> {
            val conversation = chat()
            saveChat(conversation.copy(options = conversation.options.copy(pickRerollSeed = newId())))
            ""
        }
        "qrset" -> {
            val config = state.prefs.quickReplies
            val set = config.sets.firstOrNull { it.name == text } ?: error("找不到快捷回复集 $text")
            savePrefs(state.prefs.copy(quickReplies = config.copy(enabled = true, active = listOf(set.id))))
            set.name
        }
        "help", "?" -> (if (text.equals("macros", true)) MACRO_HELP else HELP).also(notice)
        else -> {
            val databaseCommand = name.removePrefix("data-bank-").removePrefix("databank-").removePrefix("db-")
            val groupCommand = ScriptGroupCommand.from(name)
            val characterCommand = ScriptCharacterCommand.from(name)
            if (characterCommand != null) {
                scriptCharacter(characterCommand, named, text)
            } else if (groupCommand != null) {
                groupCommand.execute(state, named["field"] ?: "name", text, updateGroup, peek = { card ->
                    ask(ScriptDialog(kind = "member-card", title = card.name, text = "", card = card))
                }, notice = notice)
            } else if (databaseCommand != name && databaseCommand in setOf("list", "get", "add", "update", "delete", "enable", "disable")) {
                dataBank("db-$databaseCommand", named, text)
            } else {
                app.tavern.nativeclient.script.ScriptExtensions.execute(
                    name = name,
                    named = named,
                    text = text,
                    state = state,
                    saveChat = saveChat,
                    savePrefs = savePrefs,
                    saveBook = saveBook,
                    clearVariables = clearVariables,
                    galleryImages = galleryImages,
                    updateWorldActivation = updateWorldActivation,
                    createBoundBook = createBoundBook,
                    notice = notice,
                    ask = ask,
                    setTemporaryPersona = setTemporaryPersona,
                    openTemporaryChat = openTemporaryChat,
                    builtInThemes = builtInThemes,
                    builtInContexts = builtInContexts,
                    builtInInstructs = builtInInstructs,
                    updateReasoningCollapse = updateReasoningCollapse,
                    audioCommand = audioCommand,
                    webSearchCommand = webSearchCommand,
                )
            }
        }
    }

    private suspend fun scriptCharacter(command: ScriptCharacterCommand, named: Map<String, String>, text: String): String {
        val snapshot = state
        return when (command) {
            ScriptCharacterCommand.FIND -> {
                val (card, ambiguous) = findScriptCharacter(snapshot, text, scriptFindTags(named["tag"].orEmpty()),
                    named["preferCurrent"].orEmpty().scriptBoolean(default = true))
                if (ambiguous && !named["quiet"].orEmpty().scriptBoolean()) notice("匹配到多个角色，已采用首个结果")
                card?.scriptAvatarKey.orEmpty()
            }
            ScriptCharacterCommand.GET -> {
                val card = snapshot.scriptCardTarget(named) ?: return "".also { notice("找不到指定角色") }
                val field = named["field"] ?: text.takeIf(String::isNotBlank)?.trim()
                val activeChat = snapshot.chat?.takeIf { it.cardId == card.id }?.title
                val favorite = snapshot.prefs.library.favorites[libraryCardId(card.id)] ?: card.libraryFavorite
                val value = card.scriptCardValue(field, favorite, activeChat) ?: return ""
                val pretty = if (value is org.json.JSONObject) value.toString(2) else value.toString()
                when (val type = named["return"]?.lowercase() ?: "pipe") {
                    "pipe" -> pretty
                    "object" -> JSONArray().put(value).toString().removeSurrounding("[", "]")
                    "popup-html", "popup-text" -> "".also { ask(ScriptDialog(kind = "popup", title = card.name, text = pretty)) }
                    "toast-text", "toast-html" -> "".also { notice(pretty) }
                    "console" -> "".also { android.util.Log.i("TavernScript", pretty) }
                    "none" -> ""
                    else -> error("无法识别脚本返回方式 $type")
                }
            }
            else -> editCharacter(command, named)
        }
    }

    private fun scriptReturnType(named: Map<String, String>, fallback: String): String =
        (named["return"]?.lowercase() ?: fallback).also {
            require(it in setOf("none", "pipe", "object", "toast-text", "toast-html", "console")) { "无法识别脚本返回方式 $it" }
        }

    private fun scriptReturn(message: Message, type: String): String = when (type) {
        "pipe" -> message.text
        "object" -> message.toJson().toString()
        "toast-text", "toast-html" -> "".also { notice(message.text) }
        "console" -> "".also { android.util.Log.i("TavernScript", message.toJson().toString()) }
        else -> ""
    }

    private suspend fun append(
        command: String, named: Map<String, String>, text: String, narratorPosition: Boolean = false,
    ): Message {
        val conversation = chat()
        val persona = state.persona
        val actorName = named["name"] ?: state.regexCard()?.name
        val actor = state.cards.firstOrNull { it.name == actorName }
        val rules = state.textRules(conversation, actor ?: state.regexCard())
        val markup = withContext(Dispatchers.IO) { drawingMarkup(text) }
        val filtered = withContext(Dispatchers.Default) { rules.transform(markup.text, RegexSite.COMMAND, RegexStage.STORAGE) }
        val message = (when (command) {
            "send" -> Message("user", named["name"] ?: persona.name, filtered, personaId = persona.id)
            "sendas" -> Message("assistant", requireNotNull(actorName) { "请指定发言者" }, filtered, characterId = actor?.id)
            "sys" -> Message("assistant", named["name"] ?: "旁白", filtered,
                raw = scriptNarratorMetadata(named["compact"].orEmpty().scriptBoolean()))
            else -> Message("system", named["name"] ?: "注释", filtered)
        }).copy(images = markup.images)
        val messages = conversation.messages.toMutableList()
        val offset = named["at"]?.toIntOrNull() ?: messages.size
        val index = if (narratorPosition) scriptNarratorIndex(named["at"], messages.size)
            else if (offset < 0) messages.size + offset + 1 else offset
        require(index in 0..messages.size) { "消息位置超出范围" }
        messages.add(index, message)
        saveChat(conversation.copy(messages = messages, updated = System.currentTimeMillis()))
        posted(message)
        return message
    }

    private suspend fun runChatAction(action: ScriptChatAction, target: String = ""): String {
        val chat = state.chat
        if (chat != null) chatAction(action, chat, target)
        else if (action != ScriptChatAction.CLOSE) notice("请先打开对话")
        return ""
    }

    companion object {
        const val HELP = "/pass · /let · /var · /getvar · /setvar · /addvar · /listvar · /flushvar · /flushglobalvar · /if · /while · /times · /break · /abort · /breakpoint · /fuzzy · /run · " +
            "/upper · /lower · /trim · /len · /split · /join · /add · /sub · /mul · /div · /mod · /pow · /min · /max · /round · /floor · /ceil · /abs · /rand · " +
            "/replace · /test · /match · /array-wrap · /array-unwrap · " +
            "/inject · /listinjects · /flushinject · /reasoning-collapse · " +
            "/persona · /persona-lock · /tempchat · /tag-add · /tag-remove · /tag-exists · /tag-list · " +
            "/qr-create · /qr-update · /qr-delete · /qr-get · /qr-list · /qrset-create · /qrset-delete · /qrset-list · /qr-arg · " +
            "/api · /context · /instruct · /tokenizer · /vn · /theme · " +
            "/getchatname · /renamechat · /closechat · /chat-reload · /chat-jump · " +
            "/echo · /popup · /input · /buttons · /send · /sendas · /sys · /sysgen · /comment · /ask · /trigger · /gen · /genraw · /regex · /re-replace · /count · /trimtokens · /setinput · /speak · /translate · /summarize · /db · /sd · /caption · /show-gallery · /bg · /lockbg · /unlockbg · /preset · /char-get · " +
            "/world · /getglobalbooks · /getchatbook · /getcharbook · /getpersonabook · /findentry · /getentryfield · /setentryfield · /createentry · " +
            "/wi-get-book · /wi-list-entries · /wi-get-entry · /wi-set-entry · /wi-create-entry · /wi-delete-entry · " +
            "/tools-register · /tools-list · /tools-invoke · /tools-unregister · /messages · /message-role · /message-name · " +
            "/hide · /unhide · /cut · /del · /delname · /addswipe · /delswipe · /swipe · " +
            "/member-get · /member-count · /member-add · /member-remove · /member-enable · /member-disable · /member-up · /member-down · /member-peek · " +
            "/char-find · /char-create · /char-update · /char-duplicate · /char-delete · /model · /profile · /reroll-pick · /? macros"
        const val MACRO_HELP = "{{char}} · {{user}} · {{persona}} · {{group}} · {{groupNotMuted}} · {{description}} · " +
            "{{scenario}} · {{charFirstMessage::1}} · {{model}} · {{maxContextTokens}} · {{maxResponseTokens}} · " +
            "{{defaultExpression}} · {{lastExpression}} · {{availableExpressions}} · {{lastUserMessage}} · {{summary}} · {{getvar::name}} · " +
            "{{setvar::name::值}} · {{incvar::计数}} · {{.name ??= 值}} · {{getglobalvar::name}} · " +
            "{{get_message_variable::路径}} · {{stat_data}} · {{random::甲::乙}} · {{pick::甲::乙}} · {{roll::2d6+1}} · {{isodate}} · " +
            "{{newline}} · {{trim}} · {{outlet::名称}}。支持嵌套参数与 {{if .变量}}、{{else}}、{{/if}}。"
    }
}
