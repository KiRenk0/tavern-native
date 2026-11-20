package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject

internal enum class QuickEvent(val key: String, val label: String) {
    STARTUP("executeOnStartup", "启动应用"),
    CHAT("executeOnChatChange", "打开对话"), NEW("executeOnNewChat", "新建对话"),
    USER("executeOnUser", "发送消息"), BEFORE("executeBeforeGeneration", "生成之前"),
    AI("executeOnAi", "收到模型回复"), GROUP("executeOnGroupMemberDraft", "群成员发言"),
}

internal data class QuickReply(
    val id: Int,
    val label: String = "",
    val message: String = "",
    val title: String = "",
    val hidden: Boolean = false,
    val events: Set<QuickEvent> = emptySet(),
    val preventAutoExecute: Boolean = true,
    val raw: String = "{}",
    val automationId: String = "",
) {
    fun json() = JSONObject(raw).apply {
        put("id", id).put("label", label).put("message", message).put("title", title).put("isHidden", hidden)
        put("preventAutoExecute", preventAutoExecute)
        put("automationId", automationId)
        QuickEvent.entries.forEach { put(it.key, it in events) }
    }

    companion object {
        fun read(value: JSONObject, id: Int, legacy: Boolean) = QuickReply(id, value.string("label"),
            value.string(if (legacy) "mes" else "message"), value.string("title"),
            value.optBoolean("isHidden") || (legacy && !value.optBoolean("enabled", true)),
            QuickEvent.entries.filter { value.optBoolean(it.key) }.toSet(),
            value.optBoolean("preventAutoExecute", true), value.toString(), value.string("automationId"))
    }
}

internal data class QuickReplySet(
    val id: String = newId(),
    val name: String = "",
    val items: List<QuickReply> = emptyList(),
    val disableSend: Boolean = false,
    val injectInput: Boolean = false,
    val placeBeforeInput: Boolean = false,
    val raw: String = "{}",
) {
    fun json() = JSONObject(raw).apply {
        remove("quickReplySlots")
        remove("tavern_native_id")
        put("version", 2).put("name", name).put("qrList", JSONArray(items.map(QuickReply::json)))
        put("disableSend", disableSend).put("injectInput", injectInput).put("placeBeforeInput", placeBeforeInput)
        put("idIndex", items.maxOfOrNull(QuickReply::id) ?: 0)
    }
    fun storage() = json().put("tavern_native_id", id)

    fun textFor(item: QuickReply, input: String): String =
        if (!injectInput || input.isBlank()) item.message
        else if (placeBeforeInput) "${item.message} $input" else "$input ${item.message}"

    companion object {
        fun read(value: JSONObject, id: String = newId()): QuickReplySet {
            val current = value.optJSONArray("qrList")
            val rows = current ?: value.optJSONArray("quickReplySlots")
            require(rows != null) { "JSON 中没有 SillyTavern 快捷回复列表" }
            val used = mutableSetOf<Int>()
            val items = rows.objects().mapIndexed { index, row ->
                val supplied = row.optInt("id")
                val itemId = if (supplied > 0 && used.add(supplied)) supplied
                else generateSequence(index + 1) { it + 1 }.first(used::add)
                QuickReply.read(row, itemId, current == null)
            }
            val name = value.string("name").trim().ifBlank { "导入的快捷回复集" }
            return QuickReplySet(id, name, items, value.optBoolean("disableSend"), value.optBoolean("injectInput"),
                value.optBoolean("placeBeforeInput"), value.toString())
        }
    }
}

internal data class QuickReplies(
    val enabled: Boolean = false,
    val active: List<String> = emptyList(),
    val sets: List<QuickReplySet> = emptyList(),
) {
    fun selected(chat: Chat?): List<QuickReplySet> {
        val named = chat?.options?.quickReplyNames.orEmpty().mapNotNull { name -> sets.firstOrNull { it.name == name }?.id }
        val ids = (active + chat?.options?.quickReplySets.orEmpty() + named).distinct()
        return ids.mapNotNull { id -> sets.firstOrNull { it.id == id } }
    }

    fun find(name: String, chat: Chat?): QuickReply? {
        selected(chat).forEach { set -> set.items.firstOrNull { it.label == name }?.let { return it } }
        sets.forEach { set ->
            if (name.startsWith("${set.name}.")) {
                val label = name.removePrefix("${set.name}.")
                set.items.firstOrNull { it.label == label }?.let { return it }
            }
        }
        return null
    }

    fun json() = jsonOf("enabled" to enabled, "active" to JSONArray(active),
        "sets" to JSONArray(sets.map(QuickReplySet::storage)))

    companion object {
        fun from(value: JSONObject?) = QuickReplies(value?.optBoolean("enabled") == true,
            value?.optJSONArray("active").strings(),
            value?.optJSONArray("sets")?.objects().orEmpty().map { QuickReplySet.read(it, it.string("tavern_native_id")) })
    }
}

internal data class ScriptDialog(val id: String = newId(), val kind: String, val title: String,
                                 val text: String, val buttons: List<String> = emptyList(), val initial: String = "")

internal fun JSONObject?.variableStrings(): Map<String, String> = this?.let { value ->
    value.keys().asSequence().filterNot(value::isNull).associateWith(value::string)
}.orEmpty()

internal fun JSONObject?.quickReplyNames(): List<String> =
    this?.optJSONArray("setList")?.objects().orEmpty().map { it.string("set") }.filter(String::isNotBlank).distinct()

