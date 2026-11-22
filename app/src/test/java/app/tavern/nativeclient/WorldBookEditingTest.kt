package app.tavern.nativeclient

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldBookEditingTest {
    @Test
    fun `display ordering round trips and filtered reorder keeps hidden entries in place`() {
        val original = WorldBook.import(JSONObject("""{"entries":{
            "7":{"uid":7,"order":20,"displayIndex":1,"comment":"Lighthouse","content":"A"},
            "2":{"uid":2,"order":50,"displayIndex":3,"comment":"Harbor","content":"B"},
            "3":{"uid":3,"order":75,"displayIndex":2,"comment":"Station","content":"C"}
        }}"""), "Route")
        val changed = original.moveEntryInDisplayOrder(2, -1, setOf(7, 2))
        val restored = WorldBook.import(JSONObject(changed.export().decodeToString()), "Route")
        assertEquals(listOf(2, 3, 7), LoreEntrySort.CUSTOM.sorted(restored.entries, false).map(LoreEntry::id))
        assertEquals(original.entries.map(LoreEntry::priority), restored.entries.map(LoreEntry::priority))
        assertEquals(listOf(3, 2, 7), LoreEntrySort.PRIORITY.sorted(restored.entries, true).map(LoreEntry::id))
        assertEquals(listOf(2, 7, 3), LoreEntrySort.TITLE.sorted(restored.entries, false).map(LoreEntry::id))
        val embedded = WorldBook.import(changed.characterBook(), "Route")
        assertEquals(listOf("Harbor", "Station", "Lighthouse"),
            LoreEntrySort.CUSTOM.sorted(embedded.entries, false).map(LoreEntry::title))
    }

    @Test
    fun `copy and move preserve entry metadata while assigning destination ids and positions`() {
        val rule = LoreRules(sticky = 2, characterFilter = LoreCharacterFilter(tags = listOf("member")))
        val entry = LoreEntry(5, "Port", "A port", listOf("port"), priority = 50,
            raw = """{"customRule":{"version":2}}""", rules = rule)
        val source = WorldBook("a", "Road").withEntries(listOf(entry))
        val destination = WorldBook("b", "Map").withEntries(listOf(LoreEntry(5, "Existing", "B", displayIndex = 9)))
        val copy = source.transferEntry(destination, 5, false)
        assertEquals(source, copy.source)
        assertEquals(6, copy.entryId)
        assertEquals(listOf(9, 10), copy.destination.entries.map(LoreEntry::displayIndex))
        val restored = copy.destination.entries.first { it.id == 6 }
        assertEquals(rule, restored.rules)
        assertEquals(2, restored.json().getJSONObject("customRule").getInt("version"))
        assertTrue(source.transferEntry(destination, 5, true).source.entries.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { source.transferEntry(source, 5, true) }
        val (sameBook, duplicateId) = source.duplicateEntry(5)
        assertEquals(listOf(5, duplicateId), LoreEntrySort.CUSTOM.sorted(sameBook.entries, false).map(LoreEntry::id))
    }

    @Test
    fun `title fill and new book copy preserve the source and exclude stored character memory overrides`() {
        val original = WorldBook("src", "Route", enabled = true, raw = """{"entries":{},"tavern_native_memory_overrides":{"card":{"fixture":true}}}""")
            .withEntries(listOf(LoreEntry(0, content = "A", keywords = listOf("first", "second")),
                LoreEntry(1, "Keep", "B", listOf("different")), LoreEntry(2, content = "C")))
        val updated = original.fillBlankEntryTitles()
        assertEquals(listOf("first", "Keep", ""), updated.entries.map(LoreEntry::title))
        assertEquals(listOf("", "Keep", ""), original.entries.map(LoreEntry::title))
        val copied = updated.copyForEditing("Route 2")
        assertNotEquals(original.id, copied.id)
        assertFalse(copied.enabled)
        assertFalse(JSONObject(copied.raw).has("tavern_native_memory_overrides"))
        assertEquals(updated.entries, copied.entries)
    }
}
