package app.tavern.nativeclient.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import app.tavern.nativeclient.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

internal class LibraryCoordinator(private val host: CoordinatorHost) {
    private val groupWrites = Mutex()
    private val cardWrites = Mutex()
    fun savePersona(profile: PersonaProfile, photo: Uri?, done: () -> Unit) = host.work {
        val prefs = host.current.prefs
        val previous = prefs.personas.firstOrNull { it.id == profile.id }
        val name = profile.name.trim()
        require(name.isNotBlank()) { "请输入人设名称" }
        val avatar = if (photo == null) profile.avatar else newId().also { id ->
            withContext(Dispatchers.IO) { host.store.writePersonaAvatar(id, decodeAvatar(photo)) }
        }
        val saved = profile.copy(name = name, title = profile.title.trim(), avatar = avatar)
        val index = prefs.personas.indexOfFirst { it.id == saved.id }
        val personas = prefs.personas.toMutableList().apply { if (index < 0) add(saved) else set(index, saved) }
        try {
            withContext(NonCancellable) { host.persistPreferences(prefs.copy(personas = personas)) }
        } catch (e: Exception) {
            if (photo != null && avatar != null) withContext(NonCancellable + Dispatchers.IO) { host.store.deletePersonaAvatar(avatar) }
            throw e
        }
        previous?.avatar?.takeUnless { it == avatar }?.let { id -> withContext(Dispatchers.IO) { host.store.deletePersonaAvatar(id) } }
        done()
    }

    fun importPersonas(uri: Uri, done: () -> Unit = {}) = host.work {
        val (updated, added) = withContext(Dispatchers.IO) {
            val source = requireNotNull(host.resolver.openInputStream(uri)) { "无法打开人设 JSON" }
                .use { it.readLimited() }
            readPersonaJson(source, host.current.books, host.current.cards, host.current.groups).merge(host.current.prefs)
        }
        if (updated != host.current.prefs) host.persistPreferences(updated)
        host.notice(if (added == 0) "文件中的人设已存在" else "已导入 $added 个人设")
        done()
    }

    fun personaFromCard(card: Card) = host.work {
        val prefs = host.current.prefs
        val id = newId()
        val avatar = withContext(Dispatchers.IO) {
            val source = host.store.portraitFile(card.id)
            if (!card.hasAvatar || !source.isFile) null else newId().also { asset ->
                val bytes = source.inputStream().use { it.readLimited() }
                host.store.writePersonaAvatar(asset, resizeAvatar(bytes))
            }
        }
        val profile = PersonaProfile(
            id = id,
            name = card.name,
            description = swapPersonaMacros(card.description),
            worldId = host.current.books.firstOrNull { it.name == card.worldName }?.id.orEmpty(),
            avatar = avatar,
        )
        try {
            withContext(NonCancellable) { host.persistPreferences(prefs.copy(personas = prefs.personas + profile)) }
        } catch (e: Exception) {
            avatar?.let { withContext(NonCancellable + Dispatchers.IO) { host.store.deletePersonaAvatar(it) } }
            throw e
        }
        host.notice("已创建人设 ${profile.name}")
    }

    fun setDefaultPersona(persona: PersonaProfile) = host.work {
        val prefs = host.current.prefs
        if (prefs.personas.any { it.id == persona.id }) {
            host.persistPreferences(prefs.copy(defaultPersonaId = persona.id))
            host.notice("默认人设已更新")
        }
    }

    fun removePersona(persona: PersonaProfile) = host.work {
        val prefs = host.current.prefs
        val remaining = prefs.personas.filterNot { it.id == persona.id }.ifEmpty { listOf(PersonaProfile.legacy()) }
        val defaultId = prefs.defaultPersonaId.takeUnless { it == persona.id } ?: remaining.first().id
        host.persistPreferences(prefs.copy(
            personas = remaining,
            defaultPersonaId = defaultId,
            personaConnections = prefs.personaConnections.filterValues { it != persona.id },
        ))
        host.mutable.update { it.copy(temporaryPersonaId = it.temporaryPersonaId?.takeUnless { id -> id == persona.id }) }
        persona.avatar?.let { id -> withContext(Dispatchers.IO) { host.store.deletePersonaAvatar(id) } }
    }

    fun usePersonaTemporarily(id: String) = host.work {
        if (host.current.chat != null && host.current.prefs.personas.any { it.id == id }) {
            host.mutable.update { it.copy(temporaryPersonaId = id) }
        }
    }

