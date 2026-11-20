package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

internal enum class LoreSource(val field: String, val cardField: String, val label: String) {
    PERSONA("matchPersonaDescription", "match_persona_description", "人设描述"),
    CHARACTER("matchCharacterDescription", "match_character_description", "角色描述"),
    PERSONALITY("matchCharacterPersonality", "match_character_personality", "角色性格"),
    DEPTH_PROMPT("matchCharacterDepthPrompt", "match_character_depth_prompt", "角色深度提示"),
    SCENARIO("matchScenario", "match_scenario", "场景"),
    CREATOR("matchCreatorNotes", "match_creator_notes", "作者备注"),
}

internal data class LoreRules(
    val groups: String = "",
    val groupOverride: Boolean = false,
    val groupWeight: Int = 100,
    val groupScoring: Boolean? = null,
    val excludeRecursion: Boolean = false,
    val preventRecursion: Boolean = false,
    val recursionLevel: Int = 0,
    val sticky: Int = 0,
    val cooldown: Int = 0,
    val delay: Int = 0,
    val sources: Set<LoreSource> = emptySet(),
    val triggers: List<String> = emptyList(),
    val outlet: String = "",
    val ignoreBudget: Boolean = false,
) {
    val groupNames get() = groups.split(',').map(String::trim).filter(String::isNotBlank).distinct()

    fun write(json: JSONObject) {
        json.put("group", groups)
        json.put("groupOverride", groupOverride)
        json.put("groupWeight", groupWeight)
        json.put("useGroupScoring", groupScoring ?: JSONObject.NULL)
        json.put("excludeRecursion", excludeRecursion)
        json.put("preventRecursion", preventRecursion)
        json.put("delayUntilRecursion", recursionLevel)
        json.put("sticky", sticky)
        json.put("cooldown", cooldown)
        json.put("delay", delay)
        json.put("triggers", JSONArray(triggers))
        json.put("outletName", outlet)
        json.put("ignoreBudget", ignoreBudget)
        LoreSource.entries.forEach { json.put(it.field, it in sources) }
    }

    companion object {
        fun from(json: JSONObject, embedded: Boolean): LoreRules {
            fun field(world: String, card: String) = if (embedded) card else world
            val scoring = field("useGroupScoring", "use_group_scoring")
            val recursion = field("delayUntilRecursion", "delay_until_recursion")
            return LoreRules(
                groups = json.string("group"),
                groupOverride = json.optBoolean(field("groupOverride", "group_override")),
                groupWeight = json.optInt(field("groupWeight", "group_weight"), 100).coerceIn(0, 10_000),
                groupScoring = json.takeUnless { it.isNull(scoring) }?.optBoolean(scoring),
                excludeRecursion = json.optBoolean(field("excludeRecursion", "exclude_recursion")),
                preventRecursion = json.optBoolean(field("preventRecursion", "prevent_recursion")),
                recursionLevel = json.optInt(recursion, if (json.optBoolean(recursion)) 1 else 0).coerceAtLeast(0),
                sticky = json.optInt("sticky").coerceIn(0, 10_000),
                cooldown = json.optInt("cooldown").coerceIn(0, 10_000),
                delay = json.optInt("delay").coerceIn(0, 10_000),
                sources = LoreSource.entries.filter { json.optBoolean(if (embedded) it.cardField else it.field) }.toSet(),
                triggers = json.optJSONArray("triggers").strings(),
                outlet = json.string(field("outletName", "outlet_name")),
                ignoreBudget = json.optBoolean(field("ignoreBudget", "ignore_budget")),
            )
        }
    }
}

