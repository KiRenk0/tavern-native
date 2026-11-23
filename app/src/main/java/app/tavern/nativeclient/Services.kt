package app.tavern.nativeclient

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val MAX_RESPONSE = 24 * 1024 * 1024
private const val STREAM_INTERRUPTED = "模型连接在生成完成前中断"
private val stopParameter = Regex("""\b(?:stop|stop_sequence|stop_sequences|stopSequences)\b""", RegexOption.IGNORE_CASE)
private val unsupportedParameter = Regex(
    """not supported|unsupported|unrecognized|unknown|unexpected|not permitted|extra_forbidden|cannot be used""", RegexOption.IGNORE_CASE,
)
private val rejectedPrefill = Regex(
    """prefill|(?:last|final) (?:message|role|turn).*user|end(?:s)? with (?:a )?user (?:message|role|turn)|assistant messages? (?:are )?not supported""",
    RegexOption.IGNORE_CASE,
)
private val logprobsParameter = Regex("""\b(?:logprobs|top_logprobs)\b""", RegexOption.IGNORE_CASE)
private val macro = Regex("\\{\\{(char|user)\\}\\}", RegexOption.IGNORE_CASE)
private val referenceMarker = Regex("\uFDD0[a-f0-9-]+\uFDD0")

internal fun expand(text: String, card: Card, persona: String) =
    macro.replace(text) { if (it.groupValues[1].equals("char", true)) card.name else persona }

internal fun swapPersonaMacros(text: String) =
    macro.replace(text) { if (it.groupValues[1].equals("char", true)) "{{user}}" else "{{char}}" }

internal fun checkedAddress(address: String): URI {
    require(address.isNotBlank()) { "请输入服务地址" }
    val uri = runCatching { URI(address.trim()) }.getOrNull()
    require(uri != null && uri.scheme in setOf("https", "http") && uri.host != null &&
        uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
        "请输入有效的 HTTPS 或 HTTP 地址，例如 https://api.example.com/v1"
    }
    return uri
}

internal fun InputStream.readLimited(limit: Int = MAX_RESPONSE): ByteArray {
    val result = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var length: Int
    while (read(buffer).also { length = it } != -1) {
        require(result.size() <= limit - length) { "数据过大，最多支持 ${limit / (1024 * 1024)} MB" }
        result.write(buffer, 0, length)
    }
    return result.toByteArray()
}

internal data class HttpUpload(val contentType: String, val bytes: ByteArray)
internal class HttpRedirect(val status: Int, val location: String) : IOException("HTTP $status")
internal class HttpRequestError(val status: Int, val detail: String) : IllegalStateException(
    if (detail.isBlank()) "服务器返回 HTTP $status" else "HTTP $status · $detail",
)

internal suspend fun <T> request(
    url: String,
    body: JSONObject? = null,
    headers: Map<String, String> = emptyMap(),
    cookies: CookieManager? = null,
    readTimeout: Int = 90_000,
    followRedirects: Boolean = false,
    upload: HttpUpload? = null,
    responseAddress: ((String) -> Unit)? = null,
    reportRedirects: Boolean = false,
    redactError: (String) -> String = { it },
    method: String? = null,
    read: (InputStream, String) -> T,
): T = suspendCancellableCoroutine { continuation ->
    val active = AtomicReference<HttpURLConnection?>()
    continuation.invokeOnCancellation {
        active.get()?.let { connection -> Dispatchers.IO.dispatch(continuation.context) { connection.disconnect() } }
    }
    Dispatchers.IO.dispatch(continuation.context, Runnable {
        if (!continuation.isActive) return@Runnable
        try {
            val uri = URI(url)
            val connection = uri.toURL().openConnection() as HttpURLConnection
            active.set(connection)
            try {
                if (!continuation.isActive) return@Runnable
                connection.instanceFollowRedirects = followRedirects
                connection.connectTimeout = 15_000
                connection.readTimeout = readTimeout
                connection.requestMethod = method ?: if (body == null && upload == null) "GET" else "POST"
                connection.setRequestProperty("Accept", "application/json, text/event-stream, image/png")
                headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                cookies?.get(uri, emptyMap())?.forEach { (name, values) ->
                    if (values.isNotEmpty()) connection.setRequestProperty(name, values.joinToString("; "))
                }
                val serialized = upload?.bytes ?: body?.toString()?.toByteArray(Charsets.UTF_8)
                if (serialized != null) {
                    connection.doOutput = true
                    if (upload != null || headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
                        connection.setRequestProperty("Content-Type", upload?.contentType ?: "application/json; charset=utf-8")
                    }
                    connection.setFixedLengthStreamingMode(serialized.size)
                    connection.outputStream.use { it.write(serialized) }
                }
                val status = connection.responseCode
                if (reportRedirects && status in setOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: error("重定向缺少目标地址")
                    throw HttpRedirect(status, location)
                }
                cookies?.put(uri, connection.headerFields.filterKeys { it != null })
                if (status !in 200..299) {
                    val detail = connection.errorStream?.use {
                        runCatching {
                            val j = JSONObject(it.readLimited(1024 * 1024).toString(Charsets.UTF_8))
                            j.optJSONObject("error")?.string("message").orEmpty()
                                .ifBlank { j.string("error") }.ifBlank { j.string("message") }
                        }.getOrDefault("").let(redactError).take(250)
                    }.orEmpty()
                    throw HttpRequestError(status, detail)
                }
                val result = connection.inputStream.use {
                    responseAddress?.invoke(connection.url.toExternalForm())
                    read(it, connection.contentType.orEmpty())
                }
                if (continuation.isActive) continuation.resume(result)
            } finally {
                connection.disconnect()
                active.set(null)
            }
        } catch (e: Exception) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
    })
}

