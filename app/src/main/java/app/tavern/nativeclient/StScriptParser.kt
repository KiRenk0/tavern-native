package app.tavern.nativeclient

internal data class ScriptArgument(val raw: String, val start: Int = 0) {
    val closure get() = raw.startsWith("{:") && raw.endsWith(":}")
    fun text() = when {
        raw.startsWith('"') && raw.endsWith('"') && raw.length >= 2 -> unescape(raw.drop(1).dropLast(1))
        raw.startsWith('[') || raw.startsWith('{') && !raw.startsWith("{{") && !closure -> raw
        else -> unescape(raw)
    }

    private fun unescape(value: String) = buildString {
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            if (char == '\\' && value.getOrNull(index) in listOf('|', '"', '\\', ':', '/')) {
                append(value[index++])
            } else append(char)
        }
    }
}

internal data class ScriptCommand(
    val name: String, val named: Map<String, ScriptArgument>, val args: List<ScriptArgument>, val source: String, val line: Int,
) {
    fun rawFrom(index: Int): String = args.getOrNull(index)?.start?.let { source.substring(it).trimEnd() }.orEmpty()
}

internal data class ParsedScript(val defaults: Map<String, ScriptArgument>, val commands: List<ScriptCommand>)

internal fun parseStScript(source: String): ParsedScript {
    require(source.length <= 1_000_000) { "脚本过长" }
    return ScriptReader(source).program()
}

private val assignment = Regex("[A-Za-z_][A-Za-z0-9_.-]*=")

private class ScriptReader(private val source: String) {
    private var cursor = 0
    private val breaks = source.indices.filter { source[it] == '\n' }
    private val char get() = source.getOrNull(cursor)

    fun program(): ParsedScript {
        val defaults = linkedMapOf<String, ScriptArgument>()
        val commands = mutableListOf<ScriptCommand>()
        skipWhitespace()
        while (takeAssignment()?.let { (name, value) -> defaults[name] = value; true } == true) skipWhitespace()
        while (cursor < source.length) {
            skipWhitespace()
            if (char == '|') { cursor++; continue }
            if (char == null) break
            if (source.startsWith("//", cursor)) {
                while (char != null && char != '|' && char != '\n') cursor++
                continue
            }
            if (source.startsWith("/*", cursor)) {
                val end = source.indexOf("*/", cursor + 2)
                require(end >= 0) { "脚本注释缺少 */" }
                cursor = end + 2
                continue
            }
            require(char == '/') { "第 ${line()} 行应以斜杠命令开始" }
            val line = line()
            cursor++
            val start = cursor
            while (char != null && char?.isWhitespace() != true && char != '|') cursor++
            val rawName = source.substring(start, cursor)
            val name = if (rawName.startsWith(':')) rawName else rawName.lowercase()
            require(name.isNotEmpty()) { "第 $line 行缺少命令名" }
            val commandStart = cursor
            val fields = linkedMapOf<String, ScriptArgument>()
            val positional = mutableListOf<ScriptArgument>()
            while (!commandEnd()) {
                while (char?.isWhitespace() == true && !commandEnd()) cursor++
                if (commandEnd()) break
                val named = if (positional.isEmpty()) takeAssignment() else null
                if (named != null) {
                    fields[named.first] = named.second
                    continue
                }
                val point = cursor
                positional += ScriptArgument(readArgument(), point - commandStart)
            }
            commands += ScriptCommand(name, fields, positional, source.substring(commandStart, cursor), line)
            if (char == '|' || char == '\n') cursor++
        }
        return ParsedScript(defaults, commands)
    }

    private fun takeAssignment(): Pair<String, ScriptArgument>? {
        val match = assignment.matchAt(source, cursor) ?: return null
        cursor = match.range.last + 1
        val start = cursor
        return match.value.dropLast(1) to ScriptArgument(readArgument(), start)
    }

    private fun readArgument(): String {
        val start = cursor
        while (char != null && char?.isWhitespace() != true && char != '|') {
            when {
                char == '\\' -> cursor = minOf(cursor + 2, source.length)
                char == '"' && cursor == start -> skipGroup("\"", "\"")
                source.startsWith("{:", cursor) -> skipGroup("{:", ":}")
                source.startsWith("{{", cursor) -> skipGroup("{{", "}}")
                char == '[' && cursor == start -> skipGroup("[", "]")
                char == '{' && cursor == start -> skipGroup("{", "}")
                else -> cursor++
            }
        }
        return source.substring(start, cursor)
    }

    private fun skipGroup(open: String, close: String, level: Int = 0) {
        require(level < 80) { "脚本嵌套过深" }
        cursor += open.length
        while (cursor < source.length) {
            when {
                char == '\\' -> cursor = minOf(cursor + 2, source.length)
                source.startsWith(close, cursor) -> { cursor += close.length; return }
                open == "\"" -> cursor++
                char == '"' && (open in listOf("[", "{") || source.getOrNull(cursor - 1)?.let {
                    it.isWhitespace() || it == '=' || it == '|'
                } == true) -> skipGroup("\"", "\"", level + 1)
                source.startsWith("{:", cursor) -> skipGroup("{:", ":}", level + 1)
                source.startsWith("{{", cursor) -> skipGroup("{{", "}}", level + 1)
                char == '[' && open in listOf("[", "{") -> skipGroup("[", "]", level + 1)
                char == '{' && open in listOf("[", "{") -> skipGroup("{", "}", level + 1)
                else -> cursor++
            }
        }
        throw IllegalArgumentException("第 ${line()} 行之前缺少 $close")
    }

    private fun commandEnd(): Boolean {
        if (char == null || char == '|') return true
        if (char != '\n') return false
        var next = cursor + 1
        while (source.getOrNull(next)?.isWhitespace() == true) next++
        return source.getOrNull(next) == '/'
    }

    private fun line(): Int {
        val position = breaks.binarySearch(cursor)
        return if (position >= 0) position + 1 else -position
    }
    private fun skipWhitespace() { while (char?.isWhitespace() == true) cursor++ }
}
