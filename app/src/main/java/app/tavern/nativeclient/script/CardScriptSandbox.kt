package app.tavern.nativeclient.script

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.tavern.nativeclient.Message
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.lang.ref.WeakReference
import kotlin.math.floor

internal data class MvuDelta(
    val op: String,
    val path: String,
    val before: String?,
    val after: String?,
)

internal data class MvuComputation(
    val statData: JSONObject,
    val messageDeltas: Map<Int, List<MvuDelta>>,
)

internal object MvuEngine {
    private val updateBlockRegex = Regex("""(?is)<UpdateVariable\b[^>]*>([\s\S]*?)</UpdateVariable\s*>""")
    private val analysisBlockRegex = Regex("""(?is)<Analysis\b[^>]*>[\s\S]*?</Analysis\s*>""")
    private val trailingStatPrefix = Regex("""^(?:\$|stat_data)\.?""")
    private val identifierPartRegex = Regex("""[A-Za-z0-9_$\u4E00-\u9FFF]""")
    private val dangerousContainerStrip = Regex("""(?is)<(?:iframe|object|embed|applet|form|meta|base|link)\b[^>]*>[\s\S]*?</(?:iframe|object|embed|applet|form)\s*>|<(?:iframe|object|embed|applet|form|meta|base|link)\b[^>]*/?>""")
    private val externalUrlAttr = Regex("""(?is)\b(src|href|action|poster|xlink:href)\s*=\s*(?:"\s*(?:https?://|javascript:|vbscript:)[^"]*"|'\s*(?:https?://|javascript:|vbscript:)[^']*')""")
    private val cssImportStrip = Regex("""(?is)@import\b[^;]*;?""")
    private val cssExternalUrlStrip = Regex("""(?is)url\(\s*['"]?\s*(?:https?:|javascript:|vbscript:)[^)]*\)""")

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

    fun recomputeMvuState(
        messages: List<Message>,
        initial: JSONObject = JSONObject(),
        upToIndex: Int = messages.lastIndex,
    ): MvuComputation {
        val state = runCatching { JSONObject(initial.toString()) }.getOrElse { JSONObject() }
        val deltasByMessage = linkedMapOf<Int, List<MvuDelta>>()
        val limit = minOf(upToIndex, messages.lastIndex)
        for (index in 0..limit) {
            val msg = messages[index]
            if (msg.excludedFromContext) continue
            val rawText = msg.text
            if (!rawText.contains("UpdateVariable", ignoreCase = true)) continue
            val msgDeltas = mutableListOf<MvuDelta>()
            updateBlockRegex.findAll(rawText).forEach { match ->
                val inner = analysisBlockRegex.replace(match.groupValues[1], "").trim()
                if (inner.isNotEmpty()) {
                    applyBlock(state, inner, msgDeltas)
                }
            }
            if (msgDeltas.isNotEmpty()) {
                deltasByMessage[index] = msgDeltas
            }
        }
        return MvuComputation(state, deltasByMessage)
    }

    fun readPath(root: JSONObject, rawPath: String): String? {
        val tokens = tokenizePath(rawPath)
        if (tokens.isEmpty()) return root.toString()
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
        return formatValue(current)
    }

    private fun applyBlock(state: JSONObject, inner: String, outDeltas: MutableList<MvuDelta>) {
        // Check JSON Patch array first
        if (inner.startsWith("[")) {
            val patchArray = runCatching { JSONArray(inner) }.getOrNull()
            if (patchArray != null) {
                applyJsonPatchArray(state, patchArray, outDeltas)
                return
            }
        }
        val statements = splitStatements(inner)
        for (stmt in statements) {
            val trimmed = stmt.trim().removeSuffix(";").trim()
            if (trimmed.isEmpty()) continue
            applyStatement(state, trimmed, outDeltas)
        }
    }

