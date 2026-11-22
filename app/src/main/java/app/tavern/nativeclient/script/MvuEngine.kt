package app.tavern.nativeclient.script

import app.tavern.nativeclient.Card
import app.tavern.nativeclient.LoreEntry
import app.tavern.nativeclient.Message
import app.tavern.nativeclient.WorldBook
import app.tavern.nativeclient.embeddedWorldBook
import app.tavern.nativeclient.jsonOf
import app.tavern.nativeclient.string
import org.json.JSONArray
import org.json.JSONObject
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.util.Locale
import kotlin.math.floor

internal data class MvuDelta(
    val op: String,
    val path: String,
    val before: String?,
    val after: String?,
)

internal enum class MvuFieldType(val wire: String) {
    ANY("any"),
    NUMBER("number"),
    INTEGER("integer"),
    STRING("string"),
    BOOLEAN("boolean"),
    ARRAY("array"),
    OBJECT("object"),
    ;

    companion object {
        fun from(raw: String?): MvuFieldType =
            entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) } ?: ANY
    }
}

internal data class MvuFieldRule(
    val path: String,
    val type: MvuFieldType = MvuFieldType.ANY,
    val min: Double? = null,
    val max: Double? = null,
    val clamp: Boolean = true,
    val required: Boolean = false,
    val readonly: Boolean = false,
    val enumValues: List<String> = emptyList(),
) {
    fun json(): JSONObject = jsonOf(
        "path" to path,
        "type" to type.wire,
        "min" to min,
        "max" to max,
        "clamp" to clamp,
        "required" to required,
        "readonly" to readonly,
        "enum" to JSONArray(enumValues),
    )

    companion object {
        fun fromJson(pathFallback: String, obj: JSONObject): MvuFieldRule {
            val enums = buildList {
                val arr = obj.optJSONArray("enum")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val value = arr.opt(i)
                        if (value != null && value !== JSONObject.NULL) add(value.toString())
                    }
                }
            }
            return MvuFieldRule(
                path = obj.string("path").ifBlank { pathFallback },
                type = MvuFieldType.from(obj.string("type")),
                min = if (obj.has("min") && !obj.isNull("min")) obj.optDouble("min").takeIf(Double::isFinite) else null,
                max = if (obj.has("max") && !obj.isNull("max")) obj.optDouble("max").takeIf(Double::isFinite) else null,
                clamp = obj.optBoolean("clamp", true),
                required = obj.optBoolean("required", false),
                readonly = obj.optBoolean("readonly", false),
                enumValues = enums,
            )
        }
    }
}

internal data class MvuSchema(
    val rules: Map<String, MvuFieldRule> = emptyMap(),
    val strict: Boolean = false,
) {
    fun ruleFor(rawPath: String): MvuFieldRule? {
        val cleaned = MvuEngine.normalizePath(rawPath)
        return rules[cleaned]
    }

    companion object {
        fun fromJson(json: JSONObject?): MvuSchema {
            if (json == null) return MvuSchema()
            val strict = json.optBoolean("strict", false)
            val parsed = linkedMapOf<String, MvuFieldRule>()
            val fields = json.optJSONObject("fields") ?: json.optJSONObject("properties") ?: json
            fields.keys().forEach { key ->
                if (key == "strict" || key == "fields" || key == "properties") return@forEach
                val spec = fields.optJSONObject(key) ?: return@forEach
                val rule = MvuFieldRule.fromJson(key, spec)
                val normalized = MvuEngine.normalizePath(rule.path)
                if (normalized.isNotEmpty()) parsed[normalized] = rule.copy(path = normalized)
            }
            return MvuSchema(rules = parsed, strict = strict)
        }
    }
}

internal data class MvuValidationIssue(
    val path: String,
    val code: String,
    val message: String,
    val messageIndex: Int? = null,
)

internal data class MvuComputation(
    val statData: JSONObject,
    val messageDeltas: Map<Int, List<MvuDelta>>,
    val validationIssues: List<MvuValidationIssue> = emptyList(),
    val snapshotsByMessage: Map<Int, JSONObject> = emptyMap(),
)

internal object MvuEngine {
    private val updateBlockRegex = Regex("""(?is)<UpdateVariable\b[^>]*>([\s\S]*?)</UpdateVariable\s*>""")
    private val analysisBlockRegex = Regex("""(?is)<Analysis\b[^>]*>[\s\S]*?</Analysis\s*>""")
    private val fencedBlockRegex = Regex("""(?s)```(?:json|yaml|yml|json5)?\s*([\s\S]*?)```""")
    private val trailingStatPrefix = Regex("""^(?:\$|stat_data)\.?""")
    private val identifierPartRegex = Regex("""[A-Za-z0-9_$\u4E00-\u9FFF]""")
    private val initVarTagRegex = Regex("""(?i)\[initvar]""")

    fun normalizePath(rawPath: String): String = cleanPathLabel(rawPath)

    fun stripUpdateVariableBlocks(text: String): String {
        if (!text.contains("UpdateVariable", ignoreCase = true)) return text
        return updateBlockRegex.replace(text, "")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }

