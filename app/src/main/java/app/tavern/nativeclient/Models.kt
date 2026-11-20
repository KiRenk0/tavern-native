package app.tavern.nativeclient

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.time.Instant
import java.util.UUID
import java.util.zip.CRC32

internal fun newId() = UUID.randomUUID().toString()
internal fun JSONObject.string(key: String) = if (isNull(key)) "" else optString(key)
internal fun JSONArray.objects() = (0 until length()).mapNotNull(::optJSONObject)
internal fun JSONArray?.strings(): List<String> = this?.let { array ->
    (0 until array.length()).mapNotNull { array.opt(it) as? String }
}.orEmpty()

private val pngSignature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

internal fun jsonOf(vararg fields: Pair<String, Any?>) =
    JSONObject().apply { fields.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) } }

internal data class Card(val id: String, val original: String, val hasAvatar: Boolean = false) {
    private val data by lazy {
        val root = JSONObject(original)
        root.optJSONObject("data") ?: root
    }
    val name get() = data.string("name")
    val description get() = data.string("description")
    val personality get() = data.string("personality")
    val scenario get() = data.string("scenario")
    val examples get() = data.string("mes_example")
    val instructions get() = data.string("system_prompt")
    val postHistory get() = data.string("post_history_instructions")
    val creator get() = data.string("creator")
    val creatorNotes get() = data.string("creator_notes")
    val version get() = data.string("character_version")
    private val depthPromptData get() = data.optJSONObject("extensions")?.optJSONObject("depth_prompt")
    val depthPrompt get() = depthPromptData?.string("prompt").orEmpty()
    val depthPromptDepth get() = depthPromptData?.optInt("depth", 4)?.coerceIn(0, 80) ?: 4
    val depthPromptRole get() = PromptRole.entries.firstOrNull { it.wire == depthPromptData?.string("role") } ?: PromptRole.SYSTEM
    val tags get() = data.optJSONArray("tags").strings()
    val libraryFavorite by lazy {
        val nested = data.optJSONObject("extensions")
        if (nested?.has("fav") == true) nested.optBoolean("fav") else JSONObject(original).optBoolean("fav")
    }
    val sourceLink: String? by lazy {
        val extras = data.optJSONObject("extensions")
        val chub = extras?.optJSONObject("chub")?.string("full_path").orEmpty()
        val pygmalion = extras?.string("pygmalion_id").orEmpty()
        val risu = extras?.optJSONObject("risuai")?.optJSONArray("source").strings()
            .firstOrNull { it.startsWith("risurealm:") }?.substringAfter(':').orEmpty()
        val perchance = extras?.optJSONObject("perchance_data")?.string("slug").orEmpty()
        val source = sequenceOf(
            extras?.string("tavern_native_source").orEmpty(), extras?.string("source_url").orEmpty(),
            chub.takeIf(String::isNotBlank)?.let { "https://chub.ai/characters/$it" }.orEmpty(),
            pygmalion.takeIf(String::isNotBlank)?.let { "https://pygmalion.chat/$it" }.orEmpty(),
            risu.takeIf(String::isNotBlank)?.let { "https://realm.risuai.net/character/$it" }.orEmpty(),
            perchance.takeIf(String::isNotBlank)?.let { "https://perchance.org/ai-character-chat?data=$it" }.orEmpty(),
        ).filter(String::isNotBlank).firstNotNullOfOrNull { link ->
            runCatching { externalWebAddress(link).toASCIIString() }.getOrNull()
        }
        source
    }
    val greeting get() = data.string("first_mes")
    val alternateGreetings get() = data.optJSONArray("alternate_greetings").strings()
    val greetings get() = (listOf(greeting) + alternateGreetings).filter(String::isNotBlank)
    val book get() = data.optJSONObject("character_book")
    val worldName get() = data.optJSONObject("extensions")?.string("world").orEmpty()
    val regexRules by lazy { RegexRule.fromArray(data.optJSONObject("extensions")?.optJSONArray("regex_scripts")) }
    val expressionSprites by lazy {
        data.optJSONObject("extensions")?.optJSONArray(EXPRESSION_EXTENSION)?.objects()?.mapNotNull(ExpressionSprite::from)
    }
    val authorNote get() = CharacterNote.fromJson(data.optJSONObject("extensions")?.optJSONObject("tavern_native_author_note"))
    val talkativeness get() = data.optJSONObject("extensions")?.optDouble("talkativeness",
        data.optDouble("talkativeness", .5)) ?: data.optDouble("talkativeness", .5)

