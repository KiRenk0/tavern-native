package app.tavern.nativeclient.script

import app.tavern.nativeclient.Card
import app.tavern.nativeclient.Chat
import app.tavern.nativeclient.GalleryImage
import app.tavern.nativeclient.LoreEntry
import app.tavern.nativeclient.LorePosition
import app.tavern.nativeclient.Preferences
import app.tavern.nativeclient.TavernState
import app.tavern.nativeclient.WorldBook
import app.tavern.nativeclient.estimatedTokens
import app.tavern.nativeclient.jsonOf
import org.json.JSONArray
import org.json.JSONObject

internal object ScriptExtensions {
    private val keySplitPattern = Regex("[,，\\n]+")

    suspend fun execute(
        name: String,
        named: Map<String, String>,
        text: String,
        state: TavernState,
        saveChat: suspend (Chat) -> Unit,
        savePrefs: suspend (Preferences) -> Unit = {},
        saveBook: suspend (WorldBook) -> Unit = { error("当前脚本环境不支持修改世界书") },
        clearVariables: suspend (Boolean) -> Unit = {},
        galleryImages: suspend (Chat) -> List<GalleryImage> = { state.imageGallery?.items.orEmpty() },
    ): String? = when (name) {
        "wi-get-book", "world-get" -> getBook(state, named, text, includeEntries = true)
        "wi-list-entries" -> listEntries(state, named, text)
        "wi-get-entry" -> getEntry(state, named, text)
        "wi-set-entry" -> setEntry(state, named, text, saveBook)
        "wi-create-entry" -> createEntry(state, named, text, saveBook)
        "wi-delete-entry" -> deleteEntry(state, named, text, saveBook)
        "preset" -> switchPreset(state, text, saveChat)
        "bg" -> switchBackground(state, named, text, saveChat, galleryImages)
        "char-get" -> readCharacterField(state, named, text)
        "listvar" -> listVariables(state, named, text)
        "flushvar" -> "".also { clearVariables(false) }
        "flushglobalvar" -> "".also { clearVariables(true) }
        "re-replace" -> regexReplace(named, text)
        "trimtokens" -> trimTokens(named, text)
        else -> {
            // Avoid unused parameter warning while keeping extension signature uniform
            savePrefs.let { null }
        }
    }

    private fun resolveBook(state: TavernState, query: String?): WorldBook {
        val name = query?.trim().orEmpty()
        if (name.isNotEmpty()) {
            return state.books.firstOrNull { it.id == name || it.name.equals(name, ignoreCase = true) }
                ?: error("找不到世界书 $name")
        }
        val chat = state.chat
        val bound = chat?.options?.worldBook(state.books)
        if (bound != null) return bound
        val cardWorld = state.card?.worldName?.takeIf(String::isNotBlank)
        if (cardWorld != null) {
            state.books.firstOrNull { it.name.equals(cardWorld, ignoreCase = true) }?.let { return it }
        }
        error("请用 book= 指定世界书或先为对话绑定世界书")
    }

    private fun getBook(state: TavernState, named: Map<String, String>, text: String, includeEntries: Boolean): String {
        val book = resolveBook(state, named["book"] ?: text.takeIf(String::isNotBlank))
        return jsonOf(
            "id" to book.id,
            "name" to book.name,
            "enabled" to book.enabled,
            "scanDepth" to book.scanDepth,
            "entryCount" to book.entries.size,
            "entries" to if (includeEntries) JSONArray(book.entries.map(LoreEntry::json)) else JSONArray(),
        ).toString()
    }

    private fun listEntries(state: TavernState, named: Map<String, String>, text: String): String {
        val book = resolveBook(state, named["book"] ?: text.takeIf(String::isNotBlank))
        val array = JSONArray()
        book.entries.forEach { e ->
            array.put(jsonOf(
                "uid" to e.id,
                "comment" to e.title,
                "key" to JSONArray(e.keywords),
                "disable" to e.disabled,
                "constant" to e.constant,
                "order" to e.priority,
                "depth" to e.depth,
            ))
        }
        return array.toString()
    }

    private fun getEntry(state: TavernState, named: Map<String, String>, text: String): String {
        val book = resolveBook(state, named["book"])
        val uid = (named["uid"] ?: text.trim()).toIntOrNull() ?: error("请指定世界书条目 uid")
        val entry = book.entries.firstOrNull { it.id == uid } ?: error("找不到 uid=$uid 的世界书条目")
        val field = named["field"]?.trim()?.lowercase().orEmpty()
        return when (field) {
            "" -> entry.json().toString()
            "content" -> entry.content
            "comment", "title" -> entry.title
            "key", "keys" -> entry.keywords.joinToString(", ")
            "secondary", "keysecondary" -> entry.secondary.joinToString(", ")
            "disable", "disabled" -> entry.disabled.toString()
            "constant" -> entry.constant.toString()
            "order", "priority" -> entry.priority.toString()
            "depth" -> entry.depth.toString()
            "position" -> entry.position.name.lowercase()
            "role" -> entry.role
            else -> error("不支持读取的世界书字段 $field")
        }
    }

