package app.tavern.nativeclient.script

import app.tavern.nativeclient.Card
import app.tavern.nativeclient.Chat
import app.tavern.nativeclient.LibraryTag
import app.tavern.nativeclient.PersonaProfile
import app.tavern.nativeclient.Preferences
import app.tavern.nativeclient.TavernGroup
import app.tavern.nativeclient.TavernState
import app.tavern.nativeclient.libraryCardId
import app.tavern.nativeclient.libraryGroupId
import org.json.JSONArray

internal object ScriptTagPersonaCommands {
    val commandNames = setOf(
        "persona", "persona-set",
        "persona-lock", "lockpersona",
        "tempchat",
        "tag-add", "tagadd",
        "tag-remove", "tagremove",
        "tag-exists", "tagexists",
        "tag-list", "taglist",
    )

    suspend fun execute(
        command: String,
        named: Map<String, String>,
        text: String,
        state: TavernState,
        saveChat: suspend (Chat) -> Unit,
        savePrefs: suspend (Preferences) -> Unit,
        setTemporaryPersona: (String?) -> Unit = {},
        openTemporaryChat: suspend (String) -> String = { error("当前脚本环境不支持打开临时聊天") },
    ): String? = when (command) {
        "persona", "persona-set" -> persona(named, text, state, saveChat, savePrefs, setTemporaryPersona)
        "persona-lock", "lockpersona" -> personaLock(named, text, state, saveChat, savePrefs, setTemporaryPersona)
        "tempchat" -> openTemporaryChat(text.trim().ifBlank { "临时聊天" })
        "tag-add", "tagadd" -> tagAdd(named, text, state, savePrefs)
        "tag-remove", "tagremove" -> tagRemove(named, text, state, savePrefs)
        "tag-exists", "tagexists" -> tagExists(named, text, state)
        "tag-list", "taglist" -> tagList(named, text, state)
        else -> null
    }

    private suspend fun persona(
        named: Map<String, String>,
        text: String,
        state: TavernState,
        saveChat: suspend (Chat) -> Unit,
        savePrefs: suspend (Preferences) -> Unit,
        setTemporaryPersona: (String?) -> Unit,
    ): String {
        val query = (named["name"] ?: text).trim()
        if (query.isEmpty()) return state.persona.name
        val matched = findPersona(state.prefs.personas, query) ?: error("找不到人设 $query")
        val mode = named["mode"]?.trim()?.lowercase().orEmpty()
        when (mode) {
            "lookup" -> return matched.name
            "temp", "temporary" -> {
                setTemporaryPersona(matched.id)
                return matched.name
            }
            "lock", "chat" -> {
                val chat = requireNotNull(state.chat) { "请先打开对话" }
                saveChat(chat.copy(options = chat.options.copy(personaId = matched.id)))
                setTemporaryPersona(null)
                return matched.name
            }
            "all", "default", "" -> {
                savePrefs(state.prefs.copy(defaultPersonaId = matched.id))
                val chat = state.chat
                if (chat != null && (mode == "all" || chat.options.personaId != null)) {
                    saveChat(chat.copy(options = chat.options.copy(personaId = if (mode == "all") matched.id else null)))
                }
                setTemporaryPersona(if (mode == "default") null else matched.id)
                return matched.name
            }
            else -> error("未知的 /persona mode=$mode")
        }
    }

    private suspend fun personaLock(
        named: Map<String, String>,
        text: String,
        state: TavernState,
        saveChat: suspend (Chat) -> Unit,
        savePrefs: suspend (Preferences) -> Unit,
        setTemporaryPersona: (String?) -> Unit,
    ): String {
        val type = (named["type"] ?: "chat").trim().lowercase()
        val rawState = (named["state"] ?: text).trim().lowercase()
        val activePersona = state.persona
        return when (type) {
            "chat", "" -> {
                val chat = requireNotNull(state.chat) { "请先打开对话" }
                val currentlyLocked = chat.options.personaId != null
                val nextLocked = resolveToggleState(rawState, currentlyLocked)
                saveChat(chat.copy(options = chat.options.copy(personaId = if (nextLocked) activePersona.id else null)))
                if (nextLocked) setTemporaryPersona(null)
                nextLocked.toString()
            }
            "character", "char", "group", "subject" -> {
                val subjectId = requireNotNull(state.cardId ?: state.groupId) { "请先打开角色或群组" }
                val currentlyLocked = state.prefs.personaConnections.containsKey(subjectId)
                val nextLocked = resolveToggleState(rawState, currentlyLocked)
                val links = if (nextLocked) {
                    state.prefs.personaConnections + (subjectId to activePersona.id)
                } else {
                    state.prefs.personaConnections - subjectId
                }
                savePrefs(state.prefs.copy(personaConnections = links))
                if (nextLocked) setTemporaryPersona(null)
                nextLocked.toString()
            }
            else -> error("type 应为 chat 或 character")
        }
    }

