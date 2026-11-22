package app.tavern.nativeclient

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal enum class AudioChannel(val wire: String, val label: String) {
    BGM("bgm", "背景音乐 (BGM)"),
    AMBIENT("ambient", "环境音 (Ambient)"),
    ;

    companion object {
        fun from(wire: String?): AudioChannel =
            entries.firstOrNull { it.wire.equals(wire?.trim(), ignoreCase = true) } ?: BGM
    }
}

internal enum class AudioRuleTriggerType(val wire: String, val label: String) {
    EXPRESSION("expression", "角色表情"),
    BACKGROUND("background", "背景名称"),
    KEYWORD("keyword", "消息关键词 / 正则"),
    ;

    companion object {
        fun from(wire: String?): AudioRuleTriggerType =
            entries.firstOrNull { it.wire.equals(wire?.trim(), ignoreCase = true) } ?: KEYWORD
    }
}

internal data class AudioTrack(
    val id: String = newId(),
    val name: String,
    val uri: String,
    val channel: AudioChannel = AudioChannel.BGM,
    val loop: Boolean = true,
    val volume: Float = 0.7f,
    val tags: List<String> = emptyList(),
) {
    fun json(): JSONObject = jsonOf(
        "id" to id,
        "name" to name,
        "uri" to uri,
        "channel" to channel.wire,
        "loop" to loop,
        "volume" to volume.coerceIn(0f, 1f).toDouble(),
        "tags" to JSONArray(tags),
    )

    companion object {
        fun from(j: JSONObject?): AudioTrack? {
            if (j == null) return null
            val name = j.string("name").trim()
            val uri = j.string("uri").trim()
            if (name.isEmpty() || uri.isEmpty()) return null
            return AudioTrack(
                id = j.string("id").ifBlank { newId() },
                name = name,
                uri = uri,
                channel = AudioChannel.from(j.string("channel")),
                loop = j.optBoolean("loop", true),
                volume = j.optDouble("volume", 0.7).toFloat().coerceIn(0f, 1f),
                tags = j.optJSONArray("tags").strings(),
            )
        }
    }
}

internal data class AudioAutoRule(
    val id: String = newId(),
    val name: String,
    val triggerType: AudioRuleTriggerType,
    val pattern: String,
    val trackId: String,
    val channel: AudioChannel = AudioChannel.BGM,
    val priority: Int = 100,
    val enabled: Boolean = true,
    val isRegex: Boolean = false,
) {
    fun matches(input: String?): Boolean {
        if (!enabled) return false
        val value = input?.trim().orEmpty()
        val needle = pattern.trim()
        if (value.isEmpty() || needle.isEmpty()) return false
        return if (isRegex) {
            runCatching { Regex(needle, RegexOption.IGNORE_CASE).containsMatchIn(value) }.getOrDefault(false)
        } else {
            val tokens = needle.split(',', '，', '|').map(String::trim).filter(String::isNotEmpty)
            tokens.any { token ->
                when (triggerType) {
                    AudioRuleTriggerType.EXPRESSION -> value.equals(token, ignoreCase = true) ||
                        value.contains(token, ignoreCase = true)
                    AudioRuleTriggerType.BACKGROUND,
                    AudioRuleTriggerType.KEYWORD,
                    -> value.contains(token, ignoreCase = true)
                }
            }
        }
    }

    fun json(): JSONObject = jsonOf(
        "id" to id,
        "name" to name,
        "triggerType" to triggerType.wire,
        "pattern" to pattern,
        "trackId" to trackId,
        "channel" to channel.wire,
        "priority" to priority,
        "enabled" to enabled,
        "isRegex" to isRegex,
    )

    companion object {
        fun from(j: JSONObject?): AudioAutoRule? {
            if (j == null) return null
            val pattern = j.string("pattern").trim()
            val trackId = j.string("trackId").trim()
            if (pattern.isEmpty() || trackId.isEmpty()) return null
            return AudioAutoRule(
                id = j.string("id").ifBlank { newId() },
                name = j.string("name").ifBlank { pattern },
                triggerType = AudioRuleTriggerType.from(j.string("triggerType")),
                pattern = pattern,
                trackId = trackId,
                channel = AudioChannel.from(j.string("channel")),
                priority = j.optInt("priority", 100),
                enabled = j.optBoolean("enabled", true),
                isRegex = j.optBoolean("isRegex", false),
            )
        }
    }
}