    fun edited(fields: Map<String, String>, alternates: List<String>): Card {
        val root = JSONObject(original)
        val nested = root.optJSONObject("data")
        val target = nested ?: root
        fields.forEach { (key, value) ->
            target.put(key, value)
            if (nested != null && root.has(key)) root.put(key, value)
        }
        target.put("alternate_greetings", JSONArray(alternates))
        return copy(original = root.toString())
    }

    fun withWorld(name: String): Card {
        val root = JSONObject(original)
        val target = root.optJSONObject("data") ?: root
        val extensions = target.optJSONObject("extensions") ?: JSONObject().also { target.put("extensions", it) }
        extensions.put("world", name)
        return copy(original = root.toString())
    }

    fun withCreatorTags(tags: List<String>): Card {
        val root = JSONObject(original)
        val target = root.optJSONObject("data") ?: root
        val array = JSONArray(tags)
        target.put("tags", array)
        if (target !== root && root.has("tags")) root.put("tags", array)
        return copy(original = root.toString())
    }

    fun withSourceLink(link: String): Card {
        val root = JSONObject(original)
        val target = root.optJSONObject("data") ?: root
        val extensions = target.optJSONObject("extensions") ?: JSONObject().also { target.put("extensions", it) }
        extensions.put("tavern_native_source", link)
        return copy(original = root.toString())
    }

    fun withLibraryFavorite(favorite: Boolean): Card {
        val root = JSONObject(original)
        val target = root.optJSONObject("data") ?: root
        val extensions = target.optJSONObject("extensions") ?: JSONObject().also { target.put("extensions", it) }
        extensions.put("fav", favorite)
        return copy(original = root.toString())
    }

    fun withAuthorNote(note: CharacterNote): Card {
        val root = JSONObject(original)
        val target = root.optJSONObject("data") ?: root
        val extensions = target.optJSONObject("extensions") ?: JSONObject().also { target.put("extensions", it) }
        if (note.text.isBlank()) extensions.remove("tavern_native_author_note")
        else extensions.put("tavern_native_author_note", note.json())
        return copy(original = root.toString())
    }

    fun withRegexRules(rules: List<RegexRule>): Card {
        val root = JSONObject(original)
        val target = root.optJSONObject("data") ?: root
        val extensions = target.optJSONObject("extensions") ?: JSONObject().also { target.put("extensions", it) }
        extensions.put("regex_scripts", JSONArray(rules.map(RegexRule::json)))
        return copy(original = root.toString())
    }

    fun withDepthPrompt(text: String, depth: Int, role: PromptRole): Card {
        val root = JSONObject(original)
        val target = root.optJSONObject("data") ?: root
        val extensions = target.optJSONObject("extensions") ?: JSONObject().also { target.put("extensions", it) }
        val prompt = extensions.optJSONObject("depth_prompt") ?: JSONObject().also { extensions.put("depth_prompt", it) }
        prompt.put("prompt", text).put("depth", depth).put("role", role.wire)
        return copy(original = root.toString())
    }

    fun portableJson(): JSONObject {
        val root = JSONObject(original)
        if (root.optJSONObject("data") != null) return root
        val data = JSONObject(original)
        listOf("description", "personality", "scenario", "first_mes", "mes_example",
            "creator_notes", "system_prompt", "post_history_instructions", "creator", "character_version")
            .forEach { if (!data.has(it)) data.put(it, "") }
        listOf("alternate_greetings", "tags").forEach { if (!data.has(it)) data.put(it, JSONArray()) }
        if (!data.has("extensions")) data.put("extensions", JSONObject())
        return jsonOf("spec" to "chara_card_v2", "spec_version" to "2.0", "data" to data)
    }

    companion object {
        fun from(json: JSONObject, id: String = newId(), hasAvatar: Boolean = false): Card {
            val card = Card(id, json.toString(), hasAvatar)
            require(card.name.isNotBlank()) { "角色卡缺少名称" }
            return card
        }

        fun blank() = Card(newId(), "{}")
    }
}

internal data class CardImport(val card: Card, val portrait: ByteArray? = null)

internal object CardFile {
    fun isPng(bytes: ByteArray) = pngSignature.indices.all { index -> bytes.getOrNull(index) == pngSignature[index] }

