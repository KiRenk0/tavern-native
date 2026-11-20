package app.tavern.nativeclient.script

import android.net.Uri
import android.util.Log
import app.tavern.nativeclient.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal class ScriptCoordinator(private val host: CoordinatorHost) {
    private var scriptJob: Job? = null
    private var scriptAnswer: CompletableDeferred<String?>? = null
    private val customTools = linkedMapOf<String, ModelTool>()
    internal var nativeDrawingToolRemoved = false
        private set
    private var preventNestedQuickReplies = false
    private var quickReplyDepth = 0

    fun cancelScript() {
        scriptJob?.cancel()
    }

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
            ::scriptTrigger,
            ::scriptGenerate,
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
        ) { host.notice(it) }

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
                val protocol = when (text.lowercase()) {
                    "google", "gemini" -> ImageProtocol.GEMINI
                    "openai", "compatible" -> ImageProtocol.COMPATIBLE
                    "auto", "automatic1111", "sd", "forge" -> ImageProtocol.DIFFUSION
                    "novelai", "nai" -> ImageProtocol.NOVELAI
                    "comfy", "comfyui" -> ImageProtocol.COMFY
                    "comfyrunpod", "runpod" -> ImageProtocol.COMFY_RUNPOD
                    else -> null
                }
                val providerName = named["provider"] ?: text.takeIf { protocol == null }
                val provider = requireNotNull(providers.sortedByDescending { it.id == saved?.providerId }.firstOrNull { candidate ->
                    (providerName == null || candidate.id == providerName || candidate.name.equals(providerName, true)) &&
                        (protocol?.available(candidate) ?: ImageProtocol.entries.any { it.available(candidate) })
                }) { "请先配置对应的绘图服务" }
                val selected = protocol ?: saved?.protocol?.takeIf { it.available(provider) }
                    ?: ImageProtocol.entries.first { it.available(provider) }
                val model = named["model"] ?: selected.initialModel(provider)
                val profile = if (saved?.providerId == provider.id && saved.protocol == selected && "model" !in named) saved
                else ImageProfile(provider.id, selected, model, workflowId = settings.comfyWorkflowId.takeIf { selected.usesWorkflow })
                host.persistPreferences(host.current.prefs.copy(drawing = settings.copy(profile = profile)))
                return selected.source
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
        val profile = requireNotNull(settings.defaultProfile(providers)) { "请先在生成图片窗口保存默认绘图服务" }
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

    fun runScript(source: String, input: String = ""): Boolean = launchScript { StScript(scriptHost(input)).run(source) }

    fun runQuickReply(set: QuickReplySet, item: QuickReply, input: String): Boolean =
        launchScript { playQuickReply(set, item, input, automatic = false) }

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
            if (scriptJob === job) scriptJob = null
            host.mutable.update { it.copy(scriptRunning = false, scriptDialog = null) }
        }
    }

    internal suspend fun quickEvent(event: QuickEvent) = runAutomatic { event in it.events }

    internal suspend fun quickWorldEvent(ids: Set<String>) = runAutomatic { it.automationId in ids }

    private suspend fun runAutomatic(matches: (QuickReply) -> Boolean) {
        val config = host.current.prefs.quickReplies
        if (!config.enabled || preventNestedQuickReplies || quickReplyDepth >= 8) return
        val items = config.selected(host.current.chat).flatMap { set ->
            set.items.filter { matches(it) && it.message.isNotBlank() }.map { set to it }
        }
        if (items.isEmpty()) return
        suspend fun play() {
            quickReplyDepth++
            try {
                for ((set, item) in items) {
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

    private suspend fun playQuickReply(set: QuickReplySet, item: QuickReply, input: String, automatic: Boolean) {
        val source = if (automatic) item.message else set.textFor(item, input)
        val scriptHost = scriptHost(input)
        val engine = StScript(scriptHost)
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

    fun completeScriptDialog(id: String, response: String?) {
        if (host.current.scriptDialog?.id == id) scriptAnswer?.complete(response)
    }

    private suspend fun scriptTrigger(name: String?): String {
        val chat = requireNotNull(host.current.chat) { "请先打开对话" }
        check(host.current.generation == null) { "已有模型请求在进行" }
        check(host.current.prefs.connectionFor(chat) != null) { "请先配置模型提供商" }
        val group = host.current.group
        val speakers = if (group != null && name != null) {
            listOfNotNull(group.members(host.current.cards).firstOrNull { it.name.equals(name, true) })
        } else group?.nextTurn(host.current.cards, chat.messages) ?: listOfNotNull(host.current.card)
        require(speakers.isNotEmpty()) { "找不到发言角色" }
        val generator = requireNotNull(host.generation.generate(chat, speakers))
        generator.join()
        return host.current.chat?.messages?.drop(chat.messages.size)?.lastOrNull { it.role == "assistant" }?.text
            ?: error(host.current.notice ?: "模型没有返回回复")
    }

    private suspend fun scriptGenerate(text: String, named: Map<String, String>): String {
        val chat = requireNotNull(host.current.chat) { "请先打开对话" }
        val config = host.current
        val character = requireNotNull(config.regexCard()) { "找不到发言角色" }
        val connection = requireNotNull(config.prefs.connectionFor(chat)) { "请先配置模型提供商" }
        val prefs = config.prefs.copy(
            maxTokens = named["max"]?.toIntOrNull()?.takeIf { it > 0 }?.toString()
                ?: config.prefs.maxTokens,
        )
        val history = chat.messages + listOfNotNull(
            text.takeIf(String::isNotBlank)?.let { Message("user", config.persona.name, it) },
        )
        val group = config.group?.let {
            GroupPrompt(
                it, it.promptMembers(config.cards, character), it.members(config.cards).map(Card::name),
                it.participants(config.cards).map(Card::name),
            )
        }
        val output = StringBuilder()
        val vectors = host.world.promptVectors(config.prefs, chat, character, config.persona, config.books, history)
        val sharedMemory = host.world.promptSharedWorldMemory(chat, character, history, config.books, config.groups, config.cards)
        val loreCounter = withContext(Dispatchers.Default) { host.loreTokenizers.counterFor(connection) }
        var loreBudget: LoreBudgetUsage? = null
        val result = ModelService.reply(
            prefs, connection, character, history, config.books, group, config.persona, chat.options,
            "quiet", config.presetFor(chat),
            onPrompt = { messages ->
                host.mutable.update { it.copy(lastPrompt = PromptCapture(chat.id, connection.model, messages, loreBudget)) }
            },
            readImage = host.imageStore::bytes, readDocument = host.documentStore::text, vectors = vectors,
            onRetry = { output.clear() }, sharedMemory = sharedMemory, loreCounter = loreCounter,
            onLoreBudget = {
                loreBudget = it
                if (it.blocked && prefs.loreSettings.overflowAlert) host.notice("世界书额度已达 ${it.limit} Token，部分条目未注入")
            },
            chatId = chat.id, onVariables = { host.persistMacroVariables(chat.id, it) },
            effectiveSampling = prefs.effectiveConfig(chat).sampling,
        ) { output.append(it) }
        result.incomplete?.let(host::notice)
        return output.toString().trim()
    }
}
