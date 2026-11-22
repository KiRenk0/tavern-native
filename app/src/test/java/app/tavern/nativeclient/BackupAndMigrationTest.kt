package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupAndMigrationTest {
    private val tinyPng = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNgYAAAAAMAASsJTYQAAAAASUVORK5CYII=",
    )

    @Test
    fun databaseSchemaCreateAndUpgradePaths1Through9ReachV10Cleanly() {
        val createSql = TavernSchema.createStatements().joinToString("\n")
        assertEquals(10, TavernSchema.LATEST_VERSION)
        assertTrue(createSql.contains("CREATE TABLE cards"))
        assertTrue(createSql.contains("CREATE TABLE character_groups"))
        assertTrue(createSql.contains("CREATE TABLE world_books"))
        assertTrue(createSql.contains("CREATE TABLE chats"))
        assertTrue(createSql.contains("world_book_id TEXT NOT NULL DEFAULT ''"))
        assertTrue(createSql.contains("CREATE TABLE prompt_presets"))
        assertTrue(createSql.contains("CREATE TABLE vector_embeddings"))
        assertTrue(createSql.contains("CREATE TABLE data_bank"))
        assertTrue(createSql.contains("CREATE TABLE drawing_gallery"))
        assertTrue(createSql.contains("CREATE TABLE comfy_workflows"))
        assertTrue(createSql.contains("CREATE TABLE script_globals"))
        assertTrue(createSql.contains("CREATE TABLE chat_backups"))
        assertTrue(createSql.contains("capture_chat_before_update"))
        assertTrue(createSql.contains("capture_chat_before_delete"))

        for (oldVersion in 1..9) {
            val steps = TavernSchema.upgradeStatements(oldVersion, TavernSchema.LATEST_VERSION)
            assertEquals("Only v1–8 need the globals table", oldVersion < 9, steps.any { "script_globals" in it })
            assertTrue(steps.containsAll(ChatBackupSchema.statements))
            if (oldVersion == 1) {
                assertTrue(steps.any { "ALTER TABLE chats RENAME TO chats_v1" in it })
                assertTrue(steps.any { "world_books" in it })
            }
            if (oldVersion < 8) {
                assertTrue("v$oldVersion->10 must add chat world binding columns", steps.any { it.startsWith("ALTER TABLE chats ADD COLUMN world_book_id") })
            } else {
                assertFalse("Newer schemas already have world bindings", steps.any { it.startsWith("ALTER TABLE chats ADD COLUMN world_book_id") })
            }
        }
        assertEquals(ChatBackupSchema.statements, TavernSchema.upgradeStatements(9, 10))

        for (badOld in listOf(0, 10, 11)) {
            var threw = false
            try {
                TavernSchema.upgradeStatements(badOld, 10)
            } catch (_: IllegalStateException) {
                threw = true
            }
            assertTrue("Expected bad oldVersion $badOld to be rejected", threw)
        }
    }

    @Test
    fun legacyProviderMigrationAndChatJsonlRoundTripWork() {
        val legacyJson = jsonOf(
            "endpoint" to "https://api.example.com/v1",
            "apiKey" to "sealed:secret-key",
            "model" to "legacy-model-7b",
        )
        val migrated = Provider.legacy(legacyJson) { it.removePrefix("sealed:") }
        assertNotNull(migrated)
        assertEquals("legacy", migrated!!.id)
        assertEquals("https://api.example.com/v1", migrated.endpoint)
        assertEquals("secret-key", migrated.apiKey)
        assertEquals("legacy-model-7b", migrated.defaultModel)

        val chat = Chat(
            id = "chat-1",
            cardId = "card-1",
            title = "Moonlit Inn",
            messages = listOf(
                Message("user", "Traveler", "Enter the inn"),
                Message(
                    role = "assistant",
                    name = "Mira",
                    text = "Welcome inside.",
                    variants = listOf("Good evening.", "Welcome inside."),
                    index = 1,
                    reasoning = "Greet politely",
                    variantReasoning = listOf(null, "Greet politely"),
                    hidden = false,
                ),
            ),
            options = ConversationOptions(scenario = "Rainy night", variables = mapOf("gold" to "50")),
        )
        val jsonl = chat.jsonl("Mira", "Traveler").toString(Charsets.UTF_8)
        val rows = jsonl.lineSequence().filter(String::isNotBlank).map(::JSONObject).toList()
        val reimported = Chat.import("card-1", "Moonlit Inn", rows, id = "chat-2")
        assertEquals(2, reimported.messages.size)
        assertEquals("Welcome inside.", reimported.messages[1].text)
        assertEquals(listOf("Good evening.", "Welcome inside."), reimported.messages[1].variants)
        assertEquals(1, reimported.messages[1].index)
        assertEquals("Greet politely", reimported.messages[1].reasoning)
        assertEquals("Rainy night", reimported.options.scenario)
        assertEquals("50", reimported.options.variables["gold"])
    }

    @Test
    fun pngCharacterCardRoundTripCharXAndByafWork() {
        val card = Card.from(
            jsonOf(
                "spec" to "chara_card_v3",
                "spec_version" to "3.0",
                "data" to jsonOf(
                    "name" to "Elena",
                    "description" to "Clockwork artificer",
                    "first_mes" to "Careful with those gears, {{user}}.",
                ),
            ),
            id = "elena-1",
            hasAvatar = true,
        )
        val pngBytes = CardFile.png(card, tinyPng)
        val decoded = CardFile.read(pngBytes, id = "elena-2")
        assertEquals("Elena", decoded.card.name)
        assertEquals("Clockwork artificer", decoded.card.description)
        assertTrue(decoded.card.hasAvatar)
        assertNotNull(decoded.portrait)

        val tempDir = Files.createTempDirectory("tavern-archive-test").toFile()
        try {
            // CharX zip test
            val charxZip = ByteArrayOutputStream().apply {
                ZipOutputStream(this).use { zip ->
                    zip.putNextEntry(ZipEntry("card.json"))
                    zip.write(
                        jsonOf(
                            "spec" to "chara_card_v3",
                            "spec_version" to "3.0",
                            "data" to jsonOf(
                                "name" to "CharXHero",
                                "description" to "Packaged char",
                                "assets" to JSONArray().put(
                                    jsonOf("type" to "icon", "name" to "main", "uri" to "embeded://assets/icon.png", "ext" to "png"),
                                ),
                            ),
                        ).toString().toByteArray(),
                    )
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("assets/icon.png"))
                    zip.write(tinyPng)
                    zip.closeEntry()
                }
            }.toByteArray()

            val charxImported = readCharacterFiles(
                ByteArrayInputStream(charxZip), "hero.charx", tempDir, "Traveler",
                normalizePortrait = { it },
                saveImage = { _, label -> ChatImage("img-${label.hashCode()}.png", label, ImageFormat.PNG) },
            )
            assertEquals(1, charxImported.size)
            assertEquals("CharXHero", charxImported.single().card.card.name)
            assertNotNull(charxImported.single().card.portrait)

            // BYAF zip test
            val byafZip = ByteArrayOutputStream().apply {
                ZipOutputStream(this).use { zip ->
                    zip.putNextEntry(ZipEntry("manifest.json"))
                    zip.write(
                        jsonOf(
                            "characters" to JSONArray(listOf("chars/byaf_char.json")),
                            "scenarios" to JSONArray(listOf("scenarios/s1.json")),
                        ).toString().toByteArray(),
                    )
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("chars/byaf_char.json"))
                    zip.write(
                        jsonOf(
                            "name" to "ByafMage",
                            "persona" to "Greets {user} warmly.",
                        ).toString().toByteArray(),
                    )
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("scenarios/s1.json"))
                    zip.write(
                        jsonOf(
                            "title" to "Opening",
                            "firstMessages" to JSONArray(listOf(jsonOf("text" to "Hello {user}!"))),
                        ).toString().toByteArray(),
                    )
                    zip.closeEntry()
                }
            }.toByteArray()

            val byafImported = readCharacterFiles(
                ByteArrayInputStream(byafZip), "mage.byaf", tempDir, "Traveler",
                normalizePortrait = { it },
                saveImage = { _, label -> ChatImage("img-${label.hashCode()}.png", label, ImageFormat.PNG) },
            )
            assertEquals(1, byafImported.size)
            assertEquals("ByafMage", byafImported.single().card.card.name)
            assertTrue(byafImported.single().card.card.description.contains("{{user}}"))
            assertEquals(1, byafImported.single().chats.size)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun safeOriginalPathAndBackupZipAccountParsingWork() {
        assertTrue(safeOriginalPath("data/alice/settings.json"))
        assertFalse(safeOriginalPath("../etc/passwd"))
        assertFalse(safeOriginalPath("data/../../secret"))
        assertFalse(safeOriginalPath("data\\alice\\file.json"))
        assertFalse(safeOriginalPath("C:/windows/file.json"))
        assertFalse(safeOriginalPath("bad\u0000name"))
        assertFalse(safeOriginalPath(""))

        val backupZip = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { zip ->
                zip.putNextEntry(ZipEntry("SillyTavern/data/alice/settings.json"))
                zip.write("""{"theme":"Default"}""".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("SillyTavern/data/alice/characters/Hero.json"))
                zip.write("""{"name":"Hero","description":"Brave"}""".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

        val tempDir = Files.createTempDirectory("tavern-backup-zip-test").toFile()
        try {
            val seen = mutableListOf<Pair<String, List<String>>>()
            readOriginalBackup(ByteArrayInputStream(backupZip), tempDir, "alice-20261121-010000.zip", {}) { account, source ->
                seen += account to source.files("characters").map(OriginalLibraryFile::name)
            }
            assertEquals(1, seen.size)
            assertEquals("alice", seen.single().first)
            assertEquals(listOf("Hero.json"), seen.single().second)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
