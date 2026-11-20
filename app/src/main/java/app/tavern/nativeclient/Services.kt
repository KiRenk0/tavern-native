package app.tavern.nativeclient

import android.util.Base64
import kotlinx.coroutines.Dispatchers
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
                connection.requestMethod = if (body == null && upload == null) "GET" else "POST"
                connection.setRequestProperty("Accept", "application/json, text/event-stream, image/png")
                headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                cookies?.get(uri, emptyMap())?.forEach { (name, values) ->
                    if (values.isNotEmpty()) connection.setRequestProperty(name, values.joinToString("; "))
                }
                val serialized = upload?.bytes ?: body?.toString()?.toByteArray(Charsets.UTF_8)
                if (serialized != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", upload?.contentType ?: "application/json; charset=utf-8")
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
                            j.optJSONObject("error")?.string("message") ?: j.string("error")
                        }.getOrDefault("").take(250)
                    }.orEmpty()
                    error(if (detail.isBlank()) "服务器返回 HTTP $status" else "HTTP $status · $detail")
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
        val content = line.optJSONArray("content") ?: return@map line
        jsonOf("role" to line.string("role"), "content" to JSONArray(content.objects().map { part ->
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
                      token: (String) -> Unit): ReplyResult {
        val provider = connection.provider
        val textRules = prefs.regex.runtime(card, persona.name, preset, group?.roster.orEmpty())
        val toolConnection = connection.takeIf { prefs.toolCalling }
        val (images, documents) = withContext(Dispatchers.IO) {
            val window = modelHistory(history, vectors, toolConnection)
            val rawImages = if (prefs.captions.textOnly) emptyMap() else promptImages(window, readImage)
            rawImages to promptDocuments(window, vectors.attachments, readDocument)
        }
        val leadingSystemOnly = provider.template == ProviderTemplate.DEEPSEEK ||
            (provider.template == ProviderTemplate.GOOGLE && provider.protocol == ApiProtocol.OPENAI_COMPATIBLE)
        val includeReasoning = effectiveSampling?.includeReasoning ?: prefs.includeReasoning
        val prepared = withContext(Dispatchers.Default) {
            prompt(card, history, leadingSystemOnly, books, group, persona, options, prefs.loreSettings,
                trigger, preset, textRules, includeReasoning, prefs.scriptVariables, images, prefs.vectors, vectors,
                documents, toolConnection, sharedMemory, prefs.loreSettings.modelBudget(connection, prefs, preset, loreCounter),
                prefs.captions, connection.model, chatId)
        }
        onLoreBudget(prepared.loreBudget)
        val payload = completionPayload(connection, prepared.messages, provider.streaming)
        preset?.applySampling(payload, provider.template)
        (effectiveSampling?.temperature ?: prefs.temperature).toDoubleOrNull()?.let { payload.put("temperature", it) }
        effectiveSampling?.topP.orEmpty().toDoubleOrNull()?.let { payload.put("top_p", it) }
        (effectiveSampling?.maxTokens ?: prefs.maxTokens).toIntOrNull()?.let { payload.put("max_tokens", it) }
        if (!prepared.variables.isEmpty) onVariables(prepared.variables)
        if (prepared.automationIds.isNotEmpty()) onLoreActivated(prepared.automationIds)
        for (round in 0..MAX_TOOL_ROUNDS) {
            val available = tools?.reload?.invoke() ?: tools?.tools.orEmpty()
            val registered = available.filter { it.shouldRegister() }
            if (registered.isNotEmpty()) {
                payload.put("tools", JSONArray(registered.map(ModelTool::json)))
                payload.put("tool_choice", if (round == MAX_TOOL_ROUNDS) "none" else "auto")
            } else {
                payload.remove("tools")
                payload.remove("tool_choice")
            }
            onPrompt(inspectedPrompt(prepared.messages))
            val result = complete(provider, payload, onReasoning, token, acceptTools = registered.isNotEmpty(),
                onRetry = onRetry)
            val incomplete = result.interruption ?: when (result.finishReason) {
                "length" -> "回复达到最大输出 Token"
                "continuation" -> "模型暂停了长回复"
                "content_filter" -> "模型的内容过滤结束了回复"
                else -> null
            }
            if (incomplete != null && result.hasText) {
                return ReplyResult(prepared.loreEffects, incomplete, continuable = result.finishReason != "content_filter")
            }
            result.interruption?.let { error("$it，请重试") }
            val calls = result.calls()
            if (calls.isEmpty()) return ReplyResult(prepared.loreEffects)
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
            turn.messages().forEach(prepared.messages::put)
        }
        return ReplyResult(prepared.loreEffects)
    }

    internal data class ReplyResult(
        val loreEffects: Map<String, LoreEffect>,
        val incomplete: String? = null,
        val continuable: Boolean = true,
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

    internal suspend fun complete(provider: Provider, payload: JSONObject, onReasoning: (String) -> Unit,
                                 token: (String) -> Unit, acceptTools: Boolean = false,
                                 onRetry: () -> Unit = {}): CompletionAccumulator {
        if (provider.protocol !in setOf(ApiProtocol.OPENAI_COMPATIBLE, ApiProtocol.OPENROUTER)) {
            return ProviderProtocols.completeProtocol(provider, payload, onReasoning, token, acceptTools, onRetry)
        }
        ProviderProtocols.enrichOpenAiPayload(provider, payload)
        val hydrogen = payload.string("model").substringAfterLast('/').equals("gemini-4-hydrogen", ignoreCase = true)
        var result = readCompletion(provider, payload, onReasoning, token, acceptTools, readFullStream = hydrogen)
        if (result.hasOutput) return result

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
                result = readCompletion(provider, retryPayload, onReasoning, token, acceptTools, readFullStream = true)
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
                                       readFullStream: Boolean): CompletionAccumulator {
        val result = CompletionAccumulator()
        fun appendResponse(json: JSONObject, streaming: Boolean) {
            val detail = json.optJSONObject("error")?.string("message") ?: (json.opt("error") as? String).orEmpty()
            if (detail.isNotBlank()) {
                val message = detail.redactValue(provider.apiKey.trim()).take(250)
                if (!streaming || !result.hasText) error(message)
                result.interruption = message
                return
            }
            val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return
            if (streaming) choice.optJSONObject("delta")?.let { result.append(it, acceptTools, onReasoning, token) }
            if (!streaming || !result.hasOutput) {
                choice.optJSONObject("message")?.let { result.append(it, acceptTools, onReasoning, token) }
            }
            choice.string("finish_reason").trim().lowercase().takeIf(String::isNotBlank)?.let { result.finishReason = it }
        }
        var streamOpened = false
        var streamConsumed = false
        try {
            request("${base(provider)}/chat/completions", payload, ProviderProtocols.buildOpenAiHeaders(provider)) { input, type ->
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
                        if (!atEnd && !result.hasText) error(message)
                        result.interruption = message
                        return true
                    }
                    appendResponse(json, streaming = true)
                    return result.interruption != null || (!readFullStream && result.finishReason.isNotBlank())
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
        return result
    }

    internal data class PreparedPrompt(
        val messages: JSONArray, val loreEffects: Map<String, LoreEffect>, val automationIds: Set<String>,
        val loreBudget: LoreBudgetUsage,
        val variables: MacroVariableChanges,
    )

    private fun withoutRecalled(history: List<Message>, vectors: VectorContext) = if (vectors.replacedIndices.isEmpty()) history
        else history.filterIndexed { index, _ -> index !in vectors.replacedIndices }

    private fun modelHistory(history: List<Message>, vectors: VectorContext, toolConnection: ModelConnection?) =
        promptHistory(withoutRecalled(history, vectors)) { turn -> toolConnection?.let(turn::accepts) == true }

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
    ): PreparedPrompt {
        val impersonating = trigger == ReplyAction.IMPERSONATE.trigger
        val firstIncluded = history.indices.firstOrNull {
            it !in vectors.replacedIndices && !history[it].excludedFromContext && history[it].toolTurn == null
        }
        val macros = ConversationMacros(card, persona, history, group, options, scriptVariables, model, chatId, trigger, firstIncluded)
        val deferredOutlets = linkedMapOf<String, String>()
        fun deferOutlet(key: String): String =
            if (key.isBlank()) "" else deferredOutlets.getOrPut(key) { "\uFDD0${newId()}\uFDD0" }
        fun renderLore(text: String) = macros.expand(text, outlet = ::deferOutlet)
        val note = options.authorNote ?: AuthorNote()
        val noteDue = note.due(history)
        val sections = preset?.prompts?.filter { it.applies(trigger) && !it.atDepth }?.mapNotNull { it.section }?.toSet()
        fun inPreset(section: PromptSection) = sections == null || section in sections
        val noteText = if (noteDue) card.authorNote.combine(note.text) else ""
        val personaText = persona.description.trim()
        val noteTop = personaText.takeIf { noteDue && persona.position == PersonaPosition.NOTE_TOP }
        val noteBottom = personaText.takeIf { noteDue && persona.position == PersonaPosition.NOTE_BOTTOM }
        val documentText = vectors.documents.joinToString("\n\n") { "${it.title}\n${it.fragments.joinToString("\n…\n")}" }
        val scan = listOfNotNull(
            personaText.takeIf { persona.position == PersonaPosition.DEPTH },
            noteTop.takeIf { note.scan },
            noteText.takeIf { note.scan },
            noteBottom.takeIf { note.scan },
        ).joinToString("\n", transform = ::renderLore)
        val evaluation = evaluateLore(card, books, history.filterNot(Message::hidden).map { message ->
            val observations = message.displayImages.mapNotNull(message::captionFor)
            if (observations.isEmpty() && message.wireText == null) message
            else message.copy(text = (listOf(message.requestText) + observations).filter(String::isNotBlank).joinToString("\n"))
        },
            loreSettings, persona, options, listOf(scan, documentText.takeIf { vectorOptions.files.scanLore }.orEmpty())
                .filter(String::isNotBlank).joinToString("\n"), trigger, semanticMatches = vectors.lore, budget = loreBudget,
            render = ::renderLore, renderEntry = { entry, source ->
                val writes = when (entry.position) {
                    LorePosition.BEFORE -> inPreset(PromptSection.LORE_BEFORE)
                    LorePosition.AFTER -> inPreset(PromptSection.LORE_AFTER)
                    LorePosition.AUTHOR_TOP, LorePosition.AUTHOR_BOTTOM ->
                        noteDue && (note.position != NotePosition.DEPTH || inPreset(PromptSection.HISTORY))
                    LorePosition.DEPTH -> inPreset(PromptSection.HISTORY)
                    LorePosition.EXAMPLE_TOP, LorePosition.EXAMPLE_BOTTOM -> inPreset(PromptSection.EXAMPLES)
                    LorePosition.OUTLET -> true
                }
                val candidate = MacroVariables(macros.variables::get)
                val text = macros.expand(source, write = writes, scope = candidate, outlet = ::deferOutlet)
                LoreExpansion(text) { macros.variables.apply(candidate.changes()) }
            })
        val automationIds = evaluation.entries.map(LoreEntry::automationId).filter(String::isNotBlank).toSet()
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
        val relevantHistory = modelHistory(history, vectors, toolConnection)
        val roster = group?.let {
            val names = "群聊「${it.group.name}」的成员：${it.roster.joinToString("、")}。"
            if (impersonating) names else "$names 只生成 ${card.name} 的回复。"
        }
        val joint = group?.takeUnless { it.group.promptMode == GroupPromptMode.SWAP }
        val orderedCharacters = joint?.let {
            (it.definitions + card).sortedBy { character -> it.group.memberIds.indexOf(character.id) }
        } ?: listOf(card)
        val memory = options.memory
        val summary = history.currentSummary()?.summary?.text?.takeIf { memory.enabled }?.let(memory::render).orEmpty()
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
            PromptSection.EXAMPLES -> if (options.exampleOverride.isNotBlank()) expanded(options.exampleOverride, write = write)
                else characterField("mes_example", write, Card::examples)
            PromptSection.PERSONA -> if (persona.position == PersonaPosition.PROMPT) expanded(personaText, write = write) else ""
            else -> ""
        }
        val systemPrompt = options.systemPromptOverride.ifBlank { card.instructions }
        val visiblePositions = relevantHistory.indices.filter { relevantHistory[it].toolTurn == null }
        val captions = promptImageCaptions(relevantHistory, captionSettings, persona.name, card.name)
        val visibleCount = visiblePositions.size
        var historyIndex = 0
        fun historyLine(index: Int, entry: Message): PromptLine {
            entry.toolTurn?.let { return PromptLine("assistant", it.content, wire = it.messages()) }
            val otherSpeaker = entry.role == "assistant" && !entry.isBy(card) &&
                (group != null || entry.groupTurnId != null && entry.name.isNotBlank())
            val role = if (otherSpeaker) "user" else entry.role
            val depth = visibleCount - 1 - historyIndex++ - if (trigger == ReplyAction.CONTINUE.trigger) 1 else 0
            val body = entry.regexSite?.let { textRules.transform(entry.requestText, it, RegexStage.REQUEST, depth) } ?: entry.requestText
            val reasoning = entry.reasoning?.takeIf { includeReasoning && role == "assistant" }
                ?.let { textRules.transform(it, RegexSite.REASONING, RegexStage.REQUEST, depth) }.orEmpty()
            val text = when {
                otherSpeaker -> "${entry.name}: $body"
                reasoning.isNotBlank() -> "<think>\n$reasoning\n</think>\n\n$body"
                else -> body
            }
            val content = listOfNotNull(documents[index], expanded(text, write = false).takeIf(String::isNotBlank), captions[index])
                .joinToString("\n\n")
            return PromptLine(role, content, if (captionSettings.textOnly) emptyList() else images[index].orEmpty())
        }
        if (preset != null) {
            val context = PresetPromptContext(card, persona, group, ::sectionText, { relevantHistory.mapIndexed(::historyLine) },
                { atDepth },
                { if (note.position != NotePosition.DEPTH && inNote.isNotBlank()) PromptLine(note.role.wire, inNote) else null },
                note.position, positioned(LorePosition.EXAMPLE_TOP), positioned(LorePosition.EXAMPLE_BOTTOM),
                trigger, leadingSystemOnly, { text, values -> expanded(text, values) }, topMemory, bottomMemory, systemPrompt)
            return PreparedPrompt(insertReferenceText(assemblePreset(preset, context)), evaluation.effects, automationIds,
                evaluation.budget, macros.variables.changes())
        }
        val instruction = if (impersonating) "为 ${persona.name} 起草下一条聊天消息。"
            else systemPrompt.takeIf(String::isNotBlank)?.let { expanded(it) }
                ?: "以 ${card.name} 的身份与 ${persona.name} 对话。保持角色一致，并用对方使用的语言回复。"
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
            positioned(LorePosition.EXAMPLE_TOP),
            sectionText(PromptSection.EXAMPLES, true).takeIf(String::isNotBlank)?.let { "对话示例：\n$it" },
            positioned(LorePosition.EXAMPLE_BOTTOM),
            atDepth.filter { leadingSystemOnly && it.role == "system" }.joinToString("\n") { it.text },
            card.postHistory.takeIf { leadingSystemOnly && it.isNotBlank() }?.let { expanded(it) },
        ).filterNotNull().filter(String::isNotBlank).joinToString("\n\n")
        val messages = JSONArray().put(jsonOf("role" to "system", "content" to system))
        val injections = atDepth.filterNot { leadingSystemOnly && it.role == "system" }
            .groupBy { injectionPosition(relevantHistory.size, visiblePositions, it.depth) }
        for (index in 0..relevantHistory.size) {
            injections[index].orEmpty().filter { it.text.isNotBlank() }.forEach {
                messages.put(jsonOf("role" to it.role, "content" to it.text))
            }
            val entry = relevantHistory.getOrNull(index) ?: continue
            val line = historyLine(index, entry)
            if (line.hasContent) line.wireRows().forEach(messages::put)
        }
        if (group != null && relevantHistory.isEmpty() && !impersonating) {
            messages.put(jsonOf("role" to "user", "content" to "请以 ${card.name} 的身份开始群聊。"))
        }
        if (!leadingSystemOnly && card.postHistory.isNotBlank()) {
            messages.put(jsonOf("role" to "system", "content" to expanded(card.postHistory)))
        }
        val nudge = when (trigger) {
            ReplyAction.CONTINUE.trigger -> "接着写上一条回复，从原文结束的位置继续。避免重复已经写出的内容。"
            ReplyAction.IMPERSONATE.trigger -> "用 ${persona.name} 的视角和语言风格写一条发言。请只输出消息正文，保留其他角色的发言空间。"
            else -> null
        }
        if (nudge != null) messages.put(jsonOf("role" to if (leadingSystemOnly) "user" else "system", "content" to nudge))
        return PreparedPrompt(insertReferenceText(messages), evaluation.effects, automationIds, evaluation.budget, macros.variables.changes())
    }

}

internal data class RemoteCard(val avatar: String, val name: String)

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
        val card = CardFile.read(bytes("/api/characters/export",
            jsonOf("avatar_url" to remote.avatar, "format" to "png")), id)
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

    private suspend fun loadToken() {
        csrf = JSONObject(text("/csrf-token")).getString("token")
    }

    private fun stableId(key: String) = UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString()
}