internal data class LoreSettings(
    val recursive: Boolean = false,
    val maxPasses: Int = 0,
    val minimum: Int = 0,
    val minimumDepthMax: Int = 0,
    val caseSensitive: Boolean = false,
    val wholeWords: Boolean = false,
    val groupScoring: Boolean = false,
    val budgetPercent: Int = 25,
    val budgetCap: Int = 0,
    val overflowAlert: Boolean = false,
) {
    fun json() = jsonOf(
        "world_info_recursive" to recursive, "world_info_max_recursion_steps" to maxPasses,
        "world_info_min_activations" to minimum, "world_info_min_activations_depth_max" to minimumDepthMax,
        "world_info_case_sensitive" to caseSensitive, "world_info_match_whole_words" to wholeWords,
        "world_info_use_group_scoring" to groupScoring,
        "world_info_budget" to budgetPercent, "world_info_budget_cap" to budgetCap,
        "world_info_overflow_alert" to overflowAlert,
    )

    companion object {
        fun from(json: JSONObject?) = if (json == null) LoreSettings() else LoreSettings(
            recursive = json.optBoolean("world_info_recursive"),
            maxPasses = json.optInt("world_info_max_recursion_steps").coerceAtLeast(0),
            minimum = json.optInt("world_info_min_activations").coerceAtLeast(0),
            minimumDepthMax = json.optInt("world_info_min_activations_depth_max").coerceIn(0, 80),
            caseSensitive = json.optBoolean("world_info_case_sensitive"),
            wholeWords = json.optBoolean("world_info_match_whole_words"),
            groupScoring = json.optBoolean("world_info_use_group_scoring"),
            budgetPercent = json.optInt("world_info_budget", 25).takeIf { it in 0..100 } ?: 25,
            budgetCap = json.optInt("world_info_budget_cap").coerceIn(0, MAX_CONTEXT_TOKENS),
            overflowAlert = json.optBoolean("world_info_overflow_alert"),
        )
    }
}

internal data class LoreEffect(val signature: Int, val start: Int, val stickyEnd: Int, val cooldownEnd: Int) {
    fun json() = jsonOf("signature" to signature, "start" to start, "stickyEnd" to stickyEnd, "cooldownEnd" to cooldownEnd)

    companion object {
        fun from(json: JSONObject) = LoreEffect(json.optInt("signature"), json.optInt("start"),
            json.optInt("stickyEnd"), json.optInt("cooldownEnd"))
    }
}

internal data class LoreEvaluation(val entries: List<LoreEntry>, val effects: Map<String, LoreEffect>, val budget: LoreBudgetUsage)

internal data class LoreCandidate(val key: String, val entry: LoreEntry, val bookDepth: Int) {
    val rules get() = entry.rules
    val groups = rules.groupNames
    val signature = listOf(entry.content, entry.keywords, entry.secondary, rules.sticky, rules.cooldown).hashCode()
}

private enum class LorePass { INITIAL, RECURSION, EXPANSION }

internal fun loreCandidates(card: Card, books: List<WorldBook>, persona: PersonaProfile, options: ConversationOptions): List<LoreCandidate> {
    val embedded = card.book?.let { value ->
        val depth = value.optInt("scan_depth", 4).coerceIn(0, 80)
        value.optJSONArray("entries")?.objects().orEmpty().mapIndexed { index, item ->
            val entry = LoreEntry.from(item.optInt("id", index), item, embedded = true)
            LoreCandidate("card:${card.id}:${entry.id}", entry, depth)
        }
    }.orEmpty()
    val chatBook = options.worldBook(books)
    val global = books.filter { it.enabled || it == chatBook || it.name == card.worldName || it.id == persona.worldId }.flatMap { book ->
        val depth = book.scanDepth
        book.entries.map { LoreCandidate("book:${book.id}:${it.id}", it, depth) }
    }
    return (embedded + global).distinctBy(LoreCandidate::key)
        .filter { !it.entry.disabled && it.entry.content.isNotBlank() }
        .sortedByDescending { it.entry.priority }
}

