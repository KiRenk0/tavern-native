package app.tavern.nativeclient.script

import app.tavern.nativeclient.*
import org.json.JSONArray

internal sealed interface ScriptWorldBinding {
    data class Conversation(val chat: Chat) : ScriptWorldBinding
    data class Character(val card: Card) : ScriptWorldBinding
    data class Persona(val prefs: Preferences) : ScriptWorldBinding
}

internal suspend fun worldBookCommand(
    command: String, named: Map<String, String>, text: String, state: TavernState,
    updateActivation: suspend (List<WorldBook>) -> Unit,
    createBoundBook: suspend (WorldBook, ScriptWorldBinding) -> Unit,
    notice: (String) -> Unit,
): String = when (command) {
    "world" -> {
        val selectors = text.split(',').map(String::trim).filter(String::isNotBlank).distinctBy(String::lowercase)
        val mode = named["state"].orEmpty().lowercase()
        val silent = named["silent"].orEmpty().scriptBoolean()
        val changes = if (selectors.isEmpty()) state.books.filter(WorldBook::enabled).map { it.copy(enabled = false) }
        else selectors.mapNotNull { selector ->
            val book = state.books.firstOrNull { it.id == selector || it.name.equals(selector, true) }
            if (book == null) {
                if (!silent) notice("找不到世界书 $selector")
                return@mapNotNull null
            }
            val enabled = when (mode) { "off" -> false; "toggle" -> !book.enabled; else -> true }
            book.copy(enabled = enabled).takeIf { it != book }
        }.distinctBy(WorldBook::id)
        if (changes.isNotEmpty()) {
            updateActivation(changes)
            if (!silent) notice(if (selectors.isEmpty()) "已停用全部全局世界书" else "已更新 ${changes.size} 本全局世界书")
        }
        ""
    }
    "getglobalbooks", "getgloballore", "getglobalwi" ->
        JSONArray(state.books.filter(WorldBook::enabled).map(WorldBook::name)).toString()
    "getchatbook", "getchatlore", "getchatwi" -> {
        val chat = state.chat
        val current = chat?.options?.worldBook(state.books)
        if (current != null) current.name
        else if (chat == null || !named["create"].orEmpty().scriptBoolean(default = true)) ""
        else {
            val book = newScriptBook(state, named["name"], "聊天世界书 ${chat.title}")
            createBoundBook(book, ScriptWorldBinding.Conversation(chat.copy(options = chat.options.copy(
                worldBookId = book.id, worldBookName = book.name,
            ))))
            book.name
        }
    }
    "getpersonabook", "getpersonalore", "getpersonawi" -> {
        val persona = state.persona
        val current = state.books.firstOrNull { it.id == persona.worldId }
        if (current != null) current.name
        else if (!named["create"].orEmpty().scriptBoolean()) ""
        else {
            val book = newScriptBook(state, named["name"], "人设世界书 ${persona.name}")
            val updated = persona.copy(worldId = book.id)
            val profiles = if (state.prefs.personas.any { it.id == persona.id })
                state.prefs.personas.map { if (it.id == persona.id) updated else it } else state.prefs.personas + updated
            createBoundBook(book, ScriptWorldBinding.Persona(state.prefs.copy(personas = profiles)))
            book.name
        }
    }
    else -> {
        val type = named["type"].orEmpty().ifBlank { "primary" }.lowercase()
        require(type in setOf("primary", "additional", "all")) { "type 须为 primary、additional 或 all" }
        require(state.group == null || text.isNotBlank()) { "请在群聊中指定要查询的角色" }
        val card = if (text.isBlank()) state.card else scriptActor(state.cards, text, state.card)
        val names = if (card == null) emptyList() else {
            val catalog = state.books.associateBy(WorldBook::id)
            val primary = card.worldName.takeIf { name -> state.books.any { it.name == name } }
            val additional = card.additionalWorldBookIds.mapNotNull { catalog[it]?.name }
            val existing = when (type) { "primary" -> listOfNotNull(primary); "additional" -> additional; else -> listOfNotNull(primary) + additional }
            if (existing.isNotEmpty() || !named["create"].orEmpty().scriptBoolean()) existing.distinct() else {
                val book = newScriptBook(state, named["name"], "角色世界书 ${card.name}")
                val bound = if (type == "additional") card.withAdditionalWorldBooks(card.additionalWorldBookIds + book.id)
                else card.withWorld(book.name)
                createBoundBook(book, ScriptWorldBinding.Character(bound))
                listOf(book.name)
            }
        }
        if (type == "primary") names.firstOrNull().orEmpty() else JSONArray(names).toString()
    }
}

private fun newScriptBook(state: TavernState, supplied: String?, fallback: String): WorldBook {
    val name = supplied?.trim()?.takeIf(String::isNotBlank)
    if (name != null) {
        require(state.books.none { it.name.equals(name, true) }) { "世界书名称已存在" }
        return WorldBook(name = name)
    }
    val base = fallback.trim().take(64).trimEnd()
    val available = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base ($it)" }
        .first { candidate -> state.books.none { it.name.equals(candidate, true) } }
    return WorldBook(name = available)
}
