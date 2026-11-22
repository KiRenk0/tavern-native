package app.tavern.nativeclient

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

internal const val HORDE_ENDPOINT = "https://aihorde.net/api/v2"
internal const val HORDE_AUTO_MODEL = "horde:auto"
internal const val HORDE_DEFAULT_CONTEXT = 2048
internal const val HORDE_MAX_CONTEXT = 1_048_576
private val hordeOutputRange = 16..16384
private val hordeJobId = Regex("[A-Za-z0-9_-]{1,96}")

internal data class HordeSettings(
    val trustedOnly: Boolean = false,
    val adjustOutput: Boolean = true,
    val adjustContext: Boolean = false,
    val modelOutputs: Map<String, Int> = emptyMap(),
) {
    fun json() = jsonOf(
        "trustedOnly" to trustedOnly, "adjustOutput" to adjustOutput, "adjustContext" to adjustContext,
        "modelOutputs" to JSONObject(modelOutputs),
    )

    companion object {
        fun read(row: JSONObject?): HordeSettings {
            if (row == null) return HordeSettings()
            val outputs = row.optJSONObject("modelOutputs")
            return HordeSettings(row.optBoolean("trustedOnly"), row.optBoolean("adjustOutput", true),
                row.optBoolean("adjustContext"), outputs?.keys()?.asSequence()?.mapNotNull { id ->
                    outputs.optInt(id).takeIf { it in hordeOutputRange }?.let { id to it }
                }?.toMap().orEmpty())
        }
    }
}

internal fun hordeOutput(provider: Provider, model: String, explicit: Int?): Int {
    val reported = hordeModelLimit(provider, model, provider.horde.modelOutputs).takeIf { provider.horde.adjustOutput }
    return (explicit ?: 80).coerceIn(hordeOutputRange.first, reported ?: hordeOutputRange.last)
}

internal fun hordeModelLimit(provider: Provider, model: String, limits: Map<String, Int>): Int? {
    if (model != HORDE_AUTO_MODEL) return limits[model]
    val choices = provider.choices.filterNot { it == HORDE_AUTO_MODEL }
    return if (choices.isEmpty()) limits.values.minOrNull() else choices.mapNotNull(limits::get).minOrNull()
}

internal data class HordeWorkerLimits(val contexts: Map<String, Int>, val outputs: Map<String, Int>)

