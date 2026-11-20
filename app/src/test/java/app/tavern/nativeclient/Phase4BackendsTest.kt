package app.tavern.nativeclient

import app.tavern.nativeclient.backup.DavResource
import app.tavern.nativeclient.backup.SyncAction
import app.tavern.nativeclient.backup.SyncConflictPolicy
import app.tavern.nativeclient.backup.SyncDirection
import app.tavern.nativeclient.backup.SyncEntityKind
import app.tavern.nativeclient.backup.SyncItemMeta
import app.tavern.nativeclient.backup.computeSyncPlan
import app.tavern.nativeclient.backup.parsePropfindHrefs
import app.tavern.nativeclient.media.EdgeTtsClient
import app.tavern.nativeclient.media.encodePcm16Wav
import app.tavern.nativeclient.script.MvuEngine
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class Phase4BackendsTest {

    @Test
    fun edgeTtsSecMsGecAndSsmlAndAudioFramingWork() {
        val t1 = 1_700_000_100_000L
        val t2 = 1_700_000_250_000L // same 300s window
        val t3 = 1_700_000_450_000L // next 300s window
        val gec1 = EdgeTtsClient.computeSecMsGec(t1)
        val gec2 = EdgeTtsClient.computeSecMsGec(t2)
        val gec3 = EdgeTtsClient.computeSecMsGec(t3)

        assertEquals(64, gec1.length)
        assertTrue(gec1.all { it in '0'..'9' || it in 'A'..'F' })
        assertEquals(gec1, gec2)
        assertNotEquals(gec1, gec3)

        val ssml = EdgeTtsClient.buildEdgeSsml(
            text = "Hello <Tavern> & \"Rin\"!",
            voice = "zh-CN-XiaoxiaoNeural",
            speed = 1.2f,
            pitch = 1.1f,
        )
        assertTrue(ssml.contains("zh-CN-XiaoxiaoNeural"))
        assertTrue(ssml.contains("rate='+20%'"))
        assertTrue(ssml.contains("pitch='+5Hz'"))
        assertTrue(ssml.contains("Hello &lt;Tavern&gt; &amp; &quot;Rin&quot;!"))

        val header = "X-RequestId:123\r\nContent-Type:audio/mpeg\r\nPath:audio\r\n\r\n".toByteArray(Charsets.UTF_8)
        val audioPayload = byteArrayOf(0x49, 0x44, 0x33, 0x04)
        val frame = ByteArray(2 + header.size + audioPayload.size).apply {
            this[0] = ((header.size ushr 8) and 0xFF).toByte()
            this[1] = (header.size and 0xFF).toByte()
            System.arraycopy(header, 0, this, 2, header.size)
            System.arraycopy(audioPayload, 0, this, 2 + header.size, audioPayload.size)
        }
        assertArrayEquals(audioPayload, EdgeTtsClient.extractEdgeAudioChunk(frame))

        val endHeader = "X-RequestId:123\r\nPath:turn.end\r\n\r\n".toByteArray(Charsets.UTF_8)
        val endFrame = ByteArray(2 + endHeader.size).apply {
            this[0] = ((endHeader.size ushr 8) and 0xFF).toByte()
            this[1] = (endHeader.size and 0xFF).toByte()
            System.arraycopy(endHeader, 0, this, 2, endHeader.size)
        }
        assertNull(EdgeTtsClient.extractEdgeAudioChunk(endFrame))
    }

    @Test
    fun whisperWavEncoderWritesValidRiffHeaderAndPcm() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val wav = encodePcm16Wav(pcm, sampleRate = 16_000, channels = 1)
        assertEquals(48, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(wav, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        val buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(40, buf.getInt(4))
        assertEquals(16_000, buf.getInt(24))
        assertEquals(4, buf.getInt(40))
        assertArrayEquals(pcm, wav.copyOfRange(44, 48))
    }

    @Test
    fun novelAiZipExtractorReadsPngFromArchive() {
        val expected = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 1, 2, 3)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("image_0.png"))
            zip.write(expected)
            zip.closeEntry()
        }
        assertArrayEquals(expected, extractNovelAiZipImage(out.toByteArray()))
    }

    @Test
    fun webDavPropfindParserAndThreeWaySyncPlanWork() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/dav/TavernNative/cards/</D:href>
                <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
              </D:response>
              <D:response>
                <D:href>/dav/TavernNative/cards/%E5%B0%8F%E7%91%9E_1234.png</D:href>
                <D:propstat><D:prop>
                  <D:getlastmodified>Wed, 15 Oct 2025 10:20:30 GMT</D:getlastmodified>
                  <D:getcontentlength>4321</D:getcontentlength>
                  <D:resourcetype/>
                </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()
        val parsed = parsePropfindHrefs(xml, "/dav/TavernNative/cards/")
        assertEquals(1, parsed.size)
        val res: DavResource = parsed.single()
        assertEquals("小瑞_1234.png", res.name)
        assertEquals(4321L, res.sizeBytes)
        assertFalse(res.isCollection)
        assertTrue(res.lastModifiedMs > 0L)

        val local = mapOf(
            "onlyLocal" to SyncItemMeta(SyncEntityKind.CARD, "onlyLocal", 100L, "h1", "a.png"),
            "equal" to SyncItemMeta(SyncEntityKind.WORLD, "equal", 100L, "h2", "w.json"),
            "remoteEdited" to SyncItemMeta(SyncEntityKind.CHAT, "remoteEdited", 100L, "base", "c1.jsonl"),
            "conflict" to SyncItemMeta(SyncEntityKind.PRESET, "conflict", 300L, "locNew", "p.json"),
        )
        val remote = mapOf(
            "onlyRemote" to SyncItemMeta(SyncEntityKind.CARD, "onlyRemote", 100L, "hr", "r.png"),
            "equal" to SyncItemMeta(SyncEntityKind.WORLD, "equal", 100L, "h2", "w.json"),
            "remoteEdited" to SyncItemMeta(SyncEntityKind.CHAT, "remoteEdited", 200L, "remNew", "c1.jsonl"),
            "conflict" to SyncItemMeta(SyncEntityKind.PRESET, "conflict", 200L, "remOther", "p.json"),
        )
        val previous = mapOf("remoteEdited" to "base", "conflict" to "orig")
        val planNewest: List<SyncAction> = computeSyncPlan(local, remote, previous, SyncConflictPolicy.NEWEST_WINS)
        val byKey = planNewest.associateBy { it.key }
        assertEquals(SyncDirection.UPLOAD, byKey.getValue("onlyLocal").direction)
        assertEquals(SyncDirection.DOWNLOAD, byKey.getValue("onlyRemote").direction)
        assertEquals(SyncDirection.SKIP_EQUAL, byKey.getValue("equal").direction)
        assertEquals(SyncDirection.DOWNLOAD, byKey.getValue("remoteEdited").direction)
        assertEquals(SyncDirection.CONFLICT_RESOLVED_LOCAL, byKey.getValue("conflict").direction)

        val planRemoteWins = computeSyncPlan(local, remote, previous, SyncConflictPolicy.REMOTE_WINS).associateBy { it.key }
        assertEquals(SyncDirection.CONFLICT_RESOLVED_REMOTE, planRemoteWins.getValue("conflict").direction)
    }

    @Test
    fun extendedStScriptCommandsManageWorldEntriesCharsVarsAndText() = runBlocking {
        val cardJson = JSONObject().put("name", "Rin").put("description", "Mage").put("scenario", "Academy").toString()
        val card = Card("card-1", cardJson)
        val book = WorldBook("book-1", "LoreBook", enabled = true, raw = """{"entries":{}}""")
        val preset = PromptPreset(id = "preset-2", name = "NightMode")
        var state = TavernState(
            cards = listOf(card),
            books = listOf(book),
            presets = listOf(preset),
            cardId = card.id,
            chat = Chat(
                id = "chat-1",
                cardId = card.id,
                title = "Test",
                updated = 1L,
                options = ConversationOptions(worldBookId = book.id),
            ),
            prefs = Preferences(),
            busy = false,
        )

        val host = TavernScriptHost(
            snapshot = { state },
            input = "",
            saveChat = { chat -> state = state.copy(chat = chat) },
            savePrefs = { prefs -> state = state.copy(prefs = prefs) },
            ask = { null },
            generate = { "" },
            quiet = { _, _ -> "" },
            draft = {},
            saveVariables = { chatId, changes ->
                val globals = state.prefs.scriptVariables.toMutableMap()
                changes.global.forEach { (k, v) -> if (v == null) globals.remove(k) else globals[k] = v }
                val chat = state.chat
                val updatedChat = if (chat != null && chat.id == chatId) {
                    val locals = chat.options.variables.toMutableMap()
                    changes.local.forEach { (k, v) -> if (v == null) locals.remove(k) else locals[k] = v }
                    chat.copy(options = chat.options.copy(variables = locals))
                } else chat
                state = state.copy(chat = updatedChat, prefs = state.prefs.copy(scriptVariables = globals))
            },
            saveBook = { saved -> state = state.copy(books = state.books.map { if (it.id == saved.id) saved else it }) },
            clearVariables = { global ->
                state = if (global) state.copy(prefs = state.prefs.copy(scriptVariables = emptyMap()))
                else state.copy(chat = state.chat?.copy(options = state.chat!!.options.copy(variables = emptyMap())))
            },
            notice = {},
        )
        val script = StScript(host)

        val uid = script.run("""/wi-create-entry key="剑,剑术" comment=兵器 传世神兵""")
        assertEquals("0", uid)
        assertEquals("兵器", script.run("/wi-get-entry uid=0 field=comment"))
        script.run("""/wi-set-entry uid=0 field=content 破晓之刃""")
        assertEquals("破晓之刃", script.run("/wi-get-entry uid=0 field=content"))
        assertTrue(script.run("/wi-list-entries").contains("兵器"))
        script.run("/wi-delete-entry uid=0")
        assertEquals("[]", script.run("/wi-list-entries"))

        assertEquals("Academy", script.run("/char-get field=scenario"))
        assertEquals("Mage", script.run("/char-get name=Rin field=description"))

        assertEquals("NightMode", script.run("/preset NightMode"))
        assertEquals("NightMode", script.run("/preset"))

        script.run("/setvar key=hp 100")
        assertTrue(script.run("/listvar scope=local").contains("100"))
        script.run("/flushvar")
        assertEquals("{}", script.run("/listvar scope=local"))

        script.run("/setglobalvar key=rank S")
        assertTrue(script.run("/listvar scope=global").contains("\"S\""))
        script.run("/flushglobalvar")
        assertEquals("{}", script.run("/listvar scope=global"))

        assertEquals("HP:# MP:#", script.run("""/re-replace find="\d+" replace="#" flags=g "HP:120 MP:45" """))
        val trimmed = script.run("""/trimtokens limit=5 "one two three four five six seven eight" """)
        assertTrue(estimatedTokens.count(trimmed) <= 5)
        assertTrue(trimmed.isNotEmpty())
    }

    @Test
    fun mvuEngineParsesUpdatesAppliesJsonPatchRollsBackOnSwipeAndFeedsMacros() {
        val m0 = Message(
            role = "assistant",
            name = "Rin",
            text = """
                你好。
                <UpdateVariable>
                <Analysis>初始化角色好感度与背包</Analysis>
                _.set('主角.好感度', 10, 25);
                _.set('主角.称号', '旅者');
                _.assign('背包', { 药水: 2, 钥匙: '铜' });
                </UpdateVariable>
            """.trimIndent(),
        )
        val m1Plus = """
            太好了！
            <UpdateVariable>
            _.add('主角.好感度', 15);
            _.unset('背包', '药水');
            </UpdateVariable>
        """.trimIndent()
        val m1Minus = """
            算了吧。
            <UpdateVariable>
            [{"op":"replace","path":"/主角/好感度","value":5},{"op":"add","path":"/分支","value":"冷淡"}]
            </UpdateVariable>
        """.trimIndent()
        val m1Swiped = Message("assistant", "Rin", m1Plus, variants = listOf(m1Plus, m1Minus), index = 0)

        val stripped = MvuEngine.stripUpdateVariableBlocks(m0.text)
        assertEquals("你好。", stripped)

        val compA = MvuEngine.recomputeMvuState(listOf(m0, m1Swiped))
        assertEquals("40", MvuEngine.readPath(compA.statData, "主角.好感度"))
        assertEquals("旅者", MvuEngine.readPath(compA.statData, "stat_data.主角.称号"))
        assertEquals("铜", MvuEngine.readPath(compA.statData, "背包.钥匙"))
        assertNull(MvuEngine.readPath(compA.statData, "背包.药水"))
        assertEquals(4, compA.messageDeltas[0]?.size)

        // Swiping message #1 must roll back index 1 and re-apply alternative cleanly
        val m1SelectedAlt = m1Swiped.select(1)
        val compB = MvuEngine.recomputeMvuState(listOf(m0, m1SelectedAlt))
        assertEquals("5", MvuEngine.readPath(compB.statData, "主角.好感度"))
        assertEquals("冷淡", MvuEngine.readPath(compB.statData, "分支"))
        assertEquals("2", MvuEngine.readPath(compB.statData, "背包.药水"))

        val macros = ConversationMacros(
            card = Card.blank(),
            persona = PersonaProfile("p1", "Player"),
            history = listOf(m0, m1Swiped),
        )
        assertEquals("40", macros.expand("{{get_message_variable::主角.好感度}}"))
        assertTrue(macros.expand("{{stat_data}}").contains("旅者"))
    }
}
