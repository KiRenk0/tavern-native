package app.tavern.nativeclient.media

import app.tavern.nativeclient.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

internal val curatedEdgeVoices = listOf(
    "zh-CN-XiaoxiaoNeural" to "晓晓（中文女声 · 温柔自然）",
    "zh-CN-YunxiNeural" to "云希（中文男声 · 青年叙述）",
    "zh-CN-YunyangNeural" to "云扬（中文男声 · 沉稳旁白）",
    "zh-CN-XiaoyiNeural" to "晓伊（中文女声 · 活泼清亮）",
    "zh-TW-HsiaoChenNeural" to "曉臻（繁中女声）",
    "ja-JP-NanamiNeural" to "Nanami（日语女声）",
    "ja-JP-KeitaNeural" to "Keita（日语男声）",
    "en-US-JennyNeural" to "Jenny（英语女声）",
    "en-US-GuyNeural" to "Guy（英语男声）",
)

internal object EdgeTtsClient {
    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val CHROMIUM_FULL_VERSION = "130.0.2849.68"
    private const val WIN_EPOCH_SECONDS = 11_644_473_600L
    private const val HOST = "speech.platform.bing.com"
    private const val PATH = "/consumer/speech/synthesize/readaloud/edge/v1"
    private const val MAX_AUDIO_BYTES = 16 * 1024 * 1024
    private val rng = SecureRandom()

    fun computeSecMsGec(epochMillis: Long = System.currentTimeMillis()): String {
        var ticks = (epochMillis / 1000L) + WIN_EPOCH_SECONDS
        ticks -= ticks % 300L
        ticks *= 10_000_000L
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$ticks$TRUSTED_CLIENT_TOKEN".toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(Locale.ROOT, it) }
    }

    fun buildEdgeSsml(text: String, voice: String, speed: Float = 1f, pitch: Float = 1f): String {
        val safeVoice = xmlEscape(voice.ifBlank { "zh-CN-XiaoxiaoNeural" })
        val ratePct = ((speed.coerceIn(0.5f, 2f) - 1f) * 100f).roundToInt()
        val pitchHz = ((pitch.coerceIn(0.5f, 2f) - 1f) * 50f).roundToInt()
        val rateAttr = if (ratePct >= 0) "+$ratePct%" else "$ratePct%"
        val pitchAttr = if (pitchHz >= 0) "+${pitchHz}Hz" else "${pitchHz}Hz"
        val body = xmlEscape(text)
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
            "<voice name='$safeVoice'><prosody pitch='$pitchAttr' rate='$rateAttr' volume='+0%'>$body</prosody></voice></speak>"
    }

