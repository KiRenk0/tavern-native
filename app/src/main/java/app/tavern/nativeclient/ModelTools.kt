package app.tavern.nativeclient

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal fun String.redactValue(secret: String) = if (secret.isBlank()) this else replace(secret, "[已隐藏]")

internal const val DRAW_IMAGE_TOOL = "GenerateImage"
internal const val MAX_TOOL_ROUNDS = 5
private const val MAX_CALLS_PER_ROUND = 16
private const val MAX_TOOL_ARGUMENTS = 65_536

internal data class ModelTool(
    val name: String,
    val label: String,
    val description: String,
    val parameters: JSONObject,
    val stealth: Boolean = false,
    val formatMessage: (suspend (JSONObject) -> String)? = null,
    val shouldRegister: suspend () -> Boolean = { true },
    val invoke: suspend (JSONObject) -> String,
) {
    fun json() = jsonOf("type" to "function", "function" to jsonOf(
        "name" to name, "description" to description, "parameters" to parameters,
    ))

    suspend fun execute(arguments: JSONObject): String {
        val depth = currentCoroutineContext()[ToolDepth]?.count ?: 0
        check(depth < 80) { "工具调用层数超过 80" }
        return withContext(ToolDepth(depth + 1)) { invoke(arguments) }
    }

    suspend fun progress(arguments: JSONObject): String =
        try {
            formatMessage?.invoke(arguments) ?: "正在运行 $label…"
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            "正在运行 $label…"
        }
}

private class ToolDepth(val count: Int) : AbstractCoroutineContextElement(ToolDepth) {
    companion object : CoroutineContext.Key<ToolDepth>
}

internal data class ReplyTools(
    val tools: List<ModelTool> = emptyList(),
    val reload: (() -> List<ModelTool>)? = null,
    val progress: (String?) -> Unit = {},
    val finishStealth: (List<ToolInvocation>) -> Unit = {},
    val record: suspend (ToolTurn) -> Unit,
)

internal data class ToolCall(
    val id: String, val name: String, val arguments: String, val metadata: JSONObject? = null,
) {
    fun json() = jsonOf("id" to id, "type" to "function", "function" to
        jsonOf("name" to name, "arguments" to arguments)).apply {
        metadata?.let { put("extra_content", it) }
    }

    suspend fun run(tools: List<ModelTool>, progress: (String?) -> Unit = {},
                    redact: (String) -> String = { it }): ToolInvocation {
        val definition = tools.firstOrNull { it.name == name }
        return try {
            val tool = requireNotNull(definition) { "工具未注册：$name" }
            val args = toolArguments(arguments)
            val label = tool.progress(args).trim().take(300).takeIf(String::isNotBlank)
            progress(label)
            ToolInvocation(this, tool.label, tool.execute(args).take(16_384), stealth = tool.stealth)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolInvocation(this, definition?.label ?: name,
                redact(e.localizedMessage ?: "工具调用失败").take(500), failed = true, stealth = definition?.stealth == true)
        } finally {
            progress(null)
        }
    }
}

internal fun toolArguments(raw: String): JSONObject {
    require(raw.length <= MAX_TOOL_ARGUMENTS) { "工具参数过长" }
    return boundedJsonObject(raw.ifBlank { "{}" }, "工具参数必须是有效的 JSON 对象")
}

internal fun toolVariables(arguments: JSONObject): Map<String, String> = buildMap {
    fun add(key: String, value: Any?) {
        when (value) {
            is JSONObject -> value.keys().forEach { subKey -> add("$key.$subKey", value.opt(subKey)) }
            is JSONArray -> {
                put(key, value.toString())
                for (index in 0 until value.length()) add("$key.$index", value.opt(index))
            }
            else -> put(key, value?.toString().orEmpty())
        }
    }
    add("arg", arguments)
}

internal fun boundedJsonObject(raw: String, invalid: String): JSONObject {
    // Android's JSON reader is recursive. Bound the nesting before deserializing.
    var quoted = false
    var escaped = false
    var depth = 0
    for (char in raw) {
        if (quoted) {
            if (escaped) escaped = false
            else if (char == '\\') escaped = true
            else if (char == '"') quoted = false
        } else when (char) {
            '"' -> quoted = true
            '{', '[' -> require(++depth <= 96) { "JSON 嵌套过深" }
            '}', ']' -> require(--depth >= 0) { invalid }
            '/', '#', '\'' -> error(invalid)
        }
    }
    require(depth == 0 && !quoted) { invalid }
    val input = JSONTokener(raw)
    return try {
        (input.nextValue() as JSONObject).also { require(input.nextClean().code == 0) }
    } catch (e: Exception) {
        throw IllegalArgumentException(invalid, e)
    }
}