    fun read(bytes: ByteArray, id: String = newId()): CardImport {
        if (!isPng(bytes)) {
            val json = JSONObject(bytes.toString(Charsets.UTF_8).trimStart('\uFEFF'))
            return CardImport(Card.from(json, id))
        }
        var v2: String? = null
        var v3: String? = null
        scanPng(bytes) { type, _, offset, size ->
            if (type == "tEXt") {
                val limit = minOf(offset + size, offset + 80)
                val separator = (offset until limit).firstOrNull { bytes[it] == 0.toByte() }
                if (separator != null) {
                    val label = String(bytes, offset, separator - offset, Charsets.US_ASCII)
                    val value = { String(bytes, separator + 1, offset + size - separator - 1, Charsets.US_ASCII) }
                    if (label.equals("ccv3", true) && v3 == null) v3 = value()
                    if (label.equals("chara", true) && v2 == null) v2 = value()
                }
            }
        }
        val encoded = requireNotNull(v3 ?: v2) { "PNG 中没有 SillyTavern 角色卡数据" }
        val json = boundedJsonObject(Base64.decode(encoded, Base64.DEFAULT).decodeToString(), "PNG 角色数据无法读取")
        return CardImport(Card.from(json, id, true), bytes)
    }

    fun png(card: Card, portrait: ByteArray): ByteArray {
        val root = card.portableJson()
        val label = if (root.string("spec") == "chara_card_v3") "ccv3" else "chara"
        val payload = "$label\u0000" + Base64.encodeToString(
            root.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP,
        )
        val chunk = payload.toByteArray(Charsets.US_ASCII)
        val text = "tEXt".toByteArray(Charsets.US_ASCII)
        val output = ByteArrayOutputStream(portrait.size + chunk.size + 12)
        output.write(pngSignature)
        val stream = DataOutputStream(output)
        scanPng(portrait) { type, start, offset, size ->
            val existingCard = type == "tEXt" && listOf("chara", "ccv3").any { existing ->
                val length = existing.length
                size > length && portrait[offset + length] == 0.toByte() &&
                    String(portrait, offset, length, Charsets.US_ASCII).equals(existing, true)
            }
            if (type == "IEND") {
                stream.writeInt(chunk.size)
                stream.write(text)
                stream.write(chunk)
                stream.writeInt(CRC32().apply { update(text); update(chunk) }.value.toInt())
            }
            if (!existingCard) stream.write(portrait, start, size + 12)
        }
        return output.toByteArray()
    }

    private inline fun scanPng(bytes: ByteArray, consume: (String, Int, Int, Int) -> Unit) {
        require(bytes.size >= 20 && bytes.take(8).toByteArray().contentEquals(pngSignature)) { "无效的 PNG" }
        var start = 8
        while (start <= bytes.size - 12) {
            val size = ByteBuffer.wrap(bytes, start, 4).int
            require(size in 0..(bytes.size - start - 12)) { "PNG 数据块已损坏" }
            val type = String(bytes, start + 4, 4, Charsets.US_ASCII)
            consume(type, start, start + 8, size)
            if (type == "IEND") return
            start += size + 12
        }
        error("PNG 缺少结束数据块")
    }
}

