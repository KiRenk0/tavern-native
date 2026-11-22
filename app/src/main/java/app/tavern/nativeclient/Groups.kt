package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

internal enum class ReplyOrder(val label: String) {
    NATURAL("自然"), LIST("成员顺序"), MANUAL("手动"), POOLED("随机轮转"),
}

internal enum class GroupPromptMode(val label: String) {
    SWAP("仅当前发言者"), JOIN_ENABLED("合并启用成员设定"), JOIN_ALL("合并全部成员设定"),
}

internal data class GroupPrompt(
    val group: TavernGroup, val definitions: List<Card>, val roster: List<String>, val activeRoster: List<String> = roster,
)

internal data class TavernGroup(
    val id: String = newId(),
    val name: String = "",
    val memberIds: List<String> = emptyList(),
    val mutedIds: Set<String> = emptySet(),
    val order: ReplyOrder = ReplyOrder.NATURAL,
    val promptMode: GroupPromptMode = GroupPromptMode.SWAP,
    val allowSelf: Boolean = false,
    val prefix: String = "",
    val suffix: String = "",
    val autoDelaySeconds: Int = 5,
    val hideMutedSprites: Boolean = false,
    val favorite: Boolean = false,
    val avatar: GroupAvatar? = null,
) {
    fun members(cards: List<Card>): List<Card> {
        val byId = cards.associateBy(Card::id)
        return memberIds.distinct().mapNotNull(byId::get)
    }

    fun participants(cards: List<Card>) = members(cards).filterNot { it.id in mutedIds }

    fun nextTurn(cards: List<Card>, history: List<Message>, random: Random = Random.Default): List<Card> =
        if (order == ReplyOrder.MANUAL) listOfNotNull(participants(cards).randomOrNull(random))
        else speakers(cards, history, random)

    fun speakers(cards: List<Card>, history: List<Message>, random: Random = Random.Default): List<Card> {
        val enabled = participants(cards)
        if (enabled.isEmpty() || order == ReplyOrder.MANUAL) return emptyList()
        if (order == ReplyOrder.LIST) return enabled
        val last = history.lastOrNull { !it.excludedFromContext }
        fun Message.isSpeaker(card: Card) = role == "assistant" && isBy(card)
        val excludingLast = enabled.filterNot { card -> last?.isSpeaker(card) == true }.ifEmpty { enabled }

        if (order == ReplyOrder.POOLED) {
            val spoken = history.asReversed().asSequence().filterNot(Message::excludedFromContext)
                .takeWhile { it.role != "user" }.filter { it.role == "assistant" }.toList()
            val pool = enabled.filterNot { card -> spoken.any { it.isSpeaker(card) } }.ifEmpty { excludingLast }
            return listOf(pool.random(random))
        }

        val candidates = if (allowSelf) enabled else excludingLast
        val words = Regex("[\\p{L}\\p{N}_]+")
        val input = last?.text.orEmpty()
        val mentioned = candidates.mapNotNull { card ->
            val name = card.name.trim()
            if (name.isEmpty()) return@mapNotNull null
            val pattern = if (name.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN })
                Regex(Regex.escape(name), RegexOption.IGNORE_CASE)
            else Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(name)}(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
            val position = pattern.find(input)?.range?.first ?: words.findAll(name).mapNotNull { namePart ->
                Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(namePart.value)}(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
                    .find(input)?.range?.first
            }.minOrNull()
            position?.let { it to card }
        }.sortedBy { it.first }.map { it.second }
        val talkative = candidates.shuffled(random).filter { random.nextDouble() < it.talkativeness.coerceIn(0.0, 1.0) }
        return (mentioned + talkative).distinctBy(Card::id).ifEmpty {
            listOf(candidates.filter { it.talkativeness > 0 }.ifEmpty { candidates }.random(random))
        }
    }

    fun promptMembers(cards: List<Card>, speaker: Card): List<Card> = when (promptMode) {
        GroupPromptMode.SWAP -> emptyList()
        GroupPromptMode.JOIN_ENABLED -> participants(cards)
        GroupPromptMode.JOIN_ALL -> members(cards)
    }.filterNot { it.id == speaker.id }

    fun openingMessages(
        chat: Chat, cards: List<Card>, prefs: Preferences, preset: PromptPreset?, random: Random = Random.Default,
    ): Pair<List<Message>, MacroVariableChanges> {
        val members = members(cards)
        val names = members.map(Card::name)
        val active = members.filterNot { it.id in mutedIds }.map(Card::name)
        val persona = prefs.personaFor(chat)
        val variables = MacroVariables(chat.options.variables, prefs.scriptVariables)
        val model = prefs.connectionFor(chat)?.model.orEmpty()
        val messages = buildList {
            for (card in members) {
                val greeting = card.greetings.randomOrNull(random) ?: continue
                val group = GroupPrompt(this@TavernGroup, promptMembers(cards, card), names, active)
                val macros = ConversationMacros(card, persona, this, group, chat.options,
                    model = model, chatId = chat.id, variables = variables)
                val rules = prefs.regex.runtime(card, persona.name, preset, names)
                val text = rules.transform(macros.expand(greeting.trim(), write = true), RegexSite.REPLY, RegexStage.STORAGE)
                if (text.isNotBlank()) add(Message("assistant", card.name, text, characterId = card.id, groupTurnId = newId()))
            }
        }
        return messages to variables.changes()
    }

    fun json() = jsonOf("name" to name, "members" to JSONArray(memberIds), "disabled_members" to JSONArray(mutedIds),
        "activation_strategy" to order.ordinal, "generation_mode" to promptMode.ordinal, "allow_self_responses" to allowSelf,
        "generation_mode_join_prefix" to prefix, "generation_mode_join_suffix" to suffix,
        "auto_mode_delay" to autoDelaySeconds, "hideMutedSprites" to hideMutedSprites, "fav" to favorite,
        "avatar_url" to avatar?.dataUrl.orEmpty())

    companion object {
        fun from(id: String, raw: String): TavernGroup {
            val value = JSONObject(raw)
            return TavernGroup(id, value.string("name"), value.optJSONArray("members").strings(),
                value.optJSONArray("disabled_members").strings().toSet(),
                ReplyOrder.entries.getOrElse(value.optInt("activation_strategy")) { ReplyOrder.NATURAL },
                GroupPromptMode.entries.getOrElse(value.optInt("generation_mode")) { GroupPromptMode.SWAP },
                value.optBoolean("allow_self_responses"), value.string("generation_mode_join_prefix"),
                value.string("generation_mode_join_suffix"), value.optInt("auto_mode_delay", 5).coerceIn(1, 999),
                value.optBoolean("hideMutedSprites"), value.optBoolean("fav"), GroupAvatar.fromDataUrl(value.string("avatar_url")))
        }
    }
}
