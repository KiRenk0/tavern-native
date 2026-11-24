package app.tavern.nativeclient.script

import android.net.Uri
import android.util.Log
import app.tavern.nativeclient.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject

internal class ScriptCoordinator(private val host: CoordinatorHost) {
    private var scriptJob: Job? = null
    private var scriptAnswer: CompletableDeferred<String?>? = null
    private var activeDebugger: QuickReplyDebuggerSession? = null
    private val pendingTriggers = mutableSetOf<Job>()
    private val customTools = linkedMapOf<String, ModelTool>()
    internal var nativeDrawingToolRemoved = false
        private set
    private var preventNestedQuickReplies = false
    private var quickReplyDepth = 0

    fun cancelScript() {
        activeDebugger?.cancel()
        activeDebugger = null
        scriptJob?.cancel()
        pendingTriggers.toList().forEach { it.cancel() }
        pendingTriggers.clear()
    }

    fun stepDebugAction(action: DebugAction) {
        activeDebugger?.resume(action)
    }

    internal suspend fun resolvePromptInjections(options: ConversationOptions): ConversationOptions =
        ScriptPromptInjections.resolveForPrompt(options) { scriptHost("") }

    internal fun resetNativeDrawingTool() {
        nativeDrawingToolRemoved = false
        customTools.remove(DRAW_IMAGE_TOOL)
    }

    internal fun activeTools(prefs: Preferences, chat: Chat?, card: Card?): List<ModelTool> {
        val drawing = chat?.let { host.media.drawingTool(prefs, it, card) }?.takeUnless { it.name in customTools }
        return listOfNotNull(drawing) + customTools.values
    }

    fun saveQuickReplySet(set: QuickReplySet, done: () -> Unit = {}) = host.work {
        val config = host.current.prefs.quickReplies
        val clean = set.copy(name = set.name.trim())
        require(clean.name.isNotBlank()) { "请输入快捷回复集名称" }
        require(config.sets.none { it.id != clean.id && it.name.equals(clean.name, true) }) { "快捷回复集名称已存在" }
        val rows = config.sets.toMutableList()
        val index = rows.indexOfFirst { it.id == clean.id }
        if (index < 0) rows.add(clean) else rows[index] = clean
        host.persistPreferences(host.current.prefs.copy(quickReplies = config.copy(sets = rows)))
        done()
    }

    fun removeQuickReplySet(set: QuickReplySet, done: () -> Unit) = host.work {
        val config = host.current.prefs.quickReplies
        host.persistPreferences(host.current.prefs.copy(quickReplies = config.copy(
            active = config.active - set.id,
            sets = config.sets.filterNot { it.id == set.id },
        )))
        done()
    }

    fun importQuickReplySet(uri: Uri) = host.work {
        val source = withContext(Dispatchers.IO) {
            val raw = requireNotNull(host.resolver.openInputStream(uri)) { "无法打开快捷回复文件" }
                .use { it.readLimited().decodeToString().trimStart('\uFEFF') }
            QuickReplySet.read(JSONObject(raw))
        }
        val config = host.current.prefs.quickReplies
        val name = generateSequence(1) { it + 1 }.map { if (it == 1) source.name else "${source.name} ($it)" }
            .first { candidate -> config.sets.none { it.name.equals(candidate, true) } }
        host.persistPreferences(host.current.prefs.copy(quickReplies = config.copy(sets = config.sets + source.copy(name = name))))
        host.notice("快捷回复集已导入")
    }

    fun exportQuickReplySet(uri: Uri, set: QuickReplySet) = host.work {
        withContext(Dispatchers.IO) { host.writeUri(uri, set.json().toString(2).encodeToByteArray()) }
        host.notice("快捷回复集已导出")
    }

    fun enableQuickReplies(enabled: Boolean) = host.work {
        host.persistPreferences(host.current.prefs.copy(quickReplies = host.current.prefs.quickReplies.copy(enabled = enabled)))
    }

    fun activateQuickReplySet(id: String, enabled: Boolean, forChat: Boolean = false) = host.work {
        val config = host.current.prefs.quickReplies
        val set = requireNotNull(config.sets.firstOrNull { it.id == id }) { "找不到快捷回复集" }
        fun change(values: List<String>) = if (enabled) (values + id).distinct() else values - id
        if (forChat) {
            val chat = host.current.chat ?: return@work
            host.persist(chat.copy(options = chat.options.copy(
                quickReplySets = change(chat.options.quickReplySets),
                quickReplyNames = chat.options.quickReplyNames - set.name,
            )))
        } else host.persistPreferences(host.current.prefs.copy(quickReplies = config.copy(active = change(config.active))))
    }