internal data class Message(
    val role: String,
    val name: String,
    val text: String,
    val date: String = Instant.now().toString(),
    val variants: List<String> = emptyList(),
    val index: Int = 0,
    val raw: String? = null,
    val characterId: String? = null,
    val personaId: String? = null,
    val groupTurnId: String? = null,
    val loreEffects: Map<String, LoreEffect>? = null,
    val checkpoint: ChatPointer? = null,
    val branches: List<ChatPointer> = emptyList(),
    val reasoning: String? = null,
    val variantReasoning: List<String?> = emptyList(),
    val images: List<ChatImage> = emptyList(),
    val translation: String? = null,
    val wireText: String? = null,
    val summary: SummarySnapshot? = null,
    val documents: List<ChatDocument> = emptyList(),
    val drawing: DrawnImageSeries? = null,
    val toolTurn: ToolTurn? = null,
    val hidden: Boolean = false,
    val imageCaptions: Map<String, String> = emptyMap(),
) {
    val excludedFromContext get() = hidden || role == "system"
    val requestText get() = wireText ?: text
    val displayText get() = translation ?: text
    val displayImages get() = (images + listOfNotNull(drawing?.current)).distinctBy(ChatImage::filename)
    fun imageAssets() = images + drawing?.images.orEmpty() + drawing?.recipe?.references.orEmpty()
    fun isBy(card: Card) = characterId?.let { it == card.id } ?: (name == card.name)
    val regexSite by lazy {
        when {
            role == "user" -> RegexSite.INPUT
            raw?.let { JSONObject(it).optJSONObject("extra")?.string("type") } == "narrator" -> RegexSite.COMMAND
            role == "assistant" -> RegexSite.REPLY
            else -> null
        }
    }

    private fun reasoningAt(at: Int): String? = if (at == index) reasoning else variantReasoning.getOrNull(at)

    fun select(choice: Int) = copy(text = variants[choice], index = choice, reasoning = reasoningAt(choice),
        variantReasoning = List(variants.size, ::reasoningAt), translation = null, wireText = null)

    fun withReasoning(value: String?) = copy(reasoning = value, variantReasoning = if (variants.isEmpty()) emptyList()
        else List(variants.size) { if (it == index) value else reasoningAt(it) })

    fun withText(value: String): Message {
        val alternatives = variants.toMutableList()
        if (index in alternatives.indices) alternatives[index] = value
        return if (value == text) copy(variants = alternatives)
        else copy(text = value, variants = alternatives, translation = null, wireText = null, summary = null)
    }

    fun withAlternative(value: String, details: String? = null): Message {
        val alternatives = variants.ifEmpty { listOf(text) } + value
        val prior = if (variants.isEmpty()) listOf(reasoning) else List(variants.size, ::reasoningAt)
        return copy(text = value, variants = alternatives, index = alternatives.lastIndex, reasoning = details,
            variantReasoning = prior + details, translation = null, wireText = null, summary = null)
    }

    fun portable(chats: List<ChatSummary>) = copy(
        checkpoint = checkpoint?.portable(chats), branches = branches.map { it.portable(chats) },
    )

    fun toJson(): JSONObject = (raw?.let(::JSONObject) ?: JSONObject()).apply {
        put("name", name)
        put("is_user", role == "user")
        put("is_system", excludedFromContext)
        put("send_date", date)
        put("mes", requestText)
        val extra = optJSONObject("extra") ?: JSONObject().also { put("extra", it) }
        if (imageCaptions.isEmpty()) extra.remove("tavern_native_image_captions")
        else extra.put("tavern_native_image_captions", JSONObject(imageCaptions))
        if (hidden) extra.put("tavern_native_hidden_role", role) else extra.remove("tavern_native_hidden_role")
        val display = translation ?: text.takeIf { wireText != null }
        if (display == null) extra.remove("display_text") else extra.put("display_text", display)
        if (wireText == null) extra.remove("tavern_native_original_input") else extra.put("tavern_native_original_input", text)
        if (summary == null) {
            extra.remove("memory")
            extra.remove("tavern_native_memory_basis")
        } else {
            extra.put("memory", summary.text)
            extra.put("tavern_native_memory_basis", summary.basis)
        }
        if (images.isEmpty()) extra.remove("tavern_native_images")
        else extra.put("tavern_native_images", JSONArray(images.map(ChatImage::json)))
        if (documents.isEmpty()) extra.remove("tavern_native_documents")
        else extra.put("tavern_native_documents", JSONArray(documents.map(ChatDocument::json)))
        if (drawing == null) extra.remove("tavern_native_drawing") else extra.put("tavern_native_drawing", drawing.json())
        toolTurn?.store(extra)
        if (reasoning == null) extra.remove("reasoning") else extra.put("reasoning", reasoning)
        if (characterId == null) extra.remove("tavern_native_character") else extra.put("tavern_native_character", characterId)
        if (personaId == null) extra.remove("tavern_native_persona") else extra.put("tavern_native_persona", personaId)
        if (groupTurnId == null) extra.remove("gen_id")
        else if (extra.string("gen_id") != groupTurnId) extra.put("gen_id", groupTurnId)
        if (loreEffects == null) extra.remove("tavern_native_lore_effects")
        loreEffects?.let { effects ->
            extra.put("tavern_native_lore_effects", JSONObject().apply {
                effects.forEach { (key, effect) -> put(key, effect.json()) }
            })
        }
        if (checkpoint == null) {
            extra.remove("bookmark_link")
            extra.remove("tavern_native_checkpoint")
        } else checkpoint.write(extra, "bookmark_link", "tavern_native_checkpoint")
        if (branches.isEmpty()) {
            extra.remove("branches")
            extra.remove("tavern_native_branches")
        } else {
            extra.put("branches", JSONArray(branches.map(ChatPointer::title)))
            extra.put("tavern_native_branches", JSONArray(branches.map { it.id ?: JSONObject.NULL }))
        }
        if (variants.isNotEmpty()) {
            put("swipes", JSONArray(variants))
            put("swipe_id", index)
            val saved = optJSONArray("swipe_info")
            if (saved != null || reasoning != null || variantReasoning.any { it != null }) {
                val info = JSONArray()
                variants.indices.forEach { at ->
                    val row = saved?.optJSONObject(at) ?: JSONObject().put("send_date", date)
                    val detail = row.optJSONObject("extra") ?: JSONObject().also { row.put("extra", it) }
                    val value = reasoningAt(at)
                    if (value == null) detail.remove("reasoning") else detail.put("reasoning", value)
                    info.put(row)
                }
                put("swipe_info", info)
            }
        }
    }

    companion object {
        fun from(row: JSONObject): Message {
            require(row.opt("mes") is String) { "聊天记录包含无效消息" }
            val variants = row.optJSONArray("swipes").strings()
            val index = row.optInt("swipe_id").coerceIn(0, maxOf(variants.lastIndex, 0))
            val extra = row.optJSONObject("extra")
            val hiddenRole = extra?.string("tavern_native_hidden_role")?.takeIf { it in setOf("user", "assistant", "system") }
            val role = when {
                row.optBoolean("is_system") && hiddenRole != null -> hiddenRole
                row.optBoolean("is_user") -> "user"
                row.optBoolean("is_system") -> "system"
                else -> "assistant"
            }
            val effects = extra?.optJSONObject("tavern_native_lore_effects")
            val swipeInfo = row.optJSONArray("swipe_info")
            val branchIds = extra?.optJSONArray("tavern_native_branches")
            val branches = extra?.optJSONArray("branches").strings().mapIndexed { branch, title ->
                ChatPointer(title, (branchIds?.opt(branch) as? String)?.takeIf(String::isNotBlank))
            }
            val nativeOriginal = extra?.opt("tavern_native_original_input") as? String
            val modelText = row.string("mes")
            val original = nativeOriginal ?: modelText
            return Message(role, row.string("name"), original,
                row.string("send_date").ifBlank { Instant.now().toString() }, variants, index, row.toString(),
                extra?.string("tavern_native_character")?.takeIf(String::isNotBlank),
                extra?.string("tavern_native_persona")?.takeIf(String::isNotBlank),
                extra?.string("gen_id")?.takeIf(String::isNotBlank),
                effects?.keys()?.asSequence()?.mapNotNull { key -> effects.optJSONObject(key)?.let { key to LoreEffect.from(it) } }?.toMap(),
                ChatPointer.from(extra, "bookmark_link", "tavern_native_checkpoint"), branches,
                extra?.string("reasoning")?.takeIf(String::isNotEmpty)
                    ?: swipeInfo?.optJSONObject(index)?.optJSONObject("extra")?.string("reasoning")?.takeIf(String::isNotEmpty),
                variants.indices.map { swipeInfo?.optJSONObject(it)?.optJSONObject("extra")?.string("reasoning")?.takeIf(String::isNotEmpty) },
                extra?.optJSONArray("tavern_native_images")?.objects().orEmpty().take(MAX_CHAT_IMAGES).mapNotNull(ChatImage::from),
                extra?.string("display_text")?.takeIf { it.isNotBlank() && it != original },
                modelText.takeIf { nativeOriginal != null },
                extra?.string("memory")?.takeIf(String::isNotBlank)?.let { SummarySnapshot(it, extra.string("tavern_native_memory_basis")) },
                extra?.optJSONArray("tavern_native_documents")?.objects().orEmpty().take(MAX_CHAT_DOCUMENTS).mapNotNull(ChatDocument::from),
                DrawnImageSeries.from(extra?.optJSONObject("tavern_native_drawing")),
                ToolTurn.from(extra).takeIf { role == "system" },
                row.optBoolean("is_system") && (role != "system" || hiddenRole != null),
                readImageCaptions(extra?.optJSONObject("tavern_native_image_captions")))
        }
    }
}

