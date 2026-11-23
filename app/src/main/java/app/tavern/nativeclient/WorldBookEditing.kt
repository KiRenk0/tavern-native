package app.tavern.nativeclient

import org.json.JSONObject

internal enum class LoreEntrySort(val label: String) {
    PRIORITY("注入优先级"), CUSTOM("自定义顺序"), TITLE("标题"), ACTIVATION("激活方式"),
    CONTENT_LENGTH("正文长度"), UID("条目编号");

    fun sorted(entries: List<LoreEntry>, descending: Boolean): List<LoreEntry> {
        val byPriority = compareByDescending(LoreEntry::priority).thenBy(LoreEntry::id)
        val primary = when (this) {
            PRIORITY -> compareBy(LoreEntry::priority)
            CUSTOM -> compareBy(LoreEntry::displayIndex)
            TITLE -> compareBy(String.CASE_INSENSITIVE_ORDER, LoreEntry::title)
            ACTIVATION -> compareBy<LoreEntry> { if (it.disabled) 2 else if (it.constant) 0 else 1 }
            CONTENT_LENGTH -> compareBy<LoreEntry> { it.content.length }
            UID -> compareBy(LoreEntry::id)
        }
        return entries.sortedWith((if (descending && this != CUSTOM) primary.reversed() else primary).then(byPriority))
    }
}

internal fun WorldBook.copyForEditing(name: String): WorldBook = copy(
    id = newId(), name = name, enabled = false,
    raw = JSONObject(raw).apply {
        remove(NATIVE_BACKUP_ID)
        remove("tavern_native_backup_name")
        remove("tavern_native_memory_overrides")
    }.toString(),
)

internal fun WorldBook.nextEntryId(): Int {
    val used = entries.mapTo(hashSetOf(), LoreEntry::id)
    val largest = used.maxOrNull()?.coerceAtLeast(-1) ?: -1
    return if (largest < Int.MAX_VALUE) largest + 1 else generateSequence(0) { it + 1 }.first { it !in used }
}

internal fun WorldBook.saveEditedEntry(entry: LoreEntry): WorldBook {
    if (entries.none { it.id == entry.id }) return appendEntry(entry)
    return withEntries(entries.map { if (it.id == entry.id) entry.copy(displayIndex = it.displayIndex) else it })
}

internal fun WorldBook.duplicateEntry(id: Int): Pair<WorldBook, Int> {
    val ordered = LoreEntrySort.CUSTOM.sorted(entries, false).toMutableList()
    val position = ordered.indexOfFirst { it.id == id }
    require(position >= 0) { "找不到要复制的世界书条目" }
    val nextId = nextEntryId()
    val duplicated = ordered[position].copy(id = nextId)
    ordered.add(position + 1, duplicated)
    return withDisplayOrder(ordered, entries + duplicated) to nextId
}

internal fun WorldBook.moveEntryInDisplayOrder(id: Int, direction: Int, visibleIds: Set<Int>): WorldBook {
    require(direction in listOf(-1, 1))
    val ordered = LoreEntrySort.CUSTOM.sorted(entries, false).toMutableList()
    val positions = ordered.indices.filter { ordered[it].id in visibleIds }
    val from = positions.indexOfFirst { ordered[it].id == id }
    if (from < 0) return this
    val to = positions.getOrNull(from + direction) ?: return this
    val current = positions[from]
    val selected = ordered[current]
    ordered[current] = ordered[to]
    ordered[to] = selected
    return withDisplayOrder(ordered, entries)
}

internal fun WorldBook.fillBlankEntryTitles(): WorldBook {
    val filled = entries.map { entry ->
        val keyword = entry.keywords.firstOrNull(String::isNotBlank)
        if (entry.title.isBlank() && keyword != null) entry.copy(title = keyword.trim()) else entry
    }
    return if (filled == entries) this else withEntries(filled)
}

internal data class LoreEntryTransfer(val source: WorldBook, val destination: WorldBook, val entryId: Int)

