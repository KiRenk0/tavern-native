package app.tavern.nativeclient

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

private const val ANTHROPIC_VERSION = "2023-06-01"
private const val ANTHROPIC_CACHE_BETA = "prompt-caching-2024-07-31"
private const val GEMINI_CACHE_MIN_CHARS = 8_000
private const val PROTOCOL_STREAM_INTERRUPTED = "模型连接在生成完成前中断"

private data class GeminiCacheEntry(val name: String, val expiresAtMs: Long)

internal object ProviderProtocols {
    private val geminiContextCache = ConcurrentHashMap<String, GeminiCacheEntry>()

    internal fun clearCachesForTest() = geminiContextCache.clear()

    fun baseAddress(provider: Provider): String =
        checkedAddress(provider.endpoint).toString().trimEnd('/')
            .removeSuffix("/chat/completions")
            .removeSuffix("/messages")

    suspend fun discoverModels(provider: Provider): DiscoveredModels {
        val base = baseAddress(provider)
        return when (provider.protocol) {
            ApiProtocol.ANTHROPIC -> discoverAnthropicModels(base, provider.apiKey.trim())
            ApiProtocol.GEMINI_NATIVE -> discoverGeminiNativeModels(base, provider.apiKey.trim())
            ApiProtocol.OLLAMA -> discoverOllamaModels(base)
            ApiProtocol.KOBOLD_TEXT -> discoverKoboldModels(base, provider.apiKey.trim())
            ApiProtocol.NOVELAI_TEXT -> NovelAi.discover(provider, base)
            ApiProtocol.HORDE_TEXT -> Horde(provider).textModels()
            ApiProtocol.OPENAI_COMPATIBLE, ApiProtocol.OPENROUTER -> {
                if (provider.template == ProviderTemplate.GOOGLE &&
                    base == "https://generativelanguage.googleapis.com/v1beta/openai"
                ) {
                    try {
                        val models = discoverGeminiNativeModels(base.removeSuffix("/openai"), provider.apiKey.trim())
                        if (models.ids.isNotEmpty()) return models
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Fall back to OpenAI compatibility model list.
                    }
                }
                val overrides = provider.compiledRequestOptions()
                val headers = overrides.headersWith(buildOpenAiHeaders(provider))
                request("$base/models", headers = headers, redactError = {
                    overrides.redact(it.redactValue(provider.apiKey.trim()))
                }) { input, _ ->
                    DiscoveredModels.read(
                        JSONObject(input.readLimited().decodeToString()),
                        provider.template == ProviderTemplate.GOOGLE,
                    )
                }
            }
        }
    }

    private suspend fun discoverAnthropicModels(base: String, key: String): DiscoveredModels {
        val root = base.removeSuffix("/v1")
        val headers = mapOf(
            "x-api-key" to key,
            "anthropic-version" to ANTHROPIC_VERSION,
        )
        return request("$root/v1/models", headers = headers, readTimeout = 15_000) { input, _ ->
            val rows = JSONObject(input.readLimited().decodeToString()).optJSONArray("data")?.objects().orEmpty()
            val ids = linkedSetOf<String>()
            val contexts = linkedMapOf<String, Int>()
            for (row in rows) {
                val id = row.string("id").trim()
                if (id.isBlank()) continue
                ids += id
                val maxTokens = row.optInt("max_tokens")
                if (maxTokens in 1..MAX_CONTEXT_TOKENS) contexts[id] = maxTokens
            }
            DiscoveredModels(ids.sorted(), contexts)
        }
    }

    internal suspend fun discoverGeminiNativeModels(endpoint: String, key: String): DiscoveredModels {
        val root = geminiApiRoot(endpoint)
        var page = ""
        val seen = hashSetOf<String>()
        val ids = linkedSetOf<String>()
        val contexts = linkedMapOf<String, Int>()
        repeat(16) {
            val next = page.takeIf(String::isNotBlank)?.let {
                "&pageToken=" + URLEncoder.encode(it, "UTF-8")
            }.orEmpty()
            val result = request(
                "$root/models?pageSize=100$next",
                headers = mapOf("x-goog-api-key" to key),
                readTimeout = 12_000,
            ) { input, _ -> JSONObject(input.readLimited().decodeToString()) }
            val discovered = DiscoveredModels.read(result)
            ids += discovered.ids
            contexts += discovered.contexts
            page = result.string("nextPageToken")
            if (page.isBlank() || !seen.add(page)) return DiscoveredModels(ids.sorted(), contexts)
        }
        return DiscoveredModels(ids.sorted(), contexts)
    }

    private suspend fun discoverOllamaModels(base: String): DiscoveredModels {
        val root = base.removeSuffix("/v1").removeSuffix("/api")
        return request("$root/api/tags", readTimeout = 12_000) { input, _ ->
            val rows = JSONObject(input.readLimited().decodeToString()).optJSONArray("models")?.objects().orEmpty()
            val ids = rows.mapNotNull { it.string("name").trim().takeIf(String::isNotBlank) }.distinct().sorted()
            DiscoveredModels(ids, emptyMap())
        }
    }