internal fun evaluateLore(
    card: Card, books: List<WorldBook>, history: List<Message>, settings: LoreSettings,
    persona: PersonaProfile, options: ConversationOptions, extraScan: String,
    trigger: String = "normal", random: Random = Random.Default, semanticMatches: Set<String> = emptySet(),
    budget: LoreBudget = LoreBudget(3_000, estimatedTokens),
    render: (String) -> String = { expand(it, card, persona.name) },
    renderEntry: (LoreEntry, String) -> LoreExpansion = { _, text -> LoreExpansion(render(text)) },
): LoreEvaluation {
    val candidates = loreCandidates(card, books, persona, options)
    val count = history.size
    val effects = history.asReversed().firstNotNullOfOrNull(Message::loreEffects).orEmpty()
        .filterValues { count > it.start && count < it.cooldownEnd }.toMutableMap()
    fun effect(item: LoreCandidate) = effects[item.key]?.takeIf { it.signature == item.signature }
    fun sticky(item: LoreCandidate) = effect(item)?.let { count < it.stickyEnd } == true
    val recursionEnabled = settings.recursive || card.book?.let { value ->
        value.optBoolean("recursive_scanning") && !value.optJSONArray("entries")?.objects().isNullOrEmpty()
    } == true
    val recursionLevels = ArrayDeque(candidates.map { it.rules.recursionLevel }.filter { it > 0 }.distinct().sorted())
    var recursionLevel = recursionLevels.removeFirstOrNull() ?: 0
    val activated = linkedMapOf<LoreCandidate, LoreEntry>()
    val lostProbability = mutableSetOf<LoreCandidate>()
    val scanMessages = mutableMapOf<Int, String>()
    val recentMessages by lazy { history.takeLast(80).map { render(it.text) } }
    var cachedCharacters = 0
    val sourceText = mapOf(
        LoreSource.PERSONA to persona.description, LoreSource.CHARACTER to card.description,
        LoreSource.PERSONALITY to card.personality, LoreSource.DEPTH_PROMPT to card.depthPrompt,
        LoreSource.SCENARIO to options.scenario.ifBlank { card.scenario }, LoreSource.CREATOR to card.creatorNotes,
    )
    var expandedDepth = 0
    var recurseText = ""
    var spent = 0
    var budgetExhausted = false
    var pass = LorePass.INITIAL
    var passes = 0
    val expandLimit = minOf(history.size, settings.minimumDepthMax.takeIf { it > 0 } ?: 80)
    val shallowest = candidates.filter { it.entry.scanDepth == null }.minOfOrNull(LoreCandidate::bookDepth) ?: expandLimit
    fun scan(item: LoreCandidate): String {
        val depth = item.entry.scanDepth ?: (item.bookDepth + expandedDepth).coerceAtMost(80)
        if (depth <= 0) return ""
        val messages = scanMessages[depth] ?: recentMessages.takeLast(depth).joinToString("\n").also {
            if (it.length <= 256_000 - cachedCharacters) {
                scanMessages[depth] = it
                cachedCharacters += it.length
            }
        }
        val sources = item.rules.sources.mapNotNull(sourceText::get).map(render)
        return (listOf(messages, extraScan) + sources +
            recurseText.takeIf { pass != LorePass.EXPANSION }.orEmpty()).filter(String::isNotBlank).joinToString("\n")
    }

    while (settings.maxPasses == 0 || passes < settings.maxPasses) {
        passes++
        val matches = candidates.mapNotNull { item ->
            val entry = item.entry
            val rules = item.rules
            val pinned = sticky(item)
            if (item in activated || item in lostProbability || count < rules.delay ||
                (effect(item) != null && !pinned) || (rules.triggers.isNotEmpty() && trigger !in rules.triggers)
            ) return@mapNotNull null
            if (!pinned && ((rules.recursionLevel > 0 && (pass != LorePass.RECURSION || rules.recursionLevel > recursionLevel)) ||
                (pass == LorePass.RECURSION && recursionEnabled && rules.excludeRecursion))) return@mapNotNull null
            val (content, directive) = loreContent(entry.content)
            if (directive == "@@dont_activate" || content.isBlank()) return@mapNotNull null
            val forced = pinned || entry.constant || directive == "@@activate"
            val semantic = item.key in semanticMatches
            val scored = rules.groupScoring ?: settings.groupScoring
            val keywords = if ((forced || semantic) && !scored) null else matchingKeys(entry, settings, scan(item), render)
            if (!forced && !semantic && keywords?.first != true) return@mapNotNull null
            LoreMatch(item, pinned, scored, keywords?.second ?: 0, content)
        }.sortedWith(compareByDescending(LoreMatch::sticky).thenByDescending { it.candidate.entry.constant })
        val groupsUsed = activated.keys.flatMap(LoreCandidate::groups).toSet()
        val newText = mutableListOf<String>()
        for (match in chooseGroups(matches, groupsUsed, random)) {
            val item = match.candidate
            val entry = item.entry
            val rules = item.rules
            if (!rules.ignoreBudget && budgetExhausted) continue
            if (!match.sticky && entry.probability < 100 && random.nextInt(100) >= entry.probability) {
                lostProbability += item
                continue
            }
            val rendered = renderEntry(entry, match.content)
            if (!rules.ignoreBudget) {
                val cost = budget.counter.count(rendered.text).coerceAtLeast(1)
                if (cost > budget.limit - spent) {
                    budgetExhausted = true
                    continue
                }
                spent += cost
            }
            rendered.accept()
            activated[item] = entry.copy(content = rendered.text)
            if (!rules.preventRecursion) newText += rendered.text
            if (effect(item) == null && (rules.sticky > 0 || rules.cooldown > 0)) {
                val stickyEnd = count + rules.sticky
                effects[item.key] = LoreEffect(item.signature, count, stickyEnd, stickyEnd + rules.cooldown)
            }
        }
        if (newText.isNotEmpty()) recurseText = (listOf(recurseText) + newText).filter(String::isNotBlank).joinToString("\n")
        pass = when {
            budgetExhausted -> break
            recursionEnabled && (newText.isNotEmpty() || (pass == LorePass.EXPANSION && recurseText.isNotEmpty())) ->
                LorePass.RECURSION
            settings.maxPasses == 0 && activated.size < settings.minimum && shallowest + expandedDepth < expandLimit -> {
                expandedDepth++
                LorePass.EXPANSION
            }
            recursionLevels.isNotEmpty() -> {
                recursionLevel = recursionLevels.removeFirst()
                LorePass.RECURSION
            }
            else -> break
        }
    }
    return LoreEvaluation(activated.values.sortedByDescending(LoreEntry::priority), effects.toMap(),
        LoreBudgetUsage(budget.limit, spent, budgetExhausted, budget.counter.label))
}