internal fun WorldBook.transferEntry(destination: WorldBook, id: Int, move: Boolean): LoreEntryTransfer {
    require(this.id != destination.id) { "请选择另一部世界书" }
    val entry = requireNotNull(entries.firstOrNull { it.id == id }) { "找不到要转移的世界书条目" }
    val targetId = destination.nextEntryId()
    val updated = destination.appendEntry(entry.copy(id = targetId))
    return LoreEntryTransfer(if (move) withEntries(entries.filterNot { it.id == id }) else this, updated, targetId)
}

private fun WorldBook.appendEntry(entry: LoreEntry): WorldBook {
    val last = entries.maxOfOrNull(LoreEntry::displayIndex)?.coerceAtLeast(-1) ?: -1
    if (last < Int.MAX_VALUE) return withEntries(entries + entry.copy(displayIndex = last + 1))
    val ordered = LoreEntrySort.CUSTOM.sorted(entries, false) + entry
    return withDisplayOrder(ordered, entries + entry)
}

private fun WorldBook.withDisplayOrder(ordered: List<LoreEntry>, stored: List<LoreEntry>): WorldBook {
    val indexes = ordered.mapIndexed { index, entry -> entry.id to index }.toMap()
    return withEntries(stored.map { it.copy(displayIndex = indexes.getValue(it.id)) })
}

internal data class WorldBookRelinkResult(
    val book: WorldBook,
    val cards: List<Card>,
    val groups: List<TavernGroup>,
    val personas: List<PersonaProfile>,
    val chats: List<Chat>,
    val relinkedCards: Int,
    val relinkedGroups: Int,
    val relinkedPersonas: Int,
    val relinkedChats: Int,
) {
    val totalRelinked get() = relinkedCards + relinkedGroups + relinkedPersonas + relinkedChats
}

internal fun Chat.relinkWorldBook(bookId: String, oldName: String, newName: String): Chat {
    val matchesById = options.worldBookId == bookId
    val matchesByName = options.worldBookId.isBlank() && options.worldBookName == oldName
    if (!matchesById && !matchesByName) return this
    val updatedOptions = options.copy(worldBookId = bookId, worldBookName = newName)
    val updatedHeader = header?.let { raw ->
        runCatching {
            val json = JSONObject(raw)
            val meta = json.optJSONObject("chat_metadata")
            if (meta != null && (meta.string("tavern_native_world_book") == bookId || meta.string("world_info") == oldName)) {
                meta.put("tavern_native_world_book", bookId)
                meta.put("world_info", newName)
                json.toString()
            } else raw
        }.getOrDefault(raw)
    }
    return copy(options = updatedOptions, header = updatedHeader)
}

internal fun relinkWorldBookRename(
    book: WorldBook,
    oldName: String,
    newName: String,
    cards: List<Card>,
    groups: List<TavernGroup>,
    personas: List<PersonaProfile>,
    chats: List<Chat>,
): WorldBookRelinkResult {
    val renamedBook = book.renamed(newName)
    if (oldName == renamedBook.name) {
        return WorldBookRelinkResult(renamedBook, cards, groups, personas, chats, 0, 0, 0, 0)
    }
    var cardCount = 0
    val updatedCards = cards.map { card ->
        var next = card
        if (next.worldName == oldName) next = next.withWorld(renamedBook.name)
        if (oldName in next.additionalWorldBookIds) {
            next = next.withAdditionalWorldBooks(next.additionalWorldBookIds.map { if (it == oldName) book.id else it })
        }
        if (next != card) cardCount++
        next
    }
    var groupCount = 0
    val updatedGroups = groups.map { group ->
        if (group.worldBookName == oldName) {
            groupCount++
            group.withWorld(renamedBook.name)
        } else group
    }
    var personaCount = 0
    val updatedPersonas = personas.map { persona ->
        if (persona.worldId == oldName) {
            personaCount++
            persona.copy(worldId = book.id)
        } else persona
    }
    var chatCount = 0
    val updatedChats = chats.map { chat ->
        val next = chat.relinkWorldBook(book.id, oldName, renamedBook.name)
        if (next != chat) chatCount++
        next
    }
    return WorldBookRelinkResult(
        book = renamedBook,
        cards = updatedCards,
        groups = updatedGroups,
        personas = updatedPersonas,
        chats = updatedChats,
        relinkedCards = cardCount,
        relinkedGroups = groupCount,
        relinkedPersonas = personaCount,
        relinkedChats = chatCount,
    )
}
