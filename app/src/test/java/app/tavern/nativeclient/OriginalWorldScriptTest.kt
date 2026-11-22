package app.tavern.nativeclient

import app.tavern.nativeclient.script.ScriptExtensions
import app.tavern.nativeclient.script.ScriptWorldBinding
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginalWorldScriptTest {
    @Test
    fun `world toggles the requested books as one update and empty world clears global activation`() = runBlocking {
        val first = WorldBook("a", "Town", enabled = true)
        val second = WorldBook("b", "Harbor")
        var state = TavernState(books = listOf(first, second))
        var mutations = 0
        suspend fun run(name: String, text: String = "", args: Map<String, String> = emptyMap()) = ScriptExtensions.execute(
            name, args, text, state, saveChat = { error("Unexpected chat change") }, updateWorldActivation = { changed ->
                mutations++
                val byId = changed.associateBy(WorldBook::id)
                state = state.copy(books = state.books.map { byId[it.id] ?: it })
            })
        assertEquals("", run("world", "Town, Harbor, Harbor", mapOf("state" to "toggle")))
        assertEquals("[\"Harbor\"]", run("getglobalbooks"))
        assertEquals(1, mutations)
        assertEquals("", run("world"))
        assertEquals("[]", run("getglobalwi"))
        assertEquals(2, mutations)
    }

    @Test
    fun `original slash commands create and revisit chat persona and character book attachments`() = runBlocking {
        val card = Card("akari", """{"name":"Akari"}""")
        val persona = PersonaProfile(id = "mio", name = "Mio")
        val chat = Chat("chat", card.id, "Morning")
        var state = TavernState(cards = listOf(card), cardId = card.id, chat = chat,
            prefs = Preferences(personas = listOf(persona), defaultPersonaId = persona.id))
        suspend fun run(name: String, args: Map<String, String> = emptyMap(), text: String = "") = ScriptExtensions.execute(
            name, args, text, state, saveChat = { error("Expected an atomic binding") }, createBoundBook = { book, binding ->
                state = when (binding) {
                    is ScriptWorldBinding.Conversation -> state.copy(chat = binding.chat)
                    is ScriptWorldBinding.Character -> state.copy(cards = listOf(binding.card))
                    is ScriptWorldBinding.Persona -> state.copy(prefs = binding.prefs)
                }.let { it.copy(books = it.books + book) }
            })
        assertEquals("", run("getchatbook", mapOf("create" to "false")))
        assertEquals("Talk", run("getchatbook", mapOf("name" to "Talk")))
        assertEquals("Talk", run("getchatwi"))
        assertEquals("Mio Book", run("getpersonabook", mapOf("name" to "Mio Book", "create" to "true")))
        assertEquals("Mio Book", run("getpersonalore"))
        assertEquals("Main", run("getcharbook", mapOf("name" to "Main", "create" to "true")))
        assertEquals("[\"Extra\"]", run("getcharbook", mapOf("name" to "Extra", "type" to "additional", "create" to "true")))
        assertEquals("[\"Main\",\"Extra\"]", run("getcharwi", mapOf("type" to "all"), "Akari"))
        assertEquals(4, state.books.size)
        assertTrue(state.books.none(WorldBook::enabled))
    }

    @Test
    fun `original entry scripts search and update typed fields without discarding disabled filters`() = runBlocking {
        var book = WorldBook("a", "Town").withEntries(listOf(LoreEntry(8, "Old port lighthouse", "Sea fog", listOf("shadowfang"))))
        suspend fun run(name: String, text: String, vararg args: Pair<String, String>) = ScriptExtensions.execute(
            name, mapOf("file" to "Town") + args.toMap(), text, TavernState(books = listOf(book)), saveChat = {}, saveBook = { book = it })
        assertEquals("8", run("findentry", "shdowfang"))
        assertEquals("Sea fog", run("getentryfield", "8"))
        assertEquals("", run("setentryfield", "/red, blue/i, dusk", "uid" to "8", "field" to "keysecondary"))
        assertEquals("", run("setentryfield", "false", "uid" to "8", "field" to "selective"))
        assertEquals("false", run("getentryfield", "8", "field" to "selective"))
        val saved = book.entries.single()
        assertFalse(saved.selective)
        val keys = JSONArray(run("getentryfield", "8", "field" to "keysecondary"))
        assertEquals(listOf("/red, blue/i", "dusk"), listOf(keys.getString(0), keys.getString(1)))
        assertEquals("9", run("createentry", "River path", "key" to "river"))
        assertEquals("9", run("findwi", "river"))
    }

    @Test
    fun `a world keyword with original message separators does not cross speakers`() {
        val card = Card("akari", """{"name":"Akari"}""")
        val persona = PersonaProfile(name = "Mio")
        val book = WorldBook("b", "Town", enabled = true).withEntries(listOf(
            LoreEntry(1, content = "Matched speaker", keywords = listOf("/\\x01{{user}}:[^\\x01]*?hello/i")),
            LoreEntry(2, content = "Legacy regex", keywords = listOf("/^Mio:/")),
        ))
        fun activated(lastUser: String, lastAssistant: String) = evaluateLore(card, listOf(book),
            listOf(Message("user", "Mio", lastUser), Message("assistant", "Akari", lastAssistant)),
            LoreSettings(includeNames = true), persona, ConversationOptions(), "").entries.map(LoreEntry::id).toSet()
        assertEquals(setOf(2), activated("Good evening", "hello"))
        assertEquals(setOf(1, 2), activated("hello", "Good evening"))
    }
}
