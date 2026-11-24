package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal const val NATIVE_BACKUP_KEY = "tavern_native_account_backup"
internal const val NATIVE_BACKUP_ID = "tavern_native_backup_id"
internal const val NATIVE_BACKUP_IMAGES = "user/images/tavern-native-backup"
private const val BACKUP_ROOT = "data/tavern-native"
private const val BACKUP_ENTRY_LIMIT = 24 * 1024 * 1024
private val backupBlankPortrait = Base64.getDecoder().decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNgYAAAAAMAASsJTYQAAAAASUVORK5CYII=")
private val windowsDevice = Regex("(?i)(?:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?")

internal data class BackupExportResult(
    val cards: Int,
    val groups: Int,
    val chats: Int,
    val books: Int,
    val missing: Int,
    val personaAvatars: Int = 0,
    val mediaFiles: Int = 0,
)

internal fun writeOriginalBackup(
    output: OutputStream, store: TavernStore, documents: ChatDocumentStore, images: ChatImageStore,
    videos: ChatVideoStore? = null,
    checkActive: () -> Unit,
): BackupExportResult = ZipOutputStream(output).use { zip ->
    OriginalBackupWriter(zip, store, documents, images, videos, checkActive).write()
}

private class BackupNames {
    private val used = mutableSetOf<String>()

    fun unique(title: String): String {
        val value = title.filterNot(Char::isISOControl).replace(Regex("[\\\\/:*?\"<>|]"), "_").take(76)
            .dropLastWhile(Char::isHighSurrogate).trim().trimEnd('.')
        val base = value.takeUnless { it.isBlank() || windowsDevice.matches(it) } ?: "tavern"
        return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base ($it)" }
            .first { used.add(it.lowercase(Locale.ROOT)) }
    }
}

private fun backupId(kind: String, id: String) = "$kind-${UUID.nameUUIDFromBytes(id.encodeToByteArray())}"
internal fun portableAccountId(value: String?): String? = value?.takeIf {
    it.length == 36 && runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false)
}

internal fun DrawnImageSeries.availableBackupImages(available: (ChatImage) -> Boolean): DrawnImageSeries? {
    val kept = images.withIndex().filter { available(it.value) }
    if (kept.isEmpty()) return null
    return copy(recipe = recipe.copy(references = recipe.references.filter(available)),
        images = kept.map { it.value }, selected = kept.indexOfLast { it.index <= selected }.coerceAtLeast(0))
}
private fun quickConfig(names: List<String>) = jsonOf("setList" to JSONArray(names.map { jsonOf("set" to it, "isVisible" to true) }))

