package app.tavern.nativeclient.chat

import android.net.Uri
import app.tavern.nativeclient.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

internal class ChatManagementCoordinator(private val host: CoordinatorHost) {
    fun attachChatDocuments(chatId: String, messageIndex: Int?, uris: List<Uri>) {
        val chat = host.current.chat?.takeIf { it.id == chatId } ?: return
        if (uris.isEmpty()) return
        val target = messageIndex?.let { chat.messages.getOrNull(it) ?: return }
        val existing = target?.documents ?: host.current.draftDocuments[chatId].orEmpty()
        host.utilityWork("正在读取聊天文档…") {
            val capacity = (MAX_CHAT_DOCUMENTS - existing.size).coerceAtLeast(0)
            var lastFailure: String? = null
            val documents = withContext(Dispatchers.IO) {
                uris.take(capacity).mapNotNull { uri ->
                    currentCoroutineContext().ensureActive()
                    val name = host.app.documentName(uri)
                    try {
                        val bytes = requireNotNull(host.resolver.openInputStream(uri)) { "无法打开文件" }
                            .use { it.readLimited(MAX_DOCUMENT_INPUT_BYTES) }
                        host.documentStore.import(openDataDocument(name, host.resolver.getType(uri), bytes, host.app.cacheDir))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        lastFailure = "无法添加 $name · ${e.localizedMessage.orEmpty()}"
                        null
                    }
                }
            }
            if (documents.isNotEmpty()) {
                val combined = (existing + documents).distinctBy(ChatDocument::id)
                if (target == null) {
                    host.mutable.update { it.copy(draftDocuments = it.draftDocuments + (chatId to combined)) }
                } else {
                    val messages = chat.messages.toMutableList()
                    messages[requireNotNull(messageIndex)] = target.copy(documents = combined)
                    host.persist(chat.copy(messages = messages, updated = System.currentTimeMillis()))
                }
            }
            lastFailure?.let(host::notice)
            if (lastFailure == null && uris.size > capacity) host.notice("每条消息最多附带 $MAX_CHAT_DOCUMENTS 份文档")
        }
    }

    fun removeDraftDocument(chatId: String, document: ChatDocument) {
        if (host.current.busy || host.current.generation != null || host.current.scriptRunning) return
        host.mutable.update { state ->
            val keep = state.draftDocuments[chatId].orEmpty().filterNot { it.id == document.id }
            state.copy(draftDocuments = if (keep.isEmpty()) state.draftDocuments - chatId else state.draftDocuments + (chatId to keep))
        }
    }

    fun removeMessageDocument(chatId: String, index: Int, document: ChatDocument) = host.work {
        val chat = host.current.chat?.takeIf { it.id == chatId } ?: return@work
        val target = chat.messages.getOrNull(index) ?: return@work
        val messages = chat.messages.toMutableList()
        messages[index] = target.copy(documents = target.documents.filterNot { it.id == document.id })
        host.persist(chat.copy(messages = messages, updated = System.currentTimeMillis()))
    }

    suspend fun readChatDocument(document: ChatDocument, limit: Int): String? = withContext(Dispatchers.IO) {
        host.documentStore.text(document)?.take(limit)
    }

    fun exportChatDocument(document: ChatDocument, uri: Uri) = host.work {
        withContext(Dispatchers.IO) {
            host.writeUri(uri, requireNotNull(host.documentStore.bytes(document)) { "找不到附件文本" })
        }
        host.notice("文档已导出")
    }

    fun closeChat() {
        if (host.current.chat?.isTemporary == true) return host.library.closeSubject()
        host.generation.stopAuto()
        host.mutable.update { it.copy(chat = null, temporaryPersonaId = null) }
    }

    fun newChat(card: Card, greeting: String) = host.work { startChat(card, greeting) }

