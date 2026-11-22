package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

internal enum class TagFolder(val label: String) {
    NONE("普通标签"), OPEN("开放文件夹"), CLOSED("隐藏成员的文件夹"),
}

internal enum class LibrarySort(val label: String) {
    NAME("名称 A–Z"), NAME_REVERSE("名称 Z–A"), RECENT("最近聊天"),
    MOST_CHATS("聊天数从多到少"), FEWEST_CHATS("聊天数从少到多"), FAVORITES("收藏优先"),
}

internal data class LibraryTag(
    val id: String = newId(),
    val name: String = "",
    val folder: TagFolder = TagFolder.NONE,
    val color: String = "",
    val textColor: String = "",
    val hidden: Boolean = false,
) {
    fun json() = jsonOf("id" to id, "name" to name, "folder_type" to folder.name, "color" to color,
        "color2" to textColor, "is_hidden_on_character_card" to hidden)

    companion object {
        fun from(row: JSONObject): LibraryTag? {
            val name = row.string("name").trim().takeIf(String::isNotBlank) ?: return null
            val id = row.string("id").ifBlank { libraryTagId(name) }
            return LibraryTag(id, name, TagFolder.entries.firstOrNull { it.name == row.string("folder_type").uppercase(Locale.ROOT) }
                ?: TagFolder.NONE, row.string("color"), row.string("color2"), row.optBoolean("is_hidden_on_character_card"))
        }
    }
}

internal data class LibraryUsage(val count: Int, val lastChat: Long)

internal data class LibraryItem(
    val id: String,
    val name: String,
    val detail: String,
    val card: Card? = null,
    val group: TavernGroup? = null,
    val tags: Set<String> = emptySet(),
    val favorite: Boolean = false,
)

internal fun libraryCardId(id: String) = "card:$id"
internal fun libraryGroupId(id: String) = "group:$id"

internal data class LibraryPreferences(
    val tags: List<LibraryTag> = emptyList(),
    val assignments: Map<String, Set<String>> = emptyMap(),
    val favorites: Map<String, Boolean> = emptyMap(),
    val folders: Boolean = false,
    val sort: LibrarySort = LibrarySort.NAME,
) {
    fun materialized(cards: List<Card>): LibraryPreferences {
        val byName = tags.associateByTo(linkedMapOf()) { it.name.tagKey() }
        val used = tags.mapTo(hashSetOf(), LibraryTag::id)
        val links = assignments.toMutableMap()
        for (card in cards) {
            val key = libraryCardId(card.id)
            if (key in links) continue
            links[key] = card.tags.asSequence().map(String::trim).filter(String::isNotBlank).map { name ->
                byName.getOrPut(name.tagKey()) {
                    val id = libraryTagId(name)
                    LibraryTag(id.takeIf(used::add) ?: newId().also(used::add), name)
                }.id
            }.toSet()
        }
        return copy(tags = byName.values.toList(), assignments = links)
    }

    fun entities(cards: List<Card>, groups: List<TavernGroup>): List<LibraryItem> =
        cards.map { card ->
            val key = libraryCardId(card.id)
            LibraryItem(key, card.name, card.description.ifBlank { card.creator }, card = card,
                tags = assignments[key].orEmpty(), favorite = favorites[key] ?: card.libraryFavorite)
        } + groups.map { group ->
            val key = libraryGroupId(group.id)
            LibraryItem(key, group.name, "${group.memberIds.size} 个角色 · ${group.order.label}", group = group,
                tags = assignments[key].orEmpty(), favorite = favorites[key] ?: group.favorite)
        }

    fun removeOwner(id: String) = copy(assignments = assignments - id, favorites = favorites - id)

    fun json() = jsonOf(
        "tags" to JSONArray(tags.map(LibraryTag::json)), "tag_map" to assignmentJson(assignments),
        "favorites" to JSONObject(favorites), "folders" to folders, "sort" to sort.name,
    )

    companion object {
        fun from(value: JSONObject?): LibraryPreferences {
            val tags = readLibraryTags(value)
            val ids = tags.mapTo(hashSetOf(), LibraryTag::id)
            val assigned = readAssignments(value?.optJSONObject("tag_map")).mapValues { (_, links) -> links.intersect(ids) }
            val favorites = value?.optJSONObject("favorites")
            return LibraryPreferences(tags, assigned, favorites?.keys()?.asSequence()?.associateWith(favorites::optBoolean).orEmpty(),
                value?.optBoolean("folders") == true, LibrarySort.entries.firstOrNull { it.name == value?.string("sort") }
                    ?: LibrarySort.NAME)
        }
    }
}

