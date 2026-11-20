package app.tavern.nativeclient

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import kotlin.random.Random

class WorldEvaluationTest {
    private val card = Card.from(
        jsonOf(
            "name" to "Serena",
            "description" to "Guardian of the Sunken Archive",
        ),
        id = "card-serena",
    )
    private val persona = PersonaProfile(id = "persona-1", name = "Traveler")

    @Test
    fun primaryKeywordsCaseSensitivityWholeWordAndSecondaryLogicWork() {
        val entries = listOf(
            LoreEntry(
                id = 1,
                title = "Exact Whole Word",
                content = "Arcane tower note",
                keywords = listOf("Arc"),
                caseSensitive = true,
                wholeWords = true,
            ),
            LoreEntry(
                id = 2,
                title = "AND_ANY",
                content = "fire+ice or wind",
                keywords = listOf("element"),
                secondary = listOf("fire", "frost"),
                logic = LoreLogic.AND_ANY,
            ),
            LoreEntry(
                id = 3,
                title = "NOT_ALL",
                content = "missing one secondary",
                keywords = listOf("seal"),
                secondary = listOf("alpha", "beta"),
                logic = LoreLogic.NOT_ALL,
            ),
            LoreEntry(
                id = 4,
                title = "NOT_ANY",
                content = "pure ritual",
                keywords = listOf("ritual"),
                secondary = listOf("corrupted"),
                logic = LoreLogic.NOT_ANY,
            ),
            LoreEntry(
                id = 5,
                title = "AND_ALL",
                content = "complete trinity",
                keywords = listOf("trinity"),
                secondary = listOf("sun", "moon"),
                logic = LoreLogic.AND_ALL,
            ),
            LoreEntry(
                id = 6,
                title = "Regex Key",
                content = "ancient wyrm",
                keywords = listOf("/\\bwyrm-\\d{2}\\b/i"),
            ),
        )
        val book = WorldBook(id = "wb-1", name = "TestBook", enabled = true).withEntries(entries)
        val history = listOf(
            Message(
                "user",
                "Traveler",
                "Visited Arc (not arcane), invoked element with frost, seal alpha only, clean ritual, trinity sun moon, WYRM-07.",
            ),
        )

        val eval = evaluateLore(
            card = card,
            books = listOf(book),
            history = history,
            settings = LoreSettings(),
            persona = persona,
            options = ConversationOptions(),
            extraScan = "",
        )
        val titles = eval.entries.map(LoreEntry::title).toSet()
        assertEquals(setOf("Exact Whole Word", "AND_ANY", "NOT_ALL", "NOT_ANY", "AND_ALL", "Regex Key"), titles)

        // Verify case-sensitive and whole-word rejection
        val rejectedHistory = listOf(
            Message("user", "Traveler", "arcade arcane ritual corrupted trinity sun"),
        )
        val eval2 = evaluateLore(
            card = card,
            books = listOf(book),
            history = rejectedHistory,
            settings = LoreSettings(),
            persona = persona,
            options = ConversationOptions(),
            extraScan = "",
        )
        assertTrue(eval2.entries.isEmpty())
    }

    @Test
    fun recursionPreventExcludeAndDelayLevelsWork() {
        val entries = listOf(
            LoreEntry(
                id = 1,
                title = "Trigger",
                content = "Leads to citadel and secret",
                keywords = listOf("start"),
            ),
            LoreEntry(
                id = 2,
                title = "NormalRecursed",
                content = "Citadel facts",
                keywords = listOf("citadel"),
            ),
            LoreEntry(
                id = 3,
                title = "ExcludedFromRecursion",
                content = "Should not fire recursively",
                keywords = listOf("secret"),
                rules = LoreRules(excludeRecursion = true),
            ),
            LoreEntry(
                id = 4,
                title = "PreventFurtherRecursion",
                content = "Mentions abyss",
                keywords = listOf("citadel"),
                rules = LoreRules(preventRecursion = true, recursionLevel = 1),
            ),
            LoreEntry(
                id = 5,
                title = "AbyssBlocked",
                content = "Should not fire because parent had preventRecursion",
                keywords = listOf("abyss"),
            ),
        )
        val book = WorldBook(id = "wb-rec", name = "Rec", enabled = true).withEntries(entries)
        val history = listOf(Message("user", "Traveler", "Let us start now."))

        val eval = evaluateLore(
            card = card,
            books = listOf(book),
            history = history,
            settings = LoreSettings(recursive = true),
            persona = persona,
            options = ConversationOptions(),
            extraScan = "",
        )
        val titles = eval.entries.map(LoreEntry::title)
        assertTrue("Trigger" in titles)
        assertTrue("NormalRecursed" in titles)
        assertTrue("PreventFurtherRecursion" in titles)
        assertFalse("ExcludedFromRecursion" in titles)
        assertFalse("AbyssBlocked" in titles)
    }

