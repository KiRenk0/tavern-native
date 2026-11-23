package app.tavern.nativeclient.script

import app.tavern.nativeclient.newId
import kotlinx.coroutines.CompletableDeferred

internal enum class DebugAction {
    STEP_OVER,
    CONTINUE,
    ABORT,
}

internal data class ScriptDebugSnapshot(
    val id: String = newId(),
    val label: String,
    val source: String,
    val currentLine: Int,
    val currentCommand: String,
    val pipe: String,
    val frameVariables: Map<String, String> = emptyMap(),
    val chatVariables: Map<String, String> = emptyMap(),
    val globalVariables: Map<String, String> = emptyMap(),
    val stepCount: Int = 0,
    val pausedReason: String = "breakpoint",
)

internal interface ScriptDebuggerHook {
    suspend fun beforeCommand(
        line: Int,
        commandText: String,
        isBreakpoint: Boolean,
        pipe: String,
        frameVariables: Map<String, String>,
        stepCount: Int,
    ): DebugAction
}

internal class QuickReplyDebuggerSession(
    private val label: String,
    private val source: String,
    initialStepMode: Boolean = false,
    private val readChatVariables: () -> Map<String, String> = { emptyMap() },
    private val readGlobalVariables: () -> Map<String, String> = { emptyMap() },
    private val onStateChanged: (ScriptDebugSnapshot?) -> Unit = {},
) : ScriptDebuggerHook {
    @Volatile
    var stepMode: Boolean = initialStepMode
        private set

    @Volatile
    private var pending: CompletableDeferred<DebugAction>? = null

    val breakpointsHit = mutableListOf<Int>()
    val pausedLines = mutableListOf<Int>()

    override suspend fun beforeCommand(
        line: Int,
        commandText: String,
        isBreakpoint: Boolean,
        pipe: String,
        frameVariables: Map<String, String>,
        stepCount: Int,
    ): DebugAction {
        if (!isBreakpoint && !stepMode) return DebugAction.CONTINUE
        if (isBreakpoint) breakpointsHit += line
        pausedLines += line
        val deferred = CompletableDeferred<DebugAction>()
        pending = deferred
        onStateChanged(
            ScriptDebugSnapshot(
                label = label,
                source = source,
                currentLine = line,
                currentCommand = commandText,
                pipe = pipe,
                frameVariables = frameVariables,
                chatVariables = readChatVariables(),
                globalVariables = readGlobalVariables(),
                stepCount = stepCount,
                pausedReason = if (isBreakpoint) "breakpoint" else "step",
            ),
        )
        return try {
            val action = deferred.await()
            stepMode = action == DebugAction.STEP_OVER
            action
        } finally {
            pending = null
            onStateChanged(null)
        }
    }

    fun resume(action: DebugAction): Boolean = pending?.complete(action) == true

    fun cancel() {
        pending?.complete(DebugAction.ABORT)
        pending = null
        onStateChanged(null)
    }
}

internal enum class StScriptTokenKind {
    COMMAND,
    PIPE,
    CLOSURE,
    MACRO,
    NAMED_ARG,
    STRING,
    COMMENT,
}

internal data class StScriptToken(
    val start: Int,
    val end: Int,
    val kind: StScriptTokenKind,
)

private val legacyMacroToken = Regex("<(?:USER|BOT|CHAR|GROUP|CHARIFNOTGROUP)>", RegexOption.IGNORE_CASE)
private val namedArgToken = Regex("[A-Za-z_][A-Za-z0-9_.-]*=")

internal fun tokenizeStScript(source: String): List<StScriptToken> {
    if (source.isEmpty()) return emptyList()
    val tokens = mutableListOf<StScriptToken>()
    var i = 0
    val n = source.length
    var expectCommand = true
    while (i < n) {
        val ch = source[i]
        when {
            ch == '\\' -> i = minOf(i + 2, n)
            source.startsWith("/*", i) -> {
                val close = source.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
                tokens += StScriptToken(i, close, StScriptTokenKind.COMMENT)
                i = close
            }
            source.startsWith("//", i) && expectCommand -> {
                var end = i + 2
                while (end < n && source[end] != '\n' && source[end] != '|') end++
                tokens += StScriptToken(i, end, StScriptTokenKind.COMMENT)
                i = end
            }
            source.startsWith("{:", i) -> {
                tokens += StScriptToken(i, i + 2, StScriptTokenKind.CLOSURE)
                i += 2
                expectCommand = true
            }
            source.startsWith(":}", i) -> {
                tokens += StScriptToken(i, i + 2, StScriptTokenKind.CLOSURE)
                i += 2
                expectCommand = false
            }
            source.startsWith("{{", i) -> {
                val start = i
                var depth = 1
                i += 2
                while (i < n && depth > 0) {
                    when {
                        source[i] == '\\' -> i = minOf(i + 2, n)
                        source.startsWith("{{", i) -> { depth++; i += 2 }
                        source.startsWith("}}", i) -> { depth--; i += 2 }
                        else -> i++
                    }
                }
                tokens += StScriptToken(start, i, StScriptTokenKind.MACRO)
            }
            ch == '<' -> {
                val match = legacyMacroToken.matchAt(source, i)
                if (match != null) {
                    tokens += StScriptToken(match.range.first, match.range.last + 1, StScriptTokenKind.MACRO)
                    i = match.range.last + 1
                } else {
                    i++
                }
            }
            ch == '|' -> {
                tokens += StScriptToken(i, i + 1, StScriptTokenKind.PIPE)
                i++
                expectCommand = true
            }
            ch == '\n' -> {
                i++
                expectCommand = true
            }
            ch.isWhitespace() -> i++
            ch == '/' && expectCommand -> {
                val start = i
                i++
                while (i < n && !source[i].isWhitespace() && source[i] != '|') i++
                tokens += StScriptToken(start, i, StScriptTokenKind.COMMAND)
                expectCommand = false
            }
            ch == '"' -> {
                val start = i
                i++
                while (i < n) {
                    if (source[i] == '\\') { i = minOf(i + 2, n); continue }
                    if (source[i] == '"') { i++; break }
                    i++
                }
                tokens += StScriptToken(start, i, StScriptTokenKind.STRING)
                expectCommand = false
            }
            else -> {
                val match = if (!expectCommand && (i == 0 || source[i - 1].isWhitespace())) {
                    namedArgToken.matchAt(source, i)
                } else null
                if (match != null) {
                    tokens += StScriptToken(match.range.first, match.range.last + 1, StScriptTokenKind.NAMED_ARG)
                    i = match.range.last + 1
                } else {
                    expectCommand = false
                    i++
                }
            }
        }
    }
    return tokens
}