    private fun resolveOwnerKey(state: TavernState, targetName: String?): String {
        val query = targetName?.trim().orEmpty()
        if (query.isEmpty()) {
            return state.cardId?.let(::libraryCardId)
                ?: state.groupId?.let(::libraryGroupId)
                ?: error("请先打开角色或群组，或用 name= 指定目标")
        }
        val card = findCard(state.cards, query)
        if (card != null) return libraryCardId(card.id)
        val group = findGroup(state.groups, query)
        if (group != null) return libraryGroupId(group.id)
        error("找不到角色或群组 $query")
    }

    private suspend fun tagAdd(
        named: Map<String, String>,
        text: String,
        state: TavernState,
        savePrefs: suspend (Preferences) -> Unit,
    ): String {
        val tagName = text.trim()
        require(tagName.isNotEmpty()) { "请输入要添加的标签名称" }
        val ownerKey = resolveOwnerKey(state, named["name"])
        val library = state.prefs.library.materialized(state.cards)
        val existing = library.tags.firstOrNull { it.name.equals(tagName, ignoreCase = true) }
        val tag = existing ?: LibraryTag(name = tagName)
        val updatedTags = if (existing == null) library.tags + tag else library.tags
        val currentIds = library.assignments[ownerKey].orEmpty()
        val updatedLinks = library.assignments + (ownerKey to (currentIds + tag.id))
        savePrefs(state.prefs.copy(library = library.copy(tags = updatedTags, assignments = updatedLinks)))
        return tag.name
    }

    private suspend fun tagRemove(
        named: Map<String, String>,
        text: String,
        state: TavernState,
        savePrefs: suspend (Preferences) -> Unit,
    ): String {
        val tagName = text.trim()
        require(tagName.isNotEmpty()) { "请输入要移除的标签名称" }
        val ownerKey = resolveOwnerKey(state, named["name"])
        val library = state.prefs.library.materialized(state.cards)
        val tag = library.tags.firstOrNull { it.name.equals(tagName, ignoreCase = true) } ?: return ""
        val currentIds = library.assignments[ownerKey].orEmpty()
        if (tag.id !in currentIds) return ""
        val updatedLinks = library.assignments + (ownerKey to (currentIds - tag.id))
        savePrefs(state.prefs.copy(library = library.copy(assignments = updatedLinks)))
        return tag.name
    }

    private fun tagExists(named: Map<String, String>, text: String, state: TavernState): String {
        val tagName = text.trim()
        if (tagName.isEmpty()) return "false"
        val ownerKey = resolveOwnerKey(state, named["name"])
        val library = state.prefs.library.materialized(state.cards)
        val tag = library.tags.firstOrNull { it.name.equals(tagName, ignoreCase = true) } ?: return "false"
        return (tag.id in library.assignments[ownerKey].orEmpty()).toString()
    }

    private fun tagList(named: Map<String, String>, text: String, state: TavernState): String {
        val library = state.prefs.library.materialized(state.cards)
        val scope = named["scope"]?.trim()?.lowercase().orEmpty()
        val names = if (scope == "all") {
            library.tags.map(LibraryTag::name)
        } else {
            val ownerKey = resolveOwnerKey(state, named["name"] ?: text.takeIf(String::isNotBlank))
            val assigned = library.assignments[ownerKey].orEmpty()
            library.tags.filter { it.id in assigned }.map(LibraryTag::name)
        }
        val format = named["format"]?.trim()?.lowercase().orEmpty()
        return if (format == "csv" || format == "text") {
            names.joinToString(named["separator"] ?: ", ")
        } else {
            JSONArray(names).toString()
        }
    }

    private fun findPersona(personas: List<PersonaProfile>, query: String): PersonaProfile? =
        personas.firstOrNull { it.id == query }
            ?: personas.firstOrNull { it.name.equals(query, ignoreCase = true) }
            ?: personas.firstOrNull { it.name.startsWith(query, ignoreCase = true) }
            ?: personas.firstOrNull { it.name.contains(query, ignoreCase = true) }

    private fun findCard(cards: List<Card>, query: String): Card? =
        cards.firstOrNull { it.id == query || it.name.equals(query, ignoreCase = true) }
            ?: cards.firstOrNull { it.name.startsWith(query, ignoreCase = true) }

    private fun findGroup(groups: List<TavernGroup>, query: String): TavernGroup? =
        groups.firstOrNull { it.id == query || it.name.equals(query, ignoreCase = true) }
            ?: groups.firstOrNull { it.name.startsWith(query, ignoreCase = true) }

    private fun resolveToggleState(raw: String, current: Boolean): Boolean = when (raw) {
        "", "toggle" -> !current
        "on", "true", "1", "yes", "lock" -> true
        "off", "false", "0", "no", "unlock" -> false
        else -> error("无效的状态参数 $raw，应为 on/off/toggle")
    }
}
