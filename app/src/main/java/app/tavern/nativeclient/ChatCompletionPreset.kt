package app.tavern.nativeclient

import org.json.JSONObject

internal enum class ChatSpeakerNames(val wire: Int, val label: String) {
    NONE(-1, "省略名称"),
    DEFAULT(0, "原版默认"),
    API_FIELD(1, "使用 API 的 name 字段"),
    CONTENT(2, "把名称写入正文"),
}

internal enum class ContinueSpacing(val separator: String, val label: String) {
    NONE("", "直接拼接"),
    SPACE(" ", "空格"),
    LINE("\n", "换行"),
    PARAGRAPH("\n\n", "空一行"),
}

internal data class ChatCompletionPreset(
    val names: ChatSpeakerNames? = null,
    val spacing: ContinueSpacing? = null,
    val contextLimit: Int? = null,
    val streaming: Boolean? = null,
    val images: ReplyImageOptions = ReplyImageOptions(),
    val webSearch: Boolean = false,
) {
    fun writeTo(json: JSONObject) {
        names?.let { json.put("names_behavior", it.wire) }
        spacing?.let { json.put("continue_postfix", it.separator) }
        contextLimit?.let { json.put("openai_max_context", it) }
        streaming?.let { json.put("stream_openai", it) }
        images.writeTo(json)
        if (webSearch) json.put("enable_web_search", true)
    }

    companion object {
        fun read(json: JSONObject): ChatCompletionPreset = ChatCompletionPreset(
            names = ChatSpeakerNames.entries.firstOrNull { it.wire == json.optInt("names_behavior", Int.MIN_VALUE) }
                ?: ChatSpeakerNames.API_FIELD.takeIf { json.optBoolean("names_in_completion") },
            spacing = (json.opt("continue_postfix") as? String)?.let { value ->
                ContinueSpacing.entries.firstOrNull { it.separator == value }
            },
            contextLimit = json.optInt("openai_max_context").takeIf { it in 1..MAX_CONTEXT_TOKENS },
            streaming = json.opt("stream_openai") as? Boolean,
            images = ReplyImageOptions.read(json),
            webSearch = json.optBoolean("enable_web_search"),
        )
    }
}

internal val Provider.hasChatNameField: Boolean
    get() = !usesTextCompletion && template != ProviderTemplate.GOOGLE &&
        protocol in setOf(ApiProtocol.OPENAI_COMPATIBLE, ApiProtocol.OPENROUTER, ApiProtocol.AZURE_OPENAI)

internal val Provider.acceptsChatContinuation: Boolean
    get() = !usesTextCompletion && template != ProviderTemplate.GOOGLE &&
        (hasChatNameField || protocol == ApiProtocol.ANTHROPIC && thinkingBudget == null)

private val invalidSpeakerCharacter = Regex("[^a-zA-Z0-9_]")

internal fun chatApiName(name: String): String? =
    name.takeIf(String::isNotBlank)?.replace(invalidSpeakerCharacter, "_")?.take(64)

internal fun PromptPreset?.chatCompletionFor(connection: ModelConnection?): ChatCompletionPreset? =
    this?.chatCompletion?.takeIf { connection != null && !connection.provider.usesTextCompletion }

internal fun PromptPreset?.emptyChatInput(connection: ModelConnection?): String =
    if (chatCompletionFor(connection) != null) this?.text(PresetUtility.EMPTY_INPUT).orEmpty() else ""
