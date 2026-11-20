package app.tavern.nativeclient

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import kotlin.math.sqrt

internal const val DEFAULT_EMBEDDING_MODEL = "gemini-embedding-2"
internal const val WORLD_VECTOR_SCOPE = "world"
private const val VECTOR_BATCH = 24
private const val MAX_VECTOR_DIMENSION = 8192

internal data class VectorPreferences(
    val providerId: String = "",
    val model: String = "",
    val worldEnabled: Boolean = false,
    val worldAll: Boolean = false,
    val chatEnabled: Boolean = false,
    val queryMessages: Int = 2,
    val threshold: Float = 0.25f,
    val worldLimit: Int = 5,
    val chatLimit: Int = 3,
    val retained: Int = 5,
    val chunkLength: Int = 400,
    val boundary: String = "",
    val position: MemoryPosition = MemoryPosition.AFTER_MAIN,
    val role: PromptRole = PromptRole.SYSTEM,
    val depth: Int = 2,
    val template: String = "[过去的对话]\n{{text}}",
    val files: FileVectorSettings = FileVectorSettings(),
    val messageFiles: MessageFileSettings = MessageFileSettings(),
) {
    val enabled get() = worldEnabled || chatEnabled || files.enabled || messageFiles.enabled

    fun source(providers: List<Provider>): EmbeddingSource? {
        if (providerId == LOCAL_EMBEDDING_SOURCE_ID) {
            val modelId = model.ifBlank { LocalEmbeddingModel.JINA_EN.id }
            return LocalEmbeddingModel.entries.firstOrNull { it.id == modelId }?.let { EmbeddingSource.Local(it) }
        }
        val provider = providers.firstOrNull { it.id == providerId && supportsEmbeddings(it) } ?: return null
        val modelId = model.trim().removePrefix("models/").ifBlank { if (provider.template == ProviderTemplate.GOOGLE) DEFAULT_EMBEDDING_MODEL else "" }
        return modelId.takeIf(String::isNotBlank)?.let { EmbeddingSource.Remote(provider, it) }
    }

    fun render(text: String) = if ("{{text}}" in template) template.replace("{{text}}", text)
        else listOf(template, text).filter(String::isNotBlank).joinToString("\n")

    fun json() = jsonOf("provider" to providerId, "model" to model, "world" to worldEnabled, "worldAll" to worldAll,
        "chat" to chatEnabled, "query" to queryMessages, "threshold" to threshold, "worldLimit" to worldLimit,
        "chatLimit" to chatLimit, "retained" to retained, "chunkLength" to chunkLength, "boundary" to boundary,
        "position" to position.name, "role" to role.wire, "depth" to depth, "template" to template, "files" to files.json(),
        "messageFiles" to messageFiles.json())

    companion object {
        fun from(row: JSONObject?): VectorPreferences {
            if (row == null) return VectorPreferences()
            val default = VectorPreferences()
            return default.copy(providerId = row.string("provider"), model = row.string("model"),
                worldEnabled = row.optBoolean("world"), worldAll = row.optBoolean("worldAll"), chatEnabled = row.optBoolean("chat"),
                queryMessages = row.optInt("query", default.queryMessages).coerceIn(1, 10),
                threshold = row.optDouble("threshold", default.threshold.toDouble()).toFloat().takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: default.threshold,
                worldLimit = row.optInt("worldLimit", default.worldLimit).coerceIn(1, 30),
                chatLimit = row.optInt("chatLimit", default.chatLimit).coerceIn(1, 20),
                retained = row.optInt("retained", default.retained).coerceIn(0, 80),
                chunkLength = row.optInt("chunkLength", default.chunkLength).coerceIn(80, 1600), boundary = row.string("boundary"),
                position = MemoryPosition.entries.firstOrNull { it.name == row.string("position") } ?: default.position,
                role = PromptRole.entries.firstOrNull { it.wire == row.string("role") } ?: default.role,
                depth = row.optInt("depth", default.depth).coerceIn(0, 80),
                template = if (row.has("template")) row.string("template") else default.template,
                files = FileVectorSettings.from(row.optJSONObject("files")),
                messageFiles = MessageFileSettings.from(row.optJSONObject("messageFiles")),
            )
        }
    }
}

