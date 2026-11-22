package app.tavern.nativeclient

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.round
import kotlin.random.Random

internal interface StScriptHost {
    fun variable(key: String, global: Boolean): String?
    suspend fun variable(key: String, value: String?, global: Boolean)
    fun macro(name: String): String?
    fun macro(name: String, args: List<String>): String? = macro((listOf(name) + args).joinToString("::"))
    fun macroSeed(): String = ""
    suspend fun commitVariables(changes: MacroVariableChanges) {
        changes.local.forEach { (key, value) -> variable(key, value, false) }
        changes.global.forEach { (key, value) -> variable(key, value, true) }
    }
    fun quickReply(name: String): String?
    suspend fun command(name: String, named: Map<String, String>, text: String): String?
    fun registerTool(tool: ModelTool) { error("当前脚本环境不支持工具注册") }
}

internal class StScript(private val host: StScriptHost, private val random: Random = Random.Default,
                        private val toolArgumentsAreData: Boolean = false) {
    private sealed interface Value {
        data class Text(val text: String, val literal: Boolean = false) : Value
        data class Closure(val body: String, val frame: Frame) : Value
    }
    private class Frame(val parent: Frame? = null, val arguments: Map<String, String> = emptyMap()) {
        val values = mutableMapOf<String, Value>()
        fun get(name: String): Value? = values[name] ?: parent?.get(name)
        fun set(name: String, value: Value) {
            if (name in values) values[name] = value
            else requireNotNull(parent) { "未声明脚本变量 $name" }.set(name, value)
        }
        fun argument(name: String): String = arguments[name] ?: parent?.argument(name).orEmpty()
    }
    private data class Result(val value: Value, val broken: Boolean = false)
    private val empty = Value.Text("")
    private var steps = 0

    suspend fun run(source: String, arguments: Map<String, String> = emptyMap()): String {
        steps = 0
        return program(parseStScript(source), Frame(arguments = arguments), empty, 0).value.text()
    }

    suspend fun render(source: String): String = expand(source, Frame(), empty)

    private suspend fun program(parsed: ParsedScript, frame: Frame, upstream: Value, depth: Int): Result {
        require(depth < 80) { "脚本调用层数超过 80" }
        parsed.defaults.forEach { (name, value) ->
            frame.values[name] = frame.arguments[name]?.let(Value::Text) ?: value(value, frame, upstream)
        }
        frame.arguments.forEach { (name, value) -> frame.values.putIfAbsent(name, Value.Text(value)) }
        var pipe = upstream
        parsed.commands.forEachIndexed { i, command ->
            tick()
            val implicit = if (i == 0) empty else pipe
            val result = try {
                execute(command, frame, pipe, implicit, depth)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw IllegalArgumentException("第 ${command.line} 行 /${command.name} · ${e.localizedMessage}", e)
            }
            if (result.broken) return result
            pipe = result.value
        }
        return Result(pipe)
    }

    private suspend fun execute(node: ScriptCommand, frame: Frame, pipe: Value, implicit: Value, depth: Int): Result {
        suspend fun args() = node.named.mapValues { (key, argument) ->
            if (key == "tag" && node.repeatedTags.size > 1) {
                JSONArray(node.repeatedTags.flatMap { scriptFindTags(value(it, frame, pipe).text()) }).toString()
            } else value(argument, frame, pipe).text()
        }
        suspend fun body(from: Int = 0, fallback: Value = empty): Value {
            val items = node.args.drop(from)
            if (items.isEmpty()) return fallback
            if (items.size == 1) return value(items.single(), frame, pipe)
            return expandedValue(ScriptArgument(node.rawFrom(from)).text(), frame, pipe)
        }
        suspend fun operand(raw: String) = raw.toDoubleOrNull()?.let(::formatVariableNumber) ?: frame.get(raw)?.text()
            ?: host.variable(raw, false) ?: host.variable(raw, true) ?: raw
        suspend fun condition(): Boolean {
            val named = argsForCondition(node, frame, pipe)
            val left = operand(named["left"] ?: pipe.text())
            val right = operand(named["right"].orEmpty())
            return compare(left, right, named["rule"].orEmpty())
        }
        suspend fun call(target: Value, params: Map<String, String> = emptyMap()): Result {
            require(!toolArgumentsAreData || target !is Value.Text || target.literal) {
                "工具参数是数据，请在脚本里指定要运行的闭包或快捷回复"
            }
            val selected = if (target is Value.Text) frame.get(target.text) ?: target else target
            if (selected is Value.Closure) {
                return program(parseStScript(selected.body), Frame(selected.frame, params), pipe, depth + 1)
            }
            val name = selected.text()
            val raw = if (name.trimStart().startsWith('/')) name else host.quickReply(name)
                ?: error("找不到闭包或快捷回复 $name")
            if (!raw.trimStart().startsWith('/')) return Result(Value.Text(expand(raw, Frame(frame, params), pipe)))
            return program(parseStScript(raw), Frame(frame, params), pipe, depth + 1)
        }
        fun text(value: String) = Result(Value.Text(value))
        val name = node.name
        return when {
            name == "tools-register" || name == "tool-register" -> {
                suspend fun closure(argument: ScriptArgument?, field: String): (suspend (JSONObject) -> String)? =
                    argument?.let {
                        val selected = value(it, frame, pipe) as? Value.Closure ?: error("$field 必须是 STscript 闭包")
                        toolClosure(selected)
                    }
                require(node.args.size == 1) { "请提供一个工具执行闭包" }
                val action = requireNotNull(closure(node.args.single(), "工具执行参数"))
                val format = closure(node.named["formatMessage"], "formatMessage")
                val condition = closure(node.named["shouldRegister"], "shouldRegister")
                val options = args()
                val toolName = options["name"].orEmpty().trim()
                val description = options["description"].orEmpty().trim()
                require(toolName.isNotEmpty()) { "请填写工具名称 name" }
                require(description.isNotEmpty()) { "请填写工具说明 description" }
                val schema = toolArguments(options["parameters"]?.takeIf(String::isNotBlank)
                    ?: error("请填写参数结构 parameters"))
                host.registerTool(ModelTool(toolName, options["displayName"].orEmpty().ifBlank { toolName }, description,
                    schema, stealth = isTrue(options["stealth"]), formatMessage = format,
                    shouldRegister = { condition?.invoke(JSONObject())?.let(::isTrue) ?: true }, invoke = action))
                text("")
            }
            name in setOf("pass", "return") -> Result(body(fallback = implicit))
            name == "break" -> Result(body(fallback = pipe), broken = true)
            name in setOf("let", "var") -> {
                val options = args()
                val key = options["key"] ?: node.args.firstOrNull()?.let { value(it, frame, pipe).text() }.orEmpty()
                require(key.isNotBlank()) { "需要变量名" }
                val offset = if ("key" in options) 0 else 1
                if (name == "let") frame.values[key] = body(offset, if ("key" in options) implicit else empty)
                else if (node.args.size > offset) frame.set(key, body(offset))
                Result(frame.get(key) ?: empty)
            }
            name in VARIABLE_COMMANDS -> {
                val settings = args()
                val global = "global" in name
                val key = if (name.startsWith("flush")) {
                    settings["key"] ?: node.args.firstOrNull()?.let { value(it, frame, pipe).text() }.orEmpty().trim()
                } else {
                    settings["key"] ?: body(fallback = implicit).text().trim()
                }
                if (name.startsWith("flush") && key.isBlank()) {
                    text(host.command(name, settings, "") ?: "")
                } else {
                    require(key.isNotBlank()) { "需要变量名" }
                    val existing = host.variable(key, global)
                    val index = settings["index"]
                    when {
                        name.startsWith("get") -> text(if (index == null) existing.orEmpty() else readVariableKey(existing, index))
                        name.startsWith("flush") -> { host.variable(key, null, global); text("") }
                        else -> {
                        val item = body(fallback = implicit).text()
                        val previous = if (index == null) existing.orEmpty() else readVariableKey(existing, index)
                        val updated = when {
                            name.startsWith("set") -> item
                            name.startsWith("inc") -> addVariable(previous, "1")
                            name.startsWith("dec") -> addVariable(previous, "-1")
                            else -> addVariable(previous, item)
                        }
                        host.variable(key, if (index == null) updated else writeVariableKey(existing, index, updated), global)
                        text(updated)
                        }
                    }
                }
            }
            name == "if" -> {
                val choice = if (condition()) body() else node.named["else"]?.let { value(it, frame, pipe) } ?: empty
                if (choice.text().isBlank()) text("") else call(choice)
            }
            name == "times" -> {
                val first = node.args.firstOrNull()?.let { value(it, frame, pipe) } ?: implicit
                val repeat = operand(first.text())
                    .toDoubleOrNull()?.toInt() ?: error("需要循环次数")
                var result = Result(empty)
                val block = if (node.args.getOrNull(1)?.closure == true && node.args.size == 2) body(1) else null
                for (i in 0 until repeat.coerceIn(0, 10_000)) {
                    tick()
                    frame.values["timesIndex"] = Value.Text(i.toString())
                    result = call(block ?: body(1))
                    if (result.broken) break
                }
                text(result.value.text())
            }
            name == "while" -> {
                var result = Result(empty)
                val block = body()
                require(block.text().isNotBlank()) { "需要循环闭包" }
                while (condition()) {
                    tick()
                    result = call(block)
                    if (result.broken) break
                }
                Result(result.value)
            }
            name in setOf("run", "call", "exec") || name.startsWith(':') -> {
                val result = call(if (name.startsWith(':')) Value.Text(name.drop(1), literal = true) else body(fallback = implicit), args())
                Result(result.value)
            }
            name in setOf("delay", "wait", "sleep") -> {
                val wait = body(fallback = implicit).text().toLongOrNull()?.takeIf { it >= 0 }
                    ?: error("需要有效的等待毫秒数")
                delay(wait)
                text("")
            }
            name in setOf("add", "sub", "mul", "div", "mod", "pow", "min", "max", "round", "abs") -> {
                val items = if (node.args.isEmpty()) implicit.text().split(Regex("\\s+")) else node.args.map { value(it, frame, pipe).text() }
                val numbers = items.map { operand(it).toDoubleOrNull() ?: error("不是数字 $it") }
                require(numbers.isNotEmpty()) { "需要数值参数" }
                val a = numbers.first()
                val result = when (name) {
                    "add" -> numbers.sum()
                    "sub" -> numbers.reduce(Double::minus)
                    "mul" -> numbers.reduce(Double::times)
                    "div" -> numbers.reduce(Double::div)
                    "mod" -> numbers.reduce(Double::rem)
                    "pow" -> numbers.reduce(Math::pow)
                    "min" -> numbers.min()
                    "max" -> numbers.max()
                    "round" -> round(a)
                    else -> kotlin.math.abs(a)
                }
                text(formatVariableNumber(result))
            }
            name == "trim" -> text(body(fallback = implicit).text().trim())
            name == "len" -> {
                val raw = body(fallback = implicit).text()
                val length = runCatching { JSONArray(raw).length() }.getOrNull()
                    ?: runCatching { JSONObject(raw).length() }.getOrNull() ?: raw.length
                text(length.toString())
            }
            name == "split" -> text(JSONArray(body(fallback = implicit).text().split(args()["separator"] ?: ",")).toString())
            name == "join" -> {
                val array = JSONArray(body(fallback = implicit).text())
                text((0 until array.length()).joinToString(args()["separator"] ?: ",") { array.optString(it) })
            }
            name in scriptTextCommands -> {
                val input = body(fallback = implicit)
                require(input is Value.Text) { "此命令需要文本或 JSON 参数" }
                val options = args()
                text(withContext(Dispatchers.Default) { scriptTextCommand(name, options, input.text) })
            }
            else -> {
                val value = body(fallback = if (name == "char-get" || name == "char-data") empty else implicit).text()
                text(host.command(name, args(), value) ?: error("未实现的命令"))
            }
        }
    }

    private suspend fun argsForCondition(node: ScriptCommand, frame: Frame, pipe: Value): Map<String, String> =
        node.named.filterKeys { it != "else" }.mapValues { (_, arg) -> value(arg, frame, pipe).text() }

    private suspend fun tick() {
        currentCoroutineContext().ensureActive()
        check(++steps <= 10_000) { "脚本执行步数超过 10,000" }
        if (steps % 64 == 0) yield()
    }

    private suspend fun value(arg: ScriptArgument, frame: Frame, pipe: Value): Value {
        if (arg.closure) return Value.Closure(arg.raw.drop(2).dropLast(2), frame)
        val raw = arg.text()
        val macro = MACRO.matchEntire(raw)
        if (macro != null) {
            val key = macro.groupValues[1]
            if (key == "pipe") return pipe
            if (key.startsWith("var::")) frame.get(key.removePrefix("var::"))?.let { return it }
        }
        return expandedValue(raw, frame, pipe)
    }

    private suspend fun expandedValue(raw: String, frame: Frame, pipe: Value): Value.Text {
        val expanded = expand(raw, frame, pipe)
        return Value.Text(expanded, literal = expanded == raw && !MACRO.containsMatchIn(raw))
    }

    private fun toolClosure(closure: Value.Closure): suspend (JSONObject) -> String {
        val parsed = parseStScript(closure.body)
        return { arguments ->
            val script = StScript(host, random, toolArgumentsAreData = true)
            script.program(parsed, Frame(closure.frame, toolVariables(arguments)), script.empty, 0).value.text()
        }
    }

    private suspend fun expand(raw: String, frame: Frame, pipe: Value): String {
        val variables = MacroVariables(host::variable)
        val renderer = TavernMacroRenderer(host.macroSeed(), { name, args ->
            val key = args.firstOrNull().orEmpty()
            when (name) {
                "pipe" -> pipe.text()
                "var" -> frame.get(key)?.text().orEmpty()
                "arg" -> frame.argument(key)
                "timesindex" -> frame.get("timesIndex")?.text().orEmpty()
                else -> variables.resolve(name, args, write = true) ?: host.macro(name, args)
            }
        }, random)
        val result = renderer.expand(raw)
        val changes = variables.changes()
        if (!changes.isEmpty) host.commitVariables(changes)
        return result
    }

    private fun Value.text(): String = when (this) {
        is Value.Text -> text
        is Value.Closure -> "{: $body :}"
    }

    companion object {
        private val MACRO = Regex("\\{\\{([^{}]+)\\}\\}")
        private val VARIABLE_COMMANDS = setOf("getvar", "setvar", "addvar", "incvar", "decvar", "flushvar",
            "getglobalvar", "setglobalvar", "addglobalvar", "incglobalvar", "decglobalvar", "flushglobalvar")

        private fun isTrue(value: String?) = value?.trim()?.lowercase() in setOf("true", "1", "yes", "on")

        private fun compare(left: String, right: String, rule: String): Boolean {
            val a = left.toDoubleOrNull()
            val b = right.toDoubleOrNull()
            val order = if (a != null && b != null) a.compareTo(b) else left.compareTo(right, ignoreCase = true)
            val truth = left.isNotBlank() && left.lowercase() !in setOf("false", "off", "no", "0", "null", "undefined")
            return when (rule.lowercase()) {
                "eq", "==" -> order == 0
                "neq", "ne", "!=" -> order != 0
                "gt", ">" -> order > 0
                "gte", "ge", ">=" -> order >= 0
                "lt", "<" -> order < 0
                "lte", "le", "<=" -> order <= 0
                "in" -> left.contains(right, ignoreCase = true)
                "nin", "notin" -> !left.contains(right, ignoreCase = true)
                "not", "false" -> !truth
                "", "truthy", "true" -> truth
                else -> error("未知条件规则 $rule")
            }
        }

    }
}