    fun initialStateFrom(variables: Map<String, String>): JSONObject {
        val raw = variables["stat_data"] ?: return JSONObject()
        return runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
    }

    fun extractInitVarEntries(card: Card?, books: List<WorldBook> = emptyList()): List<LoreEntry> {
        val all = buildList {
            card?.embeddedWorldBook()?.entries?.let(::addAll)
            books.forEach { addAll(it.entries) }
        }
        return all
            .filter { entry ->
                !entry.disabled && (
                    initVarTagRegex.containsMatchIn(entry.title) ||
                        entry.keywords.any { initVarTagRegex.containsMatchIn(it) }
                    )
            }
            .sortedWith(compareBy<LoreEntry> { it.priority }.thenBy { it.displayIndex }.thenBy { it.id })
    }

    fun parseInitVarContent(content: String): Pair<JSONObject, MvuSchema?> {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return JSONObject() to null
        val candidate = fencedBlockRegex.find(trimmed)?.groupValues?.getOrNull(1)?.trim() ?: trimmed

        val parsedObj: JSONObject = runCatching { JSONObject(candidate) }.getOrNull()
            ?: parseYamlToJsonObject(candidate)
            ?: return JSONObject() to null

        val extractedSchema = (parsedObj.optJSONObject("\$schema") ?: parsedObj.optJSONObject("_schema"))
            ?.let(MvuSchema::fromJson)

        val payload = parsedObj.optJSONObject("stat_data")
            ?.let { deepCopyJsonObject(it) }
            ?: deepCopyJsonObject(parsedObj).apply {
                remove("\$meta")
                remove("\$schema")
                remove("_schema")
            }
        return payload to extractedSchema
    }

    fun resolveInitialStateWithSchema(
        variables: Map<String, String> = emptyMap(),
        card: Card? = null,
        books: List<WorldBook> = emptyList(),
    ): Pair<JSONObject, MvuSchema?> {
        val merged = JSONObject()
        var mergedRules = linkedMapOf<String, MvuFieldRule>()
        var strict = false
        var hasSchema = false

        extractInitVarEntries(card, books).forEach { entry ->
            val (initObj, entrySchema) = parseInitVarContent(entry.content)
            deepMergeInto(merged, initObj)
            if (entrySchema != null) {
                hasSchema = true
                if (entrySchema.strict) strict = true
                mergedRules.putAll(entrySchema.rules)
            }
        }

        val override = initialStateFrom(variables)
        deepMergeInto(merged, override)
        val schema = if (hasSchema) MvuSchema(mergedRules, strict) else null
        return merged to schema
    }

    fun resolveInitialState(
        variables: Map<String, String> = emptyMap(),
        card: Card? = null,
        books: List<WorldBook> = emptyList(),
    ): JSONObject = resolveInitialStateWithSchema(variables, card, books).first

    fun validate(state: JSONObject, schema: MvuSchema): List<MvuValidationIssue> {
        val issues = mutableListOf<MvuValidationIssue>()
        schema.rules.forEach { (path, rule) ->
            val rawValue = readRawPath(state, path)
            if (rawValue == null || rawValue === JSONObject.NULL) {
                if (rule.required) {
                    issues += MvuValidationIssue(path, "required", "缺少必需变量 $path")
                }
                return@forEach
            }
            if (!matchesType(rawValue, rule.type)) {
                issues += MvuValidationIssue(path, "type_mismatch", "$path 类型不符合 ${rule.type.wire}")
            }
            if (rawValue is Number) {
                val num = rawValue.toDouble()
                if (rule.min != null && num < rule.min) {
                    issues += MvuValidationIssue(path, "min_violation", "$path=$num 小于最小值 ${rule.min}")
                }
                if (rule.max != null && num > rule.max) {
                    issues += MvuValidationIssue(path, "max_violation", "$path=$num 大于最大值 ${rule.max}")
                }
            }
            if (rule.enumValues.isNotEmpty() && formatValue(rawValue) !in rule.enumValues) {
                issues += MvuValidationIssue(path, "enum_violation", "$path=${formatValue(rawValue)} 不在允许范围 ${rule.enumValues}")
            }
        }
        if (schema.strict) {
            val allowedTopKeys = schema.rules.keys.map { it.substringBefore('.').substringBefore('[') }.toSet()
            state.keys().forEach { key ->
                if (key !in allowedTopKeys) {
                    issues += MvuValidationIssue(key, "unknown_key", "严格模式下存在未声明字段 $key")
                }
            }
        }
        return issues
    }

