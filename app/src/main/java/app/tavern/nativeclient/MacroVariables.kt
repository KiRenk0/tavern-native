package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject

internal data class MacroVariableChanges(
    val local: Map<String, String?> = emptyMap(),
    val global: Map<String, String?> = emptyMap(),
) {
    val isEmpty get() = local.isEmpty() && global.isEmpty()
}

internal fun Map<String, String>.withVariableChanges(changes: Map<String, String?>): Map<String, String> =
    if (changes.isEmpty()) this else toMutableMap().apply {
        changes.forEach { (key, value) -> if (value == null) remove(key) else put(key, value) }
    }

internal class MacroVariables(private val baseline: (String, Boolean) -> String?) {
    constructor(local: Map<String, String>, global: Map<String, String>) : this({ key, shared ->
        (if (shared) global else local)[key]
    })

    private val localEdits = linkedMapOf<String, String?>()
    private val globalEdits = linkedMapOf<String, String?>()

    private fun edits(global: Boolean) = if (global) globalEdits else localEdits

    fun get(key: String, global: Boolean): String? {
        val changes = edits(global)
        return if (key in changes) changes[key] else baseline(key, global)
    }

    private fun set(key: String, value: String?, global: Boolean) {
        val changes = edits(global)
        if (baseline(key, global) == value) changes.remove(key) else changes[key] = value
    }

    fun changes() = MacroVariableChanges(localEdits.toMap(), globalEdits.toMap())

    fun apply(changes: MacroVariableChanges) {
        changes.local.forEach { (key, value) -> set(key, value, false) }
        changes.global.forEach { (key, value) -> set(key, value, true) }
    }

    fun resolve(name: String, args: List<String>, write: Boolean): String? {
        val action = variableMacros[name] ?: return null
        val key = args.firstOrNull()?.takeIf(String::isNotBlank) ?: return ""
        val global = "global" in name
        val value = get(key, global)
        when (action) {
            VariableMacro.GET -> return value.orEmpty()
            VariableMacro.HAS -> return (value != null).toString()
            VariableMacro.GET_KEY -> return args.getOrNull(1)?.let { readVariableKey(value, it) }.orEmpty()
            else -> Unit
        }
        if (!write) return if (action == VariableMacro.INCREMENT || action == VariableMacro.DECREMENT) value.orEmpty() else ""
        val updated = when (action) {
            VariableMacro.DELETE -> null
            VariableMacro.SET -> args.getOrNull(1) ?: return ""
            VariableMacro.ADD -> args.getOrNull(1)?.let { addVariable(value.orEmpty(), it) } ?: return ""
            VariableMacro.INCREMENT -> addVariable(value.orEmpty(), "1")
            VariableMacro.DECREMENT -> addVariable(value.orEmpty(), "-1")
            VariableMacro.SET_KEY -> {
                val index = args.getOrNull(1) ?: return ""
                val item = args.getOrNull(2) ?: return ""
                runCatching { writeVariableKey(value, index, item) }.getOrElse { return "" }
            }
        }
        set(key, updated, global)
        return if (action == VariableMacro.INCREMENT || action == VariableMacro.DECREMENT) updated.orEmpty() else ""
    }
}

private enum class VariableMacro { GET, HAS, SET, ADD, INCREMENT, DECREMENT, DELETE, GET_KEY, SET_KEY }

private val variableMacros = buildMap {
    for (scope in listOf("", "global")) {
        put("get${scope}var", VariableMacro.GET)
        put("has${scope}var", VariableMacro.HAS)
        put("set${scope}var", VariableMacro.SET)
        put("add${scope}var", VariableMacro.ADD)
        put("inc${scope}var", VariableMacro.INCREMENT)
        put("dec${scope}var", VariableMacro.DECREMENT)
        put("delete${scope}var", VariableMacro.DELETE)
        put("get${scope}varkey", VariableMacro.GET_KEY)
        put("get${scope}varindex", VariableMacro.GET_KEY)
        put("set${scope}varkey", VariableMacro.SET_KEY)
        put("set${scope}varindex", VariableMacro.SET_KEY)
    }
}

internal fun addVariable(left: String, right: String): String {
    if (left.startsWith('[')) return runCatching { JSONArray(left).put(right).toString() }.getOrDefault(left + right)
    val a = variableNumber(left)
    val b = variableNumber(right)
    return if (a == null || b == null) left + right else formatVariableNumber(a + b)
}

internal fun variableNumber(value: String) = value.trim().ifEmpty { "0" }.toDoubleOrNull()?.takeIf(Double::isFinite)

internal fun formatVariableNumber(value: Double): String =
    if (value.isFinite() && value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

internal fun readVariableKey(raw: String?, index: String): String {
    val source = raw?.trim() ?: return ""
    return runCatching {
        val position = index.toIntOrNull()
        if (source.startsWith('[') && position != null) {
            val array = JSONArray(source)
            val at = if (position < 0) array.length() + position else position
            if (array.isNull(at)) "" else array.optString(at)
        } else {
            val value = JSONObject(source)
            if (value.isNull(index)) "" else value.optString(index)
        }
    }.getOrDefault("")
}

internal fun writeVariableKey(raw: String?, index: String, value: String): String {
    val source = raw?.trim()
    val number = index.toIntOrNull()
    if (number != null && (source.isNullOrEmpty() || source.startsWith('['))) {
        val array = if (source.isNullOrEmpty()) JSONArray() else JSONArray(source)
        val at = if (number < 0) array.length() + number else number
        require(at in 0..100_000) { "无效的列表索引 $index" }
        return array.put(at, value).toString()
    }
    return (if (source.isNullOrEmpty()) JSONObject() else JSONObject(source)).put(index, value).toString()
}
