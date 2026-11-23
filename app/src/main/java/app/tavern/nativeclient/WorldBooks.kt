package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject

internal enum class LoreLogic(val label: String) {
    AND_ANY("任意次关键词"), NOT_ALL("不同时匹配全部"), NOT_ANY("不匹配任何次关键词"), AND_ALL("匹配全部次关键词"),
}

internal enum class LorePosition(val label: String) {
    BEFORE("角色设定之前"), AFTER("角色设定之后"), AUTHOR_TOP("作者注释之前"), AUTHOR_BOTTOM("作者注释之后"),
    DEPTH("聊天历史指定深度"), EXAMPLE_TOP("对话示例之前"), EXAMPLE_BOTTOM("对话示例之后"), OUTLET("锚点"),
}

internal data class LoreEntry(
    val id: Int,
    val title: String = "",
    val content: String = "",
    val keywords: List<String> = emptyList(),
    val secondary: List<String> = emptyList(),
    val logic: LoreLogic = LoreLogic.AND_ANY,
    val constant: Boolean = false,
    val disabled: Boolean = false,
    val caseSensitive: Boolean? = null,
    val wholeWords: Boolean? = null,
    val priority: Int = 100,
    val scanDepth: Int? = null,
    val probability: Int = 100,
    val position: LorePosition = LorePosition.BEFORE,
    val depth: Int = 4,
    val role: String = "system",
    val raw: String = "{}",
    val rules: LoreRules = LoreRules(),
    val automationId: String = "",
    val vectorized: Boolean = false,
    val displayIndex: Int = id,
    val selective: Boolean = secondary.isNotEmpty(),
    val useProbability: Boolean = probability < 100,
) {
    fun json(): JSONObject = JSONObject(raw).apply {
        put("uid", id)
        put("comment", title)
        put("content", content)
        put("key", JSONArray(keywords))
        put("keysecondary", JSONArray(secondary))
        put("selective", selective)
        put("selectiveLogic", logic.ordinal)
        put("constant", constant)
        put("vectorized", vectorized)
        put("disable", disabled)
        put("caseSensitive", caseSensitive ?: JSONObject.NULL)
        put("matchWholeWords", wholeWords ?: JSONObject.NULL)
        put("order", priority)
        put("displayIndex", displayIndex)
        put("scanDepth", scanDepth ?: JSONObject.NULL)
        put("useProbability", useProbability)
        put("probability", probability)
        put("position", position.ordinal)
        put("depth", depth)
        put("role", listOf("system", "user", "assistant").indexOf(role).coerceAtLeast(0))
        put("automationId", automationId)
        rules.write(this)
    }

    companion object {
        fun from(id: Int, value: JSONObject, embedded: Boolean = false, displayIndexFallback: Int = id): LoreEntry {
            val ext = value.optJSONObject("extensions")
            val secondary = value.optJSONArray(if (embedded) "secondary_keys" else "keysecondary").strings()
            val position = if (embedded) {
                ext?.optInt("position", -1)?.takeIf { it >= 0 } ?: if (value.string("position") == "after_char") 1 else 0
            } else value.optInt("position")
            fun flag(json: JSONObject?, key: String) = json?.takeUnless { it.isNull(key) }?.optBoolean(key)
            val useProbability = if (embedded) ext?.optBoolean("useProbability", true) != false
                else value.optBoolean("useProbability", true)
            val info = if (embedded) ext ?: JSONObject() else value
            return LoreEntry(
                id = id, title = if (embedded) value.string("name").ifBlank { value.string("comment") } else value.string("comment"),
                content = value.string("content"),
                keywords = value.optJSONArray(if (embedded) "keys" else "key").strings(),
                secondary = secondary,
                logic = LoreLogic.entries.getOrElse(info.optInt("selectiveLogic")) { LoreLogic.AND_ANY },
                constant = value.optBoolean("constant"),
                disabled = if (embedded) !value.optBoolean("enabled", true) else value.optBoolean("disable"),
                caseSensitive = if (embedded) flag(value, "case_sensitive") ?: flag(ext, "case_sensitive")
                    else flag(value, "caseSensitive"),
                wholeWords = if (embedded) flag(ext, "match_whole_words") else flag(value, "matchWholeWords"),
                priority = value.optInt(if (embedded) "insertion_order" else "order", 100),
                scanDepth = info.optInt(if (embedded) "scan_depth" else "scanDepth", -1).takeIf { it >= 0 }?.coerceAtMost(80),
                probability = info.optInt("probability", 100).coerceIn(0, 100),
                position = LorePosition.entries.getOrElse(position) { LorePosition.BEFORE },
                depth = info.optInt("depth", 4).coerceIn(0, 80),
                role = listOf("system", "user", "assistant").getOrElse(info.optInt("role")) { "system" },
                raw = value.toString(),
                rules = LoreRules.from(info, embedded),
                automationId = info.string(if (embedded) "automation_id" else "automationId"),
                vectorized = info.optBoolean("vectorized"),
                displayIndex = info.optInt(if (embedded) "display_index" else "displayIndex", displayIndexFallback),
                selective = value.optBoolean("selective", secondary.isNotEmpty()),
                useProbability = useProbability,
            )
        }
    }
}

