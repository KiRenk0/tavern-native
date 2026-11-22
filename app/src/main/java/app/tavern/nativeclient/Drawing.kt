package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal enum class DrawingMode(val trigger: String, val label: String, val instruction: String, val forcedShape: ImageShape? = null) {
    FREE("free", "自定义提示词", "扩展用户提供的绘图提示词，保留指定主题、人物和画面风格。"),
    CHARACTER("you", "角色全身像", "描述当前角色的可见外貌、服饰和全身姿态。"),
    FACE("face", "角色头像", "描述当前角色的面部特写、发型、表情和光线。", ImageShape.PORTRAIT),
    PERSONA("me", "我的人设", "根据用户人设描述人物外貌、服装与姿态。"),
    SCENE("scene", "最近剧情", "根据聊天内容构图，用单幅画表现最近剧情中的一个清晰场景。"),
    LAST("last", "最近消息的场景", "把最后一条消息里的可见动作、人物和环境写成一张图的提示词。"),
    RAW_LAST("raw_last", "最近消息原文", ""),
    BACKGROUND("background", "对话背景", "根据当前场景描述环境、光线、色彩和景别，省略人物。", ImageShape.LANDSCAPE);

    companion object {
        fun from(text: String) = entries.firstOrNull { it.trigger.equals(text.trim(), ignoreCase = true) } ?: FREE

        fun interactive(text: String) = when (text.lowercase(Locale.ROOT)) {
            "yourself", "你", "你自己" -> CHARACTER
            "your face", "your portrait", "your selfie", "你的头像" -> FACE
            "myself", "我", "我自己" -> PERSONA
            "story", "scenario", "whole story", "剧情" -> SCENE
            "last message" -> LAST
            "scene background", "scene", "scenery", "surroundings", "environment", "背景" -> BACKGROUND
            else -> from(text)
        }
    }
}

internal data class ImageTuning(
    val negative: String = "", val width: Int? = null, val height: Int? = null, val steps: Int? = null,
    val cfg: Double? = null, val seed: Long? = null, val sampler: String = "", val scheduler: String = "",
    val skip: Int? = null, val vae: String = "", val denoise: Double? = null,
) {
    fun json() = jsonOf("negative" to negative, "width" to width, "height" to height, "steps" to steps,
        "cfg" to cfg, "seed" to seed, "sampler" to sampler, "scheduler" to scheduler, "skip" to skip, "vae" to vae,
        "denoise" to denoise)

    fun override(arguments: Map<String, String>): ImageTuning {
        fun int(key: String, range: IntRange, existing: Int?): Int? = arguments[key]?.let {
            requireNotNull(it.toIntOrNull()?.takeIf(range::contains)) { "$key 应在 ${range.first}–${range.last} 之间" }
        } ?: existing
        fun dimension(key: String, existing: Int?) = int(key, 128..4096, existing).also {
            require(it == null || it % 8 == 0) { "$key 应是 8 的倍数" }
        }
        val scale = arguments["cfg"]?.let {
            requireNotNull(it.toDoubleOrNull()?.takeIf { value -> value in 0.0..50.0 }) { "cfg 应在 0–50 之间" }
        } ?: cfg
        val seedValue = arguments["seed"]?.let {
            requireNotNull(it.toLongOrNull()?.takeIf { value -> value >= -1 }) { "seed 应是大于或等于 -1 的整数" }
        } ?: seed
        val denoiseValue = arguments["denoise"]?.let {
            requireNotNull(it.toDoubleOrNull()?.takeIf { value -> value in 0.0..1.0 }) { "denoise 应在 0–1 之间" }
        } ?: denoise
        return copy(negative = arguments["negative"] ?: negative, width = dimension("width", width),
            height = dimension("height", height), steps = int("steps", 1..150, steps), cfg = scale, seed = seedValue,
            sampler = arguments["sampler"] ?: sampler, scheduler = arguments["scheduler"] ?: scheduler,
            skip = int("skip", 1..24, skip), vae = arguments["vae"] ?: vae, denoise = denoiseValue)
    }

    companion object {
        fun from(value: JSONObject?): ImageTuning {
            if (value == null) return ImageTuning()
            fun integer(key: String) = value.opt(key)?.takeUnless { it == JSONObject.NULL }?.toString()?.toIntOrNull()
            fun dimension(key: String) = integer(key)?.takeIf { it in 128..4096 && it % 8 == 0 }
            return ImageTuning(value.string("negative"), dimension("width"), dimension("height"), integer("steps")?.takeIf { it in 1..150 },
                value.optDouble("cfg").takeIf { it in 0.0..50.0 }, value.opt("seed")?.toString()?.toLongOrNull()?.takeIf { it >= -1L },
                value.string("sampler"), value.string("scheduler"), integer("skip")?.takeIf { it in 1..24 }, value.string("vae"),
                value.optDouble("denoise").takeIf { it in 0.0..1.0 })
        }
    }
}

