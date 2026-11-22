package app.tavern.nativeclient

import app.tavern.nativeclient.backup.SettingsSnapshot
import app.tavern.nativeclient.media.encodePcm16Wav
import app.tavern.nativeclient.media.geminiSpeechAudio
import app.tavern.nativeclient.media.listServiceVoices
import app.tavern.nativeclient.media.synthesizeBackendAudio
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

class NativeVoiceBackendsTest {
    private data class Call(val path: String, val query: String?, val method: String, val key: String?,
                            val bearer: String?, val body: JSONObject?)
    private data class Reply(val type: String, val body: ByteArray, val status: Int = 200)

    private fun server(handler: (Call) -> Reply): Pair<HttpServer, String> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.use {
                val body = it.requestBody.readBytes().decodeToString().takeIf(String::isNotBlank)?.let(::JSONObject)
                val reply = handler(Call(it.requestURI.path, it.requestURI.rawQuery, it.requestMethod,
                    it.requestHeaders.getFirst("x-goog-api-key") ?: it.requestHeaders.getFirst("xi-api-key"),
                    it.requestHeaders.getFirst("Authorization"), body))
                it.responseHeaders.set("Content-Type", reply.type)
                it.sendResponseHeaders(reply.status, reply.body.size.toLong())
                it.responseBody.write(reply.body)
            }
        }
        server.start()
        return server to "http://127.0.0.1:${server.address.port}"
    }

    private fun json(text: String) = Reply("application/json", text.encodeToByteArray())

    @Test
    fun geminiSpeechUsesModelSpecificRoutesAndPlaysTheCompletePcm() = runBlocking {
        val calls = CopyOnWriteArrayList<Call>()
        val modernWav = encodePcm16Wav(byteArrayOf(40, 0), sampleRate = 24_000)
        val encodedWav = Base64.getEncoder().encodeToString(modernWav)
        val (server, origin) = server { call ->
            calls += call
            when (call.path) {
                "/v1beta/interactions" -> json("""{"steps":[{"type":"user_input"},{"type":"model_output","content":[
                    {"type":"text","text":"ignored"},{"type":"audio","mime_type":"audio/wav","data":"$encodedWav"}]}]}""")
                "/v1beta/models/gemini-2.5-pro-preview-tts:generateContent" -> json("""{"candidates":[{"content":{"parts":[
                    {"text":"ignored"},{"inlineData":{"mimeType":"audio/L16; codec=pcm; rate=16000","data":"AQI="}},
                    {"inlineData":{"mimeType":"audio/L16; rate=16000","data":"AwQ="}}]}}]}""")
                else -> error(call.path)
            }
        }
        try {
            val google = Provider("local", "Gemini", "$origin/v1beta/openai", "google-secret", "chat",
                protocol = ApiProtocol.GEMINI_NATIVE)
            val options = VoicePreferences(ttsBackend = TtsBackend.GEMINI_NATIVE, geminiTtsStyle = "平静")
            val wav = synthesizeBackendAudio(Narration("你好", options, "Zephyr", google))
            assertArrayEquals(modernWav, wav)
            val modern = calls[0]
            assertEquals("google-secret", modern.key)
            val inputs = modern.body!!.getJSONArray("input").getJSONObject(0).getJSONArray("content").getJSONObject(0)
            assertEquals("你好", inputs.getString("text"))
            assertEquals("平静", inputs.getJSONArray("annotations").getJSONObject(0).getString("style"))
            assertEquals("Zephyr", modern.body.getJSONObject("generation_config").getJSONArray("speech_config")
                .getJSONObject(0).getString("voice"))
            assertFalse(modern.body.getBoolean("store"))

            val legacy = options.copy(geminiTtsModel = "models/gemini-2.5-pro-preview-tts")
            val data = synthesizeBackendAudio(Narration("你好", legacy, "Kore", google))
            assertEquals("RIFF", data.copyOfRange(0, 4).decodeToString())
            val header = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(16000, header.getInt(24))
            assertEquals(4, header.getInt(40))
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), data.copyOfRange(44, 48))
            val generation = calls[1].body!!.getJSONObject("generationConfig")
            assertEquals("Kore", generation.getJSONObject("speechConfig").getJSONObject("voiceConfig")
                .getJSONObject("prebuiltVoiceConfig").getString("voiceName"))
            assertEquals(listOf("AUDIO"), generation.getJSONArray("responseModalities").strings())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun novelAiSpeechReusesTheConfiguredOriginAndExactVoiceSeed() = runBlocking {
        val calls = CopyOnWriteArrayList<Call>()
        val sample = "ID3".encodeToByteArray()
        val (server, origin) = server { call ->
            calls += call
            Reply("audio/mpeg", sample)
        }
        try {
            val options = VoicePreferences(ttsBackend = TtsBackend.NOVELAI, novelAiTtsProvider = "novel",
                novelAiTtsVoice = "Ligeia").withVoice("character:a", "海 + snow & petals")
            val novel = Provider("novel", "NovelAI", "$origin/ai/generate-stream", "novel-secret", NovelAiModel.ERATO.id,
                protocol = ApiProtocol.NOVELAI_TEXT)
            assertEquals(novel, options.speechProvider(listOf(novel)))
            assertArrayEquals(sample, synthesizeBackendAudio(Narration("日文：こんにちは＋你好",
                options, options.actorVoice("character:a"), novel)))
            val call = calls.single()
            assertEquals("/ai/generate-voice", call.path)
            assertEquals("GET", call.method)
            assertEquals("Bearer novel-secret", call.bearer)
            val query = requireNotNull(call.query).split('&').associate {
                it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
            assertEquals(mapOf("text" to "日文：こんにちは＋你好", "voice" to "-1", "seed" to "海 + snow & petals",
                "opus" to "false", "version" to "v2"), query)
            assertTrue(listServiceVoices(options).any { it.id == "Ligeia" })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun elevenLabsCatalogReadsCursorPagesWithTheSelectedCredential() = runBlocking {
        val calls = CopyOnWriteArrayList<Call>()
        val (server, origin) = server { call ->
            calls += call
            if ("next_page_token" in call.query.orEmpty()) {
                json("""{"voices":[{"voice_id":"k2","name":"Kana","description":"Soft delivery"}],"has_more":false}""")
            } else json("""{"voices":[{"voice_id":"k1","name":"Mio"}],"has_more":true,"next_page_token":"seek + up"}""")
        }
        try {
            val options = VoicePreferences(ttsBackend = TtsBackend.ELEVENLABS, ttsEndpoint = "$origin/v1", ttsApiKey = "eleven-key")
            val result = listServiceVoices(options)
            assertEquals(listOf("k1", "k2"), result.map { it.id })
            assertEquals("Soft delivery", result.last().description)
            assertTrue(calls.all { it.path == "/v2/voices" && it.key == "eleven-key" && it.method == "GET" })
            assertTrue(calls[1].query.orEmpty().endsWith("next_page_token=seek+%2B+up"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun switchingSpeechEnginesIsolatesCredentialsAndPortableSettings() {
        val old = VoicePreferences.from(JSONObject("""{"ttsBackend":"ELEVENLABS","ttsEndpoint":"https://voice-a.example","ttsApiKey":"eleven-secret"}"""))
        val fish = old.selectTtsBackend(TtsBackend.FISH_AUDIO)
        assertEquals("", fish.ttsEndpoint)
        assertEquals("", fish.ttsApiKey)
        val stored = fish.copy(ttsEndpoint = "https://voice-b.example", ttsApiKey = "fish-secret")
            .selectTtsBackend(TtsBackend.ELEVENLABS)
        assertEquals(old.ttsEndpoint, stored.ttsEndpoint)
        assertEquals(old.ttsApiKey, stored.ttsApiKey)
        assertEquals("fish-secret", stored.selectTtsBackend(TtsBackend.FISH_AUDIO).ttsApiKey)

        val prefs = Preferences(voice = stored)
        val snapshot = SettingsSnapshot.capture("Voice", prefs) { if (it.isBlank()) it else "sealed:$it" }
        val restored = snapshot.restore(Preferences()) { it.removePrefix("sealed:") }
        assertEquals(stored, restored.voice)
        val portable = snapshot.portableBytes()
        assertFalse(portable.decodeToString().contains("-secret"))
        val clean = SettingsSnapshot.read(portable).restore(Preferences()) { it }
        assertEquals("", clean.voice.ttsApiKey)
        assertEquals("", clean.voice.selectTtsBackend(TtsBackend.FISH_AUDIO).ttsApiKey)
        val pinned = SettingsSnapshot.read(portable).restore(prefs) { it }
        assertEquals(stored, pinned.voice)
        val mismatched = SettingsSnapshot.read(portable).restore(prefs.copy(voice = stored.copy(
            inactiveTtsCredentials = mapOf(TtsBackend.FISH_AUDIO to TtsCredentials("https://different.example", "other-secret"))))) { it }
        assertEquals("", mismatched.voice.selectTtsBackend(TtsBackend.FISH_AUDIO).ttsApiKey)

        val legacy = VoicePreferences.from(JSONObject("""{"ttsBackend":"SYSTEM","backendId":"legacy"}"""))
        assertEquals(TtsBackend.OPENAI_COMPAT, legacy.activeBackend)
        val system = VoicePreferences.from(legacy.selectTtsBackend(TtsBackend.SYSTEM).json())
        assertEquals(TtsBackend.SYSTEM, system.activeBackend)
        assertEquals("legacy", system.selectTtsBackend(TtsBackend.OPENAI_COMPAT).backendId)
    }

    @Test
    fun emptyGeminiSpeechReportsAnAudioError() {
        val error = runCatching { geminiSpeechAudio(JSONObject("""{"steps":[{"type":"model_output","content":[{"type":"text"}]}]}"""), true) }
            .exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("没有返回可播放的语音"))
    }
}
