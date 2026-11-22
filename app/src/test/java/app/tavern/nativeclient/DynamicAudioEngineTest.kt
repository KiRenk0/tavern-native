package app.tavern.nativeclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicAudioEngineTest {

    private class RecordingAudioBackend : AudioPlayerBackend {
        val log = mutableListOf<String>()

        override fun play(channel: AudioChannel, uri: String, loop: Boolean, targetVolume: Float, crossfadeMs: Int) {
            log += "play:${channel.wire}:$uri:loop=$loop:fade=$crossfadeMs"
        }

        override fun pause(channel: AudioChannel) {
            log += "pause:${channel.wire}"
        }

        override fun resume(channel: AudioChannel, targetVolume: Float) {
            log += "resume:${channel.wire}"
        }

        override fun setVolume(channel: AudioChannel, volume: Float) {
            log += "vol:${channel.wire}:$volume"
        }

        override fun stop(channel: AudioChannel, crossfadeMs: Int) {
            log += "stop:${channel.wire}:fade=$crossfadeMs"
        }

        override fun release() {
            log += "release"
        }
    }

    @Test
    fun `triggers BGM and ambient tracks by expression background and keyword rules with priority`() {
        val backend = RecordingAudioBackend()
        val engine = DynamicAudioEngine(backend)

        val battle = AudioTrack(id = "t_battle", name = "Battle Theme", uri = "file:///audio/battle.mp3", channel = AudioChannel.BGM)
        val tavern = AudioTrack(id = "t_tavern", name = "Tavern Calm", uri = "file:///audio/tavern.mp3", channel = AudioChannel.BGM)
        val rain = AudioTrack(id = "t_rain", name = "Heavy Rain", uri = "file:///audio/rain.ogg", channel = AudioChannel.AMBIENT)

        val cfg = DynamicAudioConfig(
            enabled = true,
            masterVolume = 1.0f,
            bgmVolume = 0.8f,
            ambientVolume = 0.5f,
            crossfadeMs = 1200,
            tracks = listOf(battle, tavern, rain),
            rules = listOf(
                AudioAutoRule(
                    id = "r1",
                    name = "Tavern BG",
                    triggerType = AudioRuleTriggerType.BACKGROUND,
                    pattern = "tavern",
                    trackId = tavern.id,
                    channel = AudioChannel.BGM,
                    priority = 50,
                ),
                AudioAutoRule(
                    id = "r2",
                    name = "Angry Expression",
                    triggerType = AudioRuleTriggerType.EXPRESSION,
                    pattern = "anger, furious",
                    trackId = battle.id,
                    channel = AudioChannel.BGM,
                    priority = 200,
                ),
                AudioAutoRule(
                    id = "r3",
                    name = "Rain Keyword",
                    triggerType = AudioRuleTriggerType.KEYWORD,
                    pattern = """thunder|rainstorm|暴雨""",
                    trackId = rain.id,
                    channel = AudioChannel.AMBIENT,
                    priority = 100,
                    isRegex = true,
                ),
            ),
        )

        val matched = engine.evaluateTriggers(
            config = cfg,
            expression = "anger",
            backgroundName = "tavern_hearth.png",
            messageText = "窗外下起了暴雨，雷声滚滚。",
        )

        // Higher priority expression rule wins for BGM; keyword rule matches AMBIENT
        assertEquals(battle.id, matched[AudioChannel.BGM]?.id)
        assertEquals(rain.id, matched[AudioChannel.AMBIENT]?.id)
        assertTrue(engine.state.value.bgm.playing)
        assertEquals("Battle Theme", engine.state.value.bgm.trackName)
        assertTrue(engine.state.value.ambient.playing)
        assertEquals("Heavy Rain", engine.state.value.ambient.trackName)
        assertTrue(backend.log.any { it.startsWith("play:bgm:file:///audio/battle.mp3:loop=true:fade=1200") })
        assertTrue(backend.log.any { it.startsWith("play:ambient:file:///audio/rain.ogg:loop=true:fade=1200") })
    }

    @Test
    fun `handles bgm and ambient slash commands for play pause resume volume and stop`() {
        val backend = RecordingAudioBackend()
        val engine = DynamicAudioEngine(backend)
        val piano = AudioTrack(id = "t1", name = "Moonlight", uri = "https://cdn.example/moon.mp3", channel = AudioChannel.BGM)
        val forest = AudioTrack(id = "t2", name = "Forest Wind", uri = "https://cdn.example/wind.ogg", channel = AudioChannel.AMBIENT)
        val cfg = DynamicAudioConfig(enabled = true, tracks = listOf(piano, forest))

        val resPlay = engine.handleSlashCommand("bgm", "Moonlight", mapOf("volume" to "0.5", "loop" to "false"), cfg)
        assertTrue(resPlay.contains("Moonlight"))
        assertTrue(engine.state.value.bgm.playing)
        assertFalse(engine.state.value.bgm.loop)

        engine.handleSlashCommand("bgm", "pause", emptyMap(), cfg)
        assertTrue(engine.state.value.bgm.paused)
        engine.handleSlashCommand("bgm", "resume", emptyMap(), cfg)
        assertFalse(engine.state.value.bgm.paused)

        engine.handleSlashCommand("ambient", "Forest Wind", emptyMap(), cfg)
        assertEquals("Forest Wind", engine.state.value.ambient.trackName)

        engine.handleSlashCommand("bgm", "off", emptyMap(), cfg)
        assertFalse(engine.state.value.bgm.playing)
        engine.handleSlashCommand("ambient", "stop", emptyMap(), cfg)
        assertFalse(engine.state.value.ambient.playing)
    }
}