internal data class DynamicAudioConfig(
    val enabled: Boolean = false,
    val masterVolume: Float = 0.8f,
    val bgmVolume: Float = 0.7f,
    val ambientVolume: Float = 0.6f,
    val crossfadeMs: Int = 1500,
    val muteInBackground: Boolean = true,
    val tracks: List<AudioTrack> = emptyList(),
    val rules: List<AudioAutoRule> = emptyList(),
) {
    fun effectiveVolume(channel: AudioChannel, trackVolume: Float = 1f): Float {
        val channelGain = when (channel) {
            AudioChannel.BGM -> bgmVolume
            AudioChannel.AMBIENT -> ambientVolume
        }
        return (masterVolume.coerceIn(0f, 1f) * channelGain.coerceIn(0f, 1f) * trackVolume.coerceIn(0f, 1f))
            .coerceIn(0f, 1f)
    }

    fun findTrack(query: String, channel: AudioChannel? = null): AudioTrack? {
        val needle = query.trim()
        if (needle.isEmpty()) return null
        val scoped = if (channel == null) tracks else tracks.filter { it.channel == channel }
        return scoped.firstOrNull { it.id == needle || it.name.equals(needle, ignoreCase = true) }
            ?: scoped.firstOrNull { it.name.contains(needle, ignoreCase = true) || it.tags.any { t -> t.equals(needle, ignoreCase = true) } }
            ?: tracks.firstOrNull { it.id == needle || it.name.equals(needle, ignoreCase = true) }
    }

    fun json(): JSONObject = jsonOf(
        "enabled" to enabled,
        "masterVolume" to masterVolume.coerceIn(0f, 1f).toDouble(),
        "bgmVolume" to bgmVolume.coerceIn(0f, 1f).toDouble(),
        "ambientVolume" to ambientVolume.coerceIn(0f, 1f).toDouble(),
        "crossfadeMs" to crossfadeMs.coerceIn(0, 10_000),
        "muteInBackground" to muteInBackground,
        "tracks" to JSONArray(tracks.map(AudioTrack::json)),
        "rules" to JSONArray(rules.map(AudioAutoRule::json)),
    )

    companion object {
        fun from(j: JSONObject?): DynamicAudioConfig {
            if (j == null) return DynamicAudioConfig()
            return DynamicAudioConfig(
                enabled = j.optBoolean("enabled", false),
                masterVolume = j.optDouble("masterVolume", 0.8).toFloat().coerceIn(0f, 1f),
                bgmVolume = j.optDouble("bgmVolume", 0.7).toFloat().coerceIn(0f, 1f),
                ambientVolume = j.optDouble("ambientVolume", 0.6).toFloat().coerceIn(0f, 1f),
                crossfadeMs = j.optInt("crossfadeMs", 1500).coerceIn(0, 10_000),
                muteInBackground = j.optBoolean("muteInBackground", true),
                tracks = j.optJSONArray("tracks")?.objects().orEmpty().mapNotNull(AudioTrack::from),
                rules = j.optJSONArray("rules")?.objects().orEmpty().mapNotNull(AudioAutoRule::from),
            )
        }
    }
}

internal data class ChannelPlaybackState(
    val channel: AudioChannel,
    val trackId: String? = null,
    val trackName: String = "",
    val uri: String = "",
    val playing: Boolean = false,
    val paused: Boolean = false,
    val loop: Boolean = true,
    val volume: Float = 0.7f,
    val crossfadeMs: Int = 1500,
)

