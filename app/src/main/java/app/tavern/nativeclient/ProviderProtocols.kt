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
                val headers = buildOpenAiHeaders(provider)
                request("$base/models", headers = headers) { input, _ ->
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
        val root = base.removeSuffix("/api/v1/generate").removeSuffix("/api/v1").removeSuffix("/v1")
        return try {
            request("$root/api/v1/model", headers = headers, readTimeout = 10_000) { input, _ ->
                val result = JSONObject(input.readLimited().decodeToString()).string("result").trim()
                val ids = if (result.isNotBlank()) listOf(result) else emptyList()
                DiscoveredModels(ids, emptyMap())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            val v1Base = if (base.endsWith("/v1")) base else "$root/v1"
            request("$v1Base/models", headers = headers, readTimeout = 10_000) { input, _ ->
                DiscoveredModels.read(JSONObject(input.readLimited().decodeToString()))
            }
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
    ): CompletionAccumulator = when (provider.protocol) {
        ApiProtocol.ANTHROPIC -> completeAnthropic(provider, payload, onReasoning, token, acceptTools)
        ApiProtocol.GEMINI_NATIVE -> completeGeminiNative(provider, payload, onReasoning, token, acceptTools, onRetry)
        ApiProtocol.OLLAMA -> completeOllama(provider, payload, onReasoning, token)
        ApiProtocol.KOBOLD_TEXT -> completeKobold(provider, payload, token)
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
                val text = extractTextOnly(msg.opt("content"))
                if (text.isNotBlank()) systemTexts += text
                continue
            }
            if (role == "tool") {
                val toolCallId = msg.string("tool_call_id").ifBlank { "tool_call" }
                val text = extractTextOnly(msg.opt("content")).ifBlank { "{}" }
                appendMessage("user", listOf(jsonOf(
                    "type" to "tool_result",
                    "tool_use_id" to toolCallId,
                    "content" to text,
                )))
                continue
            }
            val targetRole = if (role == "assistant") "assistant" else "user"
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

        var maxTokens = payload.optInt("max_tokens", 0).takeIf { it > 0 } ?: 4096
        val thinkingBudget = provider.thinkingBudget?.takeIf { it > 0 }
        val body = JSONObject()
            .put("model", payload.string("model"))
            .put("messages", outMessages)
            .put("stream", streaming)

        if (systemBlocks.isNotEmpty()) body.put("system", JSONArray(systemBlocks))
        if (thinkingBudget != null) {
            val budget = thinkingBudget.coerceAtLeast(1024)
            maxTokens = maxOf(maxTokens, budget + 1024)
            body.put("thinking", jsonOf("type" to "enabled", "budget_tokens" to budget))
        } else {
            if (payload.has("temperature")) body.put("temperature", payload.optDouble("temperature"))
            if (payload.has("top_p")) body.put("top_p", payload.optDouble("top_p"))
        }
        body.put("max_tokens", maxTokens)

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
        return body
    }

    private suspend fun completeAnthropic(
        provider: Provider,
        payload: JSONObject,
        onReasoning: (String) -> Unit,
        token: (String) -> Unit,
        acceptTools: Boolean,
    ): CompletionAccumulator {
        val root = baseAddress(provider).removeSuffix("/v1")
        val streaming = payload.optBoolean("stream", true)
        val body = buildAnthropicPayload(provider, payload, streaming)
        val headers = buildMap {
            put("x-api-key", provider.apiKey.trim())
            put("anthropic-version", ANTHROPIC_VERSION)
            if (provider.promptCaching) put("anthropic-beta", ANTHROPIC_CACHE_BETA)
        }
        val result = CompletionAccumulator()
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
                        if (!result.hasText) error(msg)
                        result.interruption = msg
                        return true
                    }
                    val kind = json.string("type").ifBlank { currentEvent }
                    when (kind) {
                        "error" -> {
                            val detail = json.optJSONObject("error")?.string("message")
                                ?.redactValue(provider.apiKey.trim()).orEmpty().ifBlank { "Anthropic 请求出错" }
                            if (!result.hasText) error(detail)
                            result.interruption = detail
                            return true
                        }
                        "content_block_start" -> {
                            val idx = json.optInt("index", 0)
                            val block = json.optJSONObject("content_block")
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
                            when (delta.string("type")) {
                                "text_delta" -> result.appendText(delta.string("text"), token)
                                "thinking_delta" -> result.appendReasoning(delta.string("thinking"), onReasoning, explicit = true)
                                "input_json_delta" -> if (acceptTools) result.appendToolArgs(idx, delta.string("partial_json"))
                            }
                        }
                        "message_delta" -> {
                            val stop = json.optJSONObject("delta")?.string("stop_reason").orEmpty()
                            if (stop.isNotBlank()) result.finishReason = mapAnthropicStopReason(stop)
                        }
                        "message_stop" -> return true
                    }
                    return result.interruption != null
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
                if (result.finishReason.isBlank() && result.interruption == null && !result.hasOutput) {
                    result.interruption = PROTOCOL_STREAM_INTERRUPTED
                }
                streamConsumed = true
            }
        } catch (e: IOException) {
            if (!streamOpened) throw e
            if (!streamConsumed) result.interruption = PROTOCOL_STREAM_INTERRUPTED
        }
        return result
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
        for (block in json.optJSONArray("content")?.objects().orEmpty()) {
            when (block.string("type")) {
                "thinking" -> result.appendReasoning(block.string("thinking"), onReasoning, explicit = true)
                "text" -> result.appendText(block.string("text"), token)
                "tool_use" -> if (acceptTools) {
                    result.appendDirectToolCall(
                        block.string("id").ifBlank { "call_${newId()}" },
                        block.string("name"),
                        block.optJSONObject("input")?.toString() ?: "{}",
                    )
                }
            }
        }
        val stop = json.string("stop_reason")
        if (stop.isNotBlank()) result.finishReason = mapAnthropicStopReason(stop)
    }

    private fun mapAnthropicStopReason(reason: String): String = when (reason.lowercase()) {
        "end_turn", "stop_sequence" -> "stop"
        "max_tokens" -> "length"
        "tool_use" -> "tool_calls"
        "refusal" -> "content_filter"
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
                val t = extractTextOnly(msg.opt("content"))
                if (t.isNotBlank()) systemTexts += t
                continue
            }
            if (role == "tool") {
                val id = msg.string("tool_call_id")
                val name = toolCallNames[id] ?: "tool"
                val raw = extractTextOnly(msg.opt("content")).ifBlank { "{}" }
                val responseObj = runCatching { JSONObject(raw) }.getOrElse { jsonOf("result" to raw) }
                appendContent("user", listOf(jsonOf(
                    "functionResponse" to jsonOf("name" to name, "response" to responseObj),
                )))
                continue
            }
            val geminiRole = if (role == "assistant") "model" else "user"
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
            appendContent(geminiRole, parts)
        }

        if (contents.isEmpty()) {
            contents += "user" to mutableListOf(jsonOf("text" to "Continue."))
        }

        val systemJoined = systemTexts.joinToString("\n\n")
        val body = JSONObject().put(
            "contents",
            JSONArray(contents.map { (r, p) -> jsonOf("role" to r, "parts" to JSONArray(p)) }),
        )

        if (!cachedContentName.isNullOrBlank()) {
            body.put("cachedContent", cachedContentName)
        } else if (systemJoined.isNotBlank()) {
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
        if (payload.has("temperature")) generationConfig.put("temperature", payload.optDouble("temperature"))
        if (payload.has("top_p")) generationConfig.put("topP", payload.optDouble("top_p"))
        if (payload.has("max_tokens")) generationConfig.put("maxOutputTokens", payload.optInt("max_tokens"))
        if (payload.has("seed")) generationConfig.put("seed", payload.optLong("seed"))
        provider.thinkingBudget?.let { budget ->
            generationConfig.put("thinkingConfig", jsonOf("thinkingBudget" to budget))
        }
        if (generationConfig.length() > 0) body.put("generationConfig", generationConfig)

        if (cachedContentName.isNullOrBlank()) {
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
    ): CompletionAccumulator {
        val apiRoot = geminiApiRoot(baseAddress(provider))
        val modelName = payload.string("model").removePrefix("models/")
        val key = provider.apiKey.trim()
        val streaming = payload.optBoolean("stream", true)
        val (_, systemText) = buildGeminiNativePayload(provider, payload, cachedContentName = null)
        val canCache = provider.promptCaching && !payload.has("tools") && systemText.length >= GEMINI_CACHE_MIN_CHARS
        val cachedName = if (canCache) maybeCreateGeminiCache(apiRoot, modelName, key, systemText) else null
        val body = buildGeminiNativePayload(provider, payload, cachedContentName = cachedName).first

        return try {
            executeGeminiRequest(apiRoot, modelName, key, streaming, body, onReasoning, token, acceptTools)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (cachedName != null) {
                geminiContextCache.entries.removeIf { it.value.name == cachedName }
                onRetry()
                val fallbackBody = buildGeminiNativePayload(provider, payload, cachedContentName = null).first
                executeGeminiRequest(apiRoot, modelName, key, streaming, fallbackBody, onReasoning, token, acceptTools)
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
    ): CompletionAccumulator {
        val url = if (streaming) "$apiRoot/models/$modelName:streamGenerateContent?alt=sse"
        else "$apiRoot/models/$modelName:generateContent"
        val headers = mapOf("x-goog-api-key" to key)
        val result = CompletionAccumulator()
        var streamOpened = false
        var streamConsumed = false
        try {
            request(url, body, headers) { input, type ->
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
                        if (!result.hasText) error(message)
                        result.interruption = message
                        return true
                    }
                    parseGeminiResponse(json, result, acceptTools, key, onReasoning, token)
                    return result.interruption != null
                }
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank() && handle()) {
                        streamConsumed = true
                        return@request
                    }
                    if (line.startsWith("data:")) dataLines += line.removePrefix("data:").removePrefix(" ")
                }
                if (dataLines.isNotEmpty()) handle()
                if (result.finishReason.isBlank() && result.interruption == null && !result.hasOutput) {
                    result.interruption = PROTOCOL_STREAM_INTERRUPTED
                }
                streamConsumed = true
            }
        } catch (e: IOException) {
            if (!streamOpened) throw e
            if (!streamConsumed) result.interruption = PROTOCOL_STREAM_INTERRUPTED
        }
        return result
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
            if (!result.hasText) error(message)
            result.interruption = message
            return
        }
        val blockReason = json.optJSONObject("promptFeedback")?.string("blockReason").orEmpty()
        if (blockReason.isNotBlank()) {
            result.finishReason = "content_filter"
            return
        }
        val candidate = json.optJSONArray("candidates")?.optJSONObject(0) ?: return
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")?.objects().orEmpty()
        for (part in parts) {
            if (part.has("text")) {
                val text = part.string("text")
                if (part.optBoolean("thought")) {
                    result.appendReasoning(text, onReasoning, explicit = true)
                } else {
                    result.appendText(text, token)
                }
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
    ): CompletionAccumulator {
        val root = baseAddress(provider).removeSuffix("/v1").removeSuffix("/api")
        val streaming = payload.optBoolean("stream", true)
        val headers = buildOpenAiHeaders(provider)
        val options = JSONObject()
        if (payload.has("temperature")) options.put("temperature", payload.optDouble("temperature"))
        if (payload.has("top_p")) options.put("top_p", payload.optDouble("top_p"))
        if (payload.has("max_tokens")) options.put("num_predict", payload.optInt("max_tokens"))
        if (payload.has("seed")) options.put("seed", payload.optLong("seed"))

        val (url, body) = if (provider.textCompletion) {
            val flattened = flattenMessagesToPrompt(payload.optJSONArray("messages")?.objects().orEmpty())
            val req = jsonOf(
                "model" to payload.string("model"),
                "prompt" to flattened.prompt,
                "stream" to streaming,
            )
            if (options.length() > 0) req.put("options", options)
            if (flattened.stops.isNotEmpty()) {
                val opts = req.optJSONObject("options") ?: JSONObject().also { req.put("options", it) }
                opts.put("stop", JSONArray(flattened.stops))
            }
            "$root/api/generate" to req
        } else {
            val mapped = JSONArray()
            for (msg in payload.optJSONArray("messages")?.objects().orEmpty()) {
                val text = extractTextOnly(msg.opt("content"))
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
            if (options.length() > 0) req.put("options", options)
            "$root/api/chat" to req
        }

        val result = CompletionAccumulator()
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
                        if (!result.hasText) error(msg)
                        result.interruption = msg
                        break
                    }
                    consumeOllamaObject(json, provider.textCompletion, result, onReasoning, token)
                    if (json.optBoolean("done") || result.interruption != null) break
                }
                streamConsumed = true
            }
        } catch (e: IOException) {
            if (!streamOpened) throw e
            if (!streamConsumed) result.interruption = PROTOCOL_STREAM_INTERRUPTED
        }
        return result
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
            if (!result.hasText) error(err)
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
            result.finishReason = if (json.string("done_reason").equals("length", true)) "length" else "stop"
        }
    }

    // --- KoboldCPP / OpenAI Text Completion ---

    private suspend fun completeKobold(
        provider: Provider,
        payload: JSONObject,
        token: (String) -> Unit,
    ): CompletionAccumulator {
        val base = baseAddress(provider)
        val headers = buildOpenAiHeaders(provider)
        val flattened = flattenMessagesToPrompt(payload.optJSONArray("messages")?.objects().orEmpty())
        val streaming = payload.optBoolean("stream", true)
        val maxLen = payload.optInt("max_tokens", 0).takeIf { it > 0 } ?: 512

        // OpenAI /v1/completions mode
        if (base.endsWith("/v1")) {
            val req = jsonOf(
                "model" to payload.string("model"),
                "prompt" to flattened.prompt,
                "max_tokens" to maxLen,
                "stream" to streaming,
            )
            if (payload.has("temperature")) req.put("temperature", payload.optDouble("temperature"))
            if (payload.has("top_p")) req.put("top_p", payload.optDouble("top_p"))
            if (flattened.stops.isNotEmpty()) req.put("stop", JSONArray(flattened.stops))
            return executeOpenAiTextCompletion("$base/completions", req, headers, token)
        }

        // Native KoboldCPP (/api/extra/generate/stream or /api/v1/generate)
        val root = base.removeSuffix("/api/v1/generate").removeSuffix("/api/v1")
        val koboldBody = jsonOf(
            "prompt" to flattened.prompt,
            "max_length" to maxLen,
        )
        if (payload.has("temperature")) koboldBody.put("temperature", payload.optDouble("temperature"))
        if (payload.has("top_p")) koboldBody.put("top_p", payload.optDouble("top_p"))
        if (flattened.stops.isNotEmpty()) koboldBody.put("stop_sequence", JSONArray(flattened.stops))

        if (streaming) {
            try {
                return executeKoboldStream("$root/api/extra/generate/stream", koboldBody, headers, token)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Fall back to synchronous Kobold endpoint if streaming route is unavailable.
            }
        }

        val result = CompletionAccumulator()
        request("$root/api/v1/generate", koboldBody, headers) { input, _ ->
            val json = JSONObject(input.readLimited().toString(Charsets.UTF_8))
            val text = json.optJSONArray("results")?.optJSONObject(0)?.string("text").orEmpty()
            result.appendText(text, token)
            result.finishReason = "stop"
        }
        return result
    }

    private suspend fun executeOpenAiTextCompletion(
        url: String,
        body: JSONObject,
        headers: Map<String, String>,
        token: (String) -> Unit,
    ): CompletionAccumulator {
        val result = CompletionAccumulator()
        var streamOpened = false
        var streamConsumed = false
        try {
            request(url, body, headers) { input, type ->
                if (!type.contains("text/event-stream", ignoreCase = true)) {
                    val json = JSONObject(input.readLimited().toString(Charsets.UTF_8))
                    val choice = json.optJSONArray("choices")?.optJSONObject(0)
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
                    val json = JSONObject(raw)
                    val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return false
                    result.appendText(choice.string("text"), token)
                    val finish = choice.string("finish_reason")
                    if (finish.isNotBlank()) result.finishReason = finish
                    return false
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
                streamConsumed = true
            }
        } catch (e: IOException) {
            if (!streamOpened) throw e
            if (!streamConsumed) result.interruption = PROTOCOL_STREAM_INTERRUPTED
        }
        return result
    }

    private suspend fun executeKoboldStream(
        url: String,
        body: JSONObject,
        headers: Map<String, String>,
        token: (String) -> Unit,
    ): CompletionAccumulator {
        val result = CompletionAccumulator()
        var streamOpened = false
        var streamConsumed = false
        try {
            request(url, body, headers) { input, type ->
                if (!type.contains("text/event-stream", ignoreCase = true)) {
                    val json = JSONObject(input.readLimited().toString(Charsets.UTF_8))
                    val text = json.optJSONArray("results")?.optJSONObject(0)?.string("text").orEmpty()
                    result.appendText(text, token)
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
                    val json = JSONObject(raw)
                    result.appendText(json.string("token"), token)
                    val finish = json.string("finish_reason")
                    if (finish.isNotBlank()) {
                        result.finishReason = if (finish.equals("length", true)) "length" else "stop"
                        return true
                    }
                    return false
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
        return result
    }

    internal data class FlattenedPrompt(val prompt: String, val stops: List<String>)

    internal fun flattenMessagesToPrompt(messages: List<JSONObject>): FlattenedPrompt {
        val sb = StringBuilder()
        for ((idx, msg) in messages.withIndex()) {
            val role = msg.string("role").lowercase()
            val text = extractTextOnly(msg.opt("content")).trim()
            if (text.isEmpty()) continue
            val tag = when (role) {
                "system" -> "### System"
                "assistant" -> "### Assistant"
                else -> "### User"
            }
            if (sb.isNotEmpty()) sb.append("\n\n")
            if (idx == messages.lastIndex && role == "assistant") {
                sb.append("### Assistant\n").append(text)
                return FlattenedPrompt(sb.toString(), listOf("\n### User", "\n### System"))
            }
            sb.append(tag).append('\n').append(text)
        }
        if (sb.isNotEmpty()) sb.append("\n\n")
        sb.append("### Assistant\n")
        return FlattenedPrompt(sb.toString(), listOf("\n### User", "\n### System"))
    }

    private fun extractTextOnly(content: Any?): String = when (content) {
        is String -> content
        is JSONArray -> content.objects()
            .filter { it.string("type") == "text" }
            .map { it.string("text") }
            .filter(String::isNotEmpty)
            .joinToString("\n")
        else -> ""
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
