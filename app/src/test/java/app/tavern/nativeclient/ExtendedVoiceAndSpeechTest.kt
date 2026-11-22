package app.tavern.nativeclient

import app.tavern.nativeclient.backup.SettingsSnapshot
import app.tavern.nativeclient.media.LocalWhisperStt
import app.tavern.nativeclient.media.RvcSettings
import app.tavern.nativeclient.media.WHISPER_CHUNK_SAMPLES
import app.tavern.nativeclient.media.WHISPER_FRAMES
import app.tavern.nativeclient.media.WHISPER_N_MELS
import app.tavern.nativeclient.media.WhisperWeights
import app.tavern.nativeclient.media.azureSpeechRoot
import app.tavern.nativeclient.media.buildAzureSsml
import app.tavern.nativeclient.media.buildWhisperPromptTokens
import app.tavern.nativeclient.media.computeLogMelSpectrogram
import app.tavern.nativeclient.media.convertAudioWithRvc
import app.tavern.nativeclient.media.curatedKokoroVoices
import app.tavern.nativeclient.media.decodeWavPcm16
import app.tavern.nativeclient.media.decodeWhisperTokens
import app.tavern.nativeclient.media.encodePcm16Wav
import app.tavern.nativeclient.media.listRvcModels
import app.tavern.nativeclient.media.listServiceVoices
import app.tavern.nativeclient.media.padOrTrimAudio
import app.tavern.nativeclient.media.parseWhisperVocabJson
import app.tavern.nativeclient.media.resolveAllTalkAudioUrl
import app.tavern.nativeclient.media.synthesizeBackendAudio
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.PI
import kotlin.math.sin

class ExtendedVoiceAndSpeechTest {
    private data class Call(
        val method: String,
        val path: String,
        val auth: String?,
        val azureKey: String?,
        val contentType: String?,
        val body: String,
    )

    private data class Reply(val contentType: String, val body: ByteArray, val status: Int = 200)