    private suspend fun discoverKoboldModels(base: String, key: String): DiscoveredModels {
        val headers = if (key.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $key")
        val root = textApiRoot(base)
        return try {
            request("$root/api/v1/model", headers = headers, readTimeout = 10_000) { input, _ ->
                val result = JSONObject(input.readLimited().decodeToString()).string("result").trim()
                val ids = if (result.isNotBlank()) listOf(result) else emptyList()
                DiscoveredModels(ids, emptyMap())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            request("$root/v1/models", headers = headers, readTimeout = 10_000) { input, _ ->
                DiscoveredModels.read(JSONObject(input.readLimited().decodeToString()))
            }
        }
    }

    private fun textApiRoot(base: String): String =
        base.removeSuffix("/api/v1/generate").removeSuffix("/api/v1").removeSuffix("/completions").removeSuffix("/v1")

    private fun compatibleTextEndpoint(base: String) = base.endsWith("/v1") && !base.endsWith("/api/v1")

    fun defaultTextBiasTokenizer(provider: Provider): BiasTokenizer =
        if (compatibleTextEndpoint(baseAddress(provider).removeSuffix("/completions"))) BiasTokenizer.LLAMA_CPP
        else BiasTokenizer.KOBOLD_CPP

    suspend fun tokenizeBias(connection: ModelConnection, tokenizer: BiasTokenizer, text: String): IntArray {
        val json = requestTextTokens(connection, tokenizer, text, includePieces = false, responseLimit = 256 * 1024)
        return tokenIds(textTokenValues(json, tokenizer))
    }

    private fun textTokenValues(json: JSONObject, tokenizer: BiasTokenizer): JSONArray =
        requireNotNull(json.optJSONArray(if (tokenizer == BiasTokenizer.KOBOLD_CPP) "ids" else "tokens")) {
            "服务端未返回 Token ID"
        }

    private suspend fun requestTextTokens(
        connection: ModelConnection, tokenizer: BiasTokenizer, text: String, includePieces: Boolean, responseLimit: Int,
    ): JSONObject {
        val provider = connection.provider
        val root = textApiRoot(baseAddress(provider))
        val kobold = tokenizer == BiasTokenizer.KOBOLD_CPP
        val route = if (kobold) "/api/extra/tokencount" else "/tokenize"
        val body = if (kobold) jsonOf("prompt" to text, "special" to false, "model" to connection.model)
            else jsonOf("content" to text, "add_special" to false, "parse_special" to false,
                "model" to connection.model).apply { if (includePieces) put("with_pieces", true) }
        return request("$root$route", body, buildOpenAiHeaders(provider), readTimeout = 10_000,
            redactError = { it.redactValue(provider.apiKey.trim()) }) { input, _ ->
            JSONObject(input.readLimited(responseLimit).decodeToString())
        }
    }

    suspend fun inspectRemoteTokens(connection: ModelConnection, text: String): TokenPreview {
        require(connection.supportsRemoteTokenCount) { "当前连接没有可用的服务端分词接口" }
        val provider = connection.provider
        if (provider.protocol != ApiProtocol.KOBOLD_TEXT) {
            val root = geminiApiRoot(baseAddress(provider))
            val id = URLEncoder.encode(connection.model.removePrefix("models/"), "UTF-8")
            val body = jsonOf("contents" to JSONArray().put(jsonOf(
                "role" to "user", "parts" to JSONArray().put(jsonOf("text" to text)),
            )))
            return request("$root/models/$id:countTokens", body, mapOf("x-goog-api-key" to provider.apiKey.trim()),
                readTimeout = 15_000, redactError = { it.redactValue(provider.apiKey.trim()) }) { input, _ ->
                val response = JSONObject(input.readLimited(256 * 1024).decodeToString())
                val count = response.optInt("totalTokens", -1)
                require(count >= 0) { "Gemini 未返回 Token 数量" }
                TokenPreview("Gemini 服务端计数", count)
            }
        }
        var tokenizer = defaultTextBiasTokenizer(provider)
        val responseLimit = 8 * 1024 * 1024
        val json = try {
            requestTextTokens(connection, tokenizer, text, includePieces = true, responseLimit)
        } catch (e: HttpRequestError) {
            if (tokenizer != BiasTokenizer.LLAMA_CPP || e.status !in setOf(404, 405)) throw e
            tokenizer = BiasTokenizer.KOBOLD_CPP
            requestTextTokens(connection, tokenizer, text, includePieces = false, responseLimit)
        }
        val kobold = tokenizer == BiasTokenizer.KOBOLD_CPP
        val label = if (kobold) "KoboldCpp 服务端分词" else "llama.cpp 服务端分词"
        if (kobold && json.optJSONArray("ids") == null) {
            val count = json.optInt("value", -1)
            require(count >= 0) { "KoboldCpp 未返回 Token 数量" }
            return TokenPreview(label, count)
        }
        val tokens = textTokenValues(json, tokenizer)
        val pieces = List(minOf(TOKEN_PREVIEW_PIECES, tokens.length())) { index ->
            val row = tokens.optJSONObject(index)
            if (row == null) {
                TokenPiece(tokens.getInt(index))
            } else {
                val textPiece = when (val piece = row.opt("piece")) {
                    is String -> piece
                    is JSONArray -> tokenPieceText(ByteArray(piece.length()) { piece.getInt(it).toByte() })
                    else -> null
                }
                TokenPiece(row.getInt("id"), textPiece)
            }
        }
        return TokenPreview(label, tokens.length(), pieces)
    }

    suspend fun modelProperties(connection: ModelConnection, catalog: ModelFormatCatalog): BackendModelFormatting {
        val provider = connection.provider
        require(provider.protocol == ApiProtocol.KOBOLD_TEXT) { "此协议不提供 /props 模型属性" }
        val base = baseAddress(provider).removeSuffix("/completions")
        val query = if (compatibleTextEndpoint(base)) "?model=${URLEncoder.encode(connection.model, "UTF-8")}" else ""
        return request("${textApiRoot(base)}/props$query", headers = buildOpenAiHeaders(provider), readTimeout = 3_500) { input, _ ->
            catalog.fromProperties(JSONObject(input.readLimited(1024 * 1024).decodeToString()))
        }
    }

    fun buildOpenAiHeaders(provider: Provider): Map<String, String> = buildMap {
        val key = provider.apiKey.trim()
        if (key.isNotBlank()) put("Authorization", "Bearer $key")
        if (provider.protocol == ApiProtocol.OPENROUTER) {
            put("HTTP-Referer", "https://github.com/TavernNative/TavernNative")
            put("X-Title", "Tavern Native")
        }
    }

    fun enrichOpenAiPayload(provider: Provider, payload: JSONObject) {
        if (provider.protocol != ApiProtocol.OPENROUTER) return
        if (provider.openRouterProviders.isNotEmpty() || !provider.openRouterAllowFallbacks) {
            val routing = JSONObject().put("allow_fallbacks", provider.openRouterAllowFallbacks)
            if (provider.openRouterProviders.isNotEmpty()) {
                routing.put("order", JSONArray(provider.openRouterProviders))
            }
            payload.put("provider", routing)
        }
        val reasoning = JSONObject()
        if (provider.openRouterReasoningEffort.isNotBlank()) {
            reasoning.put("effort", provider.openRouterReasoningEffort)
        }
        provider.thinkingBudget?.takeIf { it > 0 }?.let { reasoning.put("max_tokens", it) }
        if (reasoning.length() > 0) payload.put("reasoning", reasoning)
    }

    suspend fun completeProtocol(
        provider: Provider,
        payload: JSONObject,
        onReasoning: (String) -> Unit,
        token: (String) -> Unit,
        acceptTools: Boolean,
        onRetry: () -> Unit,
        textPrompt: TextCompletionPrompt? = null,
        customStops: List<String> = emptyList(),
        sendStops: Boolean = true,
        onQueueProgress: (String) -> Unit = {},
    ): CompletionAccumulator = when (provider.protocol) {
        ApiProtocol.ANTHROPIC -> completeAnthropic(provider, payload, onReasoning, token, acceptTools, customStops)
        ApiProtocol.GEMINI_NATIVE -> completeGeminiNative(provider, payload, onReasoning, token, acceptTools, onRetry, customStops)
        ApiProtocol.OLLAMA -> completeOllama(provider, payload, onReasoning, token, textPrompt, customStops, sendStops)
        ApiProtocol.KOBOLD_TEXT -> completeKobold(provider, payload, token, textPrompt, customStops, sendStops)
        ApiProtocol.NOVELAI_TEXT -> NovelAi.complete(provider, baseAddress(provider), payload, textPrompt, customStops, token, onRetry)
        ApiProtocol.HORDE_TEXT -> try {
            Horde(provider).complete(payload, textPrompt, customStops, sendStops, onQueueProgress, token)
        } finally {
            onQueueProgress("")
        }
        ApiProtocol.OPENAI_COMPATIBLE, ApiProtocol.OPENROUTER -> error("OpenAI 兼容协议由主路径处理")
    }

    // --- Anthropic Claude Native (/v1/messages) ---

    internal fun buildAnthropicPayload(provider: Provider, payload: JSONObject, streaming: Boolean): JSONObject {
        val openAiMessages = payload.optJSONArray("messages")?.objects().orEmpty()
        val systemTexts = mutableListOf<String>()
        val converted = mutableListOf<Pair<String, MutableList<JSONObject>>>()

        fun appendMessage(role: String, blocks: List<JSONObject>) {
            if (blocks.isEmpty()) return
            val last = converted.lastOrNull()
            if (last != null && last.first == role) {
                last.second.addAll(blocks)
            } else {
                converted += role to blocks.toMutableList()
            }
        }

        for (msg in openAiMessages) {
            val role = msg.string("role").lowercase()
            if (role == "system") {
                val text = msg.promptText()
                if (text.isNotBlank()) systemTexts += text
                continue
            }
            if (role == "tool") {
                val toolCallId = msg.string("tool_call_id").ifBlank { "tool_call" }
                val text = msg.promptText().ifBlank { "{}" }
                appendMessage("user", listOf(jsonOf(
                    "type" to "tool_result",
                    "tool_use_id" to toolCallId,
                    "content" to text,
                )))
                continue
            }
            val targetRole = if (role == "assistant") "assistant" else "user"
            val replay = msg.optJSONArray(ANTHROPIC_WEB_REPLAY)
            if (targetRole == "assistant" && replay != null && payload.optBoolean(ANTHROPIC_WEB_SEARCH)) {
                appendMessage("assistant", replay.objects())
                continue
            }
            val blocks = mutableListOf<JSONObject>()
            when (val content = msg.opt("content")) {
                is String -> if (content.isNotEmpty()) blocks += jsonOf("type" to "text", "text" to content)
                is JSONArray -> {
                    for (part in content.objects()) {
                        when (part.string("type")) {
                            "text" -> {
                                val t = part.string("text")
                                if (t.isNotEmpty()) blocks += jsonOf("type" to "text", "text" to t)
                            }
                            "image_url" -> {
                                val dataUrl = part.optJSONObject("image_url")?.string("url").orEmpty()
                                parseDataUrl(dataUrl)?.let { (mime, b64) ->
                                    blocks += jsonOf(
                                        "type" to "image",
                                        "source" to jsonOf("type" to "base64", "media_type" to mime, "data" to b64),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (targetRole == "assistant") {
                msg.optJSONArray("tool_calls")?.objects()?.forEach { call ->
                    val fn = call.optJSONObject("function") ?: return@forEach
                    val argsObj = runCatching { JSONObject(fn.string("arguments").ifBlank { "{}" }) }
                        .getOrElse { JSONObject() }
                    blocks += jsonOf(
                        "type" to "tool_use",
                        "id" to call.string("id").ifBlank { "call_${newId()}" },
                        "name" to fn.string("name"),
                        "input" to argsObj,
                    )
                }
            }
            appendMessage(targetRole, blocks)
        }

        if (converted.isEmpty()) {
            converted += "user" to mutableListOf(jsonOf("type" to "text", "text" to "Continue."))
        } else if (converted.first().first == "assistant" && converted.size > 1) {
            converted.add(0, "user" to mutableListOf(jsonOf("type" to "text", "text" to "...")))
        } else if (converted.first().first == "assistant" && converted.size == 1) {
            converted.add(0, "user" to mutableListOf(jsonOf("type" to "text", "text" to "Continue.")))
        }

        val systemBlocks = systemTexts.map { jsonOf("type" to "text", "text" to it) }
        if (provider.promptCaching) {
            systemBlocks.lastOrNull()?.put("cache_control", jsonOf("type" to "ephemeral"))
            if (converted.size >= 2) {
                converted[converted.size - 2].second.lastOrNull()?.put("cache_control", jsonOf("type" to "ephemeral"))
            }
        }

        val outMessages = JSONArray(converted.map { (role, blocks) ->
            jsonOf("role" to role, "content" to JSONArray(blocks))
        })

        val thinkingBudget = provider.thinkingBudget?.takeIf { it > 0 }
        val body = JSONObject()
            .put("model", payload.string("model"))
            .put("messages", outMessages)
            .put("stream", streaming)

        if (systemBlocks.isNotEmpty()) body.put("system", JSONArray(systemBlocks))
        payload.optJSONArray("stop")?.takeIf { it.length() > 0 }?.let { body.put("stop_sequences", it) }
        if (thinkingBudget != null) {
            val budget = thinkingBudget.coerceAtLeast(1024)
            body.put("thinking", jsonOf("type" to "enabled", "budget_tokens" to budget))
        } else {
            if (payload.has("temperature")) body.put("temperature", payload.optDouble("temperature"))
            if (payload.has("top_p")) body.put("top_p", payload.optDouble("top_p"))
        }
        body.put("max_tokens", anthropicMaxOutput(payload.optInt("max_tokens", 0), thinkingBudget))

        payload.optJSONArray("tools")?.objects()?.takeIf { it.isNotEmpty() }?.let { tools ->
            val mapped = tools.mapNotNull { tool ->
                val fn = tool.optJSONObject("function") ?: return@mapNotNull null
                jsonOf(
                    "name" to fn.string("name"),
                    "description" to fn.string("description"),
                    "input_schema" to (fn.optJSONObject("parameters") ?: jsonOf("type" to "object", "properties" to JSONObject())),
                )
            }
            if (mapped.isNotEmpty()) body.put("tools", JSONArray(mapped))
        }
        if (payload.optBoolean(ANTHROPIC_WEB_SEARCH)) {
            val tools = body.optJSONArray("tools") ?: JSONArray().also { body.put("tools", it) }
            tools.put(jsonOf("type" to "web_search_20250305", "name" to "web_search"))
        }
        return body
    }

    private suspend fun completeAnthropic(
        provider: Provider,
        payload: JSONObject,
        onReasoning: (String) -> Unit,
        token: (String) -> Unit,
        acceptTools: Boolean,
        customStops: List<String>,
    ): CompletionAccumulator {
        val root = baseAddress(provider).removeSuffix("/v1")
        val streaming = payload.optBoolean("stream", true)
        val body = buildAnthropicPayload(provider, payload, streaming)
        val headers = buildMap {
            put("x-api-key", provider.apiKey.trim())
            put("anthropic-version", ANTHROPIC_VERSION)
            if (provider.promptCaching) put("anthropic-beta", ANTHROPIC_CACHE_BETA)
        }
        val result = CompletionAccumulator(customStops)
        val webTranscript = AnthropicWebTranscript().takeIf { payload.optBoolean(ANTHROPIC_WEB_SEARCH) }
        var streamOpened = false
        var streamConsumed = false
        try {
            request("$root/v1/messages", body, headers) { input, type ->
                if (!type.contains("text/event-stream", ignoreCase = true)) {
                    val json = JSONObject(input.readLimited().toString(Charsets.UTF_8))
                    parseAnthropicJson(json, result, acceptTools, provider.apiKey, onReasoning, token)
                    return@request
                }
                streamOpened = true
                val reader = input.bufferedReader(Charsets.UTF_8)
                val dataLines = mutableListOf<String>()
                var eventType = ""

                fun handleEvent(): Boolean {
                    val raw = dataLines.joinToString("\n").trim()
                    dataLines.clear()
                    val currentEvent = eventType
                    eventType = ""
                    if (raw.isEmpty()) return false
                    if (raw == "[DONE]") return true
                    val json = try {
                        JSONObject(raw)
                    } catch (_: JSONException) {
                        val msg = "模型返回的流式分片格式无效"
                        if (!result.hasReceivedText) error(msg)
                        result.interruption = msg
                        return true
                    }
                    val kind = json.string("type").ifBlank { currentEvent }
                    when (kind) {
                        "message_start" -> {
                            val usage = json.optJSONObject("message")?.optJSONObject("usage")
                            result.usage = result.usage.update(ResponseTokens.anthropic(usage))
                        }
                        "error" -> {
                            val detail = json.optJSONObject("error")?.string("message")
                                ?.redactValue(provider.apiKey.trim()).orEmpty().ifBlank { "Anthropic 请求出错" }
                            if (!result.hasReceivedText) error(detail)
                            result.interruption = detail
                            return true
                        }
                        "content_block_start" -> {
                            val idx = json.optInt("index", 0)
                            val block = json.optJSONObject("content_block")
                            if (block != null) {
                                webTranscript?.start(idx, block)
                                result.appendCitations(block.optJSONArray("citations"))
                            }
                            if (acceptTools && block?.string("type") == "tool_use") {
                                result.startToolCall(
                                    idx,
                                    block.string("id").ifBlank { "call_${newId()}" },
                                    block.string("name"),
                                )
                            }
                        }
                        "content_block_delta" -> {
                            val idx = json.optInt("index", 0)
                            val delta = json.optJSONObject("delta") ?: return false
                            webTranscript?.delta(idx, delta)
                            when (delta.string("type")) {
                                "text_delta" -> result.appendText(delta.string("text"), token)
                                "thinking_delta" -> result.appendReasoning(delta.string("thinking"), onReasoning, explicit = true)
                                "input_json_delta" -> if (acceptTools) result.appendToolArgs(idx, delta.string("partial_json"))
                                "citations_delta" -> delta.optJSONObject("citation")?.let(result::appendCitation)
                            }
                        }
                        "message_delta" -> {
                            val usage = ResponseTokens.anthropic(json.optJSONObject("usage"))
                            result.usage = result.usage.update(usage.copy(input = result.usage.input ?: usage.input))
                            val stop = json.optJSONObject("delta")?.string("stop_reason").orEmpty()
                            if (stop.isNotBlank()) result.finishReason = mapAnthropicStopReason(stop)
                        }
                        "message_stop" -> return true
                    }
                    return result.reachedTextStop || result.interruption != null
                }

                while (true) {
                    val line = reader.readLine() ?: break
                    when {
                        line.isBlank() -> if (handleEvent()) {
                            streamConsumed = true
                            return@request
                        }
                        line.startsWith("event:") -> eventType = line.removePrefix("event:").trim()
                        line.startsWith("data:") -> dataLines += line.removePrefix("data:").removePrefix(" ")
                    }
                }
                if (dataLines.isNotEmpty()) handleEvent()
                if (result.finishReason.isBlank() && result.interruption == null && !result.reachedTextStop &&
                    (!result.hasOutput || customStops.isNotEmpty())) {
                    result.interruption = PROTOCOL_STREAM_INTERRUPTED
                }
                streamConsumed = true
            }
        } catch (e: IOException) {
            if (!streamOpened) throw e
            if (!streamConsumed) result.interruption = PROTOCOL_STREAM_INTERRUPTED
        }
        if (streamOpened && result.interruption == null && !result.reachedTextStop) {
            result.captureWebTranscript(webTranscript?.replay())
        }
        return result.also { it.finishText(token) }
    }

    private fun parseAnthropicJson(
        json: JSONObject,
        result: CompletionAccumulator,
        acceptTools: Boolean,
        apiKey: String,
        onReasoning: (String) -> Unit,
        token: (String) -> Unit,
    ) {
        json.optJSONObject("error")?.string("message")?.takeIf(String::isNotBlank)?.let {
            error(it.redactValue(apiKey.trim()))
        }
        result.usage = result.usage.update(ResponseTokens.anthropic(json.optJSONObject("usage")))
        val content = json.optJSONArray("content")?.objects().orEmpty()
        for (block in content) {
            when (block.string("type")) {
                "thinking" -> result.appendReasoning(block.string("thinking"), onReasoning, explicit = true)
                "text" -> {
                    result.appendText(block.string("text"), token)
                    result.appendCitations(block.optJSONArray("citations"))
                }
                "tool_use" -> if (acceptTools) {
                    result.appendDirectToolCall(
                        block.string("id").ifBlank { "call_${newId()}" },
                        block.string("name"),
                        block.optJSONObject("input")?.toString() ?: "{}",
                    )
                }
            }
        }
        if (content.any { it.string("type") == "web_search_tool_result" } && content.none { it.string("type") == "tool_use" }) {
            result.captureWebTranscript(JSONArray(content))
        }
        val stop = json.string("stop_reason")
        if (stop.isNotBlank()) result.finishReason = mapAnthropicStopReason(stop)
    }

    private fun mapAnthropicStopReason(reason: String): String = when (reason.lowercase()) {
        "end_turn", "stop_sequence" -> "stop"
        "max_tokens" -> "length"
        "tool_use" -> "tool_calls"
        "refusal" -> "content_filter"
        "pause_turn" -> "continuation"
        else -> reason.lowercase()
    }

    // --- Google Gemini Native (/v1beta/models/{model}:generateContent / :streamGenerateContent) ---

    internal fun geminiApiRoot(endpoint: String): String {
        val clean = endpoint.trimEnd('/').removeSuffix("/openai")
        return when {
            clean.endsWith("/v1beta") -> clean
            "/v1beta/" in clean -> clean.substringBefore("/v1beta/") + "/v1beta"
            else -> "$clean/v1beta"
        }
    }

    internal fun buildGeminiNativePayload(
        provider: Provider,
        payload: JSONObject,
        cachedContentName: String? = null,
    ): Pair<JSONObject, String> {
        val imageOptions = payload.optJSONObject(REPLY_IMAGE_OPTIONS)
        val openAiMessages = payload.optJSONArray("messages")?.objects().orEmpty()
        val systemTexts = mutableListOf<String>()
        val contents = mutableListOf<Pair<String, MutableList<JSONObject>>>()
        val toolCallNames = mutableMapOf<String, String>()

        fun appendContent(role: String, parts: List<JSONObject>) {
            if (parts.isEmpty()) return
            val last = contents.lastOrNull()
            if (last != null && last.first == role) {
                last.second.addAll(parts)
            } else {
                contents += role to parts.toMutableList()
            }
        }

        for (msg in openAiMessages) {
            val role = msg.string("role").lowercase()
            if (role == "system") {
                val t = msg.promptText()
                if (t.isNotBlank()) systemTexts += t
                continue
            }
            if (role == "tool") {
                val id = msg.string("tool_call_id")
                val name = toolCallNames[id] ?: "tool"
                val raw = msg.promptText().ifBlank { "{}" }
                val responseObj = runCatching { JSONObject(raw) }.getOrElse { jsonOf("result" to raw) }
                appendContent("user", listOf(jsonOf(
                    "functionResponse" to jsonOf("name" to name, "response" to responseObj),
                )))
                continue
            }
            val geminiRole = if (role == "assistant") "model" else "user"
            val replay = msg.optJSONArray(GEMINI_REPLAY_PARTS)
            if (geminiRole == "model" && replay != null && imageOptions != null) {
                appendContent(geminiRole, replay.objects())
                continue
            }
            val parts = mutableListOf<JSONObject>()
            when (val content = msg.opt("content")) {
                is String -> if (content.isNotEmpty()) parts += jsonOf("text" to content)
                is JSONArray -> {
                    for (part in content.objects()) {
                        when (part.string("type")) {
                            "text" -> {
                                val t = part.string("text")
                                if (t.isNotEmpty()) parts += jsonOf("text" to t)
                            }
                            "image_url" -> {
                                val dataUrl = part.optJSONObject("image_url")?.string("url").orEmpty()
                                parseDataUrl(dataUrl)?.let { (mime, b64) ->
                                    parts += jsonOf("inlineData" to jsonOf("mimeType" to mime, "data" to b64))
                                }
                            }
                        }
                    }
                }
            }
            if (geminiRole == "model") {
                msg.optJSONArray("tool_calls")?.objects()?.forEach { call ->
                    val fn = call.optJSONObject("function") ?: return@forEach
                    val name = fn.string("name")
                    val id = call.string("id")
                    if (id.isNotBlank() && name.isNotBlank()) toolCallNames[id] = name
                    val args = runCatching { JSONObject(fn.string("arguments").ifBlank { "{}" }) }.getOrElse { JSONObject() }
                    parts += jsonOf("functionCall" to jsonOf("name" to name, "args" to args))
                }
            }
            if (imageOptions != null && geminiRole == "model" && parts.isNotEmpty()) {
                appendContent("user", listOf(jsonOf("text" to "以下是对话中先前的助手回复，供参考。")) + parts)
            } else appendContent(geminiRole, parts)
        }

        val systemJoined = systemTexts.joinToString("\n\n")
        if (imageOptions != null && systemJoined.isNotBlank()) {
            val instruction = jsonOf("text" to systemJoined)
            val first = contents.firstOrNull()
            if (first?.first == "user") first.second.add(0, instruction)
            else contents.add(0, "user" to mutableListOf(instruction))
        }
        if (contents.isEmpty()) {
            contents += "user" to mutableListOf(jsonOf("text" to "Continue."))
        }

        val body = JSONObject().put(
            "contents",
            JSONArray(contents.map { (r, p) -> jsonOf("role" to r, "parts" to JSONArray(p)) }),
        )

        if (!cachedContentName.isNullOrBlank()) {
            body.put("cachedContent", cachedContentName)
        } else if (imageOptions == null && systemJoined.isNotBlank()) {
            body.put("systemInstruction", jsonOf("parts" to JSONArray().put(jsonOf("text" to systemJoined))))
        }

        if (provider.geminiSafety != GeminiSafetyLevel.DEFAULT) {
            val categories = listOf(
                "HARM_CATEGORY_HARASSMENT",
                "HARM_CATEGORY_HATE_SPEECH",
                "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                "HARM_CATEGORY_DANGEROUS_CONTENT",
                "HARM_CATEGORY_CIVIC_INTEGRITY",
            )
            body.put("safetySettings", JSONArray(categories.map {
                jsonOf("category" to it, "threshold" to provider.geminiSafety.wire)
            }))
        }

        val generationConfig = JSONObject()
        payload.optJSONArray("stop").strings().take(5).takeIf { it.isNotEmpty() }?.let {
            generationConfig.put("stopSequences", JSONArray(it))
        }
        if (payload.has("temperature")) generationConfig.put("temperature", payload.optDouble("temperature"))
        if (payload.has("top_p")) generationConfig.put("topP", payload.optDouble("top_p"))
        if (payload.has("max_tokens")) generationConfig.put("maxOutputTokens", payload.optInt("max_tokens"))
        if (payload.has("seed")) generationConfig.put("seed", payload.optLong("seed"))
        provider.thinkingBudget?.takeIf { imageOptions == null }?.let { budget ->
            generationConfig.put("thinkingConfig", jsonOf("thinkingBudget" to budget))
        }
        if (imageOptions != null) {
            generationConfig.put("responseModalities", JSONArray(listOf("TEXT", "IMAGE")))
            if (imageOptions.length() > 0) generationConfig.put("imageConfig", imageOptions)
        }
        if (generationConfig.length() > 0) body.put("generationConfig", generationConfig)

        if (cachedContentName.isNullOrBlank() && imageOptions == null && payload.optBoolean(GEMINI_GOOGLE_SEARCH)) {
            body.put("tools", JSONArray().put(jsonOf("googleSearch" to JSONObject())))
        } else if (cachedContentName.isNullOrBlank() && imageOptions == null) {
            payload.optJSONArray("tools")?.objects()?.takeIf { it.isNotEmpty() }?.let { tools ->
                val declarations = tools.mapNotNull { tool ->
                    val fn = tool.optJSONObject("function") ?: return@mapNotNull null
                    jsonOf(
                        "name" to fn.string("name"),
                        "description" to fn.string("description"),
                        "parameters" to (fn.optJSONObject("parameters") ?: jsonOf("type" to "object", "properties" to JSONObject())),
                    )
                }
                if (declarations.isNotEmpty()) {
                    body.put("tools", JSONArray().put(jsonOf("functionDeclarations" to JSONArray(declarations))))
                }
            }
        }
        return body to systemJoined
    }

    private suspend fun maybeCreateGeminiCache(
        apiRoot: String,
        modelName: String,
        key: String,
        systemText: String,
    ): String? {
        if (systemText.length < GEMINI_CACHE_MIN_CHARS) return null
        val cacheKey = sha256Hex("$apiRoot\u0000$modelName\u0000$systemText")
        val now = System.currentTimeMillis()
        geminiContextCache[cacheKey]?.takeIf { it.expiresAtMs > now + 60_000L }?.let { return it.name }

        val createBody = jsonOf(
            "model" to "models/$modelName",
            "systemInstruction" to jsonOf("parts" to JSONArray().put(jsonOf("text" to systemText))),
            "ttl" to "3600s",
        )
        return try {
            val response = request(
                "$apiRoot/cachedContents",
                createBody,
                headers = mapOf("x-goog-api-key" to key),
                readTimeout = 15_000,
            ) { input, _ -> JSONObject(input.readLimited().decodeToString()) }
            val name = response.string("name").takeIf(String::isNotBlank) ?: return null
            geminiContextCache[cacheKey] = GeminiCacheEntry(name, now + 3_500_000L)
            name
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun completeGeminiNative(
        provider: Provider,
        payload: JSONObject,
        onReasoning: (String) -> Unit,
        token: (String) -> Unit,
        acceptTools: Boolean,
        onRetry: () -> Unit,
        customStops: List<String>,
    ): CompletionAccumulator {
        val apiRoot = geminiApiRoot(baseAddress(provider))
        val modelName = payload.string("model").removePrefix("models/")
        val key = provider.apiKey.trim()
        val streaming = payload.optBoolean("stream", true)
        val (_, systemText) = buildGeminiNativePayload(provider, payload, cachedContentName = null)
        val canCache = provider.promptCaching && !payload.has("tools") && !payload.has(REPLY_IMAGE_OPTIONS) &&
            !payload.optBoolean(GEMINI_GOOGLE_SEARCH) &&
            systemText.length >= GEMINI_CACHE_MIN_CHARS
        val cachedName = if (canCache) maybeCreateGeminiCache(apiRoot, modelName, key, systemText) else null
        val body = buildGeminiNativePayload(provider, payload, cachedContentName = cachedName).first

        return try {
            executeGeminiRequest(apiRoot, modelName, key, streaming, body, onReasoning, token, acceptTools, customStops)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (cachedName != null) {
                geminiContextCache.entries.removeIf { it.value.name == cachedName }
                onRetry()
                val fallbackBody = buildGeminiNativePayload(provider, payload, cachedContentName = null).first
                executeGeminiRequest(apiRoot, modelName, key, streaming, fallbackBody, onReasoning, token, acceptTools, customStops)
            } else throw e
        }
    }

    private suspend fun executeGeminiRequest(
        apiRoot: String,
        modelName: String,
        key: String,
        streaming: Boolean,
        body: JSONObject,
        onReasoning: (String) -> Unit,
        token: (String) -> Unit,
        acceptTools: Boolean,
        customStops: List<String>,
    ): CompletionAccumulator {
        val url = if (streaming) "$apiRoot/models/$modelName:streamGenerateContent?alt=sse"
        else "$apiRoot/models/$modelName:generateContent"
        val headers = mapOf("x-goog-api-key" to key)
        val imageMode = body.optJSONObject("generationConfig")?.optJSONArray("responseModalities").strings().contains("IMAGE")
        val grounding = body.optJSONArray("tools")?.objects()?.any { it.has("googleSearch") } == true
        val result = CompletionAccumulator(customStops, captureGeminiParts = imageMode,
            geminiGrounding = if (grounding) GeminiGrounding() else null)
        var streamOpened = false
        var streamConsumed = false
        try {
            request(url, body, headers, readTimeout = if (imageMode || grounding) 180_000 else 90_000) { input, type ->
                if (!type.contains("text/event-stream", ignoreCase = true)) {
                    val json = JSONObject(input.readLimited().decodeToString())
                    parseGeminiResponse(json, result, acceptTools, key, onReasoning, token)
                    return@request
                }
                streamOpened = true
                val reader = input.bufferedReader(Charsets.UTF_8)
                val dataLines = mutableListOf<String>()
                fun handle(): Boolean {
                    val event = dataLines.joinToString("\n").trim()
                    dataLines.clear()
                    if (event.isEmpty()) return false
                    if (event == "[DONE]") return true
                    val json = try {
                        JSONObject(event)
                    } catch (_: JSONException) {
                        val message = "模型返回的流式分片格式无效"
                        if (!result.hasReceivedReply) error(message)
                        result.interruption = message
                        return true
                    }
                    parseGeminiResponse(json, result, acceptTools, key, onReasoning, token)
                    return result.reachedTextStop || result.interruption != null
                }
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank() && handle()) {
                        streamConsumed = true
                        return@request
                    }
                    if (line.startsWith("data:")) dataLines += line.removePrefix("data:").removePrefix(" ")
                }
                val terminated = dataLines.isNotEmpty() && handle()
                if (!terminated && result.finishReason.isBlank() && result.interruption == null && !result.reachedTextStop &&
                    (!result.hasOutput || customStops.isNotEmpty() || imageMode || grounding)) {
                    result.interruption = PROTOCOL_STREAM_INTERRUPTED
                }
                streamConsumed = true
            }
        } catch (e: IOException) {
            if (!streamOpened) throw e
            if (!streamConsumed) result.interruption = PROTOCOL_STREAM_INTERRUPTED
        }
        return result.also { it.finishText(token) }
    }

    private fun parseGeminiResponse(
        json: JSONObject,
        result: CompletionAccumulator,
        acceptTools: Boolean,
        apiKey: String,
        onReasoning: (String) -> Unit,
        token: (String) -> Unit,
    ) {
        val errorObj = json.optJSONObject("error")
        if (errorObj != null) {
            val message = errorObj.string("message").redactValue(apiKey).ifBlank { "Gemini 请求失败" }
            if (!result.hasReceivedReply) error(message)
            result.interruption = message
            return
        }
        val blockReason = json.optJSONObject("promptFeedback")?.string("blockReason").orEmpty()
        result.usage = result.usage.update(ResponseTokens.gemini(json))
        if (blockReason.isNotBlank()) {
            result.finishReason = "content_filter"
            return
        }
        val candidate = json.optJSONArray("candidates")?.optJSONObject(0) ?: return
        candidate.optJSONObject("groundingMetadata")?.let { result.geminiGrounding?.accept(it) }
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")?.objects().orEmpty()
        for (part in parts) {
            val signature = part.string("thoughtSignature").ifEmpty { part.string("thought_signature") }
            if (part.has("text")) {
                val text = part.string("text")
                if (part.optBoolean("thought")) {
                    result.appendReasoning(text, onReasoning, explicit = true)
                } else {
                    result.appendGeminiText(text, signature, token)
                }
            }
            if (!part.optBoolean("thought")) {
                val inline = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
                inline?.let { result.appendGeminiImage(it.string("mimeType").ifEmpty { it.string("mime_type") },
                    it.string("data"), signature) }
            }
            if (acceptTools) {
                part.optJSONObject("functionCall")?.let { call ->
                    val name = call.string("name")
                    val args = call.optJSONObject("args")?.toString() ?: "{}"
                    if (name.isNotBlank()) result.appendDirectToolCall("gemini_${newId()}", name, args)
                }
            }
        }
        val reason = candidate.string("finishReason").trim().uppercase()
        if (reason.isNotBlank()) {
            result.finishReason = when (reason) {
                "STOP" -> "stop"
                "MAX_TOKENS" -> "length"
                "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII" -> "content_filter"
                else -> reason.lowercase()
            }
        }
    }

    // --- Ollama Native (/api/chat and /api/generate) ---

    private suspend fun completeOllama(
        provider: Provider,
        payload: JSONObject,
        onReasoning: (String) -> Unit,
        token: (String) -> Unit,
        textPrompt: TextCompletionPrompt?,
        customStops: List<String>,
        sendStops: Boolean,
    ): CompletionAccumulator {
        val root = baseAddress(provider).removeSuffix("/v1").removeSuffix("/api")
        val streaming = payload.optBoolean("stream", true)
        val headers = buildOpenAiHeaders(provider)
        val options = JSONObject()
        if (payload.has("temperature")) options.put("temperature", payload.optDouble("temperature"))
        if (payload.has("top_p")) options.put("top_p", payload.optDouble("top_p"))
        if (payload.has("max_tokens")) options.put("num_predict", payload.optInt("max_tokens"))
        if (payload.has("seed")) options.put("seed", payload.optLong("seed"))
        val sampler = payload.optJSONObject(TEXT_SAMPLER_PAYLOAD)
        applyTextSampler(sampler, options, TextBackend.OLLAMA)
        if (sampler?.has(TextSamplerField.CONTEXT.key) == true) {
            options.put("num_ctx", ModelConnection(provider, payload.string("model")).contextTokens())
        }

        val formatted = if (provider.textCompletion) instructPrompt(provider, payload, textPrompt) else null
        val (url, body) = if (provider.textCompletion) {
            val flattened = formatted ?: flattenMessagesToPrompt(payload.optJSONArray("messages")?.objects().orEmpty())
            val stops = (customStops + flattened.stops).distinct()
            val req = jsonOf(
                "model" to payload.string("model"),
                "prompt" to flattened.text,
                "stream" to streaming,
            )
            if (formatted != null) req.put("raw", true)
            if (options.length() > 0) req.put("options", options)
            if (sendStops && stops.isNotEmpty()) {
                val opts = req.optJSONObject("options") ?: JSONObject().also { req.put("options", it) }
                opts.put("stop", JSONArray(stops))
            }
            "$root/api/generate" to req
        } else {
            val mapped = JSONArray()
            for (msg in payload.optJSONArray("messages")?.objects().orEmpty()) {
                val text = msg.promptText()
                val imgs = extractBase64Images(msg.opt("content"))
                val row = jsonOf("role" to msg.string("role"), "content" to text)
                if (imgs.isNotEmpty()) row.put("images", JSONArray(imgs))
                mapped.put(row)
            }
            val req = jsonOf(
                "model" to payload.string("model"),
                "messages" to mapped,
                "stream" to streaming,
            )
            if (sendStops && customStops.isNotEmpty()) options.put("stop", JSONArray(customStops))
            if (options.length() > 0) req.put("options", options)
            "$root/api/chat" to req
        }
        sampler?.optJSONObject(TextSamplerField.JSON_SCHEMA.key)?.let { body.put("format", it) }

        val result = CompletionAccumulator((customStops + formatted?.stops.orEmpty()).distinct())
        var streamOpened = false
        var streamConsumed = false
        try {
            request(url, body, headers) { input, _ ->
                if (!streaming) {
                    val json = JSONObject(input.readLimited().toString(Charsets.UTF_8))
                    consumeOllamaObject(json, provider.textCompletion, result, onReasoning, token)
                    return@request
                }
                streamOpened = true
                val reader = input.bufferedReader(Charsets.UTF_8)
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val json = try {
                        JSONObject(line)
                    } catch (_: JSONException) {
                        val msg = "Ollama 返回的数据格式无效"
                        if (!result.hasReceivedText) error(msg)
                        result.interruption = msg
                        break
                    }
                    consumeOllamaObject(json, provider.textCompletion, result, onReasoning, token)
                    if (json.optBoolean("done") || result.interruption != null || result.reachedTextStop) break
                }
                if (result.finishReason.isBlank() && !result.reachedTextStop && result.interruption == null) {
                    result.interruption = PROTOCOL_STREAM_INTERRUPTED
                }
                streamConsumed = true
            }
        } catch (e: IOException) {
            if (!streamOpened) throw e
            if (!streamConsumed) result.interruption = PROTOCOL_STREAM_INTERRUPTED
        }
        return result.also { it.finishText(token) }
    }

    private fun consumeOllamaObject(
        json: JSONObject,
        textMode: Boolean,
        result: CompletionAccumulator,
        onReasoning: (String) -> Unit,
        token: (String) -> Unit,
    ) {
        val err = json.string("error")
        if (err.isNotBlank()) {
            if (!result.hasReceivedText) error(err)
            result.interruption = err
            return
        }
        if (textMode) {
            result.appendReasoning(json.string("thinking"), onReasoning, explicit = true)
            result.appendText(json.string("response"), token)
        } else {
            val msg = json.optJSONObject("message")
            if (msg != null) {
                result.appendReasoning(msg.string("thinking"), onReasoning, explicit = true)
                result.appendText(msg.string("content"), token)
            }
        }
        if (json.optBoolean("done")) {
            result.usage = result.usage.update(ResponseTokens.ollama(json))
            result.finishReason = if (json.string("done_reason").equals("length", true)) "length" else "stop"
        }
    }

    private fun instructPrompt(provider: Provider, payload: JSONObject, prepared: TextCompletionPrompt?) =
        prepared ?: ModelConnection(provider, payload.string("model")).resolvedTextTemplates().instruct
            ?.format(payload.optJSONArray("messages") ?: JSONArray(), InstructTurn(quiet = true))

    // --- KoboldCPP / OpenAI Text Completion ---

    private suspend fun completeKobold(
        provider: Provider,
        payload: JSONObject,
        token: (String) -> Unit,
        textPrompt: TextCompletionPrompt?,
        customStops: List<String>,
        sendStops: Boolean,
    ): CompletionAccumulator {
        val base = baseAddress(provider).removeSuffix("/completions")
        val headers = buildOpenAiHeaders(provider)
        val formatted = instructPrompt(provider, payload, textPrompt)
        val flattened = formatted ?: flattenMessagesToPrompt(payload.optJSONArray("messages")?.objects().orEmpty())
        val localStops = (customStops + formatted?.stops.orEmpty()).distinct()
        val wireStops = if (sendStops) (customStops + flattened.stops).distinct() else emptyList()
        val streaming = payload.optBoolean("stream", true)
        val maxLen = payload.optInt("max_tokens", 0).takeIf { it > 0 } ?: 512
        val sampler = payload.optJSONObject(TEXT_SAMPLER_PAYLOAD)

        // OpenAI /v1/completions mode
        if (compatibleTextEndpoint(base)) {
            val req = jsonOf(
                "model" to payload.string("model"),
                "prompt" to flattened.text,
                "max_tokens" to maxLen,
                "stream" to streaming,
            )
            if (payload.has("temperature")) req.put("temperature", payload.optDouble("temperature"))
            if (payload.has("top_p")) req.put("top_p", payload.optDouble("top_p"))
            applyTextSampler(sampler, req, TextBackend.LLAMA_CPP)
            payload.optJSONObject(LOGIT_BIAS)?.let { req.put(LOGIT_BIAS, it) }
            if (payload.has("logprobs")) req.put("logprobs", payload.optInt("logprobs"))
            if (wireStops.isNotEmpty()) req.put("stop", JSONArray(wireStops.take(4)))
            return executeOpenAiTextCompletion("$base/completions", req, headers, token, localStops)
        }

        // Native KoboldCPP (/api/extra/generate/stream or /api/v1/generate)
        val root = textApiRoot(base)
        val koboldBody = jsonOf(
            "prompt" to flattened.text,
            "max_length" to maxLen,
        )
        if (sampler?.has(TextSamplerField.CONTEXT.key) == true ||
            provider.contextLimit != null || provider.modelFormatting.deriveContextSize &&
            provider.modelFormatting.backend[payload.string("model")]?.contextTokens != null) {
            koboldBody.put("max_context_length",
                ModelConnection(provider, payload.string("model")).contextTokens(includeTextSampler = sampler != null))
        }
        if (payload.has("temperature")) koboldBody.put("temperature", payload.optDouble("temperature"))
        if (payload.has("top_p")) koboldBody.put("top_p", payload.optDouble("top_p"))
        applyTextSampler(sampler, koboldBody, TextBackend.KOBOLD)
        payload.optJSONObject(LOGIT_BIAS)?.let { koboldBody.put(LOGIT_BIAS, it) }
        if (wireStops.isNotEmpty()) koboldBody.put("stop_sequence", JSONArray(wireStops))
        val generationKey = newId().takeIf { payload.has("logprobs") }?.also {
            koboldBody.put("logprobs", true).put("genkey", it)
        }

        if (streaming) {
            val streamed = try {
                executeKoboldStream("$root/api/extra/generate/stream", koboldBody, headers, token, localStops)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Fall back to synchronous Kobold endpoint if streaming route is unavailable.
                null
            }
            if (streamed != null) {
                if (generationKey != null && streamed.tokenCollector == null && streamed.hasText &&
                    streamed.interruption == null && !streamed.reachedTextStop) {
                    try {
                        request("$root/api/extra/last_logprobs", jsonOf("genkey" to generationKey), headers, readTimeout = 5_000) { input, _ ->
                            val reply = JSONObject(input.readLimited().decodeToString())
                            val collector = ReplyTokenCollector().apply { accept(reply.optJSONObject("logprobs")) }
                            if (collector.finish(streamed.visibleText)?.aligned == true) streamed.tokenCollector = collector
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Probability lookup is optional and must not discard completed content.
                    }
                }
                return streamed
            }
        }

        val result = CompletionAccumulator(localStops)
        if (generationKey != null) result.tokenCollector = ReplyTokenCollector()
        request("$root/api/v1/generate", koboldBody, headers) { input, _ ->
            val json = JSONObject(input.readLimited().toString(Charsets.UTF_8))
            result.usage = result.usage.update(ResponseTokens.openAi(json))
            val reply = json.optJSONArray("results")?.optJSONObject(0)
            result.tokenCollector?.accept(reply?.optJSONObject("logprobs"))
            result.appendText(reply?.string("text").orEmpty(), token)
            result.finishReason = "stop"
        }
        return result.also { it.finishText(token) }
    }

    private suspend fun executeOpenAiTextCompletion(
        url: String,
        body: JSONObject,
        headers: Map<String, String>,
        token: (String) -> Unit,
        stops: List<String>,
    ): CompletionAccumulator {
        val result = CompletionAccumulator(stops)
        if (body.has("logprobs")) result.tokenCollector = ReplyTokenCollector()
        var streamOpened = false
        var streamConsumed = false
        try {
            request(url, body, headers) { input, type ->
                if (!type.contains("text/event-stream", ignoreCase = true)) {
                    val json = JSONObject(input.readLimited().toString(Charsets.UTF_8))
                    result.usage = result.usage.update(ResponseTokens.openAi(json))
                    val choice = json.optJSONArray("choices")?.optJSONObject(0)
                    result.tokenCollector?.accept(choice?.optJSONObject("logprobs"))
                    result.appendText(choice?.string("text").orEmpty(), token)
                    result.finishReason = choice?.string("finish_reason").orEmpty().ifBlank { "stop" }
                    return@request
                }
                streamOpened = true
                val reader = input.bufferedReader(Charsets.UTF_8)
                val data = mutableListOf<String>()
                fun handle(): Boolean {
                    val raw = data.joinToString("\n").trim()
                    data.clear()
                    if (raw.isEmpty()) return false
                    if (raw == "[DONE]") return true
                    val json = try {
                        JSONObject(raw)
                    } catch (e: JSONException) {
                        if (!result.hasReceivedText) throw e
                        result.interruption = PROTOCOL_STREAM_INTERRUPTED
                        return true
                    }
                    result.usage = result.usage.update(ResponseTokens.openAi(json))
                    val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return false
                    result.tokenCollector?.accept(choice.optJSONObject("logprobs"))
                    result.appendText(choice.string("text"), token)
                    val finish = choice.string("finish_reason")
                    if (finish.isNotBlank()) result.finishReason = finish
                    return result.reachedTextStop
                }
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank() && handle()) {
                        streamConsumed = true
                        return@request
                    }
                    if (line.startsWith("data:")) data += line.removePrefix("data:").removePrefix(" ")
                }
                val ended = data.isNotEmpty() && handle()
                if (!ended && result.finishReason.isBlank()) result.interruption = PROTOCOL_STREAM_INTERRUPTED
                streamConsumed = true
            }
        } catch (e: IOException) {
            if (!streamOpened) throw e
            if (!streamConsumed) result.interruption = PROTOCOL_STREAM_INTERRUPTED
        }
        return result.also { it.finishText(token) }
    }

    private suspend fun executeKoboldStream(
        url: String,
        body: JSONObject,
        headers: Map<String, String>,
        token: (String) -> Unit,
        stops: List<String>,
    ): CompletionAccumulator {
        val result = CompletionAccumulator(stops)
        var streamOpened = false
        var streamConsumed = false
        try {
            request(url, body, headers) { input, type ->
                if (!type.contains("text/event-stream", ignoreCase = true)) {
                    val json = JSONObject(input.readLimited().toString(Charsets.UTF_8))
                    val reply = json.optJSONArray("results")?.optJSONObject(0)
                    if (body.optBoolean("logprobs")) result.tokenCollector = ReplyTokenCollector().apply {
                        accept(reply?.optJSONObject("logprobs"))
                    }
                    result.appendText(reply?.string("text").orEmpty(), token)
                    result.finishReason = "stop"
                    return@request
                }
                streamOpened = true
                val reader = input.bufferedReader(Charsets.UTF_8)
                val data = mutableListOf<String>()
                fun handle(): Boolean {
                    val raw = data.joinToString("\n").trim()
                    data.clear()
                    if (raw.isEmpty()) return false
                    if (raw == "[DONE]") return true
                    val json = try {
                        JSONObject(raw)
                    } catch (e: JSONException) {
                        if (!result.hasReceivedText) throw e
                        result.interruption = PROTOCOL_STREAM_INTERRUPTED
                        return true
                    }
                    result.appendText(json.string("token"), token)
                    val finish = json.string("finish_reason")
                    if (finish.isNotBlank()) {
                        result.finishReason = if (finish.equals("length", true)) "length" else "stop"
                        return true
                    }
                    return result.reachedTextStop
                }
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank() && handle()) {
                        streamConsumed = true
                        return@request
                    }
                    if (line.startsWith("data:")) data += line.removePrefix("data:").removePrefix(" ")
                }
                if (data.isNotEmpty()) handle()
                if (result.finishReason.isBlank()) result.finishReason = "stop"
                streamConsumed = true
            }
        } catch (e: IOException) {
            if (!streamOpened) throw e
            if (!streamConsumed) result.interruption = PROTOCOL_STREAM_INTERRUPTED
        }
        return result.also { it.finishText(token) }
    }

    internal fun flattenMessagesToPrompt(messages: List<JSONObject>, preserveFinalAssistant: Boolean = false): TextCompletionPrompt {
        val sb = StringBuilder()
        for ((idx, msg) in messages.withIndex()) {
            val role = msg.string("role").lowercase()
            val finalAssistant = idx == messages.lastIndex && role == "assistant"
            val text = msg.promptText().let { if (finalAssistant && preserveFinalAssistant) it else it.trim() }
            if (text.isEmpty()) continue
            val tag = when (role) {
                "system" -> "### System"
                "assistant" -> "### Assistant"
                else -> "### User"
            }
            if (sb.isNotEmpty()) sb.append("\n\n")
            if (finalAssistant) {
                sb.append("### Assistant\n").append(text)
                return TextCompletionPrompt(sb.toString(), listOf("\n### User", "\n### System"))
            }
            sb.append(tag).append('\n').append(text)
        }
        if (sb.isNotEmpty()) sb.append("\n\n")
        sb.append("### Assistant\n")
        return TextCompletionPrompt(sb.toString(), listOf("\n### User", "\n### System"))
    }

    private fun extractBase64Images(content: Any?): List<String> {
        val array = content as? JSONArray ?: return emptyList()
        return array.objects().filter { it.string("type") == "image_url" }.mapNotNull { part ->
            parseDataUrl(part.optJSONObject("image_url")?.string("url").orEmpty())?.second
        }
    }

    private fun parseDataUrl(url: String): Pair<String, String>? {
        if (!url.startsWith("data:")) return null
        val comma = url.indexOf(',')
        if (comma < 0) return null
        val header = url.substring(5, comma)
        if (!header.endsWith(";base64", ignoreCase = true)) return null
        val mime = header.substringBefore(';').ifBlank { "image/png" }
        val data = url.substring(comma + 1).trim()
        return if (data.isNotEmpty()) mime to data else null
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
}
