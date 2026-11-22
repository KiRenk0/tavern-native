package app.tavern.nativeclient

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptCharactersTest {
    @Test
    fun `partial edits preserve avatar key alternates world and extension fields`() {
        val initial = Card.from(JSONObject("""
            {"name":"Kao","alternate_greetings":["Good morning"],"extensions":{"world":"City",
             "depth_prompt":{"prompt":"Observe","depth":4,"role":"system","custom":3},"other":{"item":9}}}
        """.trimIndent())).withScriptAvatarKey("kao.png")
        val update = ScriptCardChanges.from(mapOf("firstMessage" to "Hello", "tags" to "One, Two,One",
            "favorite" to "false", "talkativeness" to "0.67", "depthPromptRole" to "user", "depthPromptDepth" to "8"))
        val card = update.apply(initial)
        assertEquals(initial.id, card.id)
        assertEquals("kao.png", card.scriptAvatarKey)
        assertEquals("City", card.worldName)
        assertEquals("Observe", card.depthPrompt)
        assertEquals(PromptRole.USER, card.depthPromptRole)
        assertEquals(8, card.depthPromptDepth)
        assertEquals(listOf("Good morning"), card.alternateGreetings)
        assertEquals(listOf("One", "Two"), card.tags)
        assertEquals(0.67, card.talkativeness, 0.0)
        assertFalse(card.libraryFavorite)
        assertEquals(9, JSONObject(card.original).getJSONObject("extensions").getJSONObject("other").getInt("item"))
        assertEquals(3, JSONObject(card.original).getJSONObject("extensions").getJSONObject("depth_prompt").getInt("custom"))
        assertEquals("kao.png", Card.from(card.portableJson()).scriptAvatarKey)
    }

    @Test
    fun `command lookup handles repeated tags and projects character data along the script pipeline`() = runBlocking {
        val kaoru = ScriptCardChanges.from(mapOf("name" to "Kaóru", "description" to "Reads maps", "tags" to "card tag",
            "characterVersion" to "2.1", "favorite" to "false")).create()
        val copy = kaoru.copy(id = "copy").withScriptAvatarKey("copy.png")
        val tags = LibraryPreferences(
            tags = listOf(LibraryTag(id = "maps", name = "Maps"), LibraryTag(id = "east", name = "East")),
            assignments = mapOf(libraryCardId(kaoru.id) to setOf("maps"), libraryCardId(copy.id) to setOf("maps", "east")),
            favorites = mapOf(libraryCardId(copy.id) to true),
        )
        val state = TavernState(cards = listOf(copy, kaoru), cardId = kaoru.id, prefs = Preferences(library = tags))
        val messages = mutableListOf<String>()
        val host = TavernScriptHost(
            snapshot = { state }, input = "", saveChat = {}, savePrefs = {}, ask = { null }, trigger = { _, _ -> },
            quiet = { _, _ -> "" }, draft = {}, saveVariables = { _, _ -> }, notice = { messages += it },
        )
        val script = StScript(host)
        assertEquals(kaoru.scriptAvatarKey, script.run("/char-find quiet=true Kaoru"))
        assertTrue(messages.isEmpty())
        assertEquals("copy.png", script.run("/findchar preferCurrent=false Kaoru"))
        assertTrue(messages.single().contains("多个"))
        assertEquals("Reads maps", script.run("""/char-find tag=Maps tag=East Kaoru | /char-get char="{{pipe}}" field=description"""))
        assertEquals("", script.run("/char-find tag=Missing tag=East Kaoru"))
        val output = JSONObject(script.run("/char-data char=copy.png return=object"))
        assertEquals("copy.png", output.getString("avatar"))
        assertEquals("2.1", output.getString("character_version"))
        assertTrue(output.getBoolean("fav"))
        assertEquals("card tag", output.getJSONArray("tags").getString(0))
        assertEquals("card tag", script.run("/char-get field=tags"))
        assertEquals("Kaóru", script.run("/char-get field=name"))
        val piped = JSONObject(script.run("""/char-find tag=East Kaoru | /char-get char="{{pipe}}""""))
        assertEquals("copy.png", piped.getString("avatar"))
        assertEquals("Reads maps", script.run("/char-get description"))
    }

    @Test
    fun `mutating aliases pass original flags and stable native keys across commands`() = runBlocking {
        val calls = mutableListOf<Pair<ScriptCharacterCommand, Map<String, String>>>()
        val host = TavernScriptHost(
            snapshot = { TavernState() }, input = "", saveChat = {}, savePrefs = {}, ask = { null }, trigger = { _, _ -> },
            quiet = { _, _ -> "" }, draft = {}, saveVariables = { _, _ -> }, notice = {},
            editCharacter = { command, named ->
                calls += command to named
                when (command) {
                    ScriptCharacterCommand.CREATE -> ScriptCardChanges.from(named).create().scriptAvatarKey
                    ScriptCharacterCommand.UPDATE -> named.getValue("char")
                    ScriptCharacterCommand.DUPLICATE -> "copy.png"
                    ScriptCharacterCommand.DELETE -> "true"
                    else -> error("Unexpected command")
                }
            },
        )
        val script = StScript(host)
        val result = script.run("""/char-create name="Role" select=false | /char-update char="{{pipe}}" depthPrompt="New note" | /dupe char="{{pipe}}" | /char-delete char="{{pipe}}" deleteChats=true silent=true""")
        assertEquals("true", result)
        assertEquals(listOf(ScriptCharacterCommand.CREATE, ScriptCharacterCommand.UPDATE,
            ScriptCharacterCommand.DUPLICATE, ScriptCharacterCommand.DELETE), calls.map { it.first })
        val original = calls[1].second.getValue("char")
        assertTrue(original.endsWith(".png"))
        assertEquals(original, calls[2].second.getValue("char"))
        assertNotEquals(original, calls[3].second.getValue("char"))
        assertEquals(mapOf("char" to "copy.png", "deleteChats" to "true", "silent" to "true"), calls[3].second)
    }
}
