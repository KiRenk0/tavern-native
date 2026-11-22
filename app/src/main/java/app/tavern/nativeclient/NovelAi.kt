package app.tavern.nativeclient

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

internal enum class NovelAiModel(val id: String, val label: String) {
    CLIO("clio-v1", "Clio"),
    KAYRA("kayra-v1", "Kayra"),
    ERATO("llama-3-erato-v1", "Erato");

    companion object {
        fun from(id: String): NovelAiModel? = entries.firstOrNull { it.id == id }
    }
}

internal const val NOVEL_AI_ENDPOINT = "https://text.novelai.net"
internal const val NOVEL_AI_PREAMBLE = "[ Style: chat, complex, sensory, visceral ]"
internal const val NOVEL_AI_PRESET_LIMIT = 512 * 1024
private const val NOVEL_AI_LEGACY_ENDPOINT = "https://api.novelai.net"
private const val NOVEL_AI_ACCOUNT_ENDPOINT = "https://image.novelai.net"
private const val ERATO_PREAMBLE = "<|startoftext|><|reserved_special_token81|>"
private const val NOVEL_AI_INTERRUPTED = "NovelAI 连接在生成完成前中断"

internal data class NovelAiPreset(val name: String, val parameters: String, val context: Int?) {
    fun json() = JSONObject(parameters).apply {
        put("name", name)
        context?.let { put("max_context", it) }
    }

    companion object {
        private val numeric = setOf(
            "temperature", "max_length", "min_length", "top_k", "top_p", "top_a", "min_p", "typical_p",
            "tail_free_sampling", "repetition_penalty", "repetition_penalty_range", "repetition_penalty_slope",
            "repetition_penalty_frequency", "repetition_penalty_presence", "mirostat_lr", "mirostat_tau",
            "math1_temp", "math1_quad", "math1_quad_entropy_scale", "eos_token_id",
        )
        private val text = setOf("phrase_rep_pen", "prefix")
        private val flags = setOf("generate_until_sentence", "use_cache")
        private val arrays = setOf("stop_sequences", "bad_words_ids", "logit_bias_exp", "repetition_penalty_whitelist")
        private val samplers = mapOf("temperature" to 0, "top_k" to 1, "top_p" to 2, "tfs" to 3,
            "top_a" to 4, "typical_p" to 5, "mirostat" to 8, "math1" to 9, "min_p" to 10)

        fun parse(source: JSONObject, filename: String = "自定义"): NovelAiPreset {
            val wrapped = source.optJSONObject("parameters")
            val values = wrapped ?: source
            val parameters = JSONObject()
            for (key in numeric + text + flags + arrays + "order") {
                val value = values.opt(key)
                if (value == null || value == JSONObject.NULL) continue
                val valid = when (key) {
                    in numeric -> value is Number && value.toDouble().isFinite()
                    in text -> value is String
                    in flags -> value is Boolean
                    else -> value is JSONArray
                }
                require(valid) { "NovelAI 预设参数 $key 的类型无效" }
                if (key == "order") {
                    val order = value as JSONArray
                    val ids = (0 until order.length()).mapNotNull { index ->
                        when (val step = order.opt(index)) {
                            is Number -> step.toInt().takeIf { it in samplers.values }
                            is JSONObject -> samplers[step.string("id")].takeIf { step.optBoolean("enabled", true) }
                            else -> null
                        }
                    }.distinct()
                    parameters.put(key, JSONArray(ids))
                } else parameters.put(key, value)
            }
            require(parameters.length() > 0) { "文件没有可用的 NovelAI 采样参数" }
            val context = source.optInt("max_context").takeIf { it in 1..MAX_CONTEXT_TOKENS }
                ?: values.optInt("max_context").takeIf { it in 1..MAX_CONTEXT_TOKENS }
            return NovelAiPreset(source.string("name").trim().take(120).ifBlank { filename }, parameters.toString(), context)
        }
    }
}

internal data class NovelAiSettings(
    val preamble: String = NOVEL_AI_PREAMBLE,
    val presets: Map<NovelAiModel, NovelAiPreset> = emptyMap(),
    val tier: Int? = null,
) {
    fun preset(model: NovelAiModel) = presets[model] ?: NovelAiDefaults.presets.getValue(model)
    fun json() = jsonOf("preamble" to preamble, "tier" to tier, "presets" to JSONObject().apply {
        presets.forEach { (model, preset) -> put(model.id, preset.json()) }
    })

    companion object {
        fun read(value: JSONObject?): NovelAiSettings {
            if (value == null) return NovelAiSettings()
            val rows = value.optJSONObject("presets")
            val presets = NovelAiModel.entries.mapNotNull { model ->
                rows?.optJSONObject(model.id)?.let { row ->
                    runCatching { NovelAiPreset.parse(row) }.getOrNull()?.let { model to it }
                }
            }.toMap()
            return NovelAiSettings(
                value.optString("preamble", NOVEL_AI_PREAMBLE),
                presets,
                value.optInt("tier", -1).takeIf { it in 0..3 },
            )
        }
    }
}

