package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject

internal const val DEFAULT_CAPTION_PROMPT = "请用中文描述这张图片中可见的人物、动作、场景、物品和重要文字。只输出图片描述。"
internal const val DEFAULT_CAPTION_TEMPLATE = "[图片描述：{{caption}}]"
internal const val MAX_IMAGE_CAPTION = 4_000
private const val MAX_CAPTION_CONTEXT = 24_000
private val captionMacro = Regex("""\{\{(caption|user|char)\}\}""")

internal enum class CaptionSource(val label: String) {
    MULTIMODAL("多模态模型"), LOCAL("手机本地（ViT-GPT2）")
}

internal data class CaptionSettings(
    val source: CaptionSource = CaptionSource.MULTIMODAL,
    val selection: ModelSelection? = null,
    val prompt: String = DEFAULT_CAPTION_PROMPT,
    val template: String = DEFAULT_CAPTION_TEMPLATE,
    val automatic: Boolean = false,
    val ask: Boolean = false,
    val refine: Boolean = false,
    val textOnly: Boolean = false,
) {
    fun connection(prefs: Preferences, chat: Chat?) = if (selection != null) {
        prefs.resolve(selection)?.takeIf { supportsImageCaptions(it.provider) }
    } else prefs.connectionFor(chat)?.takeIf { supportsImageCaptions(it.provider) }
        ?: prefs.providers.firstOrNull { it.template == ProviderTemplate.GOOGLE && it.ready }
            ?.let { ModelConnection(it, it.defaultModel) }

    fun render(value: String, user: String, character: String): String {
        val rule = template.ifBlank { DEFAULT_CAPTION_TEMPLATE }
        if ("{{caption}}" !in rule) {
            return captionMacro.replace(rule) { if (it.groupValues[1] == "user") user else character } + "\n" + value
        }
        return captionMacro.replace(rule) {
            when (it.groupValues[1]) {
                "user" -> user
                "char" -> character
                else -> value
            }
        }
    }

    fun json() = jsonOf("source" to source.name, "selection" to selection?.json(), "prompt" to prompt,
        "template" to template, "automatic" to automatic, "ask" to ask, "refine" to refine, "textOnly" to textOnly)

    companion object {
        fun from(row: JSONObject?) = CaptionSettings(
            source = CaptionSource.entries.firstOrNull { it.name == row?.string("source") } ?: CaptionSource.MULTIMODAL,
            selection = ModelSelection.fromJson(row?.optJSONObject("selection")),
            prompt = row?.string("prompt")?.take(MAX_IMAGE_CAPTION)?.ifBlank { DEFAULT_CAPTION_PROMPT } ?: DEFAULT_CAPTION_PROMPT,
            template = row?.string("template")?.take(MAX_IMAGE_CAPTION)?.ifBlank { DEFAULT_CAPTION_TEMPLATE } ?: DEFAULT_CAPTION_TEMPLATE,
            automatic = row?.optBoolean("automatic") == true, ask = row?.optBoolean("ask") == true,
            refine = row?.optBoolean("refine") == true, textOnly = row?.optBoolean("textOnly") == true,
        )
    }
}

internal fun supportsImageCaptions(provider: Provider) =
    provider.ready && provider.template != ProviderTemplate.DEEPSEEK && provider.protocol != ApiProtocol.KOBOLD_TEXT

internal data class CaptionImageTarget(val image: ChatImage, val messageIndex: Int? = null)

internal fun Message.captionFor(image: ChatImage): String? = imageCaptions[image.id]?.takeIf(String::isNotBlank)

internal fun promptImageCaptions(history: List<Message>, settings: CaptionSettings, user: String,
                                 character: String): Map<Int, String> = buildMap {
    var remaining = MAX_CAPTION_CONTEXT
    for (index in history.indices.reversed()) {
        if (remaining <= 0) break
        val entry = history[index]
        val text = entry.displayImages.mapNotNull { image ->
            val caption = entry.captionFor(image)
            when {
                caption != null -> settings.render(caption, user, character)
                settings.textOnly && entry.role == "user" -> "[图片：${image.name.ifBlank { "未命名" }}，未添加描述]"
                else -> null
            }
        }.joinToString("\n").take(remaining)
        if (text.isNotBlank()) {
            put(index, text)
            remaining -= text.length
        }
    }
}

internal suspend fun captionMultimodal(connection: ModelConnection, image: ChatImage, bytes: ByteArray,
                                       instruction: String): String {
    require(bytes.isNotEmpty() && bytes.size <= MAX_STORED_IMAGE) { "图片大小无效" }
    val messages = JSONArray(PromptLine("user", instruction.ifBlank { DEFAULT_CAPTION_PROMPT }, listOf(image.dataUrl(bytes))).wireRows())
    return ModelService.utility(connection, messages, maxTokens = null).take(MAX_IMAGE_CAPTION)
}

internal fun readImageCaptions(value: JSONObject?): Map<String, String> {
    if (value == null) return emptyMap()
    return value.keys().asSequence().mapNotNull { id ->
        (value.opt(id) as? String)?.trim()?.take(MAX_IMAGE_CAPTION)?.takeIf(String::isNotBlank)?.let { id to it }
    }.take(MAX_CHAT_IMAGES + 1).toMap()
}
