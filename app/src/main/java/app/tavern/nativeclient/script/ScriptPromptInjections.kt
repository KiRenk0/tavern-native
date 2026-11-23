package app.tavern.nativeclient.script

import app.tavern.nativeclient.Chat
import app.tavern.nativeclient.ConversationOptions
import app.tavern.nativeclient.LoreCandidate
import app.tavern.nativeclient.LoreEntry
import app.tavern.nativeclient.LorePosition
import app.tavern.nativeclient.LoreRules
import app.tavern.nativeclient.PromptRole
import app.tavern.nativeclient.ScriptDialog
import app.tavern.nativeclient.StScript
import app.tavern.nativeclient.StScriptHost
import app.tavern.nativeclient.TavernState
import app.tavern.nativeclient.jsonOf
import app.tavern.nativeclient.newId
import app.tavern.nativeclient.objects
import app.tavern.nativeclient.scriptBoolean
import app.tavern.nativeclient.string
import org.json.JSONArray
import org.json.JSONObject

internal enum class ScriptInjectionPosition(val wire: String, val lorePosition: LorePosition?) {
    NONE("none", null),
    IN_CHAT("in_chat", LorePosition.DEPTH),
    BEFORE("before", LorePosition.BEFORE),
    AFTER("after", LorePosition.AFTER),
    AN_TOP("an_top", LorePosition.AUTHOR_TOP),
    AN_BOTTOM("an_bottom", LorePosition.AUTHOR_BOTTOM),
    EXAMPLE_TOP("example_top", LorePosition.EXAMPLE_TOP),
    EXAMPLE_BOTTOM("example_bottom", LorePosition.EXAMPLE_BOTTOM),
    OUTLET("outlet", LorePosition.OUTLET);

    companion object {
        fun from(raw: String?): ScriptInjectionPosition = when (raw?.trim()?.lowercase().orEmpty()) {
            "", "in_chat", "chat", "depth", "1" -> IN_CHAT
            "none", "-1" -> NONE
            "before", "before_char", "before_prompt", "2" -> BEFORE
            "after", "after_char", "after_prompt", "0" -> AFTER
            "an_top", "author_top", "before_an" -> AN_TOP
            "an_bottom", "author_bottom", "after_an" -> AN_BOTTOM
            "example_top" -> EXAMPLE_TOP
            "example_bottom" -> EXAMPLE_BOTTOM
            "outlet" -> OUTLET
            else -> error("未知的注入位置 position=$raw")
        }
    }
}

internal data class ScriptPromptInjection(
    val id: String,
    val text: String,
    val position: ScriptInjectionPosition = ScriptInjectionPosition.IN_CHAT,
    val depth: Int = 4,
    val role: PromptRole = PromptRole.SYSTEM,
    val ephemeral: Boolean = false,
    val scan: Boolean = false,
    val filter: String = "",
    val outlet: String = "",
) {
    fun json(): JSONObject = jsonOf(
        "id" to id,
        "text" to text,
        "position" to position.wire,
        "depth" to depth,
        "role" to role.wire,
        "ephemeral" to ephemeral,
        "scan" to scan,
        "filter" to filter,
        "outlet" to outlet,
    )

    companion object {
        fun from(row: JSONObject): ScriptPromptInjection? {
            val id = row.string("id").trim().takeIf(String::isNotEmpty) ?: return null
            val text = row.string("text")
            if (text.isBlank()) return null
            val position = runCatching { ScriptInjectionPosition.from(row.string("position")) }
                .getOrDefault(ScriptInjectionPosition.IN_CHAT)
            val role = PromptRole.entries.firstOrNull { it.wire == row.string("role").lowercase() } ?: PromptRole.SYSTEM
            return ScriptPromptInjection(
                id = id,
                text = text,
                position = position,
                depth = row.optInt("depth", 4).coerceIn(0, 80),
                role = role,
                ephemeral = row.optBoolean("ephemeral", false),
                scan = row.optBoolean("scan", false),
                filter = row.string("filter"),
                outlet = row.string("outlet"),
            )
        }

        fun fromArray(array: JSONArray?): List<ScriptPromptInjection> =
            array?.objects().orEmpty().mapNotNull(::from).distinctBy(ScriptPromptInjection::id)

        fun toArray(items: List<ScriptPromptInjection>): JSONArray = JSONArray(items.map(ScriptPromptInjection::json))
    }
}

internal object ScriptPromptInjections {
    private val falsyFilterValues = setOf("", "false", "0", "no", "off", "null", "undefined")

    suspend fun execute(
        command: String,
        named: Map<String, String>,
        text: String,
        state: TavernState,
        saveChat: suspend (Chat) -> Unit,
        ask: suspend (ScriptDialog) -> String? = { null },
        notice: (String) -> Unit = {},
    ): String? = when (command) {
        "inject" -> inject(named, text, state, saveChat)
        "listinjects", "list-injects", "injects" -> listInjects(named, state, ask, notice)
        "flushinject", "flushinjects", "flush-inject" -> flushInject(named, text, state, saveChat)
        else -> null
    }

