package app.tavern.nativeclient

import org.json.JSONArray

internal suspend fun executeCheckpointCommand(
    command: String, named: Map<String, String>, text: String, chat: Chat, history: List<ChatSummary>,
    fork: suspend (index: Int, checkpoint: Boolean, title: String, swipe: Int?) -> String,
    open: suspend (ChatSummary) -> String,
): String {
    fun title(pointer: ChatPointer?) = pointer?.let { it.resolve(history)?.title ?: it.title }.orEmpty()
    if (command == "checkpoint-parent") return title(chat.options.parent)
    if (command == "checkpoint-exit") {
        val parent = chat.options.parent?.resolve(history)?.takeUnless { it.id == chat.id } ?: return ""
        return open(parent)
    }
    if (command == "checkpoint-list") {
        val links = named["links"].orEmpty().scriptBoolean()
        return JSONArray(chat.messages.mapIndexedNotNull { index, message ->
            message.checkpoint?.let { if (links) title(it) else index }
        }).toString()
    }
    val option = named.entries.firstOrNull { it.key.equals("mesid", true) || it.key == "mes" }?.value
    val specified = (option ?: text.takeUnless { command == "checkpoint-create" })?.trim().orEmpty()
    val index = if (specified.isBlank()) chat.messages.lastIndex else specified.toIntOrNull() ?: return ""
    val message = chat.messages.getOrNull(index) ?: return ""
    return when (command) {
        "branch-create" -> {
            val swipe = named["swipe"]?.let { it.toIntOrNull() ?: error("请输入有效的备选回复编号") }
            fork(index, false, "", swipe)
        }
        "checkpoint-create" -> fork(index, true, text, null)
        "checkpoint-get" -> title(message.checkpoint)
        "checkpoint-go" -> {
            val target = message.checkpoint?.resolve(history)?.takeUnless { it.id == chat.id } ?: return ""
            open(target)
        }
        else -> error("未知的检查点命令")
    }
}
