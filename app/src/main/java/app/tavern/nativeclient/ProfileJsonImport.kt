package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class PersonaJsonImport(
    val profiles: List<PersonaProfile>,
    val defaultId: String? = null,
    val connections: Map<String, String> = emptyMap(),
) {
    fun merge(prefs: Preferences): Pair<Preferences, Int> {
        val seen = prefs.personas.mapTo(mutableSetOf(), PersonaProfile::id)
        val added = profiles.filter { seen.add(it.id) }
        val default = defaultId?.takeIf { prefs.personas == listOf(PersonaProfile.legacy()) && it in seen }
        return prefs.copy(personas = prefs.personas + added, defaultPersonaId = default ?: prefs.defaultPersonaId,
            personaConnections = connections + prefs.personaConnections) to added.size
    }
}

private class ProfileJson(bytes: ByteArray) {
    private val source = bytes.toString(Charsets.UTF_8).trim().removePrefix("\uFEFF").trim()
    val array = source.startsWith('[')
    val root = boundedJsonObject(if (array) "{\"items\":$source}" else source, "无法读取 JSON 文件")

    fun records(key: String): List<JSONObject>? {
        val values = if (array) root.optJSONArray("items") else root.optJSONArray(key)
        return values?.let { list ->
            require(list.length() > 0) { "JSON 文件没有可导入的条目" }
            (0 until list.length()).map { index ->
                requireNotNull(list.optJSONObject(index)) { "第 ${index + 1} 条记录应为 JSON 对象" }
            }
        }
    }
}

internal fun readCharacterJson(bytes: ByteArray): List<Card> {
    val file = ProfileJson(bytes)
    val rows = file.records("characters") ?: listOf(file.root)
    return rows.mapIndexed { index, root ->
        val data = root.optJSONObject("data") ?: root
        val name = data.opt("name") as? String
        require(!name.isNullOrBlank()) { "第 ${index + 1} 张角色卡缺少名称" }
        Card.from(root)
    }
}

internal fun readPersonaJson(bytes: ByteArray, books: List<WorldBook>, cards: List<Card> = emptyList(),
                             groups: List<TavernGroup> = emptyList()): PersonaJsonImport {
    val file = ProfileJson(bytes)
    val power = file.root.optJSONObject("power_user") ?: file.root
    val byAvatar = power.optJSONObject("personas")
    fun profile(row: JSONObject, name: String, id: String? = null): PersonaProfile {
        val position = row.string("position")
        val role = row.string("role")
        val world = row.string("worldId").ifBlank { row.string("lorebook") }
        val selectedWorld = books.firstOrNull { it.id == world || it.name == world }?.id.orEmpty()
        return PersonaProfile(
            id = id.orEmpty(), name = name.trim(), description = row.opt("description") as? String ?: "",
            title = (row.opt("title") as? String)?.trim().orEmpty(),
            position = PersonaPosition.entries.firstOrNull { it.code == position.toIntOrNull() || it.name.equals(position, true) }
                ?: PersonaPosition.PROMPT,
            depth = row.optInt("depth", 2).coerceAtLeast(0),
            role = PromptRole.entries.firstOrNull { it.ordinal == role.toIntOrNull() || it.wire.equals(role, true) }
                ?: PromptRole.SYSTEM,
            worldId = selectedWorld,
        )
    }
    if (byAvatar != null) {
        val descriptions = power.optJSONObject("persona_descriptions") ?: JSONObject()
        val connections = linkedMapOf<String, String>()
        val cardIds = cards.mapTo(hashSetOf(), Card::id)
        val groupIds = groups.mapTo(hashSetOf(), TavernGroup::id)
        val rows = byAvatar.keys().asSequence().map { filename ->
            val name = byAvatar.opt(filename) as? String
            require(!name.isNullOrBlank()) { "人设备份包含无效名称" }
            val id = previewPersonaId(filename)
            val entry = descriptions.optJSONObject(filename) ?: JSONObject()
            entry.optJSONArray("connections")?.objects().orEmpty().forEach { link ->
                val original = link.string("id").takeIf(String::isNotBlank) ?: return@forEach
                val owner = when (link.string("type")) {
                    "character" -> listOf(original, previewCharacterId(original)).firstOrNull { it in cardIds }
                    "group" -> listOf(original, previewGroupId(original)).firstOrNull { it in groupIds }
                    else -> null
                }
                if (owner != null) connections.putIfAbsent(owner, id)
            }
            profile(entry, name, id)
        }.toList()
        require(rows.isNotEmpty()) { "备份文件没有可导入的人设" }
        val default = power.string("default_persona").takeIf(byAvatar::has)?.let(::previewPersonaId)
        return PersonaJsonImport(rows, default, connections)
    }
    val rows = file.records("personas") ?: listOf(file.root)
    val profiles = rows.mapIndexed { index, row ->
        val name = row.opt("name") as? String
        require(!name.isNullOrBlank()) { "第 ${index + 1} 个人设缺少名称" }
        val parsed = profile(row, name)
        val source = row.string("id")
        val uuid = runCatching { UUID.fromString(source).toString() }.getOrNull()?.takeIf { it.equals(source, ignoreCase = true) }
        val worldReference = row.string("worldId").ifBlank { row.string("lorebook") }
        val key = if (source.isBlank()) parsed.copy(worldId = worldReference).json().toString() else source
        parsed.copy(id = uuid ?: UUID.nameUUIDFromBytes("persona-json:$key".toByteArray(Charsets.UTF_8)).toString())
    }
    return PersonaJsonImport(profiles)
}
