package app.tavern.nativeclient

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StScriptTest {
    private class FakeHost(
        val locals: MutableMap<String, String> = linkedMapOf(),
        val globals: MutableMap<String, String> = linkedMapOf(),
        val quickReplies: Map<String, String> = emptyMap(),
    ) : StScriptHost {
        val commands = mutableListOf<Triple<String, Map<String, String>, String>>()
        val tools = linkedMapOf<String, ModelTool>()
        val messages = mutableListOf(
            Message("user", "User", "hello"),
            Message("assistant", "Rin", "hi", variants = listOf("hi"), index = 0),
        )

        override fun variable(key: String, global: Boolean): String? =
            (if (global) globals else locals)[key]

        override suspend fun variable(key: String, value: String?, global: Boolean) {
            val map = if (global) globals else locals
            if (value == null) map.remove(key) else map[key] = value
        }

        override fun macro(name: String): String? = when (name) {
            "user" -> "User"
            "char" -> "Rin"
            else -> null
        }

        override fun quickReply(name: String): String? = quickReplies[name]

        override fun registerTool(tool: ModelTool) {
            tools[tool.name] = tool
        }

        override suspend fun command(name: String, named: Map<String, String>, text: String): String {
            commands += Triple(name, named, text)
            return when (name) {
                "echo" -> text
                "messages" -> messages.joinToString("\n") { "${it.name}: ${it.text}" }
                "hide", "unhide" -> {
                    val range = scriptMessageRange(text.ifEmpty { "-1" }, messages.size) ?: return ""
                    for (idx in range) {
                        messages[idx] = messages[idx].copy(hidden = name == "hide")
                    }
                    ""
                }
                "cut" -> {
                    val range = scriptMessageRange(text, messages.size) ?: return ""
                    val removed = range.reversed().map { idx -> messages.removeAt(idx).text }.reversed()
                    removed.joinToString("\n")
                }
                "addswipe" -> {
                    val lastIdx = messages.lastIndex
                    val last = messages[lastIdx]
                    val updated = last.withScriptAlternative(text, named["switch"].orEmpty().scriptBoolean())
                    messages[lastIdx] = updated
                    updated.variants.lastIndex.toString()
                }
                else -> text
            }
        }
    }

    @Test
    fun pipelinesLetVarsAndLocalGlobalVariables() = runBlocking {
        val host = FakeHost()
        val script = StScript(host)

        val result = script.run(
            """
            /let x 10 |
            /setvar key=hp {{var::x}} |
            /addvar key=hp 5 |
            /incvar hp |
            /setglobalvar key=score 3 |
            /incglobalvar score |
            /pass {{getvar::hp}}-{{getglobalvar::score}}
            """.trimIndent(),
        )

        assertEquals("16-4", result)
        assertEquals("16", host.locals["hp"])
        assertEquals("4", host.globals["score"])

        script.run("/flushvar hp")
        assertNull(host.locals["hp"])
    }

    @Test
    fun ifElseWhileAndTimesLoopWithClosures() = runBlocking {
        val host = FakeHost()
        val script = StScript(host)

        val ifOut = script.run(
            """
            /let n 5 |
            /if left=n rule=gt right=3 else={: /return small :} {: /return big :}
            """.trimIndent(),
        )
        assertEquals("big", ifOut)

        val timesOut = script.run(
            """
            /setvar key=sum 0 |
            /times 4 {: /addvar key=sum {{timesIndex}} :} |
            /getvar sum
            """.trimIndent(),
        )
        assertEquals("6", timesOut)

        val whileOut = script.run(
            """
            /setvar key=c 0 |
            /while left={{getvar::c}} rule=lt right=3 {: /incvar c :} |
            /getvar c
            """.trimIndent(),
        )
        assertEquals("3", whileOut)
    }

    @Test
    fun registersToolClosureAndInvokesWithArgumentsAsData() = runBlocking {
        val host = FakeHost()
        val script = StScript(host)

        script.run(
            """
            /tools-register name=roll_damage description="Roll weapon damage" parameters="{\"type\":\"object\",\"properties\":{\"dice\":{\"type\":\"string\"}}}" {:
                /setvar key=lastDice {{arg::arg.dice}} |
                /return rolled:{{arg::arg.dice}}
            :}
            """.trimIndent(),
        )

        val registered = host.tools["roll_damage"]
        assertNotNull(registered)
        val output = registered!!.execute(JSONObject().put("dice", "2d6"))
        assertEquals("rolled:2d6", output)
        assertEquals("2d6", host.locals["lastDice"])

        // When toolArgumentsAreData is true, passing dynamic quick-reply name from arg throws.
        val host2 = FakeHost(quickReplies = mapOf("dangerous" to "/echo evil"))
        val script2 = StScript(host2)
        script2.run(
            """
            /tools-register name=unsafe description="Test" parameters="{\"type\":\"object\"}" {:
                /run {{arg::arg.target}}
            :}
            """.trimIndent(),
        )
        var failed = false
        try {
            host2.tools["unsafe"]!!.execute(JSONObject().put("target", "dangerous"))
            fail("Expected dynamic non-literal /run target inside tool closure to fail")
        } catch (_: Exception) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun collectionAndStringBuiltinsAndMessageCommandsWork() = runBlocking {
        val host = FakeHost()
        val script = StScript(host)

        assertEquals("[\"a\",\"b\",\"c\"]", script.run("/split a,b,c"))
        assertEquals("a | b | c", script.run("/join separator=\" | \" [\"a\",\"b\",\"c\"]"))
        assertEquals("3", script.run("/len [1,2,3]"))

        val transcript = script.run("/messages")
        assertTrue(transcript.contains("Rin: hi"))

        script.run("/hide 0")
        assertTrue(host.messages[0].hidden)
        script.run("/unhide 0")
        assertFalse(host.messages[0].hidden)

        val swipeIdx = script.run("/addswipe switch=true hello there")
        assertEquals("1", swipeIdx)
        assertEquals(listOf("hi", "hello there"), host.messages[1].variants)
        assertEquals("hello there", host.messages[1].text)

        val cutOut = script.run("/cut 1")
        assertEquals("hello there", cutOut)
        assertEquals(1, host.messages.size)
    }

    @Test
    fun cancellationStopsLongLoopPromptly() = runBlocking {
        val host = FakeHost()
        val script = StScript(host)
        var caughtCancellation = false

        val job = launch {
            try {
                script.run("/times 10000 {: /wait 20 :}")
            } catch (_: CancellationException) {
                caughtCancellation = true
            }
        }
        delay(35)
        job.cancelAndJoin()
        assertTrue("Expected CancellationException to propagate", caughtCancellation)
    }
}
