package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.Locale

internal enum class ScriptCharacterCommand(val keyword: String, vararg val aliases: String) {
    FIND("char-find", "findchar"),
    GET("char-get", "char-data"),
    CREATE("char-create"),
    UPDATE("char-update"),
    DUPLICATE("char-duplicate", "dupe"),
    DELETE("char-delete");

    companion object {
        fun from(name: String) = entries.firstOrNull { name == it.keyword || name in it.aliases }
    }
}

private val cardTextArguments = mapOf(
    "name" to "name", "description" to "description", "firstMessage" to "first_mes",
    "personality" to "personality", "scenario" to "scenario", "messageExamples" to "mes_example",
    "creatorNotes" to "creator_notes", "systemPrompt" to "system_prompt",
    "postHistoryInstructions" to "post_history_instructions", "creator" to "creator",
    "characterVersion" to "character_version",
)

internal data class ScriptCardChanges(
    val fields: Map<String, String>,
    val tags: List<String>?,
    val favorite: Boolean?,
    val talkativeness: Double?,
    val world: String?,
    val depthPrompt: String?,
    val depth: Int?,
    val role: PromptRole?,
) {
    fun apply(card: Card): Card {
        var updated = card
        if (fields.isNotEmpty()) updated = updated.edited(fields, updated.alternateGreetings)
        tags?.let { updated = updated.withCreatorTags(it) }
        world?.let { updated = updated.withWorld(it) }
        favorite?.let { updated = updated.withLibraryFavorite(it) }
        talkativeness?.let { updated = updated.withTalkativeness(it) }
        if (depthPrompt != null || depth != null || role != null) {
            updated = updated.withDepthPrompt(depthPrompt ?: card.depthPrompt, depth ?: card.depthPromptDepth,
                role ?: card.depthPromptRole)
        }
        return updated
    }

    fun create(): Card {
        val blank = Card.blank()
        val root = blank.portableJson().put("create_date", Instant.now().toString())
        return apply(blank.copy(original = root.toString())).withScriptAvatarKey("${blank.id}.png")
    }

    companion object {
        fun from(named: Map<String, String>): ScriptCardChanges {
            val fields = cardTextArguments.mapNotNull { (arg, prop) ->
                named[arg]?.let { input ->
                    val value = if (prop == "name") input.trim().also { require(it.isNotEmpty()) { "请输入角色名称" } }
                        else input
                    prop to value
                }
            }.toMap()
            val talk = named["talkativeness"]?.let { value ->
                requireNotNull(value.trim().toDoubleOrNull()?.takeIf { it in 0.0..1.0 }) { "发言积极度须在 0 到 1 之间" }
            }
            val depth = named["depthPromptDepth"]?.let { value ->
                requireNotNull(value.trim().toIntOrNull()?.takeIf { it in 0..80 }) { "角色提示词深度须为 0 到 80 的整数" }
            }
            val role = named["depthPromptRole"]?.let { value ->
                requireNotNull(PromptRole.entries.firstOrNull { it.wire.equals(value.trim(), true) }) {
                    "角色提示词角色须为 system、user 或 assistant"
                }
            }
            return ScriptCardChanges(fields, named["tags"]?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)
                ?.distinct(), named["favorite"]?.scriptBoolean(), talk, named["world"], named["depthPrompt"], depth, role)
        }
    }
}

internal fun findScriptCharacter(state: TavernState, name: String, tags: List<String>, preferCurrent: Boolean): Pair<Card?, Boolean> {
    val library = state.prefs.library.materialized(state.cards)
    val keys = tags.map { it.trim().lowercase(Locale.ROOT) }.filter(String::isNotEmpty).distinct()
    val matchingTagIds = keys.map { key -> library.tags.firstOrNull { it.name.lowercase(Locale.ROOT) == key }?.id ?: return null to false }
    val candidates = state.cards.filter { library.assignments[libraryCardId(it.id)].orEmpty().containsAll(matchingTagIds) }
    val preferred = if (preferCurrent) state.group?.members(candidates)
        ?: listOfNotNull(state.card?.takeIf { it in candidates }) else emptyList()
    if (name.isBlank()) return (preferred.firstOrNull() ?: candidates.firstOrNull()) to (candidates.size > 1)
    val chosen = preferred.firstNotNullOfOrNull { scriptActor(listOf(it), name, it) } ?: scriptActor(candidates, name)
    val ambiguous = candidates.count { scriptActor(listOf(it), name) != null } > 1
    return chosen to ambiguous
}

internal fun scriptFindTags(raw: String): List<String> {
    val value = raw.trim()
    return if (value.startsWith('[')) JSONArray(value).strings() else value.split(',').map(String::trim).filter(String::isNotEmpty)
}

internal fun TavernState.scriptCardTarget(arguments: Map<String, String>): Card? {
    val explicit = (arguments["char"] ?: arguments["name"])?.takeIf(String::isNotBlank)
    return if (explicit == null) regexCard() else scriptActor(cards, explicit, card)
}

internal fun Card.scriptCardValue(field: String?, favorite: Boolean, activeChat: String?): Any? {
    val root = JSONObject(original)
    val data = root.optJSONObject("data") ?: root
    return when (field?.lowercase(Locale.ROOT)) {
        null, "" -> jsonOf(
            "avatar" to scriptAvatarKey, "name" to name, "description" to description, "personality" to personality,
            "scenario" to scenario, "first_mes" to greeting, "mes_example" to examples, "creator_notes" to creatorNotes,
            "system_prompt" to instructions, "post_history_instructions" to postHistory, "creator" to creator,
            "character_version" to version, "tags" to JSONArray(tags), "talkativeness" to talkativeness,
            "fav" to favorite, "chat" to (activeChat ?: root.string("chat")), "create_date" to root.string("create_date"),
        )
        "avatar" -> scriptAvatarKey
        "fav", "favorite" -> favorite
        "description", "desc" -> description
        "first_mes", "greeting" -> greeting
        "mes_example", "examples" -> examples
        "creator_notes", "notes" -> creatorNotes
        "system_prompt", "instructions" -> instructions
        "post_history_instructions", "posthistory" -> postHistory
        "tags" -> tags.joinToString(", ")
        "talkativeness" -> talkativeness
        "world" -> worldName
        "character_version", "version" -> version
        "chat" -> activeChat ?: root.string("chat")
        "json" -> root
        else -> (data.opt(field) ?: root.opt(field))?.takeUnless { it == JSONObject.NULL }
    }
}