internal data class ToolInvocation(
    val call: ToolCall, val label: String, val result: String, val failed: Boolean = false, val stealth: Boolean = false,
) {
    fun json(reasoning: String?) = jsonOf(
        "id" to call.id, "name" to call.name, "displayName" to label, "parameters" to call.arguments,
        "result" to result, "error" to failed, "reasoning" to reasoning, "extra_content" to call.metadata,
        "signature" to call.metadata?.optJSONObject("google")?.string("thought_signature")?.takeIf(String::isNotBlank),
    )

    fun message() = jsonOf("role" to "tool", "tool_call_id" to call.id, "content" to
        if (failed) jsonOf("error" to result).toString() else result.ifBlank { "{}" })

    companion object {
        fun from(row: JSONObject): ToolInvocation? {
            val id = row.string("id")
            val name = row.string("name")
            if (id.isBlank() || name.isBlank()) return null
            val metadata = row.optJSONObject("extra_content") ?: row.string("signature").takeIf(String::isNotBlank)?.let {
                jsonOf("google" to jsonOf("thought_signature" to it))
            }
            return ToolInvocation(ToolCall(id, name, row.string("parameters").take(MAX_TOOL_ARGUMENTS), metadata),
                row.string("displayName").ifBlank { name }, row.string("result").take(16_384), row.optBoolean("error"))
        }
    }
}

internal data class ToolTurn(
    val providerId: String?, val model: String?, val content: String, val reasoning: String?,
    val assistantMetadata: JSONObject?, val reasoningDetails: JSONArray?, val invocations: List<ToolInvocation>,
) {
    fun accepts(connection: ModelConnection): Boolean = if (providerId == null) model == null || model == connection.model
        else providerId == connection.provider.id && model == connection.model

    fun messages(): List<JSONObject> = listOf(jsonOf(
        "role" to "assistant", "content" to content.takeIf(String::isNotEmpty),
        "tool_calls" to JSONArray(invocations.map { it.call.json() }),
    ).apply {
        reasoning?.let { put("reasoning_content", it) }
        assistantMetadata?.let { put("extra_content", it) }
        reasoningDetails?.let { put("reasoning_details", it) }
    }) + invocations.map(ToolInvocation::message)

    fun store(extra: JSONObject) {
        extra.put("tool_invocations", JSONArray(invocations.mapIndexed { index, invocation ->
            invocation.json(reasoning.takeIf { index == 0 }?.take(32_768))
        }))
        extra.put("tavern_native_tool_turn", jsonOf("provider" to providerId, "model" to model, "content" to content,
            "reasoning" to reasoning, "extra_content" to assistantMetadata, "reasoning_details" to reasoningDetails))
        extra.put("isSmallSys", true)
        model?.let { extra.put("model", it) }
    }

    companion object {
        fun from(extra: JSONObject?): ToolTurn? {
            val rows = extra?.optJSONArray("tool_invocations")?.objects().orEmpty().take(MAX_CALLS_PER_ROUND)
            val calls = rows.mapNotNull(ToolInvocation::from).distinctBy { it.call.id }
            if (calls.isEmpty()) return null
            val data = extra?.optJSONObject("tavern_native_tool_turn")
            val reasoning = (data?.opt("reasoning") as? String) ?: rows.firstNotNullOfOrNull { it.opt("reasoning") as? String }
            return ToolTurn(data?.string("provider")?.takeIf(String::isNotBlank),
                (data?.string("model") ?: extra?.string("model"))?.takeIf(String::isNotBlank), data?.string("content").orEmpty(),
                reasoning?.take(MAX_MODEL_TEXT), data?.optJSONObject("extra_content"), data?.optJSONArray("reasoning_details"), calls)
        }
    }
}

internal const val MAX_MODEL_TEXT = 1_000_000

internal class CompletionAccumulator {
    private val text = StringBuilder()
    private val shownReasoning = StringBuilder()
    private val reasoning = StringBuilder()
    private var explicitReasoning = false
    private val parts = sortedMapOf<Int, CallParts>()
    private var assistantMetadata: JSONObject? = null
    private val reasoningDetails = linkedMapOf<String, JSONObject>()
    var finishReason: String = ""
    var interruption: String? = null
    val hasText get() = text.isNotBlank()
    val hasOutput get() = hasText || parts.isNotEmpty()

    fun appendText(fragment: String, token: (String) -> Unit) {
        if (fragment.isEmpty()) return
        text.append(fragment)
        check(text.length <= MAX_MODEL_TEXT && shownReasoning.length <= MAX_MODEL_TEXT) { "生成内容超过上限" }
        token(fragment)
    }

    fun appendReasoning(thought: String, onReasoning: (String) -> Unit, explicit: Boolean = false) {
        if (thought.isEmpty()) return
        shownReasoning.append(thought)
        if (explicit) {
            explicitReasoning = true
            reasoning.append(thought)
        }
        check(text.length <= MAX_MODEL_TEXT && shownReasoning.length <= MAX_MODEL_TEXT) { "生成内容超过上限" }
        onReasoning(thought)
    }

    fun startToolCall(index: Int, id: String, name: String) {
        require(index in 0 until MAX_CALLS_PER_ROUND) { "模型返回了太多工具调用" }
        parts.getOrPut(index) { CallParts() }.append(
            jsonOf("id" to id, "function" to jsonOf("name" to name, "arguments" to "")),
        )
    }