private class OriginalBackupWriter(
    private val zip: ZipOutputStream,
    private val store: TavernStore,
    private val documents: ChatDocumentStore,
    private val images: ChatImageStore,
    private val videos: ChatVideoStore?,
    private val checkActive: () -> Unit,
) {
    private val prefs = store.prefs()
    private val cards = store.cards()
    private val groups = store.groups()
    private val books = store.books()
    private val presets = store.promptPresets()
    private val data = store.dataDocuments().groupBy(DataDocument::owner)
    private val library = prefs.library.materialized(cards)
    private val cardsById = cards.associateBy(Card::id)
    private val avatars = cards.associate { it.id to "${backupId("character", it.id)}.png" }
    private val groupNames = groups.associate { it.id to backupId("group", it.id) }
    private val personaFiles = prefs.personas.associate { it.id to "${backupId("persona", it.id)}.png" }
    private val bookNames = BackupNames().let { names -> books.associate { it.id to names.unique(it.name) } }
    private val presetNames = BackupNames().let { names -> presets.associate { it.id to names.unique(it.name) } }
    private val quickNames = prefs.quickReplies.sets.associate { it.id to it.name }
    private val galleryNames = (cards.map(Card::name) + groups.map(TavernGroup::name)).groupingBy { it.lowercase(Locale.ROOT) }.eachCount()
    private val disabledData = JSONArray()
    private val media = mutableMapOf<String, Boolean>()
    private var missing = 0
    private var chatCount = 0
    private var personaAvatarCount = 0

    fun write(): BackupExportResult {
        val filterNames = cards.uniqueLoreFilenames().mapValues { (_, id) -> avatars.getValue(id).loreFilenameStem() } +
            avatars.mapValues { (_, filename) -> filename.loreFilenameStem() }
        for (book in books) addJson("worlds/${bookNames.getValue(book.id)}.json",
            JSONObject(book.remapLoreFilters(filterNames).export().decodeToString())
                .put("tavern_native_backup_name", book.name).put(NATIVE_BACKUP_ID, book.id))
        for (preset in presets) addJson("OpenAI Settings/${presetNames.getValue(preset.id)}.json",
            preset.json().put(NATIVE_BACKUP_ID, preset.id))
        val quickFiles = BackupNames()
        for (set in prefs.quickReplies.sets) addJson("QuickReplies/${quickFiles.unique(set.name)}.json", set.json().put(NATIVE_BACKUP_ID, set.id))
        val themeFiles = BackupNames()
        for (theme in prefs.chatThemes.items) addJson("themes/${themeFiles.unique(theme.name)}.json", theme.json())
        for (card in cards) writeCard(card)
        val groupChats = BackupNames()
        for (group in groups) writeGroup(group, groupChats)
        addJson("settings.json", settings().also(::writeBackgrounds))
        return BackupExportResult(
            cards = cards.size,
            groups = groups.size,
            chats = chatCount,
            books = books.size,
            missing = missing,
            personaAvatars = personaAvatarCount,
            mediaFiles = media.values.count { it } + personaAvatarCount,
        )
    }

    private fun writeCard(card: Card) {
        checkActive()
        val filename = avatars.getValue(card.id)
        val world = books.firstOrNull { it.name == card.worldName }?.id?.let(bookNames::get)
        val portrait = if (card.hasAvatar) store.portraitFile(card.id).takeIf { it.isFile }?.inputStream()?.use { it.readLimited() } else null
        if (card.hasAvatar && portrait == null) missing++
        val root = card.withWorld(world.orEmpty()).withLibraryFavorite(library.favorites[libraryCardId(card.id)] ?: card.libraryFavorite)
            .portableJson()
        val extensions = root.getJSONObject("data").optJSONObject("extensions") ?: JSONObject().also {
            root.getJSONObject("data").put("extensions", it)
        }
        extensions.put(NATIVE_BACKUP_ID, card.id)
        if (portrait == null) extensions.put("tavern_native_placeholder_avatar", true)
        else extensions.remove("tavern_native_placeholder_avatar")
        val portable = Card.from(root, card.id).let { copy ->
            card.expressionSprites?.let { copy.withExpressionSprites(it.filter { sprite -> writeImage(sprite.image) }) } ?: copy
        }
        add("characters/$filename", CardFile.png(portable, portrait ?: backupBlankPortrait))
        val stem = filename.removeSuffix(".png")
        writeGallery(stem, card.name, store.gallery(card.id, null))
        writeChats(store.chats(cardId = card.id), BackupNames(), "chats/$stem", card.name, card, null)
    }

    private fun writeGroup(group: TavernGroup, names: BackupNames) {
        val key = groupNames.getValue(group.id)
        val chatFiles = writeChats(store.chats(groupId = group.id), names, "group chats", group.name, null, group)
        val original = group.copy(memberIds = group.memberIds.mapNotNull(avatars::get),
            mutedIds = group.mutedIds.mapNotNull(avatars::get).toSet(),
            favorite = library.favorites[libraryGroupId(group.id)] ?: group.favorite).json()
        original.put("id", key).put(NATIVE_BACKUP_ID, group.id)
            .put("chats", JSONArray(chatFiles)).put("chat_id", chatFiles.firstOrNull().orEmpty())
        addJson("groups/$key.json", original)
        writeGallery(key, group.name, store.gallery(null, group.id))
    }

    private fun writeChats(
        summaries: List<ChatSummary>, names: BackupNames, folder: String, subject: String, card: Card?, group: TavernGroup?,
    ): List<String> {
        val files = summaries.sortedBy(ChatSummary::id).associate { it.id to names.unique(it.title) }
        val members = group?.members(cards).orEmpty()
        fun pointer(value: ChatPointer): ChatPointer {
            val id = value.resolve(summaries)?.id ?: return value.copy(id = null)
            val target = files.getValue(id)
            return ChatPointer(target, portableAccountId(id) ?: "$target.jsonl")
        }
        for (summary in summaries) {
            checkActive()
            val chat = store.chat(summary.id)
            val persona = prefs.personaFor(chat)
            val world = chat.options.worldBook(books)?.id?.let(bookNames::get).orEmpty()
            val quick = (chat.options.quickReplyNames + chat.options.quickReplySets.mapNotNull(quickNames::get)).distinct()
            val options = chat.options.copy(worldBookId = "", worldBookName = world,
                personaId = null, parent = chat.options.parent?.let(::pointer),
                promptPresetId = chat.options.promptPresetId?.let {
                    if (it.isEmpty()) it else portableAccountId(it) ?: presetNames[it]?.plus(".json")
                },
                quickReplySets = emptyList(), quickReplyNames = quick,
                background = chat.options.background?.takeIf(::writeImage))
            val header = chat.header?.let(::JSONObject) ?: JSONObject()
            header.put("user_name", persona.name).put("character_name", subject)
            val metadata = header.optJSONObject("chat_metadata") ?: JSONObject().also { header.put("chat_metadata", it) }
            metadata.put("tavern_native_backup_title", chat.title).put(NATIVE_BACKUP_ID, chat.id)
            metadata.put("attachments", dataRows(DataOwner(DataScope.CHAT, chat.id)))
            metadata.remove("persona")
            chat.options.personaId?.let(personaFiles::get)?.let { metadata.put("persona", it) }
            metadata.remove("custom_background")
            options.background?.let { metadata.put("custom_background", "$NATIVE_BACKUP_IMAGES/${it.filename}") }
            val messages = chat.messages.map { message ->
                checkActive()
                val actor = message.characterId?.let(cardsById::get) ?: card
                    ?: members.singleOrNull { it.name.equals(message.name, true) }
                originalMessage(message.copy(checkpoint = message.checkpoint?.let(::pointer),
                    branches = message.branches.map(::pointer)), actor)
            }
            val export = chat.copy(options = options, header = header.toString(), messages = messages)
            add("$folder/${files.getValue(chat.id)}.jsonl", export.jsonl(subject, persona.name), chat.updated)
            chatCount++
        }
        return summaries.map { files.getValue(it.id) }
    }

    private fun originalMessage(input: Message, actor: Card?): Message {
        val message = input.withAvailableReplyImages(::writeImage)
        val added = message.images.filter(::writeImage)
        val attached = message.documents.filter(::writeDocument)
        val clips = message.videos.filter(::writeVideo)
        val drawing = message.drawing?.availableBackupImages(::writeImage)
        val raw = message.raw?.let(::JSONObject) ?: JSONObject()
        if (message.role == "assistant") actor?.id?.let(avatars::get)?.let { raw.put("original_avatar", it) }
        val extra = raw.optJSONObject("extra") ?: JSONObject().also { raw.put("extra", it) }
        val mediaRows = (added + message.replyMedia?.images.orEmpty() + listOfNotNull(drawing?.current)).distinctBy(ChatImage::id).map { image ->
            val caption = message.captionFor(image)
            jsonOf("url" to "$NATIVE_BACKUP_IMAGES/${image.filename}", "type" to "image", "title" to caption,
                "source" to if (caption == null) "embedded" else "captioned", "captioned" to (caption != null))
        }
        val videoRows = clips.map { jsonOf("url" to "$NATIVE_BACKUP_IMAGES/${it.filename}", "type" to "video", "title" to it.name) }
        if (videoRows.isNotEmpty() || mediaRows.isNotEmpty()) extra.put("media", JSONArray(mediaRows + videoRows))
        if (mediaRows.isNotEmpty()) extra.put("image", mediaRows.first().string("url"))
        if (attached.isNotEmpty()) extra.put("files", JSONArray(attached.map {
            attachment(it.name, "user/files/${it.filename}", it.bytes, 0)
        }))
        val displayed = (added + message.replyMedia?.images.orEmpty() + drawing?.images.orEmpty()).mapTo(hashSetOf(), ChatImage::id)
        return message.copy(raw = raw.toString(), images = added, documents = attached, videos = clips, drawing = drawing, characterId = null,
            personaId = message.personaId?.let(personaFiles::get),
            imageCaptions = message.imageCaptions.filterKeys(displayed::contains))
    }

    private fun settings(): JSONObject {
        val personas = JSONObject()
        val descriptions = JSONObject()
        for (persona in prefs.personas) {
            val filename = personaFiles.getValue(persona.id)
            val connections = prefs.personaConnections.filterValues { it == persona.id }.keys.mapNotNull { owner ->
                avatars[owner]?.let { jsonOf("type" to "character", "id" to it) }
                    ?: groupNames[owner]?.let { jsonOf("type" to "group", "id" to it) }
            }
            val portrait = persona.avatar?.let(store::personaAvatar)?.takeIf { it.isFile }?.inputStream()?.use { it.readLimited() }
            if (persona.avatar != null && portrait == null) missing++
            if (portrait != null) personaAvatarCount++
            add("User Avatars/$filename", portrait ?: backupBlankPortrait)
            personas.put(filename, persona.name)
            descriptions.put(filename, jsonOf("description" to persona.description, "title" to persona.title,
                "position" to persona.position.code, "role" to persona.role.ordinal, "depth" to persona.depth,
                "lorebook" to bookNames[persona.worldId].orEmpty(), "connections" to JSONArray(connections),
                NATIVE_BACKUP_ID to persona.id,
                "tavern_native_placeholder_avatar" to (portrait == null)))
        }
        val owners = cards.associate { libraryCardId(it.id) to avatars.getValue(it.id) } +
            groups.associate { libraryGroupId(it.id) to groupNames.getValue(it.id) }
        val tagMap = JSONObject()
        library.assignments.forEach { (key, ids) -> owners[key]?.let { tagMap.put(it, JSONArray(ids)) } }
        val characterData = JSONObject()
        for (card in cards) {
            val rows = dataRows(DataOwner(DataScope.CHARACTER, card.id))
            if (rows.length() > 0) characterData.put(avatars.getValue(card.id), rows)
        }
        val fallback = prefs.defaultAuthorNote
        val note = jsonOf("default" to fallback.text, "defaultInterval" to fallback.frequency, "defaultDepth" to fallback.depth,
            "defaultPosition" to fallback.position.ordinal, "defaultRole" to fallback.role.ordinal, "allowWIScan" to fallback.scan,
            "chara" to JSONArray(cards.filter { it.authorNote.text.isNotBlank() }.map {
                jsonOf("name" to avatars.getValue(it.id), "prompt" to it.authorNote.text, "useChara" to true,
                    "position" to it.authorNote.order.ordinal)
            }))
        val extensions = jsonOf("attachments" to dataRows(DataOwner(DataScope.GLOBAL)), "character_attachments" to characterData,
            "disabled_attachments" to disabledData, "note" to note, "regex" to JSONArray(prefs.regex.global.map(RegexRule::json)),
            "disabledExtensions" to JSONArray(if (prefs.regex.enabled) emptyList<String>() else listOf("regex")),
            "character_allowed_regex" to JSONArray(prefs.regex.allowedCards.mapNotNull(avatars::get)),
            "preset_allowed_regex" to jsonOf("openai" to JSONArray(prefs.regex.allowedPresets.mapNotNull(presetNames::get))),
            "variables" to jsonOf("global" to JSONObject(prefs.scriptVariables)),
            "quickReplyV2" to jsonOf("isEnabled" to prefs.quickReplies.enabled,
                "config" to quickConfig(prefs.quickReplies.active.mapNotNull(quickNames::get))),
            "tavern_native_extensions" to prefs.extensions.exportPortableJson())
        val defaultPersona = personaFiles[prefs.defaultPersonaId].orEmpty()
        return jsonOf(NATIVE_BACKUP_KEY to 1, "user_avatar" to defaultPersona, "swipes" to prefs.chatHandling.showSwipes,
            "power_user" to jsonOf("personas" to personas, "persona_descriptions" to descriptions,
                "default_persona" to defaultPersona, "bogus_folders" to library.folders,
                "tavern_native_chat_appearance" to prefs.chatAppearance.json(),
                "theme" to prefs.chatThemes.items.firstOrNull { it.id == prefs.chatThemes.activeId }?.name,
                "tavern_native_active_theme" to prefs.chatThemes.activeId,
                "tavern_native_theme_mode" to prefs.theme,
                "tavern_native_dynamic_color" to prefs.dynamicColor,
                "waifuMode" to prefs.visualNovel,
                "gestures" to prefs.swipeGestures,
                "show_group_chat_queue" to prefs.showGroupQueue,
                "prefer_character_prompt" to prefs.textSystem.preferCharacterPrompt,
                "prefer_character_jailbreak" to prefs.textSystem.preferCharacterPostHistory,
                "request_token_probabilities" to prefs.requestTokenProbabilities,
                "tavern_native_connection_profiles" to JSONArray(prefs.connectionProfiles.map(ConnectionProfile::json)),
                "tavern_native_active_profile" to prefs.activeProfileId)
                .also(prefs.chatAppearance::writeOriginal).also(prefs.chatHandling::writeOriginal)
                .also(prefs.characterLibrary::write)
                .also(prefs.replyAutomation::applyTo),
            "world_info_settings" to prefs.loreSettings.json().put("world_info",
                jsonOf("globalSelect" to JSONArray(books.filter(WorldBook::enabled).map { bookNames.getValue(it.id) }),
                    "charLore" to JSONArray(cards.mapNotNull { card ->
                        val extra = card.additionalWorldBookIds.mapNotNull(bookNames::get)
                        extra.takeIf(List<String>::isNotEmpty)?.let {
                            jsonOf("name" to avatars.getValue(card.id).loreFilenameStem(), "extraBooks" to JSONArray(it))
                        }
                    }))),
            "oai_settings" to jsonOf("preset_settings_openai" to presetNames[prefs.defaultPresetId].orEmpty()),
            "tags" to JSONArray(library.tags.map(LibraryTag::json)), "tag_map" to tagMap, "extension_settings" to extensions)
    }

    private fun dataRows(owner: DataOwner): JSONArray = JSONArray(data[owner].orEmpty().mapNotNull { document ->
        checkActive()
        val text = store.documentText(document.id)
        if (text == null) {
            missing++
            return@mapNotNull null
        }
        val bytes = text.encodeToByteArray()
        val path = "user/files/${backupId("data", document.id)}.txt"
        add(path, bytes, document.updated)
        if (!document.enabled) disabledData.put(path)
        attachment(document.title, path, bytes.size, document.updated).put(NATIVE_BACKUP_ID, document.id)
    })

    private fun attachment(name: String, path: String, size: Int, time: Long) =
        jsonOf("name" to name, "url" to path, "size" to size, "created" to time)

    private fun writeDocument(document: ChatDocument) = writeMedia("user/files/${document.filename}") { documents.bytes(document) }

    private fun writeImage(image: ChatImage) = writeMedia("$NATIVE_BACKUP_IMAGES/${image.filename}") { images.bytes(image) }

    private fun writeVideo(video: ChatVideo): Boolean {
        val path = "$NATIVE_BACKUP_IMAGES/${video.filename}"
        return media.getOrPut(path) {
            checkActive()
            val input = videos?.input(video)
            if (input == null) {
                missing++
                false
            } else {
                input.use {
                    zip.putNextEntry(ZipEntry("$BACKUP_ROOT/$path"))
                    require(copyVideo(it, zip, video.byteCount, checkActive) == video.byteCount) { "备份视频大小与记录不符" }
                    zip.closeEntry()
                }
                true
            }
        }
    }

    private fun writeGallery(owner: String, title: String, gallery: List<GalleryImage>) {
        val folder = title.takeIf {
            galleryNames[it.lowercase(Locale.ROOT)] == 1 && it == BackupNames().unique(it) &&
                !it.equals(NATIVE_BACKUP_IMAGES.substringAfterLast('/'), true)
        } ?: owner
        val items = gallery.distinctBy { it.image.filename }.filter { writeImage(it.image) }.map { entry ->
            val image = entry.image
            writeMedia("user/images/$folder/${image.filename}", entry.created) { images.bytes(image) }
            jsonOf("image" to image.json(), "prompt" to entry.prompt, "created" to entry.created)
        }
        if (items.isNotEmpty()) addJson("TavernNative Gallery/$owner.json", jsonOf("items" to JSONArray(items)))
    }

    private fun writeBackgrounds(settings: JSONObject) {
        val library = prefs.backgrounds
        if (library == BackgroundLibrary()) return
        val used = BackupNames()
        used.unique("__transparent")
        val files = JSONObject()
        var selected = "__transparent.png"
        for (entry in library.items) {
            val image = entry.image
            val stem = used.unique(entry.title.substringBeforeLast('.'))
            val filename = "$stem.${image.format.suffix}"
            if (writeMedia("backgrounds/$filename", entry.added) { images.bytes(image) }) {
                files.put(filename, entry.json())
                if (image.id == library.selectedId) selected = filename
            }
        }
        val path = java.net.URI(null, null, "/backgrounds/$selected", null).toASCIIString().removePrefix("/")
        settings.put("background", jsonOf("name" to selected, "url" to "url(\"$path\")", "fitting" to library.fit.original))
        settings.put("tavern_native_background_files", files)
    }

    private fun writeMedia(path: String, time: Long = 0, bytes: () -> ByteArray?): Boolean = media.getOrPut(path) {
        checkActive()
        val value = bytes()
        if (value == null) missing++ else add(path, value, time)
        value != null
    }

    private fun addJson(path: String, value: JSONObject) = add(path, value.toString().encodeToByteArray())

    private fun add(path: String, value: ByteArray, time: Long = 0) {
        checkActive()
        require(safeOriginalPath(path) && value.size <= BACKUP_ENTRY_LIMIT) { "备份条目超出大小限制 · $path" }
        zip.putNextEntry(ZipEntry("$BACKUP_ROOT/$path").apply { if (time > 0) this.time = time })
        var offset = 0
        while (offset < value.size) {
            checkActive()
            val length = minOf(64 * 1024, value.size - offset)
            zip.write(value, offset, length)
            offset += length
        }
        zip.closeEntry()
    }
}
