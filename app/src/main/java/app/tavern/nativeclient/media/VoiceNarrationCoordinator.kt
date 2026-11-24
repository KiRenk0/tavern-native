package app.tavern.nativeclient.media

import android.media.AudioAttributes
import android.media.MediaPlayer
import app.tavern.nativeclient.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private data class QueuedNarration(val chatId: String?, val narration: Narration)

internal class VoiceNarrationCoordinator(private val host: CoordinatorHost) {
    private val speechPlayer = lazy { SpeechPlayback(host.app, ::stopNarration) }
    private val narrationQueue = ArrayDeque<QueuedNarration>()
    private var narrationJob: Job? = null
    private var narrationOwner: QueuedNarration? = null
    private var voiceForeground = true
    private var replySoundPlayer: MediaPlayer? = null

    fun activeNarrationChatId(): String? = narrationOwner?.chatId

    fun close() {
        stopNarration()
        closeReplySound()
        if (speechPlayer.isInitialized()) speechPlayer.value.close()
    }

    fun voiceForeground(visible: Boolean) {
        voiceForeground = visible
        if (!visible) stopNarration()
    }

    fun playReplySound(preview: Boolean = false) {
        val settings = host.current.prefs.chatHandling
        if (!preview && (!settings.replySound || settings.replySoundInBackgroundOnly && voiceForeground)) return
        closeReplySound()
        try {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val player = checkNotNull(MediaPlayer.create(host.app, R.raw.message_sound, attributes, 0))
            replySoundPlayer = player
            player.setVolume(0.8f, 0.8f)
            player.setOnCompletionListener { if (replySoundPlayer === it) closeReplySound() }
            player.setOnErrorListener { failed, _, _ ->
                if (replySoundPlayer === failed) closeReplySound()
                if (preview) host.notice("无法播放提示音")
                true
            }
            player.start()
        } catch (_: Exception) {
            closeReplySound()
            if (preview) host.notice("无法播放提示音")
        }
    }

    private fun closeReplySound() {
        replySoundPlayer?.release()
        replySoundPlayer = null
    }

    fun saveVoice(options: VoicePreferences, done: () -> Unit) = host.work {
        when (options.activeBackend) {
            TtsBackend.SYSTEM -> Unit
            TtsBackend.OPENAI_COMPAT -> {
                require(options.speechProvider(host.current.prefs.providers) != null) {
                    "请选择已保存的自定义语音服务"
                }
                require(options.backendModel.isNotBlank() && options.backendVoice.isNotBlank()) {
                    "请填写语音模型和默认音色"
                }
            }
            TtsBackend.EDGE_TTS -> Unit
            TtsBackend.AZURE -> {
                azureSpeechRoot(options.azureRegion, options.ttsEndpoint)
                require(options.ttsApiKey.isNotBlank()) { "请填写 Azure Speech 订阅密钥" }
                require(options.azureVoice.isNotBlank()) { "请选择或填写 Azure 音色名称" }
            }
            TtsBackend.ALLTALK -> {
                allTalkRoot(options.ttsEndpoint)
                require(options.allTalkVoice.isNotBlank()) { "请选择或填写 AllTalk 音色文件" }
            }
            TtsBackend.KOKORO -> {
                kokoroRoot(options.ttsEndpoint)
                require(options.kokoroVoice.isNotBlank()) { "请选择或填写 Kokoro 音色 ID" }
            }
            TtsBackend.ELEVENLABS -> {
                if (options.ttsEndpoint.isNotBlank()) checkedAddress(options.ttsEndpoint)
                require(options.ttsApiKey.isNotBlank() && options.elevenLabsVoiceId.isNotBlank()) {
                    "请填写 ElevenLabs API Key 与 Voice ID"
                }
            }
            TtsBackend.FISH_AUDIO -> {
                if (options.ttsEndpoint.isNotBlank()) checkedAddress(options.ttsEndpoint)
                require(options.ttsApiKey.isNotBlank()) { "请填写 Fish Audio API Key" }
            }
            TtsBackend.GPT_SOVITS -> {
                require(options.ttsEndpoint.isNotBlank()) { "请填写 GPT-SoVITS 服务地址" }
                checkedAddress(options.ttsEndpoint)
                require(options.gptSovitsRefAudio.isNotBlank()) { "请填写 GPT-SoVITS 参考音频路径" }
            }
            TtsBackend.GEMINI_NATIVE, TtsBackend.NOVELAI -> {
                require(options.speechProvider(host.current.prefs.providers) != null) {
                    "请选择已配置 Key 的 ${options.activeBackend.label} 连接"
                }
                require(options.defaultVoice.isNotBlank()) { "请选择默认音色" }
                if (options.activeBackend == TtsBackend.GEMINI_NATIVE) checkedGeminiTtsModel(options.geminiTtsModel)
            }
            TtsBackend.VITS, TtsBackend.STYLE_BERT_VITS2, TtsBackend.XTTS, TtsBackend.SILERO, TtsBackend.COSYVOICE -> {
                options.selfHostedSpeechRoot()
                options.checkSelfHostedVoice()
                options.readSpeechParameters()
            }
        }
        if (options.sttBackend == SttBackend.WHISPER_API) {
            require(options.sttEndpoint.isNotBlank()) { "请填写 Whisper STT 接口地址" }
            checkedAddress(options.sttEndpoint)
        }
        val validatedRvc = options.rvc.validated()
        if (options.filter.isNotBlank()) {
            val result = regexTest(RegexRule(pattern = options.filter), "朗读测试", RegexNames(null, ""))
            require(result.issue == null) { result.issue ?: "朗读正则无效" }
        }
        if (options.language.isNotBlank()) require(Locale.forLanguageTag(options.language).language.isNotBlank()) {
            "识别语言应使用语言标签，例如 zh-CN"
        }
        stopNarration()
        host.persistPreferences(host.current.prefs.copy(voice = options.copy(rvc = validatedRvc)))
        done()
    }

