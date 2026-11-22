package app.tavern.nativeclient

import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal enum class ModelMatch(val wire: String, val label: String) {
    EXACT("exact", "模型 ID"), REGEX("regex", "模型 ID 正则"), TEMPLATE_HASH("hash", "模板哈希");
}

internal data class ModelTemplatePair(
    val instruct: InstructTemplate? = null,
    val context: TextContextTemplate? = null,
) {
    val description: String get() = listOfNotNull(context?.name, instruct?.name).joinToString(" / ").ifEmpty { "纯文本默认格式" }

    fun json() = jsonOf("instruct" to instruct?.json(), "context" to context?.json())

    companion object {
        fun read(json: JSONObject?) = if (json == null) ModelTemplatePair() else ModelTemplatePair(
            json.optJSONObject("instruct")?.let { runCatching { InstructTemplate.fromJson(it) }.getOrNull() },
            json.optJSONObject("context")?.let { runCatching { TextContextTemplate.fromJson(it, checkStory = false) }.getOrNull() },
        )
    }
}

internal data class ModelFormatRule(val id: String = newId(), val match: ModelMatch = ModelMatch.EXACT,
                                    val target: String = "", val templates: ModelTemplatePair = ModelTemplatePair()) {
    fun valid(): ModelFormatRule {
        val name = target.trim()
        require(name.isNotEmpty() && name.length <= 512) { "请输入 1–512 个字符的匹配条件" }
        if (match == ModelMatch.REGEX) require(validModelRegex(name)) { "模型 ID 正则格式无效" }
        if (match == ModelMatch.TEMPLATE_HASH) require(formatHash.matches(name.lowercase())) { "请输入 SHA-256 模板哈希" }
        return copy(target = if (match == ModelMatch.TEMPLATE_HASH) name.lowercase() else name)
    }

    fun json() = jsonOf("id" to id, "match" to match.wire, "target" to target, "templates" to templates.json())

    companion object {
        fun read(json: JSONObject): ModelFormatRule? {
            val match = ModelMatch.entries.firstOrNull { it.wire == json.string("match") } ?: return null
            return runCatching {
                ModelFormatRule(json.string("id").ifBlank(::newId), match, json.string("target"),
                    ModelTemplatePair.read(json.optJSONObject("templates"))).valid()
            }.getOrNull()
        }
    }
}

private val formatHash = Regex("[0-9a-f]{64}")
private const val FORMAT_CACHE_TTL = 300_000L
internal const val MAX_MODEL_FORMAT_RULES = 64

internal data class BackendModelFormatting(
    val hash: String = "",
    val contextTokens: Int? = null,
    val suggested: ModelTemplatePair = ModelTemplatePair(),
    val inspected: Long = System.currentTimeMillis(),
) {
    fun current() = System.currentTimeMillis() - inspected in 0..FORMAT_CACHE_TTL

    fun json() = jsonOf("hash" to hash, "contextTokens" to contextTokens, "suggested" to suggested.json(), "inspected" to inspected)

    companion object {
        fun read(json: JSONObject) = BackendModelFormatting(
            json.string("hash").takeIf(formatHash::matches).orEmpty(),
            json.optInt("contextTokens").takeIf { it in 1..MAX_CONTEXT_TOKENS },
            ModelTemplatePair.read(json.optJSONObject("suggested")),
            json.optLong("inspected"),
        )
    }
}