    fun appendToolArgs(index: Int, partialJson: String) {
        parts[index]?.append(jsonOf("function" to jsonOf("arguments" to partialJson)))
    }

    fun appendDirectToolCall(id: String, name: String, argumentsJson: String) {
        val index = parts.size
        require(index in 0 until MAX_CALLS_PER_ROUND) { "模型返回了太多工具调用" }
        parts[index] = CallParts().apply {
            append(jsonOf("id" to id, "function" to jsonOf("name" to name, "arguments" to argumentsJson)))
        }
    }

    fun append(message: JSONObject, acceptTools: Boolean, onReasoning: (String) -> Unit, token: (String) -> Unit) {
        val content = when (val value = message.opt("content")) {
            is String -> value
            is JSONArray -> buildString {
                for (index in 0 until value.length()) {
                    val block = value.optJSONObject(index) ?: continue
                    when (block.string("type")) {
                        "text", "output_text" -> (block.opt("text") as? String)?.let(::append)
                    }
                }
            }
            else -> ""
        }
        val rawReasoning = message.opt("reasoning_content") as? String
        val thought = rawReasoning ?: (message.opt("reasoning") as? String).orEmpty()
        text.append(content)
        shownReasoning.append(thought)
        check(text.length <= MAX_MODEL_TEXT && shownReasoning.length <= MAX_MODEL_TEXT) { "生成内容超过上限" }
        if (thought.isNotEmpty()) onReasoning(thought)
        if (content.isNotEmpty()) token(content)
        if (!acceptTools) return
        if (rawReasoning != null) {
            explicitReasoning = true
            reasoning.append(rawReasoning)
        }
        assistantMetadata = mergeToolMetadata(assistantMetadata, message.optJSONObject("extra_content"))
        message.optJSONArray("reasoning_details")?.objects()?.forEachIndexed { index, detail ->
            val key = detail.string("id").ifBlank { "${detail.optInt("index", index)}-${detail.string("type")}" }
            reasoningDetails[key] = mergeToolMetadata(reasoningDetails[key], detail) ?: detail
        }
        message.optJSONArray("tool_calls")?.objects()?.forEachIndexed { position, row ->
            val id = row.string("id")
            val index = row.optInt("index", -1).takeIf { it >= 0 }
                ?: parts.entries.firstOrNull { id.isNotBlank() && it.value.id == id }?.key
                ?: position.takeIf { id.isBlank() || parts[position]?.id.isNullOrBlank() }
                ?: (0 until MAX_CALLS_PER_ROUND).firstOrNull { it !in parts }
                ?: error("模型返回了太多工具调用")
            require(index in 0 until MAX_CALLS_PER_ROUND) { "模型返回了太多工具调用" }
            val part = parts.getOrPut(index) { CallParts() }
            part.append(row)
        }
    }

    fun calls(): List<ToolCall> {
        if (parts.isEmpty()) return emptyList()
        check(finishReason !in setOf("length", "content_filter", "continuation")) { "模型的工具参数没有完整生成" }
        val calls = parts.values.map(CallParts::finish)
        check(calls.map(ToolCall::id).distinct().size == calls.size) { "模型返回了重复的工具调用 ID" }
        return calls
    }

    fun turn(connection: ModelConnection, calls: List<ToolInvocation>) = ToolTurn(
        connection.provider.id, connection.model, text.toString(), reasoning.toString().takeIf { explicitReasoning },
        assistantMetadata, JSONArray(reasoningDetails.values).takeIf { reasoningDetails.isNotEmpty() }, calls,
    )

    private class CallParts {
        var id: String = ""
        private val name = StringBuilder()
        private val arguments = StringBuilder()
        private var metadata: JSONObject? = null

        fun append(row: JSONObject) {
            val nextId = row.string("id")
            if (nextId.isNotEmpty() && nextId != id) id += nextId
            val function = row.optJSONObject("function")
            val fragment = function?.string("name").orEmpty()
            if (fragment.isNotEmpty() && fragment != name.toString()) name.append(fragment)
            val arg = function?.opt("arguments")
            if (arg is String) arguments.append(arg)
            if (arg is JSONObject) arguments.append(arg.toString())
            require(arguments.length <= MAX_TOOL_ARGUMENTS && id.length <= 512 && name.length <= 128) { "工具调用内容过长" }
            metadata = mergeToolMetadata(metadata, row.optJSONObject("extra_content"))
        }

        fun finish(): ToolCall {
            check(name.isNotBlank()) { "模型未返回工具名称" }
            return ToolCall(id.ifBlank { "call_${newId()}" }, name.toString(), arguments.toString(), metadata)
        }
    }
}

private fun mergeToolMetadata(previous: JSONObject?, next: JSONObject?): JSONObject? {
    if (next == null) return previous
    val output = previous ?: JSONObject()
    next.keys().forEach { key ->
        val input = next.opt(key)
        output.put(key, if (input is JSONObject) mergeToolMetadata(output.optJSONObject(key), input) else input)
    }
    return output
}