    fun startWelcomeChat(message: String, opened: () -> Unit) {
        val input = message.trim()
        host.work(completed = {
            if (input.isNotBlank() && !host.generation.send(input, interpretCommands = false)) {
                host.current.chat?.let { chat -> host.mutable.update { it.copy(suggestedDraft = DraftSuggestion(chat.id, input)) } }
            }
            opened()
        }) {
            if (input.isNotBlank()) require(host.current.prefs.connectionFor() != null) { "请先配置模型提供商" }
            val assistant = host.current.welcomeAssistant
            if (host.current.cards.none { it.id == assistant.id }) {
                val cards = withContext(Dispatchers.IO) { host.store.save(assistant); host.store.cards() }
                host.mutable.update { it.copy(cards = cards) }
            }
            startChat(assistant, assistant.greetings.firstOrNull().orEmpty())
        }
    }

    private suspend fun startChat(card: Card, greeting: String) {
        val prefs = host.current.prefs
        val persona = prefs.personaFor(subjectId = card.id)
        val chat = Chat(newId(), card.id, "", options = ConversationOptions(authorNote = host.current.prefs.defaultAuthorNote))
        val preset = host.current.copy(cardId = card.id, groupId = null).presetFor(chat)
        val rules = prefs.regex.runtime(card, persona.name, preset)
        val (messages, variables) = if (greeting.isBlank()) emptyList<Message>() to MacroVariableChanges()
        else withContext(Dispatchers.Default) {
            val macros = ConversationMacros(
                card, persona, options = chat.options, globals = prefs.scriptVariables,
                model = prefs.connectionFor(chat)?.model.orEmpty(), chatId = chat.id,
            )
            val text = rules.transform(macros.expand(greeting, write = true), RegexSite.REPLY, RegexStage.STORAGE)
            val message = text.takeIf(String::isNotBlank)?.let { Message("assistant", card.name, it, characterId = card.id) }
            listOfNotNull(message) to macros.variables.changes()
        }
        val history = if (host.current.cardId == card.id) host.current.history else withContext(Dispatchers.IO) { host.store.chats(card.id) }
        val title = if (history.isEmpty()) "新对话" else "新对话 · ${history.size + 1}"
        val locals = chat.options.variables.withVariableChanges(variables.local)
        val globals = variables.global.takeIf { it.isNotEmpty() }?.let { host.current.prefs.scriptVariables.withVariableChanges(it) }
        withContext(NonCancellable) {
            host.persist(chat.copy(title = title, messages = messages, options = chat.options.copy(variables = locals)), scriptGlobals = globals)
        }
        messages.forEach { host.media.narrate(it, automatic = true) }
        host.script.quickEvent(QuickEvent.NEW)
        host.script.quickEvent(QuickEvent.CHAT)
    }

    fun openChat(id: String) = host.work {
        host.generation.stopAuto()
        val (chat, history) = withContext(Dispatchers.IO) {
            val opened = host.store.chat(id)
            host.store.rememberLastChat(id)
            opened to host.store.chats(opened.cardId, opened.groupId)
        }
        host.showChat(chat, history, resetPersona = true)
        host.script.quickEvent(QuickEvent.CHAT)
    }

    fun openTemporaryChat(uri: Uri? = null, title: String = "临时聊天") = host.work {
        host.generation.stopAuto()
        val media = withContext(Dispatchers.IO) { TemporaryChatMedia(host.app.cacheDir) }
        val chat = try {
            if (uri == null) Chat(newId(), temporaryAssistant.id, title)
            else withContext(Dispatchers.IO) {
                readChatFile(uri, title, temporaryAssistant, null, emptyList(), media.documents, media.images)
            }
        } catch (e: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { media.close() }
            throw e
        }
        host.discardTemporaryChat()
        host.temporaryMedia = media
        host.showChat(chat, emptyList(), resetPersona = true)
        if (uri != null) host.notice("已载入临时聊天")
    }

    fun renameChat(chat: Chat, name: String) = host.work {
        require(name.isNotBlank()) { "请输入聊天名称" }
        host.persist(chat.copy(title = name.trim()))
    }

