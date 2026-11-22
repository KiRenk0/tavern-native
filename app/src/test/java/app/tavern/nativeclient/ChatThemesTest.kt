package app.tavern.nativeclient

import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatThemesTest {
    @Test
    fun `bundled SillyTavern presets supply usable palettes and stable identities`() {
        val source = JSONArray(File("src/main/assets/st-theme-presets.json").readText())
        val presets = readBuiltInChatThemes(source)
        assertEquals(listOf("Azure", "Cappuccino", "Celestial Macaron", "Dark Lite", "Dark V 1.0"),
            presets.map(ChatTheme::name))
        assertEquals(presets, readBuiltInChatThemes(source))
        assertTrue(presets.all { it.appearance.palette.colors.containsKey(ThemeColor.PANEL) })
        assertEquals(presets.map(ChatTheme::id), presets.fold(ChatThemes(), ChatThemes::add).items.map(ChatTheme::id))
    }

    @Test
    fun `SillyTavern theme retains alpha and compatible settings through exported JSON`() {
        val source = JSONObject("""{
            "name": "Nocturne",
            "chat_display": 1,
            "main_text_color": "rgba(220, 220, 210, 1)",
            "bot_mes_blur_tint_color": "rgba(32, 24, 18, 0.5)",
            "shadow_width": 3,
            "noShadows": true,
            "waifuMode": true,
            "custom_css": "body { --example: 1; }"
        }""")
        val theme = ChatTheme.read(source, "Theme")
        val exported = theme.portable()
        val restored = ChatTheme.read(exported, "Theme", theme.id)
        assertEquals(0x80201812.toInt(), theme.appearance.palette.colors[ThemeColor.ASSISTANT])
        assertEquals(ChatLayoutMode.BUBBLE, theme.appearance.layoutMode)
        assertTrue(theme.appearance.palette.noShadows && theme.visualNovel)
        assertEquals(3, theme.appearance.palette.shadowWidth)
        assertEquals(theme, restored)
        assertEquals(source.string("custom_css"), exported.string("custom_css"))
    }

    @Test
    fun `stored themes keep the selected identity without replacing names or duplicate imports`() {
        val source = JSONObject("""{"name":"Quiet","chat_display":0,"quote_text_color":"#F888"}""")
        val first = ChatTheme.read(source, "First")
        val second = ChatTheme.read(source, "Second")
        val library = ChatThemes().add(first).add(second).add(first).copy(activeId = second.id)
        val prefs = Preferences(chatThemes = library, chatAppearance = first.appearance)
        val restored = decodePreferences(encodePreferences(prefs, { it }), { it })
        assertEquals(listOf("Quiet", "Quiet (2)"), restored.chatThemes.items.map(ChatTheme::name))
        assertEquals(second.id, restored.chatThemes.activeId)
        assertEquals(0x88FF8888.toInt(), restored.chatAppearance.palette.colors[ThemeColor.QUOTE])
        assertNull(parseThemeColor("rgba(400,0,0,1)"))
    }
}