internal object ModelService {
    private fun auth(provider: Provider) =
        if (provider.apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${provider.apiKey.trim()}")

    private fun base(provider: Provider) =
        checkedAddress(provider.endpoint).toString().trimEnd('/').removeSuffix("/chat/completions")

    private fun inspectedPrompt(messages: JSONArray): JSONArray = JSONArray(messages.objects().map { line ->
        val privateFields = setOf(GEMINI_REPLAY_PARTS, ANTHROPIC_WEB_REPLAY, "server_tool_calls")
        val content = line.optJSONArray("content") ?: return@map if (privateFields.any(line::has)) {
            JSONObject(line, line.keys().asSequence().filterNot(privateFields::contains).toList().toTypedArray())
        } else line
        jsonOf("role" to line.string("role"), "name" to line.opt("name"), "content" to JSONArray(content.objects().map { part ->
            val url = part.optJSONObject("image_url")?.string("url").orEmpty()
            if (!url.startsWith("data:")) part else
                jsonOf("type" to "image_url", "image_url" to jsonOf("url" to "${url.substringBefore(',')},[图片数据]"))
        }))
    })

    fun validated(prefs: Preferences): Preferences {
        val temperature = prefs.temperature.trim()
        val maximum = prefs.maxTokens.trim()
        require(temperature.isBlank() || temperature.toDoubleOrNull()?.let { it in 0.0..2.0 } == true) {
            "温度应在 0–2 之间"
        }
        require(maximum.isBlank() || maximum.toIntOrNull()?.let { it > 0 } == true) {
            "最大输出 Token 数应为正整数"
        }
        return prefs.copy(temperature = temperature, maxTokens = maximum)
    }

    suspend fun models(provider: Provider): List<String> = discoverModels(provider).ids

    suspend fun discoverModels(provider: Provider): DiscoveredModels =
        ProviderProtocols.discoverModels(provider)

    suspend fun reply(prefs: Preferences, connection: ModelConnection, card: Card, history: List<Message>,
                      books: List<WorldBook>, group: GroupPrompt?,
                      persona: PersonaProfile = prefs.personaFor(), options: ConversationOptions = ConversationOptions(),
                      trigger: String = "normal", preset: PromptPreset? = null,
                      forcedContinuation: String? = null,
                      onPrompt: (JSONArray) -> Unit = {},
                      onReasoning: (String) -> Unit = {},
                      onLoreActivated: suspend (Set<String>) -> Unit = {},
                      readImage: (ChatImage) -> ByteArray? = { null },
                      readDocument: (ChatDocument) -> String? = { null },
                      vectors: VectorContext = VectorContext(),
                      tools: ReplyTools? = null,
                      onRetry: () -> Unit = {},
                      sharedMemory: String = "",
                      loreCounter: TextTokenCounter = estimatedTokens,
                      onLoreBudget: (LoreBudgetUsage) -> Unit = {},
                      chatId: String = "",
                      onVariables: suspend (MacroVariableChanges) -> Unit = {},
                      effectiveSampling: EffectiveSampling? = null,
                      onHiddenPrefix: (String) -> Unit = {},
                      onContextBudget: (PromptBudgetUsage) -> Unit = {},
                      quietPrompt: ScriptQuietPrompt? = null,
                      onQueueProgress: (String) -> Unit = {},
                      token: (String) -> Unit): ReplyResult {
        val chatSettings = preset.chatCompletionFor(connection)
        val webSearch = connection.webSearchBackend().takeIf {
            forcedContinuation == null && chatSettings?.webSearch == true && trigger !in setOf("quiet", "impersonate")
        }
        val outputOptions = JSONObject().also { payload ->
            if (forcedContinuation == null && trigger !in setOf("quiet", "impersonate")) chatSettings?.images?.applyTo(connection, payload)
        }
        val geminiImages = outputOptions.has(REPLY_IMAGE_OPTIONS)
        val modelImages = geminiImages || outputOptions.has("modalities")
        val searchProvider = if (chatSettings?.webSearch == true && !modelImages && trigger in setOf("normal", "regenerate")) {
            connection.googleSearchProvider()
        } else null
        val googleSearch = searchProvider != null
        val provider = searchProvider ?: connection.provider
        val requestOptions = if (provider.supportsRequestOptions && !provider.requestOptions.isEmpty) {
            val macros = ConversationMacros(card, persona, history, group, options, prefs.scriptVariables, connection.model, chatId, trigger)
            provider.compiledRequestOptions(PromptPostProcessingNames(persona.name, card.name, group?.roster.orEmpty())) {
                macros.expand(it, write = false)
            }
        } else CompiledProviderRequest()
        val tokenBias = if (!requestOptions.overridesBodyField(LOGIT_BIAS) && !googleSearch) connection.compileTokenBias()
            else null
        val deepSeekWebContinuation = provider.template == ProviderTemplate.DEEPSEEK &&
            trigger == "continue" && (webSearch == WebSearchBackend.DEEPSEEK || history.lastOrNull()?.webReply != null)
        val textRules = prefs.regex.runtime(card, persona.name, preset, group?.roster.orEmpty())
        val loreTagIds = prefs.library.loreTags(card)
        val relaxGroupTrimming = prefs.chatHandling.relaxGroupTrimming
        val otherSpeakers = if (relaxGroupTrimming || trigger in setOf("quiet", "impersonate")) emptyList()
            else group?.roster.orEmpty().filter { it.isNotBlank() && it != card.name }.distinct()
        val allowsLocalTools = !provider.usesTextCompletion && !geminiImages && !googleSearch &&
            webSearch != WebSearchBackend.DEEPSEEK && forcedContinuation == null && requestOptions.allowsTools
        val requestProbabilities = prefs.requestTokenProbabilities && provider.canRequestTokenProbabilities &&
            trigger !in setOf("quiet", "impersonate") && !modelImages && webSearch == null &&
            !(provider.protocol == ApiProtocol.OPENROUTER && connection.model.endsWith(":online", true))
        val preparedPreset = if (forcedContinuation != null) preset?.let {
            it.copy(chatCompletion = it.chatCompletion.copy(spacing = ContinueSpacing.NONE))
        } else preset
        val toolConnection = connection.takeIf { prefs.toolCalling && allowsLocalTools }
        val historyToolConnection = if (requestOptions.processesMessages && !requestOptions.allowsTools) connection else toolConnection
        val formats = connection.resolvedTextTemplates()
        val instruct = formats.instruct
        val textContext = formats.context
        val textSystem = prefs.textSystem.takeIf { provider.usesTextCompletion && it.configured }
        val novelAi = provider.novelAi.takeIf { provider.protocol == ApiProtocol.NOVELAI_TEXT }
        val hordeText = provider.protocol == ApiProtocol.HORDE_TEXT
        val candidates = modelHistory(history, vectors, historyToolConnection,
            includeNarrator = instruct != null || textContext != null || novelAi != null || hordeText)
        val historyEnabled = preset == null || textContext != null || textSystem != null ||
            preset.prompts.any { it.section == PromptSection.HISTORY && !it.atDepth && it.applies(trigger) }
        val relevant = if (historyEnabled) candidates else emptyList()
        val lastVisible = relevant.indexOfLast { it.value.toolTurn == null }
        val minimum = if (lastVisible < 0) 0 else relevant.size - lastVisible
        val (images, documents, geminiReplay) = withContext(Dispatchers.IO) {
            val window = relevant.map { it.value }
            val rawImages = if (prefs.captions.textOnly || provider.usesTextCompletion) emptyMap()
                else promptImages(window, modelImages, prefs.chatAppearance.defaultMediaDisplay, readImage)
            val replay = if (!geminiImages || prefs.captions.textOnly) emptyMap() else window.mapIndexedNotNull { i, entry ->
                entry.replyMedia?.gemini?.wireParts(connection, rawImages[i].orEmpty())?.let { i to it }
            }.toMap()
            Triple(rawImages.mapValues { it.value.values.toList() }, promptDocuments(window, vectors.attachments, readDocument), replay)
        }
        val webReplay = if (webSearch == null) emptyMap() else relevant.mapIndexedNotNull { index, row ->
            row.value.webReply?.replay?.wireValue(connection, webSearch)?.let {
                val key = if (webSearch == WebSearchBackend.ANTHROPIC) ANTHROPIC_WEB_REPLAY else "server_tool_calls"
                index to (key to it)
            }
        }.toMap()
        val leadingSystemOnly = provider.template == ProviderTemplate.DEEPSEEK ||
            (provider.template == ProviderTemplate.GOOGLE && provider.protocol == ApiProtocol.OPENAI_COMPATIBLE)
        val includeReasoning = effectiveSampling?.includeReasoning ?: prefs.includeReasoning
        val sampler = connection.textSampler()?.json()
        val temperature = (effectiveSampling?.temperature ?: prefs.temperature).toDoubleOrNull()
        val topP = effectiveSampling?.topP.orEmpty().toDoubleOrNull()
        val outputTokens = (effectiveSampling?.maxTokens ?: prefs.maxTokens).toIntOrNull()
            ?: sampler?.optInt(TextSamplerField.MAX_OUTPUT.key)?.takeIf { it > 0 }
        val contextTokens = connection.contextTokens(presetLimit = chatSettings?.contextLimit)
        val streaming = if (trigger == "quiet" && !provider.usesTextCompletion) false
            else chatSettings?.streaming ?: provider.streaming
        val reserved = connection.outputReserve(outputTokens ?: preset?.sampling?.get(PresetSampling.MAX_TOKENS)?.toIntOrNull(),
            contextTokens)
        val allowance = (contextTokens - reserved).coerceAtLeast(0)
        val loreBudget = prefs.loreSettings.modelBudget(connection, prefs, preset, loreCounter, reserved)
        if (temperature != null) sampler?.remove(TextSamplerField.DYNAMIC.key)
        val meter = PromptTokenMeter(loreCounter)
        val toolRows = mutableListOf<JSONObject>()
        var maxHistory = relevant.size
        var firstPrompt: PreparedPrompt? = null
        var previousPrompt: PreparedPrompt? = null
        val generated = linkedMapOf<String, ReplyRaster>()
        var sources = emptyList<ReplySource>()
        var imageWarning = false
        var usage = ResponseUsage()
        for (round in 0..MAX_TOOL_ROUNDS) {
            val available = if (allowsLocalTools) tools?.reload?.invoke() ?: tools?.tools.orEmpty() else emptyList()
            val registered = available.filter { it.shouldRegister() }
            val schemas = JSONArray(registered.map(ModelTool::json) + webSearch?.hostedTools().orEmpty())
            val fitted = withContext(Dispatchers.Default) {
                fun measure(attempt: PreparedPrompt): Long {
                    val text = if (provider.usesTextCompletion) {
                        attempt.textPrompt?.text ?: ProviderProtocols.flattenMessagesToPrompt(attempt.messages.objects()).text
                    } else null
                    val extra = attempt.replyPrefix.ifEmpty { attempt.continuationFallback.orEmpty() }
                    return meter.measure(requestOptions.processMessages(attempt.messages), text, extra, schemas)
                }
                previousPrompt?.let { previous ->
                    val tokens = measure(previous)
                    if (tokens <= allowance) return@withContext FittedPrompt(previous, maxHistory, tokens)
                }
                fun fit(budget: LoreBudget) =
                    fitPromptWindow(maxHistory, minimum, allowance, prefs.exampleBudget, ::measure) { count, withExamples ->
                        coroutineContext.ensureActive()
                        val from = relevant.size - count
                        val draft = prompt(card, history, leadingSystemOnly, books, group, persona, options, prefs.loreSettings,
                            trigger, preparedPreset, textRules, includeReasoning, prefs.scriptVariables, images, prefs.vectors, vectors,
                            documents, historyToolConnection, sharedMemory, budget,
                            prefs.captions, connection.model, chatId, instruct, textContext, textSystem, prefs.customStops,
                            prefs.replyFormatting, provider.usesTextCompletion,
                            historyWindow = relevant.subList(from, relevant.size), windowOffset = from, includeExamples = withExamples,
                            hasChatNameField = provider.hasChatNameField,
                            acceptsChatContinuation = provider.acceptsChatContinuation && !deepSeekWebContinuation,
                            geminiReplay = geminiReplay, webReplay = webReplay,
                            allowReplyPrefix = webSearch != WebSearchBackend.DEEPSEEK && !googleSearch,
                            forcedContinuation = forcedContinuation, preserveSpeakerRole = requestOptions.processesMessages,
                            quietPrompt = quietPrompt, novelAiPreamble = novelAi?.preamble, usePlainTextFormat = hordeText,
                            loreTagIds = loreTagIds,
                            preferCharacterPrompt = prefs.textSystem.preferCharacterPrompt,
                            preferCharacterPostHistory = prefs.textSystem.preferCharacterPostHistory,
                            relaxGroupTrimming = relaxGroupTrimming,
                            collapseNewlines = prefs.chatHandling.collapseNewlines)
                        toolRows.forEach(draft.messages::put)
                        draft
                    }
                var budget = loreBudget
                var attempt = fit(budget)
                while (attempt.tokens > allowance && budget.limit > 0 && attempt.value.loreBudget.used > 0) {
                    val overflow = (attempt.tokens - allowance).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    val limit = minOf(budget.limit / 2, attempt.value.loreBudget.used - overflow).coerceAtLeast(0)
                    budget = loreBudget.copy(limit = limit)
                    attempt = fit(budget)
                }
                attempt
            }
            maxHistory = fitted.historySize
            val prepared = fitted.value
            val first = relevant.subList(relevant.size - fitted.historySize, relevant.size)
                .firstOrNull { !it.value.excludedFromContext }
            val budgetUsage = PromptBudgetUsage(contextTokens, reserved, fitted.tokens, relevant.size - fitted.historySize,
                loreCounter.label, first?.index, first?.value?.date)
            onContextBudget(budgetUsage)
            check(fitted.tokens <= allowance) {
                "当前提示词约 ${fitted.tokens} Token，输入额度为 $allowance。请缩短最新消息或固定提示，或调整上下文与回复长度。"
            }
            val initial = firstPrompt ?: prepared.also {
                firstPrompt = it
                onLoreBudget(it.loreBudget)
                if (!it.variables.isEmpty) onVariables(it.variables)
                if (it.automationIds.isNotEmpty()) onLoreActivated(it.automationIds)
            }
            val payload = completionPayload(connection, prepared.messages, streaming)
            if (hordeText) payload.put(HORDE_CONTEXT_PAYLOAD, contextTokens)
            outputOptions.keys().forEach { payload.put(it, outputOptions.get(it)) }
            preset?.applySampling(payload, provider.template)
            tokenBias?.let { payload.put(LOGIT_BIAS, it) }
            if (requestProbabilities && registered.isEmpty()) {
                if (provider.usesTextCompletion) payload.put("logprobs", TOKEN_PROBABILITY_COUNT)
                else payload.put("logprobs", true).put("top_logprobs", TOKEN_PROBABILITY_COUNT)
            }
            (temperature ?: sampler?.opt(TextSamplerField.TEMPERATURE.key))?.let { payload.put("temperature", it) }
            (topP ?: sampler?.opt(TextSamplerField.TOP_P.key))?.let { payload.put("top_p", it) }
            outputTokens?.let { payload.put("max_tokens", it) }
            sampler?.let { payload.put(TEXT_SAMPLER_PAYLOAD, it) }
            if (schemas.length() > 0) payload.put("tools", schemas)
            if (registered.isNotEmpty()) payload.put("tool_choice", if (round == MAX_TOOL_ROUNDS) "none" else "auto")
            if (webSearch == WebSearchBackend.ANTHROPIC) payload.put(ANTHROPIC_WEB_SEARCH, true)
            if (googleSearch) payload.put(GEMINI_GOOGLE_SEARCH, true)
            val prompt = prepared.textPrompt?.let {
                if (novelAi == null) it else
                    it.copy(stopTokens = NovelAi.encodeStops(connection.model, prepared.stops + it.stops, loreCounter.encode))
            }
            val inspectText = prompt ?: if (provider.usesTextCompletion) {
                ProviderProtocols.flattenMessagesToPrompt(prepared.messages.objects())
            } else null
            val customStops = if (googleSearch) emptyList() else prepared.stops
            val allStops = (customStops + inspectText?.stops.orEmpty()).distinct()
            fun inspect(messages: JSONArray) {
                val inspected = if (inspectText != null) inspectText.copy(stops = allStops).inspection()
                else inspectedPrompt(messages).apply {
                    if (allStops.isNotEmpty()) put(jsonOf("role" to "停止序列", "content" to JSONArray(allStops).toString(2)))
                }
                onPrompt(inspected)
            }
            var groupTrimmer = otherSpeakers.takeIf(List<String>::isNotEmpty)?.let(::GroupReplyTrimmer)
            fun freshOutput() = FormattedReplyStream(prepared.replyPrefix, prefs.replyFormatting.showPrefix,
                prepared.reasoningFormat, onReasoning, { chunk ->
                    val accepted = groupTrimmer?.append(chunk) ?: chunk
                    if (accepted.isNotEmpty()) token(accepted)
                }, onHiddenPrefix)
            var output = freshOutput()
            val result = complete(provider, payload,
                { if (!googleSearch) output.acceptNativeReasoning(it) }, { if (!googleSearch) output.accept(it) },
                acceptTools = registered.isNotEmpty(), onRetry = {
                    groupTrimmer = otherSpeakers.takeIf(List<String>::isNotEmpty)?.let(::GroupReplyTrimmer)
                    output = freshOutput()
                    onRetry()
                }, textPrompt = prompt, customStops = customStops,
                assistantPrefill = prepared.replyPrefix.takeIf { prompt == null }.orEmpty(), onRequest = ::inspect,
                continuationFallback = prepared.continuationFallback.takeIf { toolRows.isEmpty() },
                requireNativeContinuation = forcedContinuation != null && !provider.usesTextCompletion,
                requestOptions = requestOptions, onQueueProgress = onQueueProgress)
            usage = usage.record(result.usage)
            val interruption = completionInterruption(result)
            if (googleSearch) {
                val grounded = result.geminiGrounding?.display(result.visibleText)
                if (grounded != null) return ReplyResult(initial.loreEffects, interruption, continuable = false,
                    usage = usage, googleGrounded = grounded)
                // Grounding metadata can arrive after the text, so classify the completed reply before publishing it.
                result.interruption?.let { error("$it，请重试") }
                output.acceptNativeReasoning(result.visibleReasoning)
                output.accept(result.visibleText)
            }
            output.finish()
            groupTrimmer?.finish()?.takeIf(String::isNotEmpty)?.let(token)
            val trimmed = groupTrimmer?.stopped == true
            val stopped = result.reachedTextStop || trimmed
            val incomplete = interruption.takeUnless { trimmed }
            sources = combineReplySources(sources + result.sources)
            val replay = webSearch?.takeIf { result.interruption == null && !stopped }?.let {
                WebReplyReplay.create(it, connection, result.webTranscript)
            }
            val webReply = StoredWebReply(sources, replay).takeIf { sources.isNotEmpty() || replay != null }
            result.images.forEach { image ->
                if (image.key !in generated) {
                    if (generated.size >= MAX_CHAT_IMAGES || generated.values.sumOf { it.bytes.size } + image.bytes.size > MAX_REPLY_IMAGE_BYTES) {
                        imageWarning = true
                    } else generated[image.key] = image
                }
            }
            imageWarning = imageWarning || result.rejectedImage
            if (imageWarning && !output.hasBody && !output.hasReasoning && generated.isEmpty() && !result.hasToolCalls) {
                error("模型返回的图片无法读取")
            }
            if (incomplete != null && (output.hasBody || output.hasReasoning || generated.isNotEmpty())) {
                return ReplyResult(initial.loreEffects, incomplete, continuable = result.finishReason != "content_filter",
                    usage = usage, hiddenReplyPrefix = output.hiddenPrefix, hasReasoning = output.hasReasoning,
                    images = generated.values.toList(), imageWarning = imageWarning, webReply = webReply,
                    replyTokens = result.tokenTrace().takeIf { round == 0 })
            }
            if (!trimmed) result.interruption?.let { error("$it，请重试") }
            val calls = if (stopped) emptyList() else result.calls()
            if (calls.isEmpty()) return ReplyResult(initial.loreEffects, continuable = !trimmed, usage = usage, stoppedBySequence = stopped,
                hiddenReplyPrefix = output.hiddenPrefix, hasReasoning = output.hasReasoning, images = generated.values.toList(),
                imageWarning = imageWarning, geminiReplayParts = result.geminiReplayParts.takeUnless { trimmed }.orEmpty(), webReply = webReply,
                replyTokens = result.tokenTrace().takeIf { round == 0 && !trimmed })
            check(round < MAX_TOOL_ROUNDS) { "模型已达到工具调用轮次上限" }
            val invocations = calls.map { call ->
                call.run(registered, progress = { tools?.progress?.invoke(it) }) { error -> error.redactValue(provider.apiKey) }
            }
            if (invocations.any(ToolInvocation::stealth)) {
                tools?.finishStealth?.invoke(invocations)
                break
            }
            val turn = result.turn(connection, invocations)
            tools?.record?.invoke(turn)
            val rows = turn.messages()
            toolRows += rows
            rows.forEach(prepared.messages::put)
            previousPrompt = prepared
        }
        return ReplyResult(requireNotNull(firstPrompt).loreEffects, usage = usage, images = generated.values.toList(),
            imageWarning = imageWarning, webReply = StoredWebReply(sources).takeIf { sources.isNotEmpty() })
    }

    internal data class ReplyResult(
        val loreEffects: Map<String, LoreEffect>,
        val incomplete: String? = null,
        val continuable: Boolean = true,
        val usage: ResponseUsage = ResponseUsage(),
        val stoppedBySequence: Boolean = false,
        val hiddenReplyPrefix: String = "",
        val hasReasoning: Boolean = false,
        val images: List<ReplyRaster> = emptyList(),
        val imageWarning: Boolean = false,
        val geminiReplayParts: List<ReplyContentPart> = emptyList(),
        val webReply: StoredWebReply? = null,
        val googleGrounded: GoogleGroundedReply? = null,
        val replyTokens: ReplyTokenTrace? = null,
    )

    private fun completionPayload(connection: ModelConnection, messages: JSONArray, streaming: Boolean) =
        jsonOf("model" to connection.model, "stream" to streaming, "messages" to messages).apply {
            if (connection.provider.template == ProviderTemplate.DEEPSEEK) put("thinking", jsonOf("type" to "disabled"))
        }

    suspend fun utility(connection: ModelConnection, messages: JSONArray, maxTokens: Int?): String {
        val payload = completionPayload(connection, messages, streaming = false)
        maxTokens?.let { payload.put("max_tokens", it) }
        val output = StringBuilder()
        val result = complete(connection.provider, payload, {}, { output.append(it) })
        result.interruption?.let { error(it) }
        return output.toString().trim().also { check(it.isNotBlank()) { "模型没有返回文本" } }
    }

    suspend fun scriptRaw(
        connection: ModelConnection, text: String, options: ScriptGenerationOptions,
        preset: PromptPreset?, sampling: EffectiveSampling, turn: InstructTurn,
        globalStops: CustomStoppingStrings, replyFormatting: ReplyFormatting,
        compiled: CompiledProviderRequest = connection.provider.compiledRequestOptions(),
        onPrompt: (JSONArray) -> Unit = {}, onIncomplete: (String) -> Unit = {},
        tokenCounter: TextTokenCounter? = null,
    ): String {
        val provider = connection.provider
        val expand = turn.expand
        val messages = scriptRawMessages(expand(text), expand(options.system))
        val prefill = expand(options.prefill)
        val baseTextPrompt = if (provider.usesTextCompletion) {
            val instruct = connection.resolvedTextTemplates().instruct.takeIf { options.instruct }
            if (provider.protocol == ApiProtocol.NOVELAI_TEXT && instruct == null) {
                TextCompletionPrompt(
                    messages.objects().joinToString("\n") { NovelAi.instruction(it.promptText()) } +
                        if (prefill.isEmpty()) "" else "\n$prefill",
                    emptyList(),
                )
            } else scriptRawTextPrompt(messages, instruct, turn, prefill)
        } else null
        val payload = completionPayload(connection, messages, streaming = false)
        val sampler = connection.textSampler()?.json()
        preset?.applySampling(payload, provider.template)
        (sampling.temperature.toDoubleOrNull()?.also { sampler?.remove(TextSamplerField.DYNAMIC.key) }
            ?: sampler?.opt(TextSamplerField.TEMPERATURE.key))?.let { payload.put("temperature", it) }
        (sampling.topP.toDoubleOrNull() ?: sampler?.opt(TextSamplerField.TOP_P.key))?.let { payload.put("top_p", it) }
        (options.length ?: sampling.maxTokens.toIntOrNull() ?: sampler?.optInt(TextSamplerField.MAX_OUTPUT.key)?.takeIf { it > 0 })
            ?.let { payload.put("max_tokens", it) }
        sampler?.let { payload.put(TEXT_SAMPLER_PAYLOAD, it) }
        if (!compiled.overridesBodyField(LOGIT_BIAS)) connection.compileTokenBias()?.let { payload.put(LOGIT_BIAS, it) }
        val stops = (globalStops.render(expand) + CustomStoppingStrings(options.stops).render(expand)).distinct()
        val textPrompt = baseTextPrompt?.let {
            if (provider.protocol == ApiProtocol.NOVELAI_TEXT) it.copy(
                text = NovelAi.promptText(connection.model, it.text),
                stopTokens = NovelAi.encodeStops(connection.model, stops + it.stops, tokenCounter?.encode),
            ) else it
        }
        val result = complete(provider, payload, {}, {}, textPrompt = textPrompt, customStops = stops,
            assistantPrefill = prefill.takeIf { textPrompt == null }.orEmpty(),
            onRequest = { rows -> onPrompt(textPrompt?.copy(stops = (stops + textPrompt.stops).distinct())?.inspection()
                ?: inspectedPrompt(rows)) }, requestOptions = compiled)
        val format = replyFormatting.reasoning.let {
            it.copy(prefix = expand(it.prefix), suffix = expand(it.suffix), separator = expand(it.separator))
        }.takeIf { replyFormatting.parseReasoning && it.prefix.isNotEmpty() && it.suffix.isNotEmpty() }
        val body = StringBuilder()
        val output = FormattedReplyStream(prefill, showPrefix = false, format, {}, { body.append(it) })
        output.acceptNativeReasoning(result.visibleReasoning)
        output.accept(result.visibleText)
        output.finish()
        val incomplete = completionInterruption(result)
        val reply = body.toString().removePrefix(output.hiddenPrefix).substringBefore("<|endoftext|>").trim()
            .let { if (options.trim) trimScriptReplyNames(it, turn.user, turn.character) else it }
        check(reply.isNotBlank()) { incomplete ?: "模型没有返回文本" }
        incomplete?.let(onIncomplete)
        return reply
    }

    private fun completionInterruption(result: CompletionAccumulator): String? = result.interruption ?: when (result.finishReason) {
        "length" -> "回复达到最大输出 Token"
        "continuation" -> "模型暂停了长回复"
        "content_filter" -> "模型的内容过滤结束了回复"
        else -> null
    }

    internal suspend fun complete(provider: Provider, payload: JSONObject, onReasoning: (String) -> Unit,
                                 token: (String) -> Unit, acceptTools: Boolean = false,
                                 onRetry: () -> Unit = {}, textPrompt: TextCompletionPrompt? = null,
                                 customStops: List<String> = emptyList(), assistantPrefill: String = "",
                                 onRequest: (JSONArray) -> Unit = {},
                                 continuationFallback: String? = null, requireNativeContinuation: Boolean = false,
                                 requestOptions: CompiledProviderRequest = provider.compiledRequestOptions(),
                                 onQueueProgress: (String) -> Unit = {}): CompletionAccumulator {
        var sendStops = true
        var nativePrefill = true
        val requestedProbabilities = payload.has("logprobs") || requestOptions.requestsProbabilities
        var sendProbabilities = true
        while (true) {
            val body = if (customStops.isEmpty() && assistantPrefill.isEmpty() && continuationFallback == null &&
                !requireNativeContinuation && !requestedProbabilities && !requestOptions.hasOverrides) payload
                else JSONObject(payload, payload.keys().asSequence().toList().toTypedArray())
            if (sendStops && customStops.isNotEmpty()) body.put("stop", JSONArray(customStops))
            if (!sendProbabilities) {
                body.remove("logprobs")
                body.remove("top_logprobs")
            }
            val messages = body.optJSONArray("messages") ?: JSONArray()
            if (assistantPrefill.isNotBlank()) {
                val starter = if (nativePrefill) jsonOf("role" to "assistant", "content" to assistantPrefill).apply {
                    if (provider.template == ProviderTemplate.DEEPSEEK) put("prefix", true)
                } else jsonOf("role" to "user", "content" to "请用下面的文字作为下一条回复的开头，然后继续回复。\n$assistantPrefill")
                body.put("messages", JSONArray(messages.objects()).put(starter))
            }
            if (continuationFallback != null || requireNativeContinuation) {
                if (!nativePrefill) {
                    body.put("messages", JSONArray(messages.objects()).put(jsonOf("role" to "user", "content" to continuationFallback)))
                } else if (provider.template == ProviderTemplate.DEEPSEEK) {
                    val rows = messages.objects()
                    val last = rows.lastOrNull()
                    check(last?.string("role") == "assistant") { "当前提示词预设没有可续写的角色前缀" }
                    requireNotNull(last)
                    val prefix = JSONObject(last, last.keys().asSequence().toList().toTypedArray()).put("prefix", true)
                    body.put("messages", JSONArray(rows.dropLast(1)).put(prefix))
                }
            }
            try {
                return completeExchange(provider, body, onReasoning, token, acceptTools, onRetry, textPrompt, customStops, sendStops,
                    sendProbabilities, requestOptions, onRequest, onQueueProgress)
            } catch (e: HttpRequestError) {
                if (e.status !in setOf(400, 422)) throw e
                when {
                    sendStops && customStops.isNotEmpty() && stopParameter.containsMatchIn(e.detail) &&
                        unsupportedParameter.containsMatchIn(e.detail) -> sendStops = false
                    requestedProbabilities && sendProbabilities && logprobsParameter.containsMatchIn(e.detail) &&
                        unsupportedParameter.containsMatchIn(e.detail) -> sendProbabilities = false
                    nativePrefill && !requireNativeContinuation && (assistantPrefill.isNotBlank() || continuationFallback != null) &&
                        rejectedPrefill.containsMatchIn(e.detail) -> nativePrefill = false
                    else -> throw e
                }
                onRetry()
            }
        }
    }

    private suspend fun completeExchange(provider: Provider, payload: JSONObject, onReasoning: (String) -> Unit,
                                         token: (String) -> Unit, acceptTools: Boolean, onRetry: () -> Unit,
                                         textPrompt: TextCompletionPrompt?, stops: List<String>, sendStops: Boolean,
                                         sendProbabilities: Boolean, requestOptions: CompiledProviderRequest,
                                         onRequest: (JSONArray) -> Unit, onQueueProgress: (String) -> Unit): CompletionAccumulator =
        MultiKeyManager.executeWithRotation(provider) { activeProvider, _ ->
            completeExchangeOnce(activeProvider, payload, onReasoning, token, acceptTools, onRetry, textPrompt,
                stops, sendStops, sendProbabilities, requestOptions, onRequest, onQueueProgress)
        }

    private suspend fun completeExchangeOnce(provider: Provider, payload: JSONObject, onReasoning: (String) -> Unit,
                                             token: (String) -> Unit, acceptTools: Boolean, onRetry: () -> Unit,
                                             textPrompt: TextCompletionPrompt?, stops: List<String>, sendStops: Boolean,
                                             sendProbabilities: Boolean, requestOptions: CompiledProviderRequest,
                                             onRequest: (JSONArray) -> Unit, onQueueProgress: (String) -> Unit): CompletionAccumulator {
        val openAiMainPath = provider.protocol in setOf(ApiProtocol.OPENAI_COMPATIBLE, ApiProtocol.OPENROUTER, ApiProtocol.AZURE_OPENAI) ||
            (provider.protocol == ApiProtocol.VERTEX_AI && VertexAiAdapter.isOpenAiCompatEndpoint(provider.endpoint))
        if (!openAiMainPath) {
            onRequest(payload.optJSONArray("messages") ?: JSONArray())
            return ProviderProtocols.completeProtocol(provider, payload, onReasoning, token, acceptTools, onRetry, textPrompt,
                stops, sendStops, onQueueProgress)
        }
        ProviderProtocols.enrichOpenAiPayload(provider, payload)
        if (sendStops && payload.has("stop")) {
            val limit = when (provider.template) {
                ProviderTemplate.DEEPSEEK -> 16
                ProviderTemplate.GOOGLE -> 5
                else -> 4
            }
            payload.put("stop", JSONArray(payload.optJSONArray("stop").strings().take(limit)))
        }
        if (requestOptions.hasOverrides) {
            if (requestOptions.processesMessages) {
                payload.optJSONArray("messages")?.let { payload.put("messages", requestOptions.processMessages(it)) }
            }
            requestOptions.applyTo(payload, buildSet {
                if (!sendStops) add("stop")
                if (!sendProbabilities) addAll(listOf("logprobs", "top_logprobs"))
            })
        }
        onRequest(payload.optJSONArray("messages") ?: JSONArray())
        val hydrogen = payload.string("model").substringAfterLast('/').equals("gemini-4-hydrogen", ignoreCase = true)
        val hostedSearch = payload.optJSONArray("tools")?.objects().orEmpty().any {
            it.string("type") in setOf("deepseek_web_search", "openrouter:web_search")
        } || provider.protocol == ApiProtocol.OPENROUTER && payload.string("model").endsWith(":online", true)
        var result = readCompletion(provider, payload, onReasoning, token, acceptTools,
            readFullStream = hydrogen || payload.has("modalities") || hostedSearch || payload.has("logprobs"), stops = stops,
            requestOptions = requestOptions)
        if (result.hasOutput || result.reachedTextStop) return result

        if (hydrogen && payload.optBoolean("stream")) {
            val retry = when {
                result.finishReason in setOf("", "stop") -> "stream" to false
                result.finishReason == "length" && provider.template == ProviderTemplate.GOOGLE && payload.has("max_tokens") ->
                    "reasoning_effort" to "low"
                else -> null
            }
            if (retry != null) {
                val retryPayload = JSONObject(payload, payload.keys().asSequence().toList().toTypedArray())
                    .put(retry.first, retry.second)
                onRetry()
                result = readCompletion(provider, retryPayload, onReasoning, token, acceptTools, readFullStream = true, stops = stops,
                    requestOptions = requestOptions)
            }
        }

        if (!result.hasOutput) {
            when (result.finishReason) {
                "length" -> if (hydrogen) {
                    error("Gemini 在生成正文前用完了 Token 预算，请检查聊天设置或提示词预设中的最大输出 Token")
                }
                "content_filter" -> error("模型的内容过滤拦截了回复")
            }
        }
        return result
    }

    private suspend fun readCompletion(provider: Provider, payload: JSONObject, onReasoning: (String) -> Unit,
                                       token: (String) -> Unit, acceptTools: Boolean,
                                       readFullStream: Boolean, stops: List<String>, requestOptions: CompiledProviderRequest): CompletionAccumulator {
        val result = CompletionAccumulator(stops)
        if (payload.has("logprobs")) result.tokenCollector = ReplyTokenCollector()
        fun redact(detail: String) = requestOptions.redact(detail.redactProviderSecrets(provider))
        fun appendResponse(json: JSONObject, streaming: Boolean) {
            val detail = json.optJSONObject("error")?.string("message") ?: (json.opt("error") as? String).orEmpty()
            if (detail.isNotBlank()) {
                val message = redact(detail).take(250)
                if (!streaming || !result.hasReceivedReply) error(message)
                result.interruption = message
                return
            }
            result.usage = result.usage.update(ResponseTokens.openAi(json))
            val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return
            result.tokenCollector?.accept(choice.optJSONObject("logprobs"))
            if (streaming) choice.optJSONObject("delta")?.let {
                result.append(it, acceptTools, onReasoning, token)
                result.appendOpenAiImages(it)
                result.appendCitations(it.optJSONArray("annotations"))
                result.captureWebTranscript(it.optJSONArray("server_tool_calls"))
            }
            choice.optJSONObject("message")?.let {
                if (!streaming || (!result.hasReceivedText && !result.hasToolCalls && !result.reachedTextStop)) {
                    result.append(it, acceptTools, onReasoning, token)
                }
                result.appendOpenAiImages(it)
                result.appendCitations(it.optJSONArray("annotations"))
                result.captureWebTranscript(it.optJSONArray("server_tool_calls"))
            }
            choice.string("finish_reason").trim().lowercase().takeIf(String::isNotBlank)?.let { result.finishReason = it }
        }

        var streamOpened = false
        var streamConsumed = false
        try {
            val url = if (provider.protocol == ApiProtocol.AZURE_OPENAI) {
                AzureOpenAiAdapter.buildChatCompletionsUrl(provider, payload.string("model"))
            } else {
                val endpoint = base(provider).let { root ->
                    val rows = payload.optJSONArray("messages")
                    val beta = provider.template == ProviderTemplate.DEEPSEEK &&
                        rows?.optJSONObject(rows.length() - 1)?.optBoolean("prefix") == true
                    if (beta && !root.endsWith("/beta")) "$root/beta" else root
                }
                "$endpoint/chat/completions"
            }
            val baseHeaders = when (provider.protocol) {
                ApiProtocol.AZURE_OPENAI -> AzureOpenAiAdapter.buildHeaders(provider)
                ApiProtocol.VERTEX_AI -> VertexAiAdapter.buildHeaders(provider.apiKey)
                else -> ProviderProtocols.buildOpenAiHeaders(provider)
            }
            val headers = requestOptions.headersWith(baseHeaders)
            request(url, payload, headers,
                readTimeout = if (payload.has("modalities")) 180_000 else 90_000, redactError = ::redact) { input, type ->
                if (!type.contains("text/event-stream", ignoreCase = true)) {
                    appendResponse(JSONObject(input.readLimited().toString(Charsets.UTF_8)), streaming = false)
                    return@request
                }

                streamOpened = true
                val reader = input.bufferedReader(Charsets.UTF_8)
                val data = mutableListOf<String>()
                fun handle(atEnd: Boolean = false): Boolean {
                    val event = data.joinToString("\n")
                    data.clear()
                    if (event.trim() == "[DONE]") return true
                    if (event.isBlank()) return false
                    val json = try {
                        JSONObject(event)
                    } catch (_: JSONException) {
                        // An unframed event at EOF may have been cut off by the connection.
                        val message = if (atEnd) STREAM_INTERRUPTED else "模型返回的流式分片格式无效"
                        if (!atEnd && !result.hasReceivedReply) error(message)
                        result.interruption = message
                        return true
                    }
                    appendResponse(json, streaming = true)
                    return result.reachedTextStop || result.interruption != null || (!readFullStream && result.finishReason.isNotBlank())
                }
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank() && handle()) {
                        streamConsumed = true
                        return@request
                    }
                    if (line.startsWith("data:")) data += line.removePrefix("data:").removePrefix(" ")
                }
                if (!handle(atEnd = true) && result.finishReason.isBlank()) result.interruption = STREAM_INTERRUPTED
                streamConsumed = true
            }
        } catch (e: IOException) {
            if (!streamOpened) throw e
            if (!streamConsumed) result.interruption = STREAM_INTERRUPTED
        }
        return result.also { it.finishText(token) }
    }

    internal data class PreparedPrompt(
        val messages: JSONArray, val loreEffects: Map<String, LoreEffect>, val automationIds: Set<String>,
        val loreBudget: LoreBudgetUsage,
        val variables: MacroVariableChanges,
        val textPrompt: TextCompletionPrompt? = null,
        val stops: List<String> = emptyList(),
        val replyPrefix: String = "",
        val reasoningFormat: ReasoningFormat? = null,
        val continuationFallback: String? = null,
    )

    private fun modelHistory(history: List<Message>, vectors: VectorContext, toolConnection: ModelConnection?,
                             includeNarrator: Boolean) =
        history.withIndex().filter { (index, message) ->
            index !in vectors.replacedIndices &&
                message.isPromptMessage(includeNarrator) { turn -> toolConnection?.let(turn::accepts) == true }
        }

    internal fun prompt(
        card: Card, history: List<Message>, leadingSystemOnly: Boolean,
        books: List<WorldBook>, group: GroupPrompt?, persona: PersonaProfile, options: ConversationOptions,
        loreSettings: LoreSettings = LoreSettings(), trigger: String = "normal", preset: PromptPreset? = null,
        textRules: RegexRuntime = RegexRuntime.EMPTY, includeReasoning: Boolean = false,
        scriptVariables: Map<String, String> = emptyMap(),
        images: Map<Int, List<String>> = emptyMap(),
        vectorOptions: VectorPreferences = VectorPreferences(),
        vectors: VectorContext = VectorContext(),
        documents: Map<Int, String> = emptyMap(),
        toolConnection: ModelConnection? = null,
        sharedMemory: String = "",
        loreBudget: LoreBudget = LoreBudget(3_000, estimatedTokens),
        captionSettings: CaptionSettings = CaptionSettings(),
        model: String = "",
        chatId: String = "",
        instruct: InstructTemplate? = null,
        textContext: TextContextTemplate? = null,
        textSystem: TextSystemSettings? = null,
        customStops: CustomStoppingStrings = CustomStoppingStrings(),
        replyFormatting: ReplyFormatting = ReplyFormatting(),
        textCompletion: Boolean = false,
        historyWindow: List<IndexedValue<Message>>? = null,
        windowOffset: Int = 0,
        includeExamples: Boolean = true,
        hasChatNameField: Boolean = false,
        acceptsChatContinuation: Boolean = true,
        geminiReplay: Map<Int, JSONArray> = emptyMap(),
        webReplay: Map<Int, Pair<String, JSONArray>> = emptyMap(),
        allowReplyPrefix: Boolean = true,
        forcedContinuation: String? = null,
        preserveSpeakerRole: Boolean = false,
        quietPrompt: ScriptQuietPrompt? = null,
        novelAiPreamble: String? = null,
        usePlainTextFormat: Boolean = false,
        loreTagIds: Set<String> = LibraryPreferences().loreTags(card),
        preferCharacterPrompt: Boolean = textSystem?.preferCharacterPrompt ?: true,
        preferCharacterPostHistory: Boolean = textSystem?.preferCharacterPostHistory ?: true,
        relaxGroupTrimming: Boolean = false,
        collapseNewlines: Boolean = false,
    ): PreparedPrompt {
        val impersonating = trigger == ReplyAction.IMPERSONATE.trigger
        val textFormat = instruct ?: plainTextInstruct.takeIf { textContext != null || novelAiPreamble != null || usePlainTextFormat }
        val chosenHistory = historyWindow ?: modelHistory(history, vectors, toolConnection, includeNarrator = textFormat != null)
        val firstIncluded = chosenHistory.firstOrNull { !it.value.excludedFromContext }?.index
        val macros = ConversationMacros(card, persona, history, group, options, scriptVariables, model, chatId, trigger, firstIncluded)
        val deferredOutlets = linkedMapOf<String, String>()
        fun deferOutlet(key: String): String =
            if (key.isBlank()) "" else deferredOutlets.getOrPut(key) { "\uFDD0${newId()}\uFDD0" }
        fun renderLore(text: String) = macros.expand(text, outlet = ::deferOutlet)
        val note = options.authorNote ?: AuthorNote()
        val noteDue = note.due(history)
        val sections = preset?.takeIf { textContext == null && textSystem == null }?.prompts
            ?.filter { it.applies(trigger) && !it.atDepth }?.mapNotNull { it.section }?.toSet()
        fun inPreset(section: PromptSection) = sections == null || section in sections
        val noteText = if (noteDue) card.authorNote.combine(note.text) else ""
        val personaText = persona.description.trim()
        val noteTop = personaText.takeIf { noteDue && persona.position == PersonaPosition.NOTE_TOP }
        val noteBottom = personaText.takeIf { noteDue && persona.position == PersonaPosition.NOTE_BOTTOM }
        val documentText = vectors.documents.joinToString("\n\n") { "${it.title}\n${it.fragments.joinToString("\n…\n")}" }
        val memory = options.memory
        val rememberedSummary = if (memory.enabled || memory.scanLore) history.currentSummary()?.summary?.text.orEmpty() else ""
        val scan = listOfNotNull(
            personaText.takeIf { persona.position == PersonaPosition.DEPTH },
            noteTop.takeIf { note.scan },
            noteText.takeIf { note.scan },
            noteBottom.takeIf { note.scan },
        ).joinToString("\n", transform = ::renderLore)
        val loreTrigger = if (group != null && trigger == ReplyAction.REGENERATE.trigger) ReplyAction.NORMAL.trigger else trigger
        val evaluation = evaluateLore(card, books, history.filterNot(Message::excludedFromContext).map { message ->
            val observations = message.displayImages.mapNotNull(message::captionFor)
            if (observations.isEmpty() && message.wireText == null) message
            else message.copy(text = (listOf(message.requestText) + observations).filter(String::isNotBlank).joinToString("\n"))
        },
            loreSettings, persona, options, listOf(scan, documentText.takeIf { vectorOptions.files.scanLore }.orEmpty(),
                rememberedSummary.takeIf { memory.scanLore }.orEmpty(),
                quietPrompt?.text?.let(::renderLore).orEmpty())
                .filter(String::isNotBlank).joinToString("\n"), loreTrigger, semanticMatches = vectors.lore, budget = loreBudget,
            render = ::renderLore, tagIds = loreTagIds, renderEntry = { entry, source ->
                val writes = when (entry.position) {
                    LorePosition.BEFORE -> inPreset(PromptSection.LORE_BEFORE)
                    LorePosition.AFTER -> inPreset(PromptSection.LORE_AFTER)
                    LorePosition.AUTHOR_TOP, LorePosition.AUTHOR_BOTTOM ->
                        noteDue && (note.position != NotePosition.DEPTH || inPreset(PromptSection.HISTORY))
                    LorePosition.DEPTH -> inPreset(PromptSection.HISTORY)
                    LorePosition.EXAMPLE_TOP, LorePosition.EXAMPLE_BOTTOM ->
                        includeExamples && instruct?.skipExamples != true && inPreset(PromptSection.EXAMPLES)
                    LorePosition.OUTLET -> true
                }
                val candidate = MacroVariables(macros.variables::get)
                val text = macros.expand(source, write = writes, scope = candidate, outlet = ::deferOutlet)
                LoreExpansion(text) { macros.variables.apply(candidate.changes()) }
            })
        val automationIds = evaluation.automationIds
        val lore = evaluation.entries
            .filter { noteDue || it.position !in setOf(LorePosition.AUTHOR_TOP, LorePosition.AUTHOR_BOTTOM) }
            .map { entry ->
                val depth = entry.depth.takeIf { entry.position == LorePosition.DEPTH }
                entry.copy(content = textRules.transform(entry.content, RegexSite.WORLD, RegexStage.REQUEST, depth))
            }
            .filter { it.content.isNotBlank() }
        val outlets = lore.filter { it.position == LorePosition.OUTLET && it.rules.outlet.isNotBlank() }
            .groupBy { it.rules.outlet }
            .mapValues { (_, entries) -> entries.joinToString("\n", transform = LoreEntry::content) }
        fun expanded(text: String, overrides: (String) -> String? = { null }, member: Card = card, write: Boolean = true) =
            macros.expand(text, member, overrides, write) { outlets[it].orEmpty() }
        fun positioned(vararg positions: LorePosition) =
            lore.filter { it.position in positions }.joinToString("\n") { it.content }
        val inNote by lazy { listOf(
            positioned(LorePosition.AUTHOR_TOP),
            noteTop?.let { expanded(it) },
            expanded(noteText),
            noteBottom?.let { expanded(it) },
            positioned(LorePosition.AUTHOR_BOTTOM),
        ).filterNotNull().filter(String::isNotBlank).joinToString("\n") }
        val relevantHistory = chosenHistory.map { it.value }
        val roster = group?.let {
            val names = "群聊「${it.group.name}」的成员：${it.roster.joinToString("、")}。"
            if (impersonating) names else "$names 只生成 ${card.name} 的回复。"
        }
        val joint = group?.takeUnless { it.group.promptMode == GroupPromptMode.SWAP }
        val orderedCharacters = joint?.let {
            (it.definitions + card).sortedBy { character -> it.group.memberIds.indexOf(character.id) }
        } ?: listOf(card)
        val summary = rememberedSummary.takeIf { memory.enabled && it.isNotBlank() }?.let(memory::render).orEmpty()
        val recalled = vectors.messages.takeIf { it.isNotEmpty() }
            ?.joinToString("\n") { "${it.speaker}: ${it.text}" }?.let(vectorOptions::render).orEmpty()
        // Reference data enters after macro expansion.
        val documentToken = documentText.takeIf(String::isNotBlank)?.let { "\uFDD0${newId()}\uFDD0" }
        val sharedMemoryToken = sharedMemory.takeIf { !impersonating && it.isNotBlank() }?.let { "\uFDD0${newId()}\uFDD0" }
        val documentPrompt = documentToken?.let(vectorOptions.files::render).orEmpty()
        fun insertReferenceText(messages: JSONArray): JSONArray {
            val replacements = buildMap {
                deferredOutlets.forEach { (key, marker) -> put(marker, outlets[key].orEmpty()) }
                documentToken?.let { put(it, documentText) }
                sharedMemoryToken?.let { put(it, sharedMemory) }
            }
            fun replaceText(row: JSONObject, key: String) {
                val text = row.opt(key) as? String ?: return
                val resolved = referenceMarker.replace(text) { replacements[it.value] ?: it.value }
                if (resolved != text) row.put(key, resolved)
            }
            if (replacements.isNotEmpty()) messages.objects().forEach { message ->
                replaceText(message, "content")
                message.optJSONArray("content")?.objects()?.filter { it.string("type") == "text" }
                    ?.forEach { replaceText(it, "text") }
            }
            return messages
        }
        val turn = InstructTurn(persona.name, card.name, trigger, group != null,
            quiet = trigger == "quiet" && quietPrompt?.asCharacter != true,
            expand = { expanded(it, write = false) }, quietName = quietPrompt?.name ?: "System")
        val layout = textContext?.let {
            TextContextLayout(it, expanded(it.exampleSeparator, write = false), expanded(it.chatStart, write = false),
                if (relaxGroupTrimming) emptyList() else group?.roster.orEmpty())
        }
        val reasoningFormat = replyFormatting.reasoning.let {
            it.copy(prefix = expanded(it.prefix, write = false), suffix = expanded(it.suffix, write = false),
                separator = expanded(it.separator, write = false))
        }
        val replyPrefix = if (allowReplyPrefix && trigger in setOf("normal", "regenerate", "swipe")) {
            expanded(replyFormatting.prefix, write = false).take(REPLY_PREFIX_LIMIT).takeIf(String::isNotBlank).orEmpty()
        } else ""
        fun prepared(wire: JSONArray, continuationFallback: String? = null): PreparedPrompt {
            quietPrompt?.text?.let { expanded(it) }?.takeIf(String::isNotBlank)?.let { text ->
                val content = if (novelAiPreamble != null && instruct == null && !quietPrompt.asCharacter) {
                    NovelAi.instruction(text)
                } else text
                wire.put(jsonOf("role" to if (leadingSystemOnly) "user" else "system", "content" to content).apply {
                    if (textFormat != null) put("instructTurn", true)
                })
            }
            val resolved = insertReferenceText(wire)
            if (forcedContinuation != null) {
                check(resolved.optJSONObject(resolved.length() - 1)?.string("role") == "assistant") {
                    "当前提示词预设未包含可续写的聊天历史"
                }
            }
            val formatted = textFormat?.format(resolved, turn, layout, plain = instruct == null)
                ?: if (textCompletion && (forcedContinuation != null || replyPrefix.isNotEmpty() || collapseNewlines)) {
                    ProviderProtocols.flattenMessagesToPrompt(resolved.objects(), preserveFinalAssistant = forcedContinuation != null)
                } else null
            val normalized = if (formatted != null && collapseNewlines && forcedContinuation == null) {
                formatted.copy(text = collapseChatNewlines(formatted.text))
            } else formatted
            val prefixed = if (normalized != null && replyPrefix.isNotEmpty()) {
                val joiner = if (normalized.text.endsWith(':') && !replyPrefix.first().isWhitespace()) " " else ""
                normalized.copy(text = normalized.text + joiner + replyPrefix)
            } else normalized
            val textPrompt = if (prefixed != null && (novelAiPreamble != null || usePlainTextFormat)) {
                val stops = if (turn.quiet) emptyList() else {
                    val speaker = if (impersonating) persona.name else card.name
                    val groupNames = if (relaxGroupTrimming) emptyList() else group?.roster.orEmpty()
                    (listOf(persona.name, card.name) + groupNames)
                        .filter { it.isNotBlank() && it != speaker }.map { "\n$it:" }
                }
                val text = novelAiPreamble?.let { NovelAi.promptText(model, prefixed.text, expanded(it, write = false)) }
                    ?: prefixed.text
                prefixed.copy(text = text,
                    stops = (prefixed.stops + stops).distinct())
            } else prefixed
            val stops = (customStops.render { expanded(it, write = false) } +
                CustomStoppingStrings(quietPrompt?.stops.orEmpty()).render { expanded(it, write = false) }).distinct()
            return PreparedPrompt(resolved, evaluation.effects, automationIds, evaluation.budget, macros.variables.changes(),
                textPrompt, stops, replyPrefix, reasoningFormat.takeIf {
                    replyFormatting.parseReasoning && it.prefix.isNotEmpty() && it.suffix.isNotEmpty()
                }, continuationFallback)
        }
        fun memoryAt(position: MemoryPosition) = listOf(summary.takeIf { memory.position == position },
            recalled.takeIf { vectorOptions.position == position }, documentPrompt.takeIf { vectorOptions.files.position == position })
            .filterNotNull().filter(String::isNotBlank).joinToString("\n\n") { expanded(it, write = false) }
        val topMemory = memoryAt(MemoryPosition.BEFORE_MAIN)
        val bottomMemory = listOfNotNull(memoryAt(MemoryPosition.AFTER_MAIN).takeIf(String::isNotBlank), sharedMemoryToken)
            .joinToString("\n\n")
        val atDepth by lazy { buildList {
            lore.filter { it.position == LorePosition.DEPTH }.forEach { add(PromptInjection(it.content, it.depth, it.role)) }
            orderedCharacters.filter { it.id == card.id || joint?.group?.mutedIds?.contains(it.id) != true }.forEach { member ->
                val text = expanded(member.depthPrompt.trim(), member = member)
                if (text.isNotBlank()) add(PromptInjection(text, member.depthPromptDepth, member.depthPromptRole.wire))
            }
            if (persona.position == PersonaPosition.DEPTH && personaText.isNotBlank()) {
                add(PromptInjection(expanded(personaText), persona.depth, persona.role.wire))
            }
            if (note.position == NotePosition.DEPTH && inNote.isNotBlank()) add(PromptInjection(inNote, note.depth, note.role.wire))
            if (memory.position == MemoryPosition.DEPTH && summary.isNotBlank()) {
                add(PromptInjection(expanded(summary, write = false), memory.depth, memory.role.wire))
            }
            if (vectorOptions.position == MemoryPosition.DEPTH && recalled.isNotBlank()) {
                add(PromptInjection(expanded(recalled, write = false), vectorOptions.depth, vectorOptions.role.wire))
            }
            if (vectorOptions.files.position == MemoryPosition.DEPTH && documentPrompt.isNotBlank()) {
                add(PromptInjection(expanded(documentPrompt, write = false), vectorOptions.files.depth, vectorOptions.files.role.wire))
            }
        } }
        fun characterField(fieldName: String, write: Boolean, read: (Card) -> String) =
            orderedCharacters.mapNotNull { member ->
                val value = read(member).takeIf(String::isNotBlank) ?: return@mapNotNull null
                val prefix = joint?.group?.prefix.orEmpty().replace("<FIELDNAME>", fieldName, ignoreCase = true)
                val suffix = joint?.group?.suffix.orEmpty().replace("<FIELDNAME>", fieldName, ignoreCase = true)
                expanded("$prefix$value$suffix", member = member, write = write)
            }.joinToString("\n")
        fun sectionText(section: PromptSection, write: Boolean) = when (section) {
            PromptSection.LORE_BEFORE -> positioned(LorePosition.BEFORE)
            PromptSection.LORE_AFTER -> positioned(LorePosition.AFTER)
            PromptSection.DESCRIPTION -> characterField("description", write, Card::description)
            PromptSection.PERSONALITY -> characterField("personality", write, Card::personality)
            PromptSection.SCENARIO -> if (options.scenario.isNotBlank()) expanded(options.scenario, write = write)
                else characterField("scenario", write, Card::scenario)
            PromptSection.EXAMPLES -> if (!includeExamples) ""
                else if (options.exampleOverride.isNotBlank()) expanded(options.exampleOverride, write = write)
                else characterField("mes_example", write, Card::examples)
            PromptSection.PERSONA -> if (persona.position == PersonaPosition.PROMPT) expanded(personaText, write = write) else ""
            else -> ""
        }
        val systemPrompt = options.systemPromptOverride.ifBlank { if (preferCharacterPrompt) card.instructions else "" }
        val postHistory = if (preferCharacterPostHistory) card.postHistory else ""
        val visiblePositions = relevantHistory.indices.filter { relevantHistory[it].toolTurn == null }
        val captions = promptImageCaptions(relevantHistory, captionSettings, persona.name, card.name)
        val visibleCount = visiblePositions.size
        var historyIndex = 0
        val firstTurn = history.firstOrNull { !it.excludedFromContext && it.toolTurn == null }
        val reasoningIndices = if (includeReasoning) {
            relevantHistory.indices.reversed().asSequence().filter {
                relevantHistory[it].role == "assistant" && !relevantHistory[it].reasoning.isNullOrBlank()
            }.take(replyFormatting.maxReasoning).toSet()
        } else emptySet()
        val namesMode = preset?.chatCompletion?.names.takeIf { !textCompletion && textFormat == null }
        fun historyLine(index: Int, entry: Message): PromptLine {
            entry.toolTurn?.let { return PromptLine("assistant", it.content, wire = it.messages()) }
            val otherSpeaker = entry.role == "assistant" && !entry.isBy(card) &&
                (group != null || entry.groupTurnId != null && entry.name.isNotBlank())
            val legacySpeaker = otherSpeaker && textFormat == null && namesMode == null && !preserveSpeakerRole
            val role = if (legacySpeaker) "user" else entry.role
            val depth = visibleCount - 1 - historyIndex++ - if (trigger == ReplyAction.CONTINUE.trigger) 1 else 0
            val body = forcedContinuation?.takeIf { entry === history.lastOrNull() }
                ?: entry.regexSite?.let { textRules.transform(entry.requestText, it, RegexStage.REQUEST, depth) } ?: entry.requestText
            val reasoning = entry.reasoning?.takeIf { index in reasoningIndices && role == "assistant" }
                ?.let { textRules.transform(it, RegexSite.REASONING, RegexStage.REQUEST, depth) }.orEmpty()
            val text = when {
                legacySpeaker -> "${entry.name}: $body"
                reasoning.isNotBlank() -> reasoningFormat.render(body, reasoning)
                else -> body
            }
            val slot = windowOffset + index
            val content = listOfNotNull(documents[slot], expanded(text, write = false).takeIf(String::isNotBlank), captions[index])
                .joinToString("\n\n")
            val line = PromptLine(role, content, if (captionSettings.textOnly) emptyList() else images[slot].orEmpty(),
                instructName = entry.name.takeIf { textFormat != null && it.isNotBlank() }, instructTurn = textFormat != null,
                instructFirst = textFormat != null && entry === firstTurn, geminiReplay = geminiReplay[slot],
                webReplay = webReplay[slot].takeIf { role == "assistant" })
            val defaultPrefix = namesMode == ChatSpeakerNames.DEFAULT && entry.name != persona.name &&
                (group != null || otherSpeaker || entry.raw?.let { JSONObject(it).string("force_avatar").isNotBlank() } == true)
            val speakerMode = namesMode ?: ChatSpeakerNames.CONTENT.takeIf { preserveSpeakerRole && otherSpeaker }
            return line.namedChatSpeaker(entry.name, speakerMode, hasChatNameField, defaultPrefix)
        }
        if (preset != null && textContext == null && textSystem == null) {
            val context = PresetPromptContext(card, persona, group, ::sectionText, { relevantHistory.mapIndexed(::historyLine) },
                { atDepth },
                { if (note.position != NotePosition.DEPTH && inNote.isNotBlank()) PromptLine(note.role.wire, inNote) else null },
                note.position, positioned(LorePosition.EXAMPLE_TOP), positioned(LorePosition.EXAMPLE_BOTTOM),
                trigger, leadingSystemOnly, { text, values -> expanded(text, values) }, topMemory, bottomMemory, systemPrompt,
                instruct, includeExamples, chatCompletion = !textCompletion && textFormat == null,
                hasApiNameField = hasChatNameField, acceptsChatContinuation = acceptsChatContinuation, postHistory = postHistory)
            val assembled = assemblePreset(preset, context)
            return prepared(assembled.messages, assembled.continuationFallback)
        }
        fun systemText(base: String, override: String): String {
            val original by lazy { expanded(base) }
            return if (override.isBlank()) original
                else expanded(override, { key -> if (key == "original") original else null })
        }
        fun textPresetRow(section: PromptSection) =
            preset?.prompts?.firstOrNull { it.section == section && it.applies(trigger) && !it.atDepth }
        val defaultInstruction = if (impersonating) "为 ${persona.name} 起草下一条聊天消息。"
            else "以 ${card.name} 的身份与 ${persona.name} 对话。保持角色一致，并用对方使用的语言回复。"
        val instruction by lazy {
            if (textSystem != null) {
                val inherited = textPresetRow(PromptSection.MAIN).takeIf { textSystem.selected == null }
                val base = textSystem.selected?.content ?: inherited?.text ?: defaultInstruction
                val allowCard = preferCharacterPrompt && inherited?.forbidOverrides != true
                val override = options.systemPromptOverride.ifBlank { card.instructions.takeIf { allowCard }.orEmpty() }
                if (textSystem.enabled) systemText(base, override) else ""
            } else if (impersonating) defaultInstruction
            else systemPrompt.takeIf(String::isNotBlank)?.let { expanded(it) } ?: defaultInstruction
        }
        val afterHistory by lazy {
            if (textSystem == null) expanded(postHistory)
            else if (textSystem.enabled) {
                val inherited = textPresetRow(PromptSection.AFTER).takeIf { textSystem.selected == null }
                val base = textSystem.selected?.postHistory ?: inherited?.text.orEmpty()
                systemText(base, postHistory.takeUnless { inherited?.forbidOverrides == true }.orEmpty())
            } else ""
        }
        val messages = JSONArray()
        var storyInjection: PromptInjection? = null
        if (textContext != null && layout != null && textFormat != null) {
            fun anchors(vararg parts: String) = parts.filter(String::isNotBlank).joinToString("\n\n")
            fun exampleLore(position: LorePosition) {
                val text = positioned(position)
                if (text.isNotBlank()) messages.put(PromptLine("system", text, instructExample = true).json())
            }
            val main = textPresetRow(PromptSection.MAIN)
            val actualInstruction = when {
                main == null || textSystem != null -> instruction
                main.forbidOverrides || systemPrompt.isBlank() -> expanded(main.text)
                else -> expanded(systemPrompt, { key -> if (key == "original") expanded(main.text) else null })
            }
            val raw = if (instruct?.skipExamples == true) "" else sectionText(PromptSection.EXAMPLES, true)
            val examples = instructExampleRows(raw, persona.name, group?.roster.orEmpty() + card.name, layout.examplesSeparator)
            val story = StoryString.render(textContext.story, mapOf(
                "system" to actualInstruction,
                "description" to sectionText(PromptSection.DESCRIPTION, true),
                "personality" to sectionText(PromptSection.PERSONALITY, true),
                "scenario" to sectionText(PromptSection.SCENARIO, true),
                "persona" to sectionText(PromptSection.PERSONA, true),
                "char" to card.name, "user" to persona.name,
                "wiBefore" to positioned(LorePosition.BEFORE), "loreBefore" to positioned(LorePosition.BEFORE),
                "wiAfter" to positioned(LorePosition.AFTER), "loreAfter" to positioned(LorePosition.AFTER),
                "anchorBefore" to anchors(topMemory, if (note.position == NotePosition.BEFORE_SCENARIO) inNote else ""),
                "anchorAfter" to anchors(bottomMemory, roster.orEmpty(), if (note.position == NotePosition.AFTER_SCENARIO) inNote else ""),
                "mesExamples" to textFormat.format(JSONArray(examples), turn, layout,
                    plain = instruct == null, examplesOnly = true).text,
                "mesExamplesRaw" to raw,
            )) { expanded(it, write = false) }
            if (story.isNotBlank()) {
                if (textContext.inChat) storyInjection = PromptInjection(story, textContext.depth, textContext.role.wire)
                else messages.put(jsonOf("role" to "system", "content" to story, "instructStory" to true))
            }
            if (includeExamples && !textContext.skipExamples && instruct?.skipExamples != true) {
                exampleLore(LorePosition.EXAMPLE_TOP)
                examples.forEach(messages::put)
                exampleLore(LorePosition.EXAMPLE_BOTTOM)
            }
            if (layout.chatStart.isNotBlank()) messages.put(instructLiteralRow(layout.chatStart))
        } else {
            val system = listOf(
                topMemory,
                instruction,
                bottomMemory,
                roster,
                positioned(LorePosition.BEFORE),
                sectionText(PromptSection.DESCRIPTION, true),
                sectionText(PromptSection.PERSONALITY, true).takeIf(String::isNotBlank)?.let { "性格：$it" },
                if (note.position == NotePosition.BEFORE_SCENARIO) inNote else null,
                sectionText(PromptSection.SCENARIO, true).takeIf(String::isNotBlank)?.let { "场景：$it" },
                if (note.position == NotePosition.AFTER_SCENARIO) inNote else null,
                positioned(LorePosition.AFTER),
                sectionText(PromptSection.PERSONA, true).takeIf(String::isNotBlank)?.let { "${persona.name} 的设定：$it" },
                if (includeExamples && instruct == null) positioned(LorePosition.EXAMPLE_TOP) else null,
                if (instruct == null) sectionText(PromptSection.EXAMPLES, true).takeIf(String::isNotBlank)?.let { "对话示例：\n$it" } else null,
                if (includeExamples && instruct == null) positioned(LorePosition.EXAMPLE_BOTTOM) else null,
                atDepth.filter { leadingSystemOnly && it.role == "system" }.joinToString("\n") { it.text },
                if (leadingSystemOnly) afterHistory else null,
            ).filterNotNull().filter(String::isNotBlank).joinToString("\n\n")
            messages.put(jsonOf("role" to "system", "content" to system))
            if (includeExamples && instruct?.skipExamples == false) {
                val examples = sectionText(PromptSection.EXAMPLES, true)
                val parts = listOf(
                    PromptLine("system", positioned(LorePosition.EXAMPLE_TOP), instructExample = true),
                ) + instructExampleLines(examples, persona.name, group?.roster.orEmpty() + card.name) +
                    PromptLine("system", positioned(LorePosition.EXAMPLE_BOTTOM), instructExample = true)
                parts.filter(PromptLine::hasContent).forEach { messages.put(it.json()) }
            }
        }
        val continueAt = relevantHistory.lastIndex.takeIf {
            (textFormat != null || forcedContinuation != null) && trigger == ReplyAction.CONTINUE.trigger &&
                relevantHistory.lastOrNull()?.let { it.role == "assistant" && it.isBy(card) && it.requestText.isNotBlank() } == true
        }
        var prefill: PromptLine? = null
        val injections = (atDepth + listOfNotNull(storyInjection)).filterNot { leadingSystemOnly && it.role == "system" }
            .groupBy { injectionPosition(relevantHistory.size, visiblePositions, it.depth) }
        for (index in 0..relevantHistory.size) {
            injections[index].orEmpty().filter { it.text.isNotBlank() }.forEach {
                messages.put(jsonOf("role" to it.role, "content" to it.text))
            }
            val entry = relevantHistory.getOrNull(index) ?: continue
            val line = historyLine(index, entry)
            if (index == continueAt) {
                prefill = line.copy(images = emptyList())
                continue
            }
            if (line.hasContent) line.wireRows().forEach(messages::put)
        }
        if (group != null && relevantHistory.isEmpty() && !impersonating) {
            messages.put(jsonOf("role" to "user", "content" to "请以 ${card.name} 的身份开始群聊。"))
        }
        if (!leadingSystemOnly && afterHistory.isNotBlank()) {
            messages.put(jsonOf("role" to if (textSystem != null) "user" else "system", "content" to afterHistory))
        }
        val nudge = when (trigger) {
            ReplyAction.CONTINUE.trigger -> "接着写上一条回复，从原文结束的位置继续。避免重复已经写出的内容。"
            ReplyAction.IMPERSONATE.trigger -> if (textContext == null) {
                "用 ${persona.name} 的视角和语言风格写一条发言。请只输出消息正文，保留其他角色的发言空间。"
            } else null
            else -> null
        }
        if (prefill != null) messages.put(prefill.json())
        else if (nudge != null) messages.put(jsonOf("role" to if (leadingSystemOnly) "user" else "system", "content" to nudge))
        return prepared(messages)
    }

}