    fun recomputeMvuState(
        messages: List<Message>,
        initial: JSONObject = JSONObject(),
        upToIndex: Int = messages.lastIndex,
        schema: MvuSchema? = null,
    ): MvuComputation {
        val state = deepCopyJsonObject(initial)
        val deltasByMessage = linkedMapOf<Int, List<MvuDelta>>()
        val snapshotsByMessage = linkedMapOf<Int, JSONObject>()
        val issues = mutableListOf<MvuValidationIssue>()
        val limit = minOf(upToIndex, messages.lastIndex)
        for (index in 0..limit) {
            val msg = messages[index]
            if (msg.excludedFromContext) {
                snapshotsByMessage[index] = deepCopyJsonObject(state)
                continue
            }
            val rawText = msg.text
            if (rawText.contains("UpdateVariable", ignoreCase = true)) {
                val msgDeltas = mutableListOf<MvuDelta>()
                updateBlockRegex.findAll(rawText).forEach { match ->
                    val inner = analysisBlockRegex.replace(match.groupValues[1], "").trim()
                    if (inner.isNotEmpty()) {
                        applyBlock(state, inner, msgDeltas, schema, index, issues)
                    }
                }
                if (msgDeltas.isNotEmpty()) {
                    deltasByMessage[index] = msgDeltas
                }
            }
            snapshotsByMessage[index] = deepCopyJsonObject(state)
        }
        if (schema != null) {
            issues += validate(state, schema)
        }
        return MvuComputation(
            statData = state,
            messageDeltas = deltasByMessage,
            validationIssues = issues,
            snapshotsByMessage = snapshotsByMessage,
        )
    }

    fun replayAfterSwipe(
        messages: List<Message>,
        messageIndex: Int,
        newVariantIndex: Int,
        initial: JSONObject = JSONObject(),
        schema: MvuSchema? = null,
    ): MvuComputation {
        val updated = messages.mapIndexed { idx, message ->
            if (idx == messageIndex) message.select(newVariantIndex) else message
        }
        return recomputeMvuState(updated, initial = initial, schema = schema)
    }

    fun replayAfterEdit(
        messages: List<Message>,
        messageIndex: Int,
        newText: String,
        initial: JSONObject = JSONObject(),
        schema: MvuSchema? = null,
    ): MvuComputation {
        val updated = messages.mapIndexed { idx, message ->
            if (idx == messageIndex) {
                val updatedVariants = if (message.variants.isNotEmpty() && message.index in message.variants.indices) {
                    message.variants.toMutableList().apply { this[message.index] = newText }
                } else {
                    message.variants
                }
                message.copy(text = newText, variants = updatedVariants)
            } else {
                message
            }
        }
        return recomputeMvuState(updated, initial = initial, schema = schema)
    }

    fun replayAfterDelete(
        messages: List<Message>,
        deletedIndices: Set<Int>,
        initial: JSONObject = JSONObject(),
        schema: MvuSchema? = null,
    ): MvuComputation {
        val remaining = messages.filterIndexed { idx, _ -> idx !in deletedIndices }
        return recomputeMvuState(remaining, initial = initial, schema = schema)
    }

    fun readPath(root: JSONObject, rawPath: String): String? {
        val value = readRawPath(root, rawPath) ?: return null
        if (value === JSONObject.NULL) return null
        return formatValue(value)
    }

    fun readRawPath(root: JSONObject, rawPath: String): Any? {
        val tokens = tokenizePath(rawPath)
        if (tokens.isEmpty()) return root
        var current: Any? = root
        for (token in tokens) {
            current = when (token) {
                is PathSegment.Key -> (current as? JSONObject)?.opt(token.name)
                is PathSegment.Index -> {
                    val idx = token.index
                    when (current) {
                        is JSONArray -> if (idx in 0 until current.length()) current.opt(idx) else null
                        is JSONObject -> current.opt(idx.toString())
                        else -> null
                    }
                }
            } ?: return null
            if (current === JSONObject.NULL) return null
        }
        return current
    }

    internal fun sanitizeCardScriptHtml(
        rawHtml: String,
        darkTheme: Boolean,
        bgHex: String,
        surfaceHex: String,
        fgHex: String,
        mutedHex: String,
        accentHex: String,
        borderHex: String,
        policy: CardSandboxPolicy = CardSandboxPolicy(),
    ): String = CardSandboxHtml.sanitizeCardScriptHtml(
        rawHtml = rawHtml,
        darkTheme = darkTheme,
        bgHex = bgHex,
        surfaceHex = surfaceHex,
        fgHex = fgHex,
        mutedHex = mutedHex,
        accentHex = accentHex,
        borderHex = borderHex,
        policy = policy,
    )

    private fun applyBlock(
        state: JSONObject,
        inner: String,
        outDeltas: MutableList<MvuDelta>,
        schema: MvuSchema?,
        messageIndex: Int,
        issues: MutableList<MvuValidationIssue>,
    ) {
        if (inner.startsWith("[")) {
            val patchArray = runCatching { JSONArray(inner) }.getOrNull()
            if (patchArray != null) {
                applyJsonPatchArray(state, patchArray, outDeltas, schema, messageIndex, issues)
                return
            }
        }
        val statements = splitStatements(inner)
        for (stmt in statements) {
            val trimmed = stmt.trim().removeSuffix(";").trim()
            if (trimmed.isEmpty()) continue
            applyStatement(state, trimmed, outDeltas, schema, messageIndex, issues)
        }
    }