internal data class ImageStyle(val name: String, val prefix: String = "", val negative: String = "") {
    fun json() = jsonOf("name" to name, "prefix" to prefix, "negative" to negative)
    companion object {
        fun from(value: JSONObject): ImageStyle? = value.string("name").takeIf(String::isNotBlank)
            ?.let { ImageStyle(it, value.string("prefix"), value.string("negative")) }
    }
}

internal data class ImageProfile(
    val providerId: String, val protocol: ImageProtocol, val model: String,
    val shape: ImageShape = ImageShape.SQUARE, val tuning: ImageTuning = ImageTuning(), val workflowId: String? = null,
) {
    fun json() = jsonOf("provider" to providerId, "protocol" to protocol.name, "model" to model,
        "shape" to shape.name, "tuning" to tuning.json(), "workflow" to workflowId)

    fun resolve(providers: List<Provider>, prompt: String, references: List<ChatImage> = emptyList()): ImageRequest? {
        val provider = if (providerId == POLLINATIONS_BUILTIN_ID && protocol == ImageProtocol.POLLINATIONS) {
            pollinationsBuiltinProvider()
        } else {
            providers.firstOrNull { it.id == providerId && protocol.available(it) } ?: return null
        }
        return ImageRequest(provider, protocol, model, shape, prompt, references, tuning, workflowId)
    }

    companion object {
        fun from(value: JSONObject?): ImageProfile? {
            val id = value?.string("provider")?.takeIf(String::isNotBlank) ?: return null
            val protocol = ImageProtocol.entries.firstOrNull { it.name == value.string("protocol") } ?: return null
            val shape = ImageShape.entries.firstOrNull { it.name == value.string("shape") } ?: ImageShape.SQUARE
            return ImageProfile(id, protocol, value.string("model"), shape, ImageTuning.from(value.optJSONObject("tuning")),
                value.string("workflow").takeIf(String::isNotBlank))
        }
    }
}

internal data class DrawingSettings(
    val profile: ImageProfile? = null,
    val styles: List<ImageStyle> = emptyList(),
    val activeStyle: String = "",
    val characterPrefixes: Map<String, String> = emptyMap(),
    val templates: Map<String, String> = emptyMap(),
    val extendFree: Boolean = false,
    val interactive: Boolean = false,
    val comfyWorkflowId: String? = null,
    val functionTool: Boolean = false,
    val video: VideoProfile = VideoProfile(),
) {
    val style get() = styles.firstOrNull { it.name == activeStyle }
    fun json() = jsonOf("profile" to profile?.json(), "styles" to JSONArray(styles.map(ImageStyle::json)),
        "activeStyle" to activeStyle, "characterPrefixes" to JSONObject(characterPrefixes), "templates" to JSONObject(templates),
        "extendFree" to extendFree, "interactive" to interactive, "comfyWorkflow" to comfyWorkflowId, "functionTool" to functionTool,
        "video" to video.json())

    fun defaultProfile(providers: List<Provider>): ImageProfile? {
        profile?.takeIf { it.resolve(providers, "") != null }?.let { return it }
        val google = providers.firstOrNull(ImageProtocol.GEMINI::available)
        if (google != null) return ImageProfile(google.id, ImageProtocol.GEMINI, DEFAULT_GEMINI_IMAGE)
        return providers.firstOrNull(ImageProtocol.HORDE::available)?.let { ImageProfile(it.id, ImageProtocol.HORDE, "") }
    }

    companion object {
        fun from(value: JSONObject?): DrawingSettings {
            if (value == null) return DrawingSettings()
            return DrawingSettings(ImageProfile.from(value.optJSONObject("profile")),
                value.optJSONArray("styles")?.objects().orEmpty().mapNotNull(ImageStyle::from), value.string("activeStyle"),
                value.optJSONObject("characterPrefixes").variableStrings(), value.optJSONObject("templates").variableStrings(),
                value.optBoolean("extendFree"), value.optBoolean("interactive"),
                value.string("comfyWorkflow").takeIf(String::isNotBlank), value.optBoolean("functionTool"),
                VideoProfile.from(value.optJSONObject("video")))
        }
    }
}

internal data class DrawingRecipe(val profile: ImageProfile, val prompt: String, val references: List<ChatImage> = emptyList()) {
    fun json() = jsonOf("profile" to profile.json(), "prompt" to prompt, "references" to JSONArray(references.map(ChatImage::json)))
    fun request(prefs: Preferences, reroll: Boolean): ImageRequest {
        val retry = if (reroll && profile.tuning.seed != null) profile.copy(tuning = profile.tuning.copy(seed = -1L)) else profile
        return requireNotNull(retry.resolve(prefs.providers, prompt, references)) { "请重新配置原来的绘图服务" }
    }

    companion object {
        fun from(value: JSONObject?): DrawingRecipe? {
            val profile = ImageProfile.from(value?.optJSONObject("profile")) ?: return null
            val prompt = value?.string("prompt")?.takeIf(String::isNotBlank) ?: return null
            return DrawingRecipe(profile, prompt, value.optJSONArray("references")?.objects().orEmpty().take(MAX_CHAT_IMAGES).mapNotNull(ChatImage::from))
        }
    }
}