    fun forkChat(position: Int, checkpoint: Boolean, requestedName: String = "") = host.work {
        val chat = host.current.chat ?: return@work
        require(!chat.isTemporary) { "请先将临时聊天导入角色或群组" }
        host.generation.stopAuto()
        val title = requestedName.trim().ifBlank { chat.nextForkTitle(host.current.history, checkpoint) }
        require(host.current.history.none { it.title == title }) { "聊天名称已存在" }
        val (source, fork) = chat.fork(position, title, checkpoint)
        val history = withContext(Dispatchers.IO) {
            host.store.saveFork(source, fork)
            if (!checkpoint) host.store.rememberLastChat(fork.id)
            host.store.chats(source.cardId, source.groupId)
        }
        host.mutable.update {
            it.copy(
                chat = if (checkpoint) source else fork,
                history = history,
                temporaryPersonaId = it.temporaryPersonaId.takeIf { checkpoint },
            )
        }
        if (checkpoint) host.notice("检查点已保存")
    }

    fun convertToGroup(done: () -> Unit) = host.work {
        val original = host.current.chat ?: return@work
        require(!original.isTemporary) { "请先将临时聊天导入角色或群组" }
        val card = host.current.card ?: return@work
        host.generation.stopAuto()
        val used = host.current.groups.mapTo(mutableSetOf(), TavernGroup::name)
        val base = "${card.name} 群聊"
        val name = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base · $it" }.first { it !in used }
        val group = TavernGroup(name = name, memberIds = listOf(card.id))
        val chat = original.withOwner(null, group, listOf(card), sourceCard = card, asCopy = true)
        val (groups, history) = withContext(Dispatchers.IO) {
            check(host.store.import(group, listOf(chat)) == 1) { "无法复制群聊" }
            host.store.rememberLastChat(chat.id)
            host.store.groups() to host.store.chats(groupId = group.id)
        }
        host.mutable.update {
            it.copy(
                groups = groups,
                history = history,
                cardId = null,
                groupId = group.id,
                chat = chat,
                temporaryPersonaId = null,
            )
        }
        done()
        host.notice("已创建群聊副本")
    }

    fun copyChatTo(ownerId: String, toGroup: Boolean, done: () -> Unit) = host.work {
        val original = host.current.chat ?: return@work
        require(!original.isTemporary) { "请先将临时聊天导入角色或群组" }
        val group = if (toGroup) requireNotNull(host.current.groups.firstOrNull { it.id == ownerId }) { "找不到目标群组" } else null
        val card = if (toGroup) null else requireNotNull(host.current.cards.firstOrNull { it.id == ownerId }) { "找不到目标角色" }
        copyHistory(original, card, group)
        done()
        host.notice("已复制到「${card?.name ?: group?.name}」")
    }

    fun loadChatImportHistory(ownerId: String, fromGroup: Boolean, loaded: (List<ChatSummary>) -> Unit) = host.work {
        val history = withContext(Dispatchers.IO) {
            if (fromGroup) host.store.chats(groupId = ownerId) else host.store.chats(cardId = ownerId)
        }
        loaded(history)
    }

    fun importLocalChat(sourceOwnerId: String, chatId: String, done: () -> Unit) = host.work {
        val (card, group) = host.current.card to host.current.group
        require(card != null || group != null) { "请先选择目标角色或群组" }
        val original = withContext(Dispatchers.IO) { host.store.chat(chatId) }
        require(if (group != null) original.cardId == sourceOwnerId else original.groupId == sourceOwnerId) {
            "找不到所选的聊天记录"
        }
        copyHistory(original, card, group)
        done()
        host.notice("聊天已导入")
    }