internal data class DynamicAudioState(
    val bgm: ChannelPlaybackState = ChannelPlaybackState(AudioChannel.BGM),
    val ambient: ChannelPlaybackState = ChannelPlaybackState(AudioChannel.AMBIENT),
) {
    fun channelState(channel: AudioChannel): ChannelPlaybackState = when (channel) {
        AudioChannel.BGM -> bgm
        AudioChannel.AMBIENT -> ambient
    }
}

internal interface AudioPlayerBackend {
    fun play(channel: AudioChannel, uri: String, loop: Boolean, targetVolume: Float, crossfadeMs: Int)
    fun pause(channel: AudioChannel)
    fun resume(channel: AudioChannel, targetVolume: Float)
    fun setVolume(channel: AudioChannel, volume: Float)
    fun stop(channel: AudioChannel, crossfadeMs: Int)
    fun release()
}

internal object NoOpAudioPlayerBackend : AudioPlayerBackend {
    override fun play(channel: AudioChannel, uri: String, loop: Boolean, targetVolume: Float, crossfadeMs: Int) = Unit
    override fun pause(channel: AudioChannel) = Unit
    override fun resume(channel: AudioChannel, targetVolume: Float) = Unit
    override fun setVolume(channel: AudioChannel, volume: Float) = Unit
    override fun stop(channel: AudioChannel, crossfadeMs: Int) = Unit
    override fun release() = Unit
}

internal class AndroidMediaPlayerAudioBackend(private val context: Context) : AudioPlayerBackend {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val players = mutableMapOf<AudioChannel, MediaPlayer>()
    private val fadeJobs = mutableMapOf<AudioChannel, Job>()

    override fun play(channel: AudioChannel, uri: String, loop: Boolean, targetVolume: Float, crossfadeMs: Int) {
        val oldPlayer = players.remove(channel)
        fadeJobs.remove(channel)?.cancel()
        val safeVol = targetVolume.coerceIn(0f, 1f)
        val next = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                setDataSource(context, Uri.parse(uri))
                isLooping = loop
                prepare()
                val startVol = if (crossfadeMs > 0) 0f else safeVol
                setVolume(startVol, startVol)
                start()
            }
        }.getOrNull()

        if (next != null) {
            players[channel] = next
            if (crossfadeMs > 0) {
                fadeJobs[channel] = scope.launch {
                    val steps = 12
                    val stepDelay = (crossfadeMs / steps).coerceAtLeast(16).toLong()
                    for (step in 1..steps) {
                        val frac = step.toFloat() / steps
                        runCatching { next.setVolume(safeVol * frac, safeVol * frac) }
                        if (oldPlayer != null) {
                            val down = safeVol * (1f - frac)
                            runCatching { oldPlayer.setVolume(down, down) }
                        }
                        delay(stepDelay)
                    }
                    releasePlayer(oldPlayer)
                }
            } else {
                releasePlayer(oldPlayer)
            }
        } else {
            releasePlayer(oldPlayer)
        }
    }

    override fun pause(channel: AudioChannel) {
        runCatching { players[channel]?.takeIf { it.isPlaying }?.pause() }
    }

    override fun resume(channel: AudioChannel, targetVolume: Float) {
        runCatching {
            players[channel]?.let {
                val vol = targetVolume.coerceIn(0f, 1f)
                it.setVolume(vol, vol)
                if (!it.isPlaying) it.start()
            }
        }
    }

    override fun setVolume(channel: AudioChannel, volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        runCatching { players[channel]?.setVolume(v, v) }
    }

    override fun stop(channel: AudioChannel, crossfadeMs: Int) {
        fadeJobs.remove(channel)?.cancel()
        val player = players.remove(channel) ?: return
        if (crossfadeMs <= 0) {
            releasePlayer(player)
            return
        }
        fadeJobs[channel] = scope.launch {
            val steps = 8
            val stepDelay = (crossfadeMs / steps).coerceAtLeast(16).toLong()
            for (step in 1..steps) {
                val down = (1f - step.toFloat() / steps).coerceAtLeast(0f)
                runCatching { player.setVolume(down, down) }
                delay(stepDelay)
            }
            releasePlayer(player)
        }
    }

    override fun release() {
        fadeJobs.values.forEach { it.cancel() }
        fadeJobs.clear()
        players.values.forEach(::releasePlayer)
        players.clear()
    }

    private fun releasePlayer(player: MediaPlayer?) {
        if (player == null) return
        runCatching { if (player.isPlaying) player.stop() }
        runCatching { player.release() }
    }
}

