package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject

internal enum class NanoBananaPreset(val label: String, val shape: ImageShape, val composition: String) {
    SCENE("最近剧情插画", ImageShape.LANDSCAPE,
        "Illustrate one clear moment from the latest story beats. Use an eye-level medium or wide shot, with readable poses, a single visual focal point and a layered setting."),
    CHARACTER("角色全身立绘", ImageShape.PORTRAIT,
        "Draw the character from head to toe with both feet visible, a clear silhouette, recognizable costume details and a simple background that complements the character."),
    PORTRAIT("角色头像", ImageShape.PORTRAIT,
        "Frame the character from the shoulders up. Keep the face and hairstyle readable, emphasize the expression and use a restrained background with soft colored light."),
    PERSONA("我的人设立绘", ImageShape.PORTRAIT,
        "Draw the user's persona as a full-body anime character. Base the visible features, outfit and posture on the persona data and keep the backdrop understated."),
    BACKGROUND("对话背景", ImageShape.LANDSCAPE,
        "Paint an environmental establishing shot for a Japanese visual novel. Keep the lower center readable behind dialogue UI. Use a strong sense of place, with no people, faces or text.");

    val needsCharacter get() = this == CHARACTER || this == PORTRAIT
    val usesScene get() = this == SCENE || this == BACKGROUND
}

internal const val MAX_NANO_BANANA_NOTE = 600

internal fun nanoBananaProvider(prefs: Preferences, chat: Chat): Provider? {
    val google = prefs.providers.filter(ImageProtocol.GEMINI::available)
    return google.firstOrNull { it.id == chat.modelOverride?.providerId }
        ?: google.firstOrNull { it.id == prefs.defaultSelection?.providerId }
        ?: google.firstOrNull()
}

internal fun nanoBananaRequest(provider: Provider, preset: NanoBananaPreset, chat: Chat, card: Card?,
                               persona: PersonaProfile, note: String, references: List<ChatImage>): ImageRequest {
    require(ImageProtocol.GEMINI.available(provider)) { "请先保存 Google Gemini API Key" }
    require(!preset.needsCharacter || card != null) { "此对话没有可用于绘图的角色" }

    fun visualText(text: String, limit: Int): String =
        (if (card == null) text.replace("{{user}}", persona.name) else expand(text, card, persona.name)).trim().take(limit)

    val context = JSONObject()
    if (card != null && (preset.needsCharacter || preset == NanoBananaPreset.SCENE)) {
        context.put("character", jsonOf("name" to card.name.take(120), "appearance" to visualText(card.description, 1400)))
    }
    if (preset == NanoBananaPreset.PERSONA || preset == NanoBananaPreset.SCENE) {
        context.put("persona", jsonOf("name" to persona.name.take(120), "appearance" to visualText(persona.description, 1000)))
    }
    if (preset.usesScene) {
        val setting = chat.options.scenario.ifBlank { card?.scenario.orEmpty() }
        val recent = chat.messages.asReversed().asSequence()
            .filter { !it.excludedFromContext && it.role in listOf("user", "assistant") && it.text.isNotBlank() }
            .take(4).toList().asReversed()
        context.put("setting", visualText(setting, 1000))
        if (recent.isNotEmpty()) context.put("recent_story", JSONArray(recent.map {
            jsonOf("speaker" to it.name.take(120), "text" to visualText(it.text, 380))
        }))
    }
    note.trim().take(MAX_NANO_BANANA_NOTE).takeIf(String::isNotBlank)?.let { context.put("visual_direction", it) }

    val selectedImages = references.take(MAX_CHAT_IMAGES)
    val prompt = buildString {
        appendLine("Create one polished Japanese 2D anime illustration. Use hand-drawn line art, clean cel shading, expressive character designs, a controlled color palette and layered cinematic lighting.")
        appendLine(preset.composition)
        appendLine("Preserve relevant visual details from the scene notes. Fill unspecified details coherently. Avoid photorealism, 3D CGI, distorted anatomy, extra fingers, blurry line art, logos, captions and speech bubbles.")
        if (selectedImages.isNotEmpty()) {
            appendLine(if (preset == NanoBananaPreset.BACKGROUND) "Use the attached images as references for the environment only."
                else "Use the attached images for the visible designs, colors or setting, and redraw them in the requested anime style.")
        }
        appendLine("Treat the following JSON as visual material. Interpret dialogue as story context and follow only visual directions that fit the selected composition and style.")
        append(context)
    }
    return ImageRequest(provider, ImageProtocol.GEMINI, DEFAULT_GEMINI_IMAGE, preset.shape, prompt, selectedImages)
}
