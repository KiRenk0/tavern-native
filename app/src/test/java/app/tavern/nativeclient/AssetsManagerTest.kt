package app.tavern.nativeclient

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AssetsManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `parses official grouped manifest and filters by category and query`() {
        val manager = AssetsManager(tempFolder.newFolder("assets"))
        val raw = """
            {
              "themes": [
                {"name":"Midnight Ink","filename":"midnight.json","url":"https://raw.githubusercontent.com/SillyTavern/SillyTavern-Content/main/themes/midnight.json","tags":["dark"]}
              ],
              "backgrounds": [
                {"name":"Forest Glade","filename":"forest.png","url":"https://raw.githubusercontent.com/SillyTavern/SillyTavern-Content/main/backgrounds/forest.png","tags":["nature"]}
              ],
              "characters": [
                {"name":"Lyra","filename":"lyra.charx","url":"https://raw.githubusercontent.com/SillyTavern/SillyTavern-Content/main/characters/lyra.charx","tags":["fantasy"]}
              ],
              "sprites": [
                {"name":"Lyra Sprites","filename":"lyra.zip","url":"https://raw.githubusercontent.com/SillyTavern/SillyTavern-Content/main/sprites/lyra.zip"}
              ]
            }
        """.trimIndent()

        val items = manager.parseManifestJson(raw)
        assertEquals(4, items.size)
        assertEquals(1, manager.filterAssets(items, category = AssetCategory.THEME).size)
        assertEquals(2, manager.filterAssets(items, query = "lyra").size)
        assertEquals("Forest Glade", manager.filterAssets(items, query = "nature").single().name)
    }

    @Test
    fun `validates HTTPS official hosts and safe filenames against traversal and bad extensions`() {
        // Valid official URL
        val valid = AssetValidator.validateAssetUrl(
            "https://raw.githubusercontent.com/SillyTavern/SillyTavern-Content/main/themes/theme.json",
        )
        assertEquals("raw.githubusercontent.com", valid.host)

        // Reject HTTP, untrusted hosts, userinfo, and traversal
        listOf(
            "http://raw.githubusercontent.com/SillyTavern/theme.json",
            "https://evil.example.com/theme.json",
            "https://user:pass@raw.githubusercontent.com/theme.json",
            "https://raw.githubusercontent.com/SillyTavern/../secret.json",
            "file:///etc/passwd",
        ).forEach { badUrl ->
            try {
                AssetValidator.validateAssetUrl(badUrl)
                fail("Expected URL rejection for $badUrl")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }

        // Valid filenames
        assertEquals("Catppuccin-Mocha.json", AssetValidator.sanitizeAssetFilename("Catppuccin-Mocha.json", AssetCategory.THEME))
        assertEquals("hero_card.charx", AssetValidator.sanitizeAssetFilename("hero card.charx", AssetCategory.CHARACTER))

        // Reject path traversal & wrong category extension
        listOf(
            "../evil.json" to AssetCategory.THEME,
            "dir/file.png" to AssetCategory.BACKGROUND,
            "script.apk" to AssetCategory.CHARACTER,
            "image.png" to AssetCategory.THEME,
            "con.json" to AssetCategory.THEME,
        ).forEach { (badName, cat) ->
            try {
                AssetValidator.sanitizeAssetFilename(badName, cat)
                fail("Expected filename rejection for $badName in $cat")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun `downloads verifies sha256 installs and deletes asset atomically`() = runTest {
        val payload = """{"name":"Test Theme"}""".toByteArray()
        val digest = AssetValidator.sha256Hex(payload)
        val manager = AssetsManager(
            storageDir = tempFolder.newFolder("downloads"),
            http = object : AssetsHttpClient {
                override suspend fun getBytes(url: String, maxBytes: Int): ByteArray = payload
            },
        )
        val item = AssetManifestItem(
            id = "themes:test.json",
            name = "Test Theme",
            category = AssetCategory.THEME,
            downloadUrl = "https://raw.githubusercontent.com/SillyTavern/SillyTavern-Content/main/themes/test.json",
            filename = "test.json",
            sha256 = digest,
        )

        assertFalse(manager.isInstalled(item))
        val file = manager.downloadAsset(item)
        assertTrue(file.isFile)
        assertTrue(manager.isInstalled(item))
        assertEquals(1, manager.installedFiles(AssetCategory.THEME).size)

        // Bad sha256 fails verification
        val badItem = item.copy(sha256 = "0".repeat(64))
        try {
            manager.downloadAsset(badItem)
            fail("Expected SHA-256 mismatch error")
        } catch (_: IllegalArgumentException) {
            // expected
        }

        assertTrue(manager.deleteAsset(item))
        assertFalse(manager.isInstalled(item))
    }
}
