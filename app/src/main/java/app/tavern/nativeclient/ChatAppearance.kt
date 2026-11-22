package app.tavern.nativeclient

import org.json.JSONObject

internal enum class ChatLayoutMode(val wire: String, val label: String, val originalId: Int) {
    FLAT("flat", "平铺模式", 0),
    BUBBLE("bubble", "气泡模式", 1),
    DOCUMENT("document", "文档小说模式", 2);

    companion object {
        fun from(wire: String): ChatLayoutMode = entries.firstOrNull { it.wire == wire } ?: BUBBLE
    }
}

internal enum class ChatAvatarStyle(val label: String, val originalId: Int) {
    ROUND("圆形", 0), RECTANGLE("长方形", 1), SQUARE("正方形", 2), ROUNDED("圆角方形", 3);
}

internal enum class NoticePosition(val wire: String, val label: String) {
    TOP_LEFT("toast-top-left", "左上"), TOP_CENTER("toast-top-center", "顶部居中"), TOP_RIGHT("toast-top-right", "右上"),
    BOTTOM_LEFT("toast-bottom-left", "左下"), BOTTOM_CENTER("toast-bottom-center", "底部居中"),
    BOTTOM_RIGHT("toast-bottom-right", "右下");

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.wire == value }
    }
}

internal data class ChatAppearance(
    val layoutMode: ChatLayoutMode = ChatLayoutMode.BUBBLE,
    val fontSizeSp: Float = 16f,
    val lineHeightMultiplier: Float = 1.45f,
    val highlightQuotes: Boolean = true,
    val renderHtmlCards: Boolean = true,
    val showTokenBar: Boolean = true,
    val showMessageTime: Boolean = false,
    val showGenerationTimer: Boolean = false,
    val showMessageTokens: Boolean = false,
    val expandMessageActions: Boolean = false,
    val avatarStyle: ChatAvatarStyle = ChatAvatarStyle.ROUND,
    val hideAvatars: Boolean = false,
    val chatWidthPercent: Int = 100,
    val showMessageIds: Boolean = false,
    val showAllSwipeCounts: Boolean = false,
    val clickToEdit: Boolean = false,
    val compactInput: Boolean = false,
    val defaultMediaDisplay: MessageMediaDisplay = MessageMediaDisplay.LIST,
    val reducedMotion: Boolean = false,
    val noticePosition: NoticePosition = NoticePosition.BOTTOM_CENTER,
    val palette: ThemePalette = ThemePalette(),
    val showModelIcon: Boolean = false,
    val favoriteHotswap: Boolean = false,
) {
    fun clamped(): ChatAppearance = copy(
        fontSizeSp = fontSizeSp.takeIf(Float::isFinite)?.coerceIn(12f, 24f) ?: 16f,
        lineHeightMultiplier = lineHeightMultiplier.takeIf(Float::isFinite)?.coerceIn(1.15f, 2.0f) ?: 1.45f,
        chatWidthPercent = chatWidthPercent.coerceIn(25, 100),
    )

    fun json(): JSONObject = jsonOf(
        "layoutMode" to layoutMode.wire,
        "fontSizeSp" to fontSizeSp.toDouble(),
        "lineHeightMultiplier" to lineHeightMultiplier.toDouble(),
        "highlightQuotes" to highlightQuotes,
        "renderHtmlCards" to renderHtmlCards,
        "showTokenBar" to showTokenBar,
        "showMessageTime" to showMessageTime,
        "showGenerationTimer" to showGenerationTimer,
        "showMessageTokens" to showMessageTokens,
        "expandMessageActions" to expandMessageActions,
        "avatarStyle" to avatarStyle.originalId,
        "hideAvatars" to hideAvatars,
        "chatWidthPercent" to chatWidthPercent,
        "showMessageIds" to showMessageIds,
        "showAllSwipeCounts" to showAllSwipeCounts,
        "clickToEdit" to clickToEdit,
        "compactInput" to compactInput,
        "defaultMediaDisplay" to defaultMediaDisplay.wire,
        "reducedMotion" to reducedMotion,
        "noticePosition" to noticePosition.wire,
        "palette" to palette.json(),
        "showModelIcon" to showModelIcon,
        "favoriteHotswap" to favoriteHotswap,
    )

    fun writeOriginal(target: JSONObject) {
        target.put("chat_display", layoutMode.originalId)
            .put("font_scale", fontSizeSp.toDouble() / 16)
            .put("avatar_style", avatarStyle.originalId)
            .put("hideChatAvatars_enabled", hideAvatars)
            .put("chat_width", chatWidthPercent)
            .put("mesIDDisplay_enabled", showMessageIds)
            .put("show_swipe_num_all_messages", showAllSwipeCounts)
            .put("click_to_edit", clickToEdit)
            .put("compact_input_area", compactInput)
            .put("media_display", defaultMediaDisplay.wire)
            .put("reduced_motion", reducedMotion)
            .put("toastr_position", noticePosition.wire)
            .put("timestamps_enabled", showMessageTime)
            .put("timer_enabled", showGenerationTimer)
            .put("message_token_count_enabled", showMessageTokens)
            .put("expand_message_actions", expandMessageActions)
            .put("timestamp_model_icon", showModelIcon)
            .put("hotswap_enabled", favoriteHotswap)
        palette.write(target)
    }

    companion object {
        fun fromOriginal(value: JSONObject?, fallback: ChatAppearance = ChatAppearance()): ChatAppearance {
            if (value == null) return fallback
            val layout = value.optInt("chat_display", -1)
            val avatar = value.optInt("avatar_style", -1)
            val fontScale = value.optDouble("font_scale")
            return fallback.copy(
                layoutMode = ChatLayoutMode.entries.firstOrNull { it.originalId == layout } ?: fallback.layoutMode,
                fontSizeSp = fontScale.takeIf { it.isFinite() && it > 0 }?.let { (it * 16).toFloat() } ?: fallback.fontSizeSp,
                avatarStyle = ChatAvatarStyle.entries.firstOrNull { it.originalId == avatar } ?: fallback.avatarStyle,
                hideAvatars = value.optBoolean("hideChatAvatars_enabled", fallback.hideAvatars),
                chatWidthPercent = value.optInt("chat_width", fallback.chatWidthPercent),
                showMessageIds = value.optBoolean("mesIDDisplay_enabled", fallback.showMessageIds),
                showAllSwipeCounts = value.optBoolean("show_swipe_num_all_messages", fallback.showAllSwipeCounts),
                clickToEdit = value.optBoolean("click_to_edit", fallback.clickToEdit),
                compactInput = value.optBoolean("compact_input_area", fallback.compactInput),
                defaultMediaDisplay = MessageMediaDisplay.from(value.string("media_display")) ?: fallback.defaultMediaDisplay,
                reducedMotion = value.optBoolean("reduced_motion", fallback.reducedMotion),
                noticePosition = NoticePosition.from(value.string("toastr_position")) ?: fallback.noticePosition,
                showMessageTime = value.optBoolean("timestamps_enabled", fallback.showMessageTime),
                showGenerationTimer = value.optBoolean("timer_enabled", fallback.showGenerationTimer),
                showMessageTokens = value.optBoolean("message_token_count_enabled", fallback.showMessageTokens),
                expandMessageActions = value.optBoolean("expand_message_actions", fallback.expandMessageActions),
                palette = ThemePalette.from(value, fallback.palette),
                showModelIcon = value.optBoolean("timestamp_model_icon", fallback.showModelIcon),
                favoriteHotswap = value.optBoolean("hotswap_enabled", fallback.favoriteHotswap),
            ).clamped()
        }

        fun from(json: JSONObject?): ChatAppearance {
            if (json == null) return ChatAppearance()
            return ChatAppearance(
                layoutMode = ChatLayoutMode.from(json.string("layoutMode")),
                fontSizeSp = json.optDouble("fontSizeSp", 16.0).toFloat(),
                lineHeightMultiplier = json.optDouble("lineHeightMultiplier", 1.45).toFloat(),
                highlightQuotes = json.optBoolean("highlightQuotes", true),
                renderHtmlCards = json.optBoolean("renderHtmlCards", true),
                showTokenBar = json.optBoolean("showTokenBar", true),
                showMessageTime = json.optBoolean("showMessageTime"),
                showGenerationTimer = json.optBoolean("showGenerationTimer"),
                showMessageTokens = json.optBoolean("showMessageTokens"),
                expandMessageActions = json.optBoolean("expandMessageActions"),
                avatarStyle = ChatAvatarStyle.entries.firstOrNull { it.originalId == json.optInt("avatarStyle", -1) }
                    ?: ChatAvatarStyle.ROUND,
                hideAvatars = json.optBoolean("hideAvatars"),
                chatWidthPercent = json.optInt("chatWidthPercent", 100),
                showMessageIds = json.optBoolean("showMessageIds"),
                showAllSwipeCounts = json.optBoolean("showAllSwipeCounts"),
                clickToEdit = json.optBoolean("clickToEdit"),
                compactInput = json.optBoolean("compactInput"),
                defaultMediaDisplay = MessageMediaDisplay.from(json.string("defaultMediaDisplay")) ?: MessageMediaDisplay.LIST,
                reducedMotion = json.optBoolean("reducedMotion"),
                noticePosition = NoticePosition.from(json.string("noticePosition")) ?: NoticePosition.BOTTOM_CENTER,
                palette = ThemePalette.from(json.optJSONObject("palette")),
                showModelIcon = json.optBoolean("showModelIcon"),
                favoriteHotswap = json.optBoolean("favoriteHotswap"),
            ).clamped()
        }
    }
}

internal data class ContextUsageEstimate(
    val usedTokens: Int,
    val maxTokens: Int,
    val counterLabel: String,
    val hasCapturedPrompt: Boolean,
    val fromRequest: Boolean = false,
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
    val maxTokens = connection?.contextTokens(presetLimit = preset.chatCompletionFor(connection)?.contextLimit)
        ?: DEFAULT_CONTEXT_TOKENS
    val measured = capturedPrompt?.takeIf { it.model == connection?.model && it.providerId == connection.provider.id }
        ?.contextBudget
    if (draft.isBlank() && measured != null) {
        return ContextUsageEstimate(measured.used.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), measured.limit,
            measured.counter, hasCapturedPrompt = true, fromRequest = true)
    }
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