    fun extractEdgeAudioChunk(frame: ByteArray): ByteArray? {
        if (frame.size < 2) return null
        val headerLength = ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)
        val bodyOffset = 2 + headerLength
        if (bodyOffset > frame.size) return null
        val header = String(frame, 2, headerLength, Charsets.UTF_8)
        return if ("Path:audio" in header) frame.copyOfRange(bodyOffset, frame.size) else null
    }

    private fun xmlEscape(raw: String): String = buildString(raw.length) {
        for (ch in raw) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(ch)
        }
    }

    private fun edgeTimestamp(): String {
        val fmt = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    suspend fun synthesize(text: String, voice: String, speed: Float, pitch: Float): ByteArray =
        suspendCancellableCoroutine { cont ->
            val socketHolder = arrayOfNulls<Socket>(1)
            cont.invokeOnCancellation {
                val s = socketHolder[0] ?: return@invokeOnCancellation
                Dispatchers.IO.dispatch(cont.context) { runCatching { s.close() } }
            }
            Dispatchers.IO.dispatch(cont.context, Runnable {
                if (!cont.isActive) return@Runnable
                var socket: SSLSocket? = null
                try {
                    val raw = Socket()
                    socketHolder[0] = raw
                    raw.connect(InetSocketAddress(HOST, 443), 15_000)
                    raw.soTimeout = 30_000
                    val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, HOST, 443, true) as SSLSocket
                    ssl.startHandshake()
                    socket = ssl
                    socketHolder[0] = ssl

                    val reqId = UUID.randomUUID().toString().replace("-", "")
                    val muid = UUID.randomUUID().toString().replace("-", "").uppercase(Locale.ROOT)
                    val gec = computeSecMsGec()
                    val fullPath = "$PATH?TrustedClientToken=$TRUSTED_CLIENT_TOKEN" +
                        "&Sec-MS-GEC=${URLEncoder.encode(gec, "UTF-8")}" +
                        "&Sec-MS-GEC-Version=${URLEncoder.encode("1-$CHROMIUM_FULL_VERSION", "UTF-8")}" +
                        "&ConnectionId=$reqId"

                    val keyBytes = ByteArray(16).also(rng::nextBytes)
                    val wsKey = Base64.getEncoder().encodeToString(keyBytes)

                    val out = ssl.outputStream
                    val input = ssl.inputStream
                    val handshake = buildString {
                        append("GET $fullPath HTTP/1.1\r\n")
                        append("Host: $HOST\r\n")
                        append("Upgrade: websocket\r\n")
                        append("Connection: Upgrade\r\n")
                        append("Sec-WebSocket-Key: $wsKey\r\n")
                        append("Sec-WebSocket-Version: 13\r\n")
                        append("Origin: chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold\r\n")
                        append("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Edg/${CHROMIUM_FULL_VERSION.substringBefore('.')}.0.0.0\r\n")
                        append("Cookie: muid=$muid;\r\n\r\n")
                    }
                    out.write(handshake.toByteArray(Charsets.US_ASCII))
                    out.flush()

                    val statusLine = readHttpLine(input)
                    check(statusLine.startsWith("HTTP/1.1 101")) { "Edge TTS 握手失败 · $statusLine" }
                    while (true) {
                        val line = readHttpLine(input)
                        if (line.isEmpty()) break
                    }

                    val ts = edgeTimestamp()
                    val configMsg = "X-Timestamp:$ts\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
                        """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}"""
                    writeTextFrame(out, configMsg)

                    val ssml = buildEdgeSsml(text, voice, speed, pitch)
                    val ssmlMsg = "X-RequestId:$reqId\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:$ts\r\nPath:ssml\r\n\r\n$ssml"
                    writeTextFrame(out, ssmlMsg)
                    out.flush()

                    val audioOut = ByteArrayOutputStream()
                    val fragmentBuffer = ByteArrayOutputStream()
                    var fragmentOpcode = -1
                    while (cont.isActive) {
                        val frame = readWsFrame(input) ?: break
                        val effectiveOpcode = if (frame.opcode == 0 && fragmentOpcode != -1) fragmentOpcode else frame.opcode
                        if (!frame.fin) {
                            if (frame.opcode != 0) fragmentOpcode = frame.opcode
                            fragmentBuffer.write(frame.payload)
                            continue
                        }
                        val payload = if (fragmentOpcode != -1 && frame.opcode == 0) {
                            fragmentBuffer.write(frame.payload)
                            fragmentBuffer.toByteArray().also {
                                fragmentBuffer.reset()
                                fragmentOpcode = -1
                            }
                        } else frame.payload

                        when (effectiveOpcode) {
                            0x1 -> {
                                val msg = payload.toString(Charsets.UTF_8)
                                if ("Path:turn.end" in msg) break
                            }
                            0x2 -> {
                                extractEdgeAudioChunk(payload)?.let { chunk ->
                                    check(audioOut.size() + chunk.size <= MAX_AUDIO_BYTES) { "Edge TTS 音频超过 16 MB 限制" }
                                    audioOut.write(chunk)
                                }
                            }
                            0x8 -> break
                            0x9 -> {
                                writeFrame(out, 0xA, payload)
                                out.flush()
                            }
                        }
                    }
                    runCatching { writeFrame(out, 0x8, ByteArray(0)); out.flush() }
                    val bytes = audioOut.toByteArray()
                    check(bytes.isNotEmpty()) { "Edge TTS 未返回音频数据" }
                    if (cont.isActive) cont.resume(bytes)
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWithException(e)
                } finally {
                    socketHolder[0] = null
                    runCatching { socket?.close() }
                }
            })
        }

    private fun readHttpLine(input: InputStream): String {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("Edge TTS 连接意外断开")
            if (b == '\n'.code) {
                val bytes = buf.toByteArray()
                val len = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, len, Charsets.US_ASCII)
            }
            if (buf.size() < 8192) buf.write(b)
        }
    }

    private fun writeTextFrame(out: OutputStream, text: String) {
        writeFrame(out, 0x1, text.toByteArray(Charsets.UTF_8))
    }

    private fun writeFrame(out: OutputStream, opcode: Int, payload: ByteArray) {
        out.write(0x80 or (opcode and 0x0F))
        val len = payload.size
        when {
            len <= 125 -> out.write(0x80 or len)
            len <= 65_535 -> {
                out.write(0x80 or 126)
                out.write((len ushr 8) and 0xFF)
                out.write(len and 0xFF)
            }
            else -> {
                out.write(0x80 or 127)
                val longLen = len.toLong()
                for (shift in 56 downTo 0 step 8) out.write(((longLen ushr shift) and 0xFFL).toInt())
            }
        }
        val mask = ByteArray(4).also(rng::nextBytes)
        out.write(mask)
        val masked = ByteArray(len)
        for (i in 0 until len) masked[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
        out.write(masked)
    }

    private data class WsFrame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    private fun readWsFrame(input: InputStream): WsFrame? {
        val b0 = input.read()
        if (b0 < 0) return null
        val b1 = input.read()
        if (b1 < 0) throw EOFException("WebSocket 帧头不完整")
        val fin = (b0 and 0x80) != 0
        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        var length = (b1 and 0x7F).toLong()
        if (length == 126L) {
            length = ((readByte(input) shl 8) or readByte(input)).toLong()
        } else if (length == 127L) {
            length = 0L
            repeat(8) { length = (length shl 8) or readByte(input).toLong() }
        }
        require(length in 0L..MAX_AUDIO_BYTES.toLong()) { "WebSocket 帧过大" }
        val mask = if (masked) readFully(input, 4) else null
        val data = readFully(input, length.toInt())
        if (mask != null) {
            for (i in data.indices) data[i] = (data[i].toInt() xor mask[i and 3].toInt()).toByte()
        }
        return WsFrame(fin, opcode, data)
    }

    private fun readByte(input: InputStream): Int {
        val v = input.read()
        if (v < 0) throw EOFException("WebSocket 数据流结束")
        return v
    }

    private fun readFully(input: InputStream, size: Int): ByteArray {
        val buf = ByteArray(size)
        var off = 0
        while (off < size) {
            val n = input.read(buf, off, size - off)
            if (n < 0) throw EOFException("WebSocket 数据提前结束")
            off += n
        }
        return buf
    }
}