    private fun applyJsonPatchArray(
        state: JSONObject,
        ops: JSONArray,
        outDeltas: MutableList<MvuDelta>,
        schema: MvuSchema?,
        messageIndex: Int,
        issues: MutableList<MvuValidationIssue>,
    ) {
        // RFC 6902: if any `test` op fails, abort the patch atomically
        val working = deepCopyJsonObject(state)
        val stagedDeltas = mutableListOf<MvuDelta>()
        val stagedIssues = mutableListOf<MvuValidationIssue>()

        for (i in 0 until ops.length()) {
            val item = ops.optJSONObject(i) ?: continue
            val op = item.optString("op").lowercase(Locale.ROOT)
            val pointer = item.optString("path")
            if (pointer.isBlank()) continue
            val normalizedPath = pointerToDotPath(pointer)
            when (op) {
                "replace", "add" -> {
                    val value = item.opt("value")
                    setPath(working, normalizedPath, value, stagedDeltas, opName = op, schema = schema, messageIndex = messageIndex, issues = stagedIssues)
                }
                "remove" -> unsetPath(working, normalizedPath, null, stagedDeltas, schema = schema, messageIndex = messageIndex, issues = stagedIssues)
                "copy" -> {
                    val from = item.optString("from")
                    if (from.isNotBlank()) {
                        val copied = cloneJsonValue(readRawPath(working, pointerToDotPath(from)))
                        setPath(working, normalizedPath, copied, stagedDeltas, opName = "copy", schema = schema, messageIndex = messageIndex, issues = stagedIssues)
                    }
                }
                "move" -> {
                    val from = item.optString("from")
                    if (from.isNotBlank()) {
                        val fromPath = pointerToDotPath(from)
                        val moved = cloneJsonValue(readRawPath(working, fromPath))
                        unsetPath(working, fromPath, null, stagedDeltas, schema = schema, messageIndex = messageIndex, issues = stagedIssues)
                        setPath(working, normalizedPath, moved, stagedDeltas, opName = "move", schema = schema, messageIndex = messageIndex, issues = stagedIssues)
                    }
                }
                "test" -> {
                    val expected = formatValue(item.opt("value"))
                    val actual = readPath(working, normalizedPath)
                    if (expected != actual) {
                        issues += MvuValidationIssue(
                            path = cleanPathLabel(normalizedPath),
                            code = "patch_test_failed",
                            message = "JSON Patch test 失败: 期望 $expected，实际 $actual",
                            messageIndex = messageIndex,
                        )
                        return
                    }
                }
            }
        }
        replaceObjectContents(state, working)
        outDeltas += stagedDeltas
        issues += stagedIssues
    }

    private fun applyStatement(
        state: JSONObject,
        stmt: String,
        outDeltas: MutableList<MvuDelta>,
        schema: MvuSchema?,
        messageIndex: Int,
        issues: MutableList<MvuValidationIssue>,
    ) {
        val openParen = stmt.indexOf('(')
        val closeParen = stmt.lastIndexOf(')')
        if (openParen <= 0 || closeParen <= openParen) return
        val fn = stmt.substring(0, openParen).trim()
        val argsText = stmt.substring(openParen + 1, closeParen)
        val args = splitArguments(argsText)
        when (fn) {
            "_.set", "set" -> {
                if (args.size < 2) return
                val path = (parseJsValue(args[0]) as? String) ?: args[0].trim('"', '\'')
                val newValueRaw = if (args.size >= 3) args[2] else args[1]
                setPath(state, path, parseJsValue(newValueRaw), outDeltas, opName = "set", schema = schema, messageIndex = messageIndex, issues = issues)
            }
            "_.add", "add" -> {
                if (args.size < 2) return
                val path = (parseJsValue(args[0]) as? String) ?: args[0].trim('"', '\'')
                val delta = parseJsValue(args[1])
                addPath(state, path, delta, outDeltas, schema = schema, messageIndex = messageIndex, issues = issues)
            }
            "_.assign", "assign" -> {
                if (args.size == 2) {
                    val path = (parseJsValue(args[0]) as? String) ?: args[0].trim('"', '\'')
                    val patch = parseJsValue(args[1]) as? JSONObject ?: return
                    assignPath(state, path, patch, outDeltas, schema = schema, messageIndex = messageIndex, issues = issues)
                } else if (args.size >= 3) {
                    val path = (parseJsValue(args[0]) as? String) ?: args[0].trim('"', '\'')
                    val key = (parseJsValue(args[1]) as? String) ?: args[1].trim('"', '\'')
                    val value = parseJsValue(args[2])
                    val fullPath = if (path.isBlank()) key else "$path.$key"
                    setPath(state, fullPath, value, outDeltas, opName = "assign", schema = schema, messageIndex = messageIndex, issues = issues)
                }
            }
            "_.unset", "unset" -> {
                if (args.isEmpty()) return
                val path = (parseJsValue(args[0]) as? String) ?: args[0].trim('"', '\'')
                val targetItem = if (args.size >= 2) parseJsValue(args[1]) else null
                unsetPath(state, path, targetItem, outDeltas, schema = schema, messageIndex = messageIndex, issues = issues)
            }
        }
    }