internal data class RemoteCard(val avatar: String, val name: String)

internal data class RemoteChatSummary(
    val fileName: String,
    val messageCount: Int,
    val fileSize: Long,
    val lastMessageMs: Long,
)

internal class TavernServer(address: String, private val handle: String, private val basicUser: String, basicPassword: String) {
    private val base = checkedAddress(address).toString().trimEnd('/')
    private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
    private val basicAuth = if (basicUser.isBlank()) emptyMap() else mapOf(
        "Authorization" to ("Basic " + Base64.encodeToString("$basicUser:$basicPassword".toByteArray(), Base64.NO_WRAP)),
    )
    private var csrf: String = ""

    private suspend fun bytes(path: String, payload: JSONObject? = null): ByteArray =
        request(base + path, payload, basicAuth + if (csrf.isBlank()) emptyMap() else mapOf("X-CSRF-Token" to csrf),
            cookies) { input, _ -> input.readLimited() }

    private suspend fun text(path: String, payload: JSONObject? = null) = bytes(path, payload).toString(Charsets.UTF_8)

    suspend fun list(password: String): List<RemoteCard> {
        loadToken()
        if (handle.isNotBlank()) {
            text("/api/users/login", jsonOf("handle" to handle, "password" to password))
            loadToken()
        }
        return JSONArray(text("/api/characters/all", JSONObject())).objects().mapNotNull {
            val avatar = it.string("avatar")
            val name = it.string("name")
            if (avatar.isBlank() || name.isBlank()) null else RemoteCard(avatar, name)
        }.sortedBy { it.name.lowercase() }
    }