    private suspend fun copyHistory(original: Chat, card: Card?, group: TavernGroup?) {
        val cards = host.current.cards
        val sourceCard = cards.firstOrNull { it.id == original.cardId }
        host.generation.stopAuto()
        val (chat, history) = withContext(Dispatchers.IO) {
            val copy = original.withOwner(
                card, group, group?.members(cards).orEmpty(), sourceCard,
                asCopy = true, knownCards = cards,
            )
            val titled = copy.copy(title = copy.availableTitle(host.store.chats(copy.cardId, copy.groupId)))
            host.store.save(titled)
            host.store.rememberLastChat(titled.id)
            titled to host.store.chats(titled.cardId, titled.groupId)
        }
        host.showChat(chat, history, resetPersona = true)
    }

    fun removeChat(chat: ChatSummary) = host.work {
        val (cardId, groupId) = host.current.cardId to host.current.groupId
        val history = withContext(Dispatchers.IO) { host.store.deleteChat(chat.id); host.store.chats(cardId, groupId) }
        host.mutable.update { it.copy(history = history, chat = it.chat?.takeUnless { open -> open.id == chat.id }) }
    }

    fun importChat(uri: Uri, title: String) = host.work {
        val (card, group) = host.current.card to host.current.group
        require(card != null || group != null) { "请先选择目标角色或群组" }
        require(host.current.chat?.isTemporary != true) { "请使用载入临时聊天" }
        val cards = host.current.cards
        host.generation.stopAuto()
        val chat = withContext(Dispatchers.IO) { readChatFile(uri, title, card, group, cards, host.savedDocuments, host.savedImages) }
        host.persist(chat)
        host.notice("聊天已导入")
    }

    private suspend fun readChatFile(
        uri: Uri,
        title: String,
        card: Card?,
        group: TavernGroup?,
        cards: List<Card>,
        documents: ChatDocumentStore,
        images: ChatImageStore,
    ): Chat {
        val members = group?.members(cards).orEmpty()
        fun parse(bytes: ByteArray): Chat {
            val root = bytes.toString(Charsets.UTF_8).trimStart('\uFEFF')
            val rows = root.lineSequence().withIndex().filter { it.value.isNotBlank() }.map { (index, line) ->
                boundedJsonObject(line, "无法读取聊天文件第 ${index + 1} 行")
            }.toList()
            val chatTitle = title.replace(Regex("(?i)\\.(jsonl|zip)$"), "").ifBlank { "导入的聊天" }
            return Chat.import(null, chatTitle, rows).withOwner(card, group, members, knownCards = cards)
        }
        return requireNotNull(host.resolver.openInputStream(uri)) { "无法打开聊天文件" }.buffered().use { input ->
            input.mark(4)
            val header = ByteArray(4)
            for (index in header.indices) {
                val next = input.read()
                if (next < 0) break
                header[index] = next.toByte()
            }
            val archive = ChatMediaArchive.isArchive(header)
            input.reset()
            if (archive) ChatMediaArchive.read(input, ::parse, documents::restore, images::restore)
            else host.backup.hydrateOriginalDocuments(parse(input.readLimited()), documents).first
        }
    }

    fun exportChat(uri: Uri, chat: Chat, subject: String, withAttachments: Boolean = false) = host.work {
        val name = host.current.persona.name
        val book = chat.options.worldBook(host.current.books)
        val history = host.current.history
        val quickNames = chat.options.quickReplyNames + chat.options.quickReplySets.mapNotNull { id ->
            host.current.prefs.quickReplies.sets.firstOrNull { it.id == id }?.name
        }
        val portable = chat.copy(
            messages = chat.messages.map { it.portable(history) },
            options = chat.options.copy(
                worldBookId = "",
                parent = chat.options.parent?.portable(history),
                quickReplySets = emptyList(),
                quickReplyNames = quickNames.distinct(),
                worldBookName = book?.name ?: chat.options.worldBookName.takeIf { chat.options.worldBookId.isEmpty() }.orEmpty(),
            ),
        )
        withContext(Dispatchers.IO) {
            val transcript = portable.jsonl(subject, name)
            if (withAttachments) requireNotNull(host.resolver.openOutputStream(uri, "wt")) { "无法写入所选文件" }.use { output ->
                ChatMediaArchive.write(output, transcript, portable, host.documentStore::bytes, host.imageStore::bytes)
            } else host.writeUri(uri, transcript)
        }
        host.notice("聊天已导出")
    }

