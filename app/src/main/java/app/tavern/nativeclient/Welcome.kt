package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

internal const val RECENT_CHAT_LIMIT = 15
internal val defaultWelcomeAssistant = Card.from(jsonOf("name" to "助手"), "native-welcome-assistant")
internal val temporaryAssistant = Card.from(jsonOf("name" to "助手"), "native-temporary-assistant")
internal val Chat.isTemporary get() = cardId == temporaryAssistant.id

internal data class RecentChat(val summary: ChatSummary, val cardId: String?, val groupId: String?)

internal class TemporaryChatMedia(cache: File) {
    private val directory = Files.createTempDirectory(cache.toPath(), prefix).toFile()
    val images = ChatImageStore(File(directory, "images"))
    val documents = ChatDocumentStore(File(directory, "documents"))
    fun close() { directory.deleteRecursively() }

    companion object {
        private const val prefix = "tavern-temp-chat-"
        private var cleaned = false

        @Synchronized
        fun cleanPreviousProcess(cache: File) {
            if (cleaned) return
            cache.listFiles { file -> file.isDirectory && file.name.startsWith(prefix) }?.forEach { it.deleteRecursively() }
            cleaned = true
        }
    }
}

internal data class WelcomePreferences(
    val assistantId: String? = null,
    val autoOpen: Boolean = false,
    val showRecent: Boolean = true,
    val recentCount: Int = 3,
    val pinnedIds: List<String> = emptyList(),
) {
    fun json() = jsonOf("assistantId" to assistantId, "autoOpen" to autoOpen, "showRecent" to showRecent,
        "recentCount" to recentCount, "pinnedIds" to JSONArray(pinnedIds))

    companion object {
        fun from(value: JSONObject?) = WelcomePreferences(
            value?.string("assistantId")?.takeIf(String::isNotBlank),
            value?.optBoolean("autoOpen") == true,
            value?.optBoolean("showRecent", true) ?: true,
            (value?.optInt("recentCount", 3) ?: 3).coerceIn(1, RECENT_CHAT_LIMIT),
            value?.optJSONArray("pinnedIds").strings().filter(String::isNotBlank).distinct().take(RECENT_CHAT_LIMIT),
        )
    }
}
