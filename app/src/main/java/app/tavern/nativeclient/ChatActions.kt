package app.tavern.nativeclient

import org.json.JSONObject
import java.time.Instant

internal enum class ReplyAction(val trigger: String) {
    NORMAL("normal"), REGENERATE("regenerate"), SWIPE("swipe"), CONTINUE("continue"), IMPERSONATE("impersonate"),
}

internal data class DraftSuggestion(val chatId: String, val text: String)

internal data class ChatPointer(val title: String, val id: String? = null) {
    fun resolve(chats: List<ChatSummary>): ChatSummary? = if (id == null) {
        chats.firstOrNull { it.title == title }
    } else chats.firstOrNull { it.id == id }

    fun portable(chats: List<ChatSummary>) = ChatPointer(resolve(chats)?.title ?: title)

    fun write(json: JSONObject, nameKey: String, idKey: String) {
        json.put(nameKey, title)
        if (id == null) json.remove(idKey) else json.put(idKey, id)
    }

    companion object {
        fun from(json: JSONObject?, nameKey: String, idKey: String): ChatPointer? {
            val title = json?.string(nameKey)?.takeIf(String::isNotBlank) ?: return null
            return ChatPointer(title, json.string(idKey).takeIf(String::isNotBlank))
        }
    }
}

private val checkpointSuffix = Regex(" · (分支|检查点) [0-9]+$")

internal fun Chat.nextForkTitle(history: List<ChatSummary>, checkpoint: Boolean): String {
    val base = title.replace(checkpointSuffix, "")
    val kind = if (checkpoint) "检查点" else "分支"
    val used = history.mapTo(mutableSetOf(), ChatSummary::title)
    return generateSequence(1) { it + 1 }.map { "$base · $kind $it" }.first { it !in used }
}

internal fun Chat.fork(position: Int, title: String, checkpoint: Boolean, swipe: Int? = null,
                      forkId: String = newId()): Pair<Chat, Chat> {
    require(position in messages.indices) { "找不到所选消息" }
    require(title.isNotBlank()) { "请输入聊天名称" }
    val now = System.currentTimeMillis()
    val header = this.header?.let(::JSONObject) ?: JSONObject()
    header.put("create_date", Instant.ofEpochMilli(now).toString())
    val metadata = header.optJSONObject("chat_metadata") ?: JSONObject().also { header.put("chat_metadata", it) }
    metadata.put("integrity", newId())
    val original = messages[position]
    require(swipe == null || swipe in original.variants.indices) { "找不到所选备选回复" }
    val snapshot = if (swipe == null || swipe == original.index) messages.take(position + 1) else {
        messages.take(position) + original.select(swipe).copy(summary = null)
    }
    val child = copy(id = forkId, title = title, updated = now, messages = snapshot,
        header = header.toString(), options = options.copy(parent = ChatPointer(this.title, this.id)))
    val pointer = ChatPointer(title, forkId)
    val messages = messages.toMutableList()
    messages[position] = if (checkpoint) original.copy(checkpoint = pointer)
        else original.copy(branches = original.branches + pointer)
    return copy(messages = messages) to child
}

internal fun Chat.refreshCheckpoint(position: Int, target: Chat, history: List<ChatSummary>): Pair<Chat, Chat> {
    val link = messages.getOrNull(position)?.checkpoint?.resolve(history)
    require(target.id != id && target.id == link?.id && target.cardId == cardId && target.groupId == groupId) {
        "找不到这条消息的检查点"
    }
    require(target.options.parent?.resolve(history)?.id == id) { "找不到检查点和当前聊天的关联" }
    val (parent, child) = fork(position, target.title, checkpoint = true, forkId = target.id)
    val messages = child.messages.map { message ->
        message.copy(checkpoint = message.checkpoint?.takeUnless { it.resolve(history)?.id == target.id },
            branches = message.branches.filterNot { it.resolve(history)?.id == target.id })
    }
    return parent to child.copy(messages = messages)
}

internal fun Chat.retargetLegacyChatLinks(old: ChatSummary, newTitle: String): Chat {
    fun update(pointer: ChatPointer): ChatPointer = if (pointer.id == null && pointer.title == old.title) {
        ChatPointer(newTitle, old.id)
    } else pointer
    return copy(options = options.copy(parent = options.parent?.let(::update)), messages = messages.map { message ->
        message.copy(checkpoint = message.checkpoint?.let(::update), branches = message.branches.map(::update))
    })
}

internal enum class ChatExportFormat(val suffix: String, val mime: String, val label: String) {
    JSONL("jsonl", "application/octet-stream", "导出聊天 JSONL"),
    TEXT("txt", "text/plain", "导出聊天文本"),
    ARCHIVE("zip", "application/zip", "导出聊天及附件 ZIP"),
}

