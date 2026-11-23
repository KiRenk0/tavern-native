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
    suspend fun conversationStatistics(chat: Chat?, cardId: String?, groupId: String?): ConversationStatistics =
        withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            val result = ConversationStatisticsCollector()
            if (chat != null) {
                result.add(chat) { context.ensureActive() }
                return@withContext result.snapshot()
            }
            var skipped = 0
            for (id in host.store.statisticsChatIds(cardId, groupId)) {
                context.ensureActive()
                val history = try {
                    host.store.chat(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: org.json.JSONException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                } catch (_: IllegalStateException) {
                    null
                }
                if (history == null) skipped++ else result.add(history) { context.ensureActive() }
            }
            result.snapshot(skipped)
        }

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

    internal suspend fun startChat(card: Card, greeting: String) {
        val prefs = host.current.prefs
        val persona = prefs.personaFor(subjectId = card.id)
        val chat = Chat(newId(), card.id, "", options = ConversationOptions(authorNote = host.current.prefs.defaultAuthorNote))
        val preset = host.current.copy(cardId = card.id, groupId = null).presetFor(chat)
        val (opening, variables) = withContext(Dispatchers.Default) {
            openingGreeting(chat, card, prefs, persona, preset, greeting)
        }
        val history = if (host.current.cardId == card.id) host.current.history else withContext(Dispatchers.IO) { host.store.chats(card.id) }
        val title = if (history.isEmpty()) "新对话" else "新对话 · ${history.size + 1}"
        val available = chat.copy(title = title).availableTitle(history)
        val locals = chat.options.variables.withVariableChanges(variables.local)
        val globals = variables.global.takeIf { it.isNotEmpty() }?.let { host.current.prefs.scriptVariables.withVariableChanges(it) }
        withContext(NonCancellable) {
            host.persist(chat.copy(title = available, messages = listOfNotNull(opening), options = chat.options.copy(variables = locals)), scriptGlobals = globals)
        }
        opening?.let { host.media.narrate(it, automatic = true) }
        host.script.quickEvent(QuickEvent.NEW)
        host.script.quickEvent(QuickEvent.CHAT)
    }

    fun openChat(id: String) = host.work { openStoredChat(id) }

    fun reloadChat(id: String) = host.work { reloadStoredChat(id) }

    private suspend fun reloadStoredChat(id: String) {
        val active = host.current.chat?.takeIf { it.id == id && !it.isTemporary } ?: return
        host.generation.stopAuto()
        val (saved, history) = withContext(Dispatchers.IO) {
            val loaded = host.store.chat(active.id)
            loaded to host.store.chats(loaded.cardId, loaded.groupId)
        }
        host.showChat(saved, history, resetPersona = false)
        host.mutable.update { it.copy(googleReplies = it.googleReplies.filterNot { reply -> reply.chatId == id },
            lastPrompt = it.lastPrompt?.takeUnless { prompt -> prompt.chatId == id }) }
        host.notice("已重新载入聊天")
    }

    suspend fun scriptChatAction(action: ScriptChatAction, chat: Chat, value: String) {
        if (action != ScriptChatAction.JUMP && host.current.generation != null) {
            host.notice("请等待当前回复结束再操作聊天")
            return
        }
        when (action) {
            ScriptChatAction.RENAME -> if (value.isBlank()) host.notice("请输入聊天名称") else renameActiveChat(chat.id, value)
            ScriptChatAction.CLOSE -> if (host.current.chat?.id == chat.id) host.library.closeSubject() else Unit
            ScriptChatAction.RELOAD -> if (chat.isTemporary) host.notice("请先打开已保存的聊天") else reloadStoredChat(chat.id)
            ScriptChatAction.JUMP -> {
                val target = scriptChatJump(chat, value)
                if (target != null) host.mutable.update { if (it.chat?.id == target.chatId) it.copy(archiveJump = target) else it }
                else host.notice("请输入聊天中的消息编号，从 0 开始")
            }
        }
    }

    fun openArchiveHit(hit: ChatArchiveHit, query: String) = host.work { openStoredChat(hit.chat.id, hit, query) }

    internal suspend fun openStoredChat(id: String, hit: ChatArchiveHit? = null, query: String = ""): Chat {
        host.generation.stopAuto()
        val (chat, history) = withContext(Dispatchers.IO) {
            val opened = host.store.chat(id)
            host.store.rememberLastChat(id)
            opened to host.store.chats(opened.cardId, opened.groupId)
        }
        host.showChat(chat, history, resetPersona = true)
        if (hit?.match != null) {
            val position = if (hit.chat.updated == chat.updated) hit.match.index
                else withContext(Dispatchers.Default) { ChatArchiveQuery(query).match(chat.messages)?.index }
            if (position != null && position in chat.messages.indices) {
                host.mutable.update { it.copy(archiveJump = ChatArchiveJump(chat.id, position)) }
            }
        }
        host.script.quickEvent(QuickEvent.CHAT)
        return chat
    }

    fun openTemporaryChat(uri: Uri? = null, title: String = "临时聊天") = host.work {
        openTemporaryChatNow(uri, title)
    }

    internal suspend fun openScriptTemporaryChat(title: String = "临时聊天"): String {
        require(host.current.generation == null) { "请等待当前回复结束再切换聊天" }
        val clean = title.trim().ifBlank { "临时聊天" }
        openTemporaryChatNow(null, clean)
        return clean
    }

    private suspend fun openTemporaryChatNow(uri: Uri?, title: String) {
        host.generation.stopAuto()
        val media = withContext(Dispatchers.IO) { TemporaryChatMedia(host.app.cacheDir) }
        val chat = try {
            if (uri == null) Chat(newId(), temporaryAssistant.id, title)
            else withContext(Dispatchers.IO) {
                val loaded = readChatFiles(uri, title, temporaryAssistant, null, emptyList(), host.current.persona.name,
                    media.documents, media.images, media.videos)
                requireNotNull(loaded.singleOrNull()) { "文件包含多段聊天，请在角色或群组详情页批量导入" }
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

    fun renameChat(chat: Chat, name: String) = host.work { renameActiveChat(chat.id, name) }

    private suspend fun renameActiveChat(id: String, name: String) {
        val current = host.current.chat?.takeIf { it.id == id } ?: return
        if (current.isTemporary) {
            require(name.isNotBlank()) { "请输入聊天名称" }
            host.persist(current.copy(title = name.trim()))
        } else renameStoredChat(id, name)
    }

    fun renameChatFile(id: String, name: String, done: () -> Unit = {}) = host.work(completed = done) { renameStoredChat(id, name) }

    private suspend fun renameStoredChat(id: String, name: String) = withContext(NonCancellable) {
        require(name.isNotBlank()) { "请输入聊天名称" }
        val state = host.current
        require(state.cardId != null || state.groupId != null) { "请先选择角色或群组" }
        val (history, open) = withContext(Dispatchers.IO) {
            val saved = host.store.renameChatFile(id, name.trim(), state.cardId, state.groupId)
            saved to state.chat?.takeUnless(Chat::isTemporary)?.let { host.store.chat(it.id) }
        }
        host.mutable.update { it.copy(history = history, chat = open?.takeIf { chat -> chat.id == it.chat?.id } ?: it.chat) }
        host.notice("聊天已重命名")
    }

    fun forkChat(position: Int, checkpoint: Boolean, requestedName: String = "", swipe: Int? = null) = host.work {
        createChatFork(position, checkpoint, requestedName, swipe)
    }

    fun refreshCheckpoint(position: Int, checkpointId: String) = host.work {
        val chat = requireNotNull(host.current.chat?.takeUnless(Chat::isTemporary)) { "请先打开已保存的聊天" }
        host.generation.stopAuto()
        val (source, history) = withContext(Dispatchers.IO) {
            val updated = host.store.refreshCheckpoint(chat.id, checkpointId, position)
            updated to host.store.chats(updated.cardId, updated.groupId)
        }
        host.mutable.update { it.copy(chat = source, history = history) }
        host.notice("检查点已更新")
    }

    internal suspend fun createChatFork(position: Int, checkpoint: Boolean, requestedName: String, swipe: Int? = null): String {
        val chat = requireNotNull(host.current.chat) { "请先打开对话" }
        require(!chat.isTemporary) { "请先将临时聊天导入角色或群组" }
        require(checkpoint || host.current.generation == null) { "请等待当前回复结束再切换聊天" }
        host.generation.stopAuto()
        val title = requestedName.trim().ifBlank { chat.nextForkTitle(host.current.history, checkpoint) }
        require(host.current.history.none { it.title == title }) { "聊天名称已存在" }
        val (source, fork) = chat.fork(position, title, checkpoint, swipe)
        val history = withContext(Dispatchers.IO) {
            host.store.saveFork(source, fork)
            if (!checkpoint) host.store.rememberLastChat(fork.id)
            host.store.chats(source.cardId, source.groupId)
        }
        if (checkpoint) {
            host.mutable.update { it.copy(chat = source, history = history) }
            host.notice("检查点已保存")
        } else {
            host.showChat(fork, history, resetPersona = true)
            host.script.quickEvent(QuickEvent.CHAT)
        }
        return title
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

    fun restoreChatBackup(id: Long, done: () -> Unit) = host.work(completed = done) {
        val card = host.current.card?.takeUnless { host.current.chat?.isTemporary == true }
        val group = host.current.group
        require((card != null) != (group != null)) { "请选择要恢复到的角色或群组" }
        val backup = withContext(Dispatchers.IO) { host.store.chatBackup(id) }
        copyHistory(backup, card, group)
        host.notice("已从备份恢复聊天副本")
    }

    fun removeChat(chat: ChatSummary) = host.work {
        val (cardId, groupId) = host.current.cardId to host.current.groupId
        val history = withContext(Dispatchers.IO) { host.store.deleteChat(chat.id); host.store.chats(cardId, groupId) }
        host.mutable.update { it.copy(history = history, chat = it.chat?.takeUnless { open -> open.id == chat.id }) }
    }

    fun importChats(uris: List<Uri>) = host.work {
        if (uris.isEmpty()) return@work
        val (card, group) = host.current.card to host.current.group
        require(card != null || group != null) { "请先选择目标角色或群组" }
        require(host.current.chat?.isTemporary != true) { "请使用载入临时聊天" }
        val cards = host.current.cards
        val userName = host.current.persona.name
        host.generation.stopAuto()
        var added = 0
        var failures = 0
        var firstFailure: String? = null
        val (selected, history) = withContext(Dispatchers.IO) {
            var first: Chat? = null
            for (uri in uris.distinct()) {
                currentCoroutineContext().ensureActive()
                var name = "聊天文件"
                try {
                    name = host.app.documentName(uri)
                    val parsed = readChatFiles(uri, name, card, group, cards, userName,
                        host.savedDocuments, host.savedImages, host.savedVideos)
                    val imported = host.store.importChats(parsed, card?.id, group?.id)
                    added += imported.size
                    if (first == null) first = imported.firstOrNull()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++
                    if (firstFailure == null) firstFailure =
                        "${name.take(100)} · ${(e.localizedMessage ?: "无法读取聊天文件").take(160)}"
                }
            }
            first?.let { host.store.rememberLastChat(it.id) }
            first to host.store.chats(card?.id, group?.id)
        }
        if (selected != null) host.showChat(selected, history, resetPersona = true)
        val summary = "已导入 $added 段聊天"
        host.notice(if (failures == 0) summary else "$summary，$failures 个文件失败。$firstFailure")
    }

    private suspend fun readChatFiles(
        uri: Uri,
        title: String,
        card: Card?,
        group: TavernGroup?,
        cards: List<Card>,
        userName: String,
        documents: ChatDocumentStore,
        images: ChatImageStore,
        videos: ChatVideoStore,
    ): List<Chat> {
        val members = group?.members(cards).orEmpty()
        val assistantName = card?.name ?: members.singleOrNull()?.name ?: "角色"
        fun attach(chat: Chat) = chat.withOwner(card, group, members, knownCards = cards)
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
            if (archive) {
                val chat = ChatMediaArchive.read(input, { transcript ->
                    attach(importJsonLinesChat(transcript.decodeToString(), chatImportTitle(title)))
                }, documents::restore, { video, stream -> videos.restore(video, stream) }, images::restore)
                listOf(chat)
            } else {
                importChatTranscripts(input.readLimited(), title, userName, assistantName).map { chat ->
                    host.backup.hydrateOriginalDocuments(attach(chat), documents).first
                }
            }
        }
    }

    fun exportChat(uri: Uri, chat: Chat, subject: String, withAttachments: Boolean = false) = host.work {
        val format = if (withAttachments) ChatExportFormat.ARCHIVE else ChatExportFormat.JSONL
        writeChatExport(uri, chat, subject, format, host.current)
    }

    fun exportChatFile(uri: Uri, id: String, format: ChatExportFormat) = host.work {
        val state = host.current
        val saved = withContext(Dispatchers.IO) { host.store.chat(id) }
        require(saved.cardId == state.cardId && saved.groupId == state.groupId) { "找不到所选聊天" }
        val subject = state.card?.name ?: state.group?.name ?: error("找不到所选聊天的角色或群组")
        writeChatExport(uri, saved, subject, format, state)
    }

    private suspend fun writeChatExport(uri: Uri, chat: Chat, subject: String, format: ChatExportFormat, state: TavernState) {
        if (format == ChatExportFormat.TEXT) {
            withContext(Dispatchers.IO) { host.writeUri(uri, chat.plainText()) }
            host.notice("聊天文本已导出")
            return
        }
        val name = if (chat.id == state.chat?.id) state.persona.name else state.prefs.personaFor(chat).name
        val book = chat.options.worldBook(state.books)
        val history = state.history
        val quickNames = chat.options.quickReplyNames + chat.options.quickReplySets.mapNotNull { id ->
            state.prefs.quickReplies.sets.firstOrNull { it.id == id }?.name
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
        val images = if (chat.isTemporary) host.imageStore else host.savedImages
        val documents = if (chat.isTemporary) host.documentStore else host.savedDocuments
        val videos = if (chat.isTemporary) host.videoStore else host.savedVideos
        withContext(Dispatchers.IO) {
            val transcript = portable.jsonl(subject, name)
            if (format == ChatExportFormat.ARCHIVE) requireNotNull(host.resolver.openOutputStream(uri, "wt")) { "无法写入所选文件" }.use { output ->
                ChatMediaArchive.write(output, transcript, portable, documents::bytes, videos::input) { image ->
                    images.bytes(image) ?: image.takeIf { it == portable.options.background }?.let(host.savedImages::bytes)
                }
            } else host.writeUri(uri, transcript)
        }
        host.notice("聊天已导出")
    }

    fun exportChatText(uri: Uri, chat: Chat) = host.work {
        writeChatExport(uri, chat, "", ChatExportFormat.TEXT, host.current)
    }

    fun editMessage(chat: Chat, index: Int, text: String, done: () -> Unit = {}) = host.work(completed = done) {
        host.generation.stopAuto()
        val active = host.current.chat?.takeIf { it.id == chat.id } ?: return@work
        val message = active.messages.getOrNull(index) ?: return@work
        updateMessage(active, index, editedMessage(active, message, text))
    }

    private suspend fun editedMessage(chat: Chat, message: Message, text: String): Message {
        require(message.acceptsEditing(text)) { "消息不能为空" }
        val rules = host.current.textRules(chat, host.current.regexCard(message))
        val replacement = withContext(Dispatchers.Default) {
            message.regexSite?.let { rules.transform(text, it, RegexStage.STORAGE, editing = true) } ?: text
        }
        return message.withText(replacement)
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

    fun deleteMessages(chat: Chat, indices: Set<Int>, includeTools: Boolean = true, done: () -> Unit = {}) = host.work(completed = done) {
        host.generation.stopAuto()
        val active = host.current.chat?.takeIf { it.id == chat.id } ?: return@work
        removeMessages(active, indices, includeTools)
    }

    internal suspend fun removeMessages(chat: Chat, indices: Set<Int>, includeTools: Boolean) {
        val removed = chat.messages.deletionIndices(indices, includeTools)
        if (removed.isNotEmpty()) host.persist(chat.copy(
            messages = chat.messages.filterIndexed { index, _ -> index !in removed },
            updated = System.currentTimeMillis(),
        ))
    }

    fun duplicateMessage(chat: Chat, index: Int, draft: String? = null, done: () -> Unit = {}) = host.work(completed = done) {
        host.generation.stopAuto()
        val active = host.current.chat?.takeIf { it.id == chat.id } ?: return@work
        val original = active.messages.getOrNull(index) ?: return@work
        val copy = (draft?.let { editedMessage(active, original, it) } ?: original)
            .copy(checkpoint = null, branches = emptyList())
        host.persist(active.copy(
            messages = active.messages.toMutableList().apply { add(index + 1, copy) },
            updated = System.currentTimeMillis(),
        ))
    }

    fun moveMessage(chat: Chat, index: Int, direction: Int, done: () -> Unit = {}) = host.work(completed = done) {
        host.generation.stopAuto()
        val active = host.current.chat?.takeIf { it.id == chat.id } ?: return@work
        val destination = index + direction
        require(index in active.messages.indices && destination in active.messages.indices) { "消息已在对话边界" }
        val moved = active.messages.toMutableList().apply { add(destination, removeAt(index)) }
        host.persist(active.copy(messages = moved, updated = System.currentTimeMillis()))
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

    fun saveSummaryExtras(settings: SummaryExtrasSettings, done: () -> Unit) = host.work {
        val clean = settings.copy(endpoint = settings.endpoint.trim(), apiKey = settings.apiKey.trim())
        if (clean.endpoint.isNotEmpty()) clean.requestAddress()
        else require(clean.apiKey.isEmpty()) { "请填写 Extras 服务地址" }
        host.persistPreferences(host.current.prefs.copy(summaryExtras = clean))
        done()
    }

    private suspend fun refreshMemory(chat: Chat, force: Boolean): String? {
        val batch = withContext(Dispatchers.Default) { chat.summaryBatch(force) } ?: return null
        val options = chat.options.memory
        val prefs = host.current.prefs
        val result = when (options.source) {
            SummarySource.MODEL -> ModelService.utility(prefs.utilityModel(chat, options.model, "摘要"),
                batch.forModel(options.modelInstruction()), options.maxTokens)
            SummarySource.EXTRAS -> summarizeWithExtras(prefs.summaryExtras, batch.forExtras())
        }
        val messages = withContext(Dispatchers.Default) { chat.messages.withSummary(batch.lastIndex, result) }
        val active = host.current.chat?.takeIf { it.id == chat.id && it.messages == chat.messages }
            ?: error("对话内容已变更，请重新更新摘要")
        host.persist(active.copy(messages = messages, options = active.options.copy(memory = options)))
        return result
    }

    fun summarizeChat(options: MemoryOptions) = host.utilityWork("正在摘要聊天…") {
        val chat = host.current.chat ?: return@utilityWork
        if (refreshMemory(chat.copy(options = chat.options.copy(memory = options)), true) == null) {
            host.notice("没有尚未摘要的新消息")
        }
    }

    internal suspend fun summarizeAutomatically(chat: Chat) {
        if (!chat.options.memory.automatic) return
        try {
            refreshMemory(chat, false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            host.notice("摘要更新失败 · ${e.localizedMessage.orEmpty()}")
        }
    }

    internal suspend fun scriptSummarize(text: String, arguments: Map<String, String>): String {
        val chat = host.current.chat
        if (text.isBlank()) {
            val active = requireNotNull(chat) { "请先打开对话" }
            return refreshMemory(active, true) ?: active.messages.currentSummary()?.summary?.text.orEmpty()
        }
        val options = chat?.options?.memory ?: MemoryOptions()
        val source = arguments["source"]?.let { value ->
            requireNotNull(SummarySource.entries.firstOrNull { it.wire == value }) { "source= 可选 main 或 extras" }
        } ?: options.source
        val prefs = host.current.prefs
        return when (source) {
            SummarySource.MODEL -> ModelService.utility(prefs.utilityModel(chat, options.model, "摘要"),
                utilityMessages(options.modelInstruction(arguments["prompt"]), text), options.maxTokens)
            SummarySource.EXTRAS -> summarizeWithExtras(prefs.summaryExtras, text)
        }
    }
}
