package app.tavern.nativeclient

import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

private const val SCRIPT_AVATAR_KEY = "tavern_native_original_avatar"
private val combiningMarks = Regex("\\p{M}+")

internal fun Card.withScriptAvatarKey(avatar: String): Card =
    copy(original = JSONObject(original).put(SCRIPT_AVATAR_KEY, avatar.substringAfterLast('/').substringAfterLast('\\')).toString())

internal val Card.scriptAvatarKey: String
    get() {
        val root = JSONObject(original)
        val data = root.optJSONObject("data")
        return sequenceOf(root.string(SCRIPT_AVATAR_KEY), data?.string(SCRIPT_AVATAR_KEY).orEmpty(),
            root.string("avatar"), data?.string("avatar").orEmpty())
            .filter { it.endsWith(".png", true) && '/' !in it && '\\' !in it }
            .firstOrNull() ?: "$id.png"
    }

private fun String.actorName() = combiningMarks.replace(
    Normalizer.normalize(trim(), Normalizer.Form.NFD).lowercase(Locale.ROOT), "",
)

internal fun scriptActor(cards: List<Card>, selector: String, preferred: Card? = null): Card? {
    val word = selector.trim().takeIf(String::isNotEmpty) ?: return null
    val filename = if (word.endsWith(".png", true)) word else "$word.png"
    val previewId = previewCharacterId(filename)
    fun Card.matchesAvatar() = id == word || id == previewId || scriptAvatarKey.equals(filename, true)
    val key = word.actorName()
    if (preferred != null && (preferred.matchesAvatar() || preferred.name.actorName() == key)) return preferred
    return cards.firstOrNull { it.matchesAvatar() } ?: cards.firstOrNull { it.name.actorName() == key }
}

internal fun TavernGroup.scriptMember(cards: List<Card>, selector: String): Card? {
    val word = selector.trim().takeIf(String::isNotEmpty) ?: return null
    val index = word.toIntOrNull()
    if (index != null) return memberIds.getOrNull(index)?.let { id -> cards.firstOrNull { it.id == id } }
    val roster = members(cards)
    scriptActor(roster, word)?.let { return it }
    val key = word.actorName().takeIf(String::isNotEmpty) ?: return null
    return roster.firstOrNull { it.name.actorName().startsWith(key) }
        ?: roster.firstOrNull { it.name.actorName().contains(key) || it.scriptAvatarKey.actorName().contains(key) }
}

internal enum class ScriptGroupCommand(val keyword: String, vararg val aliases: String) {
    GET("member-get", "getmember", "memberget"),
    ADD("member-add", "addmember", "memberadd"),
    REMOVE("member-remove", "removemember", "memberremove"),
    ENABLE("member-enable", "enable", "enablemember", "memberenable"),
    DISABLE("member-disable", "disable", "disablemember", "memberdisable"),
    UP("member-up", "upmember", "memberup"),
    DOWN("member-down", "downmember", "memberdown"),
    PEEK("member-peek", "peek", "memberpeek", "peekmember"),
    COUNT("member-count", "countmember", "membercount");

    companion object {
        fun from(name: String) = entries.firstOrNull { name == it.keyword || name in it.aliases }
    }
}

internal suspend fun ScriptGroupCommand.execute(
    state: TavernState, field: String, selector: String,
    update: suspend (String, (TavernGroup) -> TavernGroup) -> Boolean,
    peek: suspend (Card) -> Unit, notice: (String) -> Unit,
): String {
    val group = state.group ?: return "".also { notice("请在群聊中使用 /$keyword") }
    if (this == ScriptGroupCommand.COUNT) return group.memberIds.size.toString()
    val cards = state.cards
    val card = (if (this == ScriptGroupCommand.ADD) scriptActor(cards, selector) else group.scriptMember(cards, selector))
        ?: return "".also { notice("找不到角色 $selector") }
    val index = group.memberIds.indexOf(card.id)
    when (this) {
        ScriptGroupCommand.GET -> return when (field.lowercase(Locale.ROOT)) {
            "name" -> card.name
            "avatar" -> card.scriptAvatarKey
            "index" -> index.toString()
            "id" -> cards.indexOfFirst { it.id == card.id }.toString()
            else -> "".also { notice("field 须为 name、index、id 或 avatar") }
        }
        ScriptGroupCommand.PEEK -> {
            if (state.generation == null) peek(card) else notice("群聊正在生成回复")
            return ""
        }
        else -> Unit
    }
    val changed = update(group.id) { it.applyMemberCommand(this, card.id) }
    return when {
        this != ScriptGroupCommand.ADD -> ""
        changed -> card.name
        else -> "".also { notice("${card.name} 已在群组中") }
    }
}

private fun TavernGroup.applyMemberCommand(command: ScriptGroupCommand, cardId: String): TavernGroup {
    val index = memberIds.indexOf(cardId)
    if (command == ScriptGroupCommand.ADD) return if (index < 0) copy(memberIds = memberIds + cardId) else this
    if (index < 0) return this
    return when (command) {
        ScriptGroupCommand.REMOVE -> copy(memberIds = memberIds - cardId, mutedIds = mutedIds - cardId)
        ScriptGroupCommand.ENABLE -> copy(mutedIds = mutedIds - cardId)
        ScriptGroupCommand.DISABLE -> copy(mutedIds = mutedIds + cardId)
        ScriptGroupCommand.UP, ScriptGroupCommand.DOWN -> {
            val target = index + if (command == ScriptGroupCommand.UP) -1 else 1
            if (target !in memberIds.indices) this
            else copy(memberIds = memberIds.toMutableList().apply { add(target, removeAt(index)) })
        }
        ScriptGroupCommand.ADD, ScriptGroupCommand.COUNT, ScriptGroupCommand.PEEK, ScriptGroupCommand.GET -> this
    }
}
