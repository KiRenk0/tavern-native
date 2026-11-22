package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToInt

internal const val MAX_THEME_JSON = 256 * 1024
private const val NATIVE_THEME = "tavern_native_theme"
private const val MAX_THEME_NAME = 80
private val rgbThemeColor = Regex("""rgba?\(([^()]*)\)""")

internal enum class ThemeColor(val field: String, val label: String) {
    TEXT("main_text_color", "正文"),
    ITALICS("italics_text_color", "斜体"),
    UNDERLINE("underline_text_color", "下划线"),
    QUOTE("quote_text_color", "对话引号"),
    SHADOW("shadow_color", "文字阴影"),
    CHAT("chat_tint_color", "聊天背景"),
    PANEL("blur_tint_color", "界面背景"),
    BORDER("border_color", "边框"),
    USER("user_mes_blur_tint_color", "用户消息"),
    ASSISTANT("bot_mes_blur_tint_color", "角色消息"),
}

internal data class ThemePalette(
    val colors: Map<ThemeColor, Int> = emptyMap(),
    val shadowWidth: Int = 0,
    val noShadows: Boolean = false,
) {
    fun write(target: JSONObject) {
        for (role in ThemeColor.entries) {
            val color = colors[role]
            if (color == null) target.remove(role.field) else target.put(role.field, cssRgba(color))
        }
        target.put("shadow_width", shadowWidth).put("noShadows", noShadows)
    }

    fun json(): JSONObject = JSONObject().also(::write)

    companion object {
        fun from(row: JSONObject?, fallback: ThemePalette = ThemePalette()): ThemePalette {
            if (row == null) return fallback
            val overrides = ThemeColor.entries.mapNotNull { role ->
                parseThemeColor(row.string(role.field))?.let { role to it }
            }.toMap()
            return ThemePalette(
                fallback.colors + overrides,
                row.optInt("shadow_width", fallback.shadowWidth).coerceIn(0, 5),
                row.optBoolean("noShadows", fallback.noShadows),
            )
        }
    }
}

internal fun parseThemeColor(input: String): Int? {
    val text = input.trim().lowercase()
    if (text == "transparent") return 0
    if (text.startsWith('#')) {
        val hex = text.drop(1)
        if (hex.length !in setOf(3, 4, 6, 8)) return null
        val expanded = if (hex.length <= 4) hex.map { "$it$it" }.joinToString("") else hex
        val rgba = expanded.toLongOrNull(16) ?: return null
        if (rgba < 0 || hex.any { it !in "0123456789abcdef" }) return null
        return if (expanded.length == 8) ((rgba ushr 8) or ((rgba and 255) shl 24)).toInt()
            else (0xFF000000L or rgba).toInt()
    }
    val match = rgbThemeColor.matchEntire(text) ?: return null
    val parts = match.groupValues[1].split(',').map(String::trim)
    if (parts.size !in 3..4) return null
    val rgb = parts.take(3).map { component ->
        val percent = component.endsWith('%')
        val limit = if (percent) 100.0 else 255.0
        val value = component.removeSuffix("%").toDoubleOrNull()?.takeIf { it in 0.0..limit } ?: return null
        (value * 255 / limit).roundToInt()
    }
    val alpha = parts.getOrNull(3)?.let {
        val limit = if (it.endsWith('%')) 100.0 else 1.0
        val value = it.removeSuffix("%").toDoubleOrNull()?.takeIf { n -> n in 0.0..limit } ?: return null
        (value * 255 / limit).roundToInt()
    } ?: 255
    return (alpha shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
}

private fun cssRgba(argb: Int): String =
    "rgba(${argb ushr 16 and 255}, ${argb ushr 8 and 255}, ${argb and 255}, ${(argb ushr 24) / 255.0})"

internal data class ChatTheme(
    val id: String,
    val name: String,
    val appearance: ChatAppearance,
    val mode: String = "system",
    val dynamicColor: Boolean = false,
    val visualNovel: Boolean = false,
    val originalExtras: String? = null,
) {
    fun portable(): JSONObject = (originalExtras?.let(::JSONObject) ?: JSONObject()).apply {
        appearance.writeOriginal(this)
        put("name", name)
        put("waifuMode", visualNovel)
        put(NATIVE_THEME, jsonOf("appearance" to appearance.json(), "mode" to mode, "dynamicColor" to dynamicColor))
    }

    fun json() = portable().put(NATIVE_BACKUP_ID, id)

    companion object {
        private val passthrough = listOf(
            "blur_strength", "fast_ui_mode", "enableZenSliders", "enableLabMode", "custom_css", "bogus_folders",
        )
        private val originalFields = with(ChatAppearance()) { JSONObject().also(::writeOriginal).keys().asSequence().toSet() } +
            ThemeColor.entries.map(ThemeColor::field) + "waifuMode"

        fun read(row: JSONObject, fallbackName: String, id: String = UUID.randomUUID().toString()): ChatTheme {
            val native = row.optJSONObject(NATIVE_THEME)
            val appearance = native?.optJSONObject("appearance")
            require(appearance != null || originalFields.any(row::has)) { "文件中没有可识别的 SillyTavern 主题设置" }
            val name = themeName(row.string("name").ifBlank { fallbackName })
            val extras = JSONObject()
            for (key in passthrough) {
                val value = row.opt(key)
                if (value is String || value is Boolean || value is Number) extras.put(key, value)
            }
            return ChatTheme(
                id, name, appearance?.let(ChatAppearance::from) ?: ChatAppearance.fromOriginal(row),
                native?.string("mode")?.takeIf { it in listOf("system", "light", "dark") } ?: "system",
                native?.optBoolean("dynamicColor") == true, row.optBoolean("waifuMode"),
                extras.takeIf { it.length() != 0 }?.toString(),
            )
        }
    }
}

internal fun readBuiltInChatThemes(rows: JSONArray): List<ChatTheme> = rows.objects().map { row ->
    val name = row.string("name")
    val id = UUID.nameUUIDFromBytes("sillytavern-theme:$name".encodeToByteArray()).toString()
    ChatTheme.read(row, "聊天主题", id)
}.distinctBy(ChatTheme::id)

internal fun themeName(value: String): String =
    value.trim().take(MAX_THEME_NAME).dropLastWhile(Char::isHighSurrogate).ifBlank { "聊天主题" }

internal data class ChatThemes(val items: List<ChatTheme> = emptyList(), val activeId: String? = null) {
    fun json(): JSONObject = jsonOf("items" to JSONArray(items.map(ChatTheme::json)), "activeId" to activeId)

    fun add(theme: ChatTheme): ChatThemes {
        if (items.any { it.id == theme.id }) return this
        val name = generateSequence(1) { it + 1 }
            .map { if (it == 1) theme.name else "${theme.name.take(MAX_THEME_NAME - 12)} ($it)" }
            .first { candidate -> items.none { it.name.equals(candidate, true) } }
        return copy(items = items + theme.copy(name = name))
    }

    companion object {
        fun from(row: JSONObject?): ChatThemes {
            val items = row?.optJSONArray("items")?.objects().orEmpty().mapNotNull {
                val id = portableAccountId(it.string(NATIVE_BACKUP_ID)) ?: return@mapNotNull null
                runCatching { ChatTheme.read(it, "聊天主题", id) }.getOrNull()
            }.distinctBy(ChatTheme::id)
            val active = row?.string("activeId")?.takeIf { id -> items.any { it.id == id } }
            return ChatThemes(items, active)
        }
    }
}
