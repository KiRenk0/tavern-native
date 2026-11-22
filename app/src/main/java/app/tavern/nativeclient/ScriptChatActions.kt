package app.tavern.nativeclient

internal enum class ScriptChatAction { RENAME, CLOSE, RELOAD, JUMP }

internal fun scriptChatJump(chat: Chat, input: String): ChatArchiveJump? {
    val number = input.trim().toDoubleOrNull() ?: return null
    val index = number.toInt()
    return if (number == index.toDouble() && index in chat.messages.indices) ChatArchiveJump(chat.id, index) else null
}