    suspend fun systemVoices(): List<DeviceVoice> = speechPlayer.value.voices()

    fun previewVoice(options: VoicePreferences, voice: String, line: String) {
        enqueueSpeech(line, null, options, voice, replace = true)
    }

    fun stopNarration() {
        narrationQueue.clear()
        val job = narrationJob
        narrationJob = null
        narrationOwner = null
        job?.cancel()
        if (host.current.speaking) host.mutable.update { it.copy(speaking = false) }
    }

    private fun enqueueSpeech(text: String, chatId: String?, options: VoicePreferences, voice: String, replace: Boolean) {
        if (!voiceForeground || text.isBlank()) return
        if (chatId != null && chatId != host.current.chat?.id) return
        val backend = if (!options.activeBackend.usesProvider) null else {
            options.speechProvider(host.current.prefs.providers)
                ?: return host.notice("请重新选择朗读使用的语音服务")
        }
        if (replace) stopNarration()
        if (narrationQueue.size >= 20) narrationQueue.removeFirst()
        narrationQueue.addLast(QueuedNarration(chatId, Narration(text, options, voice, backend)))
        if (narrationJob?.isActive == true) return
        val job = host.scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (narrationQueue.isNotEmpty()) {
                    val next = narrationQueue.removeFirst()
                    if (next.chatId != null && next.chatId != host.current.chat?.id) continue
                    narrationOwner = next
                    val narration = next.narration
                    val spoken = withContext(Dispatchers.Default) { narration.options.speechText(narration.text) }
                    if (spoken.isNotEmpty()) speechPlayer.value.play(narration.copy(text = spoken))
                }
            } catch (_: TimeoutCancellationException) {
                host.notice("语音服务响应超时")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                host.notice(e.localizedMessage ?: "无法朗读消息")
            } finally {
                if (narrationJob === coroutineContext[Job]) {
                    narrationQueue.clear()
                    narrationJob = null
                    narrationOwner = null
                    host.mutable.update { it.copy(speaking = false) }
                }
            }
        }
        narrationJob = job
        host.mutable.update { it.copy(speaking = true) }
        job.start()
    }

    private fun voiceActor(message: Message): String? = when (message.role) {
        "user" -> (message.personaId ?: host.current.prefs.personas.firstOrNull { it.name == message.name }?.id
            ?: host.current.persona.id).let { "persona:$it" }
        "assistant" -> (message.characterId ?: host.current.replyingCharacter(message)?.id)?.let { "character:$it" }
        else -> null
    }

    fun narrate(message: Message, automatic: Boolean = false) {
        val chat = host.current.chat ?: return
        val options = host.current.prefs.voice
        if (!options.enabled) return
        if (automatic && (!options.automatic || message.role == "user" && !options.includeUser)) return
        val source = if (options.translatedText) message.displayText else message.text
        val text = host.current.regexCard(message)?.let { expand(source, it, host.current.persona.name) } ?: source
        enqueueSpeech(text, chat.id, options, options.actorVoice(voiceActor(message)), replace = !automatic)
    }

    internal fun scriptNarrate(text: String, actor: String?) {
        val options = host.current.prefs.voice
        val chat = host.current.chat ?: return
        if (!options.enabled) return
        val key = if (actor.isNullOrBlank()) host.current.regexCard()?.id?.let { "character:$it" }
        else host.current.cards.firstOrNull { it.name.equals(actor, true) }?.id?.let { "character:$it" }
            ?: host.current.prefs.personas.firstOrNull { it.name.equals(actor, true) }?.id?.let { "persona:$it" }
        require(actor.isNullOrBlank() || key != null) { "找不到指定的朗读角色或人设" }
        enqueueSpeech(text, chat.id, options, options.actorVoice(key), replace = false)
    }
}
