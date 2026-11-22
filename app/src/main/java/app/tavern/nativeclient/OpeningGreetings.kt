package app.tavern.nativeclient

internal fun openingGreeting(
    chat: Chat, card: Card, prefs: Preferences, persona: PersonaProfile, preset: PromptPreset?, preferred: String,
): Pair<Message?, MacroVariableChanges> {
    val choices = card.greetings.let { if (preferred.isNotBlank() && preferred !in it) listOf(preferred) + it else it }
    val regex = prefs.regex.runtime(card, persona.name, preset)
    val model = prefs.connectionFor(chat)?.model.orEmpty()
    val rendered = choices.mapNotNull { source ->
        val macros = ConversationMacros(card, persona, options = chat.options, globals = prefs.scriptVariables,
            model = model, chatId = chat.id)
        val text = regex.transform(macros.expand(source, write = true), RegexSite.REPLY, RegexStage.STORAGE)
        text.takeIf(String::isNotBlank)?.let { GreetingChoice(source, it, macros.variables.changes()) }
    }
    if (rendered.isEmpty()) return null to MacroVariableChanges()
    val index = rendered.indexOfFirst { it.source == preferred }.coerceAtLeast(0)
    val selected = rendered[index]
    val variants = rendered.map(GreetingChoice::text).takeIf { it.size > 1 }.orEmpty()
    return Message("assistant", card.name, selected.text, characterId = card.id, variants = variants, index = index,
        openingGreeting = true) to selected.variables
}

private data class GreetingChoice(val source: String, val text: String, val variables: MacroVariableChanges)