    private fun scriptHost(input: String): TavernScriptHost =
        TavernScriptHost(
            { host.current },
            input,
            { host.persist(it) },
            host::persistPreferences,
            ::requestScriptInput,
            ::requestTrigger,
            { text, named -> scriptGenerate(text, named) },
            ::scriptDraft,
            host.media::scriptNarrate,
            { host.media.narrate(it, automatic = true) },
            host.chats::scriptTranslate,
            host.chats::scriptSummarize,
            host.world::scriptDataBank,
            ::scriptDrawing,
            { text -> parseDrawingMarkup(text) { host.imageStore.file(it).isFile } },
            ::scriptTools,
            { customTools[it.name] = it },
            ::scriptMessage,
            host.media::scriptExpression,
            ::scriptCaption,
            host::persistMacroVariables,
            { book ->
                withContext(Dispatchers.IO) { host.store.save(book) }
                val books = withContext(Dispatchers.IO) { host.store.books() }
                host.mutable.update { it.copy(books = books) }
            },
            { global ->
                val state = host.current
                val changes = if (global) {
                    MacroVariableChanges(global = state.prefs.scriptVariables.keys.associateWith { null })
                } else {
                    MacroVariableChanges(local = state.chat?.options?.variables.orEmpty().keys.associateWith { null })
                }
                if (!changes.isEmpty) host.persistMacroVariables(state.chat?.id, changes)
            },
            { chat -> withContext(Dispatchers.IO) { host.store.gallery(chat.cardId, chat.groupId) } },
            host.chatTokenizers,
            { text, named -> scriptGenerate(text, named, raw = true) },
            ::scriptAsk,
            host.library::updateGroupRoster,
            ::scriptEditCharacter,
            checkpointCommand = ::scriptCheckpoint,
            updateWorldActivation = ::updateWorldActivation,
            createBoundBook = ::createBoundBook,
            chatAction = host.chats::scriptChatAction,
            setTemporaryPersona = { id -> host.mutable.update { it.copy(temporaryPersonaId = id) } },
            openTemporaryChat = host.chats::openScriptTemporaryChat,
            audioCommand = { cmd, arg, named ->
                host.audioEngine.handleSlashCommand(cmd, arg, named, host.current.prefs.extensions.dynamicAudio)
            },
            builtInThemes = host.provider::builtInChatThemes,
            builtInContexts = host.provider::builtInContexts,
            builtInInstructs = host.provider::builtInInstructs,
            updateReasoningCollapse = { collapse -> host.mutable.update { it.copy(reasoningCollapse = collapse) } },
        ) { host.notice(it) }

    private suspend fun updateWorldActivation(changes: List<WorldBook>) = withContext(NonCancellable) {
        val books = withContext(Dispatchers.IO) { host.store.updateWorldActivation(changes); host.store.books() }
        host.mutable.update { it.copy(books = books) }
    }

    private suspend fun createBoundBook(book: WorldBook, binding: ScriptWorldBinding) = withContext(NonCancellable) {
        val (books, cards) = withContext(Dispatchers.IO) {
            host.store.createBoundWorldBook(book, binding)
            host.store.books() to if (binding is ScriptWorldBinding.Character) host.store.cards() else null
        }
        host.mutable.update { current ->
            val linked = (binding as? ScriptWorldBinding.Conversation)?.chat?.takeIf { it.id == current.chat?.id }
            val prefs = (binding as? ScriptWorldBinding.Persona)?.prefs ?: current.prefs
            current.copy(books = books, cards = cards ?: current.cards, chat = linked ?: current.chat, prefs = prefs)
        }
    }

    private suspend fun scriptCheckpoint(command: String, named: Map<String, String>, text: String): String {
        val snapshot = host.current
        val chat = requireNotNull(snapshot.chat) { "请先打开对话" }
        return executeCheckpointCommand(command, named, text, chat, snapshot.history, host.chats::createChatFork) { target ->
            require(host.current.generation == null) { "请等待当前回复结束再切换聊天" }
            host.chats.openStoredChat(target.id).title
        }
    }

    private suspend fun scriptCaption(named: Map<String, String>, prompt: String): String {
        val chat = checkNotNull(host.current.chat) { "请先打开对话" }
        check(host.current.captionReady()) { "请先配置图片描述模型" }
        val messageId = named["mesId"] ?: named["mesid"] ?: named["id"]
        val index = named["index"]?.toIntOrNull() ?: 0
        val image = if (messageId != null) {
            val at = messageId.toIntOrNull()?.let { if (it < 0) chat.messages.size + it else it }
            val images = at?.let(chat.messages::getOrNull)?.displayImages.orEmpty()
            checkNotNull(images.getOrNull(index)) { "该消息中找不到指定图片" }
        } else {
            val path = requestScriptInput(ScriptDialog(kind = "image-file", title = "选择要描述的图片", text = ""))
                ?: return ""
            withContext(Dispatchers.IO) {
                val uri = Uri.parse(path)
                host.media.importCaptionableImage(uri, host.app.documentName(uri))
            }
        }
        val description = host.media.askImageDescription(image, prompt) ?: return ""
        if (!named["quiet"].orEmpty().scriptBoolean(default = false)) {
            val active = host.current.chat?.takeIf { it.id == chat.id } ?: error("对话已切换")
            val persona = host.current.persona
            val message = Message(
                "user", persona.name, "", personaId = persona.id, images = listOf(image),
                imageCaptions = mapOf(image.id to description),
            )
            host.persist(active.copy(updated = System.currentTimeMillis(), messages = active.messages + message))
            quickEvent(QuickEvent.USER)
        }
        return description
    }

