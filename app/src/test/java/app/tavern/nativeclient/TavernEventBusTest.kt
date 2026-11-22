package app.tavern.nativeclient

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernEventBusTest {

    @Test
    fun `supports all SillyTavern core events with case-insensitive name normalization`() {
        val bus = TavernEventBus()
        val received = mutableListOf<String>()

        TavernCoreEvent.entries.forEach { core ->
            bus.on(core) { event -> received += event.name }
        }

        // Emit using uppercase enum names and lowercase wire names interchangeably
        bus.emit("MESSAGE_SENT", jsonOf("id" to 1))
        bus.emit("message_received", jsonOf("id" to 2))
        bus.emit(TavernCoreEvent.MESSAGE_EDITED, jsonOf("id" to 2))
        bus.emit(TavernCoreEvent.MESSAGE_DELETED, jsonOf("id" to 2))
        bus.emit(TavernCoreEvent.MESSAGE_SWIPED, jsonOf("id" to 1))
        bus.emit(TavernCoreEvent.GENERATION_STARTED)
        bus.emit(TavernCoreEvent.GENERATION_ENDED)
        bus.emit(TavernCoreEvent.CHAT_CHANGED, jsonOf("chat_id" to "c1"))
        bus.emit(TavernCoreEvent.WORLDINFO_UPDATED, jsonOf("worldbook" to "Lore"))
        bus.emit(TavernCoreEvent.CHARACTER_EDITED, jsonOf("card_id" to "char1"))

        assertEquals(10, received.size)
        assertEquals(TavernCoreEvent.entries.map { it.wireName }, received)
        assertEquals(10, bus.recentEvents(20).size)
    }

    @Test
    fun `once listener fires exactly once and removes itself`() {
        val bus = TavernEventBus()
        var count = 0
        bus.once(TavernCoreEvent.MESSAGE_SENT) { count++ }

        assertEquals(1, bus.listenerCount(TavernCoreEvent.MESSAGE_SENT.wireName))
        bus.emit(TavernCoreEvent.MESSAGE_SENT)
        bus.emit(TavernCoreEvent.MESSAGE_SENT)

        assertEquals(1, count)
        assertEquals(0, bus.listenerCount(TavernCoreEvent.MESSAGE_SENT.wireName))
    }

    @Test
    fun `removeListener and removeAllListeners stop further delivery and isolate listener failures`() {
        val bus = TavernEventBus()
        val log = mutableListOf<String>()
        val badId = bus.on("custom_event") { error("boom") }
        val goodId = bus.on("custom_event") { log += it.payload.optString("msg") }

        val notified = bus.emit("custom_event", JSONObject().put("msg", "first"))
        assertEquals(2, notified)
        assertEquals(listOf("first"), log)

        assertTrue(bus.removeListener("custom_event", badId))
        assertFalse(bus.removeListener("custom_event", badId))
        bus.emit("custom_event", JSONObject().put("msg", "second"))
        assertEquals(listOf("first", "second"), log)

        assertTrue(bus.removeListener(goodId))
        bus.emit("custom_event", JSONObject().put("msg", "third"))
        assertEquals(listOf("first", "second"), log)
        assertEquals(0, bus.listenerCount())
    }
}
