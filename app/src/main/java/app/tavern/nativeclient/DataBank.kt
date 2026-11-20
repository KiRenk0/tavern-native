package app.tavern.nativeclient

import org.json.JSONObject

internal const val MAX_DOCUMENT_CHARACTERS = 2_000_000
internal const val DOCUMENT_EDITOR_LIMIT = 120_000

internal enum class DataScope(val label: String) {
    GLOBAL("全局"), CHARACTER("角色"), CHAT("对话");
}

internal data class DataOwner(val scope: DataScope, val id: String? = null) {
    init { require((scope == DataScope.GLOBAL) == (id == null)) }
}

internal data class DataDocument(
    val id: String = newId(),
    val title: String,
    val owner: DataOwner,
    val enabled: Boolean = true,
    val size: Int = 0,
    val source: String = "",
    val updated: Long = System.currentTimeMillis(),
) {
    val indexScope get() = "document:$id"
    val address get() = "tavern://document/$id"
}

internal data class DocumentText(val document: DataDocument, val text: String, val truncated: Boolean = false)

internal data class FileVectorSettings(
    val enabled: Boolean = false,
    val thresholdKb: Int = 1,
    val chunkLength: Int = 800,
    val overlap: Int = 10,
    val limit: Int = 6,
    val position: MemoryPosition = MemoryPosition.AFTER_MAIN,
    val role: PromptRole = PromptRole.SYSTEM,
    val depth: Int = 2,
    val template: String = "[参考资料]\n{{text}}",
    val scanLore: Boolean = false,
) {
    fun render(text: String) = if ("{{text}}" in template) template.replace("{{text}}", text)
        else listOf(template, text).filter(String::isNotBlank).joinToString("\n")

    fun json() = jsonOf("enabled" to enabled, "thresholdKb" to thresholdKb, "chunkLength" to chunkLength,
        "overlap" to overlap, "limit" to limit, "position" to position.name, "role" to role.wire,
        "depth" to depth, "template" to template, "scanLore" to scanLore)

    companion object {
        fun from(value: JSONObject?): FileVectorSettings {
            if (value == null) return FileVectorSettings()
            val default = FileVectorSettings()
            return default.copy(enabled = value.optBoolean("enabled"),
                thresholdKb = value.optInt("thresholdKb", default.thresholdKb).coerceIn(0, 8),
                chunkLength = value.optInt("chunkLength", default.chunkLength).coerceIn(80, 1600),
                overlap = value.optInt("overlap", default.overlap).coerceIn(0, 50),
                limit = value.optInt("limit", default.limit).coerceIn(1, 20),
                position = MemoryPosition.entries.firstOrNull { it.name == value.string("position") } ?: default.position,
                role = PromptRole.entries.firstOrNull { it.wire == value.string("role") } ?: default.role,
                depth = value.optInt("depth", default.depth).coerceIn(0, 80),
                template = if (value.has("template")) value.string("template") else default.template,
                scanLore = value.optBoolean("scanLore"))
        }
    }
}

internal data class RecalledDocument(val id: String, val title: String, val fragments: List<String>)

internal fun fileCorpus(document: DataDocument, content: String, prefs: VectorPreferences): VectorCorpus {
    val files = prefs.files
    val parts = if (content.encodeToByteArray().size <= files.thresholdKb * 1024) listOf(content.trim()).filter(String::isNotBlank)
        else vectorChunks(content, files.chunkLength, prefs.boundary, files.overlap)
    return VectorCorpus(document.indexScope, parts.mapIndexed { index, text ->
        VectorFragment(index.toString(), EmbeddingInput(document.title, text), partIndex = index)
    })
}

internal fun validatedDocumentText(text: String): String {
    val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').trim()
    require(normalized.isNotEmpty()) { "未提取到可用文本" }
    require(normalized.length <= MAX_DOCUMENT_CHARACTERS) { "单个资料最多支持 200 万字符" }
    require(normalized.none { it < ' ' && it !in "\t\n\u000C" }) { "文件包含不可读的二进制内容" }
    return normalized
}