    private suspend fun scriptMessage(command: String, named: Map<String, String>, input: String): String {
        val chat = requireNotNull(host.current.chat) { "请先打开对话" }
        val messages = chat.messages
        val text = input.trim()
        val includeTools = named["toolcalls"].orEmpty().scriptBoolean(default = true)
        return when (command) {
            "hide", "unhide" -> {
                val range = scriptMessageRange(text.ifEmpty { "-1" }, messages.size) ?: return ""
                val name = named["name"].orEmpty().trim()
                host.chats.changeMessageContext(chat, range.toSet(), command == "hide", name)
                ""
            }
            "cut", "del", "delname" -> {
                val selected = when (command) {
                    "cut" -> scriptMessageRange(text, messages.size)?.toSet() ?: return ""
                    "delname" -> {
                        if (text.isEmpty()) return ""
                        val name = host.current.cards.firstOrNull { it.name.equals(text, true) }?.name ?: text
                        messages.indices.filter { messages[it].name == name }.toSet()
                    }
                    else -> {
                        if (text.isEmpty()) {
                            host.mutable.update { it.copy(messageSelection = MessageSelectionRequest(chat.id, includeTools)) }
                            return ""
                        }
                        val count = text.toIntOrNull() ?: error("请输入要删除的消息数量")
                        if (count < 1) return ""
                        require(count <= messages.size) { "当前对话只有 ${messages.size} 条消息" }
                        (messages.size - count until messages.size).toSet()
                    }
                }
                host.chats.removeMessages(chat, selected, command != "delname" && includeTools)
                if (command == "delname") "" else selected.joinToString("") { "${messages[it].requestText}\n" }
            }
            "message-role", "message-name" -> updateScriptAuthor(chat, command, named["at"], text)
            "addswipe", "delswipe", "swipe" -> scriptSwipe(chat, command, named, input)
            else -> ""
        }
    }

    private suspend fun updateScriptAuthor(chat: Chat, command: String, at: String?, text: String): String {
        val index = scriptMessageRange(at ?: "-1", chat.messages.size)?.takeIf { it.first == it.last }?.first ?: return ""
        val original = chat.messages[index]
        if (command == "message-role") {
            val role = text.lowercase()
            if (role !in setOf("system", "assistant", "user")) return original.scriptRole
            if (original.toolTurn != null) return original.scriptRole
            if (role == original.scriptRole) return role
            val character = (host.current.cards.firstOrNull { it.name == original.name }?.id ?: original.characterId)
                .takeIf { role == "assistant" }
            val persona = (host.current.prefs.personas.firstOrNull { it.name == original.name }?.id ?: original.personaId)
                .takeIf { role == "user" }
            host.chats.updateMessage(chat, index, original.withScriptRole(role, character, persona))
            return role
        }
        if (text.isEmpty()) return original.name
        val card = host.current.cards.firstOrNull { it.name.equals(text, true) && original.scriptRole == "assistant" }
        val persona = host.current.prefs.personas.firstOrNull { it.name.equals(text, true) && original.role == "user" }
        val name = card?.name ?: persona?.name ?: text
        host.chats.updateMessage(chat, index, original.copy(name = name, characterId = card?.id, personaId = persona?.id))
        return name
    }

    private suspend fun scriptSwipe(chat: Chat, command: String, named: Map<String, String>, input: String): String {
        val message = chat.messages.lastOrNull()?.takeIf { it.role == "assistant" && !it.hidden } ?: return ""
        val index = chat.messages.lastIndex
        return when (command) {
            "addswipe" -> {
                if (input.isBlank()) return ""
                val updated = message.withScriptAlternative(input, named["switch"].orEmpty().scriptBoolean())
                host.chats.updateMessage(chat, index, updated)
                updated.variants.lastIndex.toString()
            }
            "delswipe" -> {
                if (message.variants.size < 2) return ""
                val option = input.trim().toIntOrNull()?.minus(1) ?: message.index
                if (option !in message.variants.indices) return ""
                val updated = message.withoutAlternative(option)
                host.chats.updateMessage(chat, index, updated)
                updated.index.toString()
            }
            else -> {
                check(host.current.generation == null) { "请等待当前回复完成" }
                val left = named["direction"].equals("left", ignoreCase = true)
                val option = message.index + if (left) -1 else 1
                if (option in message.variants.indices) host.chats.updateMessage(chat, index, message.select(option))
                else if (!left) {
                    val card = host.current.replyingCharacter(message) ?: return ""
                    val job = host.generation.generate(chat, listOf(card), replacedMessages = 1, action = ReplyAction.SWIPE)
                    if (named["await"].orEmpty().scriptBoolean()) job?.join()
                }
                ""
            }
        }
    }

