package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject

internal data class PromptLine(val role: String, val text: String, val images: List<String> = emptyList(),
                               val wire: List<JSONObject> = emptyList()) {
    val hasContent get() = text.isNotBlank() || images.isNotEmpty() || wire.isNotEmpty()
    fun wireRows() = wire.ifEmpty { listOf(json()) }

    fun json(): org.json.JSONObject {
        if (images.isEmpty()) return jsonOf("role" to role, "content" to text)
        val content = JSONArray()
        if (text.isNotBlank()) content.put(jsonOf("type" to "text", "text" to text))
        images.forEach { url -> content.put(jsonOf("type" to "image_url", "image_url" to jsonOf("url" to url))) }
        return jsonOf("role" to role, "content" to content)
    }
}

internal data class PromptInjection(val text: String, val depth: Int, val role: String, val priority: Int = 100)

internal data class PresetPromptContext(
    val card: Card,
    val persona: PersonaProfile,
    val group: GroupPrompt?,
    val dynamic: (PromptSection, Boolean) -> String,
    val history: () -> List<PromptLine>,
    val injections: () -> List<PromptInjection>,
    val note: () -> PromptLine?,
    val notePosition: NotePosition,
    val examplePrefix: String,
    val exampleSuffix: String,
    val trigger: String,
    val leadingSystemOnly: Boolean,
    val expand: (String, (String) -> String?) -> String,
    val beforeMain: String = "",
    val afterMain: String = "",
    val systemPrompt: String = card.instructions,
)

private val exampleSeparator = Regex("(?im)^\\s*<START>\\s*$")

