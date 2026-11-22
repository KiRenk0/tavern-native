package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject

internal const val INSTRUCT_TEMPLATE_LIMIT = 256 * 1024
internal const val INSTRUCT_SEQUENCE_LIMIT = 4096

internal enum class InstructSequence(val key: String, val label: String) {
    STORY_PREFIX("story_string_prefix", "故事正文前缀"),
    STORY_SUFFIX("story_string_suffix", "故事正文后缀"),
    USER_PREFIX("input_sequence", "用户消息前缀"),
    USER_SUFFIX("input_suffix", "用户消息后缀"),
    ASSISTANT_PREFIX("output_sequence", "角色消息前缀"),
    ASSISTANT_SUFFIX("output_suffix", "角色消息后缀"),
    SYSTEM_PREFIX("system_sequence", "系统消息前缀"),
    SYSTEM_SUFFIX("system_suffix", "系统消息后缀"),
    FIRST_USER("first_input_sequence", "首条用户消息前缀"),
    LAST_USER("last_input_sequence", "最后的用户前缀"),
    FIRST_ASSISTANT("first_output_sequence", "首条角色消息前缀"),
    LAST_ASSISTANT("last_output_sequence", "最后的角色前缀"),
    QUIET("last_system_sequence", "后台生成前缀"),
    ALIGNMENT("user_alignment_message", "首条消息的用户填充文本"),
    STOP("stop_sequence", "停止序列（每行一个）"),
}

internal enum class InstructNames(val wire: String, val label: String) {
    NEVER("none", "从不"), GROUPS("force", "群聊、其他角色或过往人设"), ALWAYS("always", "始终");
}

internal data class InstructTemplate(
    val name: String,
    val sequences: Map<InstructSequence, String> = emptyMap(),
    val wrap: Boolean = true,
    val macros: Boolean = true,
    val names: InstructNames = InstructNames.GROUPS,
    val skipExamples: Boolean = false,
    val systemAsUser: Boolean = false,
    val prefixesAsStops: Boolean = true,
    val activationRegex: String = "",
) {
    operator fun get(field: InstructSequence): String = sequences[field].orEmpty()

    fun json() = jsonOf(
        "name" to name, "wrap" to wrap, "macro" to macros, "names_behavior" to names.wire,
        "skip_examples" to skipExamples, "system_same_as_user" to systemAsUser,
        "sequences_as_stop_strings" to prefixesAsStops, "activation_regex" to activationRegex,
    ).also { json -> InstructSequence.entries.forEach { json.put(it.key, get(it)) } }

    fun validated(): InstructTemplate {
        require(name.isNotBlank() && name.length <= 80) { "请输入 1–80 个字符的模板名称" }
        require(sequences.values.all { it.length <= INSTRUCT_SEQUENCE_LIMIT }) { "单个序列不能超过 $INSTRUCT_SEQUENCE_LIMIT 个字符" }
        return copy(name = name.trim(), sequences = sequences.filterValues(String::isNotEmpty))
    }

    companion object {
        fun parse(raw: String, fallback: String = ""): InstructTemplate =
            fromJson(boundedJsonObject(raw.trimStart('\uFEFF'), "无法读取指令模板 JSON"), fallback)

        fun fromJson(json: JSONObject, fallback: String = ""): InstructTemplate {
            require(InstructSequence.entries.any { json.opt(it.key) is String }) { "JSON 缺少 SillyTavern 指令序列" }
            val names = InstructNames.entries.firstOrNull { it.wire == json.string("names_behavior") }
                ?: if (json.optBoolean("names")) InstructNames.ALWAYS else InstructNames.GROUPS
            return InstructTemplate(
                name = json.string("name").ifBlank { fallback },
                sequences = InstructSequence.entries.associateWith { json.opt(it.key) as? String ?: "" },
                wrap = json.optBoolean("wrap", true),
                macros = json.optBoolean("macro", true),
                names = names,
                skipExamples = json.optBoolean("skip_examples"),
                systemAsUser = json.optBoolean("system_same_as_user"),
                prefixesAsStops = json.optBoolean("sequences_as_stop_strings", true),
                activationRegex = json.string("activation_regex").take(INSTRUCT_SEQUENCE_LIMIT),
            ).validated()
        }
    }
}

