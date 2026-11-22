package app.tavern.nativeclient

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

internal enum class TavernCoreEvent(val wireName: String) {
    MESSAGE_SENT("message_sent"),
    MESSAGE_RECEIVED("message_received"),
    MESSAGE_EDITED("message_edited"),
    MESSAGE_DELETED("message_deleted"),
    MESSAGE_SWIPED("message_swiped"),
    GENERATION_STARTED("generation_started"),
    GENERATION_ENDED("generation_ended"),
    CHAT_CHANGED("chat_changed"),
    WORLDINFO_UPDATED("worldinfo_updated"),
    CHARACTER_EDITED("character_edited"),
    ;

    companion object {
        private val lookup: Map<String, TavernCoreEvent> = buildMap {
            TavernCoreEvent.entries.forEach { event ->
                put(event.name.lowercase(Locale.ROOT), event)
                put(event.wireName.lowercase(Locale.ROOT), event)
            }
        }

        fun fromName(name: String?): TavernCoreEvent? {
            val key = name?.trim()?.lowercase(Locale.ROOT).orEmpty()
            return if (key.isEmpty()) null else lookup[key]
        }

        fun eventTypesJson(): JSONObject = JSONObject().apply {
            entries.forEach { put(it.name, it.wireName) }
        }
    }
}

internal data class TavernEvent(
    val name: String,
    val payload: JSONObject = JSONObject(),
    val timestamp: Long = System.currentTimeMillis(),
    val source: String = "native",
) {
    val coreEvent: TavernCoreEvent? get() = TavernCoreEvent.fromName(name)

    fun toJson(): JSONObject = jsonOf(
        "name" to name,
        "payload" to runCatching { JSONObject(payload.toString()) }.getOrElse { JSONObject() },
        "timestamp" to timestamp,
        "source" to source,
    )
}

internal class TavernEventBus(private val maxHistory: Int = 100) {
    private data class ListenerEntry(
        val id: String,
        val once: Boolean,
        val handler: (TavernEvent) -> Unit,
    )

    private val lock = Any()
    private val listeners = linkedMapOf<String, CopyOnWriteArrayList<ListenerEntry>>()
    private val history = ArrayDeque<TavernEvent>()
    private val _events = MutableSharedFlow<TavernEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<TavernEvent> = _events.asSharedFlow()

    fun normalizeEventName(raw: String): String {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "事件名称不能为空" }
        return TavernCoreEvent.fromName(trimmed)?.wireName ?: trimmed.lowercase(Locale.ROOT)
    }

    fun on(
        event: TavernCoreEvent,
        listenerId: String = newId(),
        handler: (TavernEvent) -> Unit,
    ): String = on(event.wireName, listenerId, handler)

    fun on(
        eventName: String,
        listenerId: String = newId(),
        handler: (TavernEvent) -> Unit,
    ): String = register(eventName, listenerId, once = false, handler = handler)

    fun once(
        event: TavernCoreEvent,
        listenerId: String = newId(),
        handler: (TavernEvent) -> Unit,
    ): String = once(event.wireName, listenerId, handler)

    fun once(
        eventName: String,
        listenerId: String = newId(),
        handler: (TavernEvent) -> Unit,
    ): String = register(eventName, listenerId, once = true, handler = handler)

    private fun register(
        eventName: String,
        listenerId: String,
        once: Boolean,
        handler: (TavernEvent) -> Unit,
    ): String {
        val normalized = normalizeEventName(eventName)
        val id = listenerId.trim().ifEmpty { newId() }
        synchronized(lock) {
            val bucket = listeners.getOrPut(normalized) { CopyOnWriteArrayList() }
            bucket.removeIf { it.id == id }
            bucket.add(ListenerEntry(id = id, once = once, handler = handler))
        }
        return id
    }

    fun removeListener(event: TavernCoreEvent, listenerId: String): Boolean =
        removeListener(event.wireName, listenerId)

    fun removeListener(eventName: String, listenerId: String): Boolean {
        val normalized = runCatching { normalizeEventName(eventName) }.getOrNull() ?: return false
        synchronized(lock) {
            val bucket = listeners[normalized] ?: return false
            val removed = bucket.removeIf { it.id == listenerId }
            if (bucket.isEmpty()) listeners.remove(normalized)
            return removed
        }
    }

    fun removeListener(listenerId: String): Boolean {
        var removed = false
        synchronized(lock) {
            val emptyKeys = mutableListOf<String>()
            listeners.forEach { (key, bucket) ->
                if (bucket.removeIf { it.id == listenerId }) removed = true
                if (bucket.isEmpty()) emptyKeys += key
            }
            emptyKeys.forEach(listeners::remove)
        }
        return removed
    }

    fun removeAllListeners(eventName: String? = null) {
        synchronized(lock) {
            if (eventName == null) {
                listeners.clear()
            } else {
                val normalized = runCatching { normalizeEventName(eventName) }.getOrNull() ?: return
                listeners.remove(normalized)
            }
        }
    }

    fun emit(
        event: TavernCoreEvent,
        payload: JSONObject = JSONObject(),
        source: String = "native",
    ): Int = emit(event.wireName, payload, source)

    fun emit(
        eventName: String,
        payload: JSONObject = JSONObject(),
        source: String = "native",
    ): Int {
        val normalized = normalizeEventName(eventName)
        val clonedPayload = runCatching { JSONObject(payload.toString()) }.getOrElse { JSONObject() }
        val event = TavernEvent(name = normalized, payload = clonedPayload, source = source)
        val snapshot: List<ListenerEntry>
        synchronized(lock) {
            if (maxHistory > 0) {
                while (history.size >= maxHistory) history.removeFirst()
                history.addLast(event)
            }
            val bucket = listeners[normalized]
            snapshot = bucket?.toList().orEmpty()
            if (bucket != null) {
                bucket.removeIf { it.once }
                if (bucket.isEmpty()) listeners.remove(normalized)
            }
        }
        _events.tryEmit(event)
        var invoked = 0
        for (entry in snapshot) {
            runCatching { entry.handler(event) }
            invoked++
        }
        return invoked
    }

    fun listenerCount(eventName: String? = null): Int = synchronized(lock) {
        if (eventName == null) {
            listeners.values.sumOf { it.size }
        } else {
            val normalized = runCatching { normalizeEventName(eventName) }.getOrNull() ?: return 0
            listeners[normalized]?.size ?: 0
        }
    }

    fun recentEvents(limit: Int = 50): List<TavernEvent> = synchronized(lock) {
        if (limit <= 0) emptyList() else history.takeLast(limit)
    }

    fun clearHistory() = synchronized(lock) {
        history.clear()
    }
}
