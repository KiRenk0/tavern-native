package app.tavern.nativeclient

import app.tavern.nativeclient.media.listServiceVoices
import app.tavern.nativeclient.media.synthesizeSelfHostedVoice
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.ConcurrentLinkedQueue

class SelfHostedSpeechTest {
    @Test
    fun `VITS lists engine and speaker and sends multilingual BERT VITS form`() = runBlocking {
        withService(mapOf("/voice/speakers" to json("""{"BERT-VITS2":[{"id":4,"name":"Rin","lang":["ja","zh"]}]}"""))) { url, calls ->
            val options = VoicePreferences(ttsBackend = TtsBackend.VITS, ttsEndpoint = url, ttsApiKey = "demo-tts-key")
                .withSelfHostedVoice(SelfHostedVoiceOptions("BERT-VITS2&4", mapOf("lang" to "ja", "style_text" to "静か + 優しい")))
            val voice = listServiceVoices(options).single()
            assertEquals(options.defaultVoice, voice.id)
            assertEquals("ja、zh", voice.description)
            assertArrayEquals(audio, synthesizeSelfHostedVoice(options, voice.id, "こんにちは & ようこそ"))
            val (catalog, speak) = calls.toList()
            assertEquals("Bearer demo-tts-key", catalog.authorization)
            assertEquals("/voice/bert-vits2", speak.path)
            assertEquals("POST", speak.method)
            assertEquals("application/x-www-form-urlencoded", speak.contentType)
            assertEquals("こんにちは & ようこそ", form(speak.body)["text"])
            assertEquals("静か + 優しい", form(speak.body)["style_text"])
            assertEquals("4", form(speak.body)["id"])
        }
    }

    @Test
    fun `Style Bert sends model speaker and hyphenated style through the query`() = runBlocking {
        val models = """{"2":{"spk2id":{"Mina":7},"style2id":{"Soft-spoken":0}}}"""
        withService(mapOf("/models/info" to json(models))) { url, calls ->
            val options = VoicePreferences(ttsBackend = TtsBackend.STYLE_BERT_VITS2, ttsEndpoint = url)
            val voice = listServiceVoices(options).single()
            assertEquals("Mina (Soft-spoken)", voice.name)
            assertArrayEquals(audio, synthesizeSelfHostedVoice(options, voice.id, "一行<br>二行"))
            val speak = calls.last()
            assertEquals("/voice", speak.path)
            assertEquals("POST", speak.method)
            val query = form(speak.query)
            assertEquals("2", query["model_id"])
            assertEquals("7", query["speaker_id"])
            assertEquals("Soft-spoken", query["style"])
            assertEquals("JP", query["language"])
            assertEquals("一行\n二行", query["text"])
        }
    }

    @Test
    fun `XTTS loads speakers and sends typed generation controls before speaking`() = runBlocking {
        withService(mapOf("/speakers" to json("""[{"name":"Alice","voice_id":"alice.wav"}]"""),
            "/set_tts_settings" to json("{}"))) { url, calls ->
            val options = VoicePreferences(ttsBackend = TtsBackend.XTTS, ttsEndpoint = url)
                .withSelfHostedVoice(SelfHostedVoiceOptions(parameters = mapOf("language" to "zh-cn", "speed" to "1.3")))
            val speaker = listServiceVoices(options).single()
            assertArrayEquals(audio, synthesizeSelfHostedVoice(options, speaker.id, "欢迎"))
            assertEquals(listOf("/speakers", "/set_tts_settings", "/tts_to_audio/"), calls.map(Call::path))
            val params = JSONObject(calls.toList()[1].body)
            assertEquals(1.3, params.getDouble("speed"), 0.001)
            assertEquals(true, params.getBoolean("enable_text_splitting"))
            assertFalse(params.has("language"))
            val request = JSONObject(calls.last().body)
            assertEquals("alice.wav", request.getString("speaker_wav"))
            assertEquals("zh-cn", request.getString("language"))
        }
    }

