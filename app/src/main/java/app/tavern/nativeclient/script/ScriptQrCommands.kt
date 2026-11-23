package app.tavern.nativeclient.script

import app.tavern.nativeclient.Preferences
import app.tavern.nativeclient.QuickEvent
import app.tavern.nativeclient.QuickReplies
import app.tavern.nativeclient.QuickReply
import app.tavern.nativeclient.QuickReplySet
import app.tavern.nativeclient.TavernState
import app.tavern.nativeclient.scriptBoolean
import org.json.JSONArray

internal object ScriptQrCommands {
    val commandNames = setOf(
        "qr-create", "qr-update", "qr-delete", "qr-get", "qr-list",
        "qrset-create", "qrset-delete", "qrset-list",
    )

    suspend fun execute(
        command: String,
        named: Map<String, String>,
        text: String,
        state: TavernState,
        savePrefs: suspend (Preferences) -> Unit,
    ): String? = when (command) {
        "qr-create" -> createQr(named, text, state, savePrefs)
        "qr-update" -> updateQr(named, text, state, savePrefs)
        "qr-delete" -> deleteQr(named, text, state, savePrefs)
        "qr-get" -> getQr(named, text, state)
        "qr-list" -> listQr(named, text, state)
        "qrset-create" -> createSet(named, text, state, savePrefs)
        "qrset-delete" -> deleteSet(named, text, state, savePrefs)
        "qrset-list" -> listSets(named, state)
        else -> null
    }

    private suspend fun createQr(
        named: Map<String, String>,
        text: String,
        state: TavernState,
        savePrefs: suspend (Preferences) -> Unit,
    ): String {
        val config = state.prefs.quickReplies
        val set = resolveSet(config, state, named["set"])
        val label = (named["label"] ?: error("请用 label= 指定快捷回复按钮文字")).trim()
        require(label.isNotEmpty()) { "label 不能为空" }
        val message = named["message"] ?: text
        val replace = named["replace"].orEmpty().scriptBoolean(default = false)
        val existing = set.items.firstOrNull { it.label.equals(label, ignoreCase = true) }
        if (existing != null && !replace) error("快捷回复集「${set.name}」中已存在「$label」")
        val id = existing?.id ?: ((set.items.maxOfOrNull(QuickReply::id) ?: 0) + 1)
        val created = QuickReply(
            id = id,
            label = label,
            message = message,
            title = named["title"].orEmpty(),
            hidden = named["hidden"].orEmpty().scriptBoolean(default = false),
            events = parseEvents(named, emptySet()),
            preventAutoExecute = named["preventAutoExecute"]?.scriptBoolean(default = true) ?: true,
            automationId = named["automationId"].orEmpty().trim(),
        )
        val updatedItems = if (existing == null) set.items + created
        else set.items.map { if (it.id == existing.id) created else it }
        saveSet(state, set.copy(items = updatedItems), savePrefs)
        return id.toString()
    }

    private suspend fun updateQr(
        named: Map<String, String>,
        text: String,
        state: TavernState,
        savePrefs: suspend (Preferences) -> Unit,
    ): String {
        val config = state.prefs.quickReplies
        val (set, existing) = resolveReply(config, state, named["set"], named["label"], named["id"], fallbackQuery = null)
        val newLabel = named["newlabel"]?.trim()?.takeIf(String::isNotEmpty) ?: existing.label
        require(set.items.none { it.id != existing.id && it.label.equals(newLabel, ignoreCase = true) }) {
            "快捷回复集「${set.name}」中已存在「$newLabel」"
        }
        val newMessage = named["message"] ?: if (text.isNotEmpty()) text else existing.message
        val updated = existing.copy(
            label = newLabel,
            message = newMessage,
            title = named["title"] ?: existing.title,
            hidden = named["hidden"]?.scriptBoolean(default = existing.hidden) ?: existing.hidden,
            events = parseEvents(named, existing.events),
            preventAutoExecute = named["preventAutoExecute"]?.scriptBoolean(default = existing.preventAutoExecute)
                ?: existing.preventAutoExecute,
            automationId = named["automationId"]?.trim() ?: existing.automationId,
        )
        saveSet(state, set.copy(items = set.items.map { if (it.id == existing.id) updated else it }), savePrefs)
        return updated.label
    }