    private fun enforceSchemaForWrite(
        rawPath: String,
        candidate: Any?,
        schema: MvuSchema?,
        messageIndex: Int,
        issues: MutableList<MvuValidationIssue>,
    ): Pair<Boolean, Any?> {
        val rule = schema?.ruleFor(rawPath) ?: return true to candidate
        val label = cleanPathLabel(rawPath)
        if (rule.readonly) {
            issues += MvuValidationIssue(label, "readonly", "只读变量 $label 禁止修改", messageIndex)
            return false to candidate
        }
        var adjusted = candidate
        if (adjusted is Number) {
            var num = adjusted.toDouble()
            if (rule.min != null && num < rule.min) {
                if (rule.clamp) num = rule.min
                else issues += MvuValidationIssue(label, "min_violation", "$label=$num 小于最小值 ${rule.min}", messageIndex)
            }
            if (rule.max != null && num > rule.max) {
                if (rule.clamp) num = rule.max
                else issues += MvuValidationIssue(label, "max_violation", "$label=$num 大于最大值 ${rule.max}", messageIndex)
            }
            adjusted = if (rule.type == MvuFieldType.INTEGER || (floor(num) == num && adjusted !is Double)) {
                num.toInt()
            } else {
                num
            }
        }
        return true to adjusted
    }

    private fun setPath(
        root: JSONObject,
        rawPath: String,
        value: Any?,
        outDeltas: MutableList<MvuDelta>,
        opName: String = "set",
        schema: MvuSchema? = null,
        messageIndex: Int = -1,
        issues: MutableList<MvuValidationIssue> = mutableListOf(),
    ) {
        val tokens = tokenizePath(rawPath)
        if (tokens.isEmpty()) return
        val (allowed, coerced) = enforceSchemaForWrite(rawPath, value, schema, messageIndex, issues)
        if (!allowed) return
        val before = readPath(root, rawPath)
        val leaf = tokens.last()
        val parent = navigateOrCreateParent(root, tokens.dropLast(1)) ?: return
        val jsonVal = coerced ?: JSONObject.NULL
        when {
            leaf is PathSegment.Key && parent is JSONObject -> parent.put(leaf.name, jsonVal)
            leaf is PathSegment.Index && parent is JSONArray -> {
                if (leaf.index == -1 || leaf.index >= parent.length()) {
                    while (parent.length() < leaf.index) parent.put(JSONObject.NULL)
                    if (leaf.index == -1 || leaf.index == parent.length()) parent.put(jsonVal)
                    else parent.put(leaf.index, jsonVal)
                } else {
                    parent.put(leaf.index, jsonVal)
                }
            }
            leaf is PathSegment.Index && parent is JSONObject -> parent.put(leaf.index.toString(), jsonVal)
        }
        val after = formatValue(jsonVal)
        outDeltas += MvuDelta(opName, cleanPathLabel(rawPath), before, after)
    }

    private fun addPath(
        root: JSONObject,
        rawPath: String,
        delta: Any?,
        outDeltas: MutableList<MvuDelta>,
        schema: MvuSchema? = null,
        messageIndex: Int = -1,
        issues: MutableList<MvuValidationIssue> = mutableListOf(),
    ) {
        val tokens = tokenizePath(rawPath)
        if (tokens.isEmpty()) return
        val rule = schema?.ruleFor(rawPath)
        if (rule?.readonly == true) {
            issues += MvuValidationIssue(cleanPathLabel(rawPath), "readonly", "只读变量 ${cleanPathLabel(rawPath)} 禁止修改", messageIndex)
            return
        }
        val beforeStr = readPath(root, rawPath)
        val leaf = tokens.last()
        val parent = navigateOrCreateParent(root, tokens.dropLast(1)) ?: return
        val existing: Any? = when {
            leaf is PathSegment.Key && parent is JSONObject -> parent.opt(leaf.name)
            leaf is PathSegment.Index && parent is JSONArray -> if (leaf.index in 0 until parent.length()) parent.opt(leaf.index) else null
            leaf is PathSegment.Index && parent is JSONObject -> parent.opt(leaf.index.toString())
            else -> null
        }

        val rawUpdated: Any? = when {
            existing is JSONArray -> {
                existing.put(delta ?: JSONObject.NULL)
                existing
            }
            delta is Number -> {
                val prevNum = (existing as? Number)?.toDouble() ?: existing?.toString()?.toDoubleOrNull() ?: 0.0
                val sum = prevNum + delta.toDouble()
                if (floor(sum) == sum && sum in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() &&
                    existing !is Double && delta !is Double
                ) {
                    sum.toInt()
                } else if (floor(sum) == sum && existing is Long && delta is Long) {
                    sum.toLong()
                } else {
                    sum
                }
            }
            existing is String || existing == null || existing === JSONObject.NULL ->
                existing?.takeUnless { it === JSONObject.NULL }?.toString().orEmpty() + delta?.toString().orEmpty()
            else -> delta
        }

        val (_, updated) = enforceSchemaForWrite(rawPath, rawUpdated, schema, messageIndex, issues)

        if (existing !is JSONArray) {
            when {
                leaf is PathSegment.Key && parent is JSONObject -> parent.put(leaf.name, updated ?: JSONObject.NULL)
                leaf is PathSegment.Index && parent is JSONArray && leaf.index in 0 until parent.length() ->
                    parent.put(leaf.index, updated ?: JSONObject.NULL)
                leaf is PathSegment.Index && parent is JSONObject ->
                    parent.put(leaf.index.toString(), updated ?: JSONObject.NULL)
            }
        }
        outDeltas += MvuDelta("add", cleanPathLabel(rawPath), beforeStr, formatValue(updated))
    }