    @Test
    fun `Silero supports Extras prefix with optional session and CosyVoice posts to its base URL`() = runBlocking {
        withService(mapOf("/api/tts/speakers" to json("""[{"voice_id":"ru_0"}]"""),
            "/api/tts/session" to Response(404, "application/json", "{}".encodeToByteArray()),
            "/cosy/speakers" to json("""["中文女"]"""))) { url, calls ->
            val silero = VoicePreferences(ttsBackend = TtsBackend.SILERO, ttsEndpoint = "$url/api/tts")
            val cosy = VoicePreferences(ttsBackend = TtsBackend.COSYVOICE, ttsEndpoint = "$url/cosy")
            for ((options, line) in listOf(silero to "Привет!", cosy to "你好！")) {
                val voice = listServiceVoices(options).single()
                assertArrayEquals(audio, synthesizeSelfHostedVoice(options, voice.id, line))
                val body = JSONObject(calls.last().body)
                assertEquals(line, body.getString("text"))
                assertEquals(voice.id, body.getString("speaker"))
            }
            assertEquals(listOf("/api/tts/speakers", "/api/tts/session", "/api/tts/generate", "/cosy/speakers", "/cosy/"),
                calls.map(Call::path))
            assertEquals("tavern-native", JSONObject(calls.toList()[2].body).getString("session"))
        }
    }

    @Test
    fun `each service retains its own endpoint key tuning and character voice across settings reload`() {
        val vits = VoicePreferences().selectTtsBackend(TtsBackend.VITS)
            .copy(ttsEndpoint = "https://vits.example.com", ttsApiKey = "vits-credential")
            .withSelfHostedVoice(SelfHostedVoiceOptions("VITS&1", mapOf("lang" to "ja", "length" to "1.4")))
            .withVoice("character:rin", "BERT-VITS2&2")
        val cosy = vits.selectTtsBackend(TtsBackend.COSYVOICE)
            .copy(ttsEndpoint = "https://cosy.example.com", ttsApiKey = "cosy-credential")
            .withSelfHostedVoice(SelfHostedVoiceOptions("中文女"))
        val saved = cosy.json { "saved-$it" }
        assertFalse(saved.toString().contains("\"vits-credential\""))
        assertFalse(saved.toString().contains("\"cosy-credential\""))
        val loaded = VoicePreferences.from(saved) { it.removePrefix("saved-") }
        assertEquals("中文女", loaded.actorVoice("character:rin"))
        val restoredVits = loaded.selectTtsBackend(TtsBackend.VITS)
        assertEquals("BERT-VITS2&2", restoredVits.actorVoice("character:rin"))
        assertEquals("vits-credential", restoredVits.ttsApiKey)
        assertEquals("https://vits.example.com", restoredVits.ttsEndpoint)
        assertEquals(1.4, restoredVits.readSpeechParameters().getValue("length"))
    }

    private data class Call(val path: String, val query: String, val method: String, val authorization: String,
                            val contentType: String, val body: String)

    private data class Response(val status: Int, val type: String, val body: ByteArray)

    private suspend fun withService(replies: Map<String, Response>, run: suspend (String, Collection<Call>) -> Unit) {
        val calls = ConcurrentLinkedQueue<Call>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.use {
                val uri = it.requestURI
                calls.add(Call(uri.path, uri.rawQuery.orEmpty(), it.requestMethod, it.requestHeaders.getFirst("Authorization").orEmpty(),
                    it.requestHeaders.getFirst("Content-Type").orEmpty(), it.requestBody.readBytes().decodeToString()))
                val response = replies[uri.path] ?: Response(200, "audio/wav", audio)
                it.responseHeaders.set("Content-Type", response.type)
                it.sendResponseHeaders(response.status, response.body.size.toLong())
                it.responseBody.write(response.body)
            }
        }
        server.start()
        try {
            run("http://127.0.0.1:${server.address.port}", calls)
        } finally {
            server.stop(0)
        }
    }

    private fun json(text: String) = Response(200, "application/json", text.encodeToByteArray())

    private fun form(data: String): Map<String, String> = data.split('&').associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
    }

    private val audio = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x41, 0x56, 0x45)
}