internal suspend fun synthesizeBackendAudio(narration: Narration): ByteArray {
    val options = narration.options
    val voice = narration.voice.ifBlank { options.defaultVoice }
    val synthesized = when (options.activeBackend) {
        TtsBackend.SYSTEM -> error("系统朗读不使用音频下载管线")
        TtsBackend.OPENAI_COMPAT -> {
            val backend = requireNotNull(narration.backend) { "请选择 OpenAI 兼容语音服务" }
            synthesizeSpeech(backend, options.backendModel, voice, narration.text, options.speed)
        }
        TtsBackend.EDGE_TTS -> EdgeTtsClient.synthesize(narration.text, voice, options.speed, options.pitch)
        TtsBackend.AZURE -> synthesizeAzureSpeech(options, voice, narration.text)
        TtsBackend.ELEVENLABS -> synthesizeElevenLabs(options, voice, narration.text)
        TtsBackend.FISH_AUDIO -> synthesizeFishAudio(options, voice, narration.text)
        TtsBackend.GPT_SOVITS -> synthesizeGptSovits(options, voice, narration.text)
        TtsBackend.ALLTALK -> synthesizeAllTalk(options, voice, narration.text)
        TtsBackend.KOKORO -> synthesizeKokoro(options, voice, narration.text)
        TtsBackend.GEMINI_NATIVE -> synthesizeGeminiVoice(requireNotNull(narration.backend), options, voice, narration.text)
        TtsBackend.NOVELAI -> synthesizeNovelAiVoice(requireNotNull(narration.backend), voice, narration.text)
        TtsBackend.VITS, TtsBackend.STYLE_BERT_VITS2, TtsBackend.XTTS, TtsBackend.SILERO, TtsBackend.COSYVOICE ->
            synthesizeSelfHostedVoice(options, voice, narration.text)
    }
    return if (options.rvc.enabled) convertAudioWithRvc(synthesized, options.rvc) else synthesized
}

