package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject

internal data class LoreCharacterFilter(
    val names: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val exclude: Boolean = false,
) {
    val isEmpty get() = names.isEmpty() && tags.isEmpty()

    fun applies(cardId: String, filename: String, tagIds: Set<String>): Boolean {
        val named = names.any { it == cardId || it.loreFilenameStem() == filename }
        val tagged = tags.any(tagIds::contains)
        return if (exclude) !named && !tagged else (names.isEmpty() || named) && (tags.isEmpty() || tagged)
    }

    fun json() = jsonOf("names" to JSONArray(names), "tags" to JSONArray(tags), "isExclude" to exclude)

    companion object {
        fun from(value: JSONObject?) = LoreCharacterFilter(
            value?.optJSONArray("names").strings().filter(String::isNotBlank).distinct(),
            value?.optJSONArray("tags").strings().filter(String::isNotBlank).distinct(),
            value?.optBoolean("isExclude") == true,
        )
    }
}

internal fun String.loreFilenameStem() = if (endsWith(".png", ignoreCase = true)) dropLast(4) else this

internal fun LibraryPreferences.loreTags(card: Card): Set<String> {
    val owner = libraryCardId(card.id)
    return assignments[owner] ?: materialized(listOf(card)).assignments[owner].orEmpty()
}

internal fun List<Card>.uniqueLoreFilenames(): Map<String, String> =
    groupBy { it.scriptAvatarKey.loreFilenameStem() }.mapNotNull { (name, matches) ->
        matches.singleOrNull()?.let { name to it.id }
    }.toMap()

internal fun WorldBook.remapLoreFilters(names: Map<String, String>, tags: Map<String, String> = emptyMap()): WorldBook {
    val mapped = entries.map { entry ->
        val filter = entry.rules.characterFilter
        if (filter.isEmpty) entry else entry.copy(rules = entry.rules.copy(characterFilter = filter.copy(
            names = filter.names.map { names[it] ?: names[it.loreFilenameStem()] ?: it }.distinct(),
            tags = filter.tags.map { tags[it] ?: it }.distinct(),
        )))
    }
    return if (mapped == entries) this else withEntries(mapped)
}
