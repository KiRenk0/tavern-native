package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

private const val INVALID_CHAT = "无法识别聊天文件格式"
private val chatFileExtension = Regex("(?i)\\.(jsonl|json|zip)$")

internal fun chatImportTitle(fileName: String): String =
    fileName.trim().replace(chatFileExtension, "").trim().ifBlank { "导入的聊天" }

internal fun importChatTranscripts(
    bytes: ByteArray, fileName: String, userName: String, assistantName: String,
): List<Chat> {
    val text = bytes.decodeToString().removePrefix("\uFEFF")
    val startsWithArray = text.trimStart().startsWith('[')
    require(text.isNotBlank()) { "聊天记录为空" }
    val title = chatImportTitle(fileName)
    if (fileName.endsWith(".jsonl", ignoreCase = true) && !startsWithArray) {
        return listOf(importJsonLinesChat(text, title))
    }
    val root = if (fileName.endsWith(".json", ignoreCase = true) || startsWithArray) {
        boundedJsonValue(text, INVALID_CHAT)
    } else {
        try {
            boundedJsonValue(text, INVALID_CHAT)
        } catch (_: IllegalArgumentException) {
            return listOf(importJsonLinesChat(text, title))
        }
    }
    val names = TranscriptNames(userName.ifBlank { "用户" }, assistantName.ifBlank { "角色" })
    return when (root) {
        is JSONArray -> listOf(importNativeChat(title, root.requireObjects()))
        is JSONObject -> importJsonChat(root, title, names)
        else -> error(INVALID_CHAT)
    }
}

internal fun importJsonLinesChat(text: String, title: String): Chat {
    val rows = text.trimStart('\uFEFF').lineSequence().withIndex().filter { it.value.isNotBlank() }.map { (index, line) ->
        boundedJsonObject(line, "无法读取聊天文件第 ${index + 1} 行")
    }.toList()
    return importNativeChat(title, rows)
}

private fun importNativeChat(title: String, rows: List<JSONObject>): Chat = Chat.import(null, title, rows.map { row ->
    // Chub carries text in objects for both the selected message and the alternatives.
    (row.optJSONObject("mes")?.opt("message") as? String)?.let { row.put("mes", it) }
    val swipes = row.optJSONArray("swipes")
    if (swipes != null) for (index in 0 until swipes.length()) {
        (swipes.optJSONObject(index)?.opt("message") as? String)?.let { swipes.put(index, it) }
    }
    row
})

private data class TranscriptNames(val user: String, val assistant: String) {
    fun message(role: String, text: String, name: String = "", date: String = "") =
        Message(role, name.ifBlank { if (role == "user") user else assistant }, text, date.ifBlank { Instant.now().toString() })
}

private fun importJsonChat(root: JSONObject, title: String, names: TranscriptNames): List<Chat> = when {
    root.optJSONObject("savedsettings") != null -> listOf(importKoboldChat(root, title, names))
    root.has("histories") -> importCharacterAiChats(root, title, names)
    root.optJSONArray("data_visible") != null -> {
        val turns = root.getJSONArray("data_visible")
        val messages = buildList {
            for (index in 0 until turns.length()) {
                val pair = requireNotNull(turns.optJSONArray(index)) { "Text Generation WebUI 第 ${index + 1} 组消息无效" }
                (pair.opt(0) as? String)?.takeUnless(String::isEmpty)?.let { add(names.message("user", it)) }
                (pair.opt(1) as? String)?.takeUnless(String::isEmpty)?.let { add(names.message("assistant", it)) }
            }
        }
        listOf(convertedChat(title, messages))
    }
    root.string("type") == "risuChat" -> {
        val rows = requireNotNull(root.optJSONObject("data")?.optJSONArray("message")) { "RisuAI 导出缺少消息" }
        val messages = rows.requireObjects().map { row ->
            val role = when (row.string("role")) {
                "user" -> "user"
                "system" -> "system"
                else -> "assistant"
            }
            val date = row.string("time").toLongOrNull()?.let { Instant.ofEpochMilli(it).toString() }.orEmpty()
            names.message(role, row.opt("data") as? String ?: "", row.string("name"), date)
        }
        listOf(convertedChat(root.string("title").ifBlank { title }, messages))
    }
    root.optJSONArray("messages") != null -> {
        val rows = root.getJSONArray("messages").requireObjects()
        if (root.has("chat_metadata") || rows.any { it.has("mes") }) {
            val header = root.optJSONObject("header") ?: JSONObject().apply {
                listOf("chat_metadata", "user_name", "character_name", "create_date", CHAT_OWNER_KEY).forEach { key ->
                    if (!root.isNull(key)) put(key, root.get(key))
                }
            }
            val all = if (header.length() == 0) rows else listOf(header) + rows
            listOf(importNativeChat(root.string("title").ifBlank { title }, all))
        } else {
            val messages = rows.map { row ->
                val role = if (row.string("userId").isNotBlank()) "user" else "assistant"
                val content = requireNotNull(row.opt("msg") as? String) { "Agnaistic 导出包含无效消息" }
                names.message(role, content, row.string("name"), row.string("createdAt"))
            }
            listOf(convertedChat(root.string("title").ifBlank { title }, messages))
        }
    }
    root.has("mes") || root.has("chat_metadata") || root.has("user_name") || root.has("character_name") ->
        listOf(importNativeChat(title, listOf(root)))
    else -> error(INVALID_CHAT)
}

