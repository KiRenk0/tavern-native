package app.tavern.nativeclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAutocompleteTest {

    @Test
    fun `detectAutocomplete triggers for slash commands at start or after pipe`() {
        val q1 = detectAutocomplete("/se", 3)
        assertNotNull(q1)
        assertEquals("/", q1?.trigger)
        assertEquals("/se", q1?.prefix)

        val q2 = detectAutocomplete("/echo hi | /tri", 15)
        assertNotNull(q2)
        assertEquals("/", q2?.trigger)
        assertEquals("/tri", q2?.prefix)
        assertEquals(11, q2?.replaceStart)

        // Already completed command followed by space + args should NOT suggest command
        assertNull(detectAutocomplete("/send hello", 11))
    }

    @Test
    fun `detectAutocomplete triggers for open macro braces and ignores closed macros`() {
        val q1 = detectAutocomplete("你好 {{ch", 7)
        assertNotNull(q1)
        assertEquals("{{", q1?.trigger)
        assertEquals("{{ch", q1?.prefix)

        assertNull(detectAutocomplete("你好 {{char}} 世界", 14))
    }

    @Test
    fun `suggestAutocomplete includes quick replies and local or global variables`() {
        val qCmd = detectAutocomplete("/run", 4)!!
        val cmdMatches = suggestAutocomplete(
            qCmd,
            quickReplies = listOf(QuickReply(id = 1, label = "攻击检定", message = "/roll 1d20")),
        )
        assertTrue(cmdMatches.any { it.label == "/run 攻击检定" && it.kind == AutocompleteKind.QUICK_REPLY })

        val qMacro = detectAutocomplete("当前分数 {{sc", 9)!!
        val macroMatches = suggestAutocomplete(
            qMacro,
            localVars = mapOf("score" to "10"),
            globalVars = mapOf("scene" to "tavern"),
        )
        assertTrue(macroMatches.any { it.insertText == "{{var::score}}" && it.kind == AutocompleteKind.VARIABLE })
    }

    @Test
    fun `applyAutocomplete replaces prefix and positions cursor inside template`() {
        val source = "状态：{{ge"
        val query = detectAutocomplete(source, source.length)!!
        val item = macroCatalog.first { it.insertText == "{{getvar::}}" }
        val (updated, cursor) = applyAutocomplete(source, query, item)
        assertEquals("状态：{{getvar::}}", updated)
        assertEquals(updated.length - 2, cursor)
    }

    @Test
    fun `chatAppearance clamps bounds and serializes cleanly`() {
        val appearance = ChatAppearance(
            layoutMode = ChatLayoutMode.DOCUMENT,
            fontSizeSp = 40f,
            lineHeightMultiplier = 0.5f,
            highlightQuotes = false,
            renderHtmlCards = true,
            showTokenBar = false,
        ).clamped()
        assertEquals(24f, appearance.fontSizeSp, 0.001f)
        assertEquals(1.15f, appearance.lineHeightMultiplier, 0.001f)

        val roundTrip = ChatAppearance.from(appearance.json())
        assertEquals(appearance, roundTrip)
    }
}