internal data class TextCompletionPrompt(val text: String, val stops: List<String>, val stopTokens: List<IntArray> = emptyList()) {
    fun inspection() = JSONArray().put(jsonOf("role" to "文本补全", "content" to text)).apply {
        if (stops.isNotEmpty()) put(jsonOf("role" to "停止序列", "content" to JSONArray(stops).toString(2)))
    }
}

internal data class InstructTurn(
    val user: String = "User",
    val character: String = "Assistant",
    val trigger: String = "",
    val group: Boolean = false,
    val quiet: Boolean = false,
    val expand: (String) -> String = { value ->
        value.replace("{{user}}", user, ignoreCase = true).replace("{{char}}", character, ignoreCase = true)
    },
    val quietName: String = "System",
)

internal val plainTextInstruct = InstructTemplate("纯文本", names = InstructNames.ALWAYS, prefixesAsStops = false)

internal fun InstructTemplate.format(
    messages: JSONArray, turn: InstructTurn, context: TextContextLayout? = null,
    plain: Boolean = false, examplesOnly: Boolean = false,
): TextCompletionPrompt {
    val rows = messages.objects().filter { it.promptText().isNotBlank() }
    val story = if (context != null || examplesOnly) rows.takeWhile { it.optBoolean("instructStory") }
        else rows.takeWhile { it.string("role") == "system" && !it.optBoolean("instructTurn") && !it.optBoolean("instructExample") }
    val history = rows.drop(story.size)
    val joiner = if (wrap) "\n" else ""
    val personaRole = if (turn.trigger == ReplyAction.IMPERSONATE.trigger) "user" else "assistant"
    val targetName = if (personaRole == "user") turn.user else turn.character
    val realTurns = history.filter { it.optBoolean("instructTurn") && it.string("role") != "system" }
    val last = realTurns.lastOrNull()
    // The prompt builder places the partial reply after injections and other instructions.
    val prefill = history.lastOrNull()?.takeIf {
        turn.trigger == ReplyAction.CONTINUE.trigger && it == last && it.string("role") == "assistant"
    }
    fun render(value: String, name: String) = if (macros) {
        turn.expand(value.replace("{{name}}", name, ignoreCase = true))
    } else value
    fun include(name: String, current: String) = name.isNotBlank() &&
        (names == InstructNames.ALWAYS || names == InstructNames.GROUPS && (turn.group || name != current))
    fun prefix(field: InstructSequence, fallback: InstructSequence, name: String) =
        render(get(field).ifEmpty { get(fallback) }, name)
    fun message(row: JSONObject): String {
        if (row.optBoolean("instructLiteral")) return row.promptText().let { if (it.endsWith('\n')) it else "$it\n" }
        val role = row.string("role")
        val isSystem = role == "system"
        val defaultName = when (role) { "user" -> turn.user; "assistant" -> turn.character; else -> "System" }
        val name = row.string("instructName").ifBlank { defaultName }
        val first = row.optBoolean("instructFirst")
        val sequence = when {
            isSystem -> get(if (systemAsUser) InstructSequence.USER_PREFIX else InstructSequence.SYSTEM_PREFIX)
            role == "user" -> get(if (first) InstructSequence.FIRST_USER else InstructSequence.USER_PREFIX)
                .ifEmpty { get(InstructSequence.USER_PREFIX) }
            else -> get(when {
                row == prefill -> InstructSequence.LAST_ASSISTANT
                first -> InstructSequence.FIRST_ASSISTANT
                else -> InstructSequence.ASSISTANT_PREFIX
            }).ifEmpty { get(InstructSequence.ASSISTANT_PREFIX) }
        }
        val end = when {
            row == prefill -> ""
            role == "user" || isSystem && systemAsUser -> get(InstructSequence.USER_SUFFIX)
            isSystem -> get(InstructSequence.SYSTEM_SUFFIX)
            else -> get(InstructSequence.ASSISTANT_SUFFIX)
        }.ifEmpty { if (row == prefill) "" else joiner }
        val lead = render(sequence, name)
        val speaker = if (!isSystem && include(name, defaultName)) "$name: " else ""
        return lead + (if (lead.isNotEmpty()) joiner else "") + speaker + row.promptText() + render(end, name)
    }
    val text = buildString {
        if (story.isNotEmpty()) {
            val lead = turn.expand(get(InstructSequence.STORY_PREFIX).replace("{{name}}", "System", ignoreCase = true))
            if (lead.isNotEmpty()) append(lead).append(joiner)
            append(story.joinToString("\n\n") { it.promptText() })
            val trail = turn.expand(get(InstructSequence.STORY_SUFFIX).replace("{{name}}", "System", ignoreCase = true))
            append(trail)
            if (wrap && !endsWith('\n')) append('\n')
        }
        val filler = get(InstructSequence.ALIGNMENT)
        if (filler.isNotBlank() && (realTurns.firstOrNull() ?: history.firstOrNull())?.string("role") == "assistant") {
            val row = jsonOf("role" to "user", "content" to turn.expand(filler), "instructFirst" to true)
            val placement = history.indexOfFirst { it.optBoolean("instructTurn") }.coerceAtLeast(0)
            history.take(placement).forEach { append(message(it)) }
            append(message(row))
            history.drop(placement).forEach { append(message(it)) }
        } else history.forEach { append(message(it)) }
        if (prefill == null && !examplesOnly) {
            val lead = when {
                turn.quiet -> prefix(InstructSequence.QUIET, InstructSequence.ASSISTANT_PREFIX, turn.quietName)
                personaRole == "user" -> prefix(InstructSequence.LAST_USER, InstructSequence.USER_PREFIX, targetName)
                else -> prefix(InstructSequence.LAST_ASSISTANT, InstructSequence.ASSISTANT_PREFIX, targetName)
            }
            append(lead)
            if (lead.isNotEmpty()) append(joiner)
            val addName = if (plain && context != null) personaRole == "user" || context.template.forceCharacterName
                else include(targetName, targetName)
            if (!turn.quiet && addName && targetName.isNotBlank()) {
                // Mistral presets may have a space on the base prefix only.
                if (!wrap && personaRole == "assistant" && lead.isNotEmpty() && !lead.last().isWhitespace() &&
                    get(InstructSequence.ASSISTANT_PREFIX).endsWith(' ')) append(' ')
                append(targetName).append(':')
            }
        }
    }
    val stops = if (examplesOnly) emptyList() else buildList {
        if (context?.template?.singleLine == true) add("\n")
        addAll(render(get(InstructSequence.STOP), targetName).lineSequence().filter(String::isNotBlank).toList())
        context?.stops(turn)?.let(::addAll)
        if (prefixesAsStops) {
            val speakers = mapOf(
                InstructSequence.USER_PREFIX to turn.user, InstructSequence.FIRST_USER to turn.user, InstructSequence.LAST_USER to turn.user,
                InstructSequence.ASSISTANT_PREFIX to turn.character, InstructSequence.FIRST_ASSISTANT to turn.character,
                InstructSequence.LAST_ASSISTANT to turn.character, InstructSequence.SYSTEM_PREFIX to "System", InstructSequence.QUIET to "System",
            )
            speakers.forEach { (field, name) ->
                val sequence = render(get(field), name).trimEnd('\r', '\n')
                if (sequence.isNotBlank()) add(joiner + sequence)
            }
        }
    }.distinct()
    return TextCompletionPrompt(text, stops)
}

internal fun JSONObject.promptText(): String = when (val content = opt("content")) {
    is String -> content
    is JSONArray -> content.objects().filter { it.string("type") == "text" }
        .map { it.string("text") }.filter(String::isNotEmpty).joinToString("\n")
    else -> ""
}

internal class InstructStopFilter(private val stops: List<String>) {
    private var pending = ""
    val hasPending get() = pending.isNotEmpty()
    var stopped = false
        private set

    fun append(fragment: String): String {
        if (stopped) return ""
        val text = pending + fragment
        val stop = stops.minOfOrNull { text.indexOf(it).let { index -> if (index < 0) Int.MAX_VALUE else index } }
            ?: Int.MAX_VALUE
        if (stop != Int.MAX_VALUE) {
            stopped = true
            pending = ""
            return text.substring(0, stop)
        }
        var hold = 0
        for (sequence in stops) {
            for (length in minOf(text.length, sequence.length - 1) downTo hold + 1) {
                if (text.regionMatches(text.length - length, sequence, 0, length)) {
                    hold = length
                    break
                }
            }
        }
        pending = text.takeLast(hold)
        return text.dropLast(hold)
    }

    fun flush() = pending.also { pending = "" }
}