internal data class Chat(
    val id: String,
    val cardId: String?,
    val title: String,
    val updated: Long = System.currentTimeMillis(),
    val messages: List<Message> = emptyList(),
    val header: String? = null,
    val modelOverride: ModelSelection? = null,
    val groupId: String? = null,
    val options: ConversationOptions = ConversationOptions(),
) {
    fun payload() = jsonOf("messages" to JSONArray(messages.map(Message::toJson)), "header" to header,
        "modelOverride" to modelOverride?.json(), "options" to options.json())

    fun jsonl(cardName: String, persona: String): ByteArray {
        val meta = (header?.let(::JSONObject) ?: JSONObject()).apply {
            if (isNull("user_name")) put("user_name", persona)
            if (isNull("character_name")) put("character_name", cardName)
            if (isNull("create_date")) put("create_date", Instant.ofEpochMilli(updated).toString())
            put(CHAT_OWNER_KEY, jsonOf("kind" to if (groupId == null) "character" else "group", "id" to (groupId ?: cardId)))
        }
        val metadata = meta.optJSONObject("chat_metadata") ?: JSONObject().also { meta.put("chat_metadata", it) }
        options.applyTo(metadata)
        return (listOf(meta.toString()) + messages.map { it.toJson().toString() })
            .joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8)
    }

    fun plainText(): ByteArray = buildString {
        messages.filter { it.role != "system" && it.text.isNotEmpty() }.forEach { message ->
            append(message.name).append(": ").append(message.text.replace("\r\n", "\n")).append("\n\n")
        }
    }.toByteArray(Charsets.UTF_8)

    companion object {
        fun import(cardId: String?, title: String, rows: List<JSONObject>, id: String = newId(),
                   groupId: String? = null): Chat {
            require(rows.isNotEmpty()) { "聊天记录为空" }
            val first = rows.first()
            val hasHeader = !first.has("mes") &&
                listOf("chat_metadata", "character_name", "user_name").any(first::has)
            return Chat(id, cardId, title, messages = (if (hasHeader) rows.drop(1) else rows).map(Message::from).bindImportedSummaries(),
                header = first.toString().takeIf { hasHeader }, groupId = groupId,
                options = ConversationOptions.fromMetadata(first.optJSONObject("chat_metadata").takeIf { hasHeader }))
        }
    }
}