    private suspend fun setEntry(
        state: TavernState,
        named: Map<String, String>,
        text: String,
        saveBook: suspend (WorldBook) -> Unit,
    ): String {
        val book = resolveBook(state, named["book"])
        val uid = (named["uid"] ?: error("请用 uid= 指定世界书条目 ID")).toIntOrNull() ?: error("无效 uid")
        val existing = book.entries.firstOrNull { it.id == uid } ?: error("找不到 uid=$uid 的世界书条目")
        val field = (named["field"] ?: "content").trim().lowercase()
        val value = named["value"] ?: text
        val updated = when (field) {
            "content" -> existing.copy(content = value)
            "comment", "title" -> existing.copy(title = value)
            "key", "keys" -> existing.copy(keywords = splitKeys(value))
            "secondary", "keysecondary" -> existing.copy(secondary = splitKeys(value))
            "disable", "disabled" -> existing.copy(disabled = parseBool(value))
            "constant" -> existing.copy(constant = parseBool(value))
            "order", "priority" -> existing.copy(priority = value.trim().toIntOrNull() ?: error("无效 order 数值 $value"))
            "depth" -> existing.copy(depth = (value.trim().toIntOrNull() ?: error("无效 depth 数值 $value")).coerceIn(0, 80))
            "position" -> {
                val pos = value.trim().toIntOrNull()?.let { LorePosition.entries.getOrNull(it) }
                    ?: LorePosition.entries.firstOrNull { it.name.equals(value.trim(), true) }
                    ?: error("无效 position 值 $value")
                existing.copy(position = pos)
            }
            "role" -> {
                val r = value.trim().lowercase()
                require(r in setOf("system", "user", "assistant")) { "role 需为 system/user/assistant" }
                existing.copy(role = r)
            }
            else -> error("不支持修改的世界书字段 $field")
        }
        val list = book.entries.map { if (it.id == uid) updated else it }
        saveBook(book.withEntries(list))
        return value
    }

    private suspend fun createEntry(
        state: TavernState,
        named: Map<String, String>,
        text: String,
        saveBook: suspend (WorldBook) -> Unit,
    ): String {
        val book = resolveBook(state, named["book"])
        val newUid = (book.entries.maxOfOrNull(LoreEntry::id) ?: -1) + 1
        val created = LoreEntry(
            id = newUid,
            title = named["comment"] ?: named["title"].orEmpty(),
            content = named["content"] ?: text,
            keywords = splitKeys(named["key"] ?: named["keys"]),
            secondary = splitKeys(named["secondary"] ?: named["keysecondary"]),
            constant = parseBool(named["constant"]),
            disabled = parseBool(named["disable"] ?: named["disabled"]),
            priority = named["order"]?.trim()?.toIntOrNull() ?: 100,
            depth = named["depth"]?.trim()?.toIntOrNull()?.coerceIn(0, 80) ?: 4,
        )
        saveBook(book.withEntries(book.entries + created))
        return newUid.toString()
    }

    private suspend fun deleteEntry(
        state: TavernState,
        named: Map<String, String>,
        text: String,
        saveBook: suspend (WorldBook) -> Unit,
    ): String {
        val book = resolveBook(state, named["book"])
        val uid = (named["uid"] ?: text.trim()).toIntOrNull() ?: error("请指定要删除的条目 uid")
        val remaining = book.entries.filterNot { it.id == uid }
        require(remaining.size < book.entries.size) { "找不到 uid=$uid 的世界书条目" }
        saveBook(book.withEntries(remaining))
        return uid.toString()
    }

    private suspend fun switchPreset(state: TavernState, text: String, saveChat: suspend (Chat) -> Unit): String {
        val chat = requireNotNull(state.chat) { "请先打开对话" }
        val query = text.trim()
        if (query.isEmpty()) return state.presetFor(chat)?.name.orEmpty()
        if (query.equals("default", true) || query.equals("off", true) || query == "默认") {
            saveChat(chat.copy(options = chat.options.copy(promptPresetId = null)))
            return ""
        }
        val preset = state.presets.firstOrNull { it.id == query || it.name.equals(query, ignoreCase = true) }
            ?: error("找不到提示词预设 $query")
        saveChat(chat.copy(options = chat.options.copy(promptPresetId = preset.id)))
        return preset.name
    }