    private fun assignPath(
        root: JSONObject,
        rawPath: String,
        patch: JSONObject,
        outDeltas: MutableList<MvuDelta>,
        schema: MvuSchema? = null,
        messageIndex: Int = -1,
        issues: MutableList<MvuValidationIssue> = mutableListOf(),
    ) {
        val base = cleanPathLabel(rawPath)
        patch.keys().forEach { k ->
            val targetPath = if (base.isBlank()) k else "$base.$k"
            setPath(root, targetPath, patch.opt(k), outDeltas, opName = "assign", schema = schema, messageIndex = messageIndex, issues = issues)
        }
    }

    private fun unsetPath(
        root: JSONObject,
        rawPath: String,
        itemToRemove: Any?,
        outDeltas: MutableList<MvuDelta>,
        schema: MvuSchema? = null,
        messageIndex: Int = -1,
        issues: MutableList<MvuValidationIssue> = mutableListOf(),
    ) {
        val tokens = tokenizePath(rawPath)
        if (tokens.isEmpty()) return
        val label = cleanPathLabel(rawPath)
        if (schema?.ruleFor(rawPath)?.readonly == true) {
            issues += MvuValidationIssue(label, "readonly", "只读变量 $label 禁止删除", messageIndex)
            return
        }
        val before = readPath(root, rawPath)
        if (itemToRemove != null) {
            val container = navigateExisting(root, tokens)
            if (container is JSONArray) {
                val needle = formatValue(itemToRemove)
                val kept = JSONArray()
                for (i in 0 until container.length()) {
                    val elem = container.opt(i)
                    if (formatValue(elem) != needle) kept.put(elem)
                }
                val parent = navigateExisting(root, tokens.dropLast(1))
                val leaf = tokens.last()
                if (leaf is PathSegment.Key && parent is JSONObject) parent.put(leaf.name, kept)
                outDeltas += MvuDelta("unset", label, before, kept.toString())
                return
            } else if (container is JSONObject) {
                val childPath = "$label.$itemToRemove"
                if (schema?.ruleFor(childPath)?.readonly == true) {
                    issues += MvuValidationIssue(childPath, "readonly", "只读变量 $childPath 禁止删除", messageIndex)
                    return
                }
                container.remove(itemToRemove.toString())
                outDeltas += MvuDelta("unset", childPath, before, null)
                return
            }
        }

        val leaf = tokens.last()
        val parent = navigateExisting(root, tokens.dropLast(1)) ?: return
        when {
            leaf is PathSegment.Key && parent is JSONObject -> parent.remove(leaf.name)
            leaf is PathSegment.Index && parent is JSONArray && leaf.index in 0 until parent.length() ->
                parent.remove(leaf.index)
            leaf is PathSegment.Index && parent is JSONObject -> parent.remove(leaf.index.toString())
        }
        outDeltas += MvuDelta("unset", label, before, null)
    }

    private fun parseYamlToJsonObject(raw: String): JSONObject? = runCatching {
        val loader = Load(LoadSettings.builder().build())
        val loaded = loader.loadFromString(raw)
        toJsonValue(loaded) as? JSONObject
    }.getOrNull()

