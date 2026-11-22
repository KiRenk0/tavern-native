package app.tavern.nativeclient

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class TextSamplingTest {
    @Test
    fun `upstream sampler presets load and round trip in provider storage`() {
        val entries = JSONArray(File("src/main/assets/text-sampling-presets.json").readText()).objects()
        assertEquals(mapOf("Text Generation" to 6, "Kobold" to 6), entries.groupingBy { it.getString("source") }.eachCount())
        val presets = entries.map { TextSamplerPreset.read(it.getJSONObject("preset")) }
        val kobold = presets.single { it.name == "RecoveredRuins" }
        assertEquals("1.0", kobold[TextSamplerField.TYPICAL])
        assertEquals("600", kobold[TextSamplerField.REPETITION_RANGE])
        val settings = TextSamplingSettings(presets.first(), mapOf("other" to kobold))
        val provider = Provider("test", protocol = ApiProtocol.KOBOLD_TEXT, textSampling = settings)
        val stored = provider.storageJson { it }
        val loaded = Provider.fromJson(JSONObject(stored.toString())) { it }
        assertEquals(settings, loaded.textSampling)
        assertEquals(kobold, ModelConnection(loaded, "other").textSampler())
        assertEquals(settings.default, ModelConnection(loaded, "new").textSampler())
        stored.remove("textSampling")
        assertEquals(TextSamplingSettings(), Provider.fromJson(stored) { it }.textSampling)
    }

    @Test
    fun `backend converts original sampler fields without sending unrelated keys`() {
        val original = JSONObject("""{
            "name":"Advanced","typical_p":0.9,"rep_pen":1.2,"rep_pen_range":-1,"seed":-1,
            "dynatemp":true,"min_temp":0.2,"max_temp":1.2,"dynatemp_exponent":0.5,
            "dry_sequence_breakers":"[\"\\n\",\":\"]","mirostat_mode":2,"ignore_eos_token":true,
            "samplers":["dry","typ_p","typical_p","tfs_z","top_p","top_p"],
            "json_schema":{"type":"object"},"api_key":"ignored"
        }""")
        val sampler = TextSamplerPreset.read(original).json()
        val kobold = JSONObject()
        applyTextSampler(sampler, kobold, TextBackend.KOBOLD)
        assertEquals(0.7, kobold.getDouble("temperature"), 0.000001)
        assertEquals(0.5, kobold.getDouble("dynatemp_range"), 0.000001)
        assertEquals(2, kobold.getInt("mirostat"))
        assertEquals(0.9, kobold.getDouble("typical"), 0.000001)
        assertEquals(listOf("\n", ":"), kobold.getJSONArray("dry_sequence_breakers").strings())
        assertEquals(true, kobold.getBoolean("bypass_eos"))
        assertFalse(kobold.has("rep_pen_range"))
        assertFalse(kobold.has("sampler_seed"))
        assertFalse(kobold.has("json_schema"))
        val llama = JSONObject()
        applyTextSampler(sampler, llama, TextBackend.LLAMA_CPP)
        assertEquals(listOf("dry", "typ_p", "top_p", "top_p"), llama.getJSONArray("samplers").strings())
        assertEquals("object", llama.getJSONObject("json_schema").getString("type"))
        assertEquals(-1, llama.getInt("repeat_last_n"))
        assertFalse(llama.has("api_key"))
        val ollama = JSONObject()
        applyTextSampler(sampler, ollama, TextBackend.OLLAMA)
        assertEquals(1.2, ollama.getDouble("repeat_penalty"), 0.000001)
        assertFalse(ollama.has("dry_sequence_breakers"))
        assertFalse(ollama.has("dynatemp_range"))
    }

    @Test
    fun `invalid advanced fields are reported at import time`() {
        assertThrows(IllegalArgumentException::class.java) {
            TextSamplerPreset.read(JSONObject("""{"sampler_order":[0,0,1,2,3,4,5]}"""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TextSamplerPreset.read(JSONObject("""{"dynatemp":true,"min_temp":2,"max_temp":1}"""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TextSamplerPreset.read(JSONObject("""{"name":"Unrelated settings","api_key":"ignored"}"""))
        }
    }
}
