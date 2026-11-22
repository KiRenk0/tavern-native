package app.tavern.nativeclient

import org.json.JSONObject

private val repeatedNewlines = Regex("""(?:\r?\n){2,}""")

internal fun collapseChatNewlines(value: String, preservedPrefix: String = ""): String {
    val prefix = preservedPrefix.takeIf(value::startsWith).orEmpty()
    return prefix + repeatedNewlines.replace(value.removePrefix(prefix), "\n")
}

internal enum class EnterToSend(val originalId: Int, val label: String) {
    DISABLED(-1, "换行"), HARDWARE(0, "仅实体键盘发送"), ENABLED(1, "发送");

    fun acceptsEnter(hardwareKeyboard: Boolean): Boolean =
        this == ENABLED || this == HARDWARE && hardwareKeyboard
}

internal data class ChatHandling(
    val enterToSend: EnterToSend = EnterToSend.DISABLED,
    val continueOnSend: Boolean = false,
    val quickContinue: Boolean = false,
    val quickImpersonate: Boolean = false,
    val autoScroll: Boolean = true,
    val autoSaveEdits: Boolean = false,
    val confirmDelete: Boolean = true,
    val showSwipes: Boolean = true,
    val repairMarkdown: Boolean = false,
    val replySound: Boolean = false,
    val replySoundInBackgroundOnly: Boolean = true,
    val historyPageSize: Int = 0,
    val streamingFps: Int = 30,
    val smoothStreaming: Boolean = false,
    val smoothStreamingSpeed: Int = 50,
    val immediateReasoning: Boolean = false,
    val streamFadeIn: Boolean = false,
    val restoreDraft: Boolean = false,
    val relaxGroupTrimming: Boolean = false,
    val collapseNewlines: Boolean = false,
    val selectInputOnFocus: Boolean = false,
    val markdownHotkeys: Boolean = false,
) {
    fun shouldContinueOnSend(chat: Chat, draft: String, hasAttachments: Boolean): Boolean =
        continueOnSend && draft.isEmpty() && !hasAttachments && chat.groupId == null &&
            chat.messages.lastOrNull()?.let { it.role == "assistant" && !it.hidden && it.text.isNotBlank() } == true

    fun writeOriginal(target: JSONObject) {
        target.put("send_on_enter", enterToSend.originalId)
            .put("continue_on_send", continueOnSend)
            .put("quick_continue", quickContinue)
            .put("quick_impersonate", quickImpersonate)
            .put("auto_scroll_chat_to_bottom", autoScroll)
            .put("auto_save_msg_edits", autoSaveEdits)
            .put("confirm_message_delete", confirmDelete)
            .put("auto_fix_generated_markdown", repairMarkdown)
            .put("play_message_sound", replySound)
            .put("play_sound_unfocused", replySoundInBackgroundOnly)
            .put("chat_truncation", historyPageSize)
            .put("streaming_fps", streamingFps)
            .put("smooth_streaming", smoothStreaming)
            .put("smooth_streaming_speed", smoothStreamingSpeed)
            .put("smooth_streaming_no_think", immediateReasoning)
            .put("stream_fade_in", streamFadeIn)
            .put("restore_user_input", restoreDraft)
            .put("disable_group_trimming", relaxGroupTrimming)
            .put("collapse_newlines", collapseNewlines)
            .put("enable_auto_select_input", selectInputOnFocus)
            .put("enable_md_hotkeys", markdownHotkeys)
    }

    fun json(): JSONObject = JSONObject().also(::writeOriginal).put("swipes", showSwipes)

    companion object {
        fun fromOriginal(root: JSONObject?, fallback: ChatHandling = ChatHandling()): ChatHandling =
            from(root?.optJSONObject("power_user"), fallback).copy(
                showSwipes = root?.optBoolean("swipes", fallback.showSwipes) ?: fallback.showSwipes,
            )

        fun from(row: JSONObject?, fallback: ChatHandling = ChatHandling()): ChatHandling {
            if (row == null) return fallback
            val enterId = row.optInt("send_on_enter", fallback.enterToSend.originalId)
            return ChatHandling(
                enterToSend = EnterToSend.entries.firstOrNull { it.originalId == enterId } ?: fallback.enterToSend,
                continueOnSend = row.optBoolean("continue_on_send", fallback.continueOnSend),
                quickContinue = row.optBoolean("quick_continue", fallback.quickContinue),
                quickImpersonate = row.optBoolean("quick_impersonate", fallback.quickImpersonate),
                autoScroll = row.optBoolean("auto_scroll_chat_to_bottom", fallback.autoScroll),
                autoSaveEdits = row.optBoolean("auto_save_msg_edits", fallback.autoSaveEdits),
                confirmDelete = row.optBoolean("confirm_message_delete", fallback.confirmDelete),
                showSwipes = row.optBoolean("swipes", fallback.showSwipes),
                repairMarkdown = row.optBoolean("auto_fix_generated_markdown", fallback.repairMarkdown),
                replySound = row.optBoolean("play_message_sound", fallback.replySound),
                replySoundInBackgroundOnly = row.optBoolean("play_sound_unfocused", fallback.replySoundInBackgroundOnly),
                historyPageSize = row.optInt("chat_truncation", fallback.historyPageSize).coerceIn(0, 1000),
                streamingFps = row.optInt("streaming_fps", fallback.streamingFps).coerceIn(5, 100),
                smoothStreaming = row.optBoolean("smooth_streaming", fallback.smoothStreaming),
                smoothStreamingSpeed = row.optInt("smooth_streaming_speed", fallback.smoothStreamingSpeed).coerceIn(0, 100),
                immediateReasoning = row.optBoolean("smooth_streaming_no_think", fallback.immediateReasoning),
                streamFadeIn = row.optBoolean("stream_fade_in", fallback.streamFadeIn),
                restoreDraft = row.optBoolean("restore_user_input", fallback.restoreDraft),
                relaxGroupTrimming = row.optBoolean("disable_group_trimming", fallback.relaxGroupTrimming),
                collapseNewlines = row.optBoolean("collapse_newlines", fallback.collapseNewlines),
                selectInputOnFocus = row.optBoolean("enable_auto_select_input", fallback.selectInputOnFocus),
                markdownHotkeys = row.optBoolean("enable_md_hotkeys", fallback.markdownHotkeys),
            )
        }
    }
}