    suspend fun download(remote: RemoteCard, includeChats: Boolean): Pair<CardImport, List<Chat>> {
        val id = stableId("$base\u0000$handle\u0000$basicUser\u0000${remote.avatar}")
        val downloaded = CardFile.read(bytes("/api/characters/export",
            jsonOf("avatar_url" to remote.avatar, "format" to "png")), id)
        val card = downloaded.copy(card = downloaded.card.withScriptAvatarKey(remote.avatar))
        if (!includeChats) return card to emptyList()
        val listing = text("/api/characters/chats", jsonOf("avatar_url" to remote.avatar, "simple" to true)).trim()
        if (listing.startsWith("{") && JSONObject(listing).optBoolean("error")) return card to emptyList()
        val chats = JSONArray(listing).objects().map { info ->
            val title = info.string("file_id").ifBlank { info.string("file_name").removeSuffix(".jsonl") }
            require(title.isNotBlank()) { "服务器返回了无效的聊天名称" }
            val rows = JSONArray(text("/api/chats/get", jsonOf(
                "avatar_url" to remote.avatar, "file_name" to title, "ch_name" to remote.name,
            ))).objects()
            Chat.import(id, title, rows, stableId("$id\u0000$title"))
        }
        return card to chats
    }

    suspend fun documentFile(name: String): ByteArray {
        require(originalChatFilename("user/files/$name") == name) { "原版聊天文档路径无效" }
        return request("$base/user/files/$name", headers = basicAuth + ("Accept" to "text/plain"), cookies = cookies) { input, _ ->
            input.readLimited(MAX_STORED_CHAT_DOCUMENT)
        }
    }

