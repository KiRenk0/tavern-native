package app.tavern.nativeclient

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.nio.LongBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal const val LOCAL_EMBEDDING_SOURCE_ID = "tavern-native:local-onnx"
private const val HIDDEN_STATE = "last_hidden_state"

internal enum class LocalEmbeddingModel(
    val id: String,
    val label: String,
    val repository: String,
    val revision: String,
    val bytes: Long,
    val sha256: String,
    val dimensions: Int,
    val lowercase: Boolean,
    val meanPooling: Boolean,
    val queryPrefix: String = "",
) {
    JINA_EN("jina-v2-en", "Jina v2 Base（英文，109 MB）", "Cohee/jina-embeddings-v2-base-en",
        "e5e0a9ba66fd58c48e6f5a60293e7d1af2348225", 109_454_110L,
        "25de03c82059aef5f6cc33dcce981ecfef9b986d17390b418731cf6b5930bf7c",
        dimensions = 768, lowercase = true, meanPooling = true),
    BGE_ZH("bge-small-zh", "BGE Small v1.5（中文，24 MB）", "Xenova/bge-small-zh-v1.5",
        "75c43b069aac4d136ba6bc1122f995fedcfd2781", 24_010_842L,
        "15b717c382bcb518ba457b93ea6850ede7f4f1cd8937454aa06972366cd19bcc",
        dimensions = 512, lowercase = false, meanPooling = false, queryPrefix = "为这个句子生成表示以用于检索相关文章：");

    val downloadPath get() = "$repository/resolve/$revision/onnx/model_quantized.onnx"
}

internal class LocalEmbeddings(private val directory: File, private val asset: (String) -> InputStream) : Closeable {
    private var session: Pair<LocalEmbeddingModel, OrtSession>? = null
    private var closed = false
    private val tokenizers = mutableMapOf<LocalEmbeddingModel, BertWordPiece>()

    private fun weights(model: LocalEmbeddingModel) = File(directory, "${model.id}-${model.sha256.take(8)}.onnx")

    @Synchronized
    fun installed(model: LocalEmbeddingModel) = weights(model).let { it.isFile && it.length() == model.bytes }

    fun install(model: LocalEmbeddingModel, source: InputStream, progress: (Long) -> Unit) {
        val destination = weights(model)
        installVerifiedWeights(destination, model.bytes, model.sha256, source, progress) { candidate ->
            synchronized(this) {
                check(!closed) { "本地模型已关闭" }
                progress(model.bytes)
                if (session?.first == model) releaseSession()
                Files.move(candidate.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            }
        }
    }

    @Synchronized
    fun embed(model: LocalEmbeddingModel, input: EmbeddingInput): FloatArray {
        check(!closed) { "本地模型已关闭" }
        check(installed(model)) { "请先安装所选本地嵌入模型" }
        return try {
            val environment = OrtEnvironment.getEnvironment()
            val runner = sessionFor(environment, model)
            val tokenizer = tokenizers.getOrPut(model) {
                asset("${model.id}/vocab.txt").bufferedReader().use { BertWordPiece(it.readLines(), model.lowercase) }
            }
            val ids = tokenizer.encode(input.text, prefix = if (input.query) model.queryPrefix else "", keepEnd = input.query)
            val shape = longArrayOf(1, ids.size.toLong())
            OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), shape).use { tokens ->
                OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(ids.size) { 1L }), shape).use { mask ->
                    OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(ids.size)), shape).use { segments ->
                        val inputs = mutableMapOf("input_ids" to tokens, "attention_mask" to mask)
                        if ("token_type_ids" in runner.inputNames) inputs["token_type_ids"] = segments
                        runner.run(inputs, setOf(HIDDEN_STATE)).use { output ->
                            val tensor = output[0] as OnnxTensor
                            check(tensor.info.shape.contentEquals(longArrayOf(1, shape[1], model.dimensions.toLong()))) {
                                "本地嵌入模型返回的向量形状无效"
                            }
                            val vectors = tensor.floatBuffer
                            val pooled = FloatArray(model.dimensions)
                            val rows = if (model.meanPooling) ids.size else 1
                            repeat(rows) {
                                for (i in pooled.indices) pooled[i] += vectors.get()
                            }
                            // For masked mean, dividing by the common token count cancels during L2 normalization.
                            normalizedEmbedding(pooled)
                        }
                    }
                }
            }
        } catch (e: LinkageError) {
            throw IllegalStateException("设备无法加载本地嵌入运行库", e)
        }
    }

    private fun sessionFor(environment: OrtEnvironment, model: LocalEmbeddingModel): OrtSession {
        session?.takeIf { it.first == model }?.let { return it.second }
        releaseSession()
        return OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            environment.createSession(weights(model).absolutePath, options)
        }.also { session = model to it }
    }

    @Synchronized
    fun remove(model: LocalEmbeddingModel) {
        if (session?.first == model) releaseSession()
        val file = weights(model)
        check(!file.exists() || file.delete()) { "无法删除本地嵌入模型" }
    }

    @Synchronized
    override fun close() {
        closed = true
        releaseSession()
        tokenizers.clear()
    }

    private fun releaseSession() {
        val previous = session
        session = null
        previous?.second?.close()
    }
}