    private suspend fun inject(
        named: Map<String, String>,
        text: String,
        state: TavernState,
        saveChat: suspend (Chat) -> Unit,
    ): String {
        val chat = requireNotNull(state.chat) { "请先打开对话" }
        val explicitId = named["id"]?.trim().orEmpty()
        val existing = chat.options.scriptInjections
        if (text.isBlank()) {
            if (explicitId.isNotEmpty()) {
                val remaining = existing.filterNot { it.id == explicitId }
                if (remaining != existing) saveChat(chat.copy(options = chat.options.copy(scriptInjections = remaining)))
            }
            return ""
        }
        val id = explicitId.ifEmpty { newId() }
        val position = ScriptInjectionPosition.from(named["position"])
        val depth = (named["depth"]?.trim()?.toIntOrNull() ?: 4).coerceIn(0, 80)
        val roleWire = named["role"]?.trim()?.lowercase().orEmpty().ifEmpty { "system" }
        val role = PromptRole.entries.firstOrNull { it.wire == roleWire }
            ?: error("role 应为 system、user 或 assistant")
        val ephemeral = named["ephemeral"].orEmpty().scriptBoolean(default = false)
        val scan = named["scan"].orEmpty().scriptBoolean(default = false)
        val filter = named["filter"].orEmpty().trim()
        val outlet = named["outlet"].orEmpty().trim().ifEmpty { if (position == ScriptInjectionPosition.OUTLET) id else "" }
        val entry = ScriptPromptInjection(
            id = id,
            text = text,
            position = position,
            depth = depth,
            role = role,
            ephemeral = ephemeral,
            scan = scan,
            filter = filter,
            outlet = outlet,
        )
        val updated = existing.filterNot { it.id == id } + entry
        saveChat(chat.copy(options = chat.options.copy(scriptInjections = updated)))
        return id
    }

    private suspend fun listInjects(
        named: Map<String, String>,
        state: TavernState,
        ask: suspend (ScriptDialog) -> String?,
        notice: (String) -> Unit,
    ): String {
        val items = state.chat?.options?.scriptInjections.orEmpty()
        val json = ScriptPromptInjection.toArray(items).toString()
        val readable = if (items.isEmpty()) "当前对话没有活动注入" else items.joinToString("\n\n") { item ->
            buildString {
                append("[${item.id}] position=${item.position.wire} depth=${item.depth} role=${item.role.wire}")
                if (item.ephemeral) append(" ephemeral=true")
                if (item.scan) append(" scan=true")
                if (item.filter.isNotBlank()) append(" filter=${item.filter}")
                append("\n").append(item.text)
            }
        }
        val mode = named["return"]?.trim()?.lowercase()
            ?: if (named["format"].equals("text", true)) "text" else "pipe"
        return when (mode) {
            "pipe", "object", "json" -> json
            "text" -> readable
            "popup", "popup-text", "popup-html" -> {
                ask(ScriptDialog(kind = "popup", title = "当前提示词注入 (${items.size})", text = readable))
                json
            }
            "toast", "toast-text", "toast-html" -> {
                notice(readable)
                json
            }
            "none" -> ""
            else -> error("未知的返回方式 $mode")
        }
    }

    private suspend fun flushInject(
        named: Map<String, String>,
        text: String,
        state: TavernState,
        saveChat: suspend (Chat) -> Unit,
    ): String {
        val chat = requireNotNull(state.chat) { "请先打开对话" }
        val targetId = (named["id"] ?: text).trim()
        val existing = chat.options.scriptInjections
        val updated = if (targetId.isEmpty()) emptyList() else existing.filterNot { it.id == targetId }
        if (updated != existing) {
            saveChat(chat.copy(options = chat.options.copy(scriptInjections = updated)))
        }
        return ""
    }

    fun isFilterTruthy(value: String): Boolean = value.trim().lowercase() !in falsyFilterValues

    fun evaluateStaticFilter(filter: String, render: (String) -> String): Boolean {
        val trimmed = filter.trim()
        if (trimmed.isEmpty()) return true
        val body = trimmed.removePrefix("{:").removeSuffix(":}").trim()
        if (body.startsWith('/')) return true // Requires full StScript evaluation beforehand
        return isFilterTruthy(render(trimmed))
    }

    suspend fun resolveForPrompt(
        options: ConversationOptions,
        hostFactory: () -> StScriptHost,
    ): ConversationOptions {
        val items = options.scriptInjections
        if (items.isEmpty() || items.none { it.filter.isNotBlank() }) return options
        val resolved = items.filter { injection ->
            val filter = injection.filter.trim()
            if (filter.isEmpty()) return@filter true
            val body = filter.removePrefix("{:").removeSuffix(":}").trim()
            val script = StScript(hostFactory())
            val result = runCatching {
                if (body.startsWith('/')) script.run(body) else script.render(filter)
            }.getOrDefault("")
            isFilterTruthy(result)
        }.map { it.copy(filter = "") }
        return options.copy(scriptInjections = resolved)
    }

    fun withoutEphemeral(options: ConversationOptions): ConversationOptions {
        if (options.scriptInjections.none(ScriptPromptInjection::ephemeral)) return options
        return options.copy(scriptInjections = options.scriptInjections.filterNot(ScriptPromptInjection::ephemeral))
    }

    fun scanText(options: ConversationOptions, render: (String) -> String): String =
        options.scriptInjections
            .filter { it.scan && it.text.isNotBlank() && evaluateStaticFilter(it.filter, render) }
            .joinToString("\n") { render(it.text) }

    fun loreCandidates(options: ConversationOptions, render: (String) -> String): List<LoreCandidate> =
        options.scriptInjections.mapIndexedNotNull { index, injection ->
            val lorePos = injection.position.lorePosition ?: return@mapIndexedNotNull null
            if (injection.text.isBlank() || !evaluateStaticFilter(injection.filter, render)) return@mapIndexedNotNull null
            val entry = LoreEntry(
                id = 900_000 + index,
                title = "script_inject:${injection.id}",
                content = injection.text,
                constant = true,
                disabled = false,
                priority = 10_000 - index,
                position = lorePos,
                depth = injection.depth,
                role = injection.role.wire,
                rules = LoreRules(
                    ignoreBudget = true,
                    excludeRecursion = true,
                    preventRecursion = !injection.scan,
                    outlet = injection.outlet,
                ),
            )
            LoreCandidate("script-inject:${injection.id}", entry, 0)
        }
}