private fun importKoboldChat(root: JSONObject, title: String, fallback: TranscriptNames): Chat {
    val settings = root.getJSONObject("savedsettings")
    val names = TranscriptNames(settings.string("chatname").ifBlank { fallback.user },
        settings.string("chatopponent").substringBefore("||\$||").ifBlank { fallback.assistant })
    val actions = requireNotNull(root.optJSONArray("actions")) { "KoboldAI Lite 导出缺少聊天动作" }
    val messages = buildList {
        fun addAction(action: String) {
            val role = if ("{{[INPUT]}}" in action) "user" else "assistant"
            val text = action.replace("{{[INPUT]}}", "").replace("{{[OUTPUT]}}", "").trim()
            if (text.isNotEmpty()) add(names.message(role, text))
        }
        (root.opt("prompt") as? String)?.let(::addAction)
        for (index in 0 until actions.length()) {
            addAction(requireNotNull(actions.opt(index) as? String) { "KoboldAI Lite 第 ${index + 1} 条动作无效" })
        }
    }
    return convertedChat(title, messages)
}

private fun importCharacterAiChats(root: JSONObject, title: String, names: TranscriptNames): List<Chat> {
    val entries = root.optJSONObject("histories")?.optJSONArray("histories") ?: root.optJSONArray("histories")
    requireNotNull(entries) { "CAI Tools 导出缺少聊天列表" }
    val chats = entries.requireObjects().mapIndexedNotNull { index, history ->
        val rows = requireNotNull(history.optJSONArray("msgs")) { "CAI Tools 第 ${index + 1} 段聊天缺少消息" }
        if (rows.length() == 0) return@mapIndexedNotNull null
        val messages = rows.requireObjects().map { row ->
            val source = requireNotNull(row.optJSONObject("src")) { "CAI Tools 导出缺少发言者" }
            val role = if (source.optBoolean("is_human")) "user" else "assistant"
            val content = requireNotNull(row.opt("text") as? String) { "CAI Tools 导出包含无效消息" }
            names.message(role, content, source.string("name"), row.string("created"))
        }
        val label = history.string("title").ifBlank { if (entries.length() == 1) title else "$title · ${index + 1}" }
        convertedChat(label, messages)
    }
    require(chats.isNotEmpty()) { "聊天记录为空" }
    return chats
}

private fun JSONArray.requireObjects(): List<JSONObject> = (0 until length()).map { index ->
    requireNotNull(optJSONObject(index)) { "第 ${index + 1} 项不是有效的聊天记录" }
}

private fun convertedChat(title: String, messages: List<Message>): Chat {
    require(messages.isNotEmpty()) { "聊天记录为空" }
    return Chat(newId(), null, title, messages = messages)
}