internal data class DrawnImageSeries(val recipe: DrawingRecipe, val images: List<ChatImage>, val selected: Int = 0, val addToGallery: Boolean = true) {
    val current get() = images.getOrNull(selected)
    fun json() = jsonOf("recipe" to recipe.json(), "images" to JSONArray(images.map(ChatImage::json)),
        "selected" to selected, "addToGallery" to addToGallery)

    companion object {
        fun from(value: JSONObject?): DrawnImageSeries? {
            val recipe = DrawingRecipe.from(value?.optJSONObject("recipe")) ?: return null
            val images = value?.optJSONArray("images")?.objects().orEmpty().mapNotNull(ChatImage::from)
            if (images.isEmpty()) return null
            return DrawnImageSeries(recipe, images, value!!.optInt("selected").coerceIn(images.indices), value.optBoolean("addToGallery", true))
        }
    }
}

internal data class GalleryImage(val image: ChatImage, val prompt: String, val created: Long = System.currentTimeMillis())
internal data class ImageGallery(val title: String, val items: List<GalleryImage>)
internal data class ImageMarkup(val text: String, val images: List<ChatImage>)

internal fun ChatImage.drawingAddress() = "tavern://image/$filename"
private val drawUrl = Regex("!\\[([^\\]]*)\\]\\(tavern://image/([a-f0-9]{64})\\.(png|jpg)\\)", RegexOption.IGNORE_CASE)

internal fun parseDrawingMarkup(text: String, exists: (ChatImage) -> Boolean): ImageMarkup {
    val found = linkedMapOf<String, ChatImage>()
    val cleaned = drawUrl.replace(text) { match ->
        val id = match.groupValues[2].lowercase(Locale.ROOT)
        val format = ImageFormat.entries.first { it.suffix.equals(match.groupValues[3], true) }
        val image = ChatImage(id, match.groupValues[1].trim().take(120).ifBlank { "绘图" }, format)
        if (!exists(image) || (found.size >= MAX_CHAT_IMAGES && id !in found)) match.value
        else { found[id] = image; "" }
    }
    return ImageMarkup(if (found.isEmpty()) text else cleaned.trim(), found.values.toList())
}

internal fun combineImagePrompts(vararg parts: String) = parts.map(String::trim).filter(String::isNotBlank).joinToString(", ")

internal fun prepareDrawingPrompt(prompt: String, settings: DrawingSettings, card: Card?, persona: PersonaProfile,
                                  mode: DrawingMode, singleCharacter: Boolean): String {
    val character = card?.id?.let { settings.characterPrefixes[it] }.orEmpty()
    val text = when {
        "{{charPrefix}}" in prompt -> prompt.replace("{{charPrefix}}", character.takeIf { singleCharacter }.orEmpty())
        singleCharacter && mode != DrawingMode.FREE && mode != DrawingMode.BACKGROUND -> combineImagePrompts(character, prompt)
        else -> prompt
    }
    val resolved = if (card != null) expand(text, card, persona.name) else text.replace("{{user}}", persona.name)
    val prefix = settings.style?.prefix.orEmpty()
    return if ("{prompt}" in prefix) prefix.replace("{prompt}", resolved) else combineImagePrompts(prefix, resolved)
}

internal fun drawingPromptContext(mode: DrawingMode, prompt: String, chat: Chat, card: Card?, persona: PersonaProfile,
                                 settings: DrawingSettings): JSONArray {
    val history = chat.messages.filter { !it.excludedFromContext && it.text.isNotBlank() }.takeLast(if (mode == DrawingMode.LAST) 1 else 14)
    val instruction = settings.templates[mode.trigger]?.takeIf(String::isNotBlank) ?: mode.instruction
    val body = jsonOf("character" to card?.name.orEmpty(), "appearance" to card?.description.orEmpty().take(5000),
        "persona" to persona.name, "persona_description" to persona.description.take(5000),
        "setting" to chat.options.scenario.ifBlank { card?.scenario.orEmpty() }.take(4000), "user_prompt" to prompt,
        "dialogue" to JSONArray(history.map { jsonOf("speaker" to it.name, "text" to it.text.takeLast(1800)) }))
    return utilityMessages("$instruction\n根据提供的资料写绘图模型可直接使用的视觉提示词。只输出提示词正文。", body.toString())
}

private val interactiveImage = Regex("(?i)\\b(?:send|mail|imagine|generate|make|create|draw|paint|render)\\b.{0,10}?\\b(?:pic|picture|image|drawing|painting|photo|photograph)\\b\\s*(?:(?:of|depicting|showing)\\s+)?(?:(?:a|an|this)\\s+)?(.+)")
private val interactiveChinese = Regex("(?:生成|绘制|画|发)[^。？！\\n]{0,10}?(?:图片|照片|画)[：: ]*(.+)")

internal fun interactiveDrawing(text: String): String? = (interactiveImage.find(text) ?: interactiveChinese.find(text))
    ?.groupValues?.get(1)?.trim()?.trimEnd('.', '!', '?', '。', '！', '？')?.takeIf(String::isNotBlank)