    private suspend fun deleteQr(
        named: Map<String, String>,
        text: String,
        state: TavernState,
        savePrefs: suspend (Preferences) -> Unit,
    ): String {
        val config = state.prefs.quickReplies
        val (set, existing) = resolveReply(config, state, named["set"], named["label"], named["id"], fallbackQuery = text.trim())
        saveSet(state, set.copy(items = set.items.filterNot { it.id == existing.id }), savePrefs)
        return "true"
    }

    private fun getQr(named: Map<String, String>, text: String, state: TavernState): String {
        val config = state.prefs.quickReplies
        val (_, item) = resolveReply(config, state, named["set"], named["label"], named["id"], fallbackQuery = text.trim())
        val field = named["field"]?.trim()?.lowercase().orEmpty()
        return when (field) {
            "" -> item.json().toString()
            "id" -> item.id.toString()
            "label" -> item.label
            "message", "mes" -> item.message
            "title" -> item.title
            "hidden", "ishidden" -> item.hidden.toString()
            "automationid" -> item.automationId
            "preventautoexecute" -> item.preventAutoExecute.toString()
            else -> error("不支持读取的快捷回复字段 $field")
        }
    }

    private fun listQr(named: Map<String, String>, text: String, state: TavernState): String {
        val config = state.prefs.quickReplies
        val setQuery = (named["set"] ?: text).trim()
        val full = named["full"].orEmpty().scriptBoolean(default = false) ||
            named["return"]?.trim()?.lowercase() == "object"
        val items = when {
            setQuery.isNotEmpty() -> resolveSet(config, state, setQuery).items
            named["scope"].equals("all", true) -> config.sets.flatMap(QuickReplySet::items)
            else -> config.selected(state.chat).flatMap(QuickReplySet::items)
        }
        return if (full) JSONArray(items.map(QuickReply::json)).toString()
        else JSONArray(items.map(QuickReply::label)).toString()
    }

    private suspend fun createSet(
        named: Map<String, String>,
        text: String,
        state: TavernState,
        savePrefs: suspend (Preferences) -> Unit,
    ): String {
        val name = (named["name"] ?: text).trim()
        require(name.isNotEmpty()) { "请输入快捷回复集名称" }
        val config = state.prefs.quickReplies
        require(config.sets.none { it.name.equals(name, ignoreCase = true) }) { "快捷回复集「$name」已存在" }
        val created = QuickReplySet(
            name = name,
            disableSend = named["disableSend"].orEmpty().scriptBoolean(default = false),
            injectInput = named["injectInput"].orEmpty().scriptBoolean(default = false),
            placeBeforeInput = named["placeBeforeInput"].orEmpty().scriptBoolean(default = false),
        )
        val activate = named["active"]?.scriptBoolean(default = false)
            ?: named["activate"]?.scriptBoolean(default = false) ?: false
        val nextActive = if (activate) (config.active + created.id).distinct() else config.active
        savePrefs(state.prefs.copy(quickReplies = config.copy(
            enabled = config.enabled || activate,
            active = nextActive,
            sets = config.sets + created,
        )))
        return created.name
    }

    private suspend fun deleteSet(
        named: Map<String, String>,
        text: String,
        state: TavernState,
        savePrefs: suspend (Preferences) -> Unit,
    ): String {
        val query = (named["name"] ?: text).trim()
        require(query.isNotEmpty()) { "请指定要删除的快捷回复集名称" }
        val config = state.prefs.quickReplies
        val target = config.sets.firstOrNull { it.id == query || it.name.equals(query, ignoreCase = true) }
            ?: error("找不到快捷回复集 $query")
        savePrefs(state.prefs.copy(quickReplies = config.copy(
            active = config.active - target.id,
            sets = config.sets.filterNot { it.id == target.id },
        )))
        return "true"
    }

