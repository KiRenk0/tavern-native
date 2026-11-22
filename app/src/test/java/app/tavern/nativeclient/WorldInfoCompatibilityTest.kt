package app.tavern.nativeclient

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldInfoCompatibilityTest {
    private val speaker = Card("speaker", """{"name":"Akari"}""").withScriptAvatarKey("Akari-main.png")
    private val other = Card("other", """{"name":"Akari"}""").withScriptAvatarKey("Akari-alt.png")
    private val persona = PersonaProfile(name = "Mio")

    @Test
    fun `filters use the active card identity and library tags even for constants and semantic matches`() {
        val original = WorldBook.import(JSONObject("""{"entries":{
            "1":{"uid":1,"comment":"Both","content":"only the chosen Akari","constant":true,
                 "characterFilter":{"names":["Akari-main"],"tags":["guard"],"isExclude":false}},
            "2":{"uid":2,"comment":"Inverse","content":"excluded if either matches","constant":true,
                 "characterFilter":{"names":["Akari-main"],"tags":["guard"],"isExclude":true}},
            "3":{"uid":3,"comment":"Semantic","content":"only the tagged speaker","vectorized":true,
                 "characterFilter":{"tags":["guard"]}}
        }}"""), "Gate").copy(id = "gate", enabled = true)
        val books = listOf(original)
        val semantic = setOf("book:gate:3")
        fun names(card: Card, tags: Set<String>) = evaluateLore(card, books, emptyList(), LoreSettings(), persona,
            ConversationOptions(), "", semanticMatches = semantic, tagIds = tags).entries.map(LoreEntry::title).toSet()
        assertEquals(setOf("Both", "Semantic"), names(speaker, setOf("guard")))
        assertEquals(emptySet<String>(), names(speaker, emptySet()))
        assertEquals(setOf("Semantic"), names(other, setOf("guard")))
        assertEquals(setOf("Inverse"), names(other, emptySet()))
        val filter = original.entries.first { it.id == 1 }.rules.characterFilter
        assertEquals(filter, LoreEntry.from(1, original.entries.first { it.id == 1 }.json()).rules.characterFilter)
    }

    @Test
    fun `chat persona and selected character strategy determine activation and budget order`() {
        val chatBook = book("chat", 10)
        val personaBook = book("persona", 20)
        val primary = book("primary", 30)
        val extra = book("extra", 40)
        val global = book("global", 100, enabled = true)
        val activeSpeaker = speaker.withWorld(primary.name).withAdditionalWorldBooks(listOf(extra.id, global.id))
        val books = listOf(global, extra, primary, personaBook, chatBook)
        val options = ConversationOptions(worldBookId = chatBook.id)
        val user = persona.copy(worldId = personaBook.id)
        fun evaluate(strategy: LoreInsertionStrategy, limit: Int) = evaluateLore(activeSpeaker, books, emptyList(),
            LoreSettings(insertionStrategy = strategy), user, options, "", budget = LoreBudget(limit, TextTokenCounter("fixture", { 1 })))
        assertEquals(listOf("chat", "persona", "extra", "primary", "global"),
            evaluate(LoreInsertionStrategy.CHARACTER_FIRST, 5).automationIds.toList())
        assertEquals(listOf("chat", "persona", "global", "extra", "primary"),
            evaluate(LoreInsertionStrategy.GLOBAL_FIRST, 5).automationIds.toList())
        assertEquals(listOf("extra", "persona", "chat"),
            evaluate(LoreInsertionStrategy.CHARACTER_FIRST, 3).entries.map(LoreEntry::content))
        assertTrue(evaluate(LoreInsertionStrategy.CHARACTER_FIRST, 3).budget.blocked)
        assertEquals(listOf("global", "extra", "primary"), loreCandidates(activeSpeaker, listOf(global, extra, primary), persona,
            ConversationOptions(worldBookId = global.id), LoreSettings(insertionStrategy = LoreInsertionStrategy.EVENLY)).map { it.entry.content })
    }

    @Test
    fun `scan depth follows global book and entry settings and names are opt in`() {
        val entries = listOf(LoreEntry(1, content = "from name", keywords = listOf("Akari")),
            LoreEntry(2, content = "from old line", keywords = listOf("forest"), scanDepth = 2))
        val book = WorldBook("scan", "Scan", enabled = true).withEntries(entries)
        val history = listOf(Message("user", "Mio", "The forest is quiet"), Message("assistant", "Akari", "Yes"))
        fun collect(world: WorldBook, settings: LoreSettings) = evaluateLore(speaker, listOf(world), history, settings,
            persona, ConversationOptions(), "").entries.map(LoreEntry::content)
        val settings = LoreSettings(scanDepth = 1, includeNames = true, insertionStrategy = LoreInsertionStrategy.GLOBAL_FIRST)
        assertEquals(settings, LoreSettings.from(settings.json()))
        assertEquals(listOf("from name", "from old line"), collect(book, settings))
        assertEquals(listOf("from old line"), collect(book, settings.copy(includeNames = false)))
        assertEquals(listOf("from old line"), collect(book.withScanDepth(0), settings))
        assertEquals(listOf("from name", "from old line"), collect(book.withScanDepth(0).withScanDepth(null), settings))
    }

    @Test
    fun `prompt applies filters to the active group speaker and uses normal lore trigger for group regeneration`() {
        val entry = LoreEntry(1, content = "Allowed group lore", constant = true,
            rules = LoreRules(triggers = listOf("normal"), characterFilter = LoreCharacterFilter(listOf(speaker.id), listOf("watch"))))
        val book = WorldBook("gate", "Gate", enabled = true).withEntries(listOf(entry))
        val group = GroupPrompt(TavernGroup("group", "Group"), listOf(speaker, other), listOf(speaker.name, other.name))
        val history = listOf(Message("user", "Mio", "Hello"))
        val tags = LibraryPreferences(tags = listOf(LibraryTag("watch", "Watch")),
            assignments = mapOf(libraryCardId(speaker.id) to setOf("watch"), libraryCardId(other.id) to setOf("watch")))
        fun prompt(card: Card, scene: GroupPrompt?) = ModelService.prompt(card, history, true, listOf(book), scene, persona,
            ConversationOptions(), trigger = "regenerate", loreTagIds = tags.loreTags(card)).messages.toString()
        assertTrue(prompt(speaker, group).contains(entry.content))
        assertFalse(prompt(other, group).contains(entry.content))
        assertFalse(prompt(speaker, null).contains(entry.content))
    }

    @Test
    fun `account filenames and tag remapping survive and embedded primary book is the offline fallback`() {
        val entry = LoreEntry(1, title = "Token", content = "Shared rule", constant = true, scanDepth = 3,
            position = LorePosition.DEPTH, depth = 7, role = "user", probability = 65, automationId = "id",
            rules = LoreRules(sticky = 2, characterFilter = LoreCharacterFilter(listOf("Akari-main"), listOf("source-tag"))))
        val book = WorldBook("book", "Primary").withEntries(listOf(entry)).withScanDepth(5)
        val imported = book.remapLoreFilters(mapOf("Akari-main" to speaker.id), mapOf("source-tag" to "local-tag"))
        val exported = imported.remapLoreFilters(mapOf(speaker.id to "character-backup"))
        assertEquals(LoreCharacterFilter(listOf("character-backup"), listOf("local-tag")), exported.entries.single().rules.characterFilter)
        val portable = speaker.withWorld(imported.name).withCharacterBook(imported.characterBook())
        val decoded = LoreEntry.from(1, portable.book!!.getJSONArray("entries").getJSONObject(0), embedded = true)
        assertEquals(imported.entries.single().rules, decoded.rules)
        assertEquals(entry.copy(raw = "", rules = decoded.rules), decoded.copy(raw = ""))
        assertEquals(listOf("card:speaker:1"), loreCandidates(portable, emptyList(), persona, ConversationOptions(),
            tagIds = setOf("local-tag")).map(LoreCandidate::key))
        assertEquals(listOf("book:book:1"), loreCandidates(portable, listOf(imported), persona, ConversationOptions(),
            tagIds = setOf("local-tag")).map(LoreCandidate::key))
        val normalized = WorldBook.import(portable.book!!, "Primary")
        val cleared = normalized.withEntries(normalized.entries.map { stored ->
            stored.copy(rules = stored.rules.copy(characterFilter = LoreCharacterFilter()))
        })
        val row = cleared.characterBook().getJSONArray("entries").getJSONObject(0)
        assertTrue(LoreEntry.from(0, row, embedded = true).rules.characterFilter.isEmpty)
    }

    private fun book(name: String, priority: Int, enabled: Boolean = false) = WorldBook(name, name, enabled).withEntries(
        listOf(LoreEntry(1, content = name, constant = true, priority = priority, automationId = name)))
}