    private suspend fun switchBackground(
        state: TavernState,
        named: Map<String, String>,
        text: String,
        saveChat: suspend (Chat) -> Unit,
        galleryImages: suspend (Chat) -> List<GalleryImage>,
    ): String {
        val chat = requireNotNull(state.chat) { "请先打开对话" }
        val query = (named["file"] ?: text).trim()
        if (query.isEmpty()) return chat.options.background?.filename.orEmpty()
        if (query.equals("clear", true) || query.equals("none", true) || query == "无") {
            saveChat(chat.copy(options = chat.options.copy(background = null)))
            return ""
        }
        val gallery = galleryImages(chat)
        val matched = gallery.firstOrNull {
            it.image.filename.equals(query, true) || it.image.id == query || it.prompt.contains(query, true)
        }?.image ?: chat.messages.flatMap { it.displayImages }.firstOrNull {
            it.filename.equals(query, true) || it.id == query
        } ?: error("在当前图库与消息图片中找不到背景 $query")
        saveChat(chat.copy(options = chat.options.copy(background = matched)))
        return matched.filename
    }

    private fun readCharacterField(state: TavernState, named: Map<String, String>, text: String): String {
        val explicitCard = named["name"]?.trim()?.takeIf(String::isNotEmpty)?.let { name ->
            state.cards.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: error("找不到角色 $name")
        }
        val card: Card = explicitCard ?: state.regexCard() ?: error("当前没有打开角色卡")
        val field = (named["field"] ?: text.trim().ifBlank { "name" }).lowercase()
        return when (field) {
            "name" -> card.name
            "description", "desc" -> card.description
            "personality" -> card.personality
            "scenario" -> card.scenario
            "first_mes", "greeting" -> card.greeting
            "mes_example", "examples" -> card.examples
            "creator_notes", "notes" -> card.creatorNotes
            "system_prompt", "instructions" -> card.instructions
            "post_history_instructions", "posthistory" -> card.postHistory
            "tags" -> card.tags.joinToString(", ")
            "world" -> card.worldName
            "version" -> card.version
            "creator" -> card.creator
            "json" -> card.original
            else -> error("不支持读取的角色卡字段 $field")
        }
    }

    private fun listVariables(state: TavernState, named: Map<String, String>, text: String): String {
        val scope = (named["scope"] ?: text.trim().ifBlank { "local" }).lowercase()
        val local = JSONObject().apply {
            state.chat?.options?.variables.orEmpty().forEach { (k, v) -> put(k, v) }
        }
        val global = JSONObject().apply {
            state.prefs.scriptVariables.forEach { (k, v) -> put(k, v) }
        }
        return when (scope) {
            "global" -> global.toString()
            "all" -> jsonOf("local" to local, "global" to global).toString()
            else -> local.toString()
        }
    }

    private fun regexReplace(named: Map<String, String>, text: String): String {
        val pattern = named["find"] ?: named["pattern"] ?: error("请用 find= 或 pattern= 指定正则表达式")
        val replacement = named["replace"] ?: named["with"].orEmpty()
        val flags = named["flags"].orEmpty().lowercase()
        val options = buildSet {
            if ('i' in flags) add(RegexOption.IGNORE_CASE)
            if ('m' in flags) add(RegexOption.MULTILINE)
            if ('s' in flags) add(RegexOption.DOT_MATCHES_ALL)
        }
        val regex = runCatching { Regex(pattern, options) }.getOrElse { e ->
            throw IllegalArgumentException("无效正则表达式: ${e.message}", e)
        }
        val global = flags.isEmpty() || 'g' in flags
        return if (global) regex.replace(text, replacement) else regex.replaceFirst(text, replacement)
    }

    private fun trimTokens(named: Map<String, String>, text: String): String {
        val limit = (named["limit"] ?: named["tokens"])?.trim()?.toIntOrNull()?.takeIf { it > 0 }
            ?: error("请用 limit= 指定大于 0 的 Token 上限")
        if (text.isEmpty() || estimatedTokens.count(text) <= limit) return text
        val fromStart = named["direction"]?.trim()?.lowercase() == "start"
        if (fromStart) {
            var low = 0
            var high = text.length
            while (low < high) {
                var mid = (low + high) ushr 1
                if (mid in 1 until text.length && Character.isLowSurrogate(text[mid])) mid--
                if (estimatedTokens.count(text.substring(mid)) <= limit) {
                    high = mid
                } else {
                    low = mid + 1
                }
            }
            val start = if (high in 1 until text.length && Character.isLowSurrogate(text[high])) high + 1 else high
            return text.substring(start.coerceIn(0, text.length))
        } else {
            var low = 0
            var high = text.length
            while (low < high) {
                var mid = (low + high + 1) ushr 1
                if (mid in 1 until text.length && Character.isLowSurrogate(text[mid])) mid--
                if (estimatedTokens.count(text.substring(0, mid)) <= limit) {
                    low = mid
                } else {
                    high = mid - 1
                }
            }
            val end = if (low in 1 until text.length && Character.isLowSurrogate(text[low])) low - 1 else low
            return text.substring(0, end.coerceIn(0, text.length))
        }
    }

    private fun splitKeys(raw: String?): List<String> =
        raw.orEmpty().split(keySplitPattern).map(String::trim).filter(String::isNotBlank).distinct()

    private fun parseBool(raw: String?): Boolean =
        raw?.trim()?.lowercase() in setOf("true", "1", "yes", "on")
}
