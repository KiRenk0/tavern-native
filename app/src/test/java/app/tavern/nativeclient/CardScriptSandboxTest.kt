package app.tavern.nativeclient

import app.tavern.nativeclient.script.CardCssScoper
import app.tavern.nativeclient.script.CardSandboxHtml
import app.tavern.nativeclient.script.CardSandboxPolicy
import app.tavern.nativeclient.script.InMemoryTavernHelperHost
import app.tavern.nativeclient.script.TavernHelperBridge
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardScriptSandboxTest {

    @Test
    fun `TavernHelperBridge supports full variable message worldbook generation and event APIs`() {
        val bus = TavernEventBus()
        val host = InMemoryTavernHelperHost(
            initialChatVariables = JSONObject("""{"hp":100,"hero":{"name":"Kai"}}"""),
            initialMessages = listOf(
                Message("user", "Player", "Hello"),
                Message("assistant", "Rin", "Welcome"),
            ),
            initialBooks = listOf(
                WorldBook(name = "MainLore").withEntries(
                    listOf(LoreEntry(id = 0, title = "Town", content = "Peaceful town", keywords = listOf("town"))),
                ),
            ),
            eventBus = bus,
        )
        val fullPolicy = CardSandboxPolicy(
            allowScripts = true,
            allowVariableWrite = true,
            allowMessageWrite = true,
            allowWorldbookWrite = true,
            allowGeneration = true,
        )
        val dispatched = mutableListOf<Triple<String, String, String>>()
        val bridge = TavernHelperBridge(
            host = host,
            policy = fullPolicy,
            dispatchEventToJs = { lid, ev, payload -> dispatched += Triple(lid, ev, payload) },
            postToMain = { it() },
        )

        // Variable CRUD
        assertEquals("100", bridge.getVariable("hp"))
        bridge.insertOrAssignVariables("""{"hero":{"rank":"S"},"mp":50}""", null)
        assertEquals("S", bridge.getVariable("hero.rank"))
        assertEquals("50", bridge.getVariable("mp"))
        bridge.deleteVariable("mp", null)
        assertEquals("", bridge.getVariable("mp"))
        bridge.replaceVariables("""{"reset":true}""", """{"type":"global"}""")
        assertEquals(true, JSONObject(bridge.getVariables("""{"type":"global"}""")).getBoolean("reset"))

        // Message CRUD
        val lastMsg = JSONArray(bridge.getChatMessages("-1", null))
        assertEquals(1, lastMsg.length())
        assertEquals("Welcome", lastMsg.getJSONObject(0).getString("message"))
        bridge.setChatMessages("""[{"message_id":1,"message":"Updated greeting"}]""", null)
        assertEquals("Updated greeting", host.messages[1].text)
        bridge.createChatMessages("""[{"role":"user","name":"Player","message":"Next turn"}]""", null)
        assertEquals(3, host.messages.size)
        bridge.deleteChatMessages("[0]", null)
        assertEquals(2, host.messages.size)

        // WorldBook APIs
        assertEquals("MainLore", JSONArray(bridge.getWorldbookNames()).getString(0))
        val createRes = JSONObject(
            bridge.createWorldbookEntries(
                "MainLore",
                """[{"comment":"Dragon","content":"Red dragon","key":["dragon"]}]""",
            ),
        )
        assertTrue(createRes.getBoolean("ok"))
        val newUid = createRes.getJSONArray("uids").getInt(0)
        assertEquals(2, JSONArray(bridge.getWorldbook("MainLore")).length())

        val updateRes = JSONObject(
            bridge.updateWorldbookWith("MainLore", """[{"uid":$newUid,"content":"Ancient red dragon"}]"""),
        )
        assertEquals(1, updateRes.getInt("updated"))
        assertTrue(bridge.getWorldbook("MainLore").contains("Ancient red dragon"))

        val deleteRes = JSONObject(bridge.deleteWorldbookEntries("MainLore", "[$newUid]"))
        assertEquals(1, deleteRes.getInt("deleted"))
        assertEquals(1, JSONArray(bridge.getWorldbook("MainLore")).length())

        // Generation triggers
        val genRes = JSONObject(bridge.generate("""{"user_input":"Go north"}"""))
        assertTrue(genRes.getBoolean("ok"))
        assertFalse(genRes.getBoolean("raw"))
        val rawRes = JSONObject(bridge.generateRaw("""{"user_input":"Raw prompt"}"""))
        assertTrue(rawRes.getBoolean("ok"))
        assertTrue(rawRes.getBoolean("raw"))

        // Event subscriptions + dispose cleanup
        val onId = bridge.eventOn("custom_ping", "l_on")
        val onceId = bridge.eventOnce("custom_ping", "l_once")
        bridge.eventEmit("custom_ping", """{"v":1}""")
        bridge.eventEmit("custom_ping", """{"v":2}""")
        assertEquals(3, dispatched.size) // l_on twice + l_once once
        assertTrue(bridge.eventRemoveListener("custom_ping", onId))
        assertFalse(bridge.eventRemoveListener("custom_ping", onceId))
        bridge.dispose()
        assertEquals(0, bus.listenerCount("custom_ping"))
    }

    @Test
    fun `enforces per-card sandbox write and generation permissions`() {
        val host = InMemoryTavernHelperHost(
            initialChatVariables = JSONObject("""{"hp":100}"""),
            initialMessages = listOf(Message("user", "U", "Hi")),
            initialBooks = listOf(WorldBook(name = "Lore")),
        )
        val restricted = CardSandboxPolicy(
            allowScripts = true,
            allowVariableWrite = false,
            allowMessageWrite = false,
            allowWorldbookWrite = false,
            allowGeneration = false,
        )
        val bridge = TavernHelperBridge(host = host, policy = restricted, postToMain = { it() })

        assertEquals("permission_denied", JSONObject(bridge.replaceVariables("""{"hp":0}""")).getString("error"))
        assertEquals("permission_denied", JSONObject(bridge.setChatMessages("""[{"message_id":0,"message":"x"}]""")).getString("error"))
        assertEquals("permission_denied", JSONObject(bridge.createWorldbookEntries("Lore", """[{"comment":"a"}]""")).getString("error"))
        assertEquals("permission_denied", JSONObject(bridge.generate("{}")).getString("error"))
        assertEquals("permission_denied", JSONObject(bridge.generateRaw("{}")).getString("error"))
        assertEquals("100", bridge.getVariable("hp"))
    }

    @Test
    fun `enforces zero-network offline default and strict domain allowlist`() {
        val offline = CardSandboxPolicy(allowScripts = true)
        assertFalse(offline.isUrlAllowed("https://example.com/a.png"))
        assertFalse(offline.isUrlAllowed("http://localhost:8000/api"))
        assertFalse(offline.isUrlAllowed("javascript:alert(1)"))
        assertFalse(offline.isUrlAllowed("file:///etc/passwd"))
        assertTrue(offline.isUrlAllowed("data:image/png;base64,AAAA"))
        assertTrue(offline.buildCspHeader(true).contains("connect-src 'none'"))

        val allowlisted = CardSandboxPolicy(
            allowScripts = true,
            allowNetwork = true,
            allowedDomains = listOf("cdn.example.com", "https://assets.tavern.test/path"),
        )
        assertTrue(allowlisted.isUrlAllowed("https://cdn.example.com/img.png"))
        assertTrue(allowlisted.isUrlAllowed("https://sub.cdn.example.com/a.css"))
        assertTrue(allowlisted.isUrlAllowed("https://assets.tavern.test/icon.webp"))
        assertFalse(allowlisted.isUrlAllowed("https://evil-cdn.example.com/x.js"))
        assertFalse(allowlisted.isUrlAllowed("https://user:pass@cdn.example.com/x.png"))
        assertFalse(allowlisted.isUrlAllowed("https://tracker.other.org/p.gif"))
        assertTrue(allowlisted.buildCspHeader(true).contains("https://cdn.example.com"))
    }

    @Test
    fun `scopes custom CSS strictly inside card container and hardens ST 1_19_1 style attacks`() {
        val hostileHtml = """
            <style>
                @import url("https://evil.com/leak.css");
                body { background: url("https://evil.com/bg.png"); position: fixed !important; }
                .badge { color: red; behavior: url(xss.htc); /* trick */ }
                @media (max-width: 600px) { .card { padding: 4px; } }
            </style>
            <div class="badge" style="position: fixed; background-image: url('https://evil.com/p.png')">Status</div>
        """.trimIndent()

        val rendered = CardSandboxHtml.sanitizeStaticCardHtml(
            rawHtml = hostileHtml,
            darkTheme = true,
            bgHex = "#111111",
            surfaceHex = "#222222",
            fgHex = "#EEEEEE",
            mutedHex = "#AAAAAA",
            accentHex = "#55AA88",
            borderHex = "#333333",
            policy = CardSandboxPolicy(allowScripts = false, allowNetwork = false, allowCustomCss = true),
        )

        assertTrue(rendered.contains("""<div id="tavern-card-root">"""))
        assertTrue(rendered.contains("#tavern-card-root .badge"))
        assertTrue(rendered.contains("#tavern-card-root .card"))
        assertFalse(rendered.contains("@import", ignoreCase = true))
        assertFalse(rendered.contains("https://evil.com", ignoreCase = true))
        assertFalse(rendered.contains("position: fixed", ignoreCase = true))
        assertFalse(rendered.contains("behavior:", ignoreCase = true))

        // When custom CSS is disabled, style blocks are stripped completely
        assertEquals("", CardCssScoper.sanitizeAndScopeCss(".a { color: red; }", CardSandboxPolicy(allowCustomCss = false)))
    }
}
