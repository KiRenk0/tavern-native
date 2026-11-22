package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject

internal const val SYSTEM_PROMPT_LIMIT = 32_768
private const val STOP_COUNT_LIMIT = 64
private const val STOP_LENGTH_LIMIT = 512

internal data class TextSystemPreset(val name: String, val content: String = "", val postHistory: String = "") {
    fun json() = jsonOf("name" to name, "content" to content, "post_history" to postHistory)

    fun validated(): TextSystemPreset {
        require(name.isNotBlank() && name.length <= 80) { "请输入 1–80 个字符的预设名称" }
        require(content.length <= SYSTEM_PROMPT_LIMIT && postHistory.length <= SYSTEM_PROMPT_LIMIT) {
            "每段提示词不能超过 $SYSTEM_PROMPT_LIMIT 个字符"
        }
        return copy(name = name.trim())
    }

    companion object {
        fun from(json: JSONObject, fallback: String = ""): TextSystemPreset {
            val content = requireNotNull(json.opt("content") as? String) { "系统提示词 JSON 缺少 content 字段" }
            return TextSystemPreset(json.string("name").ifBlank { fallback }, content,
                json.opt("post_history") as? String ?: "").validated()
        }
    }
}

internal data class TextSystemSettings(
    val selected: TextSystemPreset? = null,
    val custom: List<TextSystemPreset> = emptyList(),
    val enabled: Boolean = true,
    val preferCharacterPrompt: Boolean = true,
    val preferCharacterPostHistory: Boolean = true,
) {
    val configured get() = !enabled || selected != null || !preferCharacterPrompt || !preferCharacterPostHistory

    fun saving(value: TextSystemPreset): TextSystemSettings {
        val saved = value.validated()
        return copy(selected = saved, custom = custom.filterNot { it.name == saved.name } + saved)
    }

    fun json() = jsonOf(
        "selected" to selected?.json(), "custom" to JSONArray(custom.map(TextSystemPreset::json)), "enabled" to enabled,
        "preferCharacterPrompt" to preferCharacterPrompt, "preferCharacterPostHistory" to preferCharacterPostHistory,
    )

    companion object {
        fun from(json: JSONObject?): TextSystemSettings {
            if (json == null) return TextSystemSettings()
            fun usable(value: JSONObject) = runCatching { TextSystemPreset.from(value) }.getOrNull()
            return TextSystemSettings(
                selected = json.optJSONObject("selected")?.let(::usable),
                custom = json.optJSONArray("custom")?.objects()?.mapNotNull(::usable)
                    ?.distinctBy(TextSystemPreset::name).orEmpty(),
                enabled = json.optBoolean("enabled", true),
                preferCharacterPrompt = json.optBoolean("preferCharacterPrompt", true),
                preferCharacterPostHistory = json.optBoolean("preferCharacterPostHistory", true),
            )
        }
    }
}

internal data class CustomStoppingStrings(val values: List<String> = emptyList(), val macros: Boolean = true) {
    fun render(expand: (String) -> String) = (if (macros) values.map(expand) else values)
        .filter { it.isNotEmpty() && it.length <= STOP_LENGTH_LIMIT }.distinct()

    fun json() = jsonOf("values" to JSONArray(values), "macros" to macros)

    companion object {
        fun parse(text: String, macros: Boolean): CustomStoppingStrings {
            if (text.isBlank()) return CustomStoppingStrings(macros = macros)
            require(text.length <= 256 * 1024) { "停止序列 JSON 过长" }
            val array = boundedJsonObject("{\"values\":$text}", "请输入由字符串组成的 JSON 数组").optJSONArray("values")
                ?: error("请输入由字符串组成的 JSON 数组")
            require(array.length() <= STOP_COUNT_LIMIT) { "最多支持 $STOP_COUNT_LIMIT 条停止序列" }
            val values = (0 until array.length()).map { index ->
                requireNotNull(array.opt(index) as? String) { "第 ${index + 1} 项必须是字符串" }.also {
                    require(it.isNotEmpty() && it.length <= STOP_LENGTH_LIMIT) { "第 ${index + 1} 项须为 1–$STOP_LENGTH_LIMIT 个字符" }
                }
            }
            return CustomStoppingStrings(values.distinct(), macros)
        }

        fun from(json: JSONObject?): CustomStoppingStrings = if (json == null) CustomStoppingStrings()
            else CustomStoppingStrings(json.optJSONArray("values").strings()
                .filter { it.isNotEmpty() && it.length <= STOP_LENGTH_LIMIT }.distinct().take(STOP_COUNT_LIMIT),
                json.optBoolean("macros", true))
    }
}