internal fun supportsEmbeddings(provider: Provider) = when (provider.template) {
    ProviderTemplate.GOOGLE -> provider.apiKey.isNotBlank()
    ProviderTemplate.DEEPSEEK -> false
    null -> provider.endpoint.isNotBlank()
}

internal sealed interface EmbeddingSource {
    val space: String

    data class Remote(val provider: Provider, val model: String) : EmbeddingSource {
        override val space = vectorDigest("embeddings:v1\u0000${provider.template?.name}\u0000${provider.endpoint}\u0000${provider.id}\u0000$model")
    }

    data class Local(val model: LocalEmbeddingModel) : EmbeddingSource {
        override val space = vectorDigest("embeddings:android-onnx-v1\u0000${model.id}\u0000${model.sha256}")
    }
}

internal data class EmbeddingInput(val title: String, val text: String, val query: Boolean = false) {
    val key: String = vectorDigest(JSONArray(listOf(title, text, query)).toString())
}

private fun vectorDigest(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(
    MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()))

internal class EmbeddingService(private val googleBase: String = "https://generativelanguage.googleapis.com/v1beta",
                                private val local: LocalEmbeddings? = null) {
    suspend fun embed(source: EmbeddingSource, inputs: List<EmbeddingInput>): List<FloatArray> {
        require(inputs.isNotEmpty() && inputs.size <= VECTOR_BATCH)
        return when (source) {
            is EmbeddingSource.Local -> withContext(Dispatchers.Default) {
                val engine = checkNotNull(local) { "本地嵌入运行库不可用" }
                val context = currentCoroutineContext()
                inputs.map { input ->
                    context.ensureActive()
                    engine.embed(source.model, input)
                }
            }
            is EmbeddingSource.Remote -> if (source.provider.template == ProviderTemplate.GOOGLE) google(source, inputs)
                else compatible(source, inputs)
        }
    }

    private suspend fun compatible(source: EmbeddingSource.Remote, inputs: List<EmbeddingInput>): List<FloatArray> {
        val root = checkedAddress(source.provider.endpoint).toString().trimEnd('/').removeSuffix("/chat/completions").removeSuffix("/embeddings")
        val headers = source.provider.apiKey.trim().takeIf(String::isNotBlank)?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty()
        val body = jsonOf("model" to source.model, "input" to JSONArray(inputs.map { it.text }), "encoding_format" to "float")
        return request("$root/embeddings", body, headers) { input, _ ->
            val rows = JSONObject(input.readLimited().toString(Charsets.UTF_8)).getJSONArray("data").objects()
            require(rows.size == inputs.size) { "向量服务返回的条目数量不匹配" }
            val output = arrayOfNulls<FloatArray>(inputs.size)
            rows.forEachIndexed { position, row ->
                val index = row.optInt("index", position)
                require(index in output.indices && output[index] == null) { "向量服务返回了无效索引" }
                output[index] = normalized(row.getJSONArray("embedding"))
            }
            output.map(::requireNotNull)
        }
    }

    private suspend fun google(source: EmbeddingSource.Remote, inputs: List<EmbeddingInput>): List<FloatArray> {
        require(source.model.matches(Regex("[a-zA-Z0-9._-]+"))) { "Gemini 向量模型 ID 无效" }
        val model = "models/${source.model}"
        val legacy = source.model == "gemini-embedding-001"
        val rows = inputs.map { item ->
            val value = if (legacy) item.text else if (item.query) "task: search result | query: ${item.text}"
                else "title: ${item.title.ifBlank { "none" }} | text: ${item.text}"
            val content = jsonOf("parts" to JSONArray().put(jsonOf("text" to value)))
            jsonOf("model" to model, "content" to content, "outputDimensionality" to 768).apply {
                if (legacy) put("taskType", if (item.query) "RETRIEVAL_QUERY" else "RETRIEVAL_DOCUMENT")
            }
        }
        val headers = mapOf("x-goog-api-key" to source.provider.apiKey.trim())
        return request("$googleBase/$model:batchEmbedContents", jsonOf("requests" to JSONArray(rows)), headers) { input, _ ->
            val vectors = JSONObject(input.readLimited().toString(Charsets.UTF_8)).getJSONArray("embeddings").objects()
            require(vectors.size == inputs.size) { "Gemini 返回的向量数量不匹配" }
            vectors.map { normalized(it.getJSONArray("values")) }
        }
    }

    private fun normalized(row: JSONArray): FloatArray {
        require(row.length() in 1..MAX_VECTOR_DIMENSION) { "向量维度超出支持范围" }
        return normalizedEmbedding(FloatArray(row.length()) { index -> (row.opt(index) as? Number)?.toFloat() ?: Float.NaN })
    }
}

