package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject

internal enum class PromptPostProcessing(
    val wire: String,
    val label: String,
    val allowsTools: Boolean = false,
    private val leadSystemOnly: Boolean = false,
    private val userFirst: Boolean = false,
) {
    NONE("", "关闭", allowsTools = true),
    MERGE("merge", "合并连续同角色消息"),
    MERGE_TOOLS("merge_tools", "合并连续同角色消息 · 保留工具", allowsTools = true),
    SEMI("semi", "半严格 · 系统消息仅置于开头", leadSystemOnly = true),
    SEMI_TOOLS("semi_tools", "半严格 · 保留工具", allowsTools = true, leadSystemOnly = true),
    STRICT("strict", "严格 · 用户首先发言", leadSystemOnly = true, userFirst = true),
    STRICT_TOOLS("strict_tools", "严格 · 保留工具", allowsTools = true, leadSystemOnly = true, userFirst = true),
    SINGLE("single", "单条用户消息");

    fun process(messages: JSONArray, names: PromptPostProcessingNames): JSONArray {
        if (this == NONE) return messages
        val normalized = messages.objects().map { message -> normalize(message, names, this == SINGLE, allowsTools) }
            .ifEmpty { listOf(openingUser()) }
        val merged = merge(normalized)
        if (leadSystemOnly) {
            for (row in merged.drop(1)) {
                if (row.role == "system") row.fields.put("role", "user")
            }
            val firstTurn = if (merged.first().role == "system") 1 else 0
            if (userFirst && merged.getOrNull(firstTurn)?.role != "user") merged.add(firstTurn, openingUser())
        }
        val result = if (leadSystemOnly) merge(merged) else merged
        return JSONArray(result.map(ProcessingMessage::json))
    }

    companion object {
        fun from(value: String): PromptPostProcessing =
            if (value == "claude") MERGE else entries.firstOrNull { it.wire == value } ?: NONE
    }
}

internal data class PromptPostProcessingNames(
    val user: String = "",
    val character: String = "",
    val group: List<String> = emptyList(),
)

private val callArrays = listOf("tool_calls", "server_tool_calls", "reasoning_details")
private val reasoningFields = listOf("reasoning", "reasoning_content")

private class ProcessingMessage(val fields: JSONObject, val parts: MutableList<Any>) {
    val role get() = fields.string("role")
    val hasContent get() = parts.any { it !is String || it.isNotEmpty() }

    fun prefix(name: String, comparableNames: List<String> = emptyList()) {
        val first = parts.firstOrNull() as? String
        if (name.isBlank() || first != null && (first.startsWith("$name: ") || comparableNames.any { first.startsWith("$it: ") })) return
        if (first == null) parts.add(0, "$name: ") else parts[0] = "$name: $first"
    }

    fun append(next: ProcessingMessage) {
        parts.addAll(next.parts)
        for (key in callArrays) {
            val additional = next.fields.optJSONArray(key) ?: continue
            val existing = fields.optJSONArray(key)
            fields.put(key, JSONArray().apply {
                for (array in listOfNotNull(existing, additional)) {
                    for (i in 0 until array.length()) put(array.opt(i))
                }
            })
        }
        for (key in reasoningFields) {
            val extra = next.fields.string(key)
            if (extra.isNotEmpty()) fields.put(key, listOf(fields.string(key), extra).filter(String::isNotEmpty).joinToString("\n\n"))
        }
        for (key in next.fields.keys()) {
            if (!fields.has(key)) fields.put(key, next.fields.get(key))
        }
        if (next.fields.optBoolean("prefix")) fields.put("prefix", true)
    }

    fun json(): JSONObject = fields.put("content", if (parts.all { it is String }) parts.joinToString("\n\n") else JSONArray().apply {
        var text = mutableListOf<String>()
        fun flush() {
            if (text.isNotEmpty()) {
                put(jsonOf("type" to "text", "text" to text.joinToString("\n\n")))
                text = mutableListOf()
            }
        }
        for (part in parts) {
            if (part is String) text.add(part) else {
                flush()
                put(part)
            }
        }
        flush()
    })
}

private fun normalize(message: JSONObject, names: PromptPostProcessingNames, single: Boolean, withTools: Boolean): ProcessingMessage {
    val fields = JSONObject(message, message.keys().asSequence().filter { it != "name" && it != "content" }.toList().toTypedArray())
    val parts = mutableListOf<Any>()
    when (val value = message.opt("content")) {
        is JSONArray -> value.objects().forEach { part ->
            parts.add(if (part.string("type") == "text") part.string("text") else part)
        }
        is String -> parts.add(value)
    }
    val row = ProcessingMessage(fields, parts)
    val name = message.string("name")
    if (row.role == "system") {
        when (name) {
            "example_assistant" -> row.prefix(names.character, names.group)
            "example_user" -> row.prefix(names.user)
        }
    } else row.prefix(name)
    if (!withTools) {
        fields.remove("tool_calls")
        fields.remove("tool_call_id")
        fields.remove("server_tool_calls")
        if (row.role == "tool") fields.put("role", "user")
    }
    if (single) {
        when (row.role) {
            "assistant" -> row.prefix(names.character, names.group)
            "user" -> row.prefix(names.user)
        }
        fields.put("role", "user")
    }
    return row
}

private fun merge(messages: List<ProcessingMessage>): MutableList<ProcessingMessage> {
    val merged = mutableListOf<ProcessingMessage>()
    for (row in messages) {
        val prior = merged.lastOrNull()
        if (prior != null && prior.role == row.role && row.role != "tool") {
            if (row.hasContent || row.fields.length() > 1) prior.append(row)
        } else {
            merged.add(row)
        }
    }
    return merged
}

private fun openingUser() = ProcessingMessage(jsonOf("role" to "user"), mutableListOf("[Start a new chat]"))
