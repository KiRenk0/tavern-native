package app.tavern.nativeclient

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.tavern.nativeclient.media.DEFAULT_ALLTALK_LANGUAGE
import app.tavern.nativeclient.media.DEFAULT_AZURE_REGION
import app.tavern.nativeclient.media.DEFAULT_AZURE_VOICE
import app.tavern.nativeclient.media.DEFAULT_KOKORO_VOICE
import app.tavern.nativeclient.media.LocalWhisperStt
import app.tavern.nativeclient.media.ServiceVoice
import app.tavern.nativeclient.media.allTalkLanguages
import app.tavern.nativeclient.media.curatedEdgeVoices
import app.tavern.nativeclient.media.curatedKokoroVoices
import app.tavern.nativeclient.media.geminiTtsModels
import app.tavern.nativeclient.media.hasVoiceCatalog
import app.tavern.nativeclient.media.listRvcModels
import app.tavern.nativeclient.media.listServiceVoices
import app.tavern.nativeclient.media.localWhisperDir
import app.tavern.nativeclient.media.rvcF0Methods
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

private val voiceSaver = Saver<VoicePreferences, String>(save = { it.json().toString() }, restore = { VoicePreferences.from(JSONObject(it)) })
private const val SAMPLE = "你好，欢迎来到酒馆。"