internal data class ModelFormatting(
    val deriveInstruct: Boolean = false,
    val deriveContext: Boolean = false,
    val deriveContextSize: Boolean = false,
    val rules: List<ModelFormatRule> = emptyList(),
    val backend: Map<String, BackendModelFormatting> = emptyMap(),
) {
    val usesMetadata get() = deriveInstruct || deriveContext || deriveContextSize || rules.any { it.match == ModelMatch.TEMPLATE_HASH }

    fun withBackend(model: String, info: BackendModelFormatting): ModelFormatting {
        val recent = backend.entries.filter { it.key != model }.sortedByDescending { it.value.inspected }
            .take(31).associate { it.toPair() }
        return copy(backend = recent + (model to info))
    }

    fun choose(model: String, defaults: ModelTemplatePair): ModelTemplatePair {
        rules.firstOrNull { it.match == ModelMatch.EXACT && it.target == model }?.let { return it.templates }
        val deadline = System.nanoTime() + 35_000_000L
        rules.filter { it.match == ModelMatch.REGEX }
            .sortedWith(compareBy({ it.templates.instruct?.name.orEmpty().lowercase() }, { it.target }))
            .firstOrNull { matchModelRegex(it.target, model, deadline) }?.let { return it.templates }
        val properties = backend[model] ?: return defaults
        rules.firstOrNull { it.match == ModelMatch.TEMPLATE_HASH && it.target == properties.hash }?.let { return it.templates }
        return ModelTemplatePair(
            properties.suggested.instruct.takeIf { deriveInstruct } ?: defaults.instruct,
            properties.suggested.context.takeIf { deriveContext } ?: defaults.context,
        )
    }

    fun json() = jsonOf(
        "deriveInstruct" to deriveInstruct, "deriveContext" to deriveContext, "deriveContextSize" to deriveContextSize,
        "rules" to JSONArray(rules.map(ModelFormatRule::json)),
        "backend" to JSONObject(backend.mapValues { it.value.json() }),
    )

    companion object {
        fun read(json: JSONObject?): ModelFormatting {
            if (json == null) return ModelFormatting()
            val backend = json.optJSONObject("backend")
            return ModelFormatting(
                json.optBoolean("deriveInstruct"), json.optBoolean("deriveContext"), json.optBoolean("deriveContextSize"),
                json.optJSONArray("rules")?.objects()?.take(MAX_MODEL_FORMAT_RULES)?.mapNotNull(ModelFormatRule::read)
                    ?.distinctBy { it.match to it.target }.orEmpty(),
                backend?.keys()?.asSequence()?.take(32)?.mapNotNull { id ->
                    backend.optJSONObject(id)?.let { id to BackendModelFormatting.read(it) }
                }?.toMap().orEmpty(),
            )
        }
    }
}

internal class ModelFormatCatalog(
    val instructs: List<InstructTemplate>, val contexts: List<TextContextTemplate>,
    private val hashes: JSONObject,
) {
    fun fromProperties(json: JSONObject): BackendModelFormatting {
        val raw = (json.opt("chat_template") as? String)?.take(262_144).orEmpty()
        val template = if (raw.endsWith('\u0000')) raw.dropLast(1) + "\n" else raw
        val hash = template.takeIf(String::isNotBlank)?.let {
            MessageDigest.getInstance("SHA-256").digest(it.encodeToByteArray()).joinToString("") { byte -> "%02x".format(byte) }
        }.orEmpty()
        val name = hashes.string(hash).ifEmpty {
            signatures.firstOrNull { (_, parts) -> parts.all(template::contains) }?.first.orEmpty()
        }
        val size = json.optJSONObject("default_generation_settings")?.optInt("n_ctx")
            ?.takeIf { it in 1..MAX_CONTEXT_TOKENS }
        return BackendModelFormatting(hash, size, ModelTemplatePair(
            instructs.firstOrNull { it.name == name }, contexts.firstOrNull { it.name == name },
        ))
    }

    companion object {
        private val signatures = listOf(
            "Moonshot AI" to listOf("<|im_user|>user<|im_middle|>", "<|im_assistant|>assistant<|im_middle|>", "<|im_end|>"),
            "OpenAI Harmony" to listOf("<|start|>user<|message|>", "<|start|>assistant<|channel|>final<|message|>", "<|end|>"),
            "ChatML" to listOf("<|im_start|>user", "<|im_start|>assistant", "<|im_end|>"),
        )

        fun load(assets: AssetManager): ModelFormatCatalog {
            fun read(name: String) = assets.open(name).bufferedReader().use { it.readText() }
            return ModelFormatCatalog(
                JSONArray(read("instruct-presets.json")).objects().map { InstructTemplate.fromJson(it) },
                JSONArray(read("context-presets.json")).objects().map { TextContextTemplate.fromJson(it, checkStory = false) },
                JSONObject(read("model-template-hashes.json")),
            )
        }
    }
}

internal fun ModelConnection.resolvedTextTemplates(): ModelTemplatePair =
    if (provider.usesTextCompletion) provider.modelFormatting.choose(model, ModelTemplatePair(provider.instruct, provider.textContext))
    else ModelTemplatePair()
