package app.tavern.nativeclient

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterLibraryOptionsTest {
    private val original = Card.from(jsonOf("name" to "Étoile", "description" to "Weather researcher",
        "scenario" to "The observatory closes at midnight.", "creator" to "Aki",
        "tags" to JSONArray(listOf("Weather")), "character_version" to "2.1"))
    private val imported = Card.from(jsonOf("name" to "Rin", "tags" to JSONArray(listOf("weather", "Travel"))))
    private val library = LibraryPreferences().materialized(listOf(original))

    @Test
    fun `advanced search matches accent variants and nearby spellings across character fields`() {
        val entries = library.entities(listOf(original), emptyList())
        fun matches(query: String, advanced: Boolean) =
            browseLibrary(entries, library, emptyMap(), query, emptySet(), emptySet(), null, false, advanced)
        assertEquals(listOf(original), matches("etoile observatroy", true).mapNotNull(LibraryItem::card))
        assertEquals(listOf(original), matches("midnight Aki", true).mapNotNull(LibraryItem::card))
        assertTrue(matches("midnight", false).isEmpty())
        assertTrue(matches("dinosaur", true).isEmpty())
        assertEquals("版本 2.1", CharacterSubtitle.VERSION.text(original))
        assertEquals("Aki", CharacterSubtitle.CREATOR.text(original))
    }

    @Test
    fun `import tag rules preserve embedded data and a zero assignment in native backups`() {
        val key = libraryCardId(imported.id)
        val skip = library.applyCardTagImport(listOf(original), listOf(imported), CardTagImport.NONE)
        assertEquals(emptySet<String>(), skip.materialized(listOf(original, imported)).assignments[key])
        val existing = library.applyCardTagImport(listOf(original), listOf(imported), CardTagImport.EXISTING)
        assertEquals(setOf(library.tags.single().id), existing.materialized(listOf(original, imported)).assignments[key])
        assertEquals(listOf("weather", "Travel"), imported.tags)
        val portable = skip.tagBackup(skip.entities(listOf(original, imported), emptyList()))
        val restored = LibraryPreferences().mergeTags(portable, ownerFor = { it }, preserveEmptyAssignments = true)
            .preferences.materialized(listOf(original, imported))
        assertEquals(emptySet<String>(), restored.assignments[key])
    }

    @Test
    fun `character settings use the original portable keys`() {
        val options = CharacterLibraryOptions(true, CharacterSubtitle.CREATOR, true, true, CardTagImport.EXISTING,
            showAvatarFilename = true, keepAvatarSize = true)
        assertEquals(4, options.json().optInt("tag_import_setting"))
        assertEquals("creator", options.json().string("aux_field"))
        assertEquals(options, CharacterLibraryOptions.from(options.json()))
        val restored = decodePreferences(encodePreferences(Preferences(characterLibrary = options), { it }), { it })
        assertEquals(options, restored.characterLibrary)
    }

    @Test
    fun `avatar decode preserves full size only when requested`() {
        assertEquals(4, avatarDecodeSample(1920, 1080, keepSize = false))
        assertEquals(1, avatarDecodeSample(1920, 1080, keepSize = true))
    }
}