@Composable
internal fun VoiceSettings(state: TavernState, model: TavernViewModel, close: () -> Unit) {
    val current = state.prefs.voice
    var options by rememberSaveable(current, stateSaver = voiceSaver) { mutableStateOf(current) }
    var shortcutRows by rememberSaveable(current.shortcuts) { mutableStateOf(current.shortcuts.entries.joinToString("\n") { "${it.key} = ${it.value}" }) }
    var voiceTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var actor by rememberSaveable { mutableStateOf("") }
    val allProviders = state.prefs.providers
    val providers = allProviders.filter(TtsBackend.OPENAI_COMPAT::accepts)
    val people = listOf("" to "使用默认音色") + state.cards.map { "character:${it.id}" to "角色 · ${it.name}" } +
        state.prefs.personas.map { "persona:${it.id}" to "人设 · ${it.name}" }
    val activeTts = options.activeBackend
    val shortcutPairs = shortcutRows.lineSequence().map(String::trim).filter(String::isNotBlank).map { line ->
        line.substringBefore('=', "").trim() to line.substringAfter('=', "").trim()
    }.toList()
    val shortcutsValid = shortcutPairs.all { (phrase, replacement) -> phrase.isNotBlank() && replacement.isNotBlank() } &&
        shortcutPairs.distinctBy { it.first.lowercase(Locale.ROOT) }.size == shortcutPairs.size
    val enabled = !state.busy
    fun leave() { model.stopNarration(); close() }
    BackHandler(onBack = ::leave)
    Form {
        FormHeading("语音朗读")
        GroupToggle("启用朗读", options.enabled, enabled) { options = options.copy(enabled = it) }
        GroupToggle("自动朗读新回复", options.automatic, enabled && options.enabled) { options = options.copy(automatic = it) }
        GroupToggle("也朗读我发送的消息", options.includeUser, enabled && options.enabled && options.automatic) { options = options.copy(includeUser = it) }
        SelectField("朗读引擎", activeTts, TtsBackend.entries, TtsBackend::label, enabled) { next ->
            if (next != activeTts) model.stopNarration()
            options = options.selectTtsBackend(next)
            if (next == TtsBackend.OPENAI_COMPAT && options.backendId.isBlank()) {
                options = options.copy(backendId = providers.firstOrNull()?.id.orEmpty())
            }
        }
        when (activeTts) {
            TtsBackend.SYSTEM -> {
                OutlinedButton({ voiceTarget = "" }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text("默认音色 · ${options.systemVoice.ifBlank { "系统默认" }}")
                }
            }
            TtsBackend.OPENAI_COMPAT -> {
                SelectField("自定义语音服务", options.backendId, providers.map(Provider::id), {
                    providers.firstOrNull { provider -> provider.id == it }?.name ?: "请先添加自定义提供商"
                }, enabled && providers.isNotEmpty()) { options = options.copy(backendId = it) }
                Field("语音模型 ID", options.backendModel, { options = options.copy(backendModel = it) }, enabled = enabled)
                Field("默认音色 ID", options.backendVoice, { options = options.copy(backendVoice = it) }, enabled = enabled)
                Text("聊天文本会发送到选定的 OpenAI 兼容语音服务。", style = MaterialTheme.typography.bodySmall)
            }
            TtsBackend.EDGE_TTS -> {
                SelectField(
                    "预设神经音色",
                    options.edgeVoice,
                    (curatedEdgeVoices.map { it.first } + options.edgeVoice).distinct(),
                    { id -> curatedEdgeVoices.firstOrNull { it.first == id }?.second ?: id },
                    enabled,
                ) { options = options.copy(edgeVoice = it) }
                Field("自定义音色名称（ShortName）", options.edgeVoice, { options = options.copy(edgeVoice = it) }, enabled = enabled)
                Text("免 API Key，通过微软 Edge 语音 WebSocket 合成神经语音。", style = MaterialTheme.typography.bodySmall)
            }
            TtsBackend.AZURE -> {
                Field("Azure 区域代码", options.azureRegion, { options = options.copy(azureRegion = it) }, enabled = enabled,
                    placeholder = DEFAULT_AZURE_REGION)
                Field("自定义终结点（可选，留空按区域生成）", options.ttsEndpoint, { options = options.copy(ttsEndpoint = it) },
                    enabled = enabled, type = KeyboardType.Uri, placeholder = "https://eastus.tts.speech.microsoft.com")
                Field("Azure Speech 订阅密钥", options.ttsApiKey, { options = options.copy(ttsApiKey = it) }, secret = true, enabled = enabled)
                Field("默认音色名称（ShortName）", options.azureVoice, { options = options.copy(azureVoice = it) }, enabled = enabled,
                    placeholder = DEFAULT_AZURE_VOICE)
                OutlinedButton({ voiceTarget = "" }, enabled = enabled && options.ttsApiKey.isNotBlank()) {
                    Text("读取并选择 Azure 音色")
                }
            }
            TtsBackend.ALLTALK -> {
                Field("AllTalk 服务地址", options.ttsEndpoint, { options = options.copy(ttsEndpoint = it) },
                    enabled = enabled, type = KeyboardType.Uri, placeholder = "http://127.0.0.1:7851")
                Field("API Key（如无鉴权可留空）", options.ttsApiKey, { options = options.copy(ttsApiKey = it) }, secret = true, enabled = enabled)
                Field("默认音色文件（如 female_01.wav）", options.allTalkVoice, { options = options.copy(allTalkVoice = it) }, enabled = enabled)
                SelectField("朗读语言", options.allTalkLanguage, (allTalkLanguages + options.allTalkLanguage).distinct(), { it }, enabled) {
                    options = options.copy(allTalkLanguage = it)
                }
                OutlinedButton({ voiceTarget = "" }, enabled = enabled && options.ttsEndpoint.isNotBlank()) {
                    Text("读取并选择 AllTalk 音色")
                }
            }
            TtsBackend.KOKORO -> {
                Field("Kokoro TTS 服务地址", options.ttsEndpoint, { options = options.copy(ttsEndpoint = it) },
                    enabled = enabled, type = KeyboardType.Uri, placeholder = "http://127.0.0.1:8880")
                Field("API Key（如无鉴权可留空）", options.ttsApiKey, { options = options.copy(ttsApiKey = it) }, secret = true, enabled = enabled)
                SelectField(
                    "预设音色",
                    options.kokoroVoice,
                    (curatedKokoroVoices.map { it.id } + options.kokoroVoice).distinct(),
                    { id -> curatedKokoroVoices.firstOrNull { it.id == id }?.name ?: id },
                    enabled,
                ) { options = options.copy(kokoroVoice = it) }
                Field("音色 ID（支持混音如 af_heart+af_bella）", options.kokoroVoice, { options = options.copy(kokoroVoice = it) }, enabled = enabled)
                OutlinedButton({ voiceTarget = "" }, enabled = enabled) {
                    Text("读取并选择 Kokoro 音色")
                }
            }
            TtsBackend.ELEVENLABS -> {
                Field("API 地址（留空默认官方）", options.ttsEndpoint, { options = options.copy(ttsEndpoint = it) }, enabled = enabled,
                    placeholder = "https://api.elevenlabs.io")
                Field("ElevenLabs API Key", options.ttsApiKey, { options = options.copy(ttsApiKey = it) }, secret = true, enabled = enabled)
                Field("Voice ID", options.elevenLabsVoiceId, { options = options.copy(elevenLabsVoiceId = it) }, enabled = enabled)
                Field("Model ID", options.elevenLabsModelId, { options = options.copy(elevenLabsModelId = it) }, enabled = enabled)
                OutlinedButton({ voiceTarget = "" }, enabled = enabled && options.ttsApiKey.isNotBlank()) {
                    Text("从账号音色中选择")
                }
            }
            TtsBackend.FISH_AUDIO -> {
                Field("API 地址（留空默认官方）", options.ttsEndpoint, { options = options.copy(ttsEndpoint = it) }, enabled = enabled,
                    placeholder = "https://api.fish.audio")
                Field("Fish Audio API Key", options.ttsApiKey, { options = options.copy(ttsApiKey = it) }, secret = true, enabled = enabled)
                Field("Reference ID（音色模型 ID）", options.fishReferenceId, { options = options.copy(fishReferenceId = it) }, enabled = enabled)
            }
            TtsBackend.GPT_SOVITS -> {
                Field("GPT-SoVITS 服务地址", options.ttsEndpoint, { options = options.copy(ttsEndpoint = it) }, enabled = enabled,
                    placeholder = "http://192.168.1.20:9880")
                Field("API Key（如无鉴权可留空）", options.ttsApiKey, { options = options.copy(ttsApiKey = it) }, secret = true, enabled = enabled)
                Field("参考音频路径（ref_audio_path）", options.gptSovitsRefAudio, { options = options.copy(gptSovitsRefAudio = it) }, enabled = enabled)
                Field("参考文本（prompt_text）", options.gptSovitsPromptText, { options = options.copy(gptSovitsPromptText = it) }, enabled = enabled)
                Field("参考语言 / 合成语言", "${options.gptSovitsPromptLang}/${options.gptSovitsTextLang}", { pair ->
                    val parts = pair.split('/', limit = 2)
                    options = options.copy(
                        gptSovitsPromptLang = parts.getOrElse(0) { "zh" }.trim(),
                        gptSovitsTextLang = parts.getOrElse(1) { "auto" }.trim(),
                    )
                }, enabled = enabled)
            }
            TtsBackend.GEMINI_NATIVE -> {
                SpeechProviderField(options, allProviders, options.geminiTtsProvider, enabled) {
                    options = options.copy(geminiTtsProvider = it)
                }
                SelectField("语音模型", options.geminiTtsModel,
                    (geminiTtsModels + options.geminiTtsModel).distinct(), { it }, enabled) {
                    options = options.copy(geminiTtsModel = it)
                }
                Field("自定义语音模型 ID", options.geminiTtsModel, { options = options.copy(geminiTtsModel = it) }, enabled = enabled)
                OutlinedButton({ voiceTarget = "" }, enabled = enabled && options.speechProvider(allProviders) != null) {
                    Text("默认音色 · ${options.defaultVoice}")
                }
                Field("朗读风格（可选）", options.geminiTtsStyle, { options = options.copy(geminiTtsStyle = it.take(1000)) },
                    lines = 2, enabled = enabled, placeholder = "例如轻声、语速平缓，像在讲睡前故事")
                Text("使用所选 Google 连接的 Key 合成语音。", style = MaterialTheme.typography.bodySmall)
            }
            TtsBackend.NOVELAI -> {
                SpeechProviderField(options, allProviders, options.novelAiTtsProvider, enabled) {
                    options = options.copy(novelAiTtsProvider = it)
                }
                OutlinedButton({ voiceTarget = "" }, enabled = enabled && options.speechProvider(allProviders) != null) {
                    Text("从 NovelAI 音色中选择")
                }
                Field("默认音色种子", options.novelAiTtsVoice, { options = options.copy(novelAiTtsVoice = it.take(250)) },
                    enabled = enabled, placeholder = DEFAULT_NOVELAI_TTS_VOICE)
            }
            TtsBackend.VITS, TtsBackend.STYLE_BERT_VITS2, TtsBackend.XTTS, TtsBackend.SILERO, TtsBackend.COSYVOICE ->
                SelfHostedSpeechFields(options, enabled, { voiceTarget = "" }) { options = it }
        }
        if (people.size > 1) {
            SelectField("角色与人设音色", actor, people.map { it.first }, { id -> people.firstOrNull { it.first == id }?.second ?: "使用默认音色" }, enabled) { actor = it }
            if (actor.isNotBlank()) {
                val override = options.voices["${options.backendScopeKey}:$actor"].orEmpty()
                if (activeTts == TtsBackend.SYSTEM) {
                    OutlinedButton({ voiceTarget = actor }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                        Text(override.ifBlank { "使用默认音色" })
                    }
                } else {
                    Field("专属音色 ID", override, { options = options.withVoice(actor, it) }, enabled = enabled,
                        placeholder = "留空使用默认音色")
                    if (activeTts.hasVoiceCatalog && canUseServiceVoiceCatalog(options, allProviders)) {
                        OutlinedButton({ voiceTarget = actor }, enabled = enabled) { Text("选择专属音色") }
                    }
                }
            }
        }
        val selectedVoice = options.actorVoice(actor.takeIf(String::isNotBlank))
        val canPreview = when (activeTts) {
            TtsBackend.SYSTEM, TtsBackend.EDGE_TTS -> true
            TtsBackend.AZURE -> options.ttsApiKey.isNotBlank() && selectedVoice.isNotBlank()
            TtsBackend.ALLTALK -> options.ttsEndpoint.isNotBlank() && selectedVoice.isNotBlank()
            TtsBackend.KOKORO -> options.ttsEndpoint.isNotBlank() && selectedVoice.isNotBlank()
            TtsBackend.OPENAI_COMPAT -> options.backendId.isNotBlank() && options.backendModel.isNotBlank() && options.backendVoice.isNotBlank()
            TtsBackend.ELEVENLABS -> options.ttsApiKey.isNotBlank() && options.elevenLabsVoiceId.isNotBlank()
            TtsBackend.FISH_AUDIO -> options.ttsApiKey.isNotBlank()
            TtsBackend.GPT_SOVITS -> options.ttsEndpoint.isNotBlank() && options.gptSovitsRefAudio.isNotBlank()
            TtsBackend.GEMINI_NATIVE, TtsBackend.NOVELAI -> options.speechProvider(allProviders) != null
            TtsBackend.VITS, TtsBackend.STYLE_BERT_VITS2, TtsBackend.XTTS, TtsBackend.SILERO, TtsBackend.COSYVOICE ->
                options.ttsEndpoint.isNotBlank() && selectedVoice.isNotBlank()
        }
        val playing = state.speaking
        OutlinedButton({
            if (playing) model.stopNarration() else model.previewVoice(options, selectedVoice, options.voiceSample(selectedVoice))
        }, enabled = enabled && canPreview) {
            Icon(if (playing) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.VolumeUp, null)
            Spacer(Modifier.width(8.dp))
            Text(if (playing) "停止试听" else "试听选定音色")
        }
        if (!activeTts.selfHosted && activeTts !in setOf(TtsBackend.GEMINI_NATIVE, TtsBackend.NOVELAI, TtsBackend.ALLTALK)) {
            VoiceSlider("语速", options.speed, enabled) { options = options.copy(speed = it) }
        }
        if (activeTts in setOf(TtsBackend.SYSTEM, TtsBackend.EDGE_TTS, TtsBackend.AZURE)) {
            VoiceSlider("音高", options.pitch, enabled) { options = options.copy(pitch = it) }
        }
        if (activeTts != TtsBackend.SYSTEM) {
            HorizontalDivider()
            RvcSettingsSection(options.rvc, enabled) { options = options.copy(rvc = it) }
        }
        GroupToggle("有引号时只读引号中的文字", options.quoteOnly, enabled) { options = options.copy(quoteOnly = it) }
        GroupToggle("跳过星号内的动作描写", options.skipActions, enabled) { options = options.copy(skipActions = it) }
        GroupToggle("跳过代码块", options.skipCode, enabled) { options = options.copy(skipCode = it) }
        GroupToggle("有译文时优先朗读译文", options.translatedText, enabled) { options = options.copy(translatedText = it) }
        GroupToggle("跳过成对标签内的文字", options.skipTags, enabled) { options = options.copy(skipTags = it) }
        Field("略读内容的正则（可选）", options.filter, { options = options.copy(filter = it) }, enabled = enabled)
        HorizontalDivider()
        FormHeading("语音输入")
        GroupToggle("显示麦克风按钮", options.dictationEnabled, enabled) { options = options.copy(dictationEnabled = it) }
        SelectField("识别引擎", options.sttBackend, SttBackend.entries, SttBackend::label, enabled) {
            options = options.copy(sttBackend = it)
        }
        if (options.sttBackend == SttBackend.WHISPER_API) {
            Field("Whisper 接口基址", options.sttEndpoint, { options = options.copy(sttEndpoint = it) }, enabled = enabled,
                placeholder = "https://api.openai.com/v1")
            Field("API Key（本地服务可留空）", options.sttApiKey, { options = options.copy(sttApiKey = it) }, secret = true, enabled = enabled)
            Field("模型名", options.sttModel, { options = options.copy(sttModel = it) }, enabled = enabled,
                placeholder = "whisper-1")
        }
        if (options.sttBackend == SttBackend.WHISPER_LOCAL) {
            LocalWhisperModelSection(enabled)
        }
        SelectField("识别结果", options.dictationMode, DictationMode.entries, DictationMode::label, enabled) { options = options.copy(dictationMode = it) }
        Field("识别语言标签（可选）", options.language, { options = options.copy(language = it) },
            placeholder = "留空随系统，例如 zh-CN", enabled = enabled)
        if (options.sttBackend == SttBackend.SYSTEM) {
            GroupToggle("仅使用设备上的离线识别", options.requireOffline, enabled) { options = options.copy(requireOffline = it) }
            Text("系统识别可联网。离线模式需要 Android 12 和已安装的语言包。", style = MaterialTheme.typography.bodySmall)
        }
        Field("语音快捷词（每行一条）", shortcutRows, { shortcutRows = it }, lines = 3, enabled = enabled,
            placeholder = "说出的短语 = 预设文字或斜杠命令")
        if (!shortcutsValid) Text("请用等号分隔短语和替换内容，并避免重复的短语。", color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(::leave, enabled = enabled) { Text("取消") }
            Button({
                model.saveVoice(
                    options.copy(
                        language = options.language.trim(),
                        filter = options.filter.trim(),
                        backendModel = options.backendModel.trim(),
                        backendVoice = options.backendVoice.trim(),
                        geminiTtsModel = options.geminiTtsModel.trim().ifBlank { DEFAULT_GEMINI_TTS_MODEL },
                        geminiTtsStyle = options.geminiTtsStyle.trim(),
                        novelAiTtsVoice = options.novelAiTtsVoice.trim().ifBlank { DEFAULT_NOVELAI_TTS_VOICE },
                        ttsEndpoint = options.ttsEndpoint.trim(),
                        ttsApiKey = options.ttsApiKey.trim(),
                        edgeVoice = options.edgeVoice.trim().ifBlank { "zh-CN-XiaoxiaoNeural" },
                        elevenLabsVoiceId = options.elevenLabsVoiceId.trim(),
                        elevenLabsModelId = options.elevenLabsModelId.trim().ifBlank { "eleven_multilingual_v2" },
                        fishReferenceId = options.fishReferenceId.trim(),
                        gptSovitsRefAudio = options.gptSovitsRefAudio.trim(),
                        gptSovitsPromptText = options.gptSovitsPromptText.trim(),
                        sttEndpoint = options.sttEndpoint.trim(),
                        sttApiKey = options.sttApiKey.trim(),
                        sttModel = options.sttModel.trim().ifBlank { "whisper-1" },
                        azureRegion = options.azureRegion.trim().ifBlank { DEFAULT_AZURE_REGION },
                        azureVoice = options.azureVoice.trim().ifBlank { DEFAULT_AZURE_VOICE },
                        allTalkVoice = options.allTalkVoice.trim(),
                        allTalkLanguage = options.allTalkLanguage.trim().ifBlank { DEFAULT_ALLTALK_LANGUAGE },
                        kokoroVoice = options.kokoroVoice.trim().ifBlank { DEFAULT_KOKORO_VOICE },
                        shortcuts = shortcutPairs.toMap(),
                    ),
                    ::leave,
                )
            }, enabled = enabled && shortcutsValid) { Text("保存语音设置") }
        }
    }
    voiceTarget?.let { target ->
        val select: (String) -> Unit = { voice ->
            options = if (target.isNotEmpty()) options.withVoice(target, voice) else when (activeTts) {
                TtsBackend.SYSTEM -> options.copy(systemVoice = voice)
                TtsBackend.AZURE -> options.copy(azureVoice = voice)
                TtsBackend.ALLTALK -> options.copy(allTalkVoice = voice)
                TtsBackend.KOKORO -> options.copy(kokoroVoice = voice)
                TtsBackend.GEMINI_NATIVE -> options.copy(geminiTtsVoice = voice)
                TtsBackend.NOVELAI -> options.copy(novelAiTtsVoice = voice)
                TtsBackend.ELEVENLABS -> options.copy(elevenLabsVoiceId = voice)
                TtsBackend.VITS, TtsBackend.STYLE_BERT_VITS2, TtsBackend.XTTS, TtsBackend.SILERO, TtsBackend.COSYVOICE ->
                    options.withSelfHostedVoice(options.selfHostedVoice.copy(voice = voice))
                else -> options
            }
            voiceTarget = null
        }
        if (activeTts == TtsBackend.SYSTEM) {
            SystemVoicePicker(model, options, target, state.speaking, { voiceTarget = null }, select)
        } else {
            ServiceVoicePicker(model, options, target, state.speaking, { voiceTarget = null }, select)
        }
    }
}