    private fun listSets(named: Map<String, String>, state: TavernState): String {
        val config = state.prefs.quickReplies
        val scope = named["scope"]?.trim()?.lowercase().orEmpty()
        val sets = when (scope) {
            "active", "global" -> config.active.mapNotNull { id -> config.sets.firstOrNull { it.id == id } }
            "chat" -> config.selected(state.chat)
            else -> config.sets
        }
        return JSONArray(sets.map(QuickReplySet::name)).toString()
    }

    private fun resolveSet(config: QuickReplies, state: TavernState, query: String?): QuickReplySet {
        val name = query?.trim().orEmpty()
        if (name.isNotEmpty()) {
            return config.sets.firstOrNull { it.id == name || it.name.equals(name, ignoreCase = true) }
                ?: error("找不到快捷回复集 $name")
        }
        return config.selected(state.chat).firstOrNull()
            ?: config.sets.firstOrNull()
            ?: error("请用 set= 指定快捷回复集，或先用 /qrset-create 创建")
    }

    private fun resolveReply(
        config: QuickReplies,
        state: TavernState,
        setQuery: String?,
        labelQuery: String?,
        idQuery: String?,
        fallbackQuery: String?,
    ): Pair<QuickReplySet, QuickReply> {
        val trimmedFallback = fallbackQuery?.trim().orEmpty()
        val explicitSet = setQuery?.trim().orEmpty()
        val dottedSet = if (explicitSet.isEmpty() && '.' in trimmedFallback && labelQuery == null && idQuery == null) {
            config.sets.firstOrNull { trimmedFallback.startsWith("${it.name}.") }
        } else null
        val targetSet = dottedSet ?: if (explicitSet.isNotEmpty()) resolveSet(config, state, explicitSet) else null
        val effectiveLabel = labelQuery?.trim()
            ?: dottedSet?.let { trimmedFallback.removePrefix("${it.name}.") }
            ?: trimmedFallback.takeIf { idQuery == null && it.isNotEmpty() }
        val numericId = idQuery?.trim()?.toIntOrNull() ?: effectiveLabel?.toIntOrNull()
        val searchSets = if (targetSet != null) listOf(targetSet)
        else (config.selected(state.chat) + config.sets).distinctBy(QuickReplySet::id)
        for (set in searchSets) {
            val found = set.items.firstOrNull { item ->
                (effectiveLabel != null && item.label.equals(effectiveLabel, ignoreCase = true)) ||
                    (idQuery != null && numericId != null && item.id == numericId)
            } ?: if (idQuery == null && numericId != null) set.items.firstOrNull { it.id == numericId } else null
            if (found != null) return set to found
        }
        error("找不到快捷回复 ${labelQuery ?: idQuery ?: trimmedFallback}")
    }

    private suspend fun saveSet(state: TavernState, updated: QuickReplySet, savePrefs: suspend (Preferences) -> Unit) {
        val config = state.prefs.quickReplies
        savePrefs(state.prefs.copy(quickReplies = config.copy(
            sets = config.sets.map { if (it.id == updated.id) updated else it },
        )))
    }

    private fun parseEvents(named: Map<String, String>, base: Set<QuickEvent>): Set<QuickEvent> {
        val map = mapOf(
            "startup" to QuickEvent.STARTUP,
            "chat" to QuickEvent.CHAT,
            "new" to QuickEvent.NEW,
            "user" to QuickEvent.USER,
            "before" to QuickEvent.BEFORE,
            "ai" to QuickEvent.AI,
            "bot" to QuickEvent.AI,
            "group" to QuickEvent.GROUP,
        )
        val result = base.toMutableSet()
        map.forEach { (key, event) ->
            val raw = named[key] ?: named[event.key]
            if (raw != null) {
                if (raw.scriptBoolean(default = false)) result += event else result -= event
            }
        }
        return result
    }
}
