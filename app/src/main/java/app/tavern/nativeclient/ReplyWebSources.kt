package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

internal const val ANTHROPIC_WEB_SEARCH = "tavern_native_anthropic_web_search"
internal const val ANTHROPIC_WEB_REPLAY = "tavern_native_anthropic_web_replay"
internal const val MAX_REPLY_SOURCES = 40
private const val MAX_WEB_REPLAY = 512 * 1024

internal enum class WebSearchBackend {
    DEEPSEEK, ANTHROPIC, OPENROUTER;

    fun hostedTools(): List<JSONObject> = when (this) {
        DEEPSEEK -> listOf(jsonOf("type" to "deepseek_web_search"), jsonOf("type" to "deepseek_read_url"))
        OPENROUTER -> listOf(jsonOf("type" to "openrouter:web_search"))
        ANTHROPIC -> emptyList()
    }
}

internal fun ModelConnection.webSearchBackend(): WebSearchBackend? = when (provider.protocol) {
    ApiProtocol.OPENAI_COMPATIBLE -> WebSearchBackend.DEEPSEEK
        .takeIf { provider.template == ProviderTemplate.DEEPSEEK && model == "deepseek-flash" }
    ApiProtocol.ANTHROPIC -> WebSearchBackend.ANTHROPIC
    ApiProtocol.OPENROUTER -> WebSearchBackend.OPENROUTER.takeUnless { model.endsWith(":online", true) }
    else -> null
}

internal data class ReplySource(val url: String, val title: String) {
    fun json() = jsonOf("url" to url, "title" to title)
    val domain get() = URI(url).host.orEmpty()

    companion object {
        fun read(row: JSONObject): ReplySource? {
            val url = row.string("url").trim()
            if (url.length !in 1..4096) return null
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            if (uri.scheme?.lowercase() !in setOf("https", "http") || uri.host.isNullOrBlank() || uri.rawUserInfo != null) return null
            val title = row.string("title").filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
                .trim().take(240).dropLastWhile(Char::isHighSurrogate)
            return ReplySource(url, title.ifBlank { uri.host })
        }
    }
}

internal fun combineReplySources(sources: Iterable<ReplySource>): List<ReplySource> {
    val unique = linkedMapOf<String, ReplySource>()
    for (source in sources) {
        val earlier = unique[source.url]
        if (earlier == null && unique.size < MAX_REPLY_SOURCES ||
            earlier != null && earlier.title == earlier.domain && source.title != source.domain) unique[source.url] = source
    }
    return unique.values.toList()
}

internal data class WebReplyReplay(
    val backend: WebSearchBackend, val provider: String, val model: String, val serialized: String,
) {
    fun json() = jsonOf("backend" to backend.name, "provider" to provider, "model" to model, "contents" to JSONArray(serialized))

    fun wireValue(connection: ModelConnection, enabled: WebSearchBackend?): JSONArray? =
        if (backend == enabled && provider == connection.provider.id && model == connection.model) JSONArray(serialized) else null

    companion object {
        fun create(backend: WebSearchBackend, connection: ModelConnection, values: JSONArray?): WebReplyReplay? {
            if (values == null || values.length() !in 1..100) return null
            val data = values.toString()
            return data.takeIf { it.length <= MAX_WEB_REPLAY }
                ?.let { WebReplyReplay(backend, connection.provider.id, connection.model, it) }
        }

        fun read(row: JSONObject?): WebReplyReplay? {
            val backend = WebSearchBackend.entries.firstOrNull { it.name == row?.string("backend") } ?: return null
            val provider = row?.string("provider")?.takeIf(String::isNotBlank) ?: return null
            val model = row.string("model").takeIf(String::isNotBlank) ?: return null
            val data = row.optJSONArray("contents")?.takeIf { it.length() in 1..100 }?.toString()
                ?.takeIf { it.length <= MAX_WEB_REPLAY } ?: return null
            return WebReplyReplay(backend, provider, model, data)
        }
    }
}

internal data class StoredWebReply(val sources: List<ReplySource>, val replay: WebReplyReplay? = null) {
    fun json() = jsonOf("sources" to JSONArray(sources.map(ReplySource::json)), "replay" to replay?.json())

    companion object {
        fun read(value: JSONObject?): StoredWebReply? {
            if (value == null) return null
            val sources = combineReplySources(value.optJSONArray("sources")?.objects().orEmpty().mapNotNull(ReplySource::read))
            val replay = WebReplyReplay.read(value.optJSONObject("replay"))
            return StoredWebReply(sources, replay).takeIf { sources.isNotEmpty() || replay != null }
        }
    }
}

internal class AnthropicWebTranscript {
    private val blocks = sortedMapOf<Int, Block>()
    private var incomplete = false
    private var size = 0

    fun start(index: Int, value: JSONObject) {
        val type = value.string("type")
        if (incomplete || index !in 0..99 ||
            type !in setOf("text", "thinking", "redacted_thinking", "server_tool_use", "web_search_tool_result")) {
            incomplete = true
            return
        }
        if (addSize(value.toString().length)) blocks[index] = Block(JSONObject(value.toString()))
    }

    fun delta(index: Int, delta: JSONObject) {
        val block = blocks[index] ?: return
        if (!addSize(delta.toString().length)) return
        when (delta.string("type")) {
            "text_delta" -> block.text.append(delta.string("text"))
            "thinking_delta" -> block.text.append(delta.string("thinking"))
            "signature_delta" -> block.signatures.append(delta.string("signature"))
            "input_json_delta" -> block.input.append(delta.string("partial_json"))
            "citations_delta" -> delta.optJSONObject("citation")?.let {
                val citations = block.raw.optJSONArray("citations") ?: JSONArray().also { array -> block.raw.put("citations", array) }
                citations.put(it)
            }
        }
    }

    fun replay(): JSONArray? {
        if (incomplete || blocks.values.none { it.raw.string("type") == "web_search_tool_result" }) return null
        return runCatching { JSONArray(blocks.values.map { it.finish() }) }.getOrNull()
    }

    private fun addSize(count: Int): Boolean {
        size += count
        if (size > MAX_WEB_REPLAY) incomplete = true
        return !incomplete
    }

    private class Block(val raw: JSONObject) {
        val text = StringBuilder()
        val signatures = StringBuilder()
        val input = StringBuilder()

        fun finish(): JSONObject {
            val key = if (raw.string("type") == "thinking") "thinking" else "text"
            if (text.isNotEmpty()) raw.put(key, raw.string(key) + text)
            if (signatures.isNotEmpty()) raw.put("signature", raw.string("signature") + signatures)
            if (input.isNotEmpty()) raw.put("input", boundedJsonObject(input.toString(), "搜索参数不完整"))
            return raw
        }
    }
}