    fun exportChatText(uri: Uri, chat: Chat) = host.work {
        withContext(Dispatchers.IO) { host.writeUri(uri, chat.plainText()) }
        host.notice("聊天文本已导出")
    }

    fun editMessage(chat: Chat, index: Int, text: String) = host.work {
        require(text.isNotBlank()) { "消息不能为空" }
        host.generation.stopAuto()
        val updated = chat.messages.toMutableList()
        val message = updated[index]
        val rules = host.current.textRules(chat, host.current.regexCard(message))
        val replacement = withContext(Dispatchers.Default) {
            message.regexSite?.let { rules.transform(text, it, RegexStage.STORAGE, editing = true) } ?: text
        }
        updated[index] = message.withText(replacement)
        host.persist(chat.copy(messages = updated, updated = System.currentTimeMillis()))
    }

    fun editReasoning(chat: Chat, index: Int, text: String) = host.work {
        host.generation.stopAuto()
        val updated = chat.messages.toMutableList()
        val message = updated[index]
        val rules = host.current.textRules(chat, host.current.regexCard(message))
        val value = withContext(Dispatchers.Default) {
            rules.transform(text, RegexSite.REASONING, RegexStage.STORAGE, editing = true)
        }
        updated[index] = message.withReasoning(value.takeIf(String::isNotBlank))
        host.persist(chat.copy(messages = updated, updated = System.currentTimeMillis()))
    }

    fun deleteMessages(chat: Chat, indices: Set<Int>, includeTools: Boolean = true) = host.work {
        host.generation.stopAuto()
        removeMessages(chat, indices, includeTools)
    }

    internal suspend fun removeMessages(chat: Chat, indices: Set<Int>, includeTools: Boolean) {
        val removed = chat.messages.deletionIndices(indices, includeTools)
        if (removed.isNotEmpty()) host.persist(chat.copy(
            messages = chat.messages.filterIndexed { index, _ -> index !in removed },
            updated = System.currentTimeMillis(),
        ))
    }

    fun duplicateMessage(chat: Chat, index: Int) = host.work {
        host.generation.stopAuto()
        val original = chat.messages[index]
        val copy = original.copy(checkpoint = null, branches = emptyList())
        host.persist(chat.copy(
            messages = chat.messages.toMutableList().apply { add(index + 1, copy) },
            updated = System.currentTimeMillis(),
        ))
    }

    fun moveMessage(chat: Chat, index: Int, direction: Int) = host.work {
        host.generation.stopAuto()
        val destination = index + direction
        require(destination in chat.messages.indices) { "消息已在对话边界" }
        val moved = chat.messages.toMutableList().apply { add(destination, removeAt(index)) }
        host.persist(chat.copy(messages = moved, updated = System.currentTimeMillis()))
    }

    fun swipe(chat: Chat, messageIndex: Int, option: Int) = host.work {
        val active = host.current.chat?.takeIf { it.id == chat.id } ?: return@work
        val message = active.messages.getOrNull(messageIndex) ?: return@work
        if (option !in message.variants.indices || option == message.index) return@work
        host.generation.stopAuto()
        updateMessage(active, messageIndex, message.select(option))
    }

    fun removeReplyAlternative(chatId: String, messageIndex: Int, option: Int) = host.work {
        val active = host.current.chat?.takeIf { it.id == chatId } ?: return@work
        val message = active.messages.getOrNull(messageIndex) ?: return@work
        if (message.role != "assistant" || message.variants.size < 2 || option !in message.variants.indices) return@work
        host.generation.stopAuto()
        updateMessage(active, messageIndex, message.withoutAlternative(option))
    }

