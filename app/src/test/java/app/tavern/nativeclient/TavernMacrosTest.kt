package app.tavern.nativeclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TavernMacrosTest {
    private val card = Card.from(
        jsonOf(
            "name" to "Aria",
            "description" to "Silver mage from the tower",
            "personality" to "Calm and curious",
        ),
        id = "card-aria",
    )
    private val persona = PersonaProfile(id = "user-1", name = "Kael")

    @Test
    fun ifElseConditionalAndNegationWork() {
        val macros = ConversationMacros(
            card = card,
            persona = persona,
            options = ConversationOptions(variables = mapOf("flag" to "true", "zero" to "0")),
        )
        val out = macros.expand("{{if .flag}}YES{{else}}NO{{/if}}-{{if !.zero}}EMPTY{{else}}SET{{/if}}")
        assertEquals("YES-EMPTY", out)
    }

    @Test
    fun randomAndStablePickWithSeedAndDiceWork() {
        val optsA = ConversationOptions(pickRerollSeed = "seed-A")
        val optsB = ConversationOptions(pickRerollSeed = "seed-B")
        val macroA1 = ConversationMacros(card, persona, options = optsA, chatId = "c1")
        val macroA2 = ConversationMacros(card, persona, options = optsA, chatId = "c1")
        val macroB = ConversationMacros(card, persona, options = optsB, chatId = "c1")

        val template = "{{pick::alpha::beta::gamma::delta::epsilon}}"
        val p1 = macroA1.expand(template)
        val p2 = macroA2.expand(template)
        assertEquals("{{pick}} must stay deterministic for identical seed and position", p1, p2)
        assertTrue(p1 in setOf("alpha", "beta", "gamma", "delta", "epsilon"))

        val seedsDiffer = (1..12).any { idx ->
            ConversationMacros(card, persona, options = ConversationOptions(pickRerollSeed = "seed-$idx"), chatId = "c1")
                .expand(template) != p1
        }
        assertTrue("Different reroll seeds should vary {{pick}} outcomes", seedsDiffer)

        val rand = TavernMacroRenderer("seed", { _, _ -> null }, random = Random(42))
        val randomChoice = rand.expand("{{random:sword,bow,wand}}")
        assertTrue(randomChoice in setOf("sword", "bow", "wand"))

        val dice = macroDice("2d6+3", Random(42)).toInt()
        assertTrue("2d6+3 must be in 5..15, was $dice", dice in 5..15)
        assertEquals("", macroDice("invalid", Random(42)))
    }

    @Test
    fun shorthandOperatorsAndCommitVariablesWork() {
        val macros = ConversationMacros(
            card = card,
            persona = persona,
            options = ConversationOptions(variables = mapOf("score" to "10", "empty" to "")),
            globals = mapOf("visits" to "1"),
        )
        val rendered = macros.expand(
            "{{.score += 5}}|{{.score++}}|{{.score}}|{{.missing ??= default}}|{{.empty ||= fallback}}|{{\$visits++}}|{{\$visits}}|{{if .score > 12}}HIGH{{/if}}",
            write = true,
        )
        // {{setvar}} / {{+=}} / {{??=}} behavior check
        val parts = rendered.split("|")
        assertEquals("16", parts[2]) // score went 10 -> 15 -> 16
        assertEquals("default", parts[3])
        assertEquals("fallback", parts[4])
        assertEquals("2", parts[6])
        assertEquals("HIGH", parts[7])

        val changes = macros.variables.changes()
        assertEquals("16", changes.local["score"])
        assertEquals("default", changes.local["missing"])
        assertEquals("fallback", changes.local["empty"])
        assertEquals("2", changes.global["visits"])

        val mergedLocal = mapOf("score" to "10", "empty" to "").withVariableChanges(changes.local)
        assertEquals("16", mergedLocal["score"])
        assertEquals("default", mergedLocal["missing"])
    }

    @Test
    fun variableKeyListObjectOpsAndLegacyTagsWork() {
        val objJson = """{"hp":42,"item":"potion"}"""
        val vars = MacroVariables(mapOf("stats" to objJson, "items" to """["potion","key"]"""), emptyMap())
        assertEquals("42", vars.resolve("getvarkey", listOf("stats", "hp"), write = false))
        assertEquals("key", vars.resolve("getvarindex", listOf("items", "1"), write = false))

        vars.resolve("setvarkey", listOf("stats", "hp", "99"), write = true)
        vars.resolve("setvarindex", listOf("items", "1", "elixir"), write = true)
        assertEquals("99", readVariableKey(vars.get("stats", global = false), "hp"))
        assertEquals("elixir", readVariableKey(vars.get("items", global = false), "1"))

        val updatedList = writeVariableKey("""["a","b"]""", "2", "c")
        assertEquals("c", readVariableKey(updatedList, "2"))

        val macros = ConversationMacros(card, persona)
        assertEquals("Kael greets Aria!", macros.expand("<USER> greets <BOT>!"))
    }
}