internal object NovelAi {
    fun encodeStops(model: String, stops: List<String>, encode: ((String) -> IntArray)?): List<IntArray> {
        if (encode == null) return emptyList()
        val variants = if (model == NovelAiModel.ERATO.id) {
            val punctuation = listOf(".", "!", "?", "*", "\"", "_", "...", ".\"", "?\"", "!\"", ".*", ")")
            stops.filter { it.startsWith('\n') }.flatMap { stop -> punctuation.map { it + stop } }
        } else emptyList()
        return (stops + variants).filter(String::isNotBlank).distinct().take(1024).map(encode)
    }

    fun promptText(model: String, text: String, preamble: String = ""): String = buildString {
        if (model == NovelAiModel.ERATO.id && !text.startsWith(ERATO_PREAMBLE)) append(ERATO_PREAMBLE)
        if (preamble.isNotBlank()) append(preamble).append('\n')
        append(text)
    }

    fun instruction(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.startsWith('{') && trimmed.endsWith('}')) return trimmed
        return "{ $trimmed }"
    }

    fun context(provider: Provider, model: NovelAiModel): Int {
        val maximum = when (model) {
            NovelAiModel.KAYRA -> if (provider.novelAi.tier == 1) 4096 else 8192
            NovelAiModel.ERATO -> 8182
            NovelAiModel.CLIO -> 8192
        }
        return minOf(maximum, provider.novelAi.preset(model).context ?: maximum)
    }

    fun output(provider: Provider, id: String, configured: Int?): Int {
        val model = NovelAiModel.from(id) ?: return 150
        val max = if (model != NovelAiModel.CLIO && provider.novelAi.tier == 3) 250 else 150
        val preset = JSONObject(provider.novelAi.preset(model).parameters).optInt("max_length", 150)
        return (configured ?: preset).coerceIn(1, max)
    }

    private fun headers(key: String) = mapOf("Authorization" to "Bearer $key")
    private fun root(base: String) = base.removeSuffix("/ai/generate-stream").removeSuffix("/ai/generate").removeSuffix("/ai")
    private fun isOfficial(base: String) = base == NOVEL_AI_ENDPOINT || base == NOVEL_AI_LEGACY_ENDPOINT ||
        base == NOVEL_AI_ACCOUNT_ENDPOINT

    fun speechRoot(provider: Provider): String {
        val base = root(ProviderProtocols.baseAddress(provider).removeSuffix("/ai/generate-voice"))
        return if (isOfficial(base)) NOVEL_AI_LEGACY_ENDPOINT else base
    }

    suspend fun discover(provider: Provider, base: String): DiscoveredModels {
        val key = provider.apiKey.trim()
        require(key.isNotEmpty()) { "请填写 NovelAI API Key" }
        val root = root(base)
        val official = isOfficial(root)
        val account = if (official) NOVEL_AI_ACCOUNT_ENDPOINT else root
        val tier = request("$account/user/subscription", headers = headers(key), redactError = { it.redactValue(key) }) { input, _ ->
            val result = JSONObject(input.readLimited().decodeToString())
            check(result.has("tier") && result.optInt("tier", -1) in 0..3) { "NovelAI 未返回有效的订阅等级" }
            result.getInt("tier")
        }
        // Clio remains selectable for compatible servers. NovelAI retired its hosted Clio model.
        val models = NovelAiModel.entries.filter { !official || it != NovelAiModel.CLIO }
        val checked = provider.copy(novelAi = provider.novelAi.copy(tier = tier, presets = emptyMap()))
        return DiscoveredModels(models.map(NovelAiModel::id), models.associate { it.id to context(checked, it) }, tier)
    }

    internal fun buildPayload(
        provider: Provider, payload: JSONObject, prepared: TextCompletionPrompt?, stopTokens: List<IntArray> = emptyList(),
    ): JSONObject {
        val id = payload.string("model")
        val model = requireNotNull(NovelAiModel.from(id)) { "NovelAI 文本补全的模型 ID 无效。请读取模型列表。" }
        val text = prepared?.text ?: promptText(id, payload.optJSONArray("messages")?.objects().orEmpty().joinToString("\n") { row ->
            val body = row.promptText()
            if (row.string("role") == "assistant") body else instruction(body)
        })
        val values = JSONObject(NovelAiDefaults.presets.getValue(model).parameters)
        val configured = JSONObject(provider.novelAi.preset(model).parameters)
        configured.keys().forEach { values.put(it, configured.get(it)) }
        listOf("temperature", "top_p").forEach { key -> if (payload.has(key)) values.put(key, payload.get(key)) }
        val max = output(provider, id, payload.optInt("max_tokens").takeIf { it > 0 })
        values.put("max_length", max).put("min_length", values.optInt("min_length", 1).coerceIn(0, max))
        values.put("use_string", true).put("return_full_text", false)
        if (!values.has("use_cache")) values.put("use_cache", false)
        if (!values.has("generate_until_sentence")) values.put("generate_until_sentence", true)
        val prefix = if (text.takeLast(1500).contains('}')) "special_instruct" else values.string("prefix")
        values.put("prefix", prefix)
        if (prefix == "theme_textadventure") values.put("eos_token_id", if (model == NovelAiModel.ERATO) 29 else 49405)
        val defaults = NovelAiDefaults.tokenRules(model)
        fun addRules(key: String, extras: List<Any>) {
            val existing = values.optJSONArray(key)
            val original = defaults.optJSONArray(key)
            val rules = JSONArray()
            val seen = hashSetOf<String>()
            fun append(value: Any?) {
                if (value != null && seen.add(value.toString()) && rules.length() < 1024) rules.put(value)
            }
            if (original != null) for (i in 0 until original.length()) append(original.opt(i))
            if (existing != null) for (i in 0 until existing.length()) append(existing.opt(i))
            extras.forEach(::append)
            if (rules.length() != 0) values.put(key, rules)
        }
        addRules("bad_words_ids", emptyList())
        addRules("logit_bias_exp", emptyList())
        addRules("stop_sequences", stopTokens.map { JSONArray(it.toList()) })
        if (!values.has("repetition_penalty_whitelist")) {
            values.put("repetition_penalty_whitelist", defaults.getJSONArray("repetition_penalty_whitelist"))
        }
        return jsonOf("input" to text, "model" to id, "parameters" to values)
    }

    suspend fun complete(
        provider: Provider, base: String, payload: JSONObject, textPrompt: TextCompletionPrompt?,
        customStops: List<String>, token: (String) -> Unit, onRetry: () -> Unit,
    ): CompletionAccumulator {
        val model = payload.string("model")
        val root = root(base).let {
            if (!isOfficial(it)) it else if (model == NovelAiModel.CLIO.id) NOVEL_AI_LEGACY_ENDPOINT else NOVEL_AI_ENDPOINT
        }
        val key = provider.apiKey.trim()
        require(key.isNotBlank()) { "请填写 NovelAI API Key" }
        val stops = (customStops + textPrompt?.stops.orEmpty()).distinct()
        val body = buildPayload(provider, payload, textPrompt, textPrompt?.stopTokens.orEmpty())
        if (payload.optBoolean("stream")) {
            try {
                val streamed = readReply("$root/ai/generate-stream", key, body, stops, token)
                if (streamed.hasReceivedText || streamed.reachedTextStop ||
                    streamed.finishReason.isNotBlank() || streamed.interruption != null) return streamed
            } catch (e: HttpRequestError) {
                if (e.status !in setOf(404, 405)) throw e
            }
            onRetry()
        }
        return readReply("$root/ai/generate", key, body, stops, token)
    }

    private suspend fun readReply(
        url: String, key: String, body: JSONObject, stops: List<String>, token: (String) -> Unit,
    ): CompletionAccumulator {
        val result = CompletionAccumulator(stops)
        var expectsFinalEvent = false
        fun json(frame: JSONObject, streaming: Boolean): Boolean {
            val error = frame.optJSONObject("error")?.string("message").orEmpty().ifBlank { frame.string("error") }
            check(error.isBlank()) { error.redactValue(key).take(250) }
            if (streaming && frame.has("ptr")) expectsFinalEvent = true
            result.appendText((frame.opt(if (streaming) "token" else "output") as? String).orEmpty(), token)
            val finish = frame.string("finish_reason")
            if (finish.isNotBlank()) result.finishReason = finish
            if (!streaming || frame.optBoolean("final")) {
                if (result.finishReason.isBlank()) result.finishReason = "stop"
                return true
            }
            return finish.isNotBlank() || result.reachedTextStop
        }
        try {
            request(url, body, headers(key), redactError = { it.redactValue(key) }) { input, type ->
                if (!type.contains("text/event-stream", ignoreCase = true)) {
                    json(JSONObject(input.readLimited().decodeToString()), streaming = false)
                    return@request
                }
                val reader = input.bufferedReader(Charsets.UTF_8)
                val data = StringBuilder()
                fun dispatch(): Boolean {
                    val raw = data.toString()
                    data.clear()
                    if (raw.isBlank()) return false
                    if (raw.trim() == "[DONE]") {
                        result.finishReason = "stop"
                        return true
                    }
                    return json(JSONObject(raw), streaming = true)
                }
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty() && dispatch()) return@request
                    if (line.startsWith("data:")) {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.removePrefix("data:").removePrefix(" "))
                        check(data.length <= MAX_STREAM_EVENT_CHARS) { "NovelAI 流式消息超出大小限制" }
                    }
                }
                val finished = dispatch()
                if (!finished && expectsFinalEvent) result.interruption = NOVEL_AI_INTERRUPTED
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!result.hasReceivedText || e !is IOException && e !is JSONException && e !is IllegalStateException) throw e
            result.interruption = if (e is IllegalStateException) e.message ?: NOVEL_AI_INTERRUPTED else NOVEL_AI_INTERRUPTED
        }
        return result.also { it.finishText(token) }
    }
}

private const val MAX_STREAM_EVENT_CHARS = 1024 * 1024