internal data class WorldBook(
    val id: String = newId(),
    val name: String = "",
    val enabled: Boolean = false,
    val raw: String = "{\"entries\":{}}",
) {
    private fun root() = JSONObject(raw)
    val scanDepthOverride by lazy { root().optInt("scan_depth", -1).takeIf { it >= 0 }?.coerceAtMost(80) }
    val sharedMemory: Boolean by lazy { root().optBoolean("tavern_native_shared_memory") }

    val entries: List<LoreEntry> by lazy { root().let { data ->
        val values = data.optJSONObject("entries")
        if (values != null) values.keys().asSequence().mapNotNull { key ->
            values.optJSONObject(key)?.let { LoreEntry.from(key.toIntOrNull() ?: it.optInt("uid"), it) }
        }.toList()
        else data.optJSONArray("entries")?.objects().orEmpty().mapIndexed { index, entry ->
            LoreEntry.from(entry.optInt("id", index), entry, embedded = true, displayIndexFallback = index)
        }
    } }

    fun withEntries(items: List<LoreEntry>): WorldBook = copy(raw = root().apply {
        put("entries", JSONObject().apply { items.forEach { put(it.id.toString(), it.json()) } })
    }.toString())

    fun withScanDepth(depth: Int?): WorldBook = copy(raw = root().apply {
        if (depth == null) remove("scan_depth") else put("scan_depth", depth.coerceIn(0, 80))
    }.toString())

    fun withSharedMemory(enabled: Boolean): WorldBook = copy(raw = root()
        .put("tavern_native_shared_memory", enabled).toString())

    fun renamed(newName: String): WorldBook {
        val clean = newName.trim()
        require(clean.isNotBlank()) { "请输入世界书名称" }
        return copy(name = clean, raw = root().put("name", clean).toString())
    }

    fun memoryOverridesFor(cardId: String): WorldMemoryOverrides {
        val all = root().optJSONObject("tavern_native_memory_overrides") ?: return WorldMemoryOverrides()
        return WorldMemoryOverrides.fromJson(all.optJSONObject(cardId))
    }

    fun withMemoryOverrides(cardId: String, overrides: WorldMemoryOverrides): WorldBook {
        val data = root()
        val all = data.optJSONObject("tavern_native_memory_overrides") ?: JSONObject()
        if (overrides.isEmpty) all.remove(cardId) else all.put(cardId, overrides.json())
        if (all.length() == 0) data.remove("tavern_native_memory_overrides")
        else data.put("tavern_native_memory_overrides", all)
        return copy(raw = data.toString())
    }

    fun export(): ByteArray = root().apply { put("name", name) }.toString(2).toByteArray(Charsets.UTF_8)

    fun characterBook(): JSONObject = jsonOf("name" to name).apply {
        scanDepthOverride?.let { put("scan_depth", it) }
        put("entries", JSONArray(entries.map { entry ->
            val source = entry.json()
            val extensions = source.optJSONObject("extensions") ?: JSONObject()
            extensions.remove("character_filter")
            for ((key, embeddedKey) in embeddedLoreFields) {
                if (source.has(key)) extensions.put(embeddedKey, source.opt(key)) else extensions.remove(embeddedKey)
            }
            jsonOf("id" to entry.id, "name" to entry.title, "comment" to entry.title, "content" to entry.content,
                "keys" to JSONArray(entry.keywords), "secondary_keys" to JSONArray(entry.secondary),
                "constant" to entry.constant, "selective" to entry.selective, "enabled" to !entry.disabled,
                "insertion_order" to entry.priority, "use_regex" to true,
                "position" to if (entry.position == LorePosition.BEFORE) "before_char" else "after_char",
                "extensions" to extensions)
        }))
    }

    companion object {
        fun import(data: JSONObject, fallbackName: String): WorldBook {
            val name = data.string("name").ifBlank { fallbackName }.ifBlank { "导入的世界书" }
            if (data.optJSONObject("entries") == null) {
                convertForeignLorebook(data)?.let { return WorldBook(name = name).withEntries(it) }
            }
            require(data.optJSONObject("entries") != null || data.optJSONArray("entries") != null) {
                "世界书 JSON 缺少 entries 条目"
            }
            val book = WorldBook(name = name, raw = data.toString())
            // Leave SillyTavern world-book fields intact. Normalize embedded Character Book arrays for editing.
            return if (data.optJSONArray("entries") != null) book.withEntries(book.entries.mapIndexed { i, e -> e.copy(id = i) })
                else book
        }
    }
}

internal fun Card.embeddedWorldBook(): WorldBook? {
    val embedded = book ?: return null
    if (embedded.optJSONArray("entries")?.length() == 0 || embedded.optJSONObject("entries")?.length() == 0 ||
        !embedded.has("entries")) return null
    val parsed = WorldBook.import(embedded, "$name · 世界书")
    val id = java.util.UUID.nameUUIDFromBytes("embedded-character-book:${this.id}".encodeToByteArray()).toString()
    return parsed.takeIf { it.entries.isNotEmpty() }?.copy(id = id, enabled = false)
}

private val embeddedLoreFields = mapOf(
    "position" to "position", "probability" to "probability", "useProbability" to "useProbability",
    "depth" to "depth", "role" to "role", "selectiveLogic" to "selectiveLogic", "vectorized" to "vectorized",
    "outletName" to "outlet_name", "group" to "group", "groupOverride" to "group_override", "groupWeight" to "group_weight",
    "excludeRecursion" to "exclude_recursion", "preventRecursion" to "prevent_recursion", "delayUntilRecursion" to "delay_until_recursion",
    "scanDepth" to "scan_depth", "matchWholeWords" to "match_whole_words", "useGroupScoring" to "use_group_scoring",
    "caseSensitive" to "case_sensitive", "automationId" to "automation_id", "ignoreBudget" to "ignore_budget",
    "displayIndex" to "display_index",
    "sticky" to "sticky", "cooldown" to "cooldown", "delay" to "delay", "triggers" to "triggers", "characterFilter" to "characterFilter",
) + LoreSource.entries.associate { it.field to it.cardField }
