package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

internal fun bundledFormatCatalogForTest(): ModelFormatCatalog {
    val assets = File("src/main/assets")
    return ModelFormatCatalog(
        JSONArray(File(assets, "instruct-presets.json").readText()).objects().map { InstructTemplate.fromJson(it) },
        JSONArray(File(assets, "context-presets.json").readText()).objects().map { TextContextTemplate.fromJson(it, checkStory = false) },
        JSONObject(File(assets, "model-template-hashes.json").readText()),
    )
}

class ModelFormattingTest {
    @Test
    fun `model selection matches exact id first and reuses provider defaults for unrelated models`() {
        val fallback = TextContextTemplate("Fallback", "B")
        val reversed = ModelFormatRule(match = ModelMatch.REGEX, target = "/qwen/i",
            templates = ModelTemplatePair(InstructTemplate("Z Format")))
        val first = reversed.copy(id = newId(), target = "/QWEN/i", templates = ModelTemplatePair(InstructTemplate("A Format")))
        val exact = ModelFormatRule(match = ModelMatch.EXACT, target = "qwen-final",
            templates = ModelTemplatePair(context = TextContextTemplate("Exact", "A")))
        val provider = Provider("p", endpoint = "http://localhost", defaultModel = "local",
            protocol = ApiProtocol.KOBOLD_TEXT, textContext = fallback, modelFormatting = ModelFormatting(rules = listOf(reversed, first, exact)))
        val stored = Provider.fromJson(provider.storageJson { it }) { it }
        assertEquals(provider, stored)
        val profile = ConnectionProfile(providerId = provider.id, model = "qwen-final", name = "Model profile")
        val prefs = Preferences(providers = listOf(stored), defaultSelection = provider.defaultSelection,
            connectionProfiles = listOf(profile), activeProfileId = profile.id)
        val chat = Chat("chat", "card", "Chat", modelOverride = ModelSelection(stored.id, "QWEN-8B"))
        assertEquals("A Format", prefs.connectionFor(chat)!!.resolvedTextTemplates().instruct!!.name)
        assertEquals("Exact", prefs.connectionFor(chat.copy(modelOverride = null))!!.resolvedTextTemplates().context!!.name)
        assertEquals(fallback, prefs.resolve(ModelSelection(provider.id, "another"))!!.resolvedTextTemplates().context)
        assertEquals(ModelTemplatePair(), ModelConnection(provider.copy(protocol = ApiProtocol.OLLAMA), "qwen-final").resolvedTextTemplates())
        assertThrows(IllegalArgumentException::class.java) { reversed.copy(target = "/(/i").valid() }
    }

    @Test
    fun `backend template recognition and hash binding respect selected model and configured context cap`() {
        val catalog = bundledFormatCatalogForTest()
        val body = jsonOf("chat_template" to "<|im_start|>user...<|im_start|>assistant...<|im_end|>\u0000",
            "default_generation_settings" to jsonOf("n_ctx" to 24_576))
        val props = catalog.fromProperties(body)
        val hash = "355ce6f9173f420dacc38d53eec8636f555775ddd0828d379a4fe25447937d5c2"
        assertEquals(64, props.hash.length)
        val known = ModelFormatCatalog(catalog.instructs, catalog.contexts, jsonOf(props.hash to "Llama 3 Instruct"))
        assertEquals("Llama 3 Instruct", known.fromProperties(body).suggested.instruct!!.name)
        assertEquals("ChatML", props.suggested.instruct!!.name)
        val bound = ModelFormatRule(match = ModelMatch.TEMPLATE_HASH, target = props.hash,
            templates = ModelTemplatePair(InstructTemplate("Custom"), null)).valid()
        val config = ModelFormatting(deriveInstruct = true, deriveContext = true, deriveContextSize = true, rules = listOf(bound))
            .withBackend("first", props).withBackend("other", props.copy(hash = hash))
        val provider = Provider("p", protocol = ApiProtocol.KOBOLD_TEXT, contextLimit = 16_384, modelFormatting = config)
        assertEquals("Custom", ModelConnection(provider, "first").resolvedTextTemplates().instruct!!.name)
        assertEquals("ChatML", ModelConnection(provider, "other").resolvedTextTemplates().context!!.name)
        assertEquals(16_384, ModelConnection(provider, "first").contextTokens())
        assertEquals(24_576, ModelConnection(provider.copy(contextLimit = null), "first").contextTokens())
        assertEquals(DEFAULT_CONTEXT_TOKENS, ModelConnection(provider.copy(contextLimit = null), "missing").contextTokens())
        val unknown = catalog.fromProperties(jsonOf("chat_template" to "unknown Jinja"))
        assertEquals(ModelTemplatePair(), unknown.suggested)
        assertFalse(unknown.hash.isBlank())
    }
}
