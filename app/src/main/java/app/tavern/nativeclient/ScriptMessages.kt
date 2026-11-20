package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

internal data class MessageSelectionRequest(val chatId: String, val includeTools: Boolean)

internal fun String.scriptBoolean(default: Boolean = false): Boolean = when (trim().lowercase()) {
    "true", "yes", "on", "1" -> true
    "false", "no", "off", "0" -> false
    else -> default
}

private val indices = Regex("(-?\\d+)(?:\\s*-\\s*(-?\\d+))?")

internal fun scriptMessageRange(value: String, size: Int): IntRange? {
    val match = indices.matchEntire(value.trim()) ?: return null
    fun convert(text: String) = text.toIntOrNull()?.let { if (it < 0) size + it else it }
    val start = convert(match.groupValues[1]) ?: return null
    val end = match.groups[2]?.let { convert(it.value) ?: return null } ?: start
    return (start..end).takeIf { start in 0 until size && end in start until size }
}

internal val Message.scriptRole get() = if (role == "assistant" && regexSite == RegexSite.COMMAND) "system" else role

internal fun Message.withScriptRole(selected: String, characterId: String?, personaId: String?): Message {
    val json = raw?.let(::JSONObject) ?: JSONObject()
    val extra = json.optJSONObject("extra") ?: JSONObject().also { json.put("extra", it) }
    if (selected == "system") extra.put("type", "narrator")
    else if (extra.string("type") == "narrator") extra.remove("type")
    return copy(
        role = if (selected == "user") "user" else "assistant", raw = json.toString(),
        characterId = characterId, personaId = personaId, groupTurnId = null, summary = null, loreEffects = null,
    )
}

internal fun Message.withoutAlternative(option: Int): Message {
    require(variants.size > 1 && option in variants.indices) { "找不到可删除的备选回复" }
    val newIndex = when {
        option < index -> index - 1
        option > index -> index
        else -> minOf(option, variants.size - 2)
    }
    val originalIndices = variants.indices.filterNot { it == option }
    val alternativeReasoning = originalIndices.map { if (it == index) reasoning else variantReasoning.getOrNull(it) }
    val info = raw?.let(::JSONObject)?.apply {
        optJSONArray("swipe_info")?.let { existing ->
            put("swipe_info", JSONArray(originalIndices.map { existing.opt(it) ?: JSONObject() }))
        }
    }?.toString()
    val changed = copy(variants = originalIndices.map(variants::get), variantReasoning = alternativeReasoning,
        index = newIndex, raw = info)
    if (option != index) return changed
    return changed.copy(text = changed.variants[newIndex], reasoning = alternativeReasoning[newIndex],
        translation = null, wireText = null, summary = null)
}

internal fun Message.withScriptAlternative(value: String, select: Boolean): Message {
    val selected = withAlternative(value)
    val json = raw?.let(::JSONObject) ?: JSONObject()
    val info = json.optJSONArray("swipe_info")
    val rows = variants.ifEmpty { listOf(text) }.indices.map { row -> info?.optJSONObject(row) ?: JSONObject() }
    val added = jsonOf("send_date" to Instant.now().toString(),
        "extra" to jsonOf("api" to "manual", "model" to "slash command"))
    json.put("swipe_info", JSONArray(rows + added))
    if (select) return selected.copy(raw = json.toString())
    return copy(variants = selected.variants, variantReasoning = selected.variantReasoning, raw = json.toString())
}

internal fun List<Message>.deletionIndices(selected: Set<Int>, includeTools: Boolean): Set<Int> = buildSet {
    selected.forEach { index ->
        val message = getOrNull(index) ?: return@forEach
        add(index)
        if (includeTools && message.role == "assistant" && !message.hidden) {
            var previous = index - 1
            while (getOrNull(previous)?.toolTurn != null) add(previous--)
        }
    }
}
