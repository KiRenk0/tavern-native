package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject

internal enum class RegexSite(val code: Int, val label: String) {
    INPUT(1, "用户消息"), REPLY(2, "模型回复"), COMMAND(3, "命令文本"),
    WORLD(5, "世界书"), REASONING(6, "模型推理"),
}

internal enum class RegexStage {
    STORAGE, DISPLAY, REQUEST,
}

internal enum class RegexMacros(val label: String) {
    NONE("保留原文"), RAW("替换宏"), ESCAPED("替换并转义宏"),
}

internal enum class RegexScope(val label: String) {
    GLOBAL("全局"), CARD("角色卡"), PRESET("提示词预设"),
}

internal data class RegexTarget(val scope: RegexScope = RegexScope.GLOBAL, val id: String = "")

internal data class RegexRule(
    val id: String = newId(),
    val name: String = "",
    val pattern: String = "",
    val replacement: String = "",
    val trims: List<String> = emptyList(),
    val sites: List<RegexSite> = listOf(RegexSite.REPLY),
    val disabled: Boolean = false,
    val displayOnly: Boolean = false,
    val requestOnly: Boolean = false,
    val onEdit: Boolean = true,
    val macros: RegexMacros = RegexMacros.NONE,
    val minDepth: Int? = null,
    val maxDepth: Int? = null,
    val raw: String = "{}",
) {
    fun applies(site: RegexSite, stage: RegexStage, depth: Int?, editing: Boolean): Boolean {
        if (disabled || site !in sites || (editing && !onEdit)) return false
        if (depth != null && ((minDepth != null && depth < minDepth) || (maxDepth != null && depth > maxDepth))) return false
        return when (stage) {
            RegexStage.STORAGE -> !displayOnly && !requestOnly
            RegexStage.DISPLAY -> displayOnly
            RegexStage.REQUEST -> requestOnly
        }
    }

    fun json() = JSONObject(raw).apply {
        put("id", id)
        put("scriptName", name)
        put("findRegex", pattern)
        put("replaceString", replacement)
        put("trimStrings", JSONArray(trims))
        put("placement", JSONArray(sites.map(RegexSite::code)))
        put("disabled", disabled)
        put("markdownOnly", displayOnly)
        put("promptOnly", requestOnly)
        put("runOnEdit", onEdit)
        put("substituteRegex", macros.ordinal)
        put("minDepth", minDepth ?: JSONObject.NULL)
        put("maxDepth", maxDepth ?: JSONObject.NULL)
    }

    companion object {
        fun from(json: JSONObject): RegexRule? {
            if (!json.has("findRegex")) return null
            val placements = json.optJSONArray("placement")
            val codes = placements?.let { array -> (0 until array.length()).map { array.optInt(it, -1) } }.orEmpty()
            val oldDisplay = 0 in codes
            val sites = if (oldDisplay && codes.size == 1) listOf(RegexSite.INPUT, RegexSite.REPLY, RegexSite.COMMAND)
            else codes.mapNotNull { code -> RegexSite.entries.firstOrNull { it.code == (if (code == 4) 3 else code) } }.distinct()
            return RegexRule(json.string("id").ifBlank(::newId), json.string("scriptName").ifBlank { "未命名规则" },
                json.string("findRegex"), json.string("replaceString"), json.optJSONArray("trimStrings").strings(),
                sites, json.optBoolean("disabled"), json.optBoolean("markdownOnly") || oldDisplay,
                json.optBoolean("promptOnly"), json.optBoolean("runOnEdit"),
                RegexMacros.entries.getOrElse(json.optInt("substituteRegex")) { RegexMacros.NONE },
                json.optInt("minDepth", -2).takeIf { it >= -1 }, json.optInt("maxDepth", -1).takeIf { it >= 0 },
                json.toString())
        }

        fun fromArray(array: JSONArray?): List<RegexRule> =
            array?.objects()?.mapNotNull(::from)?.distinctBy(RegexRule::id).orEmpty()

        fun import(text: String): List<RegexRule> {
            val source = text.trimStart('\uFEFF').trim()
            val rules = if (source.startsWith("[")) fromArray(JSONArray(source)) else {
                val json = JSONObject(source)
                val data = json.optJSONObject("extension_settings") ?: json
                fromArray(data.optJSONArray("regex") ?: data.optJSONArray("regex_scripts")
                    ?: data.optJSONObject("extensions")?.optJSONArray("regex_scripts"))
                    .ifEmpty { listOfNotNull(from(data)) }
            }
            require(rules.isNotEmpty()) { "JSON 中没有可导入的 SillyTavern 正则规则" }
            return rules
        }
    }
}

internal data class RegexConfig(
    val enabled: Boolean = true,
    val global: List<RegexRule> = emptyList(),
    val allowedCards: Set<String> = emptySet(),
    val allowedPresets: Set<String> = emptySet(),
) {
    fun json() = jsonOf("enabled" to enabled, "global" to JSONArray(global.map(RegexRule::json)),
        "cards" to JSONArray(allowedCards.toList()), "presets" to JSONArray(allowedPresets.toList()))

    fun runtime(card: Card?, persona: String, preset: PromptPreset?, group: List<String> = emptyList()): RegexRuntime {
        if (!enabled) return RegexRuntime.EMPTY
        val presetRules = preset?.takeIf { it.id in allowedPresets }?.regexRules.orEmpty()
        val cardRules = card?.takeIf { it.id in allowedCards }?.regexRules.orEmpty()
        return RegexRuntime(global + presetRules + cardRules, RegexNames(card, persona, group))
    }

    companion object {
        fun from(value: JSONObject?) = RegexConfig(value?.optBoolean("enabled", true) ?: true,
            RegexRule.fromArray(value?.optJSONArray("global")), value?.optJSONArray("cards").strings().toSet(),
            value?.optJSONArray("presets").strings().toSet())
    }
}

internal data class RegexNames(val card: Card?, val persona: String, val group: List<String> = emptyList()) {
    private val fields = mapOf(
        "char" to card?.name.orEmpty(),
        "user" to persona,
        "group" to group.joinToString(", ").ifBlank { card?.name.orEmpty() },
        "charifnotgroup" to group.joinToString(", ").ifBlank { card?.name.orEmpty() },
        "scenario" to card?.scenario.orEmpty(),
        "personality" to card?.personality.orEmpty(),
        "description" to card?.description.orEmpty(),
        "chardescription" to card?.description.orEmpty(),
    )

    fun expand(text: String, escape: (String) -> String = { it }) = token.replace(text) {
        fields[it.groupValues[1].lowercase()]?.let(escape) ?: it.value
    }

    companion object {
        private val token = Regex("\\{\\{\\s*([A-Za-z]+)\\s*\\}\\}")
    }
}