    private suspend fun scriptTools(command: String, named: Map<String, String>, text: String): String {
        if (command == "unregister") {
            val name = text.trim()
            require(name.isNotBlank()) { "请输入要注销的工具名称" }
            customTools.remove(name)
            if (name == DRAW_IMAGE_TOOL) nativeDrawingToolRemoved = true
            return ""
        }
        val tools = activeTools(host.current.prefs, host.current.chat, host.current.regexCard())
        if (command == "list") {
            val rows = JSONArray(tools.map(ModelTool::json))
            return when (val mode = named["return"]?.trim()?.lowercase() ?: "popup-html") {
                "pipe", "object" -> rows.toString()
                "popup", "popup-html", "popup-text" -> {
                    requestScriptInput(ScriptDialog(kind = "popup", title = "已注册工具", text = toolListText(tools)))
                    ""
                }
                "toast", "toast-html", "toast-text" -> {
                    host.notice(tools.joinToString(" · ", transform = ModelTool::label).ifBlank { "暂无已注册工具" })
                    ""
                }
                "console" -> "".also { Log.i("TavernNative", rows.toString()) }
                "none" -> ""
                else -> error("未知的工具列表返回类型 $mode")
            }
        }
        val chosen = requireNotNull(tools.firstOrNull { it.name == text.trim() }) { "工具未注册：$text" }
        val args = toolArguments(named["parameters"] ?: error("请用 parameters= 提供工具参数"))
        return chosen.execute(args)
    }

    private fun toolListText(tools: List<ModelTool>): String = tools.joinToString("\n\n") {
        "${it.label} · ${it.name}\n${it.description}\n${it.parameters.toString(2)}"
    }.ifBlank { "暂无已注册工具" }

    private suspend fun scriptDrawing(command: String, named: Map<String, String>, text: String): String {
        if (command == "gallery") return host.media.loadImageGallery()
        val settings = host.current.prefs.drawing
        val providers = host.current.prefs.providers
        when (command) {
            "style" -> {
                if (text.isBlank()) return settings.activeStyle
                val style = if (text.equals("none", true)) "" else
                    requireNotNull(settings.styles.firstOrNull { it.name.equals(text, true) }) { "找不到绘图风格 $text" }.name
                host.persistPreferences(host.current.prefs.copy(drawing = settings.copy(activeStyle = style)))
                return style
            }
            "source" -> {
                val saved = settings.defaultProfile(providers)
                if (text.isBlank()) return saved?.protocol?.source.orEmpty()
                val profile = resolveImageProfileForSource(text, providers, settings, named["provider"], named["model"])
                host.persistPreferences(host.current.prefs.copy(drawing = settings.copy(profile = profile)))
                return profile.protocol.source
            }
            "workflow" -> {
                if (text.isBlank()) return host.current.comfyWorkflow(settings.comfyWorkflowId)?.name.orEmpty()
                val workflow = findComfyWorkflow(text)
                val profile = settings.profile?.let {
                    if (it.protocol.usesWorkflow) it.copy(workflowId = workflow.id) else it
                }
                host.persistPreferences(host.current.prefs.copy(drawing = settings.copy(profile = profile, comfyWorkflowId = workflow.id)))
                return workflow.name
            }
        }
        val chat = requireNotNull(host.current.chat) { "请先打开对话" }
        val mode = DrawingMode.from(text)
        val profile = named["source"]?.takeIf(String::isNotBlank)?.let { source ->
            resolveImageProfileForSource(source, providers, settings, named["provider"], named["model"])
        } ?: requireNotNull(settings.defaultProfile(providers)) { "请先在生成图片窗口保存默认绘图服务" }
        val references = host.current.draftImages[chat.id].orEmpty().takeIf { profile.protocol.supportsReferences }.orEmpty()
        val request = requireNotNull(profile.resolve(providers, "", references)) { "绘图服务不可用" }
        val truthy = setOf("true", "1", "yes", "on")
        val extend = named["extend"]?.let { it.lowercase() in truthy } ?: settings.extendFree
        var prompt = host.media.drawingPrompt(chat, mode, if (mode == DrawingMode.FREE) text else "", extend)
        if (named["edit"]?.lowercase() in truthy) {
            prompt = requestScriptInput(ScriptDialog(kind = "input", title = "编辑绘图提示词", text = "", initial = prompt))
                ?: return ""
        }
        val workflowId = if (profile.protocol.usesWorkflow) {
            named["workflow"]?.let(::findComfyWorkflow)?.id ?: request.workflowId ?: settings.comfyWorkflowId
        } else null
        val options = host.media.prepareDrawing(
            request.copy(
                prompt = prompt,
                model = named["model"] ?: request.model,
                workflowId = workflowId,
                tuning = request.tuning.override(named),
            ),
            chat,
            mode,
            null,
        )
        val quiet = named["quiet"]?.lowercase() in truthy
        val gallery = named["gallery"]?.let { it.lowercase() in truthy } ?: true
        host.mutable.update { it.copy(generatingImage = true) }
        return try {
            host.media.renderDrawing(chat, options, quiet = quiet, gallery = gallery, background = mode == DrawingMode.BACKGROUND).drawingAddress()
        } finally {
            host.mutable.update { it.copy(generatingImage = false) }
        }
    }

