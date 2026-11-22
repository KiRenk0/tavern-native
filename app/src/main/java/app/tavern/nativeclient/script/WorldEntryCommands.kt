package app.tavern.nativeclient.script

import app.tavern.nativeclient.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val booleanFields = setOf("constant", "vectorized", "selective", "addMemo", "disable", "ignoreBudget",
    "excludeRecursion", "preventRecursion", "useProbability", "groupOverride") + LoreSource.entries.map(LoreSource::field)
private val nullableBooleanFields = setOf("caseSensitive", "matchWholeWords", "useGroupScoring")
private val numberFields = setOf("selectiveLogic", "order", "position", "delayUntilRecursion", "probability", "depth", "groupWeight", "role")
private val nullableNumberFields = setOf("scanDepth", "sticky", "cooldown", "delay")
private val arrayFields = setOf("key", "keysecondary", "triggers")
private val filterFields = setOf("characterFilterNames", "characterFilterTags", "characterFilterExclude")
private val entryFields = booleanFields + nullableBooleanFields + numberFields + nullableNumberFields + arrayFields + filterFields +
    setOf("comment", "content", "outletName", "group", "automationId")
private val validTriggers = setOf("normal", "continue", "impersonate", "swipe", "regenerate", "quiet")
private val keywordParts = Regex("""\s*(/(?:\\.|[^/\\\r\n])+/[a-zA-Z]*)(?=\s*(?:[,，\r\n]|$))|\s*([^,，\r\n]+)""")

internal suspend fun worldEntryCommand(
    command: String, args: Map<String, String>, text: String, state: TavernState, book: WorldBook,
    saveBook: suspend (WorldBook) -> Unit,
): String {
    if (command in setOf("createentry", "createlore", "createwi")) {
        val id = book.nextEntryId()
        val key = args["key"]?.trim().orEmpty()
        val entry = LoreEntry(id, title = key, content = text, keywords = listOfNotNull(key.takeIf(String::isNotBlank)),
            selective = true, useProbability = true)
        saveBook(book.saveEditedEntry(entry))
        return id.toString()
    }
    val find = command in setOf("findentry", "findlore", "findwi")
    val field = args["field"]?.trim().orEmpty().ifBlank { if (find) "key" else "content" }
    require(field in entryFields) { "不支持的世界书条目字段 $field" }
    if (find) return withContext(Dispatchers.Default) { findEntry(book, state, field, text) { coroutineContext.ensureActive() } }
    val reading = command in setOf("getentryfield", "getlorefield", "getwifield")
    val uid = (if (reading) text.trim() else args["uid"])?.toIntOrNull() ?: error("请指定世界书条目编号")
    val entry = book.entries.firstOrNull { it.id == uid } ?: return ""
    if (reading) return fieldValue(entry, state, field)?.toString().orEmpty()
    saveBook(book.saveEditedEntry(writeField(entry, state, field, text)))
    return ""
}

private fun fieldValue(entry: LoreEntry, state: TavernState, field: String): Any? {
    val filter = entry.rules.characterFilter
    return when (field) {
        "characterFilterNames" -> JSONArray(filter.names.map { selector ->
            state.cards.firstOrNull { it.id == selector }?.scriptAvatarKey?.loreFilenameStem() ?: selector
        })
        "characterFilterTags" -> JSONArray(state.prefs.library.materialized(state.cards).tags.filter { it.id in filter.tags }.map(LibraryTag::name))
        "characterFilterExclude" -> filter.exclude
        "addMemo" -> entry.json().optBoolean(field, entry.title.isNotBlank())
        else -> entry.json().opt(field).takeUnless { it == JSONObject.NULL }
    }
}

private fun writeField(entry: LoreEntry, state: TavernState, field: String, text: String): LoreEntry {
    val value = text.trim()
    if (field in filterFields) {
        val previous = entry.rules.characterFilter
        val updated = when (field) {
            "characterFilterNames" -> previous.copy(names = parseList(value).mapNotNull { scriptActor(state.cards, it)?.id }.distinct())
            "characterFilterTags" -> {
                val tags = state.prefs.library.materialized(state.cards).tags
                previous.copy(tags = parseList(value).mapNotNull { name -> tags.firstOrNull { it.id == name || it.name.equals(name, true) }?.id }.distinct())
            }
            else -> previous.copy(exclude = value.scriptBoolean())
        }
        return entry.copy(rules = entry.rules.copy(characterFilter = updated))
    }
    val nullable = field in nullableBooleanFields || field in nullableNumberFields
    val typed: Any = when {
        nullable && (value.isEmpty() || value.equals("null", true)) -> JSONObject.NULL
        field in booleanFields || field in nullableBooleanFields -> value.scriptBoolean()
        field in numberFields || field in nullableNumberFields -> requireNotNull(value.toIntOrNull()) { "$field 需要整数" }
        field in arrayFields -> JSONArray(parseList(value).filter { field != "triggers" || it in validTriggers })
        else -> text
    }
    return LoreEntry.from(entry.id, entry.json().put(field, typed))
}

private fun parseList(text: String): List<String> = if (text.startsWith('[')) {
    val parsed = boundedJsonValue(text, "列表须为 JSON 数组") as? JSONArray ?: error("列表须为 JSON 数组")
    parsed.strings().map(String::trim).filter(String::isNotBlank).distinct()
} else keywordParts.findAll(text).map { it.value.trim() }.filter(String::isNotBlank).distinct().toList()

private fun findEntry(book: WorldBook, state: TavernState, field: String, input: String, checkActive: () -> Unit): String {
    val query = input.trim().lowercase()
    if (query.isEmpty()) return ""
    val normalized = if (field in booleanFields + nullableBooleanFields + "characterFilterExclude") {
        when (query) { "on", "yes", "1" -> "true"; "off", "no", "0" -> "false"; else -> query }
    } else query
    return book.entries.asSequence().mapNotNull { entry ->
        checkActive()
        val value = fieldValue(entry, state, field) ?: return@mapNotNull null
        val values = if (value is JSONArray) value.strings() else listOf(value.toString())
        val score = values.mapNotNull { matchScore(it.lowercase(), normalized, checkActive) }.minOrNull() ?: return@mapNotNull null
        score to entry.id
    }.minWithOrNull(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })?.second?.toString().orEmpty()
}

private val searchWords = Regex("[\\p{L}\\p{N}_-]+")

private fun matchScore(value: String, query: String, checkActive: () -> Unit): Int? {
    if (value == query) return 0
    if (value.contains(query)) return 1 + (if (value.startsWith(query)) 0 else 1)
    val allowed = (query.length * 3 / 10).coerceAtMost(24)
    if (allowed == 0) return null
    return (sequenceOf(value) + searchWords.findAll(value).map { it.value })
        .filter { kotlin.math.abs(it.length - query.length) <= allowed }
        .map { checkActive(); editDistance(it, query) }.filter { it <= allowed }.minOrNull()?.plus(3)
}

private fun editDistance(value: String, query: String): Int {
    val row = IntArray(query.length + 1) { it }
    for ((index, char) in value.withIndex()) {
        var previous = row[0]
        row[0] = index + 1
        for (i in query.indices) {
            val old = row[i + 1]
            row[i + 1] = minOf(old + 1, row[i] + 1, previous + if (query[i] == char) 0 else 1)
            previous = old
        }
    }
    return row.last()
}
