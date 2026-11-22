package app.tavern.nativeclient

import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

internal enum class CharacterSubtitle(val field: String, val label: String) {
    DESCRIPTION("description", "简介"), VERSION("character_version", "角色卡版本"), CREATOR("creator", "创作者");

    fun text(card: Card): String = when (this) {
        DESCRIPTION -> card.description.ifBlank { card.creator }
        VERSION -> card.version.takeIf(String::isNotBlank)?.let { "版本 $it" }.orEmpty()
        CREATOR -> card.creator
    }
}

internal enum class CardTagImport(val originalId: Int, val label: String) {
    ASK(1, "每次询问"), NONE(2, "不添加标签"), ALL(3, "添加全部标签"), EXISTING(4, "仅添加已有标签")
}

internal data class CharacterLibraryOptions(
    val advancedSearch: Boolean = false,
    val subtitle: CharacterSubtitle = CharacterSubtitle.DESCRIPTION,
    val spoilerFree: Boolean = false,
    val grid: Boolean = false,
    val tagImport: CardTagImport = CardTagImport.ALL,
    val showAvatarFilename: Boolean = false,
    val keepAvatarSize: Boolean = false,
    val askToImportLore: Boolean = false,
) {
    fun write(target: JSONObject) {
        target.put("fuzzy_search", advancedSearch)
            .put("aux_field", subtitle.field)
            .put("spoiler_free_mode", spoilerFree)
            .put("charListGrid", grid)
            .put("tag_import_setting", tagImport.originalId)
            .put("show_card_avatar_urls", showAvatarFilename)
            .put("never_resize_avatars", keepAvatarSize)
            .put("world_import_dialog", askToImportLore)
    }

    fun json() = JSONObject().also(::write)

    companion object {
        fun from(row: JSONObject?, fallback: CharacterLibraryOptions = CharacterLibraryOptions()): CharacterLibraryOptions {
            if (row == null) return fallback
            val tagMode = row.optInt("tag_import_setting", fallback.tagImport.originalId)
            return CharacterLibraryOptions(
                advancedSearch = row.optBoolean("fuzzy_search", fallback.advancedSearch),
                subtitle = CharacterSubtitle.entries.firstOrNull { it.field == row.string("aux_field") } ?: fallback.subtitle,
                spoilerFree = row.optBoolean("spoiler_free_mode", fallback.spoilerFree),
                grid = row.optBoolean("charListGrid", fallback.grid),
                tagImport = CardTagImport.entries.firstOrNull { it.originalId == tagMode } ?: fallback.tagImport,
                showAvatarFilename = row.optBoolean("show_card_avatar_urls", fallback.showAvatarFilename),
                keepAvatarSize = row.optBoolean("never_resize_avatars", fallback.keepAvatarSize),
                askToImportLore = row.optBoolean("world_import_dialog", fallback.askToImportLore),
            )
        }
    }
}

private val searchWords = Regex("""[\p{L}\p{N}]+""")
private val latinCombiningMarks = Regex("""(?<=\p{IsLatin})\p{M}+""")

internal class CharacterLibrarySearch(query: String) {
    private val normalized = searchText(query)
    private val terms = searchWords.findAll(normalized).map { it.value }.toList()

    fun matches(item: LibraryItem, tagNames: List<String>): Boolean {
        val card = item.card
        val characterFields = if (card == null) emptySequence() else sequenceOf(
            card.scenario, card.personality, card.greeting, card.creator, card.creatorNotes, card.examples,
            card.version, card.instructions, card.postHistory, card.worldName,
        )
        val fields = sequenceOf(item.name, item.detail) + tagNames.asSequence() + characterFields
        val texts = fields.filter(String::isNotBlank).map(::searchText).toList()
        if (texts.any { normalized in it }) return true
        val words = texts.asSequence().flatMap { searchWords.findAll(it).map(MatchResult::value) }
        return terms.isNotEmpty() && terms.all { term ->
            texts.any { term in it } || term.length in 4..40 && words.any { oneEditApart(term, it) }
        }
    }
}

private fun searchText(text: String): String {
    val folded = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(latinCombiningMarks, "")
    return Normalizer.normalize(folded, Normalizer.Form.NFC)
}

private fun oneEditApart(query: String, candidate: String): Boolean {
    if (abs(query.length - candidate.length) > 1) return false
    val short = if (query.length <= candidate.length) query else candidate
    val long = if (query.length <= candidate.length) candidate else query
    val index = short.indices.firstOrNull { short[it] != long[it] } ?: return true
    val remaining = short.length - index
    if (short.length < long.length) return short.regionMatches(index, long, index + 1, remaining)
    return short.regionMatches(index + 1, long, index + 1, remaining - 1) ||
        remaining >= 2 && short[index] == long[index + 1] && short[index + 1] == long[index] &&
        short.regionMatches(index + 2, long, index + 2, remaining - 2)
}