    fun pinPersona(id: String?) = host.work {
        val chat = host.current.chat ?: return@work
        require(id == null || host.current.prefs.personas.any { it.id == id }) { "找不到该人设" }
        host.persist(chat.copy(options = chat.options.copy(personaId = id)))
        host.mutable.update { it.copy(temporaryPersonaId = null) }
    }

    fun bindSubjectPersona(id: String?) = host.work {
        val subject = host.current.cardId ?: host.current.groupId ?: return@work
        val prefs = host.current.prefs
        require(id == null || prefs.personas.any { it.id == id }) { "找不到该人设" }
        val links = if (id == null) prefs.personaConnections - subject else prefs.personaConnections + (subject to id)
        host.persistPreferences(prefs.copy(personaConnections = links))
        host.mutable.update { it.copy(temporaryPersonaId = null) }
    }

    private fun decodeAvatar(uri: Uri) =
        resizeAvatar(requireNotNull(host.resolver.openInputStream(uri)) { "无法读取头像文件" }.use { it.readLimited() })

    internal fun resizeAvatar(image: ByteArray): ByteArray = normalizeAvatar(image, keepSize = false)

    private fun importCharacterPortrait(image: ByteArray): ByteArray =
        normalizeAvatar(image, keepSize = host.current.prefs.characterLibrary.keepAvatarSize)

