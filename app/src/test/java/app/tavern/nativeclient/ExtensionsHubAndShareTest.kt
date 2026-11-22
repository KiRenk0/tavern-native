package app.tavern.nativeclient

import app.tavern.nativeclient.backup.SettingsSnapshot
import app.tavern.nativeclient.script.CardSandboxPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionsHubAndShareTest {

    @Test
    fun `ExtensionRegistry manages built-in and custom extensions and portable JSON export-import`() {
        val builtIns = ExtensionRegistry.builtInDescriptors.map { it.id }.toSet()
        assertTrue(BuiltInExtensionId.QUICK_PROMPT_TOGGLES.id in builtIns)
        assertTrue(BuiltInExtensionId.CHAT_STATUS_INSPECTOR.id in builtIns)
        assertTrue(BuiltInExtensionId.CHARACTER_LORE_HELPER.id in builtIns)
        assertTrue(BuiltInExtensionId.WEB_SEARCH.id in builtIns)
        assertTrue(BuiltInExtensionId.DYNAMIC_AUDIO.id in builtIns)
        assertTrue(BuiltInExtensionId.ASSETS_DOWNLOADER.id in builtIns)

        val custom = ExtensionDescriptor(
            id = "custom_dice_roller",
            displayName = "Dice Roller",
            description = "Custom tabletop dice extension",
            category = ExtensionCategory.CHAT_TOOLS,
            defaultEnabled = true,
            builtIn = false,
        )
        ExtensionRegistry.register(custom)
        assertEquals("Dice Roller", ExtensionRegistry.find("custom_dice_roller")?.displayName)
        assertTrue(ExtensionRegistry.unregister("custom_dice_roller"))
        assertNull(ExtensionRegistry.find("custom_dice_roller"))

        val config = ExtensionHubConfig(
            quickPromptToggles = listOf(
                QuickPromptToggleItem(id = "q1", label = "Concise", promptText = "Keep replies concise.", enabled = true),
                QuickPromptToggleItem(id = "q2", label = "Disabled", promptText = "Ignored.", enabled = false),
            ),
            webSearch = WebSearchConfig(
                enabled = true,
                backend = WebSearchProvider.TAVILY,
                apiKey = "super-secret-key",
                triggerMode = WebSearchTriggerMode.KEYWORD_REGEX,
                maxResults = 7,
            ),
            dynamicAudio = DynamicAudioConfig(
                enabled = true,
                masterVolume = 0.65f,
                crossfadeMs = 2000,
                tracks = listOf(AudioTrack(id = "t1", name = "Campfire", uri = "file:///camp.mp3", channel = AudioChannel.AMBIENT)),
            ),
            cardSandboxPolicies = mapOf(
                "card-42" to CardSandboxPolicy(
                    allowScripts = true,
                    allowNetwork = true,
                    allowedDomains = listOf("cdn.example.com"),
                    allowMessageWrite = true,
                ),
            ),
        ).withEnabled(BuiltInExtensionId.WEB_SEARCH, true)

        assertEquals(1, config.activeQuickPrompts().size)
        assertEquals("Concise", config.activeQuickPrompts().single().label)

        // Export strips API key secret
        val exported = config.exportPortableJson()
        val exportedText = exported.toString()
        assertFalse(exportedText.contains("super-secret-key"))
        assertEquals(EXTENSIONS_PORTABLE_FORMAT, exported.getString("format"))

        // Import back preserves existing key when endpoint/backend match
        val reimported = ExtensionHubConfig.importPortableJson(exportedText, current = config)
        assertEquals("super-secret-key", reimported.webSearch.apiKey)
        assertEquals(7, reimported.webSearch.maxResults)
        assertEquals(2000, reimported.dynamicAudio.crossfadeMs)
        assertEquals("Campfire", reimported.dynamicAudio.tracks.single().name)
        assertTrue(reimported.policyForCard("card-42", globalScriptsEnabled = false).allowNetwork)
        assertEquals(listOf("cdn.example.com"), reimported.policyForCard("card-42", false).normalizedDomains)

        // SettingsSnapshot roundtrip preserves extensions & retains API key cleanly
        val prefs = Preferences(extensions = config)
        val snapshot = SettingsSnapshot.capture("Backup 1", prefs) { it }
        val portableBytes = snapshot.portableBytes()
        assertFalse(portableBytes.decodeToString().contains("super-secret-key"))
        val restored = SettingsSnapshot.read(portableBytes).restore(prefs) { it }
        assertEquals("super-secret-key", restored.extensions.webSearch.apiKey)
        assertEquals(1, restored.extensions.dynamicAudio.tracks.size)
        assertEquals("Concise", restored.extensions.activeQuickPrompts().single().label)
    }

    @Test
    fun `ShareImportHandler recognizes png json charx byaf and rejects unsupported files`() {
        assertEquals(ShareCardFormat.PNG, ShareImportHandler.detectFormat("alice.png", "image/png"))
        assertEquals(ShareCardFormat.JSON, ShareImportHandler.detectFormat("bob.JSON", "application/json"))
        assertEquals(ShareCardFormat.CHARX, ShareImportHandler.detectFormat("char_pack.charx", "application/octet-stream"))
        assertEquals(ShareCardFormat.BYAF, ShareImportHandler.detectFormat("story.byaf", "application/zip"))
        assertEquals(ShareCardFormat.PNG, ShareImportHandler.detectFormat("shared_image", "image/png"))
        assertEquals(ShareCardFormat.JSON, ShareImportHandler.detectFormat(null, "application/json; charset=utf-8"))

        assertNull(ShareImportHandler.detectFormat("movie.mp4", "video/mp4"))
        assertNull(ShareImportHandler.detectFormat("malware.apk", "application/octet-stream"))
        assertNull(ShareImportHandler.detectFormat("notes.txt", "text/plain"))

        val plan = ShareImportHandler.buildImportPlanFromItems(
            listOf(
                "alice.png" to "image/png",
                "bob.json" to "application/json",
                "pack.charx" to "application/octet-stream",
                "world.byaf" to "application/zip",
                "video.mp4" to "video/mp4",
            ),
        )
        assertEquals(4, plan.candidates.size)
        assertEquals(1, plan.rejectedCount)
        assertEquals(
            listOf(ShareCardFormat.PNG, ShareCardFormat.JSON, ShareCardFormat.CHARX, ShareCardFormat.BYAF),
            plan.candidates.map { it.format },
        )
    }
}