internal const val CHAT_OWNER_KEY = "tavern_native_chat_owner"

internal fun Chat.withOwner(card: Card?, group: TavernGroup?, members: List<Card>,
                            sourceCard: Card? = null, asCopy: Boolean = false, knownCards: List<Card> = members): Chat {
    require((card != null) != (group != null)) { "请选择角色或群组" }
    val ownerId = card?.id ?: requireNotNull(group).id
    val kind = if (group != null) "group" else "character"
    val header = this.header?.let(::JSONObject)
    val origin = header?.optJSONObject(CHAT_OWNER_KEY)
    val originalName = header?.string("character_name").orEmpty()
    val originalKind = when {
        asCopy && groupId != null -> "group"
        asCopy && cardId != null -> "character"
        origin != null -> origin.string("kind")
        originalName == "unused" && header?.string("user_name") == "unused" -> "group"
        messages.any { it.role == "assistant" && it.groupTurnId != null } -> "group"
        originalName.isNotBlank() -> "character"
        else -> null
    }
    val detached = asCopy || (originalKind != null && originalKind != kind) ||
        (origin != null && origin.string("id") != ownerId) ||
        (origin == null && originalKind == "character" && card != null && !originalName.equals(card.name, true))
    val now = if (asCopy) System.currentTimeMillis() else updated
    val newHeader = if (detached) (header ?: JSONObject()).apply {
        put("character_name", card?.name ?: requireNotNull(group).name)
        if (asCopy) put("create_date", Instant.ofEpochMilli(now).toString())
        put(CHAT_OWNER_KEY, jsonOf("kind" to kind, "id" to ownerId))
        val metadata = optJSONObject("chat_metadata") ?: JSONObject().also { put("chat_metadata", it) }
        metadata.put("integrity", newId())
        metadata.remove("main_chat")
        metadata.remove("tavern_native_parent_chat")
    }.toString() else this.header
    val byId = knownCards.associateBy(Card::id)
    val byName = knownCards.groupBy { it.name.lowercase() }
    val memberNames = members.groupBy { it.name.lowercase() }
    val fallback = sourceCard ?: if (originalKind == "character") {
        byId[origin?.string("id")] ?: byName[originalName.lowercase()]?.singleOrNull()
    } else null
    val groupHistory = group != null || originalKind == "group"
    val transferred = messages.map { message ->
        val row = if (detached) message.copy(checkpoint = null, branches = emptyList()) else message
        if (row.role != "assistant") return@map row
        val direct = byId[row.characterId]
        val avatar = if (direct == null) row.raw?.let(::JSONObject)?.string("original_avatar")?.takeIf(String::isNotBlank)
            else null
        val matched = direct ?: avatar?.let { byId[previewCharacterId(it)] }
            ?: byName[row.name.lowercase()]?.singleOrNull()
            ?: memberNames[row.name.lowercase()]?.singleOrNull()
            ?: fallback?.takeIf { row.characterId == null && row.groupTurnId == null && avatar == null }
        row.copy(characterId = matched?.id ?: row.characterId,
            groupTurnId = row.groupTurnId ?: if (groupHistory || avatar != null) newId() else null)
    }
    return copy(id = if (asCopy) newId() else id, cardId = card?.id, groupId = group?.id, updated = now,
        header = newHeader, options = if (detached) options.copy(parent = null) else options, messages = transferred)
}

internal fun Chat.availableTitle(history: List<ChatSummary>): String =
    availableTitle(history.mapTo(hashSetOf(), ChatSummary::title))

internal fun Chat.availableTitle(titles: Set<String>): String {
    if (title !in titles) return title
    return generateSequence(2) { it + 1 }.map { "$title · $it" }.first { it !in titles }
}

internal fun joinContinuation(prefix: String, continuation: String, spacing: ContinueSpacing? = null,
                              transform: (String) -> String = { it }): String {
    if (continuation.isEmpty()) return prefix
    val suffix = transform(continuation)
    if (prefix.isEmpty() || suffix.isEmpty()) return prefix + suffix
    if (spacing != null) {
        if (suffix.isBlank()) return prefix
        if (spacing == ContinueSpacing.NONE) return prefix + suffix
        return prefix.trimEnd() + spacing.separator + suffix.trimStart()
    }
    val last = prefix.last()
    val first = continuation.first()
    fun wordLike(character: Char) = character.isDigit() ||
        Character.UnicodeScript.of(character.code) == Character.UnicodeScript.LATIN
    val separator = if (wordLike(first) && (wordLike(last) || last in ".!?;,:\"”)]")) " " else ""
    return prefix + separator + suffix
}