    private fun normalizeAvatar(image: ByteArray, keepSize: Boolean): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "所选文件不是可用的图片" }
        if (keepSize && CardFile.isPng(image)) return image
        val sample = avatarDecodeSample(bounds.outWidth, bounds.outHeight, keepSize)
        val bitmap = requireNotNull(
            BitmapFactory.decodeByteArray(image, 0, image.size, BitmapFactory.Options().apply { inSampleSize = sample }),
        ) { "无法解码头像" }
        return try {
            val out = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "无法保存头像" }
            require(out.size() <= MAX_IMAGE_SOURCE) { "转换后的头像文件过大" }
            out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    fun saveCard(card: Card, done: () -> Unit) = host.work {
        require(card.name.isNotBlank()) { "请输入角色名称" }
        cardWrites.withLock { withContext(NonCancellable) {
            val cards = withContext(Dispatchers.IO) { host.store.save(card); host.store.cards() }
            host.mutable.update { it.copy(cards = cards) }
        } }
        host.eventBus.emit(TavernCoreEvent.CHARACTER_EDITED, jsonOf("card_id" to card.id, "name" to card.name))
        done()
    }

    internal suspend fun saveScriptCard(id: String?, changes: ScriptCardChanges, portrait: ByteArray?): Card = cardWrites.withLock {
        val original = id?.let { key -> checkNotNull(host.current.cards.firstOrNull { it.id == key }) { "找不到要更新的角色" } }
        val edited = original?.let(changes::apply) ?: changes.create()
        val saved = edited.copy(hasAvatar = edited.hasAvatar || portrait != null)
        require(saved.name.isNotBlank()) { "请输入角色名称" }
        val library = host.current.prefs.library.materialized(host.current.cards)
        val key = libraryCardId(saved.id)
        withContext(NonCancellable) {
            val cards = withContext(Dispatchers.IO) {
                if (original == null) host.store.import(CardImport(saved, portrait), emptyList())
                else host.store.replaceCard(saved, portrait)
                host.store.cards()
            }
            val assignments = if (original == null) library.assignments + (key to emptySet<String>()) else library.assignments
            val favorites = changes.favorite?.let { library.favorites + (key to it) } ?: library.favorites
            persistLibrary(library.copy(assignments = assignments, favorites = favorites))
            host.mutable.update { it.copy(cards = cards) }
        }
        saved
    }

    private suspend fun persistLibrary(library: LibraryPreferences) {
        if (library != host.current.prefs.library) host.persistPreferences(host.current.prefs.copy(library = library))
    }

    fun sortLibrary(sort: LibrarySort) = host.work { persistLibrary(host.current.prefs.library.copy(sort = sort)) }

    fun setCharacterLibrary(options: CharacterLibraryOptions) = host.work {
        host.persistPreferences(host.current.prefs.copy(characterLibrary = options))
    }

    fun showLibraryFolders(show: Boolean) = host.work { persistLibrary(host.current.prefs.library.copy(folders = show)) }

    fun toggleLibraryFavorite(id: String) = host.work {
        val library = host.current.prefs.library
        val item = library.entities(host.current.cards, host.current.groups).firstOrNull { it.id == id } ?: return@work
        persistLibrary(library.copy(favorites = library.favorites + (id to !item.favorite)))
    }

    fun saveLibraryTag(tag: LibraryTag, done: () -> Unit) = host.work {
        val library = host.current.prefs.library.materialized(host.current.cards)
        val name = tag.name.trim()
        require(name.isNotEmpty()) { "请输入标签名称" }
        require(library.tags.none { it.id != tag.id && it.name.equals(name, true) }) { "同名标签已存在" }
        val value = tag.copy(name = name, color = tag.color.trim(), textColor = tag.textColor.trim())
        val tags = library.tags.toMutableList()
        val index = tags.indexOfFirst { it.id == value.id }
        val createsFolder = value.folder != TagFolder.NONE && (tags.getOrNull(index)?.folder ?: TagFolder.NONE) == TagFolder.NONE
        if (index < 0) tags += value else tags[index] = value
        persistLibrary(library.copy(tags = tags, folders = library.folders || createsFolder))
        done()
    }

    fun moveLibraryTag(id: String, direction: Int) = host.work {
        val library = host.current.prefs.library.materialized(host.current.cards)
        val tags = library.tags.toMutableList()
        val index = tags.indexOfFirst { it.id == id }
        val target = index + direction
        if (index < 0 || target !in tags.indices) return@work
        tags.add(target, tags.removeAt(index))
        persistLibrary(library.copy(tags = tags))
    }

    fun removeLibraryTag(id: String) = host.work {
        val library = host.current.prefs.library.materialized(host.current.cards)
        persistLibrary(library.copy(
            tags = library.tags.filterNot { it.id == id },
            assignments = library.assignments.mapValues { (_, ids) -> ids - id },
        ))
    }

    fun assignLibraryTags(owners: Set<String>, changes: Map<String, Boolean>, newName: String, done: () -> Unit) = host.work {
        val library = host.current.prefs.library.materialized(host.current.cards)
        val targets = library.entities(host.current.cards, host.current.groups).mapTo(hashSetOf(), LibraryItem::id).intersect(owners)
        if (targets.isEmpty()) return@work
        val tags = library.tags.toMutableList()
        val tagIds = tags.mapTo(hashSetOf(), LibraryTag::id)
        val add = changes.filterValues { it }.keys.intersect(tagIds).toMutableSet()
        val remove = changes.filterValues { !it }.keys
        newName.trim().takeIf(String::isNotBlank)?.let { name ->
            val tag = tags.firstOrNull { it.name.equals(name, true) } ?: LibraryTag(name = name).also(tags::add)
            add += tag.id
        }
        val links = library.assignments.toMutableMap()
        targets.forEach { id -> links[id] = (links[id].orEmpty() - remove) + add }
        persistLibrary(library.copy(tags = tags, assignments = links))
        done()
    }

    fun exportLibraryTags(uri: Uri) = host.work {
        val library = host.current.prefs.library.materialized(host.current.cards)
        val entries = library.entities(host.current.cards, host.current.groups)
        withContext(Dispatchers.IO) { host.writeUri(uri, library.tagBackup(entries).toString(2).encodeToByteArray()) }
        host.notice("标签备份已导出")
    }

    fun importLibraryTags(uri: Uri) = host.work {
        val library = host.current.prefs.library.materialized(host.current.cards)
        val existing = library.entities(host.current.cards, host.current.groups)
        val byId = existing.associateBy(LibraryItem::id)
        val byTitle = existing.groupBy { (it.card != null) to it.name }
        val backup = withContext(Dispatchers.IO) {
            val bytes = requireNotNull(host.resolver.openInputStream(uri)) { "无法打开标签备份" }.use { it.readLimited() }
            boundedJsonObject(bytes.decodeToString().trimStart('\uFEFF'), "无法读取标签 JSON")
        }
        require(backup.optJSONArray("tags") != null) { "标签备份缺少 tags 列表" }
        val names = backup.optJSONObject("native_owners")
        val imported = library.mergeTags(backup, ownerFor = { id ->
            byId[id]?.id ?: when {
                names?.has(id) == true -> byTitle[(id.startsWith("card:")) to names.string(id)]?.singleOrNull()?.id
                id.endsWith(".png", true) -> byId[libraryCardId(previewCharacterId(id))]?.id
                else -> byId[libraryGroupId(previewGroupId(id))]?.id
            }
        })
        persistLibrary(imported.preferences.copy(folders = library.folders || backup.optBoolean("folders")))
        host.notice("已导入 ${imported.added} 个新标签" +
            if (imported.unmatched > 0) "，${imported.unmatched} 个标签关联未找到对应角色或群组" else "")
    }

    fun addExample() = host.work {
        val example = Card.from(jsonOf(
            "name" to "岚",
            "description" to "经营着海边山坡上的一间旧书店。她会给每本修好的书夹上一张手写的纸条。",
            "personality" to "好奇、敏锐，习惯把重要的事情说得轻描淡写。",
            "scenario" to "暴雨淹没小镇的海堤。书店快打烊了，窗外的路灯忽明忽暗。",
            "first_mes" to "*门铃轻响。岚把摊在账台上的地图折好，抬眼望过来。*\n\n「进来吧，{{user}}。你是来躲雨的，还是在找那本从目录上消失的书？」",
        ))
        val cards = withContext(Dispatchers.IO) { host.store.save(example); host.store.cards() }
        host.mutable.update { it.copy(cards = cards, cardId = example.id, chat = null, history = emptyList()) }
    }

    fun importCard(uri: Uri) = importCards(listOf(uri))

    private fun characterFiles(
        input: InputStream,
        name: String,
        userName: String,
        includeExtras: Boolean = true,
    ): List<ImportedCharacterArchive> =
        readCharacterFiles(input, name, host.app.cacheDir, userName, ::importCharacterPortrait, host.imageStore::import, includeExtras)

    private fun externalImporter(userName: String, includeExtras: Boolean = true) = ExternalContentImporter(
        { input, name -> characterFiles(input, name, userName, includeExtras) },
        ::importCharacterPortrait,
    )

    fun importCards(uris: List<Uri>, mode: CardTagImport = CardTagImport.ALL) = host.work {
        if (uris.isEmpty()) return@work
        val before = host.current
        val userName = before.prefs.personaFor().name
        var failures = 0
        var chats = 0
        var skippedPictures = 0
        var firstFailure: String? = null
        val (cards, count) = withContext(Dispatchers.IO) {
            var added = 0
            for (uri in uris.distinct()) {
                currentCoroutineContext().ensureActive()
                val name = host.app.documentName(uri).takeUnless { it == "导入的聊天" } ?: "角色卡文件"
                try {
                    val entries = requireNotNull(host.resolver.openInputStream(uri)) { "无法打开角色卡" }.use { input ->
                        characterFiles(input, name, userName)
                    }
                    val imported = if (entries.size == 1 && name.substringAfterLast('.').lowercase() in setOf("png", "json")) entries.map { entry ->
                        val card = entry.card.card
                        val identified = if (card.scriptAvatarKey == "${card.id}.png") {
                            card.withScriptAvatarKey(name.substringBeforeLast('.') + ".png")
                        } else card
                        entry.copy(card = entry.card.copy(card = identified))
                    } else entries
                    if (imported.all { it.card.portrait == null && it.chats.isEmpty() && it.pictures.isEmpty() }) {
                        added += host.store.importCharacters(imported.map { it.card.card })
                        skippedPictures += imported.sumOf(ImportedCharacterArchive::skippedImages)
                    } else {
                        for (archive in imported) {
                            chats += host.store.import(archive.card, archive.chats, archive.pictures)
                            skippedPictures += archive.skippedImages
                            added++
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++
                    if (firstFailure == null) {
                        firstFailure = "${name.take(100)} · ${(e.localizedMessage ?: "无法读取角色卡").take(160)}"
                    }
                }
            }
            host.store.cards() to added
        }
        updateImportedCards(before.cards, cards, mode)
        val summary = buildString {
            append("已导入 $count 张角色卡")
            if (chats > 0) append("、$chats 个聊天")
            if (skippedPictures > 0) append("，$skippedPictures 张素材图片未能读取")
        }
        host.notice(if (failures == 0) summary else "$summary，$failures 个文件失败。$firstFailure")
    }

    fun importExternalContent(addresses: String, mode: CardTagImport): Boolean = host.utilityWork("正在导入分享链接…") {
        val rows = addresses.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        require(rows.isNotEmpty()) { "请输入角色或世界书的分享链接" }
        val before = host.current
        val importer = externalImporter(before.prefs.personaFor().name)
        val visited = hashSetOf<Pair<ExternalContentSite, String>>()
        var importedCards = 0
        var importedBooks = 0
        var chats = 0
        var skippedImages = 0
        var failures = 0
        var firstFailure: String? = null
        val statusCode = Regex("HTTP [1-5][0-9]{2}")
        try {
            for ((index, line) in rows.withIndex()) {
                currentCoroutineContext().ensureActive()
                try {
                    val source = parseExternalContentSource(line)
                    if (!visited.add(source.site to source.id)) continue
                    val payload = importer.load(source)
                    withContext(Dispatchers.IO) {
                        when (payload) {
                            is ExternalContent.Characters -> payload.cards.forEach { entry ->
                                chats += host.store.import(entry.card, entry.chats, entry.pictures)
                                importedCards++
                                skippedImages += entry.skippedImages
                            }
                            is ExternalContent.Book -> {
                                host.world.saveImportedBook(payload.book, host.store.books().map(WorldBook::name))
                                importedBooks++
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (error: Exception) {
                    failures++
                    if (firstFailure == null) {
                        val status = statusCode.find(error.message.orEmpty())?.value
                        firstFailure = "第 ${index + 1} 行未能读取" + if (status == null) "" else "（$status）"
                    }
                }
            }
        } finally {
            if (importedCards + importedBooks > 0) withContext(NonCancellable) {
                val (cards, books) = withContext(Dispatchers.IO) { host.store.cards() to host.store.books() }
                updateImportedCards(before.cards, cards, mode)
                host.mutable.update { it.copy(books = books) }
            }
        }
        val summary = buildString {
            append("已导入 $importedCards 张角色卡、$importedBooks 本世界书")
            if (chats > 0) append("、$chats 个聊天")
            if (skippedImages > 0) append("，$skippedImages 张图片未能读取")
            if (failures > 0) append("，$failures 个链接失败。$firstFailure")
        }
        host.notice(summary)
    }

    private suspend fun updateImportedCards(before: List<Card>, cards: List<Card>, mode: CardTagImport) {
        val originalIds = before.mapTo(hashSetOf(), Card::id)
        val state = host.current
        val imported = cards.filterNot { it.id in originalIds }
        val updated = state.prefs.library.applyCardTagImport(before, imported, mode)
        val offered = if (!state.prefs.characterLibrary.askToImportLore) emptyList() else withContext(Dispatchers.Default) {
            imported.filter { runCatching { it.embeddedWorldBook() != null }.getOrDefault(false) }.map(Card::id)
        }
        if (updated != state.prefs.library) {
            val prefs = state.prefs.copy(library = updated)
            withContext(Dispatchers.IO) { host.store.save(prefs) }
            host.mutable.update { it.copy(cards = cards, prefs = prefs, offeredCardLore = offered) }
        } else {
            host.mutable.update { it.copy(cards = cards, offeredCardLore = offered) }
        }
    }

    fun updateCardFromLink(card: Card, address: String): Boolean = host.utilityWork("正在更新角色卡…") {
        val source = parseExternalContentSource(address)
        require(source.site != ExternalContentSite.CHUB_BOOK) { "请选择角色卡分享链接" }
        val downloaded = externalImporter(host.current.persona.name, includeExtras = false).load(source) as ExternalContent.Characters
        updateCardDefinitions(card, downloaded.cards)
    }

    fun updateCardFromFile(card: Card, uri: Uri) = host.utilityWork("正在读取角色卡…") {
        val name = host.app.documentName(uri)
        val persona = host.current.persona.name
        val cards = withContext(Dispatchers.IO) {
            requireNotNull(host.resolver.openInputStream(uri)) { "无法打开角色卡" }
                .use { characterFiles(it, name, persona, includeExtras = false) }
        }
        updateCardDefinitions(card, cards)
    }

    private suspend fun updateCardDefinitions(card: Card, entries: List<ImportedCharacterArchive>) {
        val downloaded = entries.singleOrNull() ?: error("选择一张角色卡来更新当前角色")
        val value = downloaded.card
        var updated = value.card.copy(id = card.id, hasAvatar = value.portrait != null || card.hasAvatar)
            .edited(mapOf("name" to card.name), value.card.alternateGreetings)
            .withWorld(card.worldName).withAdditionalWorldBooks(card.additionalWorldBookIds)
            .withAuthorNote(card.authorNote).withLibraryFavorite(card.libraryFavorite)
            .withScriptAvatarKey(card.scriptAvatarKey)
        card.expressionSprites?.let { updated = updated.withExpressionSprites(it) }
        if (updated.sourceLink == null) card.sourceLink?.let { updated = updated.withSourceLink(it) }
        withContext(NonCancellable) {
            val cards = withContext(Dispatchers.IO) { host.store.replaceCard(updated, value.portrait); host.store.cards() }
            host.mutable.update { it.copy(cards = cards) }
        }
        host.notice("角色设定已更新")
    }

    fun duplicateCard(card: Card) = host.work {
        val copy = copyCard(card, keepName = false)
        host.mutable.update { it.copy(cardId = copy.id, groupId = null, chat = null, history = emptyList()) }
        host.notice("已创建角色副本")
    }

    internal suspend fun copyCard(card: Card, keepName: Boolean): Card = cardWrites.withLock {
        val source = checkNotNull(host.current.cards.firstOrNull { it.id == card.id }) { "角色已不存在" }
        val names = host.current.cards.mapTo(hashSetOf()) { it.name.lowercase() }
        val base = "${source.name} · 副本"
        val name = if (keepName) source.name else generateSequence(1) { it + 1 }
            .map { if (it == 1) base else "$base $it" }.first { it.lowercase() !in names }
        val id = newId()
        val copy = source.copy(id = id).edited(mapOf("name" to name), source.alternateGreetings).withScriptAvatarKey("$id.png")
        val library = host.current.prefs.library.materialized(host.current.cards)
        val tags = library.assignments[libraryCardId(source.id)].orEmpty()
        withContext(NonCancellable) {
            val cards = withContext(Dispatchers.IO) {
                val portrait = if (source.hasAvatar) host.store.portraitFile(source.id).inputStream().use { it.readLimited() } else null
                host.store.import(CardImport(copy, portrait), emptyList())
                host.store.cards()
            }
            persistLibrary(library.copy(assignments = library.assignments + (libraryCardId(id) to tags)))
            host.mutable.update { it.copy(cards = cards) }
        }
        copy
    }

    fun deleteCard(card: Card) = host.work { removeCard(card, deleteChats = true) }

    internal suspend fun removeCard(card: Card, deleteChats: Boolean) = cardWrites.withLock {
        groupWrites.withLock {
            withContext(NonCancellable) {
                val active = host.current.chat?.takeIf { it.cardId == card.id }
                val archive = TavernGroup(name = "${card.name} · 已归档", order = ReplyOrder.MANUAL).takeUnless { deleteChats }
                val (retained, cards, groups) = withContext(Dispatchers.IO) {
                    val result = host.store.deleteCard(card.id, archive, card.worldName)
                    Triple(result, host.store.cards(), host.store.groups())
                }
                host.mutable.update { current ->
                    val library = current.copy(cards = cards, groups = groups)
                    if (current.cardId == card.id) library.copy(chat = null, cardId = null, history = emptyList()) else library
                }
                if (retained != null && active != null) {
                    val (opened, history) = withContext(Dispatchers.IO) {
                        host.store.chat(active.id) to host.store.chats(groupId = retained.id)
                    }
                    host.showChat(opened, history)
                }
                persistLibrary(host.current.prefs.library.removeOwner(libraryCardId(card.id)))
                host.refreshRecentChats()
                host.refreshLibraryUsage()
                if (retained != null) host.notice("聊天已保存在群组「${retained.name}」")
            }
        }
    }

    fun exportCard(uri: Uri, card: Card) = host.work {
        val primary = host.current.books.firstOrNull { it.name == card.worldName }
        val names = host.current.cards.associate { it.id to it.scriptAvatarKey.loreFilenameStem() }
        withContext(Dispatchers.IO) {
            val portable = primary?.let { card.withCharacterBook(it.remapLoreFilters(names).characterBook()) } ?: card
            val payload = if (card.hasAvatar) CardFile.png(portable, host.store.portraitFile(card.id).readBytes())
            else portable.portableJson().toString(2).toByteArray(Charsets.UTF_8)
            host.writeUri(uri, payload)
        }
        host.notice("角色卡已导出")
    }

    fun openCard(card: Card) = host.work {
        val history = withContext(Dispatchers.IO) { host.store.chats(card.id) }
        host.discardTemporaryChat()
        host.mutable.update { it.copy(cardId = card.id, groupId = null, chat = null, history = history) }
    }

    fun closeSubject() {
        host.generation.stopAuto()
        host.discardTemporaryChat()
        host.mutable.update { it.copy(cardId = null, groupId = null, chat = null, history = emptyList(), temporaryPersonaId = null) }
    }

    fun saveGroup(group: TavernGroup, done: () -> Unit) = host.work {
        val members = group.members(host.current.cards).map(Card::id)
        val name = group.name.trim()
        require(name.isNotBlank() && members.isNotEmpty()) { "请输入群组名称并选择至少一个角色" }
        persistGroup(group.copy(name = name, memberIds = members, mutedIds = group.mutedIds.intersect(members.toSet())))
        done()
    }

    private suspend fun persistGroup(group: TavernGroup) = groupWrites.withLock {
        withContext(NonCancellable) {
            val groups = withContext(Dispatchers.IO) { host.store.save(group); host.store.groups() }
            host.mutable.update { it.copy(groups = groups) }
        }
    }

    internal suspend fun updateGroupRoster(id: String, change: (TavernGroup) -> TavernGroup): Boolean = groupWrites.withLock {
        withContext(NonCancellable) {
            val (changed, groups) = withContext(Dispatchers.IO) {
                host.store.updateGroupRoster(id, change) to host.store.groups()
            }
            host.mutable.update { it.copy(groups = groups) }
            changed
        }
    }

    fun openGroup(group: TavernGroup) = host.work {
        val history = withContext(Dispatchers.IO) { host.store.chats(groupId = group.id) }
        host.discardTemporaryChat()
        host.mutable.update { it.copy(cardId = null, groupId = group.id, chat = null, history = history) }
    }

    fun deleteGroup(group: TavernGroup) = host.work {
        val groups = withContext(Dispatchers.IO) { host.store.deleteGroup(group.id); host.store.groups() }
        host.mutable.update { it.copy(groups = groups, groupId = null, chat = null, history = emptyList()) }
        persistLibrary(host.current.prefs.library.removeOwner(libraryGroupId(group.id)))
    }

    fun openFavoriteConversation(item: LibraryItem) = host.work {
        val state = host.current
        val card = item.card?.let { favorite -> state.cards.firstOrNull { it.id == favorite.id } }
        val group = item.group?.let { favorite -> state.groups.firstOrNull { it.id == favorite.id } }
        if (card == null && group == null) return@work
        val active = state.chat
        if (active != null && !active.isTemporary &&
            (card != null && card.id == active.cardId || group != null && group.id == active.groupId)) return@work
        host.generation.stopAuto()
        val newest = withContext(Dispatchers.IO) { host.store.chats(card?.id, group?.id, limit = 1).firstOrNull() }
        when {
            newest != null -> host.chats.openStoredChat(newest.id)
            card != null -> host.chats.startChat(card, card.greeting)
            group != null -> startGroupChat(group)
            else -> Unit
        }
    }

    fun newGroupChat(group: TavernGroup) = host.work { startGroupChat(group) }

    private suspend fun startGroupChat(group: TavernGroup) {
        val state = host.current
        val prefs = state.prefs
        val history = if (state.groupId == group.id) state.history else withContext(Dispatchers.IO) { host.store.chats(groupId = group.id) }
        val title = if (history.isEmpty()) "新群聊" else "新群聊 · ${history.size + 1}"
        val chat = Chat(
            newId(), null, title, groupId = group.id,
            options = ConversationOptions(authorNote = prefs.defaultAuthorNote),
        )
        val preset = state.copy(cardId = null, groupId = group.id).presetFor(chat)
        val (messages, changes) = withContext(Dispatchers.Default) { group.openingMessages(chat, state.cards, prefs, preset) }
        val options = chat.options.copy(variables = chat.options.variables.withVariableChanges(changes.local))
        val globals = changes.global.takeIf { it.isNotEmpty() }?.let { host.current.prefs.scriptVariables.withVariableChanges(it) }
        withContext(NonCancellable) {
            host.persist(chat.copy(title = chat.availableTitle(history), messages = messages, options = options), scriptGlobals = globals)
        }
        host.script.quickEvent(QuickEvent.NEW)
        host.script.quickEvent(QuickEvent.CHAT)
    }
}
