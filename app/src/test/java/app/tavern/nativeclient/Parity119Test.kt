package app.tavern.nativeclient

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import app.tavern.nativeclient.script.ReasoningCollapseState
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Parity119Test {

    @Test
    fun missingMacrosExpandContextResponseAndExpressions() {
        val spriteJoy = ExpressionSprite("joy", ChatImage("1".repeat(64), "joy.png", ImageFormat.PNG))
        val spriteSad = ExpressionSprite("sadness", ChatImage("2".repeat(64), "sad.png", ImageFormat.PNG))
        val card = Card.from(jsonOf("name" to "Rin"), id = "card-rin")
            .withExpressionSprites(listOf(spriteJoy, spriteSad))
        val persona = PersonaProfile(id = "p1", name = "Alex")
        val options = ConversationOptions(expressions = ExpressionSettings(mode = ExpressionMode.MANUAL, fallback = "calm"))

        val macros = ConversationMacros(
            card = card,
            persona = persona,
            options = options,
            maxContextTokens = 32768,
            maxResponseTokens = 2048,
            lastExpression = "joy",
        )

        assertEquals("32768", macros.expand("{{maxContextTokens}}"))
        assertEquals("2048", macros.expand("{{maxResponseTokens}}"))
        assertEquals("calm", macros.expand("{{defaultExpression}}"))
        assertEquals("joy", macros.expand("{{lastExpression}}"))
        assertEquals("joy, sadness", macros.expand("{{availableExpressions}}"))

        // Emoji fallback expands built-in expression list when card has no sprites.
        val emojiCard = Card.from(jsonOf("name" to "Nova"), id = "card-nova")
        val emojiMacros = ConversationMacros(
            card = emojiCard,
            persona = persona,
            options = ConversationOptions(expressions = ExpressionSettings(fallback = EXPRESSION_EMOJI)),
        )
        assertEquals("neutral", emojiMacros.expand("{{defaultExpression}}"))
        assertTrue(emojiMacros.expand("{{availableExpressions}}").contains("joy"))
        assertEquals("", emojiMacros.expand("{{lastExpression}}"))
        assertEquals(DEFAULT_CONTEXT_TOKENS.toString(), emojiMacros.expand("{{maxContextTokens}}"))
    }

    @Test
    fun storyStringPreservesNew119MacrosForRuntimeExpansion() {
        val rendered = StoryString.render(
            story = "Ctx={{maxContextTokens}} Resp={{maxResponseTokens}} Def={{defaultExpression}} Last={{lastExpression}} Avail={{availableExpressions}}",
            values = mapOf("char" to "Rin"),
            expand = { text ->
                text.replace("{{maxContextTokens}}", "16384")
                    .replace("{{maxResponseTokens}}", "1024")
                    .replace("{{defaultExpression}}", "neutral")
                    .replace("{{lastExpression}}", "joy")
                    .replace("{{availableExpressions}}", "joy, neutral")
            },
        )
        assertEquals("Ctx=16384 Resp=1024 Def=neutral Last=joy Avail=joy, neutral", rendered)
    }

    @Test
    fun reasoningCollapseCommandTogglesGlobalAndPerMessageState() = runBlocking {
        val chat = Chat(
            id = "chat-1",
            cardId = "card-1",
            title = "Test",
            messages = listOf(
                Message("user", "Alex", "Hi"),
                Message("assistant", "Rin", "Hello", reasoning = "Thinking step 1"),
                Message("assistant", "Rin", "More", reasoning = "Thinking step 2"),
            ),
        )
        var state = TavernState(
            chat = chat,
            prefs = Preferences(replyFormatting = ReplyFormatting(autoExpand = true)),
        )
        val host = TavernScriptHost(
            snapshot = { state },
            input = "",
            saveChat = { state = state.copy(chat = it) },
            savePrefs = { state = state.copy(prefs = it) },
            ask = { null },
            trigger = { _, _ -> },
            quiet = { _, _ -> "" },
            draft = {},
            saveVariables = { _, _ -> },
            updateReasoningCollapse = { state = state.copy(reasoningCollapse = it) },
            notice = {},
        )
        val engine = StScript(host)

        // Default /reasoning-collapse collapses all reasoning blocks.
        assertEquals("true", engine.run("/reasoning-collapse"))
        assertEquals(false, state.reasoningCollapse.expandedFor("chat-1", 1, true))
        assertEquals(false, state.reasoningCollapse.expandedFor("chat-1", 2, true))

        // Expand only the last message via mesId=-1.
        assertEquals("false", engine.run("/reasoning-collapse mesId=-1 state=false"))
        assertEquals(false, state.reasoningCollapse.expandedFor("chat-1", 1, true))
        assertEquals(true, state.reasoningCollapse.expandedFor("chat-1", 2, true))

        // Toggle last message back to collapsed.
        assertEquals("true", engine.run("/reasoning-collapse mesId=-1 state=toggle"))
        assertEquals(false, state.reasoningCollapse.expandedFor("chat-1", 2, true))

        // /reasoning-expand expands all.
        assertEquals("false", engine.run("/reasoning-expand"))
        assertEquals(true, state.reasoningCollapse.expandedFor("chat-1", 1, true))
        assertEquals(true, state.reasoningCollapse.expandedFor("chat-1", 2, true))
        assertTrue(state.reasoningCollapse.revision >= 4L)
    }

    @Test
    fun inputPopupAndButtonsSupportPlaceholderTooltipAndIcon() = runBlocking {
        val captured = mutableListOf<ScriptDialog>()
        val host = TavernScriptHost(
            snapshot = { TavernState() },
            input = "",
            saveChat = {},
            savePrefs = {},
            ask = { dialog ->
                captured += dialog
                when (dialog.kind) {
                    "input" -> "typed"
                    "buttons" -> dialog.buttonItems.firstOrNull()?.label
                    else -> dialog.text
                }
            },
            trigger = { _, _ -> },
            quiet = { _, _ -> "" },
            draft = {},
            saveVariables = { _, _ -> },
            notice = {},
        )
        val engine = StScript(host)

        val inputRes = engine.run("""/input title="Name" placeholder="Enter hero name" tooltip="Shown in chat" icon="edit" default="Aria" Who are you?""")
        assertEquals("typed", inputRes)
        val d1 = captured.last()
        assertEquals("Enter hero name", d1.placeholder)
        assertEquals("Shown in chat", d1.tooltip)
        assertEquals("edit", d1.icon)
        assertEquals("Aria", d1.initial)
        assertNotNull(scriptDialogIcon(d1.icon))

        val popupRes = engine.run("""/popup title="Alert" placeholder="No content" tooltip="Read carefully" icon="warning" Watch out!""")
        assertEquals("Watch out!", popupRes)
        val d2 = captured.last()
        assertEquals("No content", d2.placeholder)
        assertEquals("Read carefully", d2.tooltip)
        assertEquals("warning", d2.icon)

        val btnRes = engine.run("""/buttons title="Choose" tooltip="Pick one" icon="star" labels="[{\"label\":\"Fight\",\"icon\":\"bolt\",\"tooltip\":\"Attack enemy\"},\"Flee\"]" Ready?""")
        assertEquals("Fight", btnRes)
        val d3 = captured.last()
        assertEquals("Pick one", d3.tooltip)
        assertEquals("star", d3.icon)
        assertEquals(2, d3.buttonItems.size)
        assertEquals("Fight", d3.buttonItems[0].label)
        assertEquals("bolt", d3.buttonItems[0].icon)
        assertEquals("Attack enemy", d3.buttonItems[0].tooltip)
        assertEquals("Flee", d3.buttonItems[1].label)
    }

    @Test
    fun backgroundLibraryVirtualFoldersSupportNavigationMoveRenameDeleteAndRoundTrip() {
        val imgA = ChatImage("a".repeat(64), "harbor.jpg", ImageFormat.JPEG)
        val imgB = ChatImage("b".repeat(64), "castle.jpg", ImageFormat.JPEG)
        val imgC = ChatImage("c".repeat(64), "forest.jpg", ImageFormat.JPEG)

        var lib = BackgroundLibrary()
            .createFolder("Scenery/Night")
            .createFolder("Interior")
            .add(listOf(
                BackgroundEntry(imgA, 1, "Scenery/Night"),
                BackgroundEntry(imgB, 2, "Interior"),
                BackgroundEntry(imgC, 3, ""),
            ))

        // Normalization & breadcrumbs
        assertEquals("Scenery/Night", normalizeBackgroundFolder(" /Scenery\\..\\Night/ "))
        assertEquals(listOf("" to "根目录", "Scenery" to "Scenery", "Scenery/Night" to "Night"), lib.breadcrumbs("Scenery/Night"))
        assertEquals(listOf("Interior", "Scenery"), lib.childFolders(""))
        assertEquals(listOf("Scenery/Night"), lib.childFolders("Scenery"))
        assertEquals(1, lib.folderItemCount("Scenery"))
        assertEquals(1, lib.itemsInFolder("Scenery/Night").size)
        assertEquals(1, lib.itemsInFolder("").size)

        // Find by full virtual path
        assertEquals(imgA, lib.find("Scenery/Night/harbor.jpg")?.image)

        // Move item to new folder
        lib = lib.moveItem(imgC.id, "Scenery/Day")
        assertEquals("Scenery/Day", lib.items.first { it.image.id == imgC.id }.folder)
        assertEquals(2, lib.folderItemCount("Scenery"))

        // Rename folder cascades to subfolders & items
        lib = lib.renameFolder("Scenery", "Outdoor")
        assertTrue("Outdoor/Night" in lib.allFolders())
        assertTrue("Outdoor/Day" in lib.allFolders())
        assertEquals("Outdoor/Night", lib.items.first { it.image.id == imgA.id }.folder)

        // JSON persistence round-trip
        assertEquals(lib, BackgroundLibrary.from(lib.json()))

        // Delete folder moves contained images to parent (root), never deleting images
        lib = lib.deleteFolder("Outdoor")
        assertFalse("Outdoor" in lib.allFolders())
        assertEquals("", lib.items.first { it.image.id == imgA.id }.folder)
        assertEquals("", lib.items.first { it.image.id == imgC.id }.folder)
        assertEquals(3, lib.items.size)
    }

    @Test
    fun accessibilityHighContrastAndReducedMotionSerializeAndMeetWcwagAaa() {
        val appearance = ChatAppearance(reducedMotion = true, highContrast = true)
        assertEquals(appearance, ChatAppearance.from(appearance.json()))

        val originalJson = JSONObject().also(appearance::writeOriginal)
        assertTrue(originalJson.getBoolean("reduced_motion"))
        assertTrue(originalJson.getBoolean("high_contrast"))
        val restoredOriginal = ChatAppearance.fromOriginal(originalJson)
        assertTrue(restoredOriginal.reducedMotion)
        assertTrue(restoredOriginal.highContrast)

        val darkHc = darkColorScheme().withThemePalette(ThemePalette(), highContrast = true, dark = true)
        assertTrue("Dark high-contrast surface/onSurface must be >= 7:1", contrastRatio(darkHc.onSurface, darkHc.surface) >= 7.0)
        assertTrue("Dark high-contrast primary/onPrimary must be >= 7:1", contrastRatio(darkHc.onPrimary, darkHc.primary) >= 7.0)
        assertTrue("Dark high-contrast surfaceVariant/onSurfaceVariant must be >= 7:1", contrastRatio(darkHc.onSurfaceVariant, darkHc.surfaceVariant) >= 7.0)

        val lightHc = lightColorScheme().withThemePalette(ThemePalette(), highContrast = true, dark = false)
        assertTrue("Light high-contrast surface/onSurface must be >= 7:1", contrastRatio(lightHc.onSurface, lightHc.surface) >= 7.0)
        assertTrue("Light high-contrast primary/onPrimary must be >= 7:1", contrastRatio(lightHc.onPrimary, lightHc.primary) >= 7.0)
        assertTrue("Light high-contrast surfaceVariant/onSurfaceVariant must be >= 7:1", contrastRatio(lightHc.onSurfaceVariant, lightHc.surfaceVariant) >= 7.0)
    }

    @Test
    fun worldBookRenameAutoRelinksCharactersGroupsPersonasAndChats() {
        val book = WorldBook(id = "wb-1", name = "Old Lore", enabled = false)
        val cardPrimary = Card.from(jsonOf("name" to "Hero"), id = "c1").withWorld("Old Lore")
        val cardExtra = Card.from(jsonOf("name" to "Mage"), id = "c2").withAdditionalWorldBooks(listOf("Old Lore", "wb-other"))
        val cardUnrelated = Card.from(jsonOf("name" to "NPC"), id = "c3").withWorld("Other Lore")

        val groupLinked = TavernGroup(id = "g1", name = "Party", worldBookName = "Old Lore")
        val groupOther = TavernGroup(id = "g2", name = "Guild", worldBookName = "Other Lore")

        val personaByName = PersonaProfile(id = "p1", name = "Traveler", worldId = "Old Lore")
        val personaById = PersonaProfile(id = "p2", name = "Knight", worldId = "wb-1")

        val chatById = Chat(
            id = "ch1",
            cardId = "c1",
            title = "Adventure",
            header = jsonOf("chat_metadata" to jsonOf("tavern_native_world_book" to "wb-1", "world_info" to "Old Lore")).toString(),
            options = ConversationOptions(worldBookId = "wb-1", worldBookName = "Old Lore"),
        )
        val chatLegacyName = Chat(
            id = "ch2",
            cardId = null,
            groupId = "g1",
            title = "Group Chat",
            header = jsonOf("chat_metadata" to jsonOf("world_info" to "Old Lore")).toString(),
            options = ConversationOptions(worldBookId = "", worldBookName = "Old Lore"),
        )
        val chatUnrelated = Chat(
            id = "ch3",
            cardId = "c3",
            title = "Other",
            options = ConversationOptions(worldBookId = "wb-other", worldBookName = "Other Lore"),
        )

        val result = relinkWorldBookRename(
            book = book,
            oldName = "Old Lore",
            newName = "New Lore",
            cards = listOf(cardPrimary, cardExtra, cardUnrelated),
            groups = listOf(groupLinked, groupOther),
            personas = listOf(personaByName, personaById),
            chats = listOf(chatById, chatLegacyName, chatUnrelated),
        )

        assertEquals("New Lore", result.book.name)
        assertEquals("New Lore", JSONObject(result.book.raw).getString("name"))
        assertEquals(2, result.relinkedCards)
        assertEquals("New Lore", result.cards[0].worldName)
        assertEquals(listOf("wb-1", "wb-other"), result.cards[1].additionalWorldBookIds)
        assertEquals("Other Lore", result.cards[2].worldName)

        assertEquals(1, result.relinkedGroups)
        assertEquals("New Lore", result.groups[0].worldBookName)
        assertEquals("Other Lore", result.groups[1].worldBookName)

        assertEquals(1, result.relinkedPersonas)
        assertEquals("wb-1", result.personas[0].worldId)
        assertEquals("wb-1", result.personas[1].worldId)

        assertEquals(2, result.relinkedChats)
        assertEquals("wb-1", result.chats[0].options.worldBookId)
        assertEquals("New Lore", result.chats[0].options.worldBookName)
        assertEquals("New Lore", JSONObject(result.chats[0].header!!).getJSONObject("chat_metadata").getString("world_info"))
        assertEquals("wb-1", result.chats[1].options.worldBookId)
        assertEquals("New Lore", result.chats[1].options.worldBookName)
        assertEquals("Other Lore", result.chats[2].options.worldBookName)
        assertEquals(6, result.totalRelinked)
    }
}
