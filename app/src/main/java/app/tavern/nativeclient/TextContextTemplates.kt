package app.tavern.nativeclient

import com.github.jknack.handlebars.Context
import com.github.jknack.handlebars.EscapingStrategy
import com.github.jknack.handlebars.Handlebars
import com.github.jknack.handlebars.context.MapValueResolver
import com.github.jknack.handlebars.io.AbstractTemplateLoader
import com.github.jknack.handlebars.io.TemplateSource
import org.json.JSONObject
import java.io.FileNotFoundException
import java.io.Writer

internal const val CONTEXT_STORY_LIMIT = 32_768
internal const val CONTEXT_SEPARATOR_LIMIT = 4096
internal const val CONTEXT_DEPTH_LIMIT = 10_000

internal data class TextContextTemplate(
    val name: String,
    val story: String = "",
    val exampleSeparator: String = "",
    val chatStart: String = "",
    val separatorsAsStops: Boolean = false,
    val namesAsStops: Boolean = true,
    val forceCharacterName: Boolean = true,
    val inChat: Boolean = false,
    val depth: Int = 1,
    val role: PromptRole = PromptRole.SYSTEM,
    val singleLine: Boolean = false,
    val trimSentences: Boolean = false,
    val skipExamples: Boolean = false,
) {
    fun json() = jsonOf(
        "name" to name, "story_string" to story,
        "example_separator" to exampleSeparator, "chat_start" to chatStart,
        "use_stop_strings" to separatorsAsStops, "names_as_stop_strings" to namesAsStops,
        "always_force_name2" to forceCharacterName, "story_string_position" to if (inChat) 1 else 0,
        "story_string_depth" to depth, "story_string_role" to PromptRole.entries.indexOf(role),
        "single_line" to singleLine, "trim_sentences" to trimSentences,
        "strip_examples" to skipExamples.takeIf { it },
    ).apply { if (!skipExamples) remove("strip_examples") }

    fun validated(checkStory: Boolean = true): TextContextTemplate {
        require(name.isNotBlank() && name.length <= 80) { "请输入 1–80 个字符的模板名称" }
        require(story.length <= CONTEXT_STORY_LIMIT) { "故事正文模板不能超过 $CONTEXT_STORY_LIMIT 个字符" }
        require(exampleSeparator.length <= CONTEXT_SEPARATOR_LIMIT && chatStart.length <= CONTEXT_SEPARATOR_LIMIT) {
            "分隔符不能超过 $CONTEXT_SEPARATOR_LIMIT 个字符"
        }
        require(depth in 0..CONTEXT_DEPTH_LIMIT) { "故事正文注入深度应在 0–$CONTEXT_DEPTH_LIMIT 之间" }
        if (checkStory) StoryString.compile(story)
        return copy(name = name.trim())
    }

    companion object {
        fun parse(raw: String, fallback: String = "") =
            fromJson(boundedJsonObject(raw.trimStart('\uFEFF'), "无法读取上下文模板 JSON"), fallback)

        fun fromJson(json: JSONObject, fallback: String = "", checkStory: Boolean = true): TextContextTemplate {
            val story = requireNotNull(json.opt("story_string") as? String) { "JSON 缺少原版 story_string 字段" }
            return TextContextTemplate(
                name = json.string("name").ifBlank { fallback },
                story = story,
                exampleSeparator = json.string("example_separator"),
                chatStart = json.string("chat_start"),
                separatorsAsStops = json.optBoolean("use_stop_strings"),
                namesAsStops = json.optBoolean("names_as_stop_strings", true),
                forceCharacterName = json.optBoolean("always_force_name2", true),
                inChat = json.optInt("story_string_position") == 1,
                depth = json.optInt("story_string_depth", 1),
                role = PromptRole.entries.getOrNull(json.optInt("story_string_role")) ?: PromptRole.SYSTEM,
                singleLine = json.optBoolean("single_line"),
                trimSentences = json.optBoolean("trim_sentences"),
                skipExamples = json.optBoolean("strip_examples"),
            ).validated(checkStory)
        }
    }
}

