package app.tavern.nativeclient.chat

import app.tavern.nativeclient.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ChatGenerationCoordinator(private val host: CoordinatorHost) {
    private var generator: Job? = null
    private var autoTimer: Job? = null

    internal fun owns(job: Job?) = job != null && generator === job

    fun send(text: String, interpretCommands: Boolean = true, allowContinueWhenEmpty: Boolean = false): Boolean {
        val state = host.current
        val chat = state.chat ?: return false
        if (state.busy || state.generation != null || state.scriptRunning) return false
        val attachments = state.draftImages[chat.id].orEmpty()
        val documents = state.draftDocuments[chat.id].orEmpty()
        if (allowContinueWhenEmpty && state.prefs.chatHandling.shouldContinueOnSend(
                chat, text, attachments.isNotEmpty() || documents.isNotEmpty(),
            ) && chat.messages.lastOrNull()?.let(state::replyingCharacter) != null) {
            return generateForLastReply(ReplyAction.CONTINUE)
        }
        val input = if (text.isBlank() && attachments.isEmpty() && documents.isEmpty()) {
            if (!interpretCommands) return false
            val connection = state.prefs.connectionFor(chat)
            val substitute = state.presetFor(chat).emptyChatInput(connection)
            if (substitute.isBlank()) return false
            val group = state.group
            val members = group?.members(state.cards).orEmpty()
            val prompt = group?.let { GroupPrompt(it, members, members.map(Card::name),
                it.participants(state.cards).map(Card::name)) }
            val actor = state.card ?: chat.messages.lastOrNull { it.role == "assistant" }?.let(state::replyingCharacter)
                ?: members.firstOrNull()
            ConversationMacros(actor, state.persona, chat.messages, prompt, chat.options, state.prefs.scriptVariables,
                connection?.model.orEmpty(), chat.id).expand(substitute).takeIf(String::isNotBlank) ?: return false
        } else text
        if (interpretCommands && text.trimStart().startsWith('/')) return host.script.runScript(text, text)
        val functionsDraw = host.current.prefs.toolCalling && host.current.prefs.connectionFor(chat) != null &&
            host.media.drawingTool(host.current.prefs, chat, host.media.drawingCard(chat)) != null
        if (interpretCommands && host.current.prefs.drawing.interactive && !functionsDraw) {
            val subject = interactiveDrawing(text)
            val request = host.current.prefs.drawing.defaultProfile(host.current.prefs.providers)
                ?.resolve(host.current.prefs.providers, "")
            if (subject != null && request != null) return host.media.launchDrawing(chat.id) { active ->
                sendInteractiveDrawing(active, text, subject, request, attachments, documents)
            }
        }
        stopAuto()
        val persona = host.current.persona
        val group = host.current.group
        val manual = group?.order == ReplyOrder.MANUAL
        if (!manual && !readyToGenerate()) return false
        val cards = host.current.cards
        val card = host.current.card
        if (!manual && (group?.participants(cards)?.isEmpty() ?: (card == null))) {
            host.notice("群组没有启用的发言角色")
            return false
        }
        val rules = host.current.textRules(chat)
        var accepted = false
        val submit: suspend () -> Unit = {
            val content = withContext(Dispatchers.Default) {
                val trimmed = input.trim()
                val raw = if (interpretCommands && trimmed.startsWith("\\/")) trimmed.drop(1) else trimmed
                rules.transform(raw, RegexSite.INPUT, RegexStage.STORAGE)
            }
            val translation = chat.options.translation
            val wire = if (translation.mode.input) host.chats.automaticTranslation(host.current.prefs, chat, content, translation.requestLanguage) else null
            val captions = host.media.prepareImageCaptions(chat.id, attachments)
            val withUser = chat.copy(
                updated = System.currentTimeMillis(),
                messages = chat.messages + Message(
                    "user", persona.name, content, personaId = persona.id, images = attachments,
                    wireText = wire, documents = documents, imageCaptions = captions,
                ),
            )
            if (manual) {
                host.persist(withUser)
                accepted = true
                host.clearDraftAttachments(chat.id)
                host.media.narrate(withUser.messages.last(), automatic = true)
                host.script.quickEvent(QuickEvent.USER)
                host.current.chat?.let { host.chats.summarizeAutomatically(it) }
            } else {
                val speakers = group?.speakers(cards, withUser.messages) ?: listOfNotNull(card)
                accepted = generate(withUser, speakers, addUser = true) != null
            }
        }
        val autoCaption = host.current.prefs.captions.automatic &&
            attachments.any { it.id !in host.current.draftImageCaptions[chat.id].orEmpty() }
        val preparationLabel = when {
            autoCaption -> "正在生成图片描述…"
            chat.options.translation.mode.input && input.isNotBlank() -> "正在翻译输入…"
            else -> null
        }
        if (preparationLabel != null) return host.utilityWork(preparationLabel) {
            try {
                submit()
            } finally {
                if (!accepted) host.mutable.update { it.copy(suggestedDraft = DraftSuggestion(chat.id, text)) }
            }
        }
        host.work(action = submit)
        return true
    }

    private suspend fun sendInteractiveDrawing(
        chat: Chat,
        text: String,
        subject: String,
        request: ImageRequest,
        attachments: List<ChatImage>,
        documents: List<ChatDocument>,
    ) {
        val persona = host.current.persona
        val rules = host.current.textRules(chat)
        val content = withContext(Dispatchers.Default) { rules.transform(text.trim(), RegexSite.INPUT, RegexStage.STORAGE) }
        val translation = chat.options.translation
        val wire = if (translation.mode.input) host.chats.automaticTranslation(host.current.prefs, chat, content, translation.requestLanguage) else null
        val message = Message(
            "user", persona.name, content, personaId = persona.id, images = attachments, documents = documents, wireText = wire,
            imageCaptions = host.media.prepareImageCaptions(chat.id, attachments),
        )
        val withUser = chat.copy(messages = chat.messages + message, updated = System.currentTimeMillis())
        host.persist(withUser)
        host.clearDraftAttachments(chat.id)
        host.media.narrate(message, automatic = true)
        val mode = DrawingMode.interactive(subject)
        val prompt = host.media.drawingPrompt(withUser, mode, subject.takeIf { mode == DrawingMode.FREE }.orEmpty(), host.current.prefs.drawing.extendFree)
        val references = if (request.protocol.supportsReferences) attachments else emptyList()
        host.media.renderDrawing(
            withUser,
            host.media.prepareDrawing(request.copy(prompt = prompt, references = references), withUser, mode, null),
            background = mode == DrawingMode.BACKGROUND,
        )
        host.script.quickEvent(QuickEvent.USER)
    }

    fun regenerate() {
        val chat = host.current.chat ?: return
        val group = host.current.group
        if (!readyToGenerate() || (chat.messages.isEmpty() && group == null)) return
        val last = chat.messages.lastOrNull()
        val previousSpeaker = last?.let(host.current::replyingCharacter)
        val replacedMessages = when {
            last?.role != "assistant" -> 0
            group == null -> if (previousSpeaker == null) 0 else 1
            else -> chat.messages.asReversed().takeWhile {
                it.groupTurnId == last.groupTurnId && (it.role == "assistant" || last.groupTurnId != null && it.role == "system")
            }.size
        }
        val speakers = if (group == null) listOfNotNull(previousSpeaker ?: host.current.card)
        else group.nextTurn(host.current.cards, chat.messages.dropLast(replacedMessages))
        if (speakers.isEmpty()) {
            host.notice("请选择发言角色")
            return
        }
        val action = if (group == null) ReplyAction.REGENERATE else ReplyAction.NORMAL
        generate(chat, speakers, replacedMessages = replacedMessages, action = action)
    }

    fun continueReply() { generateForLastReply(ReplyAction.CONTINUE) }

    fun nextAlternative() { generateForLastReply(ReplyAction.SWIPE) }

    private fun generateForLastReply(action: ReplyAction): Boolean {
        val chat = host.current.chat ?: return false
        val last = chat.messages.lastOrNull()?.takeIf { !it.hidden && it.text.isNotBlank() } ?: return false
        val character = host.current.replyingCharacter(last)
        if (character == null) {
            host.notice("找不到这条回复的发言角色")
            return false
        }
        if (!readyToGenerate()) return false
        stopAuto()
        return generate(chat, listOf(character), replacedMessages = 1, action = action) != null
    }

    fun rerollToken(chatId: String, message: Message, token: Int, candidate: ReplyTokenCandidate) {
        val chat = host.current.chat?.takeIf { it.id == chatId && it.messages.lastOrNull() == message } ?: return
        if (message.hidden || !readyToGenerate() || host.current.prefs.connectionFor(chat)?.provider?.canRerollToken != true) return
        val character = host.current.replyingCharacter(message) ?: return host.notice("找不到这条回复的发言角色")
        val prefix = message.replyTokens?.replacement(token, candidate) ?: return
        stopAuto()
        generate(chat, listOf(character), replacedMessages = 1, action = ReplyAction.CONTINUE, rerollPrefix = prefix)
    }

    fun impersonate() {
        val chat = host.current.chat ?: return
        val character = (host.current.card ?: host.current.group?.members(host.current.cards)?.randomOrNull())
            ?: return host.notice("群组还没有发言角色")
        if (!readyToGenerate()) return
        stopAuto()
        generate(chat, listOf(character), action = ReplyAction.IMPERSONATE)
    }

    fun consumeDraft(suggestion: DraftSuggestion) {
        host.mutable.update { if (it.suggestedDraft == suggestion) it.copy(suggestedDraft = null) else it }
    }

    fun speakAs(character: Card) {
        val chat = host.current.chat ?: return
        if (host.current.group?.memberIds?.contains(character.id) != true || !readyToGenerate()) return
        generate(chat, listOf(character))
    }

    private fun readyToGenerate(): Boolean {
        if (host.current.generation != null || host.current.busy || host.current.scriptRunning) return false
        if (host.current.prefs.connectionFor(host.current.chat) != null) return true
        host.notice("请先配置模型提供商")
        return false
    }

    fun toggleAuto() {
        val chat = host.current.chat ?: return
        if (host.current.autoChatId == chat.id) {
            stopAuto()
            return
        }
        val group = host.current.group ?: return
        if (!readyToGenerate()) return
        if (group.participants(host.current.cards).isEmpty()) {
            host.notice("群组没有启用的发言角色")
            return
        }
        host.mutable.update { it.copy(autoChatId = chat.id) }
        scheduleAuto()
    }

    fun stopAuto() {
        autoTimer?.cancel()
        autoTimer = null
        if (host.current.autoChatId != null) host.mutable.update { it.copy(autoChatId = null) }
    }

    fun stop() {
        stopAuto()
        host.script.cancelScript()
        generator?.cancel()
        host.media.cancelImage()
        host.cancelUtility()
    }

    private fun scheduleAuto() {
        val chatId = host.current.autoChatId ?: return
        val seconds = host.current.group?.autoDelaySeconds ?: return
        autoTimer?.cancel()
        autoTimer = host.scope.launch {
            delay(seconds * 1000L)
            autoTimer = null
            val chat = host.current.chat?.takeIf { it.id == chatId && host.current.autoChatId == chatId } ?: return@launch
            val group = host.current.group ?: return@launch stopAuto()
            if (host.current.generation != null) return@launch
            if (host.current.busy) return@launch scheduleAuto()
            if (!readyToGenerate()) return@launch stopAuto()
            val speakers = group.nextTurn(host.current.cards, chat.messages)
            if (speakers.isEmpty()) return@launch stopAuto()
            generate(chat, speakers)
        }
    }

    internal fun generate(
        chat: Chat,
        speakers: List<Card>,
        addUser: Boolean = false,
        replacedMessages: Int = 0,
        action: ReplyAction = ReplyAction.NORMAL,
        rerollPrefix: String? = null,
        preserveDraftAttachments: Boolean = false,
        onSavedReply: (Message) -> Unit = {},
    ): Job? {
        val prefs = host.current.prefs
        val selectedConnection = prefs.connectionFor(chat) ?: return null
        val persona = host.current.persona
        val group = host.current.group
        val cards = host.current.cards
        val books = host.current.books
        val groups = host.current.groups
        val preset = host.current.presetFor(chat)
        val initialText = rerollPrefix ?: chat.messages.lastOrNull()?.text.orEmpty().takeIf { action == ReplyAction.CONTINUE }.orEmpty()
        val generation = Generation(
            chat.id, text = initialText, replacedMessages = replacedMessages,
            characterId = speakers.first().id,
            speaker = if (action == ReplyAction.IMPERSONATE) persona.name else speakers.first().name,
            action = action,
            groupQueue = if (group != null && action != ReplyAction.IMPERSONATE) speakers else emptyList(),
        )
        autoTimer?.cancel()
        autoTimer = null
        host.mutable.update { it.copy(generation = generation) }
        host.eventBus.emit(TavernCoreEvent.GENERATION_STARTED, jsonOf("chat_id" to chat.id, "speaker" to generation.speaker))
        GenerationForegroundService.startOrUpdate(
            context = host.app,
            title = "正在生成回复 · ${generation.speaker}",
            status = "后台生成中，可点击停止",
            kind = GenerationForegroundService.KIND_LLM,
        )
        generator = host.scope.launch {
            var completed = false
            var receivedReply = false
            try {
                val connection = host.provider.formattedConnection(selectedConnection)
                val presetSpacing = preset.chatCompletionFor(connection)?.spacing
                val loreCounter = withContext(Dispatchers.Default) { host.chatTokenizers.counterFor(connection) }
                if (addUser) {
                    withContext(NonCancellable) { host.persist(chat) }
                    if (!preserveDraftAttachments) host.clearDraftAttachments(chat.id)
                    host.eventBus.emit(TavernCoreEvent.MESSAGE_SENT, jsonOf("chat_id" to chat.id, "message_id" to chat.messages.lastIndex))
                    host.media.narrate(chat.messages.last(), automatic = true)
                    host.script.quickEvent(QuickEvent.USER)
                }
                var ongoing = host.current.chat?.takeIf { it.id == chat.id } ?: chat
                for ((index, card) in speakers.withIndex()) {
                    val turn = if (index == 0) generation else generation.copy(groupQueue = generation.groupQueue.drop(index))
                    if (index > 0 && turn.groupQueue.isNotEmpty()) host.mutable.update { state ->
                        state.copy(generation = state.generation?.copy(groupQueue = turn.groupQueue))
                    }
                    if (group != null) host.script.quickEvent(QuickEvent.GROUP)
                    host.script.quickEvent(QuickEvent.BEFORE)
                    ongoing = host.current.chat?.takeIf { it.id == chat.id } ?: ongoing
                    var passAction = action
                    var replace = replacedMessages.takeIf { index == 0 } ?: 0
                    var followups = 0
                    var toolAttempted = false
                    while (true) {
                        val replacement = rerollPrefix.takeIf { index == 0 && followups == 0 }
                        val continueSpacing = if (replacement != null) ContinueSpacing.NONE else presetSpacing
                        val continued = ongoing.messages.lastOrNull()?.takeIf { passAction == ReplyAction.CONTINUE }?.let { last ->
                            if (replacement == null) last else last.withText(replacement).copy(
                                reasoning = null, images = emptyList(), documents = emptyList(), drawing = null, replyMedia = null,
                            )
                        }
                        val context = ongoing.messages.dropLast(replace)
                        val removed = ongoing.messages.takeLast(replace)
                        val speaker = if (action == ReplyAction.IMPERSONATE) persona.name else card.name
                        host.mutable.update {
                            it.copy(generation = turn.copy(
                                text = continued?.text.orEmpty(),
                                speaker = speaker,
                                replacedMessages = replace,
                                characterId = card.id,
                                action = passAction,
                                hiddenReplyPrefix = continued?.hiddenReplyPrefix,
                            ))
                        }
                        val buffer = StringBuilder()
                        val reasoningBuffer = StringBuilder()
                        val scene = group?.let {
                            GroupPrompt(it, it.promptMembers(cards, card), it.members(cards).map(Card::name), it.participants(cards).map(Card::name))
                        }
                        val promptHistory = if (continued == null) context else context + continued
                        val activePrefs = prefs.copy(scriptVariables = host.current.prefs.scriptVariables)
                        val vectors = host.world.promptVectors(activePrefs, ongoing, card, persona, books, promptHistory)
                        val sharedMemory = if (action == ReplyAction.IMPERSONATE) "" else
                            host.world.promptSharedWorldMemory(ongoing, card, promptHistory, books, groups, cards)
                        var toolSucceeded = false
                        fun resetReply() {
                            buffer.clear()
                            reasoningBuffer.clear()
                            host.mutable.update { state ->
                                val running = state.generation
                                if (running?.id == generation.id) state.copy(generation = running.copy(
                                    text = continued?.text.orEmpty(),
                                    reasoning = "",
                                    hiddenReplyPrefix = continued?.hiddenReplyPrefix,
                                )) else state
                            }
                        }
                        val tools = if (followups == 0 && activePrefs.toolCalling &&
                            passAction != ReplyAction.IMPERSONATE && passAction != ReplyAction.CONTINUE
                        ) {
                            ReplyTools(
                                reload = { host.script.activeTools(host.current.prefs, host.current.chat ?: ongoing, card) },
                                progress = { label -> host.mutable.update { it.copy(toolProgress = label) } },
                                finishStealth = { invoked ->
                                    toolAttempted = true
                                    toolSucceeded = true
                                    invoked.firstOrNull { it.failed }?.let { host.notice(it.result) }
                                },
                            ) { turn ->
                                toolAttempted = true
                                recordToolTurn(ongoing, turn, card)
                                toolSucceeded = toolSucceeded || turn.invocations.any { !it.failed }
                                resetReply()
                            }
                        } else null
                        var loreBudget: LoreBudgetUsage? = null
                        var contextBudget: PromptBudgetUsage? = null
                        val started = System.currentTimeMillis()
                        val startedNano = System.nanoTime()
                        var firstText: Long? = null
                        val promptOptions = host.script.resolvePromptInjections(ongoing.options)
                        val result = ModelService.reply(
                            activePrefs, connection, card, promptHistory, books, scene,
                            persona, promptOptions, passAction.trigger, preset,
                            forcedContinuation = replacement,
                            onPrompt = { messages ->
                                host.mutable.update { it.copy(lastPrompt = PromptCapture(chat.id, connection.model, messages,
                                    loreBudget, contextBudget, connection.provider.id)) }
                            },
                            onReasoning = { fragment ->
                                reasoningBuffer.append(fragment)
                                val text = reasoningBuffer.toString()
                                host.mutable.update { state ->
                                    val running = state.generation
                                    if (running?.id == generation.id) state.copy(generation = running.copy(reasoning = text)) else state
                                }
                            },
                            onLoreActivated = host.script::quickWorldEvent,
                            readImage = host.imageStore::bytes,
                            readDocument = host.documentStore::text,
                            vectors = vectors,
                            tools = tools,
                            onRetry = ::resetReply,
                            onQueueProgress = { label ->
                                host.mutable.update { state ->
                                    if (state.generation?.id == generation.id) {
                                        state.copy(toolProgress = label.takeIf(String::isNotEmpty))
                                    } else state
                                }
                            },
                            sharedMemory = sharedMemory,
                            loreCounter = loreCounter,
                            onContextBudget = { contextBudget = it },
                            onLoreBudget = {
                                loreBudget = it
                                if (it.blocked && activePrefs.loreSettings.overflowAlert) {
                                    host.notice("世界书额度已达 ${it.limit} Token，部分条目未注入")
                                }
                            },
                            chatId = ongoing.id,
                            onVariables = { host.persistMacroVariables(ongoing.id, it) },
                            effectiveSampling = activePrefs.effectiveConfig(ongoing).sampling,
                            onHiddenPrefix = { prefix ->
                                host.mutable.update { state ->
                                    val running = state.generation
                                    if (running?.id == generation.id) state.copy(generation = running.copy(hiddenReplyPrefix = prefix))
                                    else state
                                }
                            },
                        ) { fragment ->
                            if (firstText == null && fragment.isNotBlank()) firstText = (System.nanoTime() - startedNano) / 1_000_000L
                            buffer.append(fragment)
                            val text = if (continued == null) buffer.toString()
                                else joinContinuation(continued.text, buffer.toString(), continueSpacing)
                            host.mutable.update { state ->
                                val running = state.generation
                                if (running?.id == generation.id) state.copy(generation = running.copy(text = text)) else state
                            }
                        }
                        val grounded = result.googleGrounded
                        if (grounded != null) {
                            stopAuto()
                            val display = OpenGoogleReply(newId(), ongoing.id, context.lastOrNull()?.date,
                                speaker, grounded, result.incomplete)
                            host.mutable.update { state ->
                                if (state.chat?.id == display.chatId && state.generation?.id == generation.id) {
                                    state.copy(googleReplies = (state.googleReplies + display).takeLast(12),
                                        generation = state.generation.copy(replacedMessages = 0))
                                } else state
                            }
                            receivedReply = receivedReply || result.incomplete == null
                            break
                        }
                        val finished = System.currentTimeMillis()
                        val elapsed = (System.nanoTime() - startedNano) / 1_000_000L
                        val keepCompleteSentences = result.incomplete == null && connection.provider.usesTextCompletion &&
                            connection.resolvedTextTemplates().context?.trimSentences == true
                        val response = buffer.toString().trimEnd().let {
                            val body = if (prefs.chatHandling.collapseNewlines) {
                                collapseChatNewlines(it, result.hiddenReplyPrefix)
                            } else it
                            if (keepCompleteSentences) trimToCompleteSentence(body) else body
                        }
                        val stored = withContext(Dispatchers.IO) {
                            result.images.mapIndexedNotNull { imageIndex, image ->
                                try {
                                    image.key to host.imageStore.importReply(image, "模型回复-${generation.id.take(6)}-${imageIndex + 1}")
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    null
                                }
                            }.toMap()
                        }
                        val renderedImages = stored.values.distinctBy(ChatImage::id)
                        val replayParts = result.geminiReplayParts.mapNotNull { part ->
                            if (part.imageKey == null) part else stored[part.imageKey]?.let { part.copy(imageKey = it.id) }
                        }
                        val replay = if (replayParts.isEmpty() || replayParts.size != result.geminiReplayParts.size) null
                            else GeminiReplyReplay(connection.provider.id, connection.model.removePrefix("models/"), replayParts)
                        val replyMedia = if (renderedImages.isEmpty() && replay == null) null else StoredReplyMedia(renderedImages, replay)
                        val failedImages = result.imageWarning || stored.size != result.images.size
                        if (response.isBlank() && renderedImages.isEmpty() && toolSucceeded) {
                            ongoing = host.current.chat?.takeIf { it.id == chat.id } ?: ongoing
                            break
                        }
                        if (response.isBlank() && renderedImages.isEmpty() && result.stoppedBySequence && !result.hasReasoning) {
                            host.notice("回复开头匹配了停止序列")
                            return@launch
                        }
                        if (response.isBlank() && result.hasReasoning && action == ReplyAction.IMPERSONATE) {
                            host.notice("模型返回了推理，尚未生成草稿")
                            return@launch
                        }
                        check(response.isNotBlank() || result.hasReasoning || renderedImages.isNotEmpty()) {
                            if (failedImages) "模型返回的图片无法保存" else "模型没有返回可显示的回复"
                        }
                        val rules = prefs.regex.runtime(card, persona.name, preset, scene?.roster.orEmpty())
                        val site = if (action == ReplyAction.IMPERSONATE) RegexSite.INPUT else RegexSite.REPLY
                        val (content, reasoning) = withContext(Dispatchers.Default) {
                            val joined = joinContinuation(continued?.text.orEmpty(),
                                if (continued == null) response.trimStart() else response, continueSpacing) {
                                rules.transform(it, site, RegexStage.STORAGE)
                            }
                            val body = if (prefs.chatHandling.repairMarkdown) {
                                val prefix = result.hiddenReplyPrefix.trimStart().takeIf { it.isNotEmpty() && joined.startsWith(it) }
                                    ?: continued?.hiddenReplyPrefix?.takeIf { joined.startsWith(it) }.orEmpty()
                                prefix + repairReplyMarkdown(joined.removePrefix(prefix))
                            } else joined
                            val details = rules.transform(reasoningBuffer.toString().trim(), RegexSite.REASONING, RegexStage.STORAGE)
                            body to details.takeIf(String::isNotBlank)
                        }
                        if (action == ReplyAction.IMPERSONATE) {
                            host.mutable.update { it.copy(suggestedDraft = DraftSuggestion(chat.id, content)) }
                            if (result.incomplete != null) {
                                host.notice("${result.incomplete}，已保留收到的草稿")
                                return@launch
                            }
                            break
                        }
                        val old = ongoing.messages.lastOrNull()?.takeIf { replace == 1 }
                        val measured = ReplyMetrics(
                            started = started, finished = finished, elapsed = elapsed, firstText = firstText,
                            provider = connection.provider.name, model = connection.model, api = connection.provider.protocol.wire,
                            bodyTokens = withContext(Dispatchers.Default) { loreCounter.count(content).toLong() },
                            counter = loreCounter.label, usage = result.usage,
                        )
                        val hiddenPrefix = result.hiddenReplyPrefix.trimStart().takeIf { it.isNotEmpty() && content.startsWith(it) }
                            ?: continued?.hiddenReplyPrefix?.takeIf { content.startsWith(it) }
                        val tokens = result.replyTokens?.stored(content, continued?.text.orEmpty())
                        val webReply = if (continued == null) result.webReply else {
                            val sources = combineReplySources(continued.webReply?.sources.orEmpty() + result.webReply?.sources.orEmpty())
                            StoredWebReply(sources).takeIf { sources.isNotEmpty() }
                        }
                        val reply = when {
                            replacement != null && old != null -> old.withAlternative(content, reasoning, measured,
                                hiddenPrefix, replyMedia, webReply, tokens)
                            continued != null -> continued.withText(content).withReasoning(
                                listOfNotNull(continued.reasoning, reasoning).joinToString("\n").takeIf(String::isNotBlank),
                            ).copy(replyMetrics = continued.replyMetrics?.continued(measured) ?: measured, webReply = webReply,
                                replyTokens = tokens,
                                replyMedia = (continued.replyMedia?.images.orEmpty() + renderedImages)
                                    .distinctBy(ChatImage::id).take(MAX_CHAT_IMAGES).takeIf(List<ChatImage>::isNotEmpty)
                                    ?.let { StoredReplyMedia(it) })
                            old != null && (group == null || passAction == ReplyAction.SWIPE) ->
                                old.withAlternative(content, reasoning, measured, hiddenPrefix, replyMedia, webReply, tokens)
                            else -> Message(
                                "assistant", card.name, content,
                                raw = if (chat.groupId == null && chat.cardId != null && chat.cardId != card.id) jsonOf(
                                    "original_avatar" to card.scriptAvatarKey, "force_avatar" to card.scriptAvatarKey,
                                ).toString() else null,
                                groupTurnId = generation.id.takeIf { group != null },
                                reasoning = reasoning,
                                replyMetrics = measured,
                                hiddenReplyPrefix = hiddenPrefix,
                                replyMedia = replyMedia,
                                webReply = webReply,
                                replyTokens = tokens,
                            )
                        }
                        val effects = result.loreEffects.takeIf {
                            it.isNotEmpty() || promptHistory.asReversed().firstNotNullOfOrNull(Message::loreEffects)?.isNotEmpty() == true
                        }
                        val latest = host.current.chat?.takeIf { it.id == ongoing.id } ?: ongoing
                        val retained = if (latest === ongoing) context else latest.messages.filterNot { message ->
                            removed.any { it === message }
                        }
                        val savedReply = reply.copy(characterId = card.id, loreEffects = effects)
                        val cleanedOptions = app.tavern.nativeclient.script.ScriptPromptInjections.withoutEphemeral(latest.options)
                        ongoing = latest.copy(
                            messages = retained + savedReply,
                            updated = System.currentTimeMillis(),
                            options = cleanedOptions,
                        )
                        withContext(NonCancellable) {
                            host.persist(ongoing, replyGallery = renderedImages.map { GalleryImage(it, content.take(1500)) })
                            onSavedReply(savedReply)
                            ongoing = host.current.chat?.takeIf { it.id == chat.id } ?: ongoing
                            host.mutable.update {
                                it.copy(generation = turn.copy(
                                    text = "", speaker = card.name,
                                    replacedMessages = 0, characterId = card.id, action = passAction,
                                ))
                            }
                        }
                        if (response.isBlank() && renderedImages.isEmpty()) {
                            host.notice(result.incomplete?.let { "$it，已保留收到的推理" } ?: "模型未生成正文，已保留收到的推理")
                            return@launch
                        }
                        val automation = prefs.replyAutomation
                        val next = if (renderedImages.isEmpty() && (automation.autoContinue || automation.autoSwipe) &&
                            followups < automation.extraRequests &&
                            host.draftInProgress != chat.id && result.continuable
                        ) {
                            withContext(Dispatchers.Default) {
                                when {
                                    passAction != ReplyAction.CONTINUE && !toolAttempted && result.incomplete == null &&
                                        automation.needsSwipe(content) -> ReplyAction.SWIPE
                                    content != continued?.text &&
                                        automation.needsContinuation(content, response, loreCounter) -> ReplyAction.CONTINUE
                                    else -> null
                                }
                            }
                        } else null
                        if (next != null && host.draftInProgress != chat.id && host.current.chat?.id == chat.id) {
                            passAction = next
                            replace = 1
                            followups++
                            continue
                        }
                        if (result.incomplete != null) {
                            host.notice(if (renderedImages.isEmpty()) {
                                "${result.incomplete}，已保留收到的正文。可使用「续写最后一条回复」"
                            } else "${result.incomplete}，已保留收到的图片及正文")
                            return@launch
                        }
                        if (failedImages) host.notice("部分图片无法保存，已保留其余回复")
                        val translation = ongoing.options.translation
                        if (translation.mode.replies && reply.displayText.isNotBlank()) {
                            val translated = host.chats.automaticTranslation(activePrefs, ongoing, reply.displayText, translation.target)
                            if (translated != null) {
                                val saved = host.current.chat ?: ongoing
                                val messages = saved.messages.dropLast(1) + saved.messages.last().copy(translation = translated)
                                host.persist(saved.copy(messages = messages))
                            }
                        }
                        ongoing = host.current.chat ?: ongoing
                        receivedReply = true
                        host.eventBus.emit(TavernCoreEvent.MESSAGE_RECEIVED, jsonOf("chat_id" to chat.id, "message_id" to ongoing.messages.lastIndex))
                        host.audioEngine.evaluateTriggers(
                            config = host.current.prefs.extensions.dynamicAudio,
                            backgroundName = host.current.prefs.backgrounds.effective(ongoing)?.filename,
                            messageText = ongoing.messages.lastOrNull()?.text,
                        )
                        host.media.narrate(ongoing.messages.last(), automatic = true)
                        host.script.quickEvent(QuickEvent.AI)
                        break
                    }
                }
                if (generation.groupQueue.isNotEmpty()) host.mutable.update { state ->
                    state.copy(generation = state.generation?.copy(groupQueue = emptyList()))
                }
                if (action != ReplyAction.IMPERSONATE) host.current.chat?.let { host.chats.summarizeAutomatically(it) }
                completed = true
                if (receivedReply && action != ReplyAction.IMPERSONATE) host.media.playReplySound()
            } catch (e: CancellationException) {
                if (host.current.generation?.chatId == chat.id) host.notice("已停止生成")
                throw e
            } catch (e: Exception) {
                host.notice(e.localizedMessage ?: "生成失败，请重试")
            } finally {
                host.mutable.update { it.copy(generation = null) }
                GenerationForegroundService.stop(host.app)
                host.eventBus.emit(TavernCoreEvent.GENERATION_ENDED, jsonOf("chat_id" to chat.id, "completed" to completed))
                if (completed && host.current.autoChatId == chat.id) scheduleAuto()
                else if (!completed) stopAuto()
            }
        }
        return generator
    }

    private suspend fun recordToolTurn(chat: Chat, turn: ToolTurn, speaker: Card) {
        val latest = host.current.chat?.takeIf { it.id == chat.id } ?: chat
        val text = turn.invocations.joinToString(" · ") {
            if (it.failed) "${it.label}（失败）" else it.label
        }
        val record = Message(
            "system", "工具调用", text, characterId = speaker.id, toolTurn = turn,
            groupTurnId = host.current.generation?.id.takeIf { latest.groupId != null },
        )
        withContext(NonCancellable) {
            host.persist(latest.copy(messages = latest.messages + record, updated = System.currentTimeMillis()))
        }
    }
}