    internal suspend fun updateMessage(chat: Chat, index: Int, message: Message) {
        if (message == chat.messages[index]) return
        val updated = chat.messages.toMutableList().apply { this[index] = message }
        host.persist(chat.copy(messages = updated, updated = System.currentTimeMillis()))
    }

    internal suspend fun changeMessageContext(chat: Chat, indices: Set<Int>, hidden: Boolean, name: String = "") {
        val messages = chat.messages.mapIndexed { index, message ->
            if (index !in indices || name.isNotEmpty() && name != message.name) message
            else if (!hidden && message.role == "system" && message.toolTurn == null) message.copy(role = "assistant", hidden = false)
            else message.copy(hidden = hidden)
        }
        if (messages != chat.messages) host.persist(chat.copy(messages = messages, updated = System.currentTimeMillis()))
    }

    fun toggleMessageContext(chat: Chat, index: Int) = host.work {
        val message = chat.messages.getOrNull(index) ?: return@work
        host.generation.stopAuto()
        changeMessageContext(chat, setOf(index), hidden = if (message.toolTurn == null) !message.excludedFromContext else !message.hidden)
    }

    fun dismissMessageSelection() = host.mutable.update { it.copy(messageSelection = null) }

    fun saveTranslation(options: TranslationOptions, done: () -> Unit) = host.work {
        val chat = host.current.chat ?: return@work
        require(validTranslationLanguage(options.target) && validTranslationLanguage(options.requestLanguage)) {
            "请填写有效的语言标签，例如 zh-CN 或 en"
        }
        val messages = if (options.target.equals(chat.options.translation.target, true)) chat.messages
        else chat.messages.map { it.copy(translation = null) }
        host.persist(chat.copy(messages = messages, options = chat.options.copy(translation = options)))
        done()
    }

    fun saveTranslationServices(settings: TranslationServices, done: () -> Unit) = host.work {
        val validated = settings.validated()
        if (settings.source == TranslationSource.MODEL && settings.model != null) {
            host.current.prefs.utilityModel(host.current.chat, settings.model, "翻译")
        }
        host.persistPreferences(host.current.prefs.copy(translationServices = validated))
        done()
    }

    fun testTranslationService(settings: TranslationServices) = host.utilityWork("正在测试翻译服务…") {
        val chat = host.current.chat
        val prefs = host.current.prefs.copy(translationServices = settings)
        val options = TranslationOptions()
        val result = host.translationService.translate(prefs, chat, options, "The train will arrive on time.", options.target)
        host.notice("翻译服务已返回译文 · $result")
    }

    fun translateDraft(text: String, done: (String) -> Unit) = host.utilityWork("正在翻译输入…") {
        val chat = host.current.chat ?: return@utilityWork
        val options = chat.options.translation
        done(host.translationService.translate(host.current.prefs, chat, options, text, options.target))
    }

    fun toggleTranslation(index: Int) = host.utilityWork("正在翻译消息…") {
        val chat = host.current.chat ?: return@utilityWork
        val message = chat.messages.getOrNull(index) ?: return@utilityWork
        val translated = when {
            message.translation != null -> null
            message.wireText != null -> message.wireText
            else -> host.translationService.translate(
                host.current.prefs, chat, chat.options.translation, message.text, chat.options.translation.target,
            )
        }
        val messages = chat.messages.toMutableList().apply { this[index] = message.copy(translation = translated) }
        host.persist(chat.copy(messages = messages))
    }

    fun translateChat(options: TranslationOptions) = host.utilityWork("正在翻译聊天…") {
        val chat = host.current.chat ?: return@utilityWork
        val prefs = host.current.prefs
        val sameLanguage = options.target.equals(chat.options.translation.target, true)
        val messages = chat.messages.map { if (sameLanguage) it else it.copy(translation = null) }.toMutableList()
        val indices = messages.indices.filter { index ->
            val message = messages[index]
            !message.excludedFromContext && message.text.isNotBlank() && message.translation == null
        }
        var completed = 0
        try {
            for (index in indices) {
                host.mutable.update { it.copy(utility = "正在翻译聊天 · ${completed + 1}/${indices.size}") }
                val message = messages[index]
                val text = host.translationService.translate(prefs, chat, options, message.text, options.target)
                messages[index] = message.copy(translation = text.takeIf { it != message.text })
                completed++
            }
        } finally {
            if (completed > 0 || indices.isEmpty()) withContext(NonCancellable) {
                host.persist(chat.copy(messages = messages, options = chat.options.copy(translation = options)))
            }
        }
    }