    private fun toJsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().apply {
            value.forEach { (k, v) -> if (k != null) put(k.toString(), toJsonValue(v)) }
        }
        is List<*> -> JSONArray().apply { value.forEach { put(toJsonValue(it)) } }
        is Boolean, is Int, is Long, is Double, is Float, is String -> value
        else -> value.toString()
    }

    private fun matchesType(value: Any, type: MvuFieldType): Boolean = when (type) {
        MvuFieldType.ANY -> true
        MvuFieldType.NUMBER -> value is Number
        MvuFieldType.INTEGER -> value is Int || value is Long || (value is Double && floor(value) == value)
        MvuFieldType.STRING -> value is String
        MvuFieldType.BOOLEAN -> value is Boolean
        MvuFieldType.ARRAY -> value is JSONArray
        MvuFieldType.OBJECT -> value is JSONObject
    }

    internal fun deepCopyJsonObject(src: JSONObject): JSONObject =
        runCatching { JSONObject(src.toString()) }.getOrElse { JSONObject() }

    private fun cloneJsonValue(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> deepCopyJsonObject(value)
        is JSONArray -> runCatching { JSONArray(value.toString()) }.getOrElse { JSONArray() }
        else -> value
    }

    private fun replaceObjectContents(target: JSONObject, source: JSONObject) {
        target.keys().asSequence().toList().forEach(target::remove)
        source.keys().forEach { key -> target.put(key, source.opt(key)) }
    }

    internal fun deepMergeInto(target: JSONObject, source: JSONObject) {
        source.keys().forEach { key ->
            val srcVal = source.opt(key)
            val dstVal = target.opt(key)
            if (srcVal is JSONObject && dstVal is JSONObject) {
                deepMergeInto(dstVal, srcVal)
            } else {
                target.put(key, cloneJsonValue(srcVal) ?: JSONObject.NULL)
            }
        }
    }

    private fun navigateOrCreateParent(root: JSONObject, tokens: List<PathSegment>): Any? {
        var current: Any = root
        for (i in tokens.indices) {
            val token = tokens[i]
            val nextToken = tokens.getOrNull(i + 1)
            when (token) {
                is PathSegment.Key -> {
                    val obj = current as? JSONObject ?: return null
                    var child = obj.opt(token.name)
                    if (child == null || child === JSONObject.NULL) {
                        child = if (nextToken is PathSegment.Index) JSONArray() else JSONObject()
                        obj.put(token.name, child)
                    }
                    current = child
                }
                is PathSegment.Index -> {
                    val arr = current as? JSONArray ?: return null
                    val idx = token.index
                    while (arr.length() <= idx) {
                        arr.put(if (arr.length() == idx && nextToken !is PathSegment.Index) JSONObject() else JSONObject.NULL)
                    }
                    var child = arr.opt(idx)
                    if (child == null || child === JSONObject.NULL) {
                        child = if (nextToken is PathSegment.Index) JSONArray() else JSONObject()
                        arr.put(idx, child)
                    }
                    current = child
                }
            }
        }
        return current
    }

    private fun navigateExisting(root: JSONObject, tokens: List<PathSegment>): Any? {
        var current: Any? = root
        for (token in tokens) {
            current = when (token) {
                is PathSegment.Key -> (current as? JSONObject)?.opt(token.name)
                is PathSegment.Index -> when (current) {
                    is JSONArray -> if (token.index in 0 until current.length()) current.opt(token.index) else null
                    is JSONObject -> current.opt(token.index.toString())
                    else -> null
                }
            } ?: return null
        }
        return current
    }

    private sealed interface PathSegment {
        data class Key(val name: String) : PathSegment
        data class Index(val index: Int) : PathSegment
    }

    private fun pointerToDotPath(pointer: String): String =
        pointer.trimStart('/').split('/').joinToString(".") { seg ->
            seg.replace("~1", "/").replace("~0", "~")
        }

    private fun cleanPathLabel(rawPath: String): String =
        rawPath.trim().replace(trailingStatPrefix, "").trim('.')

    private fun tokenizePath(rawPath: String): List<PathSegment> {
        val cleaned = cleanPathLabel(rawPath)
        if (cleaned.isEmpty()) return emptyList()
        val out = mutableListOf<PathSegment>()
        var i = 0
        val current = StringBuilder()
        fun flushKey() {
            if (current.isNotEmpty()) {
                val s = current.toString()
                current.setLength(0)
                val asInt = s.toIntOrNull()
                if (asInt != null && out.isNotEmpty()) out += PathSegment.Index(asInt)
                else out += PathSegment.Key(s)
            }
        }
        while (i < cleaned.length) {
            when (val ch = cleaned[i]) {
                '.' -> {
                    flushKey()
                    i++
                }
                '[' -> {
                    flushKey()
                    val end = cleaned.indexOf(']', i + 1)
                    if (end < 0) break
                    val inner = cleaned.substring(i + 1, end).trim().trim('\'', '"')
                    if (inner == "-") {
                        out += PathSegment.Index(-1)
                    } else {
                        val idx = inner.toIntOrNull()
                        if (idx != null) out += PathSegment.Index(idx)
                        else if (inner.isNotEmpty()) out += PathSegment.Key(inner)
                    }
                    i = end + 1
                }
                else -> {
                    current.append(ch)
                    i++
                }
            }
        }
        flushKey()
        return out
    }

    private fun splitStatements(source: String): List<String> {
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        var parenDepth = 0
        var braceDepth = 0
        var bracketDepth = 0
        var quote: Char? = null
        var i = 0
        while (i < source.length) {
            val ch = source[i]
            val next = source.getOrNull(i + 1)
            if (quote != null) {
                buf.append(ch)
                if (ch == '\\' && next != null) {
                    buf.append(next)
                    i += 2
                    continue
                }
                if (ch == quote) quote = null
                i++
                continue
            }
            if (ch == '/' && next == '/') {
                while (i < source.length && source[i] != '\n') i++
                continue
            }
            if (ch == '/' && next == '*') {
                val close = source.indexOf("*/", i + 2)
                i = if (close < 0) source.length else close + 2
                continue
            }
            when (ch) {
                '\'', '"', '`' -> {
                    quote = ch
                    buf.append(ch)
                }
                '(' -> {
                    parenDepth++
                    buf.append(ch)
                }
                ')' -> {
                    if (parenDepth > 0) parenDepth--
                    buf.append(ch)
                    if (parenDepth == 0 && braceDepth == 0 && bracketDepth == 0) {
                        out += buf.toString()
                        buf.setLength(0)
                    }
                }
                '{' -> {
                    braceDepth++
                    buf.append(ch)
                }
                '}' -> {
                    if (braceDepth > 0) braceDepth--
                    buf.append(ch)
                }
                '[' -> {
                    bracketDepth++
                    buf.append(ch)
                }
                ']' -> {
                    if (bracketDepth > 0) bracketDepth--
                    buf.append(ch)
                }
                ';', '\n' -> {
                    if (parenDepth == 0 && braceDepth == 0 && bracketDepth == 0) {
                        if (buf.isNotBlank()) out += buf.toString()
                        buf.setLength(0)
                    } else {
                        buf.append(ch)
                    }
                }
                else -> buf.append(ch)
            }
            i++
        }
        if (buf.isNotBlank()) out += buf.toString()
        return out
    }

    private fun splitArguments(argsText: String): List<String> {
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        var parenDepth = 0
        var braceDepth = 0
        var bracketDepth = 0
        var quote: Char? = null
        var i = 0
        while (i < argsText.length) {
            val ch = argsText[i]
            val next = argsText.getOrNull(i + 1)
            if (quote != null) {
                buf.append(ch)
                if (ch == '\\' && next != null) {
                    buf.append(next)
                    i += 2
                    continue
                }
                if (ch == quote) quote = null
                i++
                continue
            }
            if (ch == '/' && next == '/') {
                while (i < argsText.length && argsText[i] != '\n') i++
                continue
            }
            when (ch) {
                '\'', '"', '`' -> {
                    quote = ch
                    buf.append(ch)
                }
                '(' -> { parenDepth++; buf.append(ch) }
                ')' -> { if (parenDepth > 0) parenDepth--; buf.append(ch) }
                '{' -> { braceDepth++; buf.append(ch) }
                '}' -> { if (braceDepth > 0) braceDepth--; buf.append(ch) }
                '[' -> { bracketDepth++; buf.append(ch) }
                ']' -> { if (bracketDepth > 0) bracketDepth--; buf.append(ch) }
                ',' -> {
                    if (parenDepth == 0 && braceDepth == 0 && bracketDepth == 0) {
                        out += buf.toString().trim()
                        buf.setLength(0)
                    } else {
                        buf.append(ch)
                    }
                }
                else -> buf.append(ch)
            }
            i++
        }
        if (buf.isNotBlank()) out += buf.toString().trim()
        return out
    }

    private fun parseJsValue(raw: String): Any? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "null" || trimmed == "undefined") return null
        if (trimmed == "true") return true
        if (trimmed == "false") return false
        if ((trimmed.startsWith('"') && trimmed.endsWith('"')) ||
            (trimmed.startsWith('\'') && trimmed.endsWith('\'')) ||
            (trimmed.startsWith('`') && trimmed.endsWith('`'))
        ) {
            return unescapeJsString(trimmed.substring(1, trimmed.length - 1))
        }
        if (trimmed.startsWith('{') && trimmed.endsWith('}')) {
            return parseJsObject(trimmed)
        }
        if (trimmed.startsWith('[') && trimmed.endsWith(']')) {
            val inner = trimmed.substring(1, trimmed.length - 1)
            val arr = JSONArray()
            splitArguments(inner).forEach { arr.put(parseJsValue(it) ?: JSONObject.NULL) }
            return arr
        }
        trimmed.toIntOrNull()?.let { return it }
        trimmed.toLongOrNull()?.let { return it }
        trimmed.toDoubleOrNull()?.let { return it }
        return trimmed
    }

    private fun parseJsObject(raw: String): JSONObject {
        runCatching { return JSONObject(raw) }
        val inner = raw.trim().drop(1).dropLast(1).trim()
        val obj = JSONObject()
        if (inner.isEmpty()) return obj
        for (entry in splitArguments(inner)) {
            val colon = findTopLevelColon(entry)
            if (colon <= 0) continue
            val rawKey = entry.substring(0, colon).trim()
            val key = ((parseJsValue(rawKey) as? String) ?: rawKey.takeIf { it.all { c -> identifierPartRegex.matches(c.toString()) } })
                ?: rawKey.trim('"', '\'')
            val valRaw = entry.substring(colon + 1).trim()
            obj.put(key, parseJsValue(valRaw) ?: JSONObject.NULL)
        }
        return obj
    }

    private fun findTopLevelColon(s: String): Int {
        var quote: Char? = null
        var depth = 0
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (quote != null) {
                if (ch == '\\') { i += 2; continue }
                if (ch == quote) quote = null
            } else {
                when (ch) {
                    '\'', '"', '`' -> quote = ch
                    '{', '[', '(' -> depth++
                    '}', ']', ')' -> if (depth > 0) depth--
                    ':' -> if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    private fun unescapeJsString(s: String): String = buildString(s.length) {
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                when (val esc = s[i + 1]) {
                    'n' -> append('\n')
                    'r' -> append('\r')
                    't' -> append('\t')
                    '\\', '\'', '"', '`' -> append(esc)
                    else -> append(esc)
                }
                i += 2
            } else {
                append(ch)
                i++
            }
        }
    }

    private fun formatValue(value: Any?): String? = when (value) {
        null, JSONObject.NULL -> null
        is Double -> if (floor(value) == value && value in Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble()) {
            value.toLong().toString()
        } else {
            value.toString()
        }
        else -> value.toString()
    }
}
