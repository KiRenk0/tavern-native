package app.tavern.nativeclient

import app.tavern.nativeclient.script.DebugAction
import app.tavern.nativeclient.script.QuickReplyDebuggerSession
import app.tavern.nativeclient.script.ScriptInjectionPosition
import app.tavern.nativeclient.script.ScriptPromptInjection
import app.tavern.nativeclient.script.ScriptPromptInjections
import app.tavern.nativeclient.script.StScriptTokenKind
import app.tavern.nativeclient.script.tokenizeStScript
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class StScriptCompleteTest {

    private fun testHost(
        stateRef: () -> TavernState,
        updateState: ((TavernState) -> TavernState) -> Unit,
        notices: MutableList<String> = mutableListOf(),
        builtInThemes: List<ChatTheme> = emptyList(),
        builtInContexts: List<TextContextTemplate> = emptyList(),
        builtInInstructs: List<InstructTemplate> = emptyList(),
    ): TavernScriptHost = TavernScriptHost(
        snapshot = stateRef,
        input = "",
        saveChat = { chat -> updateState { it.copy(chat = chat) } },
        savePrefs = { prefs -> updateState { it.copy(prefs = prefs) } },
        ask = { null },
        trigger = { _, _ -> },
        quiet = { _, _ -> "" },
        draft = {},
        saveVariables = { _, changes ->
            updateState { cur ->
                val updatedChat = cur.chat?.let { c ->
                    c.copy(options = c.options.copy(variables = c.options.variables.withVariableChanges(changes.local)))
                }
                val updatedPrefs = cur.prefs.copy(scriptVariables = cur.prefs.scriptVariables.withVariableChanges(changes.global))
                cur.copy(chat = updatedChat, prefs = updatedPrefs)
            }
        },
        setTemporaryPersona = { id -> updateState { it.copy(temporaryPersonaId = id) } },
        openTemporaryChat = { title ->
            val temp = Chat(newId(), temporaryAssistant.id, title)
            updateState { it.copy(chat = temp, cardId = temporaryAssistant.id, groupId = null) }
            title
        },
        builtInThemes = { builtInThemes },
        builtInContexts = { builtInContexts },
        builtInInstructs = { builtInInstructs },
        notice = { notices += it },
    )

    @Test
    fun promptInjectionsCommandsPersistenceFilterScanAndEphemeralWorkEndToEnd() = runBlocking {
        val card = Card.from(jsonOf("name" to "Aria", "description" to "Mage"), id = "card-1")
        val persona = PersonaProfile(id = "p1", name = "Kael")
        val chat = Chat(
            id = "chat-1",
            cardId = card.id,
            title = "Test",
            messages = listOf(
                Message("user", "Kael", "Hello"),
                Message("assistant", "Aria", "Welcome"),
            ),
        )
        var state = TavernState(cards = listOf(card), cardId = card.id, chat = chat, prefs = Preferences(personas = listOf(persona), defaultPersonaId = persona.id))
        val host = testHost({ state }, { fn -> state = fn(state) })
        val script = StScript(host)

        // Inject persistent in-chat entry with scan=true, ephemeral entry, and filtered-out entry
        val id1 = script.run("""/inject id=quest position=in_chat depth=1 role=system scan=true Secret keyword: dragonfire""")
        assertEquals("quest", id1)
        script.run("""/inject id=temp position=before role=system ephemeral=true One-shot reminder""")
        script.run("""/inject id=gated position=after filter={: /pass false :} Should be skipped""")

        val listJson = JSONArray(script.run("/listinjects"))
        assertEquals(3, listJson.length())

        // ConversationOptions round-trip preserves injections
        val meta = JSONObject().also { state.chat!!.options.applyTo(it) }
        val restoredOptions = ConversationOptions.fromMetadata(meta)
        assertEquals(3, restoredOptions.scriptInjections.size)
        assertTrue(restoredOptions.scriptInjections.first { it.id == "quest" }.scan)
        assertTrue(restoredOptions.scriptInjections.first { it.id == "temp" }.ephemeral)

        // Resolve STscript closure filters before prompt assembly
        val resolvedOptions = ScriptPromptInjections.resolveForPrompt(state.chat!!.options) { host }
        assertEquals(2, resolvedOptions.scriptInjections.size)
        assertTrue(resolvedOptions.scriptInjections.none { it.id == "gated" })

        // Verify hook into ModelService.prompt AND scan=true triggering a WorldBook keyword entry!
        val book = WorldBook(id = "wb-scan", name = "Dragons", enabled = true).withEntries(
            listOf(LoreEntry(1, "Dragon Lore", "Dragons breathe fire.", keywords = listOf("dragonfire"), position = LorePosition.AFTER)),
        )
        val prepared = ModelService.prompt(
            card = card,
            history = state.chat!!.messages,
            leadingSystemOnly = false,
            books = listOf(book),
            group = null,
            persona = persona,
            options = resolvedOptions,
        )
        val promptDump = prepared.messages.toString()
        assertTrue("In-chat injection should appear in prompt", promptDump.contains("Secret keyword: dragonfire"))
        assertTrue("Before-char injection should appear in prompt", promptDump.contains("One-shot reminder"))
        assertTrue("scan=true injection must trigger WorldBook entry", promptDump.contains("Dragons breathe fire."))
        assertFalse("Filtered-out injection must not appear", promptDump.contains("Should be skipped"))

        // Ephemeral flush removes only ephemeral=true
        val afterEphemeral = ScriptPromptInjections.withoutEphemeral(state.chat!!.options)
        assertEquals(2, afterEphemeral.scriptInjections.size)
        assertTrue(afterEphemeral.scriptInjections.none { it.id == "temp" })

        // /flushinject single id and flush all
        script.run("/flushinject quest")
        assertEquals(2, state.chat!!.options.scriptInjections.size)
        script.run("/flushinject")
        assertTrue(state.chat!!.options.scriptInjections.isEmpty())
    }

    @Test
    fun abortFuzzyAndStringMathUtilityCommandsWork() = runBlocking {
        var state = TavernState(chat = Chat("c1", "card-1", "Test"))
        val notices = mutableListOf<String>()
        val host = testHost({ state }, { fn -> state = fn(state) }, notices)
        val script = StScript(host, random = Random(1234))

        // /abort halts nested closure and skips outer commands
        val aborted = script.run(
            """
            /setvar key=step 1 |
            /if left=1 rule=eq right=1 {: /setvar key=step 2 | /abort stopped early | /setvar key=step 3 :} |
            /setvar key=step 4
            """.trimIndent(),
        )
        assertEquals("", aborted)
        assertEquals("2", state.chat!!.options.variables["step"])
        assertEquals(listOf("stopped early"), notices)

        // quiet=true suppresses notice
        notices.clear()
        script.run("/abort quiet=true silent stop")
        assertTrue(notices.isEmpty())

        // /fuzzy matching (exact, prefix, typo, mode=all, variable-backed list)
        script.run("""/setvar key=fruits ["Apple","Apricot","Banana","Blueberry"]""")
        assertEquals("Apricot", script.run("/fuzzy list=fruits aprcot"))
        assertEquals("Banana", script.run("""/fuzzy list=["Apple","Banana","Cherry"] banan"""))
        val allMatches = JSONArray(script.run("/fuzzy list=fruits mode=all threshold=0.5 ap"))
        assertTrue(allMatches.length() >= 2)
        assertEquals("", script.run("/fuzzy list=fruits threshold=0.95 zzzzz"))

        // String utilities
        assertEquals("HELLO WORLD", script.run("/upper hello world"))
        assertEquals("hello world", script.run("/lower HELLO WORLD"))
        assertEquals("clean", script.run("/trim    clean   "))
        assertEquals("5", script.run("/len hello"))
        assertEquals("3", script.run("""/len ["a","b","c"]"""))
        assertEquals("""["a","b","c"]""", script.run("/split separator=; a;b;c"))
        assertEquals("""["a","b","c"]""", script.run("""/split separator="|" a\|b\|c"""))
        assertEquals("a - b - c", script.run("""/join separator=" - " ["a","b","c"]"""))

        // Math utilities
        assertEquals("10", script.run("/add 2 3 5"))
        assertEquals("4", script.run("/sub 10 4 2"))
        assertEquals("24", script.run("/mul 2 3 4"))
        assertEquals("5", script.run("/div 20 4"))
        assertEquals("1", script.run("/mod 10 3"))
        assertEquals("8", script.run("/pow 2 3"))
        assertEquals("2", script.run("/min 9 2 5"))
        assertEquals("9", script.run("/max 9 2 5"))
        assertEquals("3", script.run("/floor 3.9"))
        assertEquals("4", script.run("/ceil 3.1"))
        assertEquals("4", script.run("/round 3.6"))
        assertEquals("7", script.run("/abs -7"))

        // /rand with bounds & rounding
        val rolled = script.run("/rand from=1 to=6 round=round").toInt()
        assertTrue("Expected 1..6, got $rolled", rolled in 1..6)
        val unitRand = script.run("/rand").toDouble()
        assertTrue("Expected 0.0..1.0, got $unitRand", unitRand in 0.0..1.0)

        // Division by zero throws clean error
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { script.run("/div 10 0") }
        }
        Unit
    }

    @Test
    fun breakpointAndQuickReplyStepDebuggerPauseStepContinueAndAbort() = runBlocking {
        var state = TavernState(chat = Chat("c1", "card-1", "Test"))
        val host = testHost({ state }, { fn -> state = fn(state) })
        val snapshots = mutableListOf<app.tavern.nativeclient.script.ScriptDebugSnapshot>()

        val source = """
            /let x 10
            /breakpoint
            /add {{var::x}} 5
            /setvar key=out {{pipe}}
        """.trimIndent()

        val debugger = QuickReplyDebuggerSession(
            label = "TestQR",
            source = source,
            initialStepMode = false,
            readChatVariables = { state.chat?.options?.variables.orEmpty() },
            onStateChanged = { snap -> if (snap != null) snapshots += snap },
        )

        val job = async { StScript(host, debugger = debugger).run(source) }
        // Wait for breakpoint pause on line 2
        while (snapshots.isEmpty()) delay(5)
        assertEquals(2, snapshots.last().currentLine)
        assertEquals("breakpoint", snapshots.last().pausedReason)
        assertEquals("10", snapshots.last().frameVariables["x"])

        // Step over to line 3 (/add)
        debugger.resume(DebugAction.STEP_OVER)
        while (snapshots.size < 2) delay(5)
        assertEquals(3, snapshots.last().currentLine)
        assertEquals("step", snapshots.last().pausedReason)

        // Continue to completion
        debugger.resume(DebugAction.CONTINUE)
        val result = job.await()
        assertEquals("15", result)
        assertEquals("15", state.chat!!.options.variables["out"])

        // Verify syntax highlighter tokenizes commands, pipes, closures, macros, named args
        val tokens = tokenizeStScript("""/if left={{var::x}} rule=eq right="10" {: /echo ok | /pass {{pipe}} :}""")
        val kinds = tokens.map(app.tavern.nativeclient.script.StScriptToken::kind).toSet()
        assertTrue(StScriptTokenKind.COMMAND in kinds)
        assertTrue(StScriptTokenKind.PIPE in kinds)
        assertTrue(StScriptTokenKind.CLOSURE in kinds)
        assertTrue(StScriptTokenKind.MACRO in kinds)
        assertTrue(StScriptTokenKind.NAMED_ARG in kinds)
        assertTrue(StScriptTokenKind.STRING in kinds)
    }

    @Test
    fun personaLockTempchatAndTagCommandsWork() = runBlocking {
        val card = Card.from(jsonOf("name" to "Rin"), id = "card-rin")
        val p1 = PersonaProfile(id = "p1", name = "Alex")
        val p2 = PersonaProfile(id = "p2", name = "Blake")
        var state = TavernState(
            cards = listOf(card),
            cardId = card.id,
            chat = Chat("chat-1", card.id, "Main"),
            prefs = Preferences(personas = listOf(p1, p2), defaultPersonaId = p1.id),
        )
        val host = testHost({ state }, { fn -> state = fn(state) })
        val script = StScript(host)

        // /persona & /persona-lock
        assertEquals("Alex", script.run("/persona"))
        assertEquals("Blake", script.run("/persona mode=temp Blake"))
        assertEquals("Blake", state.persona.name)
        assertEquals("true", script.run("/persona-lock on"))
        assertEquals("p2", state.chat!!.options.personaId)
        assertEquals("false", script.run("/persona-lock off"))
        assertEquals(null, state.chat!!.options.personaId)

        // Character persona lock
        assertEquals("true", script.run("/persona-lock type=character on"))
        assertEquals("p1", state.prefs.personaConnections[card.id])

        // /tag-add, /tag-exists, /tag-list, /tag-remove
        assertEquals("Fantasy", script.run("/tag-add Fantasy"))
        assertEquals("RPG", script.run("/tag-add RPG"))
        assertEquals("true", script.run("/tag-exists fantasy"))
        assertEquals("false", script.run("/tag-exists SciFi"))
        val tags = JSONArray(script.run("/tag-list"))
        assertEquals(2, tags.length())
        assertEquals("Fantasy, RPG", script.run("/tag-list format=csv"))
        assertEquals("Fantasy", script.run("/tag-remove Fantasy"))
        assertEquals("false", script.run("/tag-exists Fantasy"))

        // /tempchat opens temporary chat
        assertEquals("Scratchpad", script.run("/tempchat Scratchpad"))
        assertTrue(state.chat!!.isTemporary)
        assertEquals("Scratchpad", state.chat!!.title)
    }

    @Test
    fun quickReplyCrudAndQrArgCommandsWork() = runBlocking {
        var state = TavernState(chat = Chat("c1", "card-1", "Test"))
        val host = testHost({ state }, { fn -> state = fn(state) })
        val script = StScript(host)

        // /qrset-create & /qrset-list
        assertEquals("Combat", script.run("/qrset-create active=true Combat"))
        assertEquals("""["Combat"]""", script.run("/qrset-list"))

        // /qr-create & /qr-get & /qr-list
        val createdId = script.run("""/qr-create set=Combat label=Strike title="Basic hit" user=true /echo Hit!""")
        assertEquals("1", createdId)
        assertEquals("/echo Hit!", script.run("/qr-get set=Combat label=Strike field=message"))
        assertEquals("""["Strike"]""", script.run("/qr-list Combat"))

        // /qr-update
        assertEquals("PowerStrike", script.run("""/qr-update set=Combat label=Strike newlabel=PowerStrike hidden=true /echo Crit!"""))
        assertEquals("/echo Crit!", script.run("/qr-get set=Combat label=PowerStrike field=message"))
        assertEquals("true", script.run("/qr-get set=Combat label=PowerStrike field=hidden"))

        // /qr-arg inside script & closure
        val argResult = script.run("""/qr-arg weapon Longsword | /pass {{arg::weapon}}""")
        assertEquals("Longsword", argResult)
        val argRead = script.run("""/qr-arg key=buff Haste | /qr-arg buff""")
        assertEquals("Haste", argRead)

        // /qr-delete & /qrset-delete
        assertEquals("true", script.run("/qr-delete set=Combat label=PowerStrike"))
        assertEquals("[]", script.run("/qr-list Combat"))
        assertEquals("true", script.run("/qrset-delete Combat"))
        assertEquals("[]", script.run("/qrset-list"))
    }

    @Test
    fun configAndUiCommandsSwitchApiContextInstructTokenizerVnAndTheme() = runBlocking {
        val openAi = Provider(
            id = "p-oai", name = "OpenAI", endpoint = "https://api.openai.com/v1",
            apiKey = "sk-1", defaultModel = "gpt-4o", protocol = ApiProtocol.OPENAI_COMPATIBLE,
        )
        val claude = Provider(
            id = "p-claude", name = "Anthropic", endpoint = "https://api.anthropic.com",
            apiKey = "sk-2", defaultModel = "claude-sonnet-4-5", protocol = ApiProtocol.ANTHROPIC,
        )
        val chatMlContext = TextContextTemplate(name = "ChatML", story = "{{system}}")
        val chatMlInstruct = InstructTemplate(name = "ChatML")
        val midnightTheme = ChatTheme(
            id = "theme-midnight",
            name = "Midnight",
            appearance = ChatAppearance(layoutMode = ChatLayoutMode.DOCUMENT),
            mode = "dark",
            visualNovel = true,
        )
        var state = TavernState(
            chat = Chat("c1", "card-1", "Test"),
            prefs = Preferences(
                providers = listOf(openAi, claude),
                defaultSelection = openAi.defaultSelection,
            ),
        )
        val host = testHost(
            { state },
            { fn -> state = fn(state) },
            builtInThemes = listOf(midnightTheme),
            builtInContexts = listOf(chatMlContext),
            builtInInstructs = listOf(chatMlInstruct),
        )
        val script = StScript(host)

        // /api
        assertEquals("openai", script.run("/api"))
        assertEquals("anthropic", script.run("/api anthropic"))
        assertEquals("p-claude", state.chat!!.modelOverride?.providerId)

        // /context & /instruct
        assertEquals("ChatML", script.run("/context ChatML"))
        assertEquals("ChatML", script.run("/context"))
        assertEquals("ChatML", script.run("/instruct ChatML"))
        assertEquals("ChatML", script.run("/instruct"))
        assertEquals("", script.run("/context off"))
        assertEquals("", script.run("/instruct off"))

        // /tokenizer
        assertEquals("model", script.run("/tokenizer"))
        assertEquals("o200k", script.run("/tokenizer o200k_base"))
        assertEquals(TextTokenizer.O200K, state.prefs.providers.first { it.id == "p-claude" }.textTokenizer)

        // /vn
        assertEquals("true", script.run("/vn on"))
        assertTrue(state.prefs.visualNovel)
        assertEquals("false", script.run("/vn"))
        assertFalse(state.prefs.visualNovel)

        // /theme
        assertEquals("dark", script.run("/theme dark"))
        assertEquals("dark", state.prefs.theme)
        assertEquals("high-contrast", script.run("/theme high-contrast"))
        assertTrue(state.prefs.chatAppearance.highContrast)
        assertEquals("Midnight", script.run("/theme Midnight"))
        assertEquals("theme-midnight", state.prefs.chatThemes.activeId)
        assertEquals(ChatLayoutMode.DOCUMENT, state.prefs.chatAppearance.layoutMode)
    }

    @Test
    fun autocompleteRegistersAllNewCommandsAndMacros() {
        val commandLabels = stScriptCatalog.map(AutocompleteItem::label).toSet()
        val expectedCommands = listOf(
            "/reasoning-collapse", "/popup",
            "/inject", "/listinjects", "/flushinject",
            "/abort", "/fuzzy", "/breakpoint",
            "/persona", "/persona-lock", "/tempchat",
            "/tag-add", "/tag-remove", "/tag-exists", "/tag-list",
            "/qr-create", "/qr-update", "/qr-delete", "/qr-get", "/qr-list",
            "/qrset-create", "/qrset-delete", "/qrset-list", "/qr-arg",
            "/api", "/context", "/instruct", "/tokenizer", "/vn", "/theme",
            "/upper", "/lower", "/trim", "/len", "/split", "/join",
            "/floor", "/ceil", "/abs", "/min", "/max", "/round", "/rand",
            "/add", "/sub", "/mul", "/div", "/mod", "/pow",
        )
        expectedCommands.forEach { cmd ->
            assertTrue("Missing command in autocomplete: $cmd", cmd in commandLabels)
        }

        val macroLabels = macroCatalog.map(AutocompleteItem::label).toSet()
        val expectedMacros = listOf(
            "{{maxContextTokens}}",
            "{{maxResponseTokens}}",
            "{{defaultExpression}}",
            "{{lastExpression}}",
            "{{availableExpressions}}",
        )
        expectedMacros.forEach { macro ->
            assertTrue("Missing macro in autocomplete: $macro", macro in macroLabels)
        }
    }
}
