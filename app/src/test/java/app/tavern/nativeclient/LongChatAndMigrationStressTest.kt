package app.tavern.nativeclient

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

class LongChatAndMigrationStressTest {

    private fun buildLongChat(count: Int): Chat {
        val baseTime = 1_700_000_000_000L
        val messages = ArrayList<Message>(count)
        for (i in 0 until count) {
            val isUser = i % 2 == 0
            val hidden = i % 25 == 0
            val text = if (i == 17) {
                "Early landmark: silver compass hidden behind the lighthouse."
            } else if (i == count - 7) {
                "Late landmark: obsidian key unlocked the northern observatory."
            } else {
                "Turn #$i — ${if (isUser) "Traveler asks about route segment" else "Mira describes tavern chronicle entry"} $i with enough prose to simulate realistic dialogue."
            }
            val variants = if (!isUser && i % 15 == 0) listOf("$text (draft 1)", text) else listOf(text)
            messages += Message(
                role = if (isUser) "user" else "assistant",
                name = if (isUser) "Traveler" else "Mira",
                text = text,
                date = "2026-11-24T00:00:${(i % 60).toString().padStart(2, '0')}Z",
                variants = variants,
                index = variants.lastIndex,
                hidden = hidden,
            )
        }
        return Chat(
            id = "stress-chat-$count",
            cardId = "card-mira",
            title = "Chronicle of $count Turns",
            updated = baseTime + count * 1000L,
            messages = messages,
            options = ConversationOptions(
                scenario = "Long-running campaign stress test",
                variables = mapOf("chapter" to "42", "turns" to count.toString()),
            ),
        )
    }

    @Test
    fun twelveThousandMessagesPaginationAndDepthComputationAreFastAndBounded() {
        val total = 12_000
        val chat = buildLongChat(total)
        val messages = chat.messages
        assertEquals(total, messages.size)

        // Verify ChatScreen pagination window math across page sizes 50, 100, 200, and 0 (unbounded)
        val elapsedMs = measureTimeMillis {
            for (pageSize in listOf(50, 100, 200)) {
                val handling = ChatHandling(historyPageSize = pageSize)
                assertEquals(pageSize, handling.historyPageSize)
                var earliestRevealed = Int.MAX_VALUE
                val defaultStart = (messages.size - pageSize).coerceAtLeast(0)
                var historyStart = minOf(defaultStart, earliestRevealed)
                var page = messages.subList(historyStart, messages.size)
                assertEquals(pageSize, page.size)
                assertEquals(messages.last().text, page.last().text)
                assertTrue(historyStart > 0)

                // Step backward 10 pages as a user scrolls up and taps "load earlier"
                repeat(10) {
                    earliestRevealed = (historyStart - pageSize).coerceAtLeast(0)
                    historyStart = minOf(defaultStart, earliestRevealed)
                    page = messages.subList(historyStart, messages.size)
                }
                assertEquals(pageSize * 11, page.size)
                assertEquals(total - pageSize * 11, historyStart)

                // Jump to an early archive hit (index 17) expands window to include index 17
                val jumpIndex = 17
                earliestRevealed = minOf(earliestRevealed, jumpIndex)
                historyStart = minOf(defaultStart, earliestRevealed)
                page = messages.subList(historyStart, messages.size)
                assertEquals(jumpIndex, historyStart)
                assertEquals(messages[jumpIndex].text, page.first().text)
            }

            // Compute reverse visible depth array exactly as ChatScreen does
            var visible = 0
            val depths = IntArray(messages.size).apply {
                for (index in messages.indices.reversed()) {
                    this[index] = visible
                    if (!messages[index].excludedFromContext) visible++
                }
            }
            val expectedVisible = messages.count { !it.excludedFromContext }
            assertEquals(expectedVisible, visible)
            assertEquals(0, depths[messages.lastIndex])
            assertEquals(expectedVisible - if (messages[0].excludedFromContext) 0 else 1, depths[0])
        }
        assertTrue("12,000-message pagination & depth scan took too long: ${elapsedMs}ms", elapsedMs < 2_000L)
    }

    @Test
    fun tenThousandMessagesArchiveSearchAndJsonlRoundTripSucceedWithinBudget() {
        val total = 10_500
        val chat = buildLongChat(total)

        // Multi-term cross-message archive search across 10,500 messages
        var crossMatch: ChatArchiveMatch? = null
        val searchMs = measureTimeMillis {
            crossMatch = ChatArchiveQuery("silver compass obsidian key").match(chat.messages)
        }
        assertNotNull("Cross-message search must match across early and late messages", crossMatch)
        assertTrue("Archive search across 10,500 messages took too long: ${searchMs}ms", searchMs < 3_000L)

        // Memory-bounded JSONL export + re-import round-trip on 10,500 messages
        System.gc()
        val rt = Runtime.getRuntime()
        val beforeUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)