private fun String.tagKey() = trim().lowercase(Locale.ROOT)
private fun libraryTagId(name: String) = UUID.nameUUIDFromBytes(("library-tag:" + name.tagKey()).encodeToByteArray()).toString()

private fun readLibraryTags(value: JSONObject?) = value?.optJSONArray("tags")?.objects().orEmpty()
    .sortedBy { it.optInt("sort_order", Int.MAX_VALUE) }.mapNotNull(LibraryTag::from)
    .distinctBy(LibraryTag::id).distinctBy { it.name.tagKey() }

private fun readAssignments(value: JSONObject?) = value?.let {
    it.keys().asSequence().filter { key -> it.optJSONArray(key) != null }.associateWith { key ->
        it.optJSONArray(key).strings().toSet()
    }
}.orEmpty()

private fun assignmentJson(assignments: Map<String, Set<String>>) =
    JSONObject(assignments.mapValues { (_, tags) -> JSONArray(tags.toList()) })

internal data class LibraryTagImport(val preferences: LibraryPreferences, val added: Int, val unmatched: Int, val idMap: Map<String, String>)

internal fun LibraryPreferences.mergeTags(
    backup: JSONObject, ownerFor: (String) -> String?, preserveAssignments: Boolean = false,
    preserveEmptyAssignments: Boolean = false,
): LibraryTagImport {
    val rawTags = backup.optJSONArray("tags")?.objects().orEmpty().sortedBy { it.optInt("sort_order", Int.MAX_VALUE) }
    val byName = tags.associateByTo(linkedMapOf()) { it.name.tagKey() }
    val used = tags.mapTo(hashSetOf(), LibraryTag::id)
    val mapped = mutableMapOf<String, String>()
    for (raw in rawTags) {
        val item = LibraryTag.from(raw) ?: continue
        val actual = byName.getOrPut(item.name.tagKey()) {
            if (used.add(item.id)) item else item.copy(id = newId().also(used::add))
        }
        mapped[item.id] = actual.id
    }
    val links = assignments.toMutableMap()
    var unmatched = 0
    for ((owner, selected) in readAssignments(backup.optJSONObject("tag_map"))) {
        val key = ownerFor(owner)
        if (key == null) {
            if (selected.isNotEmpty()) unmatched++
            continue
        }
        if (preserveAssignments && key in assignments) continue
        val imported = selected.mapNotNullTo(linkedSetOf(), mapped::get)
        if (imported.isNotEmpty()) links[key] = links[key].orEmpty() + imported
        else if (preserveEmptyAssignments && selected.isEmpty() && key !in links) links[key] = emptySet()
    }
    return LibraryTagImport(copy(tags = byName.values.toList(), assignments = links), byName.size - tags.size, unmatched, mapped)
}

internal fun LibraryPreferences.tagBackup(entities: List<LibraryItem>): JSONObject {
    val owners = entities.associate { it.id to it.name }
    return jsonOf("tags" to JSONArray(tags.map(LibraryTag::json)),
        "tag_map" to assignmentJson(assignments.filterKeys(owners::containsKey)),
        "native_owners" to JSONObject(owners), "folders" to folders)
}

