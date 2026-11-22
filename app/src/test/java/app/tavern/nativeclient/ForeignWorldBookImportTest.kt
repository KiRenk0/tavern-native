package app.tavern.nativeclient

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.zip.CRC32

class ForeignWorldBookImportTest {
    private val novel = """{"lorebookVersion":2,"entries":[
        {"displayName":"Novel","text":"Novel lore","keys":["snow"],"enabled":true,"contextConfig":{"budgetPriority":30}}
    ]}"""

    @Test
    fun `NovelAI JSON and naidata PNG import the same world entries`() {
        val json = readWorldBook(novel.encodeToByteArray(), "Novel")
        val png = readWorldBook(novelPng(novel), "Novel")
        assertEquals(json.entries, png.entries)
        assertEquals("Novel lore", json.entries.single().content)
        assertEquals(listOf("snow"), json.entries.single().keywords)
        assertEquals(30, json.entries.single().priority)
    }

    @Test
    fun `Agnai and Risu convert their own trigger fields before exporting SillyTavern JSON`() {
        val agnai = WorldBook.import(JSONObject("""{"kind":"memory","entries":[
            {"name":"Old","entry":"Agnai text","keywords":["road"],"weight":12,"enabled":false}
        ]}"""), "Agnai").entries.single()
        assertTrue(agnai.disabled)
        assertEquals("Agnai text", agnai.content)
        assertEquals(12, agnai.priority)
        val risu = WorldBook.import(JSONObject("""{"type":"risu","data":[
            {"comment":"Risu","content":"Risu text","key":" snow, path ","secondkey":"sky,dawn",
             "alwaysActive":true,"selective":true,"insertorder":20,"activationPercent":42}
        ]}"""), "Risu")
        val originalFormat = JSONObject(risu.export().decodeToString())
        val restored = WorldBook.import(originalFormat, "Risu").entries.single()
        assertEquals(listOf("snow", "path"), restored.keywords)
        assertEquals(listOf("sky", "dawn"), restored.secondary)
        assertEquals(42, restored.probability)
        assertTrue(restored.constant)
        assertFalse(restored.disabled)
    }

    private fun novelPng(json: String): ByteArray {
        val png = Base64.getDecoder().decode(REPLY_PNG_FIXTURE)
        val label = "tEXt".encodeToByteArray()
        val payload = ("naidata\u0000" + Base64.getEncoder().encodeToString(json.encodeToByteArray())).encodeToByteArray()
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.write(png, 0, png.size - 12)
            data.writeInt(payload.size)
            data.write(label)
            data.write(payload)
            data.writeInt(CRC32().apply { update(label); update(payload) }.value.toInt())
            data.write(png, png.size - 12, 12)
        }
        return output.toByteArray()
    }
}
