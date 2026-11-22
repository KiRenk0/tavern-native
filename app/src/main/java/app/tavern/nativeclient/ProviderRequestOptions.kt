package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.lowlevel.Parse
import org.snakeyaml.engine.v2.events.CollectionStartEvent
import org.snakeyaml.engine.v2.events.Event
import org.snakeyaml.engine.v2.events.ScalarEvent
import java.util.Locale

private const val requestYamlLimit = 32_768
private val requestYamlSettings = LoadSettings.builder()
    .setCodePointLimit(requestYamlLimit)
    .setMaxAliasesForCollections(0)
    .setAllowDuplicateKeys(false)
    .setAllowRecursiveKeys(false)
    .setUseMarks(false)
    .build()
private val headerName = Regex("[!#\$%&'*+.^_`|~0-9A-Za-z-]+")
private val transportHeaders = setOf(
    "host", "content-length", "transfer-encoding", "connection", "proxy-connection", "keep-alive",
    "trailer", "te", "upgrade", "expect",
)
private val secretParameter = Regex("authorization|password|token|secret|api[_-]?key|credential", RegexOption.IGNORE_CASE)
private val standardYamlTags = listOf("str", "int", "float", "bool", "null", "seq", "map")
    .mapTo(hashSetOf()) { "tag:yaml.org,2002:$it" }

internal data class ProviderRequestOptions(
    val includeBody: String = "",
    val excludeBody: String = "",
    val includeHeaders: String = "",
    val processing: PromptPostProcessing = PromptPostProcessing.NONE,
) {
    val hasYaml get() = includeBody.isNotBlank() || excludeBody.isNotBlank() || includeHeaders.isNotBlank()
    val isEmpty get() = !hasYaml && processing == PromptPostProcessing.NONE

    fun settingsJson() = jsonOf("includeBody" to includeBody, "excludeBody" to excludeBody, "includeHeaders" to includeHeaders)

    fun compile(names: PromptPostProcessingNames = PromptPostProcessingNames(), expand: (String) -> String = { it }): CompiledProviderRequest {
        val additions = mappings(parseYaml(includeBody, "补充请求体"), "补充请求体")
            .mapValues { (_, value) -> jsonValue(value, expand) }
        val exclusions = when (val value = parseYaml(excludeBody, "移除请求体参数")) {
            null -> emptyList()
            is String -> listOf(value)
            is List<*> -> value.map { requireKey(it, "移除请求体参数") }
            is Map<*, *> -> value.keys.map { requireKey(it, "移除请求体参数") }
            else -> error("移除请求体参数应为 YAML 字段名、列表或对象")
        }.map(expand).toSet()
        val headers = linkedMapOf<String, String>()
        for ((name, value) in mappings(parseYaml(includeHeaders, "补充请求头"), "补充请求头")) {
            val key = name.lowercase(Locale.ROOT)
            require(headerName.matches(name) && name.length <= 128 && key !in transportHeaders) {
                "请求头名称无效或由 HTTP 客户端管理"
            }
            require(value is String || value is Number || value is Boolean) { "请求头的值应为单行文本、数字或布尔值" }
            val text = expand(value.toString())
            require(text.length <= 8_192 && text.all { it == '\t' || it.code in 32..126 || it.code in 160..255 }) {
                "请求头的值包含换行或无效字符"
            }
            require(headers.put(key, text.trim()) == null) { "请求头名称重复，大小写视为相同" }
        }
        return CompiledProviderRequest(additions, exclusions, headers, processing, names)
    }

    companion object {
        fun fromJson(value: JSONObject?): ProviderRequestOptions = ProviderRequestOptions(
            value?.string("includeBody").orEmpty().take(requestYamlLimit),
            value?.string("excludeBody").orEmpty().take(requestYamlLimit),
            value?.string("includeHeaders").orEmpty().take(requestYamlLimit),
        )

        fun fromOriginal(value: JSONObject): ProviderRequestOptions = ProviderRequestOptions(
            value.string("custom_include_body"), value.string("custom_exclude_body"), value.string("custom_include_headers"),
            PromptPostProcessing.from(value.string("custom_prompt_post_processing")),
        )
    }
}

