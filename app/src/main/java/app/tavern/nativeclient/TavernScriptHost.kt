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
    private val generate: suspend (String?) -> String,
    private val quiet: suspend (String, Map<String, String>) -> String,
    private val draft: (String) -> Unit,
    private val speak: (String, String?) -> Unit = { _, _ -> },
    private val posted: (Message) -> Unit = {},
    private val translate: suspend (String, String?) -> String = { _, _ -> "" },
    private val summarize: suspend (String, String?) -> String = { _, _ -> "" },
    private val dataBank: suspend (String, Map<String, String>, String) -> String = { _, _, _ -> "" },
    private val drawing: suspend (String, Map<String, String>, String) -> String = { _, _, _ -> "" },
    private val drawingMarkup: (String) -> ImageMarkup = { ImageMarkup(it, emptyList()) },
    private val tools: suspend (String, Map<String, String>, String) -> String = { _, _, _ -> "" },
    private val addTool: (ModelTool) -> Unit = { error("当前脚本环境不支持工具注册") },
    private val messageCommand: suspend (String, Map<String, String>, String) -> String = { _, _, _ -> "" },
    private val expression: suspend (String, Map<String, String>, String) -> String = { _, _, _ -> "" },
    private val caption: suspend (Map<String, String>, String) -> String = { _, _ -> "" },
    private val saveVariables: suspend (String?, MacroVariableChanges) -> Unit,
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
        val macros = ConversationMacros(config.regexCard(), config.persona, chat?.messages.orEmpty(), group,
            chat?.options ?: ConversationOptions(), config.prefs.scriptVariables, config.prefs.connectionFor(chat)?.model.orEmpty(),
            chat?.id.orEmpty(), allowHidden = true)
        return macros.read(name, args)
    }

    override fun quickReply(name: String): String? = state.prefs.quickReplies.find(name, state.chat)?.message
    override fun registerTool(tool: ModelTool) = addTool(tool)

    override suspend fun command(name: String, named: Map<String, String>, text: String): String? = when (name) {
        "echo", "toast" -> text.also(notice)
        "send", "sendas", "sys", "comment" -> append(name, named, text)
        "trigger" -> generate(named["name"] ?: text.takeIf(String::isNotBlank))
        "gen" -> quiet(text, named)
        "speak", "narrate", "tts" -> "".also { speak(text, named["voice"] ?: named["name"]) }
        "translate" -> translate(text, named["target"])
        "summarize" -> summarize(text, named["prompt"])
        "db", "databank", "data-bank" -> dataBank("db", named, text)
        "sd", "img", "image", "imagine" -> drawing("draw", named, text)
        "sd-source", "img-source", "imagine-source" -> drawing("source", named, text)
        "sd-style", "img-style", "imagine-style" -> drawing("style", named, text)
        "imagine-comfy-workflow", "icw" -> drawing("workflow", named, text)
        "show-gallery" -> drawing("gallery", named, text)
        "expression-set", "expression" -> expression("set", named, text)
        "expression-list" -> expression("list", named, text)
        "caption" -> caption(named, text)
        "tools-list", "tool-list" -> tools("list", named, text)
        "tools-invoke", "tool-invoke" -> tools("invoke", named, text)
        "tools-unregister", "tool-unregister" -> tools("unregister", named, text)
        "setinput" -> text.also { input = it; draft(it) }
        "popup", "input", "buttons" -> {
            val choices = named["labels"]?.let { labels ->
                val raw = variable(labels, false) ?: variable(labels, true) ?: labels
                JSONArray(raw).strings()
            }.orEmpty()
            ask(ScriptDialog(kind = name, title = named["title"] ?: if (name == "input") "脚本输入" else "脚本提示",
                text = text, buttons = choices, initial = named["default"].orEmpty())).orEmpty()
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
            if (databaseCommand != name && databaseCommand in setOf("list", "get", "add", "update", "delete", "enable", "disable")) {
                dataBank("db-$databaseCommand", named, text)
            } else null
        }
    }

    private suspend fun append(command: String, named: Map<String, String>, text: String): String {
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
            "sys" -> Message("assistant", named["name"] ?: "旁白", filtered, raw = """{"extra":{"type":"narrator"}}""")
            else -> Message("system", named["name"] ?: "注释", filtered)
        }).copy(images = markup.images)
        val messages = conversation.messages.toMutableList()
        val offset = named["at"]?.toIntOrNull() ?: messages.size
        val index = if (offset < 0) messages.size + offset + 1 else offset
        require(index in 0..messages.size) { "消息位置超出范围" }
        messages.add(index, message)
        saveChat(conversation.copy(messages = messages, updated = System.currentTimeMillis()))
        posted(message)
        return filtered
    }

    companion object {
        const val HELP = "/pass · /let · /var · /getvar · /setvar · /addvar · /if · /while · /times · /break · /run · " +
            "/echo · /input · /buttons · /send · /sendas · /sys · /comment · /trigger · /gen · /regex · /setinput · /speak · /translate · /summarize · /db · /sd · /caption · /show-gallery · " +
            "/tools-register · /tools-list · /tools-invoke · /tools-unregister · /messages · /message-role · /message-name · " +
            "/hide · /unhide · /cut · /del · /delname · /addswipe · /delswipe · /swipe · /reroll-pick · /? macros"
        const val MACRO_HELP = "{{char}} · {{user}} · {{persona}} · {{group}} · {{groupNotMuted}} · {{description}} · " +
            "{{scenario}} · {{charFirstMessage::1}} · {{model}} · {{lastUserMessage}} · {{summary}} · {{getvar::name}} · " +
            "{{setvar::name::值}} · {{incvar::计数}} · {{.name ??= 值}} · {{getglobalvar::name}} · " +
            "{{random::甲::乙}} · {{pick::甲::乙}} · {{roll::2d6+1}} · {{isodate}} · " +
            "{{newline}} · {{trim}} · {{outlet::名称}}。支持嵌套参数与 {{if .变量}}、{{else}}、{{/if}}。"
    }
}
