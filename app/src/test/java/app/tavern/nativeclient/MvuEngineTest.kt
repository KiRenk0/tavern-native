package app.tavern.nativeclient

import app.tavern.nativeclient.script.MvuEngine
import app.tavern.nativeclient.script.MvuFieldRule
import app.tavern.nativeclient.script.MvuFieldType
import app.tavern.nativeclient.script.MvuSchema
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MvuEngineTest {

    @Test
    fun `initializes variables from card and WorldBook InitVar entries in JSON and YAML`() {
        val yamlEntry = LoreEntry(
            id = 1,
            title = "[InitVar] Core Stats",
            priority = 10,
            content = """
                ```yaml
                hero:
                  hp: 100
                  mp: 50
                  rank: Novice
                inventory:
                  - sword
                ```
            """.trimIndent(),
        )
        val jsonEntry = LoreEntry(
            id = 2,
            title = "[InitVar] Overrides and Schema",
            priority = 20,
            content = """
                {
                  "hero": { "hp": 120 },
                  "${'$'}schema": {
                    "fields": {
                      "hero.hp": { "type": "integer", "min": 0, "max": 120, "clamp": true },
                      "hero.rank": { "type": "string", "enum": ["Novice", "Knight", "Legend"] }
                    }
                  }
                }
            """.trimIndent(),
        )
        val disabledEntry = LoreEntry(
            id = 3,
            title = "[InitVar] Disabled",
            disabled = true,
            content = """{"hero":{"hp":999}}""",
        )
        val book = WorldBook(name = "RPG Lore").withEntries(listOf(jsonEntry, yamlEntry, disabledEntry))
        val (initial, schema) = MvuEngine.resolveInitialStateWithSchema(
            variables = mapOf("stat_data" to """{"gold":25}"""),
            card = null,
            books = listOf(book),
        )

        assertEquals("120", MvuEngine.readPath(initial, "hero.hp"))
        assertEquals("50", MvuEngine.readPath(initial, "hero.mp"))
        assertEquals("Novice", MvuEngine.readPath(initial, "hero.rank"))
        assertEquals("25", MvuEngine.readPath(initial, "gold"))
        assertEquals("sword", MvuEngine.readPath(initial, "inventory[0]"))
        assertTrue(schema != null && schema.ruleFor("hero.hp")?.max == 120.0)
    }

    @Test
    fun `schema validation enforces readonly fields clamping enum and required rules`() {
        val schema = MvuSchema(
            rules = mapOf(
                "hp" to MvuFieldRule("hp", type = MvuFieldType.INTEGER, min = 0.0, max = 100.0, clamp = true),
                "name" to MvuFieldRule("name", type = MvuFieldType.STRING, readonly = true, required = true),
                "mood" to MvuFieldRule("mood", type = MvuFieldType.STRING, enumValues = listOf("calm", "angry")),
                "secret" to MvuFieldRule("secret", type = MvuFieldType.STRING, required = true),
            ),
            strict = true,
        )
        val initial = JSONObject("""{"hp":80,"name":"Aerin","mood":"calm"}""")
        val msg = Message(
            role = "assistant",
            name = "Aerin",
            text = """
                <UpdateVariable>
                _.add('hp', 50);
                _.set('name', 'Hacked');
                _.set('mood', 'ecstatic');
                _.set('extra', 1);
                </UpdateVariable>
            """.trimIndent(),
        )
        val result = MvuEngine.recomputeMvuState(listOf(msg), initial = initial, schema = schema)

        // Clamped to max=100, readonly mutation blocked
        assertEquals("100", MvuEngine.readPath(result.statData, "hp"))
        assertEquals("Aerin", MvuEngine.readPath(result.statData, "name"))
        val codes = result.validationIssues.map { it.code }.toSet()
        assertTrue("readonly" in codes)
        assertTrue("enum_violation" in codes)
        assertTrue("required" in codes)
        assertTrue("unknown_key" in codes)
    }

    @Test
    fun `supports full JSON Patch including move copy and atomic test failure`() {
        val initial = JSONObject("""{"hp":100,"bag":{"a":"potion"}}""")
        val m0 = Message(
            role = "assistant",
            name = "GM",
            text = """
                <UpdateVariable>
                [
                  {"op":"test","path":"/hp","value":100},
                  {"op":"copy","from":"/bag/a","path":"/bag/b"},
                  {"op":"move","from":"/bag/a","path":"/equipped"}
                ]
                </UpdateVariable>
            """.trimIndent(),
        )
        val m1FailedTest = Message(
            role = "assistant",
            name = "GM",
            text = """
                <UpdateVariable>
                [
                  {"op":"replace","path":"/hp","value":1},
                  {"op":"test","path":"/hp","value":999}
                ]
                </UpdateVariable>
            """.trimIndent(),
        )
        val comp = MvuEngine.recomputeMvuState(listOf(m0, m1FailedTest), initial = initial)
        // First patch applied; second patch rolled back atomically because test failed
        assertEquals("100", MvuEngine.readPath(comp.statData, "hp"))
        assertEquals("potion", MvuEngine.readPath(comp.statData, "bag.b"))
        assertEquals("potion", MvuEngine.readPath(comp.statData, "equipped"))
        assertNull(MvuEngine.readPath(comp.statData, "bag.a"))
        assertTrue(comp.validationIssues.any { it.code == "patch_test_failed" })
    }

    @Test
    fun `deterministic replay and rollback on swipe edit and delete`() {
        val initial = JSONObject("""{"affection":10}""")
        val m0 = Message("assistant", "Rin", "<UpdateVariable>_.add('affection', 5);</UpdateVariable>")
        val alt0 = "<UpdateVariable>_.add('affection', 20);</UpdateVariable>"
        val alt1 = "<UpdateVariable>_.add('affection', -5);</UpdateVariable>"
        val m1 = Message("assistant", "Rin", alt0, variants = listOf(alt0, alt1), index = 0)
        val m2 = Message("assistant", "Rin", "<UpdateVariable>_.add('affection', 3);</UpdateVariable>")
        val list = listOf(m0, m1, m2)

        assertEquals("38", MvuEngine.readPath(MvuEngine.recomputeMvuState(list, initial).statData, "affection"))

        // Swipe m1 to variant 1 -> 10 + 5 - 5 + 3 = 13
        val afterSwipe = MvuEngine.replayAfterSwipe(list, messageIndex = 1, newVariantIndex = 1, initial = initial)
        assertEquals("13", MvuEngine.readPath(afterSwipe.statData, "affection"))

        // Edit m0 -> 10 + 100 + 20 + 3 = 133
        val afterEdit = MvuEngine.replayAfterEdit(
            list,
            messageIndex = 0,
            newText = "<UpdateVariable>_.add('affection', 100);</UpdateVariable>",
            initial = initial,
        )
        assertEquals("133", MvuEngine.readPath(afterEdit.statData, "affection"))

        // Delete m1 -> 10 + 5 + 3 = 18
        val afterDelete = MvuEngine.replayAfterDelete(list, deletedIndices = setOf(1), initial = initial)
        assertEquals("18", MvuEngine.readPath(afterDelete.statData, "affection"))
    }
}