internal data class CompiledProviderRequest(
    private val additions: Map<String, Any> = emptyMap(),
    private val exclusions: Set<String> = emptySet(),
    private val headers: Map<String, String> = emptyMap(),
    private val processing: PromptPostProcessing = PromptPostProcessing.NONE,
    private val names: PromptPostProcessingNames = PromptPostProcessingNames(),
) {
    val processesMessages get() = processing != PromptPostProcessing.NONE
    val allowsTools get() = processing.allowsTools
    val hasOverrides get() = processesMessages || additions.isNotEmpty() || exclusions.isNotEmpty() || headers.isNotEmpty()
    val requestsProbabilities get() = additions.containsKey("logprobs") || additions.containsKey("top_logprobs")

    fun overridesBodyField(name: String) = name in additions || name in exclusions

    fun applyTo(body: JSONObject, skip: Set<String> = emptySet()) {
        additions.forEach { (key, value) -> if (key !in skip) body.put(key, value) }
        exclusions.forEach(body::remove)
    }

    fun headersWith(source: Map<String, String>): Map<String, String> =
        if (headers.isEmpty()) source else source.filterKeys { it.lowercase(Locale.ROOT) !in headers } + headers

    fun processMessages(messages: JSONArray): JSONArray = processing.process(messages, names)

    fun redact(value: String): String {
        val sensitive = buildList {
            addAll(headers.values.filter { it.isNotEmpty() })
            headers["authorization"]?.substringAfter(' ', "")?.takeIf(String::isNotEmpty)?.let(::add)
            additions.forEach { (key, data) -> if (secretParameter.containsMatchIn(key) && data is String && data.isNotEmpty()) add(data) }
        }
        return sensitive.distinct().sortedByDescending(String::length).fold(value) { text, secret -> text.redactValue(secret) }
    }
}

internal val Provider.supportsRequestOptions: Boolean
    get() = template == null && protocol in setOf(ApiProtocol.OPENAI_COMPATIBLE, ApiProtocol.OPENROUTER)

internal fun Provider.compiledRequestOptions(
    names: PromptPostProcessingNames = PromptPostProcessingNames(), expand: (String) -> String = { it },
): CompiledProviderRequest =
    if (supportsRequestOptions && !requestOptions.isEmpty) requestOptions.compile(names, expand)
    else CompiledProviderRequest()

private fun parseYaml(source: String, label: String): Any? {
    if (source.isBlank()) return null
    require(source.length <= requestYamlLimit) { "$label 超过 32 Ki 字符" }
    return try {
        var depth = 0
        for (event in Parse(requestYamlSettings).parseString(source)) {
            val tag = when (event) {
                is ScalarEvent -> event.tag.orElse(null)
                is CollectionStartEvent -> event.tag.orElse(null)
                else -> null
            }
            require(tag == null || tag in standardYamlTags)
            when (event.eventId) {
                Event.ID.MappingStart, Event.ID.SequenceStart -> require(++depth <= 24)
                Event.ID.MappingEnd, Event.ID.SequenceEnd -> depth--
                else -> Unit
            }
        }
        Load(requestYamlSettings).loadFromString(source)
    } catch (_: RuntimeException) {
        throw IllegalArgumentException("$label 的 YAML 或 JSON 无法读取")
    }
}

private fun mappings(value: Any?, label: String): Map<String, Any?> {
    if (value == null) return emptyMap()
    val rows = if (value is List<*>) value else listOf(value)
    val merged = linkedMapOf<String, Any?>()
    for (row in rows) {
        require(row is Map<*, *>) { "$label 应为 YAML 对象或对象列表" }
        row.forEach { (key, value) -> merged[requireKey(key, label)] = value }
    }
    return merged
}

private fun requireKey(key: Any?, label: String): String {
    require(key is String && key.isNotBlank()) { "$label 的字段名应为文本" }
    return key
}

private fun jsonValue(value: Any?, expand: (String) -> String): Any = when (value) {
    null -> JSONObject.NULL
    is String -> expand(value)
    is Boolean -> value
    is Number -> value.also { require(it.toDouble().isFinite()) { "请求体包含无效数字" } }
    is Map<*, *> -> JSONObject().apply {
        value.forEach { (key, entry) -> put(requireKey(key, "补充请求体"), jsonValue(entry, expand)) }
    }
    is List<*> -> JSONArray(value.map { jsonValue(it, expand) })
    else -> error("补充请求体包含无法转换成 JSON 的值")
}