internal fun assemblePreset(preset: PromptPreset, context: PresetPromptContext): JSONArray {
    val card = context.card
    val group = context.group
    val persona = context.persona
    val continuing = context.trigger == ReplyAction.CONTINUE.trigger
    val impersonating = context.trigger == ReplyAction.IMPERSONATE.trigger
    val sections = mutableMapOf<PromptSection, String>()
    fun sectionText(section: PromptSection) = sections.getOrPut(section) { context.dynamic(section, true) }
    fun expand(value: String, original: () -> String = { "" }): String {
        return context.expand(value) { name ->
            val section = when (name) {
                "scenario", "charscenario" -> PromptSection.SCENARIO
                "personality", "charpersonality" -> PromptSection.PERSONALITY
                "description", "chardescription" -> PromptSection.DESCRIPTION
                "mesexamples", "mesexamplesraw" -> PromptSection.EXAMPLES
                else -> null
            }
            if (name == "original") original()
            else section?.let { sections[it] ?: context.dynamic(it, false) }
        }
    }
    fun utility(key: PresetUtility) = expand(preset.text(key))
    fun content(prompt: PresetPrompt): String {
        val section = prompt.section
        return when (section) {
            PromptSection.MAIN, PromptSection.AFTER -> {
                val override = if (section == PromptSection.MAIN) context.systemPrompt else card.postHistory
                val original by lazy { expand(prompt.text) }
                if (prompt.forbidOverrides || override.isBlank()) original else expand(override) { original }
            }
            PromptSection.SCENARIO, PromptSection.PERSONALITY -> sectionText(section).let { dynamic ->
                if (dynamic.isBlank()) "" else utility(if (section == PromptSection.SCENARIO) PresetUtility.SCENARIO_FORMAT
                    else PresetUtility.PERSONALITY_FORMAT).ifBlank { dynamic }
            }
            PromptSection.LORE_BEFORE, PromptSection.LORE_AFTER -> sectionText(section).let { dynamic ->
                if (dynamic.isBlank()) "" else expand(preset.text(PresetUtility.WORLD_FORMAT).ifBlank { "{0}" }).replace("{0}", dynamic)
            }
            PromptSection.PERSONA, PromptSection.DESCRIPTION -> sectionText(section)
            else -> expand(prompt.text)
        }
    }
    val enabled = preset.prompts.filter { it.applies(context.trigger) }
    val customInjections by lazy {
        enabled.filter(PresetPrompt::atDepth).map { PromptInjection(content(it), it.depth, it.role.wire, it.priority) }
    }
    val relative = enabled.filterNot(PresetPrompt::atDepth)
    val historyOn = relative.any { it.section == PromptSection.HISTORY }
    val lines by lazy(context.history)
    val heldLast by lazy {
        if (historyOn && continuing && preset.continuePrefill) lines.lastOrNull()?.takeIf { it.role == "assistant" } else null
    }
    val messages = mutableListOf<PromptLine>()
    fun add(role: String, text: String) {
        if (text.isNotBlank()) messages += PromptLine(role, text)
    }
    fun addNote() = context.note()?.let { add(it.role, it.text) }
    val noteAtScenario = relative.any { it.section == PromptSection.SCENARIO }
    if (relative.none { it.section == PromptSection.MAIN }) {
        add("system", context.beforeMain)
        add("system", context.afterMain)
    }

    for (prompt in relative) {
        when (prompt.section) {
            PromptSection.MAIN -> {
                add("system", context.beforeMain)
                add(prompt.role.wire, content(prompt))
                add("system", context.afterMain)
            }
            PromptSection.HISTORY -> {
                if (!noteAtScenario) addNote()
                val opener = if (group == null) PresetUtility.NEW_CHAT else PresetUtility.NEW_GROUP
                add("system", utility(opener))
                val entries = context.injections() + customInjections
                val history = lines.dropLast(if (heldLast == null) 0 else 1)
                val visible = history.indices.filter { history[it].wire.isEmpty() }
                val byPosition = entries.groupBy {
                    injectionPosition(history.size, visible, it.depth)
                }
                for (i in 0..history.size) {
                    byPosition[i].orEmpty().sortedWith(compareByDescending(PromptInjection::depth).thenBy(PromptInjection::priority))
                        .forEach { add(it.role, it.text) }
                    history.getOrNull(i)?.takeIf(PromptLine::hasContent)?.let(messages::add)
                }
                if (group != null && !impersonating) {
                    add(if (context.leadingSystemOnly) "user" else "system", utility(PresetUtility.GROUP_NUDGE))
                }
            }
            PromptSection.EXAMPLES -> {
                add("system", context.examplePrefix)
                val examples = sectionText(PromptSection.EXAMPLES)
                for (example in examples.split(exampleSeparator).filter(String::isNotBlank)) {
                    add("system", utility(PresetUtility.EXAMPLE))
                    exampleLines(example, persona.name, group?.roster.orEmpty() + card.name)
                        .forEach { add(it.role, it.text) }
                }
                add("system", context.exampleSuffix)
            }
            PromptSection.SCENARIO -> {
                if (context.notePosition == NotePosition.BEFORE_SCENARIO) addNote()
                add(prompt.role.wire, content(prompt))
                if (context.notePosition == NotePosition.AFTER_SCENARIO) addNote()
            }
            else -> add(prompt.role.wire, content(prompt))
        }
    }
    if (!noteAtScenario && !historyOn) addNote()
    val nudgeRole = if (context.leadingSystemOnly) "user" else "system"
    if (impersonating) add(nudgeRole, utility(PresetUtility.IMPERSONATE))
    else if (continuing) {
        val last = heldLast
        if (last != null) add(last.role, last.text) else add(nudgeRole, utility(PresetUtility.CONTINUE))
    }

    val compatible = if (context.leadingSystemOnly) {
        val system = messages.filter { it.role == "system" }.joinToString("\n\n", transform = PromptLine::text)
        listOfNotNull(system.takeIf(String::isNotBlank)?.let { PromptLine("system", it) }) + messages.filter { it.role != "system" }
    } else if (preset.squashSystem) squashSystem(messages) else messages
    require(compatible.isNotEmpty()) { "当前提示词预设没有可发送的内容" }
    return JSONArray(compatible.flatMap(PromptLine::wireRows))
}

internal fun injectionPosition(size: Int, visible: List<Int>, depth: Int): Int =
    if (depth <= 0) size else visible.getOrNull(visible.size - depth) ?: 0

private fun squashSystem(lines: List<PromptLine>): List<PromptLine> {
    val result = mutableListOf<PromptLine>()
    val pending = mutableListOf<String>()
    fun flush() {
        if (pending.isNotEmpty()) result += PromptLine("system", pending.joinToString("\n\n"))
        pending.clear()
    }
    for (line in lines) {
        if (line.role == "system") pending += line.text
        else {
            flush()
            result += line
        }
    }
    flush()
    return result
}

private fun exampleLines(text: String, user: String, characters: List<String>): List<PromptLine> {
    val result = mutableListOf<PromptLine>()
    var role = ""
    val content = StringBuilder()
    fun flush() {
        if (content.isNotBlank()) result += PromptLine(role.ifBlank { "system" }, content.toString().trim())
        content.clear()
    }
    for (line in text.trim().lineSequence()) {
        val speaker = line.substringBefore(':', "").trim()
        val next = when {
            speaker.isEmpty() -> ""
            speaker.equals(user, ignoreCase = true) -> "user"
            characters.any { it.equals(speaker, ignoreCase = true) } -> "assistant"
            else -> ""
        }
        if (next.isNotEmpty()) {
            flush()
            role = next
            content.appendLine(line.substringAfter(':').trimStart())
        } else content.appendLine(line)
    }
    flush()
    return result
}