internal fun browseLibrary(
    entries: List<LibraryItem>, catalog: LibraryPreferences, usage: Map<String, LibraryUsage>, query: String,
    included: Set<String>, excluded: Set<String>, folder: String?, onlyFavorites: Boolean,
    advancedSearch: Boolean = false,
): List<LibraryItem> {
    val wanted = included + listOfNotNull(folder)
    val closed = if (catalog.folders && catalog.tags.none { it.id == folder && it.folder == TagFolder.CLOSED })
        catalog.tags.filter { it.folder == TagFolder.CLOSED && it.id !in wanted }.mapTo(hashSetOf(), LibraryTag::id)
    else emptySet()
    val names = catalog.tags.associate { it.id to it.name }
    val byName = compareBy<LibraryItem, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy(LibraryItem::id)
    val order = when (catalog.sort) {
        LibrarySort.NAME -> byName
        LibrarySort.NAME_REVERSE -> byName.reversed()
        LibrarySort.RECENT -> compareByDescending<LibraryItem> { usage[it.id]?.lastChat ?: 0L }.then(byName)
        LibrarySort.MOST_CHATS -> compareByDescending<LibraryItem> { usage[it.id]?.count ?: 0 }.then(byName)
        LibrarySort.FEWEST_CHATS -> compareBy<LibraryItem> { usage[it.id]?.count ?: 0 }.then(byName)
        LibrarySort.FAVORITES -> compareByDescending(LibraryItem::favorite).then(byName)
    }
    val term = query.trim()
    val search = if (advancedSearch && term.isNotEmpty()) CharacterLibrarySearch(term) else null
    return entries.filter { item ->
        if (onlyFavorites && !item.favorite || !item.tags.containsAll(wanted) || item.tags.any { it in excluded || it in closed }) {
            return@filter false
        }
        term.isEmpty() || (search?.matches(item, item.tags.mapNotNull(names::get)) ?:
            (item.name.contains(term, true) || item.detail.contains(term, true) ||
                item.card?.creator?.contains(term, true) == true || item.tags.any { names[it]?.contains(term, true) == true }))
    }.sortedWith(order)
}

internal fun LibraryPreferences.applyCardTagImport(
    existingCards: List<Card>, importedCards: List<Card>, mode: CardTagImport,
): LibraryPreferences {
    if (importedCards.isEmpty() || mode == CardTagImport.ALL || mode == CardTagImport.ASK) return this
    val current = materialized(existingCards)
    val tagIds = current.tags.associate { it.name.tagKey() to it.id }
    val assignments = importedCards.associate { card ->
        val tags = if (mode == CardTagImport.EXISTING) card.tags.mapNotNull { tagIds[it.tagKey()] }.toSet() else emptySet()
        libraryCardId(card.id) to tags
    }
    return current.copy(assignments = current.assignments + assignments)
}

internal fun libraryTagColor(value: String): Int? {
    val text = value.trim()
    if (text.startsWith('#')) {
        val hex = text.drop(1).let { if (it.length == 3 || it.length == 4) it.map { char -> "$char$char" }.joinToString("") else it }
        val number = hex.toLongOrNull(16) ?: return null
        return when (hex.length) {
            6 -> (0xff000000L or number).toInt()
            8 -> (((number and 0xff) shl 24) or (number shr 8)).toInt()
            else -> null
        }
    }
    if (text.startsWith("rgb(") || text.startsWith("rgba(")) {
        val raw = text.substringAfter('(').removeSuffix(")").split(',').map(String::trim)
        if (raw.size !in 3..4) return null
        val rgb = raw.take(3).map { it.toIntOrNull()?.takeIf { channel -> channel in 0..255 } ?: return null }
        val alpha = if (raw.size == 4) raw.last().toFloatOrNull()?.takeIf { it in 0f..1f } ?: return null else 1f
        return ((alpha * 255 + .5f).toInt() shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
    }
    return null
}