internal class DynamicAudioEngine(private val backend: AudioPlayerBackend = NoOpAudioPlayerBackend) {
    private val _state = MutableStateFlow(DynamicAudioState())
    val state: StateFlow<DynamicAudioState> = _state.asStateFlow()

    fun play(
        channel: AudioChannel,
        track: AudioTrack,
        config: DynamicAudioConfig,
        loopOverride: Boolean? = null,
        volumeOverride: Float? = null,
        crossfadeOverride: Int? = null,
    ) {
        val loop = loopOverride ?: track.loop
        val trackVol = (volumeOverride ?: track.volume).coerceIn(0f, 1f)
        val effectiveVol = config.effectiveVolume(channel, trackVol)
        val crossfade = (crossfadeOverride ?: config.crossfadeMs).coerceIn(0, 10_000)
        val current = _state.value.channelState(channel)
        if (current.playing && !current.paused && current.trackId == track.id && current.uri == track.uri) {
            backend.setVolume(channel, effectiveVol)
            updateChannel(channel, current.copy(volume = effectiveVol, loop = loop))
            return
        }
        backend.play(channel, track.uri, loop, effectiveVol, crossfade)
        updateChannel(
            channel,
            ChannelPlaybackState(
                channel = channel,
                trackId = track.id,
                trackName = track.name,
                uri = track.uri,
                playing = true,
                paused = false,
                loop = loop,
                volume = effectiveVol,
                crossfadeMs = crossfade,
            ),
        )
    }

    fun pause(channel: AudioChannel) {
        val current = _state.value.channelState(channel)
        if (!current.playing || current.paused) return
        backend.pause(channel)
        updateChannel(channel, current.copy(paused = true))
    }

    fun resume(channel: AudioChannel) {
        val current = _state.value.channelState(channel)
        if (!current.playing || !current.paused) return
        backend.resume(channel, current.volume)
        updateChannel(channel, current.copy(paused = false))
    }

    fun setVolume(channel: AudioChannel, volume: Float, config: DynamicAudioConfig) {
        val effective = config.effectiveVolume(channel, volume.coerceIn(0f, 1f))
        backend.setVolume(channel, effective)
        updateChannel(channel, _state.value.channelState(channel).copy(volume = effective))
    }

    fun applyConfigVolumes(config: DynamicAudioConfig) {
        AudioChannel.entries.forEach { ch ->
            val current = _state.value.channelState(ch)
            if (current.playing) {
                val trackVol = config.tracks.firstOrNull { it.id == current.trackId }?.volume ?: 1f
                val effective = config.effectiveVolume(ch, trackVol)
                backend.setVolume(ch, effective)
                updateChannel(ch, current.copy(volume = effective))
            }
        }
    }

    fun stop(channel: AudioChannel, crossfadeMs: Int = 0) {
        backend.stop(channel, crossfadeMs.coerceIn(0, 10_000))
        updateChannel(channel, ChannelPlaybackState(channel = channel))
    }

    fun stopAll(crossfadeMs: Int = 0) {
        AudioChannel.entries.forEach { stop(it, crossfadeMs) }
    }

    fun setAppForeground(foreground: Boolean, config: DynamicAudioConfig) {
        if (!config.muteInBackground) return
        AudioChannel.entries.forEach { ch ->
            val current = _state.value.channelState(ch)
            if (current.playing) {
                if (foreground) backend.resume(ch, current.volume) else backend.pause(ch)
            }
        }
    }