internal class Horde(
    private val provider: Provider,
    private val pollIntervalMs: Long = 2_500L,
    private val queueTimeoutMs: Long = 20 * 60_000L,
) {
    private val root = checkedAddress(provider.endpoint).toString().trimEnd('/').let {
        when {
            it.endsWith("/api/v2") || it.endsWith("/v2") -> it
            it.endsWith("/api") -> "$it/v2"
            else -> "$it/api/v2"
        }
    }
    private val key = provider.apiKey.trim()
    private val agent = mapOf("Client-Agent" to "Tavern-Native:0.75.0:local-client")
    private val authenticated = agent + ("apikey" to key.ifBlank { "0000000000" })

    private suspend fun <T> exchange(
        path: String, body: JSONObject? = null, method: String? = null, read: (String) -> T,
    ): T = request("$root$path", body, authenticated, readTimeout = if (method == "DELETE") 5_000 else 20_000,
        redactError = { it.redactValue(key) }, method = method) { stream, _ ->
        read(stream.readLimited().decodeToString())
    }

    private suspend fun json(path: String, body: JSONObject? = null): JSONObject =
        exchange(path, body, read = ::JSONObject)

    private suspend fun catalog(type: String) = exchange("/status/models?type=$type", read = ::JSONArray)
        .objects().mapNotNull { row ->
            row.string("name").trim().takeIf { it.isNotEmpty() && it != HORDE_AUTO_MODEL && row.optInt("count", 1) > 0 }
        }.distinct().sorted()

    suspend fun imageModels(): List<String> = catalog("image")

    suspend fun textModels(): DiscoveredModels {
        val ids = catalog("text")
        if (ids.isEmpty()) return DiscoveredModels(emptyList(), emptyMap(), hordeOutputs = emptyMap())
        val available = ids.toSet()
        val limits = workerLimits()
        return DiscoveredModels(listOf(HORDE_AUTO_MODEL) + ids, limits?.contexts.orEmpty().filterKeys(available::contains),
            hordeOutputs = limits?.outputs.orEmpty().filterKeys(available::contains))
    }

    suspend fun workerLimits(): HordeWorkerLimits? {
        val workers = try {
            exchange("/workers?type=text", read = ::JSONArray).objects().filter {
                !it.optBoolean("maintenance_mode") && !it.optBoolean("paused") &&
                    (!provider.horde.trustedOnly || it.optBoolean("trusted")) && it.optBoolean("accepts_prompt_jobs", true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Model discovery still works on deployments that restrict the detailed worker list.
            return null
        }
        val contexts = mutableMapOf<String, Int>()
        val outputs = mutableMapOf<String, Int>()
        fun putMinimum(target: MutableMap<String, Int>, id: String, value: Int) {
            target[id] = minOf(value, target[id] ?: value)
        }
        for (worker in workers) {
            val matched = worker.optJSONArray("models").strings()
            val context = worker.optInt("max_context_length").takeIf { it in 80..HORDE_MAX_CONTEXT }
            val output = worker.optInt("max_length").takeIf { it in hordeOutputRange }
            for (id in matched) {
                if (context != null) putMinimum(contexts, id, context)
                if (output != null) putMinimum(outputs, id, output)
            }
        }
        return HordeWorkerLimits(contexts, outputs)
    }

    private suspend fun queued(
        start: String, input: JSONObject, statusPath: String, checkPath: String = statusPath,
        progress: (String) -> Unit = {}, inspect: (JSONObject) -> Boolean,
    ): JSONObject {
        var cancelPath: String? = null
        var finished = false
        try {
            currentCoroutineContext().ensureActive()
            // Keep the submission alive just long enough to learn the job ID when Stop is pressed.
            val id = withContext(NonCancellable) {
                val accepted = json(start, input)
                val value = accepted.string("id").takeIf(hordeJobId::matches)
                    ?: error(accepted.string("message").redactValue(key).take(250).ifBlank { "AI Horde 未返回任务 ID" })
                cancelPath = "$statusPath/$value"
                value
            }
            val check = "$checkPath/$id"
            val last = withTimeoutOrNull(queueTimeoutMs) {
                var consecutiveErrors = 0
                var result: JSONObject? = null
                while (result == null) {
                    val status = try {
                        json(check).also { consecutiveErrors = 0 }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val transient = e is IOException || e is HttpRequestError && (e.status == 429 || e.status in 500..599)
                        if (!transient || ++consecutiveErrors >= 4) throw e
                        progress("AI Horde 状态查询暂时失败，正在重试")
                        delay(pollIntervalMs * consecutiveErrors)
                        continue
                    }
                    if (inspect(status)) result = status
                    else {
                        progress(queueLabel(status))
                        delay(pollIntervalMs)
                    }
                }
                result
            } ?: error("AI Horde 排队超时")
            finished = true
            return if (checkPath == statusPath) last else json("$statusPath/$id")
        } finally {
            if (!finished) cancelPath?.let { path ->
                withContext(NonCancellable) {
                    // Failure during cleanup must not replace the original error or cancellation.
                    runCatching { exchange(path, method = "DELETE") {} }
                }
            }
        }
    }

    private fun queueLabel(status: JSONObject): String {
        val position = status.optInt("queue_position", -1)
        val seconds = status.optInt("wait_time", -1)
        return when {
            status.optInt("processing", 0) > 0 || status.string("state") == "processing" -> "AI Horde 正在生成"
            position > 0 && seconds > 0 -> "AI Horde 排队第 $position 位，预计 $seconds 秒"
            position > 0 -> "AI Horde 排队第 $position 位"
            else -> "正在等待 AI Horde 节点"
        }
    }

    private fun generationFinished(status: JSONObject): Boolean {
        check(!status.optBoolean("faulted")) { "AI Horde 节点生成失败" }
        if (status.optBoolean("done")) return true
        check(status.optBoolean("is_possible", true) || status.optInt("processing") > 0) {
            "当前没有能处理此模型与参数的 AI Horde 节点"
        }
        return false
    }

    suspend fun complete(
        payload: JSONObject, prepared: TextCompletionPrompt?, customStops: List<String>, sendStops: Boolean,
        progress: (String) -> Unit, token: (String) -> Unit,
    ): CompletionAccumulator {
        val model = payload.string("model")
        val connection = ModelConnection(provider, model)
        val prompt = prepared ?: ProviderProtocols.flattenMessagesToPrompt(payload.optJSONArray("messages")?.objects().orEmpty())
        val stops = (customStops + prompt.stops).distinct()
        val context = payload.optInt(HORDE_CONTEXT_PAYLOAD).takeIf { it > 0 } ?: connection.contextTokens()
        val length = hordeOutput(provider, model, payload.optInt("max_tokens").takeIf { it > 0 })
        val common = JSONObject()
        applyTextSampler(payload.optJSONObject(TEXT_SAMPLER_PAYLOAD), common, TextBackend.KOBOLD)
        for (key in listOf("temperature", "top_p")) payload.opt(key)?.let { common.put(key, it) }
        val params = jsonOf("n" to 1, "max_context_length" to context.coerceIn(80, HORDE_MAX_CONTEXT),
            "max_length" to length, "frmtadsnsp" to false, "frmtrmblln" to false, "frmtrmspch" to false, "frmttriminc" to false)
        for (name in hordeSupportedSamplers) common.opt(name)?.let { params.put(name, it) }
        if (sendStops && stops.isNotEmpty()) params.put("stop_sequence", JSONArray(stops.take(128)))
        val ids = if (model == HORDE_AUTO_MODEL) provider.choices.filterNot { it == HORDE_AUTO_MODEL } else listOf(model)
        val input = jsonOf("prompt" to prompt.text, "params" to params, "trusted_workers" to provider.horde.trustedOnly,
            "models" to ids.takeIf(List<String>::isNotEmpty)?.let(::JSONArray))
        val completed = queued("/generate/text/async", input, "/generate/text/status", progress = progress,
            inspect = ::generationFinished)
        val reply = completed.optJSONArray("generations")?.objects()?.firstOrNull { it.string("text").isNotBlank() }
        checkNotNull(reply) { "AI Horde 任务已结束，节点未返回可用文本" }
        return CompletionAccumulator(stops).also { result ->
            result.appendText(reply.string("text"), token)
            result.finishReason = if (reply.string("finish_reason") == "length") "length" else "stop"
            result.finishText(token)
        }
    }

    suspend fun image(options: ImageRequest): ByteArray {
        val tuning = options.tuning
        val width = tuning.width ?: options.shape.width
        val height = tuning.height ?: options.shape.height
        require(listOf(width, height).all { it in 64..3072 && it % 64 == 0 }) { "AI Horde 图像宽高须为 64 到 3072 内的 64 的倍数" }
        val negative = tuning.negative.take(2500)
        val prompt = options.prompt.take(5000 - negative.length - 5) + " ### " + negative
        val params = jsonOf("n" to 1, "width" to width, "height" to height, "steps" to (tuning.steps ?: 25),
            "cfg_scale" to tuning.cfg, "seed" to tuning.seed?.takeIf { it >= 0 }?.toString(), "clip_skip" to tuning.skip,
            "sampler_name" to tuning.sampler.takeIf(String::isNotBlank), "scheduler" to tuning.scheduler.takeIf(String::isNotBlank))
        val body = jsonOf("prompt" to prompt, "params" to params, "r2" to false, "shared" to false,
            "trusted_workers" to provider.horde.trustedOnly,
            "models" to options.model.trim().takeIf(String::isNotBlank)?.let { JSONArray(listOf(it)) })
        val done = queued("/generate/async", body, "/generate/status", "/generate/check", inspect = ::generationFinished)
        val generation = done.optJSONArray("generations")?.optJSONObject(0)
        check(generation != null && !generation.optBoolean("censored") && generation.string("state") != "censored") {
            "AI Horde 没有返回可用图片"
        }
        return decodeGeneratedImage(generation.string("img"))
    }

    suspend fun caption(dataUrl: String): String {
        val body = jsonOf("source_image" to dataUrl.substringAfter("base64,", dataUrl),
            "forms" to JSONArray().put(jsonOf("name" to "caption")))
        val done = queued("/interrogate/async", body, "/interrogate/status") { status ->
            when (status.string("state")) {
                "done", "partial" -> true
                "cancelled", "faulted" -> error("AI Horde 图片描述任务失败")
                else -> false
            }
        }
        val caption = done.optJSONArray("forms")?.objects()?.firstOrNull { it.string("form") == "caption" || it.string("name") == "caption" }
            ?: done.optJSONArray("forms")?.optJSONObject(0)
        return caption?.optJSONObject("result")?.string("caption")?.trim()?.take(MAX_IMAGE_CAPTION)?.takeIf(String::isNotBlank)
            ?: error("AI Horde 没有返回图片描述")
    }
}

internal const val HORDE_CONTEXT_PAYLOAD = "_tavern_horde_context"
private val hordeSupportedSamplers = setOf(
    "rep_pen", "rep_pen_range", "rep_pen_slope", "singleline", "temperature", "tfs", "top_a", "top_k", "top_p", "typical",
    "sampler_order", "use_default_badwordsids", "min_p", "smoothing_factor", "dynatemp_range", "dynatemp_exponent",
)
