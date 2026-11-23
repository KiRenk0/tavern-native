package app.tavern.nativeclient

import java.time.ZonedDateTime

internal class ConversationMacros(
    private val card: Card?,
    private val persona: PersonaProfile,
    private val history: List<Message> = emptyList(),
    private val group: GroupPrompt? = null,
    private val options: ConversationOptions = ConversationOptions(),
    globals: Map<String, String> = emptyMap(),
    private val model: String = "",
    private val chatId: String = "",
    private val trigger: String = "normal",
    private val firstIncluded: Int? = null,
    private val allowHidden: Boolean = false,
    val variables: MacroVariables = MacroVariables(options.variables, globals),
    private val maxContextTokens: Int? = null,
    private val maxResponseTokens: Int? = null,
    private val lastExpression: String? = null,
    private val availableExpressions: List<String>? = null,
) {
    private val now = ZonedDateTime.now()
    private val mvuState by lazy {
        app.tavern.nativeclient.script.MvuEngine.recomputeMvuState(
            messages = history,
            initial = app.tavern.nativeclient.script.MvuEngine.initialStateFrom(options.variables),
        )
    }
    private val eligible by lazy { history.indices.filter { allowHidden || !history[it].excludedFromContext } }
    private val last by lazy { eligible.lastOrNull()?.let(history::get) }
    private fun recent(role: String) = eligible.asReversed().firstOrNull { history[it].role == role }?.let(history::get)
    private fun Message?.macroText() = if (allowHidden) this?.text.orEmpty() else this?.requestText.orEmpty()

    fun expand(text: String, member: Card? = card, overrides: (String) -> String? = { null }, write: Boolean = false,
               scope: MacroVariables = variables,
               outlet: ((String) -> String)? = null): String {
        val resolving = hashSetOf<String>()
        lateinit var engine: TavernMacroRenderer
        val seed = "${chatId.ifBlank { member?.id.orEmpty() }}:${options.pickRerollSeed}"
        engine = TavernMacroRenderer(seed, { name, args ->
            val given = overrides(name)
            when {
                given != null -> given
                name == "outlet" -> outlet?.invoke(args.firstOrNull().orEmpty())
                else -> scope.resolve(name, args, write && resolving.isEmpty()) ?: read(name, args, member)?.let { value ->
                    if (name !in fieldMacros) value
                    else if (!resolving.add(name)) ""
                    else try { engine.expand(value) } finally { resolving.remove(name) }
                }
            }
        }, now = now)
        return engine.expand(text)
    }

    fun read(name: String, args: List<String> = emptyList(), member: Card? = card): String? {
        val key = args.firstOrNull().orEmpty()
        return when (name) {
            "char" -> member?.name.orEmpty()
            "user" -> persona.name
            "group", "charifnotgroup" -> group?.roster?.joinToString(", ") ?: member?.name.orEmpty()
            "groupnotmuted" -> group?.activeRoster?.joinToString(", ") ?: member?.name.orEmpty()
            "notchar" -> (group?.roster.orEmpty().filterNot { it == member?.name } + persona.name).joinToString(", ")
            "persona" -> persona.description
            "description", "chardescription" -> member?.description.orEmpty()
            "personality", "charpersonality" -> member?.personality.orEmpty()
            "scenario", "charscenario" -> options.scenario.ifBlank { member?.scenario.orEmpty() }
            "charprompt" -> options.systemPromptOverride.ifBlank { member?.instructions.orEmpty() }
            "charinstruction" -> member?.postHistory.orEmpty()
            "chardepthprompt" -> member?.depthPrompt.orEmpty()
            "charnotes", "charcreatornotes", "creatornotes" -> member?.creatorNotes.orEmpty()
            "charversion", "characterversion", "charactercardversion" -> member?.version.orEmpty()
            "mesexamples", "mesexamplesraw" -> options.exampleOverride.ifBlank { member?.examples.orEmpty() }
            "charfirstmessage", "greeting" -> when {
                key.isEmpty() || key == "0" -> member?.greeting.orEmpty()
                else -> key.toIntOrNull()?.takeIf { it > 0 }?.let { member?.alternateGreetings?.getOrNull(it - 1) }.orEmpty()
            }
            "lastmessage", "lastchatmessage" -> last.macroText()
            "lastusermessage" -> recent("user").macroText()
            "lastcharmessage" -> recent("assistant").macroText()
            "lastmessageid" -> eligible.lastOrNull()?.toString().orEmpty()
            "firstincludedmessageid" -> (firstIncluded ?: eligible.firstOrNull())?.toString().orEmpty()
            "firstdisplayedmessageid" -> (if (allowHidden && history.isNotEmpty()) 0 else eligible.firstOrNull())?.toString().orEmpty()
            "allchatrange" -> eligible.lastOrNull()?.let { "0-$it" }.orEmpty()
            "currentswipeid" -> last?.takeIf { it.variants.isNotEmpty() }?.let { (it.index + 1).toString() }.orEmpty()
            "lastswipeid" -> last?.variants?.size?.takeIf { it > 0 }?.toString().orEmpty()
            "summary" -> history.currentSummary()?.summary?.text.orEmpty()
            "get_message_variable" -> if (key.isBlank()) mvuState.statData.toString()
                else app.tavern.nativeclient.script.MvuEngine.readPath(mvuState.statData, key).orEmpty()
            "stat_data" -> mvuState.statData.toString()
            "model" -> model
            "maxcontexttokens" -> (maxContextTokens ?: DEFAULT_CONTEXT_TOKENS).toString()
            "maxresponsetokens" -> (maxResponseTokens ?: 512).toString()
            "defaultexpression" -> options.expressions.fallback.let {
                if (it == EXPRESSION_EMOJI) "neutral" else it.ifBlank { "neutral" }
            }
            "lastexpression" -> lastExpression?.takeIf(String::isNotBlank).orEmpty()
            "availableexpressions" -> {
                val labels = availableExpressions ?: member?.expressionSprites.orEmpty()
                    .map(ExpressionSprite::label)
                    .distinct()
                    .ifEmpty {
                        if (options.expressions.fallback == EXPRESSION_EMOJI) expressionEmoji.keys.toList()
                        else emptyList()
                    }
                labels.joinToString(", ")
            }
            "lastgenerationtype" -> trigger
            "ismobile" -> "true"
            "original", "input" -> ""
            else -> null
        }
    }

    private companion object {
        val fieldMacros = setOf("persona", "description", "chardescription", "personality", "charpersonality",
            "scenario", "charscenario", "charprompt", "charinstruction", "chardepthprompt", "charnotes",
            "charcreatornotes", "creatornotes", "mesexamples", "mesexamplesraw", "charfirstmessage", "greeting")
    }
}