internal fun normalizedEmbedding(values: FloatArray): FloatArray {
    require(values.all(Float::isFinite)) { "向量服务返回了无效数值" }
    val norm = sqrt(values.sumOf { it.toDouble() * it })
    require(norm > 0) { "向量服务返回了空向量" }
    return FloatArray(values.size) { (values[it] / norm).toFloat() }
}

internal fun vectorBytes(vector: FloatArray): ByteArray = ByteBuffer.allocate(vector.size * 4).apply {
    vector.forEach(::putFloat)
}.array()

internal fun vectorFloats(bytes: ByteArray): FloatArray? {
    if (bytes.isEmpty() || bytes.size % 4 != 0 || bytes.size > MAX_VECTOR_DIMENSION * 4) return null
    val buffer = ByteBuffer.wrap(bytes)
    return FloatArray(bytes.size / 4) { buffer.float }.takeIf { it.all(Float::isFinite) }
}

internal interface VectorCache {
    fun readVectors(scope: String, space: String, keys: List<String>): Map<String, FloatArray>
    fun saveVectors(scope: String, space: String, values: Map<String, FloatArray>)
    fun clearVectors(scope: String)
}

internal data class VectorFragment(val subject: String, val input: EmbeddingInput, val messageIndex: Int? = null, val partIndex: Int = 0)
internal data class VectorCorpus(val scope: String, val fragments: List<VectorFragment>)
internal data class RecalledMessage(val index: Int, val speaker: String, val text: String)
internal data class VectorContext(val lore: Set<String> = emptySet(), val messages: List<RecalledMessage> = emptyList(),
                                  val documents: List<RecalledDocument> = emptyList(),
                                  val attachments: Map<String, List<String>> = emptyMap()) {
    val replacedIndices = messages.mapTo(mutableSetOf(), RecalledMessage::index)
}

internal fun chatVectorScope(id: String) = "chat:$id"

internal fun chatCorpus(chatId: String, history: List<Message>, config: VectorPreferences, query: Boolean): VectorCorpus {
    val visible = history.indices.filter { !history[it].excludedFromContext && history[it].requestText.isNotBlank() }
    val eligible = if (query) visible.dropLast(maxOf(config.retained, config.queryMessages)) else visible
    val fragments = eligible.asReversed().flatMap { index ->
        val message = history[index]
        if (message.displayImages.isNotEmpty() || message.documents.isNotEmpty()) return@flatMap emptyList()
        vectorChunks(message.requestText, config.chunkLength, config.boundary).map { part ->
            VectorFragment(index.toString(), EmbeddingInput(message.name, part), index)
        }
    }
    return VectorCorpus(chatVectorScope(chatId), fragments)
}

internal fun worldCorpus(candidates: List<LoreCandidate>, config: VectorPreferences): VectorCorpus {
    val fragments = candidates.filter { !it.entry.constant && (config.worldAll || it.entry.vectorized) }.flatMap { entry ->
        val (content, directive) = loreContent(entry.entry.content)
        if (directive == "@@dont_activate" || directive == "@@activate") emptyList()
        else vectorChunks(content, config.chunkLength, config.boundary).map { part ->
            VectorFragment(entry.key, EmbeddingInput(entry.entry.title, part))
        }
    }
    return VectorCorpus(WORLD_VECTOR_SCOPE, fragments)
}

