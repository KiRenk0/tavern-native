package app.tavern.nativeclient

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

internal data class ScriptGenerationOptions(
    val length: Int? = null,
    val name: String = "",
    val asCharacter: Boolean = false,
    val system: String = "",
    val prefill: String = "",
    val instruct: Boolean = true,
    val trim: Boolean = false,
    val stops: List<String> = emptyList(),
) {
    companion object {
        fun read(arguments: Map<String, String>, raw: Boolean, resolve: (String) -> String): ScriptGenerationOptions {
            fun value(key: String): String = arguments[key]?.let(resolve).orEmpty()
            return ScriptGenerationOptions(
                length = (value("length").takeIf(String::isNotBlank) ?: value("max")).trim()
                    .toIntOrNull()?.takeIf { it > 0 },
                name = arguments["name"].orEmpty().trim(),
                asCharacter = arguments["as"].orEmpty().equals("char", true),
                system = if (raw) value("system") else "",
                prefill = if (raw) value("prefill") else "",
                instruct = arguments["instruct"].orEmpty().scriptBoolean(default = true),
                trim = arguments["trim"].orEmpty().scriptBoolean(default = raw),
                stops = CustomStoppingStrings.parse(value("stop"), macros = false).values,
            )
        }
    }
}

internal data class ScriptQuietPrompt(
    val text: String,
    val name: String = "System",
    val asCharacter: Boolean = false,
    val stops: List<String> = emptyList(),
)

internal fun scriptRawMessages(text: String, system: String): JSONArray = JSONArray().apply {
    if (system.isNotBlank()) put(jsonOf("role" to "system", "content" to system.trim()))
    put(jsonOf("role" to "user", "content" to text.trim()))
}

internal fun scriptRawTextPrompt(
    messages: JSONArray, instruct: InstructTemplate?, turn: InstructTurn, prefill: String,
): TextCompletionPrompt {
    if (instruct == null) {
        val text = messages.objects().map { row ->
            val body = row.string("content")
            if (row.string("role") == "user" && turn.user.isNotEmpty()) "${turn.user}: $body" else body
        }.plus(prefill).joinToString("\n")
        return TextCompletionPrompt(text, emptyList())
    }
    val result = instruct.format(messages, turn)
    val separator = if (prefill.isNotEmpty() && result.text.endsWith(':') && !prefill.first().isWhitespace()) " " else ""
    return result.copy(text = result.text + separator + prefill)
}

internal fun trimScriptReplyNames(value: String, user: String, character: String): String {
    val userPrefix = user.takeIf(String::isNotBlank)?.let { "$it:" }
    if (userPrefix != null && value.startsWith(userPrefix, true)) return ""
    val nextUser = userPrefix?.let { value.indexOf("\n$it", ignoreCase = true) } ?: -1
    val body = (if (nextUser < 0) value else value.substring(0, nextUser)).trimEnd()
    val characterPrefix = character.takeIf(String::isNotBlank)?.let { "$it:" }
    return if (characterPrefix != null && body.startsWith(characterPrefix, true)) {
        body.substring(characterPrefix.length).trimStart()
    } else body
}

internal fun scriptNarratorIndex(position: String?, size: Int): Int {
    val raw = position?.trim() ?: return size
    val value = raw.toIntOrNull() ?: return size
    return (if (value < 0) size + value else if (raw == "-0") size else value)
        .takeIf { it in 0..size } ?: size
}

internal fun scriptNarratorMetadata(compact: Boolean): String =
    JSONObject().put("extra", jsonOf("type" to "narrator", "isSmallSys" to compact)).toString()

internal suspend fun awaitScriptTriggerSlot(state: StateFlow<TavernState>, chatId: String, timeoutMillis: Long = 10_000): Boolean? =
    withTimeoutOrNull(timeoutMillis) {
        do {
            state.first { it.chat?.id != chatId || !it.busy && it.generation == null }
            val current = state.value
            if (current.chat?.id != chatId) return@withTimeoutOrNull false
        } while (current.busy || current.generation != null)
        true
    }
