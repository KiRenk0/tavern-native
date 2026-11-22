package app.tavern.nativeclient

import app.tavern.nativeclient.backup.SettingsSnapshot
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProviderRequestOptionsTest {
    @Test
    fun originalYamlOptionsMergeThenExcludeAndKeepJsonTypes() {
        val source = jsonOf("oai_settings" to jsonOf(
            "custom_include_body" to """
                - top_k: 10
                  repetition_penalty: 1.15
                  response_format: {type: json_object, strict: true}
                  label: "yes"
                  array: [1, false, null]
                - top_k: 20
                  max_tokens: 90
            """.trimIndent(),
            "custom_exclude_body" to "[max_tokens, frequency_penalty]",
            "custom_include_headers" to "authorization: Bearer session\nX-Project: test",
        ))
        val options = readOriginalProviderRequestOptions(source.toString().encodeToByteArray()).compile()
        val body = jsonOf("max_tokens" to 8, "frequency_penalty" to 0, "model" to "m")
        options.applyTo(body)
        assertEquals(20, body.getInt("top_k"))
        assertEquals(1.15, body.getDouble("repetition_penalty"), 0.0)
        assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
        assertTrue(body.getJSONObject("response_format").getBoolean("strict"))
        assertEquals("yes", body.getString("label"))
        assertEquals("[1,false,null]", body.getJSONArray("array").toString())
        assertFalse(body.has("max_tokens") || body.has("frequency_penalty"))
        assertEquals("m", body.getString("model"))
        assertEquals(mapOf("authorization" to "Bearer session", "x-project" to "test"),
            options.headersWith(mapOf("Authorization" to "Bearer default")))
        assertFalse(options.redact("API echo: session").contains("session"))
    }

    @Test
    fun unsafeOrAmbiguousHeadersAndYamlAreRejectedWithoutEchoingValues() {
        val invalid = listOf(
            ProviderRequestOptions(includeHeaders = "Authorization: token\nauthorization: other"),
            ProviderRequestOptions(includeHeaders = "Content-Length: 0"),
            ProviderRequestOptions(includeHeaders = """X-Trace: "value\r\nX-Secret: injected"""".trim()),
            ProviderRequestOptions(includeHeaders = "X-Trace: !ENV EXAMPLE_VARIABLE"),
            ProviderRequestOptions(includeBody = "duplicate: 1\nduplicate: 2"),
            ProviderRequestOptions(includeBody = "base: &base [1, 2]\nalias: *base"),
            ProviderRequestOptions(includeBody = "deep: " + "[".repeat(25) + "0" + "]".repeat(25)),
        )
        invalid.forEach { assertTrue(runCatching { it.compile() }.isFailure) }
        assertTrue(runCatching { readOriginalProviderRequestOptions("{}".encodeToByteArray()) }.isFailure)
    }

    @Test
    fun privateRequestOptionsRoundTripInLocalSnapshotsAndAreRemovedFromPortableFiles() {
        val overrides = ProviderRequestOptions("schema_key: private-body", "n", "X-Project: private-header")
        val provider = Provider("custom", "Custom", "https://example.com/v1", "private-key", "model",
            requestOptions = overrides)
        val prefs = Preferences(providers = listOf(provider))
        fun encrypt(input: String) = input.takeIf(String::isNotEmpty)?.reversed().orEmpty()
        val saved = Provider.fromJson(provider.storageJson(::encrypt), ::encrypt)
        assertEquals(overrides, saved.requestOptions)
        val snapshot = SettingsSnapshot.capture("Custom", prefs, ::encrypt)
        assertEquals(overrides, SettingsSnapshot.read(snapshot.bytes()).restore(Preferences(), ::encrypt).providers.single().requestOptions)
        val portable = snapshot.portableBytes()
        assertFalse(portable.decodeToString().contains("private-"))
        assertTrue(SettingsSnapshot.read(portable).restore(Preferences(), ::encrypt).providers.single().requestOptions.isEmpty)
        val template = ProviderTemplate.DEEPSEEK.profile().copy(requestOptions = overrides)
        assertFalse(template.compiledRequestOptions().hasOverrides)
    }
}
