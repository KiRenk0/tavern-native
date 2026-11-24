package app.tavern.nativeclient.world

import android.net.Uri
import app.tavern.nativeclient.*
import app.tavern.nativeclient.script.ScriptWorldBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.util.Locale

internal class WorldMemoryCoordinator(private val host: CoordinatorHost) {
    fun saveBook(book: WorldBook, done: () -> Unit) = host.work {
        val value = book.copy(name = book.name.trim())
        require(value.name.isNotBlank()) { "请输入世界书名称" }
        require(host.current.books.none { it.id != value.id && it.name.equals(value.name, true) }) { "世界书名称已存在" }
        val previous = host.current.books.firstOrNull { it.id == value.id }
        val renamedFrom = previous?.name?.takeIf { it != value.name }
        val linkedChat = host.current.chat?.takeIf { chat ->
            !chat.isTemporary && chat.options.hasWorldBookOverride &&
                chat.options.worldBook(host.current.books)?.let { it.id == value.id && it.name != value.name } == true
        }
        val (books, cards, groups, chat) = withContext(Dispatchers.IO) {
            host.store.save(value)
            Quad(host.store.books(), host.store.cards(), host.store.groups(), linkedChat?.let { host.store.chat(it.id) })
        }
        if (renamedFrom != null) {
            val prefs = host.current.prefs
            val relinkedPersonas = prefs.personas.map { persona ->
                if (persona.worldId == renamedFrom) persona.copy(worldId = value.id) else persona
            }
            if (relinkedPersonas != prefs.personas) {
                host.persistPreferences(prefs.copy(personas = relinkedPersonas))
            }
        }
        host.mutable.update { current ->
            val updatedChat = when {
                chat != null && chat.id == current.chat?.id -> chat
                renamedFrom != null && current.chat?.isTemporary == true ->
                    current.chat.relinkWorldBook(value.id, renamedFrom, value.name)
                else -> current.chat
            }
            current.copy(
                books = books,
                cards = cards,
                groups = groups,
                chat = updatedChat,
            )
        }
        host.eventBus.emit(TavernCoreEvent.WORLDINFO_UPDATED, jsonOf("book_id" to value.id, "book_name" to value.name))
        done()
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    fun deleteBook(book: WorldBook) = host.work {
        val (books, cards) = withContext(Dispatchers.IO) { host.store.deleteBook(book); host.store.books() to host.store.cards() }
        host.mutable.update { it.copy(books = books, cards = cards) }
    }

    fun transferEntry(sourceId: String, targetId: String, entryId: Int, move: Boolean, done: (Int) -> Unit) = host.work {
        val (newEntryId, books) = withContext(Dispatchers.IO) {
            val id = host.store.transferWorldEntry(sourceId, targetId, entryId, move)
            id to host.store.books()
        }
        host.mutable.update { it.copy(books = books) }
        host.notice(if (move) "已移动世界书条目" else "已复制世界书条目")
        done(newEntryId)
    }

    fun importBook(uri: Uri, displayName: String) = host.work {
        val books = withContext(Dispatchers.IO) {
            val source = requireNotNull(host.resolver.openInputStream(uri)) { "无法打开世界书文件" }
                .use { it.readLimited() }
            val parsed = readWorldBook(source, displayName.substringBeforeLast('.'))
            saveImportedBook(parsed, host.store.books().map(WorldBook::name))
            host.store.books()
        }
        host.mutable.update { it.copy(books = books) }
        host.notice("世界书已导入")
    }

    fun importCardLore(ids: List<String>, done: () -> Unit) = host.work(completed = done) {
        val requested = ids.toHashSet()
        val state = host.current
        var added = 0
        try {
            withContext(Dispatchers.IO) {
                val books = host.store.books().toMutableList()
                val names = books.mapTo(hashSetOf()) { it.name.lowercase(Locale.ROOT) }
                val cards = host.store.cards()
                val aliases = cards.uniqueLoreFilenames()
                for (card in cards.filter { it.id in requested }) {
                    currentCoroutineContext().ensureActive()
                    val book = card.embeddedWorldBook() ?: continue
                    if (books.any { it.id == book.id }) continue
                    val title = generateSequence(1) { it + 1 }
                        .map { if (it == 1) book.name else "${book.name} ($it)" }
                        .first { names.add(it.lowercase(Locale.ROOT)) }
                    val created = book.remapLoreFilters(aliases).copy(name = title)
                    val bound = if (books.any { it.name == card.worldName }) {
                        card.withAdditionalWorldBooks((card.additionalWorldBookIds + created.id).distinct())
                    } else card.withWorld(title)
                    host.store.createBoundWorldBook(created, ScriptWorldBinding.Character(bound))
                    books += created
                    added++
                }
            }
        } finally {
            if (added > 0) withContext(NonCancellable) {
                val (books, cards) = withContext(Dispatchers.IO) { host.store.books() to host.store.cards() }
                host.mutable.update { it.copy(books = books, cards = cards) }
            }
        }
        host.notice(if (added == 0) "所选角色卡没有新的内嵌世界书" else "已提取并关联 $added 本世界书")
    }

    internal fun saveImportedBook(book: WorldBook, names: List<String>) {
        val expected = names.mapTo(hashSetOf()) { it.lowercase() }
        val name = generateSequence(1) { it + 1 }.map { if (it == 1) book.name else "${book.name} ($it)" }
            .first { it.lowercase() !in expected }
        host.store.save(book.remapLoreFilters(host.store.cards().uniqueLoreFilenames()).copy(name = name))
    }

    fun exportBook(uri: Uri, book: WorldBook) = host.work {
        val names = host.current.cards.associate { it.id to it.scriptAvatarKey.loreFilenameStem() }
        withContext(Dispatchers.IO) { host.writeUri(uri, book.remapLoreFilters(names).export()) }
        host.notice("世界书已导出")
    }

    fun saveVectorPreferences(options: VectorPreferences, done: () -> Unit) = host.work {
        require(!options.enabled || host.current.vectorSource(options) != null) { "请选择可用的向量服务或安装本地模型" }
        host.persistPreferences(host.current.prefs.copy(vectors = options))
        done()
    }

    fun testVectorPreferences(options: VectorPreferences) = host.utilityWork("正在测试向量服务…") {
        val source = requireNotNull(host.current.vectorSource(options)) { "请选择可用的向量服务或安装本地模型" }
        val text = if (source is EmbeddingSource.Local && source.model == LocalEmbeddingModel.BGE_ZH) "测试本地向量检索"
        else "Tavern Native embedding test"
        val vector = host.embeddingService.embed(source, listOf(EmbeddingInput("", text, query = true))).single()
        val label = if (source is EmbeddingSource.Local) "本地模型运行成功" else "向量服务连接成功"
        host.notice("$label · ${vector.size} 维")
    }

    fun downloadEmbeddingModel(model: LocalEmbeddingModel) = host.utilityWork("正在下载本地嵌入模型…") {
        installEmbeddingModel(model) { install ->
            downloadModelWeights("${model.downloadPath}?download=true", install) {
                host.mutable.update { it.copy(utility = "正在从镜像下载本地嵌入模型…") }
            }
        }
    }

    fun importEmbeddingModel(model: LocalEmbeddingModel, uri: Uri) = host.utilityWork("正在导入本地嵌入模型…") {
        installEmbeddingModel(model) { install ->
            withContext(Dispatchers.IO) {
                requireNotNull(host.resolver.openInputStream(uri)) { "无法打开模型文件" }.use(install)
            }
        }
    }

    private suspend fun installEmbeddingModel(model: LocalEmbeddingModel, read: suspend ((InputStream) -> Unit) -> Unit) {
        val context = currentCoroutineContext()
        host.mutable.update { it.copy(localEmbeddingProgress = 0) }
        var lastPercent = 0
        try {
            read { input ->
                host.localEmbeddings.install(model, input) { received ->
                    context.ensureActive()
                    val percent = (received * 100 / model.bytes).toInt()
                    if (lastPercent != percent) {
                        lastPercent = percent
                        host.mutable.update { it.copy(localEmbeddingProgress = percent) }
                    }
                }
            }
            host.notice("本地嵌入模型已就绪")
        } finally {
            host.refreshInstalledEmbeddingModels()
        }
    }

    fun removeEmbeddingModel(model: LocalEmbeddingModel) = host.work {
        withContext(Dispatchers.IO) { host.localEmbeddings.remove(model) }
        host.refreshInstalledEmbeddingModels()
        host.notice("已移除本地嵌入模型")
    }

    fun loadDataBank() = host.work { host.refreshDataBank() }

    fun dismissDataBankRequest() = host.mutable.update { it.copy(openDataBank = false) }

    fun openDataDocument(id: String) = host.work {
        val document = host.current.dataDocuments.firstOrNull { it.id == id } ?: return@work
        val text = withContext(Dispatchers.IO) {
            requireNotNull(host.store.documentText(id, DOCUMENT_EDITOR_LIMIT)) { "找不到指定资料" }
        }
        host.mutable.update { it.copy(openDocument = DocumentText(document, text, document.size > DOCUMENT_EDITOR_LIMIT)) }
    }

    fun closeDataDocument() = host.mutable.update { it.copy(openDocument = null) }

    private fun checkDataOwner(owner: DataOwner) {
        when (owner.scope) {
            DataScope.GLOBAL -> Unit
            DataScope.CHARACTER -> require(host.current.cards.any { it.id == owner.id }) { "角色已不存在" }
            DataScope.CHAT -> require(host.current.chat?.id == owner.id) { "该对话已关闭" }
        }
    }

    private suspend fun persistDataDocument(document: DataDocument, text: String?) {
        checkDataOwner(document.owner)
        val title = document.title.trim().take(180)
        require(title.isNotBlank()) { "请输入资料名称" }
        withContext(Dispatchers.IO) {
            host.store.saveDocument(document.copy(title = title, updated = System.currentTimeMillis()), text?.let(::validatedDocumentText))
        }
        host.refreshDataBank()
    }

    fun saveDataDocument(document: DataDocument, text: String?, done: () -> Unit) = host.work {
        persistDataDocument(document, text)
        closeDataDocument()
        done()
    }

    fun importDataDocuments(owner: DataOwner, uris: List<Uri>) = host.utilityWork("正在导入资料…") {
        checkDataOwner(owner)
        var imported = 0
        val failures = mutableListOf<String>()
        try {
            withContext(Dispatchers.IO) {
                for (uri in uris) {
                    currentCoroutineContext().ensureActive()
                    val name = host.app.documentName(uri)
                    try {
                        val bytes = requireNotNull(host.resolver.openInputStream(uri)) { "无法打开文件" }
                            .use { it.readLimited(MAX_DOCUMENT_INPUT_BYTES) }
                        val document = openDataDocument(name, host.resolver.getType(uri), bytes, host.app.cacheDir)
                        host.store.saveDocument(DataDocument(title = document.title, owner = owner), document.text)
                        imported++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failures += "$name · ${e.localizedMessage.orEmpty()}"
                    }
                }
            }
        } finally {
            withContext(NonCancellable) { host.refreshDataBank() }
        }
        host.notice(if (failures.isEmpty()) "已导入 $imported 份资料" else "已导入 $imported 份资料 · ${failures.first()}")
    }

    fun importDataUrls(owner: DataOwner, urls: List<String>) = host.utilityWork("正在提取网页文本…") {
        require(urls.size in 1..MAX_WEB_DOCUMENTS) { "每批最多导入 $MAX_WEB_DOCUMENTS 个网页" }
        checkDataOwner(owner)
        var imported = 0
        val failures = mutableListOf<String>()
        try {
            for ((index, url) in urls.withIndex()) {
                currentCoroutineContext().ensureActive()
                try {
                    val document = importWebDocument(url)
                    checkDataOwner(owner)
                    withContext(Dispatchers.IO) {
                        host.store.saveDocument(DataDocument(title = document.title, owner = owner, source = url.trim().take(2000)), document.text)
                    }
                    imported++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures += "第 ${index + 1} 个地址 · ${e.localizedMessage.orEmpty().take(180)}"
                }
            }
        } finally {
            withContext(NonCancellable) { host.refreshDataBank() }
        }
        val report = "已导入 $imported 份网页资料"
        host.notice(if (failures.isEmpty()) report else "$report · ${failures.size} 项失败 · ${failures.first()}")
    }

    fun exportDataDocument(document: DataDocument, uri: Uri) = host.work {
        withContext(Dispatchers.IO) {
            val text = requireNotNull(host.store.documentText(document.id)) { "找不到指定资料" }
            host.writeUri(uri, text.encodeToByteArray())
        }
        host.notice("资料已导出")
    }

    fun deleteDataDocument(id: String) = host.work {
        withContext(Dispatchers.IO) { host.store.deleteDocument(id) }
        host.refreshDataBank()
        closeDataDocument()
    }

    fun indexDataDocuments(owner: DataOwner) = host.utilityWork("正在准备资料索引…") {
        checkDataOwner(owner)
        val snapshot = host.current
        val prefs = snapshot.prefs.vectors
        val source = requireNotNull(snapshot.vectorSource()) { "请先在向量设置配置服务" }
        val documents = withContext(Dispatchers.IO) { host.store.dataDocuments(owner).filter(DataDocument::enabled) }
        var chunks = 0
        for ((index, document) in documents.withIndex()) {
            val corpus = withContext(Dispatchers.IO) { host.store.documentText(document.id)?.let { fileCorpus(document, it, prefs) } } ?: continue
            chunks += host.vectorSearch.index(source, corpus) { done, total ->
                host.mutable.update { it.copy(utility = "正在建立资料索引 · ${index + 1}/${documents.size} · $done/$total") }
            }
        }
        host.notice("资料索引已更新 · $chunks 个片段")
    }

    fun clearDataDocumentIndex(id: String) = host.work {
        val document = host.current.dataDocuments.firstOrNull { it.id == id } ?: return@work
        withContext(Dispatchers.IO) { host.store.clearVectors(document.indexScope) }
        host.notice("资料索引已清除")
    }

    internal suspend fun scriptDataBank(command: String, named: Map<String, String>, input: String): String {
        val chat = requireNotNull(host.current.chat) { "请先打开对话" }
        require(!chat.isTemporary) { "请先将临时聊天导入角色或群组" }
        if (command == "db") {
            host.mutable.update { it.copy(openDataBank = true) }
            return ""
        }
        val activeCharacter = host.current.generation?.characterId?.let { id -> host.current.cards.firstOrNull { it.id == id } }
            ?: host.current.regexCard()
        val defaultScope = if (command in setOf("db-add", "db-update", "db-delete")) "chat" else ""
        val source = named["source"]?.trim()?.lowercase(Locale.ROOT).orEmpty().ifBlank { defaultScope }
        val owner = when (source) {
            "chat" -> DataOwner(DataScope.CHAT, chat.id)
            "global" -> DataOwner(DataScope.GLOBAL)
            "character" -> DataOwner(DataScope.CHARACTER, requireNotNull(activeCharacter?.id) { "找不到当前角色" })
            "", "all" -> null
            else -> error("未知的资料范围 $source")
        }
        if (command == "db-add") {
            val document = DataDocument(
                title = named["name"]?.trim()?.takeIf(String::isNotBlank) ?: "资料 ${newId().take(8)}",
                owner = owner ?: DataOwner(DataScope.CHAT, chat.id),
            )
            persistDataDocument(document, input)
            return document.address
        }
        val items = withContext(Dispatchers.IO) {
            if (owner != null) host.store.dataDocuments(owner) else host.store.dataDocuments().filter {
                it.owner.scope == DataScope.GLOBAL || it.owner == DataOwner(DataScope.CHAT, chat.id) ||
                    (it.owner.scope == DataScope.CHARACTER && it.owner.id == activeCharacter?.id)
            }
        }
        if (command == "db-list") return JSONArray(items.map {
            when (named["field"]) { "name" -> it.title; "size" -> it.size; "created" -> it.updated; else -> it.address }
        }).toString()
        val reference = if (command == "db-update") named["url"] ?: named["name"] else input.trim()
        val document = items.firstOrNull { it.address == reference || it.id == reference }
            ?: items.firstOrNull { it.title == reference || (it.source.isNotEmpty() && it.source == reference) }
            ?: error("找不到指定资料")
        return when (command) {
            "db-get" -> withContext(Dispatchers.IO) { host.store.documentText(document.id).orEmpty() }
            "db-update" -> {
                persistDataDocument(document, input)
                document.address
            }
            "db-disable", "db-enable" -> {
                persistDataDocument(document.copy(enabled = command == "db-enable"), null)
                ""
            }
            "db-delete" -> {
                withContext(Dispatchers.IO) { host.store.deleteDocument(document.id) }
                host.refreshDataBank()
                ""
            }
            else -> error("未知的资料库命令")
        }
    }

    internal suspend fun promptSharedWorldMemory(
        chat: Chat,
        card: Card,
        history: List<Message>,
        books: List<WorldBook>,
        groups: List<TavernGroup>,
        cards: List<Card>,
    ): String {
        val world = chat.memoryWorld(card, books)?.takeIf(WorldBook::sharedMemory) ?: return ""
        val groupById = groups.filter { card.id in it.memberIds }.associateBy(TavernGroup::id)
        val recall = withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            val terms = sharedWorldTerms(history)
            val solo = host.store.sharedMemoryChats(card, world, chat.id)
            val group = groupById.keys.asSequence().flatMap { groupId ->
                context.ensureActive()
                host.store.sharedMemoryChats(card, world, chat.id, groupId).asSequence()
            }.sortedByDescending(ChatSummary::updated).take(SHARED_WORLD_CHAT_LIMIT)
            val turns = buildList {
                for (source in solo + group) {
                    context.ensureActive()
                    val previous = try {
                        host.store.chat(source.id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    if (previous != null && previous.memoryWorld(card, books)?.id == world.id) {
                        addAll(previous.sharedWorldTurns(card, groupById[previous.groupId], cards, terms))
                    }
                }
            }
            selectSharedWorldMemoriesHybrid(
                world = world,
                card = card,
                active = chat,
                candidates = turns,
                queryText = sharedWorldQueryText(history),
                embeddingSource = host.current.vectorSource(),
                embeddingService = host.embeddingService,
                vectorCache = host.store,
            )
        }
        host.mutable.update { it.copy(lastSharedMemory = recall) }
        return recall.promptText
    }

    fun inspectSharedWorldMemory(world: WorldBook, card: Card, chat: Chat? = host.current.chat) = host.work {
        if (!world.sharedMemory) return@work
        val targetChat = chat ?: Chat(id = "", cardId = card.id, title = card.name)
        val snapshot = host.current
        val groupById = snapshot.groups.filter { card.id in it.memberIds }.associateBy(TavernGroup::id)
        val recall = withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            val terms = sharedWorldTerms(targetChat.messages)
            val solo = host.store.sharedMemoryChats(card, world, targetChat.id)
            val group = groupById.keys.asSequence().flatMap { groupId ->
                context.ensureActive()
                host.store.sharedMemoryChats(card, world, targetChat.id, groupId).asSequence()
            }.sortedByDescending(ChatSummary::updated).take(SHARED_WORLD_CHAT_LIMIT)
            val turns = buildList {
                for (source in solo + group) {
                    context.ensureActive()
                    val previous = runCatching { host.store.chat(source.id) }.getOrNull()
                    if (previous != null && previous.memoryWorld(card, snapshot.books)?.id == world.id) {
                        addAll(previous.sharedWorldTurns(card, groupById[previous.groupId], snapshot.cards, terms))
                    }
                }
            }
            selectSharedWorldMemoriesHybrid(
                world = world,
                card = card,
                active = targetChat,
                candidates = turns,
                queryText = sharedWorldQueryText(targetChat.messages),
                embeddingSource = snapshot.vectorSource(),
                embeddingService = host.embeddingService,
                vectorCache = host.store,
            )
        }
        host.mutable.update { it.copy(lastSharedMemory = recall) }
    }

    fun pinSharedMemory(world: WorldBook, card: Card, item: RecalledWorldMemory) {
        val overrides = world.memoryOverridesFor(card.id)
        val pin = PinnedWorldMemory(
            sourceChatId = item.turn.chatId,
            sourceTitle = item.turn.title,
            group = item.turn.group,
            text = item.displayText,
        )
        val updated = world.withMemoryOverrides(card.id, overrides.copy(pinned = overrides.pinned + pin))
        saveBook(updated) { inspectSharedWorldMemory(updated, card) }
    }

    fun unpinSharedMemory(world: WorldBook, card: Card, pinId: String) {
        val overrides = world.memoryOverridesFor(card.id)
        val updated = world.withMemoryOverrides(card.id, overrides.copy(pinned = overrides.pinned.filterNot { it.id == pinId }))
        saveBook(updated) { inspectSharedWorldMemory(updated, card) }
    }

    fun editSharedMemory(world: WorldBook, card: Card, replyKey: String, newText: String) {
        val overrides = world.memoryOverridesFor(card.id)
        val nextEdited = overrides.editedTexts + (replyKey to newText)
        val updated = world.withMemoryOverrides(card.id, overrides.copy(editedTexts = nextEdited))
        saveBook(updated) { inspectSharedWorldMemory(updated, card) }
    }

    fun blockSharedMemory(world: WorldBook, card: Card, replyKey: String, block: Boolean) {
        val overrides = world.memoryOverridesFor(card.id)
        val nextBlocked = if (block) overrides.blockedKeys + replyKey else overrides.blockedKeys - replyKey
        val updated = world.withMemoryOverrides(card.id, overrides.copy(blockedKeys = nextBlocked))
        saveBook(updated) { inspectSharedWorldMemory(updated, card) }
    }

    fun promoteSharedMemoryToLore(world: WorldBook, card: Card, memoryText: String, sourceTitle: String) {
        val updated = promoteMemoryToLoreEntry(world, card, memoryText, sourceTitle)
        saveBook(updated) {
            host.notice("已写入世界书「${updated.name}」")
            inspectSharedWorldMemory(updated, card)
        }
    }

    internal suspend fun promptVectors(
        prefs: Preferences,
        chat: Chat,
        card: Card,
        persona: PersonaProfile,
        books: List<WorldBook>,
        history: List<Message>,
    ): VectorContext {
        val config = prefs.vectors
        if (!config.enabled || chat.isTemporary) return VectorContext()
        val source = config.source(prefs.providers) ?: return VectorContext().also { host.notice("请重新配置向量检索提供商") }
        if (source is EmbeddingSource.Local && source.model !in host.current.installedEmbeddingModels) {
            host.notice("请先安装所选本地嵌入模型，已使用普通聊天上下文")
            return VectorContext()
        }
        return try {
            withTimeoutOrNull(18_000) {
                val candidates = if (config.worldEnabled) withContext(Dispatchers.Default) {
                    val prefs = host.current.prefs
                    loreCandidates(card, books, persona, chat.options, prefs.loreSettings, prefs.library.loreTags(card))
                } else emptyList()
                val documents = if (config.files.enabled) withContext(Dispatchers.IO) {
                    host.store.availableDocuments(card.id, chat.id)
                } else emptyList()
                host.vectorSearch.retrieve(source, config, chat.id, history, candidates, documents, host.documentStore::text) {
                    host.store.documentText(it)
                }
            } ?: VectorContext().also { host.notice("向量检索超时，已使用普通聊天上下文") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            host.notice("向量检索失败，已使用普通聊天上下文 · ${e.localizedMessage.orEmpty()}")
            VectorContext()
        }
    }

    private suspend fun vectorCorpora(snapshot: TavernState, chat: Chat): List<VectorCorpus> = withContext(Dispatchers.Default) {
        val config = snapshot.prefs.vectors
        buildList {
            if (config.chatEnabled) add(chatCorpus(chat.id, chat.messages, config, query = false))
            if (config.worldEnabled) {
                val cards = snapshot.group?.members(snapshot.cards) ?: listOfNotNull(snapshot.card)
                val lore = cards.flatMap { card ->
                    loreCandidates(card, snapshot.books, snapshot.persona, chat.options,
                        snapshot.prefs.loreSettings, snapshot.prefs.library.loreTags(card))
                }
                    .distinctBy(LoreCandidate::key)
                add(worldCorpus(lore, config))
            }
        }
    }

    private fun indexedAttachments(chat: Chat, config: VectorPreferences): List<ChatDocument> = if (config.messageFiles.enabled) {
        chat.messages.flatMap(Message::documents).distinctBy(ChatDocument::id)
            .filter { it.bytes > config.messageFiles.thresholdKb * 1024 }
    } else emptyList()

    private suspend fun documentVectorCorpus(document: ChatDocument, config: VectorPreferences): VectorCorpus? = withContext(Dispatchers.IO) {
        host.documentStore.text(document)?.let { attachmentCorpus(document, it, config) }
    }

    fun indexVectors() = host.utilityWork("正在准备向量索引…") {
        val snapshot = host.current
        val chat = snapshot.chat ?: return@utilityWork
        val source = requireNotNull(snapshot.vectorSource()) { "请先配置向量检索" }
        var completed = vectorCorpora(snapshot, chat).sumOf { corpus ->
            val label = if (corpus.scope == WORLD_VECTOR_SCOPE) "世界书" else "当前聊天"
            host.vectorSearch.index(source, corpus) { done, total ->
                host.mutable.update { it.copy(utility = "正在建立${label}索引 · $done/$total") }
            }
        }
        val config = snapshot.prefs.vectors
        for (document in indexedAttachments(chat, config)) {
            val corpus = documentVectorCorpus(document, config) ?: continue
            completed += host.vectorSearch.index(source, corpus) { done, total ->
                host.mutable.update { it.copy(utility = "正在建立文档索引 · ${document.name} · $done/$total") }
            }
        }
        host.notice("向量索引已更新 · $completed 个内容片段")
    }

    fun showVectorCounts() = host.utilityWork("正在读取向量索引…") {
        val snapshot = host.current
        val chat = snapshot.chat ?: return@utilityWork
        val source = requireNotNull(snapshot.vectorSource()) { "请先配置向量检索" }
        val summary = vectorCorpora(snapshot, chat).map { corpus ->
            val (indexed, total) = host.vectorSearch.counts(source, corpus)
            "${if (corpus.scope == WORLD_VECTOR_SCOPE) "世界书" else "聊天"} $indexed/$total"
        }.toMutableList()
        val config = snapshot.prefs.vectors
        if (config.messageFiles.enabled) {
            var indexed = 0
            var total = 0
            for (document in indexedAttachments(chat, config)) {
                val corpus = documentVectorCorpus(document, config) ?: continue
                val (ready, count) = host.vectorSearch.counts(source, corpus)
                indexed += ready
                total += count
            }
            summary += "消息文档 $indexed/$total"
        }
        host.notice(summary.joinToString(" · ").ifBlank { "当前没有启用的向量索引" })
    }

    fun clearVectorIndex(world: Boolean) = host.work {
        val scope = if (world) WORLD_VECTOR_SCOPE else host.current.chat?.id?.let(::chatVectorScope) ?: return@work
        withContext(Dispatchers.IO) { host.store.clearVectors(scope) }
        host.notice(if (world) "世界书向量索引已清除" else "当前聊天的向量索引已清除")
    }

    fun clearChatAttachmentIndex() = host.work {
        val chat = host.current.chat ?: return@work
        val scopes = chat.messages.flatMap(Message::documents).map(ChatDocument::vectorScope).distinct()
        withContext(Dispatchers.IO) { scopes.forEach(host.store::clearVectors) }
        host.notice("消息文档索引已清除")
    }
}