private data class LoreMatch(
    val candidate: LoreCandidate, val sticky: Boolean, val scored: Boolean, val score: Int, val content: String,
)

internal data class LoreExpansion(val text: String, val accept: () -> Unit = {})

private fun chooseGroups(matches: List<LoreMatch>, used: Set<String>, random: Random): List<LoreMatch> {
    val available = matches.filter { it.sticky || it.candidate.groups.none(used::contains) }.toMutableSet()
    val groups = available.flatMap { match -> match.candidate.groups.map { it to match } }.groupBy({ it.first }, { it.second })
    for (members in groups.values) {
        val active = members.filter(available::contains)
        if (active.size < 2) continue
        val fixed = active.filter(LoreMatch::sticky)
        if (fixed.isNotEmpty()) {
            available.removeAll(active.filterNot(LoreMatch::sticky).toSet())
            continue
        }
        val highScore = active.maxOf(LoreMatch::score)
        val eligible = active.filter { !it.scored || it.score == highScore }
        val winner = eligible.firstOrNull { it.candidate.rules.groupOverride } ?: run {
            var roll = random.nextLong(eligible.sumOf { it.candidate.rules.groupWeight.toLong() }.coerceAtLeast(1))
            eligible.firstOrNull { (roll - it.candidate.rules.groupWeight).also { next -> roll = next } < 0 } ?: eligible.first()
        }
        available.removeAll(active.filter { it != winner }.toSet())
    }
    return matches.filter(available::contains)
}

internal fun loreContent(content: String): Pair<String, String?> {
    val first = content.lineSequence().firstOrNull()?.trim()
    return if (first == "@@activate" || first == "@@dont_activate") content.substringAfter('\n', "") to first
    else content to null
}

private fun matchingKeys(entry: LoreEntry, settings: LoreSettings, text: String, render: (String) -> String): Pair<Boolean, Int> {
    fun matches(raw: String): Boolean {
        val key = render(raw).trim()
        if (key.isBlank()) return false
        regexKeywordMatches(key, text)?.let { return it }
        val insensitive = !(entry.caseSensitive ?: settings.caseSensitive)
        if (!(entry.wholeWords ?: settings.wholeWords)) return text.contains(key, insensitive)
        val edges = "(?<![\\p{L}\\p{N}_])${Regex.escape(key)}(?![\\p{L}\\p{N}_])"
        return Regex(edges, if (insensitive) setOf(RegexOption.IGNORE_CASE) else emptySet()).containsMatchIn(text)
    }
    val primary = entry.keywords.count(::matches)
    val secondary = entry.secondary.count(::matches)
    val valid = entry.secondary.isEmpty() || when (entry.logic) {
        LoreLogic.AND_ANY -> secondary > 0
        LoreLogic.NOT_ALL -> secondary < entry.secondary.size
        LoreLogic.NOT_ANY -> secondary == 0
        LoreLogic.AND_ALL -> secondary == entry.secondary.size
    }
    return (primary > 0 && valid) to (primary + secondary)
}