    suspend fun listChats(remote: RemoteCard): List<RemoteChatSummary> {
        val listing = text("/api/characters/chats", jsonOf("avatar_url" to remote.avatar))
        return JSONArray(listing).objects().mapNotNull { row ->
            val file = row.string("file_name").ifBlank { return@mapNotNull null }
            val count = row.optInt("chat_items", 0)
            val size = row.optLong("file_size", 0L)
            val stamp = row.optLong("last_mes", 0L).takeIf { it > 1_000_000_000L } ?: 0L
            RemoteChatSummary(file, count, size, stamp)
        }
    }

    suspend fun downloadChatRows(remote: RemoteCard, fileName: String): List<JSONObject> =
        JSONArray(text("/api/chats/get", jsonOf(
            "ch_name" to remote.name,
            "file_name" to fileName.removeSuffix(".jsonl"),
            "avatar_url" to remote.avatar,
        ))).objects()

    suspend fun saveChat(remote: RemoteCard, chat: Chat, fileName: String, personaName: String) {
        if (csrf.isBlank()) loadToken()
        val rows = JSONArray().apply {
            chat.jsonl(remote.name, personaName).decodeToString().lineSequence()
                .filter(String::isNotBlank)
                .forEach { put(JSONObject(it)) }
        }
        text("/api/chats/save", jsonOf(
            "ch_name" to remote.name,
            "file_name" to fileName.removeSuffix(".jsonl"),
            "avatar_url" to remote.avatar,
            "chat" to rows,
        ))
    }

    private suspend fun loadToken() {
        csrf = JSONObject(text("/csrf-token")).getString("token")
    }

    private fun stableId(key: String) = UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString()
}