    private fun startServer(handler: (Call) -> Reply): Pair<HttpServer, String> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.use {
                val body = it.requestBody.readBytes().decodeToString()
                val reply = handler(
                    Call(
                        method = it.requestMethod,
                        path = it.requestURI.path,
                        auth = it.requestHeaders.getFirst("Authorization"),
                        azureKey = it.requestHeaders.getFirst("Ocp-Apim-Subscription-Key"),
                        contentType = it.requestHeaders.getFirst("Content-Type"),
                        body = body,
                    ),
                )
                it.responseHeaders.set("Content-Type", reply.contentType)
                it.sendResponseHeaders(reply.status, reply.body.size.toLong())
                it.responseBody.write(reply.body)
            }
        }
        server.start()
        return server to "http://127.0.0.1:${server.address.port}"
    }

    @Test
    fun azureSpeechListsVoicesSynthesizesSsmlAndEncryptsCredentials() = runBlocking {
        assertEquals("https://japaneast.tts.speech.microsoft.com", azureSpeechRoot("japaneast", ""))
        assertTrue(runCatching { azureSpeechRoot("bad/region!", "") }.isFailure)

        val ssml = buildAzureSsml("Hello <World> & 'Rin'", "ja-JP-NanamiNeural", speed = 1.25f, pitch = 1.1f)
        assertTrue(ssml.contains("xml:lang='ja-JP'"))
        assertTrue(ssml.contains("name='ja-JP-NanamiNeural'"))
        assertTrue(ssml.contains("rate='+25%'"))
        assertTrue(ssml.contains("pitch='+5%'"))
        assertTrue(ssml.contains("Hello &lt;World&gt; &amp; &apos;Rin&apos;"))

        val calls = CopyOnWriteArrayList<Call>()
        val mp3Sample = byteArrayOf(0x49, 0x44, 0x33, 0x04, 0x01)
        val (server, origin) = startServer { call ->
            calls += call
            when (call.path) {
                "/cognitiveservices/voices/list" -> Reply(
                    "application/json",
                    """[{"ShortName":"zh-CN-XiaoxiaoNeural","LocalName":"晓晓","Locale":"zh-CN","Gender":"Female"}]""".encodeToByteArray(),
                )
                "/cognitiveservices/v1" -> Reply("audio/mpeg", mp3Sample)
                else -> Reply("text/plain", ByteArray(0), 404)
            }
        }
        try {
            val options = VoicePreferences(
                ttsBackend = TtsBackend.AZURE,
                ttsEndpoint = origin,
                ttsApiKey = "azure-secret-key",
                azureRegion = "eastus",
                azureVoice = "zh-CN-XiaoxiaoNeural",
            ).withVoice("character:hero", "ja-JP-NanamiNeural")

            val voices = listServiceVoices(options)
            assertEquals(1, voices.size)
            assertEquals("zh-CN-XiaoxiaoNeural", voices.single().id)
            assertEquals("azure-secret-key", calls[0].azureKey)

            val audio = synthesizeBackendAudio(Narration("你好酒馆", options, options.actorVoice("character:hero")))
            assertArrayEquals(mp3Sample, audio)
            assertEquals("azure-secret-key", calls[1].azureKey)
            assertTrue(calls[1].contentType.orEmpty().contains("application/ssml+xml"))
            assertTrue(calls[1].body.contains("ja-JP-NanamiNeural"))

            // Keystore seal/unseal round-trip & snapshot redaction
            val sealedJson = options.json { if (it.isBlank()) it else "enc:$it" }
            assertEquals("enc:azure-secret-key", sealedJson.getString("ttsApiKey"))
            val restored = VoicePreferences.from(sealedJson) { it.removePrefix("enc:") }
            assertEquals("azure-secret-key", restored.ttsApiKey)
            assertEquals("ja-JP-NanamiNeural", restored.actorVoice("character:hero"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun allTalkListsVoicesSynthesizesTwoStepAudioAndBlocksCrossOriginUrls() = runBlocking {
        assertEquals("http://127.0.0.1:7851/audio/out.wav", resolveAllTalkAudioUrl("http://127.0.0.1:7851", "/audio/out.wav"))
        assertTrue(runCatching { resolveAllTalkAudioUrl("http://127.0.0.1:7851", "https://evil.example/a.wav") }.isFailure)

        val calls = CopyOnWriteArrayList<Call>()
        val wavSample = encodePcm16Wav(byteArrayOf(10, 0, 20, 0))
        val (server, origin) = startServer { call ->
            calls += call
            when (call.path) {
                "/api/voices" -> Reply("application/json", """{"voices":["female_01.wav","male_02.wav"]}""".encodeToByteArray())
                "/api/tts-generate" -> Reply(
                    "application/json",
                    """{"status":"generate-success","output_file_url":"/audio/tavern_tts_1.wav"}""".encodeToByteArray(),
                )
                "/audio/tavern_tts_1.wav" -> Reply("audio/wav", wavSample)
                else -> Reply("text/plain", ByteArray(0), 404)
            }
        }
        try {
            val options = VoicePreferences(
                ttsBackend = TtsBackend.ALLTALK,
                ttsEndpoint = origin,
                allTalkVoice = "female_01.wav",
                allTalkLanguage = "zh-cn",
            )
            val voices = listServiceVoices(options)
            assertEquals(listOf("female_01.wav", "male_02.wav"), voices.map { it.id })
            val audio = synthesizeBackendAudio(Narration("欢迎光临", options, "female_01.wav"))
            assertArrayEquals(wavSample, audio)
            assertEquals("/api/tts-generate", calls[1].path)
            assertTrue(calls[1].body.contains("character_voice_gen=female_01.wav"))
            assertTrue(calls[1].body.contains("language=zh-cn"))
            assertEquals("/audio/tavern_tts_1.wav", calls[2].path)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun kokoroListsVoicesWithFallbackAndSynthesizesMixedVoice() = runBlocking {
        val calls = CopyOnWriteArrayList<Call>()
        val mp3Sample = "ID3KOKORO".encodeToByteArray()
        val (server, origin) = startServer { call ->
            calls += call
            when (call.path) {
                "/v1/audio/voices" -> Reply("application/json", """{"voices":["af_heart","af_bella","zf_xiaoxiao"]}""".encodeToByteArray())
                "/v1/audio/speech" -> Reply("audio/mpeg", mp3Sample)
                else -> Reply("text/plain", ByteArray(0), 404)
            }
        }
        try {
            val options = VoicePreferences(
                ttsBackend = TtsBackend.KOKORO,
                ttsEndpoint = origin,
                ttsApiKey = "kokoro-key",
                kokoroVoice = "af_heart+af_bella",
                speed = 1.15f,
            )
            val voices = listServiceVoices(options)
            assertEquals(listOf("af_heart", "af_bella", "zf_xiaoxiao"), voices.map { it.id })
            val audio = synthesizeBackendAudio(Narration("Hello tavern", options, "af_heart+af_bella"))
            assertArrayEquals(mp3Sample, audio)
            val reqJson = JSONObject(calls[1].body)
            assertEquals("kokoro", reqJson.getString("model"))
            assertEquals("af_heart+af_bella", reqJson.getString("voice"))
            assertEquals(1.15, reqJson.getDouble("speed"), 0.01)
            assertEquals("Bearer kokoro-key", calls[1].auth)

            // Blank endpoint falls back to curated voices
            assertEquals(curatedKokoroVoices, listServiceVoices(options.copy(ttsEndpoint = "")))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun rvcVoiceConverterListsModelsPostProcessesTtsAndRedactsKeyInSnapshots() = runBlocking {
        val rawAudio = encodePcm16Wav(byteArrayOf(1, 0, 2, 0))
        val convertedAudio = encodePcm16Wav(byteArrayOf(9, 0, 8, 0))
        val convertedB64 = Base64.getEncoder().encodeToString(convertedAudio)

        // Disabled RVC is a pass-through
        assertArrayEquals(rawAudio, convertAudioWithRvc(rawAudio, RvcSettings(enabled = false)))

        val calls = CopyOnWriteArrayList<Call>()
        val (server, origin) = startServer { call ->
            calls += call
            when (call.path) {
                "/v1/audio/speech" -> Reply("audio/wav", rawAudio)
                "/api/rvc/models" -> Reply("application/json", """{"models":["rin_v2","kaito"]}""".encodeToByteArray())
                "/api/rvc/process-audio" -> Reply(
                    "application/json",
                    jsonOf("audio_data" to "data:audio/wav;base64,$convertedB64").toString().encodeToByteArray(),
                )
                else -> Reply("text/plain", ByteArray(0), 404)
            }
        }
        try {
            val rvc = RvcSettings(
                enabled = true,
                endpoint = origin,
                apiKey = "rvc-secret",
                modelName = "rin_v2",
                pitchShift = 4,
                indexRate = 0.6f,
                filterRadius = 5,
                f0Method = "rmvpe",
            )
            assertEquals(listOf("rin_v2", "kaito"), listRvcModels(rvc))

            // Chained Kokoro TTS -> RVC post-processing
            val options = VoicePreferences(
                ttsBackend = TtsBackend.KOKORO,
                ttsEndpoint = origin,
                kokoroVoice = "af_heart",
                rvc = rvc,
            )
            val finalAudio = synthesizeBackendAudio(Narration("Test RVC", options, "af_heart"))
            assertArrayEquals(convertedAudio, finalAudio)
            val rvcCall = calls.last()
            assertEquals("/api/rvc/process-audio", rvcCall.path)
            assertEquals("Bearer rvc-secret", rvcCall.auth)
            val payload = JSONObject(rvcCall.body)
            assertEquals("rin_v2", payload.getString("model_name"))
            assertEquals(4, payload.getInt("pitch_offset"))
            assertEquals(0.6, payload.getDouble("index_rate"), 0.01)
            assertEquals(5, payload.getInt("filter_radius"))
            assertEquals(Base64.getEncoder().encodeToString(rawAudio), payload.getString("audio_data"))

            // Snapshot redaction & restore retention for RVC API key
            val prefs = Preferences(voice = options)
            val snap = SettingsSnapshot.capture("RVC", prefs) { if (it.isBlank()) it else "sealed:$it" }
            val portable = snap.portableBytes()
            assertFalse(portable.decodeToString().contains("rvc-secret"))
            val restoredClean = SettingsSnapshot.read(portable).restore(Preferences()) { it }
            assertEquals("", restoredClean.voice.rvc.apiKey)
            val restoredPinned = SettingsSnapshot.read(portable).restore(prefs) { it }
            assertEquals("rvc-secret", restoredPinned.voice.rvc.apiKey)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun localWhisperModelManagementAudioFramingAndGracefulMissingErrorWork() = runBlocking {
        val dir = Files.createTempDirectory("tavern-whisper-test").toFile()
        try {
            val stt = LocalWhisperStt(dir)
            assertFalse(stt.installed())
            assertEquals(3, stt.status().missingParts.size)

            // Graceful error when model weights not installed
            val silencePcm = ByteArray(1600 * 2) // 0.1s @ 16kHz
            val wav16k = encodePcm16Wav(silencePcm, sampleRate = 16_000, channels = 1)
            val missingErr = runCatching { stt.transcribeWav(wav16k, "zh") }.exceptionOrNull()
            assertTrue(missingErr is IllegalStateException)
            assertTrue(missingErr?.message.orEmpty().contains("未安装"))

            // Valid archive import
            val vocabJson = """{"hello":10,"Ġworld":11,"<|startoftranscript|>":50258,"<|zh|>":50260,"<|transcribe|>":50359,"<|notimestamps|>":50363}"""
            val validZip = ByteArrayOutputStream().apply {
                ZipOutputStream(this).use { zip ->
                    zip.putNextEntry(ZipEntry("whisper-tiny/encoder_model_quantized.onnx"))
                    zip.write("fake-encoder-onnx".encodeToByteArray())
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("whisper-tiny/decoder_model_quantized.onnx"))
                    zip.write("fake-decoder-onnx".encodeToByteArray())
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("whisper-tiny/vocab.json"))
                    zip.write(vocabJson.encodeToByteArray())
                    zip.closeEntry()
                }
            }.toByteArray()
            stt.importArchive(ByteArrayInputStream(validZip))
            assertTrue(stt.installed())
            assertTrue(stt.status().totalBytes > 0)

            // Path traversal ZIP is rejected
            val evilZip = ByteArrayOutputStream().apply {
                ZipOutputStream(this).use { zip ->
                    zip.putNextEntry(ZipEntry("../escape.onnx"))
                    zip.write(byteArrayOf(1, 2, 3))
                    zip.closeEntry()
                }
            }.toByteArray()
            assertTrue(runCatching { stt.importArchive(ByteArrayInputStream(evilZip)) }.isFailure)

            // Remove cleans up
            stt.remove()
            assertFalse(stt.installed())
            assertEquals(0L, stt.status().totalBytes)

            // Vocab & BPE decoding
            val vocab = parseWhisperVocabJson(vocabJson)
            val prompt = buildWhisperPromptTokens("zh-CN", vocab)
            assertEquals(listOf(50258, 50260, 50359, 50363), prompt)
            assertEquals("hello world", decodeWhisperTokens(listOf(10, 11, 50257), vocab.idToToken))

            // Audio WAV decode + stereo 48kHz downmix/resample + padding + log-mel spectrogram
            val stereoFrames = 4800 // 0.1s at 48kHz
            val stereoBuf = ByteBuffer.allocate(stereoFrames * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until stereoFrames) {
                val sample = (sin(2.0 * PI * 440.0 * i / 48_000.0) * 16_000.0).toInt().toShort()
                stereoBuf.putShort(sample)
                stereoBuf.putShort(sample)
            }
            val stereoWav = encodePcm16Wav(stereoBuf.array(), sampleRate = 48_000, channels = 2)
            val decoded = decodeWavPcm16(stereoWav)
            assertEquals(1600, decoded.size) // resampled 48k -> 16k
            val padded = padOrTrimAudio(decoded, WHISPER_CHUNK_SAMPLES)
            assertEquals(WHISPER_CHUNK_SAMPLES, padded.size)

            // Compute log-mel on short 1-second slice (100 frames) for speed and verify tone > silence
            val tone = FloatArray(16_000) { i -> (sin(2.0 * PI * 440.0 * i / 16_000.0) * 0.5).toFloat() }
            val mel = computeLogMelSpectrogram(tone)
            assertEquals(WHISPER_N_MELS * 100, mel.size)
            assertTrue(mel.all { it.isFinite() })
            assertTrue("Expected non-constant spectrogram energy for 440Hz tone", mel.max() > mel.min() + 0.5f)
            assertEquals(3_000, WHISPER_FRAMES)
        } finally {
            dir.deleteRecursively()
        }
    }
}
