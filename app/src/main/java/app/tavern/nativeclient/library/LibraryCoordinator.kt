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
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

internal class LibraryCoordinator(private val host: CoordinatorHost) {
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

    private fun resizeAvatar(image: ByteArray): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "所选文件不是可用的图片" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 640) sample *= 2
        val bitmap = requireNotNull(
            BitmapFactory.decodeByteArray(image, 0, image.size, BitmapFactory.Options().apply { inSampleSize = sample }),
        ) { "无法解码头像" }
        return try {
            val out = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "无法保存头像" }
            out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    fun saveCard(card: Card, done: () -> Unit) = host.work {
        require(card.name.isNotBlank()) { "请输入角色名称" }
        val cards = withContext(Dispatchers.IO) { host.store.save(card); host.store.cards() }
        host.mutable.update { it.copy(cards = cards) }
        done()
    }

    private suspend fun persistLibrary(library: LibraryPreferences) {
        if (library != host.current.prefs.library) host.persistPreferences(host.current.prefs.copy(library = library))
    }

    fun sortLibrary(sort: LibrarySort) = host.work { persistLibrary(host.current.prefs.library.copy(sort = sort)) }

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
        readCharacterFiles(input, name, host.app.cacheDir, userName, ::resizeAvatar, host.imageStore::import, includeExtras)

    private fun externalImporter(userName: String, includeExtras: Boolean = true) = ExternalContentImporter(
        { input, name -> characterFiles(input, name, userName, includeExtras) },
        ::resizeAvatar,
    )

    fun importCards(uris: List<Uri>) = host.work {
        if (uris.isEmpty()) return@work
        val userName = host.current.prefs.personaFor().name
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
                    val imported = requireNotNull(host.resolver.openInputStream(uri)) { "无法打开角色卡" }.use { input ->
                        characterFiles(input, name, userName)
                    }
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
        host.mutable.update { it.copy(cards = cards) }
        val summary = buildString {
            append("已导入 $count 张角色卡")
            if (chats > 0) append("、$chats 个聊天")
            if (skippedPictures > 0) append("，$skippedPictures 张素材图片未能读取")
        }
        host.notice(if (failures == 0) summary else "$summary，$failures 个文件失败。$firstFailure")
    }

    fun importExternalContent(addresses: String): Boolean = host.utilityWork("正在导入分享链接…") {
        val rows = addresses.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        require(rows.isNotEmpty()) { "请输入角色或世界书的分享链接" }
        val importer = externalImporter(host.current.prefs.personaFor().name)
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
                host.mutable.update { it.copy(cards = cards, books = books) }
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
            .withWorld(card.worldName).withAuthorNote(card.authorNote).withLibraryFavorite(card.libraryFavorite)
        card.expressionSprites?.let { updated = updated.withExpressionSprites(it) }
        if (updated.sourceLink == null) card.sourceLink?.let { updated = updated.withSourceLink(it) }
        withContext(NonCancellable) {
            val cards = withContext(Dispatchers.IO) { host.store.replaceCard(updated, value.portrait); host.store.cards() }
            host.mutable.update { it.copy(cards = cards) }
        }
        host.notice("角色设定已更新")
    }

    fun duplicateCard(card: Card) = host.work {
        val names = host.current.cards.mapTo(hashSetOf()) { it.name.lowercase() }
        val base = "${card.name} · 副本"
        val name = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }
            .first { it.lowercase() !in names }
        val id = newId()
        val cards = withContext(Dispatchers.IO) {
            val portrait = if (card.hasAvatar) host.store.portraitFile(card.id).inputStream().use { it.readLimited() } else null
            val copy = card.copy(id = id).edited(mapOf("name" to name), card.alternateGreetings)
            host.store.import(CardImport(copy, portrait), emptyList())
            host.store.cards()
        }
        val library = host.current.prefs.library.materialized(host.current.cards)
        val tags = library.assignments[libraryCardId(card.id)].orEmpty()
        host.mutable.update { it.copy(cards = cards, cardId = id, groupId = null, chat = null, history = emptyList()) }
        if (tags.isNotEmpty()) persistLibrary(library.copy(assignments = library.assignments + (libraryCardId(id) to tags)))
        host.notice("已创建角色副本")
    }

    fun deleteCard(card: Card) = host.work {
        val (cards, groups) = withContext(Dispatchers.IO) { host.store.deleteCard(card.id); host.store.cards() to host.store.groups() }
        host.mutable.update { it.copy(cards = cards, groups = groups, chat = null, cardId = null, history = emptyList()) }
        persistLibrary(host.current.prefs.library.removeOwner(libraryCardId(card.id)))
    }

    fun exportCard(uri: Uri, card: Card) = host.work {
        withContext(Dispatchers.IO) {
            val payload = if (card.hasAvatar) CardFile.png(card, host.store.portraitFile(card.id).readBytes())
            else card.portableJson().toString(2).toByteArray(Charsets.UTF_8)
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
        val saved = group.copy(name = name, memberIds = members, mutedIds = group.mutedIds.intersect(members.toSet()))
        val groups = withContext(Dispatchers.IO) { host.store.save(saved); host.store.groups() }
        host.mutable.update { it.copy(groups = groups) }
        done()
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

    fun newGroupChat(group: TavernGroup) = host.work {
        val title = if (host.current.history.isEmpty()) "新群聊" else "新群聊 · ${host.current.history.size + 1}"
        host.persist(Chat(
            newId(), null, title, groupId = group.id,
            options = ConversationOptions(authorNote = host.current.prefs.defaultAuthorNote),
        ))
        host.script.quickEvent(QuickEvent.NEW)
        host.script.quickEvent(QuickEvent.CHAT)
    }
}
