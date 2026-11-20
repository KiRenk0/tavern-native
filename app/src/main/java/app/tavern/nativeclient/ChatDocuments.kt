package app.tavern.nativeclient

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI

internal const val MAX_CHAT_DOCUMENTS = 5
internal const val MAX_STORED_CHAT_DOCUMENT = MAX_DOCUMENT_CHARACTERS * 4
private const val MAX_PROMPT_DOCUMENT_CHARACTERS = 64_000
private val documentId = Regex("[0-9a-f]{64}")
private val originalTextName = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]{0,180}\\.txt")

internal data class ChatDocument(val id: String, val name: String, val bytes: Int) {
    val filename get() = "$id.txt"
    val vectorScope get() = "attachment:$id"

    fun json() = jsonOf("id" to id, "name" to name, "bytes" to bytes)

    companion object {
        fun from(value: JSONObject): ChatDocument? {
            val id = value.string("id")
            val bytes = value.optInt("bytes")
            return if (documentId.matches(id) && bytes in 1..MAX_STORED_CHAT_DOCUMENT) {
                ChatDocument(id, value.string("name").take(180).ifBlank { "文档" }, bytes)
            } else null
        }
    }
}

internal data class OriginalChatFile(val index: Int, val name: String, val file: String?, val inline: String?)

internal fun originalChatFilename(url: String): String? {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
    if (uri.isAbsolute || uri.rawAuthority != null || uri.rawQuery != null || uri.rawFragment != null) return null
    val path = uri.path?.removePrefix("/") ?: return null
    if (!path.startsWith("user/files/")) return null
    return path.removePrefix("user/files/").takeIf(originalTextName::matches)
}

internal fun Message.originalChatFiles(): List<OriginalChatFile> {
    val files = raw?.let(::JSONObject)?.optJSONObject("extra")?.optJSONArray("files") ?: return emptyList()
    return (0 until files.length()).mapNotNull { index ->
        val file = files.optJSONObject(index) ?: return@mapNotNull null
        val path = originalChatFilename(file.string("url"))
        val inline = file.string("text").takeIf(String::isNotBlank)
        if (inline == null && path == null) null else OriginalChatFile(index,
            file.string("name").ifBlank { path ?: "文档" }, path, inline)
    }.take((MAX_CHAT_DOCUMENTS - documents.size).coerceAtLeast(0))
}

internal fun Message.withOriginalDocuments(loaded: Map<Int, ChatDocument>): Message {
    if (loaded.isEmpty()) return this
    val row = raw?.let(::JSONObject)
    val extra = row?.optJSONObject("extra")
    val files = extra?.optJSONArray("files")
    if (files != null) {
        val remaining = JSONArray()
        for (index in 0 until files.length()) if (index !in loaded) remaining.put(files.opt(index))
        if (remaining.length() == 0) extra.remove("files") else extra.put("files", remaining)
    }
    return copy(raw = row?.toString(), documents = (documents + loaded.values).distinctBy(ChatDocument::id))
}

internal class ChatDocumentStore(private val directory: File) {
    private fun file(document: ChatDocument): File {
        require(documentId.matches(document.id))
        return File(directory, document.filename)
    }

    fun bytes(document: ChatDocument): ByteArray? = file(document).takeIf { it.isFile && it.length() == document.bytes.toLong() }
        ?.inputStream()?.use { it.readLimited(MAX_STORED_CHAT_DOCUMENT) }

    fun text(document: ChatDocument): String? = bytes(document)?.decodeToString(throwOnInvalidSequence = true)

    fun import(value: ImportedDocument): ChatDocument {
        val bytes = validatedDocumentText(value.text).encodeToByteArray()
        val attachment = ChatDocument(ChatImageStore.digest(bytes), value.title.take(180).ifBlank { "文档" }, bytes.size)
        restore(attachment, bytes)
        return attachment
    }

    @Synchronized
    fun restore(document: ChatDocument, bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size == document.bytes && bytes.size <= MAX_STORED_CHAT_DOCUMENT &&
            ChatImageStore.digest(bytes) == document.id) { "聊天文档校验失败" }
        val text = bytes.decodeToString(throwOnInvalidSequence = true)
        require(validatedDocumentText(text) == text) { "聊天文档不是规范文本" }
        val target = file(document)
        if (this.bytes(document)?.contentEquals(bytes) == true) return
        val atomic = AtomicFile(target)
        val out = atomic.startWrite()
        try {
            out.write(bytes)
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        }
    }
}

internal data class MessageFileSettings(
    val enabled: Boolean = false,
    val thresholdKb: Int = 2,
    val chunkLength: Int = 800,
    val overlap: Int = 10,
    val limit: Int = 4,
) {
    fun json() = jsonOf("enabled" to enabled, "thresholdKb" to thresholdKb, "chunkLength" to chunkLength,
        "overlap" to overlap, "limit" to limit)

    companion object {
        fun from(value: JSONObject?): MessageFileSettings {
            if (value == null) return MessageFileSettings()
            val default = MessageFileSettings()
            return default.copy(enabled = value.optBoolean("enabled"),
                thresholdKb = value.optInt("thresholdKb", default.thresholdKb).coerceIn(0, 8),
                chunkLength = value.optInt("chunkLength", default.chunkLength).coerceIn(80, 1600),
                overlap = value.optInt("overlap", default.overlap).coerceIn(0, 50),
                limit = value.optInt("limit", default.limit).coerceIn(1, 20))
        }
    }
}

internal fun attachmentCorpus(document: ChatDocument, text: String, config: VectorPreferences): VectorCorpus {
    val prefs = config.messageFiles
    val parts = vectorChunks(text, prefs.chunkLength, config.boundary, prefs.overlap).mapIndexed { index, part ->
        VectorFragment(index.toString(), EmbeddingInput(document.name, part), partIndex = index)
    }
    return VectorCorpus(document.vectorScope, parts)
}

internal fun promptDocuments(history: List<Message>, snippets: Map<String, List<String>>,
                             read: (ChatDocument) -> String?): Map<Int, String> {
    val result = mutableMapOf<Int, String>()
    var remaining = MAX_PROMPT_DOCUMENT_CHARACTERS
    for (index in history.indices.reversed()) {
        if (remaining == 0) break
        val sections = history[index].documents.mapNotNull { document ->
            if (remaining == 0) return@mapNotNull null
            val content = snippets[document.id]?.joinToString("\n…\n") ?: read(document) ?: return@mapNotNull null
            if (content.isBlank()) return@mapNotNull null
            val end = minOf(content.length, remaining)
            val safeEnd = if (end < content.length && Character.isHighSurrogate(content[end - 1])) end - 1 else end
            remaining -= end
            "[附件 ${document.name}]\n${content.substring(0, safeEnd)}${if (end < content.length) "\n[附件节选]" else ""}"
        }
        if (sections.isNotEmpty()) result[index] = sections.joinToString("\n\n")
    }
    return result
}
