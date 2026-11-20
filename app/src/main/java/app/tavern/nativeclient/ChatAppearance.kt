package app.tavern.nativeclient

import org.json.JSONObject

internal enum class ChatLayoutMode(val wire: String, val label: String) {
    BUBBLE("bubble", "气泡模式"),
    DOCUMENT("document", "文档小说模式");

    companion object {
        fun from(wire: String): ChatLayoutMode = entries.firstOrNull { it.wire == wire } ?: BUBBLE
    }
}

internal data class ChatAppearance(
    val layoutMode: ChatLayoutMode = ChatLayoutMode.BUBBLE,
    val fontSizeSp: Float = 16f,
    val lineHeightMultiplier: Float = 1.45f,
    val highlightQuotes: Boolean = true,
    val renderHtmlCards: Boolean = true,
    val showTokenBar: Boolean = true,
) {
    fun clamped(): ChatAppearance = copy(
        fontSizeSp = fontSizeSp.takeIf(Float::isFinite)?.coerceIn(12f, 24f) ?: 16f,
        lineHeightMultiplier = lineHeightMultiplier.takeIf(Float::isFinite)?.coerceIn(1.15f, 2.0f) ?: 1.45f,
    )

    fun json(): JSONObject = jsonOf(
        "layoutMode" to layoutMode.wire,
        "fontSizeSp" to fontSizeSp.toDouble(),
        "lineHeightMultiplier" to lineHeightMultiplier.toDouble(),
        "highlightQuotes" to highlightQuotes,
        "renderHtmlCards" to renderHtmlCards,
        "showTokenBar" to showTokenBar,
    )

    companion object {
        fun from(json: JSONObject?): ChatAppearance {
            if (json == null) return ChatAppearance()
            return ChatAppearance(
                layoutMode = ChatLayoutMode.from(json.string("layoutMode")),
                fontSizeSp = json.optDouble("fontSizeSp", 16.0).toFloat(),
                lineHeightMultiplier = json.optDouble("lineHeightMultiplier", 1.45).toFloat(),
                highlightQuotes = json.optBoolean("highlightQuotes", true),
                renderHtmlCards = json.optBoolean("renderHtmlCards", true),
                showTokenBar = json.optBoolean("showTokenBar", true),
            ).clamped()
        }
    }
}

internal data class ContextUsageEstimate(
    val usedTokens: Int,
    val maxTokens: Int,
    val counterLabel: String,
    val hasCapturedPrompt: Boolean,
) {
    val fraction: Float
        get() = if (maxTokens <= 0) 0f else (usedTokens.toFloat() / maxTokens.toFloat()).coerceIn(0f, 1f)
}

internal fun estimateConversationTokens(
    chat: Chat,
    card: Card?,
    persona: PersonaProfile,
    preset: PromptPreset?,
    draft: String,
    connection: ModelConnection?,
    counter: TextTokenCounter,
    capturedPrompt: PromptCapture?,
): ContextUsageEstimate {
    val maxTokens = connection?.contextTokens() ?: DEFAULT_CONTEXT_TOKENS
    val visibleHistory = chat.messages.filterNot(Message::excludedFromContext)
    val basePromptTokens = buildList {
        card?.let {
            add(it.description)
            add(it.personality)
            add(it.scenario)
            add(it.instructions)
            add(it.postHistory)
            add(it.examples)
            add(it.depthPrompt)
        }
        add(persona.description)
        add(chat.options.scenario)
        add(chat.options.exampleOverride)
        add(chat.options.systemPromptOverride)
        add(chat.options.authorNote?.text.orEmpty())
        add(if (chat.options.memory.enabled) chat.messages.currentSummary()?.summary?.text.orEmpty() else "")
        preset?.prompts?.filter { it.included && it.enabled }?.forEach { add(it.text) }
    }.filter(String::isNotBlank).sumOf { counter.count(it) + 4 }
    val historyTokens = visibleHistory.sumOf { message ->
        val body = message.requestText
        val reasoning = message.reasoning.orEmpty()
        counter.count(body) + counter.count(reasoning) + 6
    }
    val draftTokens = if (draft.isBlank()) 0 else counter.count(draft) + 4
    val loreTokens = capturedPrompt?.loreBudget?.used ?: 0
    val estimated = basePromptTokens + historyTokens + loreTokens + draftTokens
    return ContextUsageEstimate(
        usedTokens = estimated,
        maxTokens = maxTokens,
        counterLabel = counter.label,
        hasCapturedPrompt = capturedPrompt != null,
    )
}
