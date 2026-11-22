package app.tavern.nativeclient

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpeningGreetingsTest {
    private val persona = PersonaProfile(name = "Mio")

    @Test
    fun `opening choices expand with independent variables and survive chat JSONL selection`() {
        val card = Card.from(jsonOf("name" to "Akari", "first_mes" to "{{setvar::place::harbor}}Hi {{user}}!",
            "alternate_greetings" to JSONArray(listOf("{{setvar::place::cafe}}Tea with {{char}}?", "Welcome back."))), "akari")
        val chat = Chat("chat", card.id, "Morning")
        val (opening, changes) = openingGreeting(chat, card, Preferences(), persona, null, card.greeting)
        val message = requireNotNull(opening)
        assertEquals("Hi Mio!", message.text)
        assertEquals(listOf("Hi Mio!", "Tea with Akari?", "Welcome back."), message.variants)
        assertEquals("harbor", changes.local["place"])
        val chosen = message.select(1)
        val restored = importJsonLinesChat(chat.copy(messages = listOf(chosen)).jsonl(card.name, persona.name).decodeToString(), "Copy")
        assertEquals(1, restored.messages.single().index)
        assertEquals("Tea with Akari?", restored.messages.single().text)
        assertTrue(restored.messages.single().openingGreeting)
        assertFalse(restored.messages.single().withText("Edited").openingGreeting)
        assertEquals("cafe", openingGreeting(chat, card, Preferences(), persona, null, card.alternateGreetings.first()).second.local["place"])
    }

    @Test
    fun `first available alternative becomes greeting when the primary is blank`() {
        val chat = Chat("chat", "akari", "Morning")
        val card = Card.from(jsonOf("name" to "Akari", "alternate_greetings" to JSONArray(listOf(" ", "Hi!"))), "akari")
        val message = requireNotNull(openingGreeting(chat, card, Preferences(), persona, null, "").first)
        assertEquals("Hi!", message.text)
        assertTrue(message.variants.isEmpty())
        assertNull(openingGreeting(chat, Card.blank(), Preferences(), persona, null, "").first)
    }

    @Test
    fun `talkativeness edits round trip the card fields used for natural group turns`() {
        val imported = Card("akari", """{"name":"Akari","talkativeness":0.2,"data":{"name":"Akari","talkativeness":0.3}}""")
        val updated = imported.withTalkativeness(1.0)
        val json = updated.portableJson()
        assertEquals(1.0, json.getDouble("talkativeness"), 0.0)
        assertEquals(1.0, json.getJSONObject("data").getDouble("talkativeness"), 0.0)
        val restored = Card.from(json, imported.id)
        val quiet = Card("mio", """{"name":"Mio"}""").withTalkativeness(0.0)
        val group = TavernGroup(memberIds = listOf(quiet.id, restored.id), order = ReplyOrder.NATURAL)
        assertEquals(listOf(restored.id), group.speakers(listOf(quiet, restored), emptyList()).map(Card::id))
    }
}
