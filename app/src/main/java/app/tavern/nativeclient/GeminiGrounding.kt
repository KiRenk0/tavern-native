package app.tavern.nativeclient

import org.json.JSONObject
import java.net.URI

internal const val GEMINI_GOOGLE_SEARCH = "tavern_native_gemini_google_search"

internal fun ModelConnection.googleSearchProvider(): Provider? {
    val id = model.removePrefix("models/").lowercase()
    if (!id.startsWith("gemini-") || id.startsWith("gemini-1.") || id.contains("gemini-2.0-flash-lite") ||
        id.contains("-tts") || id.contains("embedding") || id.contains("-live") || isGeminiImageModel(id)) return null
    return when {
        provider.protocol == ApiProtocol.GEMINI_NATIVE -> provider
        provider.protocol == ApiProtocol.OPENAI_COMPATIBLE && provider.template == ProviderTemplate.GOOGLE &&
            provider.endpoint.trimEnd('/') == ProviderTemplate.GOOGLE.endpoint ->
            provider.copy(protocol = ApiProtocol.GEMINI_NATIVE)
        else -> null
    }
}

internal data class GoogleGroundedReply(
    val text: String,
    val suggestionHtml: String?,
    val searchQueries: List<String>,
    val sources: List<ReplySource>,
)

internal data class OpenGoogleReply(
    val id: String,
    val chatId: String,
    val anchorDate: String?,
    val speaker: String,
    val reply: GoogleGroundedReply,
    val interruption: String?,
)

internal class GeminiGrounding {
    private var usedSearch = false
    private var suggestionHtml: String? = null
    private val queries = linkedSetOf<String>()
    private val sources = linkedMapOf<String, ReplySource>()

    fun accept(metadata: JSONObject) {
        val entryPoint = metadata.optJSONObject("searchEntryPoint")
        val html = entryPoint?.opt("renderedContent") as? String
        if (!html.isNullOrBlank() && html.length <= 128 * 1024) suggestionHtml = html
        val searchQueries = metadata.optJSONArray("webSearchQueries")
        val chunks = metadata.optJSONArray("groundingChunks")
        usedSearch = usedSearch || entryPoint != null || (searchQueries?.length() ?: 0) > 0 ||
            (chunks?.length() ?: 0) > 0 || (metadata.optJSONArray("groundingSupports")?.length() ?: 0) > 0
        searchQueries.strings().take(20).filter { it.isNotBlank() && it.length <= 1024 }.forEach {
            if (queries.size < 5) queries.add(it)
        }
        chunks?.objects().orEmpty().take(100).forEach { chunk ->
            val web = chunk.optJSONObject("web") ?: return@forEach
            val source = ReplySource.read(jsonOf("url" to web.string("uri"), "title" to web.string("title")))
                ?: return@forEach
            if (sources.size < MAX_REPLY_SOURCES) sources.putIfAbsent(source.url, source)
        }
    }

    fun display(text: String): GoogleGroundedReply? =
        if (usedSearch && text.isNotBlank()) GoogleGroundedReply(text, suggestionHtml, queries.toList(), sources.values.toList())
        else null
}

internal fun isGoogleSearchSuggestion(value: String): Boolean {
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false
    return uri.scheme.equals("https", ignoreCase = true) && uri.rawUserInfo == null &&
        (uri.port == -1 || uri.port == 443) &&
        (host == "google.com" || host.endsWith(".google.com"))
}