    fun evaluateTriggers(
        config: DynamicAudioConfig,
        expression: String? = null,
        backgroundName: String? = null,
        messageText: String? = null,
    ): Map<AudioChannel, AudioTrack> {
        if (!config.enabled || config.rules.isEmpty() || config.tracks.isEmpty()) return emptyMap()
        val matched = linkedMapOf<AudioChannel, AudioTrack>()
        val tracksById = config.tracks.associateBy(AudioTrack::id)
        val sortedRules = config.rules.filter(AudioAutoRule::enabled).sortedByDescending(AudioAutoRule::priority)

        for (rule in sortedRules) {
            if (rule.channel in matched) continue
            val candidateInput = when (rule.triggerType) {
                AudioRuleTriggerType.EXPRESSION -> expression
                AudioRuleTriggerType.BACKGROUND -> backgroundName
                AudioRuleTriggerType.KEYWORD -> messageText
            }
            if (rule.matches(candidateInput)) {
                val track = tracksById[rule.trackId] ?: continue
                matched[rule.channel] = track
                play(rule.channel, track, config)
            }
        }
        return matched
    }

    fun handleSlashCommand(
        commandName: String,
        args: String,
        named: Map<String, String>,
        config: DynamicAudioConfig,
    ): String {
        val channel = when (commandName.trim().lowercase(Locale.ROOT).removePrefix("/")) {
            "bgm" -> AudioChannel.BGM
            "ambient" -> AudioChannel.AMBIENT
            else -> return ""
        }
        val cleaned = args.trim()
        val lower = cleaned.lowercase(Locale.ROOT)
        val volumeArg = named["volume"]?.toFloatOrNull()?.coerceIn(0f, 1f)
        val loopArg = named["loop"]?.lowercase(Locale.ROOT)?.let {
            when (it) {
                "true", "1", "yes", "on" -> true
                "false", "0", "no", "off" -> false
                else -> null
            }
        }
        val crossfadeArg = named["crossfade"]?.toIntOrNull()?.coerceIn(0, 10_000)

        when {
            lower in setOf("off", "stop", "none", "clear") -> {
                stop(channel, crossfadeArg ?: config.crossfadeMs)
                return "已停止 ${channel.label}"
            }
            lower == "pause" -> {
                pause(channel)
                return "已暂停 ${channel.label}"
            }
            lower == "resume" -> {
                resume(channel)
                return "已恢复 ${channel.label}"
            }
            cleaned.isEmpty() && volumeArg != null -> {
                setVolume(channel, volumeArg, config)
                return "${channel.label} 音量已设为 ${(volumeArg * 100).toInt()}%"
            }
            cleaned.isEmpty() -> {
                val cur = _state.value.channelState(channel)
                return if (cur.playing) "${channel.label}: ${cur.trackName}" else "${channel.label}: 未播放"
            }
            else -> {
                val track = config.findTrack(cleaned, channel)
                    ?: AudioTrack(
                        id = "direct:$cleaned",
                        name = cleaned.substringAfterLast('/'),
                        uri = cleaned,
                        channel = channel,
                        loop = loopArg ?: true,
                        volume = volumeArg ?: 0.7f,
                    ).takeIf { cleaned.startsWith("http://") || cleaned.startsWith("https://") || cleaned.startsWith("content://") || cleaned.startsWith("file://") }
                    ?: error("找不到音频曲目：$cleaned")
                play(channel, track, config, loopOverride = loopArg, volumeOverride = volumeArg, crossfadeOverride = crossfadeArg)
                return "正在播放 ${channel.label}：${track.name}"
            }
        }
    }

    fun release() {
        stopAll(0)
        backend.release()
    }

    private fun updateChannel(channel: AudioChannel, next: ChannelPlaybackState) {
        _state.update { current ->
            when (channel) {
                AudioChannel.BGM -> current.copy(bgm = next)
                AudioChannel.AMBIENT -> current.copy(ambient = next)
            }
        }
    }
}