    private fun applyJsonPatchArray(state: JSONObject, ops: JSONArray, outDeltas: MutableList<MvuDelta>) {
        for (i in 0 until ops.length()) {
            val item = ops.optJSONObject(i) ?: continue
            val op = item.optString("op").lowercase()
            val pointer = item.optString("path")
            if (pointer.isBlank()) continue
            val normalizedPath = pointerToDotPath(pointer)
            when (op) {
                "replace", "add" -> {
                    val value = item.opt("value")
                    setPath(state, normalizedPath, value, outDeltas, opName = op)
                }
                "remove" -> unsetPath(state, normalizedPath, null, outDeltas)
            }
        }
    }

    private fun applyStatement(state: JSONObject, stmt: String, outDeltas: MutableList<MvuDelta>) {
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
                setPath(state, path, parseJsValue(newValueRaw), outDeltas, opName = "set")
            }
            "_.add", "add" -> {
                if (args.size < 2) return
                val path = (parseJsValue(args[0]) as? String) ?: args[0].trim('"', '\'')
                val delta = parseJsValue(args[1])
                addPath(state, path, delta, outDeltas)
            }
            "_.assign", "assign" -> {
                if (args.size == 2) {
                    val path = (parseJsValue(args[0]) as? String) ?: args[0].trim('"', '\'')
                    val patch = parseJsValue(args[1]) as? JSONObject ?: return
                    assignPath(state, path, patch, outDeltas)
                } else if (args.size >= 3) {
                    val path = (parseJsValue(args[0]) as? String) ?: args[0].trim('"', '\'')
                    val key = (parseJsValue(args[1]) as? String) ?: args[1].trim('"', '\'')
                    val value = parseJsValue(args[2])
                    val fullPath = if (path.isBlank()) key else "$path.$key"
                    setPath(state, fullPath, value, outDeltas, opName = "assign")
                }
            }
            "_.unset", "unset" -> {
                if (args.isEmpty()) return
                val path = (parseJsValue(args[0]) as? String) ?: args[0].trim('"', '\'')
                val targetItem = if (args.size >= 2) parseJsValue(args[1]) else null
                unsetPath(state, path, targetItem, outDeltas)
            }
        }
    }

    private fun setPath(
        root: JSONObject,
        rawPath: String,
        value: Any?,
        outDeltas: MutableList<MvuDelta>,
        opName: String = "set",
    ) {
        val tokens = tokenizePath(rawPath)
        if (tokens.isEmpty()) return
        val before = readPath(root, rawPath)
        val leaf = tokens.last()
        val parent = navigateOrCreateParent(root, tokens.dropLast(1)) ?: return
        val jsonVal = value ?: JSONObject.NULL
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

    private fun addPath(root: JSONObject, rawPath: String, delta: Any?, outDeltas: MutableList<MvuDelta>) {
        val tokens = tokenizePath(rawPath)
        if (tokens.isEmpty()) return
        val beforeStr = readPath(root, rawPath)
        val leaf = tokens.last()
        val parent = navigateOrCreateParent(root, tokens.dropLast(1)) ?: return
        val existing: Any? = when {
            leaf is PathSegment.Key && parent is JSONObject -> parent.opt(leaf.name)
            leaf is PathSegment.Index && parent is JSONArray -> if (leaf.index in 0 until parent.length()) parent.opt(leaf.index) else null
            leaf is PathSegment.Index && parent is JSONObject -> parent.opt(leaf.index.toString())
            else -> null
        }

        val updated: Any? = when {
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

    private fun assignPath(root: JSONObject, rawPath: String, patch: JSONObject, outDeltas: MutableList<MvuDelta>) {
        val base = cleanPathLabel(rawPath)
        patch.keys().forEach { k ->
            val targetPath = if (base.isBlank()) k else "$base.$k"
            setPath(root, targetPath, patch.opt(k), outDeltas, opName = "assign")
        }
    }

    private fun unsetPath(root: JSONObject, rawPath: String, itemToRemove: Any?, outDeltas: MutableList<MvuDelta>) {
        val tokens = tokenizePath(rawPath)
        if (tokens.isEmpty()) return
        val before = readPath(root, rawPath)
        if (itemToRemove != null) {
            // Remove matching key/element inside array/object at rawPath
            val container = if (tokens.isEmpty()) root else navigateExisting(root, tokens)
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
                outDeltas += MvuDelta("unset", cleanPathLabel(rawPath), before, kept.toString())
                return
            } else if (container is JSONObject) {
                container.remove(itemToRemove.toString())
                outDeltas += MvuDelta("unset", cleanPathLabel("$rawPath.$itemToRemove"), before, null)
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
        outDeltas += MvuDelta("unset", cleanPathLabel(rawPath), before, null)
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

    internal fun sanitizeCardScriptHtml(
        rawHtml: String,
        darkTheme: Boolean,
        bgHex: String,
        surfaceHex: String,
        fgHex: String,
        mutedHex: String,
        accentHex: String,
        borderHex: String,
    ): String {
        val cleaned = rawHtml
            .replace(dangerousContainerStrip, "")
            .replace(externalUrlAttr) { m -> " ${m.groupValues[1]}=\"#blocked\"" }
            .replace(cssImportStrip, "")
            .replace(cssExternalUrlStrip, "none")

        val bridgeBootstrap = """
            <script>
            (function() {
                function safeParse(raw, fallback) {
                    try { return JSON.parse(raw); } catch (e) { return fallback; }
                }
                var helper = {
                    getVariables: function() {
                        if (window.TavernNativeBridge) {
                            return safeParse(window.TavernNativeBridge.getVariables(), {});
                        }
                        return {};
                    },
                    getVariable: function(path) {
                        if (window.TavernNativeBridge) {
                            return window.TavernNativeBridge.getVariable(String(path || ""));
                        }
                        return "";
                    },
                    triggerSlash: function(cmd) {
                        if (window.TavernNativeBridge && cmd) {
                            window.TavernNativeBridge.triggerSlash(String(cmd));
                        }
                    }
                };
                window.TavernHelper = helper;
                window.SillyTavern = window.SillyTavern || {
                    getContext: function() {
                        return { variables: helper.getVariables(), stat_data: helper.getVariables() };
                    }
                };
            })();
            </script>
        """.trimIndent()

        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="utf-8">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src data:;">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <style>
            :root {
                --bg: $bgHex;
                --surface: $surfaceHex;
                --fg: $fgHex;
                --fg-muted: $mutedHex;
                --accent: $accentHex;
                --border: $borderHex;
                color-scheme: ${if (darkTheme) "dark" else "light"};
            }
            html, body {
                margin: 0;
                padding: 8px;
                background: transparent;
                color: var(--fg);
                font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                font-size: 14px;
                line-height: 1.5;
                overflow-x: auto;
            }
            </style>
            $bridgeBootstrap
            </head>
            <body>
            $cleaned
            </body>
            </html>
        """.trimIndent()
    }
}

internal class TavernHelperBridge(
    private val readVariables: () -> String,
    onSlashCommand: (String) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val slashRef = WeakReference(onSlashCommand)
    @Volatile
    private var lastSlashAt = 0L

    @JavascriptInterface
    fun getVariables(): String = runCatching { readVariables() }.getOrDefault("{}")

    @JavascriptInterface
    fun getVariable(path: String?): String {
        val rawPath = path?.trim().orEmpty()
        if (rawPath.isEmpty()) return ""
        return runCatching {
            val obj = JSONObject(readVariables())
            MvuEngine.readPath(obj, rawPath).orEmpty()
        }.getOrDefault("")
    }

    @JavascriptInterface
    fun triggerSlash(command: String?) {
        val trimmed = command?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.length > 4096 || !trimmed.startsWith("/")) return
        val now = System.currentTimeMillis()
        if (now - lastSlashAt < 250L) return
        lastSlashAt = now
        mainHandler.post {
            slashRef.get()?.invoke(trimmed)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun CardScriptWebView(
    rawHtml: String,
    label: String,
    mvuStateJson: String,
    onRunScript: ((String) -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme() || scheme.surface.luminanceCompat() < 0.4f
    var collapsed by rememberSaveable(label) { mutableStateOf(false) }
    var showSource by rememberSaveable(label) { mutableStateOf(false) }
    val latestVarsState = rememberUpdatedState(mvuStateJson)
    val latestRunScriptState = rememberUpdatedState(onRunScript)
    val wrappedHtml = remember(rawHtml, dark, scheme, mvuStateJson) {
        MvuEngine.sanitizeCardScriptHtml(
            rawHtml = rawHtml,
            darkTheme = dark,
            bgHex = scheme.surface.toHexCss(),
            surfaceHex = scheme.surfaceContainerHigh.toHexCss(),
            fgHex = scheme.onSurface.toHexCss(),
            mutedHex = scheme.onSurfaceVariant.toHexCss(),
            accentHex = scheme.primary.toHexCss(),
            borderHex = scheme.outlineVariant.toHexCss(),
        )
    }

    OutlinedCard(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = scheme.surfaceContainerLow),
        border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$label · 沙盒脚本",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { showSource = !showSource }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Outlined.Code,
                        contentDescription = if (showSource) "预览面板" else "查看源码",
                        modifier = Modifier.size(16.dp),
                        tint = if (showSource) scheme.primary else scheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { collapsed = !collapsed }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (collapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                        contentDescription = if (collapsed) "展开面板" else "折叠面板",
                        modifier = Modifier.size(18.dp),
                        tint = scheme.onSurfaceVariant,
                    )
                }
            }
            if (!collapsed) {
                HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
                if (showSource) {
                    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(10.dp)) {
                        SelectionContainer {
                            Text(
                                text = rawHtml,
                                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp),
                            )
                        }
                    }
                } else {
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 560.dp),
                        factory = { context ->
                            WebView(context).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                )
                                setBackgroundColor(0)
                                isVerticalScrollBarEnabled = false
                                isHorizontalScrollBarEnabled = true
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    allowFileAccess = false
                                    allowContentAccess = false
                                    @Suppress("DEPRECATION")
                                    allowFileAccessFromFileURLs = false
                                    @Suppress("DEPRECATION")
                                    allowUniversalAccessFromFileURLs = false
                                    blockNetworkLoads = true
                                    blockNetworkImage = true
                                    loadsImagesAutomatically = false
                                    setSupportZoom(false)
                                    builtInZoomControls = false
                                    displayZoomControls = false
                                }
                                val nativeBridge = TavernHelperBridge(
                                    readVariables = { latestVarsState.value },
                                    onSlashCommand = { cmd -> latestRunScriptState.value?.invoke(cmd) },
                                )
                                addJavascriptInterface(nativeBridge, "TavernNativeBridge")
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true

                                    override fun shouldInterceptRequest(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                    ): WebResourceResponse = WebResourceResponse(
                                        "text/plain",
                                        "utf-8",
                                        ByteArrayInputStream(ByteArray(0)),
                                    )
                                }
                            }
                        },
                        update = { view ->
                            if (view.tag != wrappedHtml) {
                                view.tag = wrappedHtml
                                view.loadDataWithBaseURL(null, wrappedHtml, "text/html", "utf-8", null)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun MvuStateChip(deltas: List<MvuDelta>, statDataJson: String) {
    if (deltas.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = { expanded = !expanded },
        shape = RoundedCornerShape(10.dp),
        color = scheme.surfaceContainerHighest.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.65f)),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.DataObject, null, modifier = Modifier.size(15.dp), tint = scheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "MVU 状态变更 (${deltas.size} 项)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "收起状态变更" else "展开状态变更",
                    modifier = Modifier.size(16.dp),
                    tint = scheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                deltas.forEach { delta ->
                    val transition = buildString {
                        append(delta.path)
                        append(": ")
                        if (delta.before != null) {
                            append(delta.before)
                            append(" → ")
                        }
                        append(delta.after ?: "已移除")
                    }
                    Text(
                        text = "• $transition",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurface,
                    )
                }
                if (statDataJson.length > 2) {
                    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
                    Text(
                        text = statDataJson,
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp),
                        color = scheme.onSurfaceVariant,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun Color.toHexCss(): String = String.format("#%06X", toArgb() and 0xFFFFFF)

private fun Color.luminanceCompat(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