    private fun findComfyWorkflow(name: String): ComfyWorkflow = requireNotNull(host.current.comfyWorkflows.firstOrNull {
        val short = if (name.endsWith(".json", ignoreCase = true)) name.dropLast(5) else name
        it.id == name || it.name.equals(short, true) ||
            it.id == DEFAULT_COMFY_WORKFLOW && short.equals("Default_Comfy_Workflow", true) ||
            it.id == AVATAR_COMFY_WORKFLOW && short.equals("Char_Avatar_Comfy_Workflow", true)
    }) { "找不到 ComfyUI 工作流 $name" }

    private fun createDebugger(label: String, source: String, stepMode: Boolean): QuickReplyDebuggerSession =
        QuickReplyDebuggerSession(
            label = label,
            source = source,
            initialStepMode = stepMode,
            readChatVariables = { host.current.chat?.options?.variables.orEmpty() },
            readGlobalVariables = { host.current.prefs.scriptVariables },
            onStateChanged = { snapshot -> host.mutable.update { it.copy(scriptDebug = snapshot) } },
        ).also { activeDebugger = it }

    fun runScript(source: String, input: String = ""): Boolean = launchScript {
        val debugger = if (source.contains("/breakpoint", ignoreCase = true)) {
            createDebugger("STscript 调试", source, stepMode = false)
        } else null
        try {
            StScript(scriptHost(input), debugger = debugger).run(source)
        } finally {
            if (activeDebugger === debugger) {
                activeDebugger = null
                host.mutable.update { it.copy(scriptDebug = null) }
            }
        }
    }

    fun runQuickReply(set: QuickReplySet, item: QuickReply, input: String): Boolean =
        launchScript { playQuickReply(set, item, input, automatic = false, stepDebug = false) }

    fun debugQuickReply(set: QuickReplySet, item: QuickReply, input: String = ""): Boolean =
        launchScript { playQuickReply(set, item, input, automatic = false, stepDebug = true) }

    private fun launchScript(block: suspend () -> Unit): Boolean {
        if (host.current.busy || host.current.generation != null || host.current.scriptRunning) return false
        host.generation.stopAuto()
        host.mutable.update { it.copy(scriptRunning = true) }
        val job = host.scope.launch {
            trackScript(block)
        }
        if (!job.isCompleted && host.current.scriptRunning) scriptJob = job
        job.invokeOnCompletion {
            if (scriptJob === job) {
                scriptJob = null
                host.mutable.update { it.copy(scriptRunning = false, scriptDialog = null) }
            }
        }
        return true
    }