@Composable
private fun SelfHostedSpeechFields(options: VoicePreferences, enabled: Boolean, pick: () -> Unit, change: (VoicePreferences) -> Unit) {
    val backend = options.activeBackend
    val voice = options.selfHostedVoice
    val parameters = backend.speechParameters
    val suffix = if (backend == TtsBackend.SILERO) "/tts" else ""
    var expanded by rememberSaveable(backend) { mutableStateOf(false) }
    Field("自建语音服务地址", options.ttsEndpoint, { change(options.copy(ttsEndpoint = it)) },
        enabled = enabled, type = KeyboardType.Uri, placeholder = "http://192.168.1.20:${backend.localPort}$suffix")
    Field("API Key（如无鉴权可留空）", options.ttsApiKey, { change(options.copy(ttsApiKey = it)) }, secret = true, enabled = enabled)
    Field("默认音色 ID", voice.voice, { change(options.withSelfHostedVoice(voice.copy(voice = it.take(500)))) }, enabled = enabled)
    OutlinedButton(pick, enabled = enabled && options.ttsEndpoint.isNotBlank()) { Text("读取并选择音色") }
    if (parameters.isNotEmpty()) {
        TextButton({ expanded = !expanded }, enabled = enabled) { Text(if (expanded) "收起合成参数" else "合成参数") }
        if (expanded) {
            if (backend == TtsBackend.XTTS) {
                Text("采样参数会应用到 XTTS 服务。", style = MaterialTheme.typography.bodySmall)
            }
            parameters.forEach { parameter ->
                val value = parameter.raw(voice)
                val update: (String) -> Unit = { change(options.withSelfHostedVoice(voice.withParameter(parameter, it))) }
                when {
                    parameter.default is Boolean -> GroupToggle(parameter.label, value.toBooleanStrictOrNull() ?: parameter.default,
                        enabled) { update(it.toString()) }
                    parameter.choices.isNotEmpty() -> SelectField(parameter.label, value, parameter.choices, { it }, enabled, update)
                    else -> {
                        val keyboard = when (parameter.default) {
                            is Int -> KeyboardType.Number
                            is Double -> KeyboardType.Decimal
                            else -> KeyboardType.Text
                        }
                        Field(parameter.label, value, { update(it.take(500)) }, type = keyboard, enabled = enabled)
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeechProviderField(
    options: VoicePreferences, providers: List<Provider>, selected: String, enabled: Boolean, change: (String) -> Unit,
) {
    val eligible = providers.filter(options.activeBackend::accepts)
    SelectField("语音服务连接", selected, (listOf("", selected) + eligible.map(Provider::id)).distinct(), { id ->
        if (id.isEmpty()) "自动选择已配置的连接" else eligible.firstOrNull { it.id == id }?.name ?: "连接不可用"
    }, enabled && eligible.isNotEmpty(), change)
    if (eligible.isEmpty()) Text("请先在提供商设置中配置 ${options.activeBackend.label} 的 Key。",
        style = MaterialTheme.typography.bodySmall)
}

private fun canUseServiceVoiceCatalog(options: VoicePreferences, providers: List<Provider>) =
    when (options.activeBackend) {
        TtsBackend.ELEVENLABS, TtsBackend.AZURE -> options.ttsApiKey.isNotBlank()
        TtsBackend.ALLTALK -> options.ttsEndpoint.isNotBlank()
        TtsBackend.KOKORO -> true
        TtsBackend.GEMINI_NATIVE, TtsBackend.NOVELAI -> options.speechProvider(providers) != null
        else -> options.activeBackend.selfHosted && options.ttsEndpoint.isNotBlank()
    }

@Composable
private fun RvcSettingsSection(
    rvc: app.tavern.nativeclient.media.RvcSettings,
    enabled: Boolean,
    change: (app.tavern.nativeclient.media.RvcSettings) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var loadingModels by remember { mutableStateOf(false) }
    var modelError by remember { mutableStateOf<String?>(null) }
    FormHeading("RVC 变声后处理")
    GroupToggle("TTS 合成后启用 RVC 变声", rvc.enabled, enabled) { change(rvc.copy(enabled = it)) }
    if (rvc.enabled) {
        Field("RVC / Extras 服务地址", rvc.endpoint, { change(rvc.copy(endpoint = it)) },
            enabled = enabled, type = KeyboardType.Uri, placeholder = "http://192.168.1.20:5100")
        Field("API Key（如无鉴权可留空）", rvc.apiKey, { change(rvc.copy(apiKey = it)) }, secret = true, enabled = enabled)
        Field("RVC 模型名称", rvc.modelName, { change(rvc.copy(modelName = it)) }, enabled = enabled)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = {
                    loadingModels = true
                    modelError = null
                    scope.launch {
                        try {
                            models = listRvcModels(rvc)
                            if (models.isEmpty()) modelError = "服务端没有可用的 RVC 模型"
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            modelError = e.localizedMessage ?: "无法读取 RVC 模型列表"
                        } finally {
                            loadingModels = false
                        }
                    }
                },
                enabled = enabled && !loadingModels && rvc.endpoint.isNotBlank(),
            ) { Text(if (loadingModels) "正在读取…" else "读取 RVC 模型") }
        }
        modelError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (models.isNotEmpty()) {
            SelectField("选择 RVC 模型", rvc.modelName, (models + rvc.modelName).filter(String::isNotBlank).distinct(),
                { it }, enabled) { change(rvc.copy(modelName = it)) }
        }
        SelectField("音高提取算法 (F0)", rvc.f0Method, rvcF0Methods, { it }, enabled) { change(rvc.copy(f0Method = it)) }
        Column {
            Text("变调 (Pitch Shift) · ${if (rvc.pitchShift >= 0) "+${rvc.pitchShift}" else "${rvc.pitchShift}"} 半音",
                style = MaterialTheme.typography.labelLarge)
            Slider(rvc.pitchShift.toFloat(), { change(rvc.copy(pitchShift = it.roundToInt())) },
                enabled = enabled, valueRange = -24f..24f, steps = 47)
        }
        Column {
            Text("检索特征占比 (Index Rate) · ${String.format(Locale.ROOT, "%.2f", rvc.indexRate)}",
                style = MaterialTheme.typography.labelLarge)
            Slider(rvc.indexRate, { change(rvc.copy(indexRate = (it * 20f).roundToInt() / 20f)) },
                enabled = enabled, valueRange = 0f..1f, steps = 19)
        }
        Column {
            Text("中值滤波半径 (Filter Radius) · ${rvc.filterRadius}", style = MaterialTheme.typography.labelLarge)
            Slider(rvc.filterRadius.toFloat(), { change(rvc.copy(filterRadius = it.roundToInt())) },
                enabled = enabled, valueRange = 0f..7f, steps = 6)
        }
    }
}

@Composable
private fun LocalWhisperModelSection(enabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stt = remember(context) { LocalWhisperStt(localWhisperDir(context.applicationContext)) }
    var status by remember { mutableStateOf(stt.status()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val importer = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        message = "正在导入本地 Whisper ONNX 模型包…"
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openInputStream(uri)) { "无法打开模型包" }.use { input ->
                        stt.importArchive(input)
                    }
                }
                status = stt.status()
                message = "本地 Whisper ONNX 模型已就绪"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.localizedMessage ?: "导入本地 Whisper 模型失败"
            } finally {
                busy = false
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val statusText = if (status.installed) {
            "本地 Whisper ONNX 模型已安装（${String.format(Locale.ROOT, "%.1f", status.totalBytes / (1024.0 * 1024.0))} MB）"
        } else {
            "尚未安装本地 Whisper 模型（缺少：${status.missingParts.joinToString("、") { it.filename }}）"
        }
        Text(statusText, style = MaterialTheme.typography.bodySmall)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ importer.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                enabled = enabled && !busy) { Text("导入 ONNX 模型包 (.zip)") }
            if (status.totalBytes > 0L) {
                TextButton(
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { stt.remove() }
                                status = stt.status()
                                message = "已移除本地 Whisper 模型"
                            } catch (e: Exception) {
                                message = e.localizedMessage ?: "移除模型失败"
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = enabled && !busy,
                ) { Text("移除模型") }
            }
        }
        Text("支持包含 encoder.onnx、decoder.onnx 与 vocab.json（或 tokenizer.json）的 ZIP 模型包，全流程本机离线运行。",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ServiceVoicePicker(model: TavernViewModel, options: VoicePreferences, actor: String, playing: Boolean,
                               close: () -> Unit, select: (String) -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var voices by remember { mutableStateOf<List<ServiceVoice>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(options) {
        try {
            voices = listServiceVoices(options)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.localizedMessage ?: "无法读取语音服务音色"
        } finally {
            loading = false
        }
    }
    val visible = voices.filter { voice ->
        listOf(voice.id, voice.name, voice.description).any { it.contains(query.trim(), true) }
    }
    AlertDialog(onDismissRequest = close, title = { Text("选择 ${options.activeBackend.label} 音色") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("搜索音色", query, { query = it })
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!loading && error == null && visible.isEmpty()) Text("没有匹配的音色")
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                if (actor.isNotEmpty()) item { TextButton({ select("") }) { Text("跟随默认音色") } }
                items(visible, key = ServiceVoice::id) { voice ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton({ select(voice.id) }, Modifier.weight(1f)) {
                            Column {
                                Text(voice.name)
                                if (voice.description.isNotBlank()) Text(voice.description, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        IconButton({
                            if (playing) model.stopNarration() else model.previewVoice(options, voice.id, options.voiceSample(voice.id))
                        }) { Icon(if (playing) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.VolumeUp, "试听 ${voice.name}") }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(close) { Text("关闭") } })
}

private fun VoicePreferences.voiceSample(id: String): String {
    val language = activeBackend.speechParameters.firstOrNull { it.key == "language" || it.key == "lang" }
        ?.raw(selfHostedVoice) ?: if (activeBackend == TtsBackend.SILERO) id.substringBefore('_') else ""
    return when (language.lowercase(Locale.ROOT).substringBefore('-')) {
        "ja", "jp" -> "こんにちは。酒場へようこそ。"
        "ko" -> "안녕하세요. 선술집에 오신 것을 환영합니다."
        "ru" -> "Здравствуйте. Добро пожаловать в таверну."
        "zh", "auto", "" -> SAMPLE
        else -> "Hello. Welcome to the tavern."
    }
}

@Composable
private fun VoiceSlider(label: String, value: Float, enabled: Boolean, change: (Float) -> Unit) {
    Column {
        Text("$label · ${String.format(Locale.ROOT, "%.2f", value)}×", style = MaterialTheme.typography.labelLarge)
        Slider(value, change, enabled = enabled, valueRange = 0.5f..2f, steps = 29)
    }
}

@Composable
private fun SystemVoicePicker(model: TavernViewModel, options: VoicePreferences, actor: String, playing: Boolean,
                              close: () -> Unit, select: (String) -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var voices by remember { mutableStateOf<List<DeviceVoice>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(model) {
        try {
            voices = model.systemVoices()
        } catch (_: TimeoutCancellationException) {
            error = "系统朗读引擎响应超时"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.localizedMessage ?: "无法读取系统音色"
        } finally {
            loading = false
        }
    }
    val visible = voices.filter { it.id.contains(query.trim(), true) || it.language.contains(query.trim(), true) }
    AlertDialog(onDismissRequest = close, title = { Text("选择系统音色") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("查找语言或音色", query, { query = it })
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                item { TextButton({ select("") }) { Text(if (actor.isEmpty()) "系统默认" else "跟随默认音色") } }
                items(visible, key = DeviceVoice::id) { voice ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton({ select(voice.id) }, Modifier.weight(1f)) {
                            Column {
                                Text(voice.language + if (voice.network) " · 联网" else " · 本机")
                                Text(voice.id, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        IconButton({
                            if (playing) model.stopNarration() else model.previewVoice(options, voice.id, SAMPLE)
                        }) { Icon(if (playing) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.VolumeUp, "试听 ${voice.id}") }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(close) { Text("关闭") } })
}