internal suspend fun synthesizeElevenLabs(options: VoicePreferences, voiceId: String, text: String): ByteArray {
    val key = options.ttsApiKey.trim()
    require(key.isNotBlank()) { "请填写 ElevenLabs API Key" }
    val targetVoice = voiceId.trim().ifBlank { options.elevenLabsVoiceId.trim() }
    require(targetVoice.isNotBlank()) { "请填写 ElevenLabs Voice ID" }
    val base = elevenLabsTtsRoot(options.ttsEndpoint)
    val encodedVoice = URLEncoder.encode(targetVoice, "UTF-8")
    val body = jsonOf(
        "text" to text,
        "model_id" to options.elevenLabsModelId.trim().ifBlank { "eleven_multilingual_v2" },
        "voice_settings" to jsonOf("stability" to 0.5, "similarity_boost" to 0.75, "speed" to options.speed),
    )
    return request(
        "$base/v1/text-to-speech/$encodedVoice",
        body = body,
        headers = mapOf("xi-api-key" to key, "Accept" to "audio/mpeg"),
        redactError = { it.redactValue(key) },
    ) { input, type ->
        check(!type.startsWith("application/json", true)) { "ElevenLabs 返回了非音频响应" }
        input.readLimited(12 * 1024 * 1024).also { check(it.isNotEmpty()) { "ElevenLabs 未返回音频" } }
    }
}

internal suspend fun synthesizeFishAudio(options: VoicePreferences, referenceId: String, text: String): ByteArray {
    val key = options.ttsApiKey.trim()
    require(key.isNotBlank()) { "请填写 Fish Audio API Key" }
    val ref = referenceId.trim().ifBlank { options.fishReferenceId.trim() }
    val base = options.ttsEndpoint.trim().ifBlank { "https://api.fish.audio" }
        .let { checkedAddress(it).toString().trimEnd('/') }
        .removeSuffix("/v1/tts")
        .removeSuffix("/v1")
    val body = jsonOf("text" to text, "format" to "mp3").apply {
        if (ref.isNotBlank()) put("reference_id", ref)
    }
    return request(
        "$base/v1/tts",
        body = body,
        headers = mapOf("Authorization" to "Bearer $key", "Accept" to "audio/mpeg"),
    ) { input, type ->
        check(!type.startsWith("application/json", true)) { "Fish Audio 返回了非音频响应" }
        input.readLimited(12 * 1024 * 1024).also { check(it.isNotEmpty()) { "Fish Audio 未返回音频" } }
    }
}

internal suspend fun synthesizeGptSovits(options: VoicePreferences, refOverride: String, text: String): ByteArray {
    val endpoint = options.ttsEndpoint.trim()
    require(endpoint.isNotBlank()) { "请填写 GPT-SoVITS 服务地址" }
    val refAudio = refOverride.trim().ifBlank { options.gptSovitsRefAudio.trim() }
    require(refAudio.isNotBlank()) { "请填写 GPT-SoVITS 参考音频路径（ref_audio_path）" }
    val base = checkedAddress(endpoint).toString().trimEnd('/').removeSuffix("/tts")
    val body = jsonOf(
        "text" to text,
        "text_lang" to options.gptSovitsTextLang.trim().ifBlank { "auto" },
        "ref_audio_path" to refAudio,
        "prompt_text" to options.gptSovitsPromptText.trim(),
        "prompt_lang" to options.gptSovitsPromptLang.trim().ifBlank { "zh" },
        "media_type" to "wav",
        "speed_factor" to options.speed,
    )
    val headers = buildMap {
        put("Accept", "audio/wav, audio/mpeg")
        val key = options.ttsApiKey.trim()
        if (key.isNotBlank()) put("Authorization", "Bearer $key")
    }
    return request("$base/tts", body = body, headers = headers) { input, type ->
        check(!type.startsWith("application/json", true)) { "GPT-SoVITS 返回了非音频响应" }
        input.readLimited(16 * 1024 * 1024).also { check(it.isNotEmpty()) { "GPT-SoVITS 未返回音频" } }
    }
}