    private suspend fun trackScript(block: suspend () -> Unit) {
        val job = currentCoroutineContext()[Job]
        scriptJob = job
        host.mutable.update { it.copy(scriptRunning = true) }
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            host.notice(e.localizedMessage ?: "脚本执行失败")
        } finally {
            if (scriptJob === job) {
                scriptJob = null
                activeDebugger?.cancel()
                activeDebugger = null
            }
            host.mutable.update { it.copy(scriptRunning = false, scriptDialog = null, scriptDebug = null) }
        }
    }

    internal suspend fun quickEvent(event: QuickEvent) = runAutomatic { event in it.events }

    internal suspend fun quickWorldEvent(ids: Set<String>) =
        runAutomatic(ids.withIndex().associate { (index, id) -> id to index }) { it.automationId in ids }

    private suspend fun runAutomatic(order: Map<String, Int> = emptyMap(), matches: (QuickReply) -> Boolean) {
        val config = host.current.prefs.quickReplies
        if (!config.enabled || preventNestedQuickReplies || quickReplyDepth >= 8) return
        val items = config.selected(host.current.chat).flatMap { set ->
            set.items.filter { matches(it) && it.message.isNotBlank() }.map { set to it }
        }
        if (items.isEmpty()) return
        val ordered = if (order.isEmpty()) items else items.sortedBy { (_, item) -> order.getValue(item.automationId) }
        suspend fun play() {
            quickReplyDepth++
            try {
                for ((set, item) in ordered) {
                    try {
                        playQuickReply(set, item, "", automatic = true)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        host.notice(e.localizedMessage ?: "快捷回复执行失败")
                    }
                }
            } finally {
                quickReplyDepth--
            }
        }
        if (host.current.scriptRunning) play() else trackScript { play() }
    }

    private suspend fun playQuickReply(
        set: QuickReplySet,
        item: QuickReply,
        input: String,
        automatic: Boolean,
        stepDebug: Boolean = false,
    ) {
        val source = if (automatic) item.message else set.textFor(item, input)
        val scriptHost = scriptHost(input)
        val debugger = if (!automatic && (stepDebug || source.contains("/breakpoint", ignoreCase = true))) {
            createDebugger(item.label.ifBlank { set.name.ifBlank { "快捷回复调试" } }, source, stepMode = stepDebug)
        } else null
        val engine = StScript(scriptHost, debugger = debugger)
        if (source.isBlank()) return
        val previous = preventNestedQuickReplies
        preventNestedQuickReplies = previous || item.preventAutoExecute
        try {
            if (!set.disableSend && source.trimStart().startsWith('/')) engine.run(source)
            else {
                val message = engine.render(source)
                if (set.disableSend) scriptDraft(message)
                else {
                    val shouldGenerate = host.current.generation == null && host.current.group?.order != ReplyOrder.MANUAL
                    if (shouldGenerate && host.current.prefs.connectionFor(host.current.chat) == null) {
                        scriptDraft(message)
                        host.notice("请先配置模型提供商")
                    } else {
                        scriptHost.command("send", emptyMap(), message)
                        if (shouldGenerate) scriptTrigger(null)
                    }
                }
            }
        } finally {
            preventNestedQuickReplies = previous
            if (activeDebugger === debugger) {
                activeDebugger = null
                host.mutable.update { it.copy(scriptDebug = null) }
            }
        }
    }

    private fun scriptDraft(value: String) {
        val chat = host.current.chat ?: return
        host.mutable.update { it.copy(suggestedDraft = DraftSuggestion(chat.id, value)) }
    }

    internal suspend fun requestScriptInput(dialog: ScriptDialog): String? {
        val result = CompletableDeferred<String?>()
        scriptAnswer = result
        host.mutable.update { it.copy(scriptDialog = dialog) }
        return try { result.await() } finally {
            scriptAnswer = null
            host.mutable.update { it.copy(scriptDialog = null) }
        }
    }

    private suspend fun scriptEditCharacter(command: ScriptCharacterCommand, named: Map<String, String>): String {
        val creating = command == ScriptCharacterCommand.CREATE
        val source = if (creating) null else host.current.scriptCardTarget(named)
            ?: return (if (command == ScriptCharacterCommand.DELETE) "false" else "").also { host.notice("找不到指定角色") }
        if (command == ScriptCharacterCommand.DELETE) {
            val card = requireNotNull(source)
            val deleteChats = named["deleteChats"].orEmpty().scriptBoolean()
            if (!named["silent"].orEmpty().scriptBoolean()) {
                val details = if (deleteChats) "角色与关联的聊天和备份将被删除。"
                    else "角色将被删除，关联聊天将保存在归档群组中。"
                if (requestScriptInput(ScriptDialog(kind = "confirm-delete", title = "删除「${card.name}」？", text = details)) == null) {
                    return "false"
                }
            }
            if (host.current.generation != null &&
                (host.current.cardId == card.id || host.current.generation?.characterId == card.id)) {
                return "false".also { host.notice("该角色正在生成回复") }
            }
            return try {
                host.library.removeCard(card, deleteChats)
                "true"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                host.notice(e.localizedMessage ?: "无法删除角色")
                "false"
            }
        }
        val select = (creating || command == ScriptCharacterCommand.DUPLICATE) &&
            named["select"].orEmpty().scriptBoolean(default = creating)
        check(!select || host.current.generation == null) { "生成期间无法切换角色" }
        val saved = if (command == ScriptCharacterCommand.DUPLICATE) host.library.copyCard(requireNotNull(source), keepName = true)
        else {
            val changes = ScriptCardChanges.from(named)
            require(!creating || changes.fields.containsKey("name")) { "请输入角色名称" }
            val portrait = named["avatar"]?.takeIf(String::isNotBlank)?.let {
                scriptAvatar(it, named["avatarPromptResize"].orEmpty().scriptBoolean(default = true))
            }
            check(!select || host.current.generation == null) { "生成期间无法切换角色" }
            host.library.saveScriptCard(source?.id, changes, portrait)
        }
        if (select) {
            if (host.current.generation == null) host.chats.startChat(saved, saved.greeting)
            else host.notice("角色已保存，当前回复结束后可打开角色")
        }
        return saved.scriptAvatarKey
    }

    private suspend fun scriptAvatar(input: String, crop: Boolean): ByteArray? {
        val name = input.trim()
        return try {
            val source = if (name.equals("prompt", true)) {
                val picked = requestScriptInput(ScriptDialog(kind = "image-file", title = "选择角色头像", text = "")) ?: return null
                withContext(Dispatchers.IO) {
                    checkNotNull(host.resolver.openInputStream(Uri.parse(picked))) { "无法读取头像" }.use { it.readLimited(MAX_IMAGE_SOURCE) }
                }
            } else withContext(Dispatchers.IO) {
                originalGroupAvatarData(name) ?: localScriptAvatar(name)
            }
            val selected = if (crop) {
                val data = requestScriptInput(ScriptDialog(kind = "avatar-crop", title = "裁剪角色头像", text = "", avatarBytes = source))
                    ?: return null
                requireNotNull(originalGroupAvatarData(data)) { "无法读取裁剪结果" }
            } else source
            withContext(Dispatchers.Default) { host.library.resizeAvatar(selected) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            host.notice(e.localizedMessage ?: "无法读取头像")
            null
        }
    }

    private fun localScriptAvatar(address: String): ByteArray {
        val state = host.current
        val path = address.removePrefix("./").removePrefix("/")
        val filename = path.substringAfterLast('/')
        val background = state.prefs.backgrounds.find(filename)?.image
        val bytes = when {
            address.startsWith("tavern://image/") -> {
                val images = parseDrawingMarkup(address) { image ->
                    host.imageStore.file(image).isFile || host.savedImages.file(image).isFile
                }.images
                images.singleOrNull()?.let { host.imageStore.bytes(it) ?: host.savedImages.bytes(it) }
            }
            path.startsWith("characters/", true) || '/' !in path -> scriptActor(state.cards, filename)?.takeIf(Card::hasAvatar)
                ?.let { host.store.portraitFile(it.id).inputStream().use { file -> file.readLimited(MAX_IMAGE_SOURCE) } }
            path.startsWith("User Avatars/", true) -> state.prefs.personas.firstOrNull { persona ->
                "${persona.avatar}.png".equals(filename, true) || persona.name.equals(filename.substringBeforeLast('.'), true)
            }?.avatar?.let { host.store.personaAvatar(it).inputStream().use { file -> file.readLimited(MAX_IMAGE_SOURCE) } }
            path.startsWith("backgrounds/", true) -> background?.let { host.savedImages.bytes(it) ?: host.imageStore.bytes(it) }
            path.startsWith("assets/", true) || path.startsWith("user/images/", true) -> {
                val chat = state.chat
                val images = chat?.messages.orEmpty().flatMap(Message::imageAssets) +
                    (chat?.let { host.store.gallery(it.cardId, it.groupId).map(GalleryImage::image) }.orEmpty())
                val image = images.firstOrNull { it.filename.equals(filename, true) || it.name.equals(filename, true) } ?: background
                image?.let { host.imageStore.bytes(it) ?: host.savedImages.bytes(it) }
            }
            else -> null
        }
        return requireNotNull(bytes) { "未找到头像素材，可用 avatar=prompt 选择本机图片" }
    }

    fun completeScriptDialog(id: String, response: String?) {
        if (host.current.scriptDialog?.id == id) scriptAnswer?.complete(response)
    }

    private suspend fun requestTrigger(name: String?, awaitReply: Boolean) {
        val chatId = requireNotNull(host.current.chat) { "请先打开对话" }.id
        if (awaitReply) {
            check(!host.generation.owns(currentCoroutineContext()[Job])) { "当前生成事件无法等待另一条回复" }
            triggerWhenReady(chatId, name)
            return
        }
        val queued = host.scope.launch(start = CoroutineStart.LAZY) {
            val job = requireNotNull(currentCoroutineContext()[Job])
            try {
                yield()
                triggerWhenReady(chatId, name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (host.current.chat?.id == chatId) host.notice(e.localizedMessage ?: "无法触发回复")
            } finally {
                pendingTriggers.remove(job)
            }
        }
        pendingTriggers += queued
        queued.start()
    }

    private suspend fun triggerWhenReady(chatId: String, name: String?) {
        val ready = awaitScriptTriggerSlot(host.mutable, chatId)
        if (ready == true && host.current.chat?.id == chatId) scriptTrigger(name)
        else if (ready == null && host.current.chat?.id == chatId) host.notice("仍有模型请求在进行，暂时无法触发新回复")
    }

    private suspend fun scriptTrigger(name: String?) {
        val chat = requireNotNull(host.current.chat) { "请先打开对话" }
        check(host.current.generation == null) { "已有模型请求在进行" }
        check(host.current.prefs.connectionFor(chat) != null) { "请先配置模型提供商" }
        val group = host.current.group
        val specified = if (group != null && name != null) group.scriptMember(host.current.cards, name) else null
        if (specified == null && group != null && name != null) host.notice("找不到群成员 $name，将按群聊设置选择发言者")
        val speakers = specified?.let(::listOf) ?: group?.nextTurn(host.current.cards, chat.messages) ?: listOfNotNull(host.current.card)
        require(speakers.isNotEmpty()) { "找不到发言角色" }
        val generator = requireNotNull(host.generation.generate(chat, speakers))
        generator.join()
    }

    private suspend fun scriptAsk(name: String, text: String): Message? {
        val state = host.current
        val chat = requireNotNull(state.chat) { "请先打开对话" }
        if (chat.groupId != null) return null.also { host.notice("请在单聊使用 /ask") }
        check(state.generation == null) { "已有模型请求在进行" }
        val guest = scriptActor(state.cards, name, state.card) ?: return null.also { host.notice("找不到角色 $name") }
        check(state.prefs.connectionFor(chat) != null) { "请先配置模型提供商" }
        val prompt = withContext(Dispatchers.Default) {
            state.textRules(chat).transform(text.trim(), RegexSite.COMMAND, RegexStage.STORAGE)
        }
        val addUser = prompt.isNotBlank()
        val translation = chat.options.translation
        val wire = if (addUser && translation.mode.input) {
            host.chats.automaticTranslation(state.prefs, chat, prompt, translation.requestLanguage)
        } else null
        val active = checkNotNull(host.current.chat?.takeIf { it.id == chat.id && host.current.generation == null }) {
            "对话已切换或正在生成回复"
        }
        val withPrompt = if (!addUser) active else active.copy(
            updated = System.currentTimeMillis(), messages = active.messages + Message(
                "user", state.persona.name, prompt, personaId = state.persona.id, wireText = wire,
            ),
        )
        var reply: Message? = null
        val generator = requireNotNull(host.generation.generate(withPrompt, listOf(guest), addUser = addUser,
            preserveDraftAttachments = true, onSavedReply = { reply = it }))
        generator.join()
        return reply
    }

    private suspend fun scriptGenerate(text: String, named: Map<String, String>, raw: Boolean = false): String {
        val config = host.current
        val chat = config.chat
        val prefs = config.prefs
        val settings = ScriptGenerationOptions.read(named, raw) { key ->
            chat?.options?.variables?.get(key) ?: prefs.scriptVariables[key] ?: key
        }
        val effective = prefs.effectiveConfig(chat)
        val connection = host.provider.formattedConnection(requireNotNull(effective.connection) { "请先配置模型提供商" })
        val explicit = settings.name.takeIf { !raw && it.isNotEmpty() }?.let { name ->
            scriptActor(config.cards, name, config.regexCard())
        }
        val character = explicit ?: config.regexCard()
        val group = config.group?.takeIf { character != null }?.let {
            GroupPrompt(
                it, it.promptMembers(config.cards, requireNotNull(character)), it.members(config.cards).map(Card::name),
                it.participants(config.cards).map(Card::name),
            )
        }
        val preset = config.presetFor(chat)
        var loreBudget: LoreBudgetUsage? = null
        var contextBudget: PromptBudgetUsage? = null
        fun capture(messages: JSONArray) {
            if (chat == null) return
            host.mutable.update { current -> current.copy(lastPrompt = PromptCapture(chat.id, connection.model, messages,
                loreBudget, contextBudget, connection.provider.id)) }
        }
        if (raw) {
            val macros = ConversationMacros(character, config.persona, chat?.messages.orEmpty(), group,
                chat?.options ?: ConversationOptions(), prefs.scriptVariables, connection.model, chat?.id.orEmpty(), "quiet")
            val turn = InstructTurn(config.persona.name, character?.name ?: "Assistant", trigger = "quiet",
                group = group != null, quiet = !settings.asCharacter, expand = { macros.expand(it) })
            val compiled = connection.provider.compiledRequestOptions(
                PromptPostProcessingNames(turn.user, turn.character, group?.roster.orEmpty()),
            ) { macros.expand(it) }
            return ModelService.scriptRaw(connection, text, settings, preset, effective.sampling, turn,
                prefs.customStops, prefs.replyFormatting, compiled, ::capture, host::notice,
                tokenCounter = withContext(Dispatchers.Default) { host.chatTokenizers.counterFor(connection) })
        }
        requireNotNull(chat) { "请先打开对话" }
        requireNotNull(character) { "找不到发言角色" }
        val history = chat.messages
        val quiet = ScriptQuietPrompt(text, explicit?.name ?: settings.name.ifBlank { "System" }, settings.asCharacter, settings.stops)
        val recallHistory = if (text.isBlank()) history else history + Message("user", config.persona.name, text)
        val output = StringBuilder()
        val vectors = host.world.promptVectors(prefs, chat, character, config.persona, config.books, recallHistory)
        val sharedMemory = host.world.promptSharedWorldMemory(chat, character, recallHistory, config.books, config.groups, config.cards)
        val loreCounter = withContext(Dispatchers.Default) { host.chatTokenizers.counterFor(connection) }
        val promptOptions = resolvePromptInjections(chat.options)
        val result = ModelService.reply(
            prefs, connection, character, history, config.books, group, config.persona, promptOptions,
            "quiet", preset, onPrompt = ::capture,
            readImage = host.imageStore::bytes, readDocument = host.documentStore::text, vectors = vectors,
            onRetry = { output.clear() }, sharedMemory = sharedMemory, loreCounter = loreCounter,
            onContextBudget = { contextBudget = it.copy(firstHistoryIndex = null, firstHistoryDate = null) },
            onLoreBudget = {
                loreBudget = it
                if (it.blocked && prefs.loreSettings.overflowAlert) host.notice("世界书额度已达 ${it.limit} Token，部分条目未注入")
            },
            chatId = chat.id, onVariables = { host.persistMacroVariables(chat.id, it) },
            effectiveSampling = effective.sampling.copy(maxTokens = settings.length?.toString() ?: effective.sampling.maxTokens),
            quietPrompt = quiet,
        ) { output.append(it) }
        val flushed = ScriptPromptInjections.withoutEphemeral(chat.options)
        if (flushed != chat.options) {
            host.current.chat?.takeIf { it.id == chat.id }?.let { active ->
                host.persist(active.copy(options = ScriptPromptInjections.withoutEphemeral(active.options)))
            }
        }
        result.incomplete?.let(host::notice)
        return output.toString().trim().let { if (settings.trim) trimToCompleteSentence(it) else it }
    }
}