internal fun vectorChunks(text: String, limit: Int, boundary: String, overlap: Int = 0): List<String> {
    require(limit >= 8 && overlap in 0..50)
    val sections = if (boundary.isEmpty()) listOf(text) else text.split(boundary)
    return sections.flatMap { section ->
        buildList {
            var start = 0
            while (start < section.length) {
                var end = minOf(start + limit, section.length)
                if (end < section.length) {
                    val separator = section.lastIndexOfAny(charArrayOf('\n', '。', '！', '？', '.', ' ', '，'), end - 1)
                    if (separator > start + limit / 2) end = separator + 1
                    else if (Character.isHighSurrogate(section[end - 1])) end--
                }
                val part = section.substring(start, end).trim()
                if (part.isNotEmpty()) add(part)
                if (end == section.length) break
                val next = (end - (end - start) * overlap / 100).coerceAtLeast(start + 1)
                start = if (Character.isLowSurrogate(section[next]) && next > start + 1) next - 1 else next
            }
        }
    }
}

internal class VectorSearch(private val cache: VectorCache, private val service: EmbeddingService = EmbeddingService()) {
    private data class Hit(val fragment: VectorFragment, val score: Double)
    private data class FileHit(val document: DataDocument, val hit: Hit)
    private class IndexBudget(var remaining: Int = VECTOR_BATCH)

    suspend fun retrieve(source: EmbeddingSource, config: VectorPreferences, chatId: String, history: List<Message>,
                         lore: List<LoreCandidate>, documents: List<DataDocument> = emptyList(),
                         readAttachment: (ChatDocument) -> String? = { null },
                         readDocument: (String) -> String? = { null }): VectorContext = withContext(Dispatchers.IO) {
        val worlds = if (config.worldEnabled) worldCorpus(lore, config) else null
        val chats = if (config.chatEnabled) chatCorpus(chatId, history, config, query = true) else null
        val files = if (config.files.enabled) documents.filter(DataDocument::enabled) else emptyList()
        val hasAttachments = config.messageFiles.enabled && promptHistory(history).any { message ->
            message.documents.any { it.bytes > config.messageFiles.thresholdKb * 1024 }
        }
        if (worlds?.fragments.isNullOrEmpty() && chats?.fragments.isNullOrEmpty() && files.isEmpty() && !hasAttachments) return@withContext VectorContext()
        val text = history.asSequence().filter { !it.excludedFromContext && it.requestText.isNotBlank() }.toList()
            .takeLast(config.queryMessages).joinToString("\n") { it.requestText.takeLast(1600) }.takeLast(8000)
        if (text.isBlank()) return@withContext VectorContext()
        val query = EmbeddingInput("", text, query = true)
        val queryVector = cached(source, chatVectorScope(chatId), listOf(query), 1).getValue(query.key)
        val worldHits = worlds?.let { rank(source, it, queryVector, config.threshold, config.worldLimit) }.orEmpty()
        val chatHits = chats?.let { rank(source, it, queryVector, config.threshold, config.chatLimit) }.orEmpty()
        val recalledFiles = rankFiles(source, config, files, queryVector, readDocument)
        val recalledChat = chatHits.asReversed().mapNotNull { hit ->
            val fragment = hit.fragment
            fragment.messageIndex?.let { RecalledMessage(it, fragment.input.title, fragment.input.text) }
        }
        val relocated = recalledChat.mapTo(mutableSetOf(), RecalledMessage::index)
        val snippets = if (config.messageFiles.enabled) rankAttachments(source, config,
            promptHistory(history.filterIndexed { index, _ -> index !in relocated }), queryVector, readAttachment) else emptyMap()
        VectorContext(worldHits.mapTo(mutableSetOf()) { it.fragment.subject }, recalledChat, recalledFiles, snippets)
    }

    private suspend fun rankAttachments(source: EmbeddingSource, config: VectorPreferences, history: List<Message>,
                                        query: FloatArray, read: (ChatDocument) -> String?): Map<String, List<String>> {
        val prefs = config.messageFiles
        val budget = IndexBudget()
        val result = mutableMapOf<String, List<String>>()
        val references = history.asReversed().flatMap(Message::documents).distinctBy(ChatDocument::id)
        for (document in references) {
            currentCoroutineContext().ensureActive()
            if (document.bytes <= prefs.thresholdKb * 1024) continue
            val text = read(document) ?: continue
            val corpus = attachmentCorpus(document, text, config)
            val hits = rank(source, corpus, query, config.threshold, prefs.limit, budget, uniqueInput = true)
            result[document.id] = hits.sortedBy { it.fragment.partIndex }.map { it.fragment.input.text }
                .ifEmpty { corpus.fragments.take(1).map { it.input.text } }
        }
        return result
    }