    fun clearTranslations() = host.work {
        val chat = host.current.chat ?: return@work
        host.persist(chat.copy(messages = chat.messages.map { it.copy(translation = null) }))
    }

    internal suspend fun scriptTranslate(text: String, target: String?): String {
        val chat = requireNotNull(host.current.chat) { "请先打开对话" }
        val options = chat.options.translation
        return host.translationService.translate(host.current.prefs, chat, options, text, target ?: options.target)
    }

    internal suspend fun automaticTranslation(prefs: Preferences, chat: Chat, text: String, language: String): String? {
        if (text.isBlank()) return null
        return try {
            host.translationService.translate(prefs, chat, chat.options.translation, text, language).takeUnless { it == text }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            host.notice("自动翻译失败，已保留原文 · ${e.localizedMessage.orEmpty()}")
            null
        }
    }

    fun saveMemory(options: MemoryOptions, text: String, done: () -> Unit) = host.work {
        val chat = host.current.chat ?: return@work
        val previous = chat.messages.currentSummary()
        val value = text.trim()
        val messages = when {
            value == previous?.summary?.text || (value.isBlank() && previous == null) -> chat.messages
            value.isEmpty() && previous != null -> chat.messages.withSummary(previous.index, "")
            else -> {
                require(chat.messages.isNotEmpty()) { "请先发送一条消息" }
                chat.messages.withSummary(chat.messages.lastIndex, value)
            }
        }
        host.persist(chat.copy(messages = messages, options = chat.options.copy(memory = options)))
        done()
    }

    fun restoreMemory() = host.work {
        val chat = host.current.chat ?: return@work
        val previous = chat.messages.currentSummary() ?: return@work
        host.persist(chat.copy(messages = chat.messages.withSummary(previous.index, "")))
    }

    private suspend fun refreshMemory(chat: Chat, force: Boolean): String? {
        val batch = withContext(Dispatchers.Default) { chat.summaryBatch(force) } ?: return null
        val options = chat.options.memory
        val result = ModelService.utility(
            host.current.prefs.utilityModel(chat, options.model, "摘要"),
            batch.messages,
            options.maxTokens,
        )
        val messages = withContext(Dispatchers.Default) { chat.messages.withSummary(batch.lastIndex, result) }
        host.persist(chat.copy(messages = messages))
        return result
    }

    fun summarizeChat(options: MemoryOptions) = host.utilityWork("正在摘要聊天…") {
        val chat = host.current.chat ?: return@utilityWork
        if (refreshMemory(chat.copy(options = chat.options.copy(memory = options)), true) == null) {
            host.notice("没有尚未摘要的新消息")
        }
    }

    internal suspend fun summarizeAutomatically(chat: Chat) {
        if (!chat.options.memory.enabled || chat.options.memory.paused || chat.options.memory.interval == 0) return
        try {
            refreshMemory(chat, false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            host.notice("摘要更新失败 · ${e.localizedMessage.orEmpty()}")
        }
    }

    internal suspend fun scriptSummarize(text: String, instruction: String?): String {
        val chat = requireNotNull(host.current.chat) { "请先打开对话" }
        val options = chat.options.memory
        if (text.isNotBlank()) return ModelService.utility(
            host.current.prefs.utilityModel(chat, options.model, "摘要"),
            utilityMessages(instruction ?: options.instruction, text),
            options.maxTokens,
        )
        return refreshMemory(chat, true) ?: chat.messages.currentSummary()?.summary?.text.orEmpty()
    }
}
