package app.tavern.nativeclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTranscriptImportTest {
    private fun load(text: String, name: String = "Session.json") =
        importChatTranscripts(text.trimIndent().encodeToByteArray(), name, "Mio", "Rina")

    @Test
    fun `original and Chub JSONL retain group speakers and alternatives across owners`() {
        val rows = """
            {"user_name":"unused","character_name":"unused","chat_metadata":{"world_info":"Campus"}}
            {"name":"Mio","is_user":true,"mes":"Incoming","send_date":"2026-05-01T00:00:00Z"}
            {"name":"Rina","mes":{"message":"Chosen"},"swipes":[{"message":"First"},{"message":"Chosen"}],"swipe_id":1}
            {"name":"Yui","mes":"Another speaker","extra":{"gen_id":5}}
        """.trimIndent().replace("\n", "\r\n")
        val original = load("\uFEFF$rows", "Meeting.JSONL").single()
        val rina = Card("rina", jsonOf("name" to "Rina").toString())
        val yui = Card("yui", jsonOf("name" to "Yui").toString())
        val cards = listOf(rina, yui)
        val group = TavernGroup(name = "Meeting", memberIds = cards.map(Card::id))
        val imported = original.withOwner(null, group, cards, knownCards = cards)
        assertEquals(listOf("user", "assistant", "assistant"), imported.messages.map(Message::role))
        assertEquals(listOf(null, "rina", "yui"), imported.messages.map(Message::characterId))
        assertEquals(listOf("First", "Chosen"), imported.messages[1].variants)
        assertEquals(1, imported.messages[1].index)
        assertEquals("2026-05-01T00:00:00Z", imported.messages[0].date)
        assertEquals("Campus", imported.options.worldBookName)

        val exported = imported.jsonl(group.name, "Mio")
        val solo = importChatTranscripts(exported, "Group export.jsonl", "Mio", "Rina").single()
            .withOwner(rina, null, emptyList(), knownCards = cards)
        assertEquals(group.id, imported.groupId)
        assertEquals(rina.id, solo.cardId)
        assertEquals(imported.messages.map(Message::text), solo.messages.map(Message::text))
        assertEquals(imported.messages.map(Message::characterId), solo.messages.map(Message::characterId))
    }

    @Test
    fun `legacy JSON imports use original order names and timestamps`() {
        val textgen = load("""{"data_visible":[["Hello","First"],["","Second"],["Bye",null]]}""").single()
        assertEquals(listOf("Mio", "Rina", "Rina", "Mio"), textgen.messages.map(Message::name))
        assertEquals(listOf("Hello", "First", "Second", "Bye"), textgen.messages.map(Message::text))

        val agnai = load("""{"messages":[
            {"userId":"user-123","msg":"Question","createdAt":"2026-02-02T09:00:00Z"},
            {"msg":"Answer","name":"Yui"}
        ]}""").single()
        assertEquals(listOf("user", "assistant"), agnai.messages.map(Message::role))
        assertEquals("2026-02-02T09:00:00Z", agnai.messages.first().date)
        assertEquals("Yui", agnai.messages.last().name)

        val kobold = load("""{"savedsettings":{"chatname":"Player","chatopponent":"Guide||$||Crew"},
            "prompt":"{{[OUTPUT]}} Welcome ","actions":["{{[INPUT]}} Hello ","{{[OUTPUT]}} Go"]}""").single()
        assertEquals(listOf("Guide", "Player", "Guide"), kobold.messages.map(Message::name))
        assertEquals(listOf("Welcome", "Hello", "Go"), kobold.messages.map(Message::text))

        val risu = load("""{"type":"risuChat","data":{"message":[
            {"role":"user","name":"Hana","time":0,"data":"First"},
            {"role":"char","name":"Aki","time":1000,"data":"Second"}
        ]}}""").single()
        assertEquals(listOf("Hana", "Aki"), risu.messages.map(Message::name))
        assertEquals(listOf("1970-01-01T00:00:00Z", "1970-01-01T00:00:01Z"), risu.messages.map(Message::date))
    }

    @Test
    fun `every CAI Tools history gets a separate chat and title`() {
        val chats = load("""
            {"histories":{"histories":[
                {"title":"Day 1","msgs":[{"text":"Hello","src":{"is_human":true}}]},
                {"msgs":[{"text":"New scene","src":{"is_human":false,"name":"Yui"}}]},
                {"msgs":[]}
            ]}}
        """, "Character.AI.json")
        assertEquals(listOf("Day 1", "Character.AI · 2"), chats.map(Chat::title))
        assertEquals(listOf("Hello", "New scene"), chats.map { it.messages.single().text })
        assertEquals("Yui", chats[1].messages.single().name)
        assertEquals(2, chats.map(Chat::id).distinct().size)
        assertEquals("Day 1 · 3", chats[0].availableTitle(setOf("Day 1", "Day 1 · 2")))
    }

    @Test
    fun `wrapped native JSON and malformed file diagnostics use the same message model`() {
        val chat = load("""{"title":"Wrapped","chat_metadata":{"world_info":"Campus"},"messages":[{"mes":"Line"}]}""").single()
        assertNotNull(chat.header)
        assertEquals("Campus", chat.options.worldBookName)
        assertEquals("Line", chat.messages.single().text)
        assertThrows(IllegalStateException::class.java) { load("""{"name":"Not a chat","description":"Character card"}""") }
        val failure = assertThrows(IllegalArgumentException::class.java) {
            importChatTranscripts("\n{\"user_name\":\"Mio\"}\n{\"mes\":".encodeToByteArray(), "bad.jsonl", "Mio", "Rina")
        }
        assertTrue(failure.message.orEmpty().contains("第 3 行"))
    }
}
