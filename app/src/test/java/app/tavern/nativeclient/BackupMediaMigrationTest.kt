package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupMediaMigrationTest {
    private val tinyPng = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNgYAAAAAMAASsJTYQAAAAASUVORK5CYII=",
    )
    private val tinyJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10)

    @Test
    fun pathTraversalHardeningBlocksEncodedAndReservedPaths() {
        assertTrue(safeOriginalPath("User Avatars/traveler.png"))
        assertTrue(safeOriginalPath("backgrounds/tavern/night.jpg"))
        assertTrue(safeOriginalPath("user/assets/props/sword.webp"))

        // ST 1.19.1 security hardening checks
        val unsafePaths = listOf(
            "",
            "../secret.png",
            "user/images/../../etc/passwd",
            "user/images/%2e%2e/secret.png",
            "user/images/%252e%252e/secret.png",
            "user/images/foo%2fbar.png",
            "user/images/foo%5cbar.png",
            "user/images/bad%00.png",
            "user\\images\\pic.png",
            "C:/windows/win.ini",
            "/leading/slash.png",
            "trailing/slash/",
            "User Avatars/CON.png",
            "User Avatars/nul",
            "User Avatars/trail.",
            "User Avatars/space ",
            "bad\nline.png",
            (1..20).joinToString("/") { "d$it" },
        )
        for (path in unsafePaths) {
            assertFalse("Expected unsafe path to be rejected: $path", safeOriginalPath(path))
        }

        assertEquals("user/images/My Char/pic.png", resolveOriginalMediaPath("/user/images/My%20Char/pic.png"))
        assertEquals("User Avatars/hero.png", resolveOriginalMediaPath("User Avatars/hero.png"))
        assertEquals("user/assets/map.webp", resolveOriginalMediaPath("./user/assets/map.webp"))
        assertEquals("assets/cg/ending.jpg", resolveOriginalMediaPath("/assets/cg/ending.jpg"))
        assertNull(resolveOriginalMediaPath("https://evil.example/user/images/a.png"))
        assertNull(resolveOriginalMediaPath("user/images/%2e%2e/secret.png"))
        assertNull(resolveOriginalMediaPath("user/images/script.js"))
    }

    @Test
    fun backupZipRestoresUserAvatarsNestedBackgroundsAndExtraMediaWithSafeguards() {
        val backupZip = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { zip ->
                zip.putNextEntry(ZipEntry("data/default-user/User Avatars/traveler.png"))
                zip.write(tinyPng)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("data/default-user/User Avatars/knight.jpg"))
                zip.write(tinyJpeg)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("data/default-user/User Avatars/corrupt.png"))
                zip.write("not-an-image".encodeToByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("data/default-user/backgrounds/tavern/night.png"))
                zip.write(tinyPng)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("data/default-user/user/images/Mira/extra/cg1.png"))
                zip.write(tinyPng)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("data/default-user/user/assets/chat_prop.png"))
                zip.write(tinyPng)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("data/default-user/assets/custom_banner.jpg"))
                zip.write(tinyJpeg)
                zip.closeEntry()
            }
        }.toByteArray()

        val tempDir = Files.createTempDirectory("tavern-migration-test").toFile()
        try {
            var restoredCount = 0
            readOriginalBackup(ByteArrayInputStream(backupZip), tempDir, "default-user-20261122-010000.zip", {}) { account, source ->
                assertEquals("default-user", account)
                val media = OriginalLibraryMedia(source, documents = null, images = null, checkActive = {})

                // User Avatars discovery & header validation
                val avatars = media.userAvatarFiles()
                assertEquals(setOf("traveler.png", "knight.jpg", "corrupt.png"), avatars.keys)
                assertArrayEquals(tinyPng, media.readPersonaAvatar(avatars.getValue("traveler.png")))
                assertArrayEquals(tinyJpeg, media.readPersonaAvatar(avatars.getValue("knight.jpg")))
                assertNull(media.readPersonaAvatar(avatars.getValue("corrupt.png")))
                assertEquals(2, media.personaAvatarCount)
                assertEquals(1, media.missing)

                // Recursive enumeration of nested backgrounds, gallery, and assets
                assertEquals(
                    listOf("backgrounds/tavern/night.png"),
                    source.allFiles("backgrounds").map(OriginalLibraryFile::relativePath),
                )
                assertEquals(
                    listOf("user/images/Mira/extra/cg1.png"),
                    source.allFiles("user/images/Mira").map(OriginalLibraryFile::relativePath),
                )
                assertEquals(
                    listOf("user/assets/chat_prop.png"),
                    source.allFiles("user/assets").map(OriginalLibraryFile::relativePath),
                )
                assertEquals(
                    listOf("assets/custom_banner.jpg"),
                    source.allFiles("assets").map(OriginalLibraryFile::relativePath),
                )
                restoredCount++
            }
            assertEquals(1, restoredCount)

            // Malicious ZIP with encoded traversal is rejected outright
            val traversalZip = ByteArrayOutputStream().apply {
                ZipOutputStream(this).use { zip ->
                    zip.putNextEntry(ZipEntry("data/default-user/User Avatars/%2e%2e/pwn.png"))
                    zip.write(tinyPng)
                    zip.closeEntry()
                }
            }.toByteArray()
            assertTrue(
                runCatching {
                    readOriginalBackup(ByteArrayInputStream(traversalZip), tempDir, "evil.zip", {}) { _, _ -> }
                }.isFailure,
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun folderLibraryAllFilesWalksNestedMediaAndExportResultTracksAvatars() {
        val root = Files.createTempDirectory("tavern-folder-lib").toFile()
        try {
            val bgNested = File(root, "backgrounds/forest/dawn.png").apply {
                parentFile!!.mkdirs()
                writeBytes(tinyPng)
            }
            val avatar = File(root, "User Avatars/me.png").apply {
                parentFile!!.mkdirs()
                writeBytes(tinyPng)
            }
            val asset = File(root, "user/assets/emblem.jpg").apply {
                parentFile!!.mkdirs()
                writeBytes(tinyJpeg)
            }
            assertTrue(bgNested.isFile && avatar.isFile && asset.isFile)

            val lib = FolderOriginalLibrary(root)
            assertEquals(listOf("backgrounds/forest/dawn.png"), lib.allFiles("backgrounds").map { it.relativePath })
            assertEquals(listOf("User Avatars/me.png"), lib.allFiles("User Avatars").map { it.relativePath })
            assertEquals(listOf("user/assets/emblem.jpg"), lib.allFiles("user/assets").map { it.relativePath })

            assertTrue(isSupportedImageHeader(tinyPng))
            assertTrue(isSupportedImageHeader(tinyJpeg))
            assertFalse(isSupportedImageHeader("plain text".encodeToByteArray()))

            val exportResult = BackupExportResult(cards = 2, groups = 1, chats = 3, books = 1, missing = 0, personaAvatars = 2, mediaFiles = 5)
            assertEquals(2, exportResult.personaAvatars)
            assertEquals(5, exportResult.mediaFiles)

            // Message extra media referencing user/assets resolves cleanly
            val rawMsg = JSONObject().put(
                "extra",
                JSONObject().put(
                    "media",
                    JSONArray().put(JSONObject().put("url", "/user/assets/emblem.jpg").put("type", "image")),
                ),
            )
            assertEquals(
                "user/assets/emblem.jpg",
                resolveOriginalMediaPath(rawMsg.getJSONObject("extra").getJSONArray("media").getJSONObject(0).getString("url")),
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