internal object StoryString {
    private val engine = Handlebars(object : AbstractTemplateLoader() {
        override fun sourceAt(location: String): TemplateSource = throw FileNotFoundException(location)
    }).with(EscapingStrategy.NOOP).apply {
        // Java port extras are not part of SillyTavern's story context.
        for (helper in listOf("precompile", "embedded", "i18n", "i18nJs")) {
            registerHelper<Any>(helper) { _, _ -> "" }
        }
    }
    private val templateError = "故事正文模板语法无效"
    private val simpleStoryMacros = listOf(
        "trim", "newline", "space", "date", "time", "isodate", "isotime", "weekday", "model", "summary",
        "group", "groupnotmuted", "charifnotgroup", "notchar", "lastmessage", "lastchatmessage", "lastusermessage",
        "lastcharmessage", "lastmessageid", "lastgenerationtype", "allchatrange", "currentswipeid", "lastswipeid",
        "firstincludedmessageid", "firstdisplayedmessageid", "charnotes", "charcreatornotes", "creatornotes",
        "chardepthprompt", "charprompt", "charinstruction", "charversion", "characterversion", "charactercardversion",
        "greeting", "charfirstmessage", "ismobile", "stat_data",
        "maxcontexttokens", "maxresponsetokens", "defaultexpression", "lastexpression", "availableexpressions",
    ).joinToString("|")
    private val runtimeTags = Regex(
        """(?<![\\{])\{\{(?!\{)(?=\s*(?:[.$][\p{L}_]|//|[\w-]+::|(?:$simpleStoryMacros)\s*\}\}))""",
        RegexOption.IGNORE_CASE,
    )

    private fun prepare(story: String): Pair<String, Map<String, String>> {
        val tags = linkedMapOf<String, String>()
        var cursor = 0
        val source = buildString {
            while (true) {
                val opening = runtimeTags.find(story, cursor) ?: break
                val start = opening.range.first
                append(story, cursor, start)
                var depth = 1
                var end = start + 2
                while (end < story.length && depth > 0) {
                    when {
                        story[end] == '\\' -> end += 2
                        story.startsWith("{{", end) -> { depth++; end += 2 }
                        story.startsWith("}}", end) -> { depth--; end += 2 }
                        else -> end++
                    }
                }
                require(depth == 0) { templateError }
                val field = "__st_story_macro_${tags.size}"
                tags[field] = story.substring(start, end)
                append("{{").append(field).append("}}")
                cursor = end
            }
            append(story, cursor, story.length)
        }
        return source to tags
    }

    private fun <T> checked(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        throw IllegalArgumentException("${templateError}：${e.message.orEmpty().lineSequence().first().take(180)}", e)
    } catch (e: StackOverflowError) {
        throw IllegalArgumentException("故事正文模板嵌套过深", e)
    }

    fun compile(story: String) {
        if (story.isNotEmpty()) checked { engine.compileInline(prepare(story).first) }
    }

    fun render(story: String, values: Map<String, String>, expand: (String) -> String): String {
        if (story.isEmpty()) return ""
        return checked {
            val (source, tags) = prepare(story)
            val context = Context.newBuilder(values + tags).resolver(MapValueResolver.INSTANCE).build()
            val buffer = StringBuilder()
            val writer = object : Writer() {
                override fun write(chars: CharArray, offset: Int, length: Int) {
                    require(buffer.length + length <= 1_000_000) { "故事正文模板展开结果过长" }
                    buffer.append(chars, offset, length)
                }
                override fun flush() = Unit
                override fun close() = Unit
            }
            engine.compileInline(source).apply(context, writer)
            expand(buffer.toString()).trimStart('\r', '\n')
        }
    }
}

internal data class TextContextLayout(
    val template: TextContextTemplate,
    val examplesSeparator: String,
    val chatStart: String,
    val roster: List<String>,
) {
    fun stops(turn: InstructTurn): List<String> = buildList {
        if (template.namesAsStops && !turn.quiet) {
            if (turn.trigger == ReplyAction.IMPERSONATE.trigger) add("\n${turn.character}:")
            add("\n${turn.user}:")
            roster.filter { it != turn.character }.forEach { add("\n$it:") }
        }
        if (template.separatorsAsStops) {
            listOf(chatStart, examplesSeparator).filter(String::isNotBlank).forEach { add("\n$it") }
        }
    }
}

private const val sentenceEnds = ".!?*\")}`]\$。！？”）】’」_"

internal fun trimToCompleteSentence(text: String): String {
    var index = text.length
    while (index > 0) {
        val codePoint = text.codePointBefore(index)
        val start = index - Character.charCount(codePoint)
        val emoji = codePoint in 0x1F300..0x1FAFF || codePoint in 0x2600..0x27BF
        val punctuation = codePoint <= Char.MAX_VALUE.code && codePoint.toChar() in sentenceEnds
        if (emoji || punctuation) {
            val end = if (punctuation && start > 0 && text[start - 1].isWhitespace()) start else index
            return text.substring(0, end).trimEnd().ifBlank { text.trimEnd() }
        }
        index = start
    }
    return text.trimEnd()
}