    private suspend fun rankFiles(source: EmbeddingSource, config: VectorPreferences, files: List<DataDocument>,
                                  query: FloatArray, readDocument: (String) -> String?): List<RecalledDocument> {
        var best = emptyList<FileHit>()
        val budget = IndexBudget()
        for (document in files) {
            currentCoroutineContext().ensureActive()
            val text = readDocument(document.id) ?: continue
            val hits = rank(source, fileCorpus(document, text, config), query, config.threshold, config.files.limit,
                budget, uniqueInput = true)
            best = (best + hits.map { FileHit(document, it) }).sortedByDescending { it.hit.score }.take(config.files.limit)
        }
        return best.groupBy(FileHit::document).map { (document, hits) ->
            RecalledDocument(document.id, document.title,
                hits.sortedBy { it.hit.fragment.partIndex }.map { it.hit.fragment.input.text })
        }
    }

    suspend fun index(source: EmbeddingSource, corpus: VectorCorpus, progress: (Int, Int) -> Unit = { _, _ -> }): Int =
        withContext(Dispatchers.IO) {
            val inputs = corpus.fragments.map(VectorFragment::input).distinctBy(EmbeddingInput::key)
            var completed = 0
            for (batch in inputs.chunked(VECTOR_BATCH)) {
                currentCoroutineContext().ensureActive()
                cached(source, corpus.scope, batch, VECTOR_BATCH)
                completed += batch.size
                progress(completed, inputs.size)
            }
            completed
        }

    suspend fun counts(source: EmbeddingSource, corpus: VectorCorpus): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val keys = corpus.fragments.map { it.input.key }.distinct()
        keys.chunked(VECTOR_BATCH).sumOf { cache.readVectors(corpus.scope, source.space, it).size } to keys.size
    }

    private suspend fun rank(source: EmbeddingSource, corpus: VectorCorpus, query: FloatArray,
                             threshold: Float, limit: Int, budget: IndexBudget = IndexBudget(),
                             uniqueInput: Boolean = false): List<Hit> {
        val best = mutableMapOf<String, Hit>()
        for (batch in corpus.fragments.chunked(VECTOR_BATCH)) {
            currentCoroutineContext().ensureActive()
            val inputs = batch.map(VectorFragment::input).distinctBy(EmbeddingInput::key)
            val cached = cache.readVectors(corpus.scope, source.space, inputs.map(EmbeddingInput::key)).toMutableMap()
            val missing = inputs.filter { it.key !in cached }.take(budget.remaining)
            if (missing.isNotEmpty()) {
                cached += load(source, corpus.scope, missing)
                budget.remaining -= missing.size
            }
            for (fragment in batch) {
                val vector = cached[fragment.input.key]?.takeIf { it.size == query.size } ?: continue
                val score = query.indices.sumOf { query[it].toDouble() * vector[it] }.coerceIn(-1.0, 1.0)
                if (score < threshold || score <= (best[fragment.subject]?.score ?: -1.0)) continue
                best[fragment.subject] = Hit(fragment, score)
            }
        }
        val ranked = best.values.sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.fragment.partIndex }
            .thenBy { it.fragment.subject })
        return (if (uniqueInput) ranked.distinctBy { it.fragment.input.key } else ranked).take(limit)
    }

    private suspend fun cached(source: EmbeddingSource, scope: String, inputs: List<EmbeddingInput>, limit: Int): Map<String, FloatArray> {
        val existing = cache.readVectors(scope, source.space, inputs.map(EmbeddingInput::key))
        val missing = inputs.filter { it.key !in existing }.take(limit)
        return if (missing.isEmpty()) existing else existing + load(source, scope, missing)
    }

    private suspend fun load(source: EmbeddingSource, scope: String, inputs: List<EmbeddingInput>): Map<String, FloatArray> =
        buildMap {
            // Save completed local batches during longer CPU indexing so interrupted requests can resume.
            val batchSize = if (source is EmbeddingSource.Local) 4 else VECTOR_BATCH
            for (batch in inputs.chunked(batchSize)) {
                val result = batch.map(EmbeddingInput::key).zip(service.embed(source, batch)).toMap()
                cache.saveVectors(scope, source.space, result)
                putAll(result)
            }
        }
}