    @Test
    fun inclusionGroupsStickyCooldownDelayAndOutletsWork() {
        val entries = listOf(
            LoreEntry(
                id = 1,
                title = "Heavy",
                content = "@@activate\nChosen heavy faction",
                keywords = listOf("unused"),
                rules = LoreRules(groups = "faction", groupWeight = 900, groupOverride = true),
            ),
            LoreEntry(
                id = 2,
                title = "Light",
                content = "@@activate\nLight faction",
                keywords = listOf("unused"),
                rules = LoreRules(groups = "faction", groupWeight = 1),
            ),
            LoreEntry(
                id = 3,
                title = "StickyTimed",
                content = "Buff active",
                keywords = listOf("charge"),
                position = LorePosition.OUTLET,
                rules = LoreRules(delay = 1, sticky = 2, cooldown = 2, outlet = "status_box"),
            ),
        )
        val book = WorldBook(id = "wb-rules", name = "Rules", enabled = true).withEntries(entries)

        // Turn 0 (empty history): delay = 1 prevents StickyTimed
        val turn0 = evaluateLore(card, listOf(book), emptyList(), LoreSettings(), persona, ConversationOptions(), "charge")
        assertEquals(listOf("Heavy"), turn0.entries.map(LoreEntry::title))

        // Turn 1: "charge" appears -> StickyTimed activates (stickyEnd = 1+2 = 3, cooldownEnd = 5)
        val msg1 = Message("user", "Traveler", "charge!")
        val turn1 = evaluateLore(card, listOf(book), listOf(msg1), LoreSettings(), persona, ConversationOptions(), "")
        assertTrue(turn1.entries.any { it.title == "StickyTimed" && it.rules.outlet == "status_box" })
        val reply1 = Message("assistant", "Serena", "ok", loreEffects = turn1.effects)

        // Turn 2 (count = 2 < stickyEnd 3): stays active without keyword via sticky
        val turn2 = evaluateLore(card, listOf(book), listOf(msg1, reply1), LoreSettings(), persona, ConversationOptions(), "")
        assertTrue(turn2.entries.any { it.title == "StickyTimed" })

        // Turn 3 (count = 3 >= stickyEnd 3, < cooldownEnd 5): on cooldown even if "charge" repeated
        val msg2 = Message("user", "Traveler", "charge again!")
        val turn3 = evaluateLore(card, listOf(book), listOf(msg1, reply1, msg2), LoreSettings(), persona, ConversationOptions(), "")
        assertFalse("Must stay on cooldown at count=3", turn3.entries.any { it.title == "StickyTimed" })
    }

    @Test
    fun modelContextBudgetsAndDeepSeekBpeTokenizerWork() {
        val claudeConn = ModelConnection(
            Provider(id = "p-claude", name = "Claude", template = ProviderTemplate.ANTHROPIC, protocol = ApiProtocol.ANTHROPIC),
            "claude-sonnet-4-20250514",
        )
        val geminiConn = ModelConnection(
            Provider(id = "p-gemini", name = "Gemini", template = ProviderTemplate.GOOGLE, protocol = ApiProtocol.GEMINI_NATIVE),
            "gemini-4-hydrogen",
        )
        val deepSeekConn = ModelConnection(
            Provider(id = "p-ds", name = "DeepSeek", template = ProviderTemplate.DEEPSEEK),
            "deepseek-v4.1-chat",
        )
        assertEquals(200_000, claudeConn.contextTokens())
        assertEquals(1_048_576, geminiConn.contextTokens())
        assertEquals(1_048_576, deepSeekConn.contextTokens())

        val settings = LoreSettings(budgetPercent = 25, budgetCap = 4_000)
        val budget = settings.modelBudget(claudeConn, Preferences(maxTokens = "2000"), null, estimatedTokens)
        assertEquals(4_000, budget.limit)

        val uncapped = LoreSettings(budgetPercent = 10, budgetCap = 0)
            .modelBudget(claudeConn, Preferences(maxTokens = "0"), null, estimatedTokens)
        assertEquals(20_000, uncapped.limit)

        // Verify actual bundled DeepSeek V4.1 BPE asset loads cleanly
        val bpeFile = listOf(
            File("app/src/main/assets/tokenizers/deepseek-v41.bpe"),
            File("src/main/assets/tokenizers/deepseek-v41.bpe"),
        ).firstOrNull(File::isFile) ?: error("Missing deepseek-v41.bpe asset")

        val tokenizer = FileInputStream(bpeFile).use(DeepSeekTokens::read)
        val enTokens = tokenizer.count("Hello SillyTavern Native roleplay world!")
        val zhTokens = tokenizer.count("你好，酒馆原生客户端正在进行世界书预算分词测试。")
        assertTrue("Expected positive BPE count for English ($enTokens)", enTokens in 3..30)
        assertTrue("Expected positive BPE count for Chinese ($zhTokens)", zhTokens in 5..50)
    }
}