internal data class Preferences(
    val temperature: String = "",
    val maxTokens: String = "",
    val theme: String = "system",
    val dynamicColor: Boolean = false,
    val server: String = "",
    val handle: String = "",
    val providers: List<Provider> = emptyList(),
    val defaultSelection: ModelSelection? = null,
    val fullTavernAddress: String = "",
    val personas: List<PersonaProfile> = listOf(PersonaProfile.legacy()),
    val defaultPersonaId: String = PersonaProfile.LEGACY_ID,
    val personaConnections: Map<String, String> = emptyMap(),
    val defaultAuthorNote: AuthorNote = AuthorNote(),
    val loreSettings: LoreSettings = LoreSettings(),
    val defaultPresetId: String? = null,
    val regex: RegexConfig = RegexConfig(),
    val includeReasoning: Boolean = false,
    val quickReplies: QuickReplies = QuickReplies(),
    val scriptVariables: Map<String, String> = emptyMap(),
    val voice: VoicePreferences = VoicePreferences(),
    val vectors: VectorPreferences = VectorPreferences(),
    val drawing: DrawingSettings = DrawingSettings(),
    val toolCalling: Boolean = false,
    val visualNovel: Boolean = false,
    val welcome: WelcomePreferences = WelcomePreferences(),
    val captions: CaptionSettings = CaptionSettings(),
    val translationServices: TranslationServices = TranslationServices(),
    val library: LibraryPreferences = LibraryPreferences(),
    val replyAutomation: ReplyAutomation = ReplyAutomation(),
    val chatAppearance: ChatAppearance = ChatAppearance(),
    val connectionProfiles: List<ConnectionProfile> = emptyList(),
    val activeProfileId: String? = null,
) {
    fun personaFor(chat: Chat? = null, subjectId: String? = chat?.cardId ?: chat?.groupId,
                   temporaryId: String? = null): PersonaProfile =
        sequenceOf(temporaryId, chat?.options?.personaId, subjectId?.let(personaConnections::get), defaultPersonaId)
            .filterNotNull().mapNotNull { id -> personas.firstOrNull { it.id == id } }
            .firstOrNull() ?: personas.firstOrNull() ?: PersonaProfile.legacy()

    fun resolve(selection: ModelSelection?): ModelConnection? {
        val provider = providers.firstOrNull { it.id == selection?.providerId && it.ready } ?: return null
        return ModelConnection(provider, requireNotNull(selection).model)
    }

    fun connectionFor(chat: Chat? = null): ModelConnection? = effectiveConfig(chat).connection
}