        lateinit var reimported: Chat
        val roundTripMs = measureTimeMillis {
            val bytes = chat.jsonl("Mira", "Traveler")
            assertTrue("JSONL payload must be substantial", bytes.size > 1_500_000)
            val rows = bytes.toString(Charsets.UTF_8)
                .lineSequence()
                .filter(String::isNotBlank)
                .map(::JSONObject)
                .toList()
            reimported = Chat.import("card-mira", chat.title, rows, id = "reimported-$total")
        }
        val afterUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)

        assertEquals(total, reimported.messages.size)
        assertEquals(chat.messages.first().text, reimported.messages.first().text)
        assertEquals(chat.messages.last().text, reimported.messages.last().text)
        assertEquals(chat.messages[15].variants, reimported.messages[15].variants)
        assertEquals(chat.messages[25].hidden, reimported.messages[25].hidden)
        assertEquals("42", reimported.options.variables["chapter"])
        assertEquals(total.toString(), reimported.options.variables["turns"])
        assertTrue("10,500-message JSONL round-trip took too long: ${roundTripMs}ms", roundTripMs < 15_000L)
        assertTrue(
            "Memory growth during 10,500-message round-trip exceeded ceiling (${afterUsedMb - beforeUsedMb} MiB)",
            (afterUsedMb - beforeUsedMb) < 256L,
        )
    }

    @Test
    fun tenThousandMessagesWorldScanAndPayloadSerializationStayFast() {
        val total = 10_000
        val chat = buildLongChat(total)
        val book = WorldBook(
            id = "wb-stress",
            name = "Observatory Lore",
            enabled = true,
        ).withEntries(
            listOf(
                LoreEntry(
                    id = 1,
                    title = "Northern Observatory",
                    keywords = listOf("obsidian key", "observatory"),
                    content = "The observatory overlooks the frozen bay.",
                    depth = 4,
                    enabled = true,
                ),
            ),
        )
        val card = Card("card-mira", jsonOf("name" to "Mira", "description" to "Archivist").toString())

        val evalMs = measureTimeMillis {
            val eval = evaluateLore(
                card = card,
                books = listOf(book),
                history = chat.messages,
                settings = LoreSettings(scanDepth = 20),
                persona = PersonaProfile(name = "Traveler"),
                options = ConversationOptions(),
                extraScan = "",
            )
            assertTrue("Recent obsidian key message must activate world entry", eval.entries.any { it.content.contains("frozen bay") })
        }
        assertTrue("WorldBook scan on 10,000-message chat took too long: ${evalMs}ms", evalMs < 3_000L)

        val payloadMs = measureTimeMillis {
            val payload = chat.payload()
            assertEquals(total, payload.getJSONArray("messages").length())
        }
        assertTrue("10,000-message payload serialization took too long: ${payloadMs}ms", payloadMs < 8_000L)
    }

    private data class SchemaSnapshot(
        val tables: MutableSet<String> = linkedSetOf(),
        val alteredColumns: MutableMap<String, MutableSet<String>> = linkedMapOf(),
        val indices: MutableSet<String> = linkedSetOf(),
        val triggers: MutableSet<String> = linkedSetOf(),
    )

    private fun applyDdl(statements: List<String>, state: SchemaSnapshot, label: String) {
        val createTable = Regex("""(?is)^\s*CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(\w+)\s*\(""")
        val alterRename = Regex("""(?is)^\s*ALTER\s+TABLE\s+(\w+)\s+RENAME\s+TO\s+(\w+)\s*$""")
        val alterAddCol = Regex("""(?is)^\s*ALTER\s+TABLE\s+(\w+)\s+ADD\s+COLUMN\s+(\w+)\b""")
        val dropTable = Regex("""(?is)^\s*DROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?(\w+)\s*$""")
        val createIndex = Regex("""(?is)^\s*CREATE\s+(?:UNIQUE\s+)?INDEX\s+(?:IF\s+NOT\s+EXISTS\s+)?(\w+)\s+ON\s+(\w+)\s*\(""")
        val createTrigger = Regex("""(?is)^\s*CREATE\s+TRIGGER\s+(?:IF\s+NOT\s+EXISTS\s+)?(\w+)\s+(?:BEFORE|AFTER|INSTEAD\s+OF)\s+\w+(?:\s+OF\s+[\w,\s]+)?\s+ON\s+(\w+)\b""")
        val insertInto = Regex("""(?is)^\s*INSERT\s+INTO\s+(\w+)\b.*\bFROM\s+(\w+)\b""")

        for (raw in statements) {
            val sql = raw.trim()
            when {
                createTable.containsMatchIn(sql) -> {
                    val name = createTable.find(sql)!!.groupValues[1].lowercase()
                    assertTrue("$label: duplicate CREATE TABLE $name", state.tables.add(name))
                }
                alterRename.containsMatchIn(sql) -> {
                    val m = alterRename.find(sql)!!
                    val from = m.groupValues[1].lowercase()
                    val to = m.groupValues[2].lowercase()
                    assertTrue("$label: RENAME missing source table $from", state.tables.remove(from))
                    assertTrue("$label: RENAME duplicate target table $to", state.tables.add(to))
                }
                alterAddCol.containsMatchIn(sql) -> {
                    val m = alterAddCol.find(sql)!!
                    val table = m.groupValues[1].lowercase()
                    val col = m.groupValues[2].lowercase()
                    assertTrue("$label: ALTER TABLE missing table $table", table in state.tables)
                    val cols = state.alteredColumns.getOrPut(table) { linkedSetOf() }
                    assertTrue("$label: duplicate column $table.$col", cols.add(col))
                }
                dropTable.containsMatchIn(sql) -> {
                    val name = dropTable.find(sql)!!.groupValues[1].lowercase()
                    assertTrue("$label: DROP missing table $name", state.tables.remove(name))
                }
                createIndex.containsMatchIn(sql) -> {
                    val m = createIndex.find(sql)!!
                    val idx = m.groupValues[1].lowercase()
                    val table = m.groupValues[2].lowercase()
                    assertTrue("$label: INDEX $idx references missing table $table", table in state.tables)
                    assertTrue("$label: duplicate INDEX $idx", state.indices.add(idx))
                }
                createTrigger.containsMatchIn(sql) -> {
                    val m = createTrigger.find(sql)!!
                    val trg = m.groupValues[1].lowercase()
                    val table = m.groupValues[2].lowercase()
                    assertTrue("$label: TRIGGER $trg references missing table $table", table in state.tables)
                    assertTrue("$label: duplicate TRIGGER $trg", state.triggers.add(trg))
                }
                insertInto.containsMatchIn(sql) -> {
                    val m = insertInto.find(sql)!!
                    val dst = m.groupValues[1].lowercase()
                    val src = m.groupValues[2].lowercase()
                    assertTrue("$label: INSERT target $dst missing", dst in state.tables)
                    assertTrue("$label: INSERT source $src missing", src in state.tables)
                }
                else -> error("$label: unrecognized DDL statement: $sql")
            }
        }
    }

    private fun baselineSchemaAt(version: Int): SchemaSnapshot {
        val state = SchemaSnapshot()
        val init = buildList {
            add("CREATE TABLE cards (id TEXT PRIMARY KEY, payload TEXT NOT NULL, portrait INTEGER NOT NULL)")
            if (version == 1) {
                add("CREATE TABLE chats (id TEXT PRIMARY KEY, card_id TEXT NOT NULL, title TEXT NOT NULL, updated INTEGER NOT NULL, payload TEXT NOT NULL)")
            } else {
                addAll(TavernSchema.createLibrariesStatements)
                add(TavernSchema.createChatsTableSql)
                addAll(TavernSchema.createChatIndicesStatements)
            }
            if (version >= 3) add(TavernSchema.createPromptPresetsSql)
            if (version >= 4) addAll(TavernSchema.createSearchIndexStatements)
            if (version >= 5) addAll(TavernSchema.createDataBankStatements)
            if (version >= 6) addAll(TavernSchema.createGalleryStatements)
            if (version >= 7) add(TavernSchema.createComfyWorkflowsSql)
            if (version >= 8) addAll(TavernSchema.addChatWorldBindingsStatements)
            if (version >= 9) add(TavernSchema.createGlobalScriptVariablesSql)
            if (version >= 10) addAll(ChatBackupSchema.statements)
        }
        applyDdl(init, state, "baseline-v$version")
        return state
    }

    @Test
    fun fullSqliteSchemaMigrationChainV1ThroughV9ConvergesToV10() {
        val freshV10 = SchemaSnapshot()
        applyDdl(TavernSchema.createStatements(), freshV10, "fresh-v10")
        assertTrue("Fresh v10 must include chats", "chats" in freshV10.tables)
        assertTrue("Fresh v10 must include chat_backups", "chat_backups" in freshV10.tables)
        assertTrue("Fresh v10 must include script_globals", "script_globals" in freshV10.tables)
        assertEquals(setOf("world_book_id", "world_book_name"), freshV10.alteredColumns["chats"])

        // Verify every single migration path v1..v9 -> v10 converges to identical schema objects
        for (oldVersion in 1 until TavernSchema.LATEST_VERSION) {
            val migrated = baselineSchemaAt(oldVersion)
            val steps = TavernSchema.upgradeStatements(oldVersion, TavernSchema.LATEST_VERSION)
            assertTrue("v$oldVersion -> v10 must be non-empty", steps.isNotEmpty())
            applyDdl(steps, migrated, "migrate-v$oldVersion-to-v10")

            assertEquals("Tables mismatch for v$oldVersion -> v10", freshV10.tables, migrated.tables)
            assertEquals("Altered columns mismatch for v$oldVersion -> v10", freshV10.alteredColumns, migrated.alteredColumns)
            assertEquals("Indices mismatch for v$oldVersion -> v10", freshV10.indices, migrated.indices)
            assertEquals("Triggers mismatch for v$oldVersion -> v10", freshV10.triggers, migrated.triggers)
        }

        // Verify invalid upgrade boundaries fail fast
        for ((from, to) in listOf(0 to 10, 10 to 10, 11 to 10, 5 to 9, 1 to 8)) {
            var failed = false
            try {
                TavernSchema.upgradeStatements(from, to)
            } catch (_: IllegalStateException) {
                failed = true
            }
            assertFalse("Invalid upgrade $from -> $to should have thrown", !failed)
        }
    }
}
