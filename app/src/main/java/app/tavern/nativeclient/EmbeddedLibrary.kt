package app.tavern.nativeclient

import org.json.JSONObject
import java.io.File
import java.util.UUID

internal class EmbeddedLibrary(
    private val source: OriginalLibrarySource,
    private val attachmentStore: ChatDocumentStore? = null,
    private val images: ChatImageStore? = null,
    private val account: String = "default-user",
) {
    constructor(profile: File, attachmentStore: ChatDocumentStore? = null, images: ChatImageStore? = null) :
        this(FolderOriginalLibrary(profile), attachmentStore, images)

    val exists: Boolean
        get() = listOf("characters", "groups", "worlds", "User Avatars", "OpenAI Settings", "QuickReplies").any {
            source.files(it).isNotEmpty()
        }

    fun import(store: TavernStore, checkActive: () -> Unit = {}): String {
        var errors = 0
        var chatCount = 0
        var documentCount = 0
        val media = OriginalLibraryMedia(source, attachmentStore, images, checkActive)
        val avatars = mutableMapOf<String, String>()
        val groupIds = mutableMapOf<String, String>()
        val savedData = store.dataDocuments().mapTo(hashSetOf(), DataDocument::id)
        fun attempt(block: () -> Unit) {
            checkActive()
            try {
                block()
            } catch (_: IllegalArgumentException) {
                errors++
            } catch (_: org.json.JSONException) {
                errors++
            } catch (_: java.io.IOException) {
                errors++
            }
        }
        fun json(file: OriginalLibraryFile) = JSONObject(file.bytes().decodeToString().trimStart('\uFEFF'))
        var settings: JSONObject? = null
        source.file("settings.json")?.let { attempt { settings = json(it) } }
        val nativeBackup = settings?.optInt(NATIVE_BACKUP_KEY) == 1
        fun savedId(row: JSONObject?) = if (nativeBackup) portableAccountId(row?.string(NATIVE_BACKUP_ID)) else null
        val oldExtensions = settings?.optJSONObject("extension_settings")
        val disabledData = oldExtensions?.optJSONArray("disabled_attachments").strings().toHashSet()
        fun importData(rows: org.json.JSONArray?, owner: DataOwner) {
            for (row in rows?.objects().orEmpty()) attempt {
                val url = row.string("url")
                val file = originalChatFilename(url)
                val inline = row.string("text").takeIf(String::isNotBlank)
                if (file == null && inline == null) return@attempt
                val id = savedId(row)
                    ?: sourceId("document:${owner.scope}:${owner.id}:${file ?: ChatImageStore.digest(inline.orEmpty().encodeToByteArray())}")
                if (id in savedData) return@attempt
                val text = inline ?: media.document(requireNotNull(file))?.bytes(MAX_STORED_CHAT_DOCUMENT)?.let(::decodeDocumentText)
                    ?: throw java.io.IOException("备份中缺少资料文件")
                val title = row.string("name").ifBlank { file ?: "资料" }.take(180)
                val document = DataDocument(id, title, owner, enabled = url !in disabledData,
                    source = "SillyTavern", updated = row.optLong("created", System.currentTimeMillis()))
                store.saveDocument(document, validatedDocumentText(text))
                savedData += id
                documentCount++
            }
        }
        val power = settings?.optJSONObject("power_user")
        val personaNames = power?.optJSONObject("personas")
        val descriptions = power?.optJSONObject("persona_descriptions")
        val startingPersonas = store.prefs().personas
        val sourceNotes = settings?.optJSONObject("extension_settings")?.optJSONObject("note")
        val scanNotes = sourceNotes?.optBoolean("allowWIScan") == true
        val defaultNote = sourceNotes?.let {
            AuthorNote.fromMetadata(jsonOf("note_prompt" to it.string("default"), "note_interval" to it.optInt("defaultInterval", 1),
                "note_depth" to it.optInt("defaultDepth", 4), "note_position" to it.optInt("defaultPosition", 1),
                "note_role" to it.optInt("defaultRole"), "tavern_native_note_scan" to scanNotes))
        }
        val characterNotes = sourceNotes?.optJSONArray("chara")?.objects().orEmpty().filter { it.optBoolean("useChara") }
            .associateBy { it.string("name") }
        val personaIds = personaNames?.keys()?.asSequence()?.filter { personaNames.string(it).isNotBlank() }
            ?.associateWith { name ->
                val description = descriptions?.optJSONObject(name)
                savedId(description) ?: PersonaProfile.LEGACY_ID.takeIf {
                    nativeBackup && description?.string(NATIVE_BACKUP_ID) == it
                } ?: sourceId("persona:$name")
            }.orEmpty()
        val loreSettings = settings?.optJSONObject("world_info_settings")
        val enabled = loreSettings?.optJSONObject("world_info")?.optJSONArray("globalSelect").strings()
        val scanDepth = loreSettings?.optInt("world_info_depth", 4)?.coerceIn(0, 80) ?: 4
        val knownBooks = store.books().associateByTo(mutableMapOf(), WorldBook::id)
        val homeBooks = knownBooks.values.associateBy { it.name.lowercase() }
        val usedBookNames = homeBooks.keys.toMutableSet()
        val importedBooks = mutableMapOf<String, WorldBook>()
        var bookCount = 0
        for (file in files("worlds", "json")) attempt {
            val name = file.nameWithoutExtension
            val raw = if (nativeBackup) json(file) else null
            val id = savedId(raw) ?: sourceId("world:${file.name}")
            val existing = knownBooks[id] ?: homeBooks[name.lowercase()].takeIf { account == "default-user" }
            val book = if (existing != null) existing else {
                val content = raw ?: json(file)
                val title = if (nativeBackup) content.string("tavern_native_backup_name").ifBlank { name } else name
                val uniqueName = generateSequence(1) { it + 1 }.map { if (it == 1) title else "$title ($it)" }
                    .first { it.lowercase() !in usedBookNames }
                var value = WorldBook.import(content, name).copy(id = id, name = uniqueName, enabled = name in enabled)
                if (!content.has("scan_depth")) value = value.withScanDepth(scanDepth)
                store.save(value)
                knownBooks[id] = value
                usedBookNames += uniqueName.lowercase()
                bookCount++
                value
            }
            importedBooks[name] = book
        }
        fun worldFor(name: String) = importedBooks[name] ?: homeBooks[name.lowercase()].takeIf { account == "default-user" }
        fun history(file: OriginalLibraryFile, owner: String, group: Boolean, id: String): Chat {
            val rows = file.bytes().decodeToString().trimStart('\uFEFF')
                .lineSequence().filter(String::isNotBlank).map(::JSONObject).toList()
            val imported = Chat.import(owner.takeUnless { group }, file.nameWithoutExtension, rows, id, owner.takeIf { group })
            val metadata = imported.header?.let(::JSONObject)?.optJSONObject("chat_metadata")
            fun pointer(value: ChatPointer): ChatPointer {
                val existing = portableAccountId(value.id)
                if (existing != null) return value
                val filename = value.id?.takeIf { it.endsWith(".jsonl") && '/' !in it && safeOriginalPath(it) }
                return value.copy(id = filename?.let { sourceId("chat:$owner:$it") })
            }
            val importedNote = imported.options.authorNote?.let { note ->
                if (metadata?.has("tavern_native_note_scan") == true) note else note.copy(scan = scanNotes)
            } ?: defaultNote
            val world = worldFor(imported.options.worldBookName)
            val options = imported.options.copy(personaId = imported.options.personaId
                ?: metadata?.string("persona")?.let(personaIds::get), authorNote = importedNote,
                worldBookId = world?.id ?: imported.options.worldBookId, worldBookName = world?.name ?: imported.options.worldBookName,
                background = if (nativeBackup) imported.options.background?.let(media::nativeImage)
                    else imported.options.background ?: media.background(metadata?.string("custom_background").orEmpty()),
                parent = if (nativeBackup) imported.options.parent?.let(::pointer) else imported.options.parent,
                promptPresetId = imported.options.promptPresetId?.let { preset ->
                    if (!nativeBackup || preset.isEmpty()) preset
                    else portableAccountId(preset) ?: preset.takeIf { it.endsWith(".json") && '/' !in it && safeOriginalPath(it) }
                        ?.let { sourceId("prompt-preset:$it") }
                })
            val title = if (nativeBackup) metadata?.string("tavern_native_backup_title").orEmpty().ifBlank { imported.title } else imported.title
            return imported.copy(id = savedId(metadata) ?: id, title = title,
                updated = file.modified.takeIf { it > 0 } ?: imported.updated,
                options = options,
                messages = imported.messages.map { message ->
                    val row = message.raw?.let(::JSONObject)
                    val avatar = row?.string("original_avatar").orEmpty()
                    val decoded = runCatching { java.net.URI(avatar).path.substringAfterLast('/') }.getOrDefault(avatar)
                    val hydrated = media.message(message, nativeBackup)
                    val summary = hydrated.summary?.let {
                        if (row?.optJSONObject("extra")?.string("tavern_native_memory_basis").isNullOrBlank()) it.copy(basis = "") else it
                    }
                    val result = hydrated.copy(characterId = avatars[decoded] ?: message.characterId, summary = summary)
                    if (nativeBackup) result.copy(personaId = message.personaId?.let(personaIds::get),
                        checkpoint = message.checkpoint?.let(::pointer), branches = message.branches.map(::pointer))
                    else result
                }.bindImportedSummaries())
        }
        fun importChats(files: List<OriginalLibraryFile>, owner: String, group: Boolean) {
            for (file in files) attempt {
                // The chat identity also includes its original file name so later backups can add conversations.
                val originalId = sourceId("chat:$owner:${file.name}")
                val chat = if (!nativeBackup && store.containsChat(originalId)) store.chat(originalId)
                    else history(file, owner, group, originalId)
                val id = chat.id
                if (store.importOriginalChat(chat)) chatCount++
                else require(store.chat(id).let { it.cardId == chat.cardId && it.groupId == chat.groupId }) { "聊天归属不一致" }
                val header = chat.header?.let(::JSONObject)?.optJSONObject("chat_metadata")
                importData(header?.optJSONArray("attachments"), DataOwner(DataScope.CHAT, id))
            }
        }

        val collectedCards = mutableListOf<Pair<OriginalLibraryFile, Card>>()
        for (file in files("characters", "png")) attempt {
            val source = CardFile.read(file.bytes(), sourceId("card:${file.name}"))
            val extensions = if (nativeBackup) source.card.portableJson().optJSONObject("data")?.optJSONObject("extensions") else null
            val id = savedId(extensions) ?: source.card.id
            val parsed = source.copy(card = source.card.copy(id = id))
            val sourceNote = characterNotes[file.name] ?: characterNotes[file.nameWithoutExtension]
            val card = if (sourceNote == null) parsed else parsed.copy(card = parsed.card.withAuthorNote(
                CharacterNote(sourceNote.string("prompt"),
                    CharacterNoteOrder.entries.getOrElse(sourceNote.optInt("position")) { CharacterNoteOrder.REPLACE })))
            val book = worldFor(card.card.worldName)
            val linked = if (book == null || book.name == card.card.worldName) card else card.copy(card = card.card.withWorld(book.name))
            val portable = if (nativeBackup) {
                val value = linked.card
                val hasAvatar = extensions?.optBoolean("tavern_native_placeholder_avatar") != true
                val restored = value.expressionSprites?.let { sprites ->
                    value.withExpressionSprites(sprites.filter { media.nativeImage(it.image) != null })
                } ?: value
                linked.copy(card = restored.copy(hasAvatar = hasAvatar), portrait = linked.portrait.takeIf { hasAvatar })
            } else linked
            store.import(portable, emptyList())
            avatars[file.name] = id
            collectedCards += file to portable.card
        }
        for ((file, card) in collectedCards) attempt {
            val gallery = if (nativeBackup) media.nativeGallery(file.nameWithoutExtension)
                else media.gallery(listOf(card.name, file.nameWithoutExtension))
            store.importOriginalGallery(card.id, null, gallery)
            importChats(files("chats/${file.nameWithoutExtension}", "jsonl"), card.id, false)
            importData(oldExtensions?.optJSONObject("character_attachments")?.optJSONArray(file.name),
                DataOwner(DataScope.CHARACTER, card.id))
        }
        importData(oldExtensions?.optJSONArray("attachments"), DataOwner(DataScope.GLOBAL))

        val groupFiles = files("group chats", "jsonl").associateBy { it.nameWithoutExtension }
        val groupFavorites = mutableMapOf<String, Boolean>()
        var groupCount = 0
        for (file in files("groups", "json")) attempt {
            val data = json(file)
            val id = savedId(data) ?: sourceId("group:${file.nameWithoutExtension}.json")
            groupIds[data.string("id").ifBlank { file.nameWithoutExtension }] = id
            val original = TavernGroup.from(id, data.toString())
            val group = original.copy(name = original.name.ifBlank { file.nameWithoutExtension },
                memberIds = original.memberIds.mapNotNull(avatars::get),
                mutedIds = original.mutedIds.mapNotNull(avatars::get).toSet())
            if (data.has("fav")) groupFavorites[libraryGroupId(id)] = group.favorite
            val chatIds = (data.optJSONArray("chats").strings() + data.string("chat_id")).map { it.removeSuffix(".jsonl") }.distinct()
            store.import(group, emptyList())
            importChats(chatIds.mapNotNull(groupFiles::get), id, true)
            val gallery = if (nativeBackup) media.nativeGallery(file.nameWithoutExtension) else media.gallery(listOf(group.name))
            store.importOriginalGallery(null, id, gallery)
            groupCount++
        }
        val prefs = store.prefs()
        val avatarFiles = source.files("User Avatars").associateBy(OriginalLibraryFile::name)
        val personas = prefs.personas.associateByTo(linkedMapOf(), PersonaProfile::id)
        val links = prefs.personaConnections.toMutableMap()
        for ((original, id) in personaIds) {
            val description = descriptions?.optJSONObject(original) ?: JSONObject()
            description.optJSONArray("connections")?.objects().orEmpty().forEach { link ->
                val owner = when (link.string("type")) {
                    "character" -> avatars[link.string("id")]
                    "group" -> groupIds[link.string("id")]
                    else -> null
                }
                if (owner != null) links.putIfAbsent(owner, id)
            }
            val existing = personas[id]?.takeUnless {
                nativeBackup && id == PersonaProfile.LEGACY_ID && startingPersonas == listOf(PersonaProfile.legacy())
            }
            var avatar = existing?.avatar
            if (avatar == null && !(nativeBackup && description.optBoolean("tavern_native_placeholder_avatar"))) {
                avatarFiles[original]?.let { file -> attempt {
                    val asset = sourceId("persona-avatar:$original")
                    store.writePersonaAvatar(asset, file.bytes())
                    avatar = asset
                } }
            }
            personas[id] = existing?.copy(avatar = avatar) ?: PersonaProfile(
                id, personaNames?.string(original).orEmpty(), description.string("description"), description.string("title"),
                PersonaPosition.entries.firstOrNull { it.code == description.optInt("position") } ?: PersonaPosition.PROMPT,
                description.optInt("depth", 2).coerceAtLeast(0),
                PromptRole.entries.getOrElse(description.optInt("role")) { PromptRole.SYSTEM },
                worldFor(description.string("lorebook"))?.id.orEmpty(), avatar)
        }
        val profiles = personas.values.toList()
        val useSourceDefaults = prefs.personas == listOf(PersonaProfile.legacy())
        val sourceDefaultId = power?.string("default_persona")?.ifBlank { settings?.string("user_avatar").orEmpty() }
            ?.let(personaIds::get)
        val importedLore = LoreSettings.from(loreSettings).takeIf { prefs.loreSettings == LoreSettings() }
        val storedPresets = store.promptPresets()
        val knownPresets = storedPresets.mapTo(mutableSetOf(), PromptPreset::id)
        val presetNames = storedPresets.mapTo(mutableSetOf()) { it.name.lowercase() }
        val openaiSettings = settings?.optJSONObject("oai_settings")
        val sourcePreset = openaiSettings?.string("preset_settings_openai").orEmpty()
        val presetIds = mutableMapOf<String, String>()
        var selectedPreset: String? = null
        var addedPresets = 0
        for (file in files("OpenAI Settings", "json")) attempt {
            val raw = if (nativeBackup) json(file) else null
            val id = savedId(raw) ?: sourceId("prompt-preset:${file.name}")
            if (id !in knownPresets) {
                val parsed = PromptPreset.read(raw ?: json(file), file.nameWithoutExtension, id)
                val name = generateSequence(1) { it + 1 }
                    .map { if (it == 1) parsed.name else "${parsed.name} ($it)" }
                    .first { it.lowercase() !in presetNames }
                store.save(parsed.copy(name = name))
                presetNames += name.lowercase()
                knownPresets += id
                addedPresets++
            }
            presetIds[file.nameWithoutExtension] = id
            if (file.nameWithoutExtension == sourcePreset) selectedPreset = id
        }
        if (selectedPreset == null && openaiSettings != null && storedPresets.isEmpty() && sourcePreset.isNotBlank()) attempt {
            val id = sourceId("prompt-preset:settings")
            val preset = PromptPreset.read(openaiSettings, sourcePreset, id)
            store.save(preset)
            presetIds[sourcePreset] = id
            selectedPreset = id
            addedPresets++
        }
        val defaultPreset = prefs.defaultPresetId ?: selectedPreset.takeIf { storedPresets.isEmpty() }
        val globalRules = oldExtensions?.optJSONArray("regex")?.objects().orEmpty().mapIndexedNotNull { index, rule ->
            if (rule.string("id").isBlank()) rule.put("id", sourceId("regex:$index:${rule.string("scriptName")}"))
            RegexRule.from(rule)
        }
        val freshRegex = prefs.regex == RegexConfig()
        val regex = prefs.regex.copy(
            enabled = if (freshRegex) "regex" !in oldExtensions?.optJSONArray("disabledExtensions").strings() else prefs.regex.enabled,
            global = (prefs.regex.global + globalRules).distinctBy(RegexRule::id),
            allowedCards = if (freshRegex) oldExtensions?.optJSONArray("character_allowed_regex").strings()
                .mapNotNull(avatars::get).toSet() else prefs.regex.allowedCards,
            allowedPresets = if (freshRegex) oldExtensions?.optJSONObject("preset_allowed_regex")?.optJSONArray("openai").strings()
                .mapNotNull(presetIds::get).toSet() else prefs.regex.allowedPresets,
        )
        val quick = prefs.quickReplies
        val quickSets = quick.sets.toMutableList()
        val quickIds = mutableMapOf<String, String>()
        var addedQuickSets = 0
        for (file in files("QuickReplies", "json")) attempt {
            val raw = json(file)
            val id = savedId(raw) ?: sourceId("quick-reply:${file.name}")
            val preset = QuickReplySet.read(raw, id)
            quickIds[preset.name] = id
            if (quickSets.none { it.id == id }) {
                val name = generateSequence(1) { it + 1 }
                    .map { if (it == 1) preset.name else "${preset.name} ($it)" }
                    .first { title -> quickSets.none { it.name.equals(title, true) } }
                quickSets += preset.copy(name = name)
                addedQuickSets++
            }
        }
        val originalQuick = oldExtensions?.optJSONObject("quickReplyV2")
        val legacyQuick = oldExtensions?.optJSONObject("quickReply")
        val oldQuickNames = originalQuick?.optJSONObject("config").quickReplyNames()
            .ifEmpty { listOfNotNull(legacyQuick?.string("selectedPreset")?.takeIf(String::isNotBlank)) }
        val quickReplies = if (quick == QuickReplies()) QuickReplies(
            originalQuick?.optBoolean("isEnabled") ?: (legacyQuick?.optBoolean("quickReplyEnabled") == true),
            oldQuickNames.mapNotNull(quickIds::get), quickSets,
        ) else quick.copy(sets = quickSets)
        val scriptVariables = oldExtensions?.optJSONObject("variables")?.optJSONObject("global").variableStrings() +
            prefs.scriptVariables
        val automation = if (prefs.replyAutomation == ReplyAutomation()) ReplyAutomation.from(power) else prefs.replyAutomation
        val appearance = if (prefs.chatAppearance == ChatAppearance() && power?.has("tavern_native_chat_appearance") == true) {
            ChatAppearance.from(power.optJSONObject("tavern_native_chat_appearance"))
        } else prefs.chatAppearance
        val profilesImported = if (prefs.connectionProfiles.isEmpty() && power?.has("tavern_native_connection_profiles") == true) {
            power.optJSONArray("tavern_native_connection_profiles")?.objects()?.mapNotNull(ConnectionProfile::fromJson).orEmpty()
        } else prefs.connectionProfiles
        val activeProfileImported = prefs.activeProfileId ?: power?.string("tavern_native_active_profile")?.takeIf(String::isNotBlank)
        val tags = settings?.let { settingsJson ->
            prefs.library.mergeTags(settingsJson, ownerFor = { key ->
                avatars[key]?.let(::libraryCardId) ?: groupIds[key]?.let(::libraryGroupId)
            }, preserveAssignments = true).preferences
        } ?: prefs.library
        val library = tags.copy(favorites = groupFavorites + tags.favorites,
            folders = tags.folders || prefs.library == LibraryPreferences() && power?.optBoolean("bogus_folders") == true)
        if (profiles != prefs.personas || links != prefs.personaConnections || (defaultNote != null && prefs.defaultAuthorNote == AuthorNote()) ||
            (importedLore != null && importedLore != prefs.loreSettings) || defaultPreset != prefs.defaultPresetId ||
            regex != prefs.regex || quickReplies != quick || scriptVariables != prefs.scriptVariables ||
            library != prefs.library || automation != prefs.replyAutomation || appearance != prefs.chatAppearance ||
            profilesImported != prefs.connectionProfiles || activeProfileImported != prefs.activeProfileId) {
            store.save(prefs.copy(personas = profiles, personaConnections = links,
                defaultPersonaId = sourceDefaultId.takeIf { useSourceDefaults } ?: prefs.defaultPersonaId,
                defaultAuthorNote = defaultNote?.takeIf { prefs.defaultAuthorNote == AuthorNote() } ?: prefs.defaultAuthorNote,
                loreSettings = importedLore ?: prefs.loreSettings, defaultPresetId = defaultPreset, regex = regex,
                quickReplies = quickReplies, scriptVariables = scriptVariables, library = library,
                replyAutomation = automation, chatAppearance = appearance,
                connectionProfiles = profilesImported, activeProfileId = activeProfileImported))
        }
        return "已读取 ${avatars.size} 个角色、$groupCount 个群组、新增 $chatCount 个聊天、$bookCount 本世界书、" +
            "${profiles.size - prefs.personas.size} 个人设、$addedPresets 个提示词预设和 $addedQuickSets 个快捷回复集" +
            "。读取 ${media.imageCount} 张图片，新增 $documentCount 份资料" +
            if (errors + media.missing > 0) "。${errors + media.missing} 项文件缺失或无法读取" else ""
    }

    private fun files(directory: String, extension: String) =
        source.files(directory).filter { it.extension.equals(extension, true) }.sortedBy(OriginalLibraryFile::name)

    private fun sourceId(key: String) = stableId(if (account == "default-user") key else "account:$account:$key")
}

internal fun previewPersonaId(avatar: String) = stableId("persona:$avatar")
internal fun previewCharacterId(avatar: String) = stableId("card:$avatar")
internal fun previewGroupId(id: String) = stableId("group:${id.removeSuffix(".json")}.json")

private fun stableId(key: String) = UUID.nameUUIDFromBytes("embedded:$key".toByteArray(Charsets.UTF_8)).toString()
