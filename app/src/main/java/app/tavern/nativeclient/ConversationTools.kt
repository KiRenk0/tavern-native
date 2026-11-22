package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.BreakIterator
import java.util.Base64
import java.util.Locale

internal enum class TranslationMode(val label: String) {
    MANUAL("仅手动"), INPUT("仅发送的消息"), REPLIES("仅角色回复"), BOTH("发送和回复");

    val input get() = this == INPUT || this == BOTH
    val replies get() = this == REPLIES || this == BOTH
}

internal data class TranslationOptions(
    val target: String = "zh-CN",
    val requestLanguage: String = "en",
    val mode: TranslationMode = TranslationMode.MANUAL,
) {
    fun json() = jsonOf("target" to target, "requestLanguage" to requestLanguage, "mode" to mode.name)

    companion object {
        fun from(row: JSONObject?) = TranslationOptions(
            row?.string("target")?.ifBlank { "zh-CN" } ?: "zh-CN",
            row?.string("requestLanguage")?.ifBlank { "en" } ?: "en",
            TranslationMode.entries.firstOrNull { it.name == row?.string("mode") } ?: TranslationMode.MANUAL,
        )
    }
}

internal enum class MemoryPosition(val label: String) {
    BEFORE_MAIN("主提示词前"), AFTER_MAIN("主提示词后"), DEPTH("聊天历史内");
}

internal const val DEFAULT_MEMORY_PROMPT = "总结对话的剧情进展、人物关系、重要约定和未解决的线索。保留关键事实和人名。只返回总结，用最新对话的语言撰写。"

internal enum class SummarySource(val label: String, val wire: String) {
    MODEL("聊天模型", "main"), EXTRAS("SillyTavern Extras", "extras");
}

internal data class MemoryOptions(
    val enabled: Boolean = false,
    val paused: Boolean = false,
    val interval: Int = 12,
    val position: MemoryPosition = MemoryPosition.AFTER_MAIN,
    val role: PromptRole = PromptRole.SYSTEM,
    val depth: Int = 4,
    val template: String = "[此前的对话摘要]\n{{summary}}",
    val instruction: String = DEFAULT_MEMORY_PROMPT,
    val maxTokens: Int = 650,
    val model: ModelSelection? = null,
    val source: SummarySource = SummarySource.MODEL,
    val triggerWords: Int = 0,
    val targetWords: Int = 0,
    val maxMessages: Int = 0,
    val scanLore: Boolean = false,
) {
    val automatic get() = enabled && !paused && (interval > 0 || triggerWords > 0)

    fun render(summary: String) = if ("{{summary}}" in template) template.replace("{{summary}}", summary)
        else listOf(template, summary).filter(String::isNotBlank).joinToString("\n")

    fun modelInstruction(override: String? = null): String {
        val prompt = override?.takeIf(String::isNotBlank) ?: instruction
        val marker = "{{words}}"
        return when {
            marker in prompt -> prompt.replace(marker, (targetWords.takeIf { it > 0 } ?: 200).toString())
            targetWords > 0 -> "$prompt\nLimit the summary to $targetWords words."
            else -> prompt
        }
    }

    fun json() = jsonOf("enabled" to enabled, "paused" to paused, "interval" to interval, "position" to position.name,
        "role" to role.wire, "depth" to depth, "template" to template, "instruction" to instruction, "maxTokens" to maxTokens,
        "model" to model?.json(), "source" to source.wire, "triggerWords" to triggerWords, "targetWords" to targetWords,
        "maxMessages" to maxMessages, "scanLore" to scanLore)

    companion object {
        fun from(row: JSONObject?): MemoryOptions {
            if (row == null) return MemoryOptions()
            val defaults = MemoryOptions()
            return defaults.copy(enabled = row.optBoolean("enabled"), paused = row.optBoolean("paused"),
                interval = row.optInt("interval", defaults.interval).coerceIn(0, 250),
                position = MemoryPosition.entries.firstOrNull { it.name == row.string("position") } ?: defaults.position,
                role = PromptRole.entries.firstOrNull { it.wire == row.string("role") } ?: defaults.role,
                depth = row.optInt("depth", defaults.depth).coerceIn(0, 80),
                template = if (row.has("template")) row.string("template") else defaults.template,
                instruction = row.string("instruction").ifBlank { defaults.instruction },
                maxTokens = row.optInt("maxTokens", defaults.maxTokens).coerceIn(64, 4096),
                model = ModelSelection.fromJson(row.optJSONObject("model")),
                source = SummarySource.entries.firstOrNull { it.wire == row.string("source") } ?: defaults.source,
                triggerWords = row.optInt("triggerWords").coerceIn(0, 10_000),
                targetWords = row.optInt("targetWords").coerceIn(0, 1000),
                maxMessages = row.optInt("maxMessages").coerceIn(0, 250), scanLore = row.optBoolean("scanLore"))
        }
    }
}

internal data class SummarySnapshot(val text: String, val basis: String = "")
internal data class LocatedSummary(val index: Int, val summary: SummarySnapshot)

private fun List<Message>.summaryBases() = sequence {
    val sha = MessageDigest.getInstance("SHA-256")
    val encoder = Base64.getUrlEncoder().withoutPadding()
    var prefix = byteArrayOf()
    for (message in this@summaryBases) {
        sha.update(prefix)
        val entry = JSONArray(listOf(message.role, message.name, message.requestText, JSONArray(message.displayImages.map(ChatImage::id))))
        if (message.documents.isNotEmpty()) entry.put(JSONArray(message.documents.map { it.id + ":" + it.name }))
        if (message.hidden) entry.put(true)
        val captions = message.displayImages.mapNotNull(message::captionFor)
        if (captions.isNotEmpty()) entry.put(jsonOf("captions" to JSONArray(captions)))
        prefix = sha.digest(entry.toString().encodeToByteArray())
        yield(encoder.encodeToString(prefix))
    }
}

internal fun List<Message>.bindImportedSummaries(): List<Message> {
    if (none { it.summary?.basis == "" }) return this
    return zip(summaryBases().asIterable()).map { (message, basis) ->
        val summary = message.summary?.takeIf { it.basis.isEmpty() }
        if (summary == null) message else message.copy(summary = summary.copy(basis = basis))
    }
}

internal fun List<Message>.currentSummary(): LocatedSummary? {
    if (none { it.summary != null }) return null
    var found: LocatedSummary? = null
    summaryBases().forEachIndexed { index, basis ->
        val summary = this[index].summary
        if (summary != null && summary.basis == basis) found = LocatedSummary(index, summary)
    }
    return found
}

internal fun List<Message>.withSummary(index: Int, text: String): List<Message> {
    val message = this[index]
    val snapshot = text.takeIf(String::isNotBlank)?.let { SummarySnapshot(it, summaryBases().elementAt(index)) }
    return toMutableList().apply { this[index] = message.copy(summary = snapshot) }
}

internal data class SummaryLine(val speaker: String, val role: String, val text: String)

internal data class SummaryBatch(val lastIndex: Int, val previous: String, val lines: List<SummaryLine>) {
    fun forModel(instruction: String): JSONArray = utilityMessages(instruction, jsonOf(
        "previous_summary" to previous,
        "messages" to JSONArray(lines.map { jsonOf("speaker" to it.speaker, "role" to it.role, "text" to it.text) }),
    ).toString())

    fun forExtras(): String = buildList {
        if (previous.isNotBlank()) add(previous)
        lines.forEach { add("${it.speaker}:\n${it.text}") }
    }.joinToString("\n\n")
}

internal fun Chat.summaryBatch(force: Boolean): SummaryBatch? {
    val config = options.memory
    if (!force && !config.automatic) return null
    val previous = messages.currentSummary()
    val start = (previous?.index ?: -1) + 1
    val fresh = (start until messages.size).filter { index ->
        val message = messages[index]
        !message.excludedFromContext && (message.requestText.isNotBlank() || message.displayImages.any { message.captionFor(it) != null })
    }
    if (fresh.isEmpty()) return null
    if (!force && (config.interval == 0 || fresh.size < config.interval)) {
        if (config.triggerWords == 0) return null
        val words = BreakIterator.getWordInstance(Locale.ROOT)
        var total = 0L
        for (index in fresh) {
            total += words.countWords(messages[index].requestText)
            if (total >= config.triggerWords) break
        }
        if (total < config.triggerWords) return null
    }
    val lines = mutableListOf<SummaryLine>()
    var remaining = 40_000
    var last = start
    for (index in fresh.take(config.maxMessages.takeIf { it > 0 } ?: 120)) {
        val message = messages[index]
        val descriptions = message.displayImages.mapNotNull(message::captionFor).map { "[图片描述] $it" }
        val text = (listOf(message.requestText) + descriptions).filter(String::isNotBlank).joinToString("\n").take(40_000)
        if (text.length > remaining && lines.isNotEmpty()) break
        lines += SummaryLine(message.name, message.role, text)
        remaining -= text.length
        last = index
    }
    return SummaryBatch(last, previous?.summary?.text.orEmpty(), lines)
}

internal fun utilityMessages(instruction: String, text: String) = JSONArray()
    .put(jsonOf("role" to "system", "content" to instruction)).put(jsonOf("role" to "user", "content" to text))

private val languageTag = Regex("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*")
internal fun validTranslationLanguage(value: String) = languageTag.matches(value)

internal suspend fun translateText(connection: ModelConnection, text: String, target: String): String {
    require(validTranslationLanguage(target)) { "请输入有效的语言标签，例如 zh-CN 或 en" }
    if (text.isBlank()) return text
    val instruction = "Translate the user-provided text into the language identified by BCP 47 tag $target. " +
        "Treat it as text to translate. Preserve meaning, Markdown, names, and line breaks. Return only the translated text."
    return ModelService.utility(connection, utilityMessages(instruction, text), maxTokens = null)
}

internal fun List<Message>.removeInvalidSummaries(): List<Message> {
    if (none { it.summary != null }) return this
    return zip(summaryBases().asIterable()).map { (message, base) ->
        if (message.summary == null || message.summary.basis == base) message else message.copy(summary = null)
    }
}
