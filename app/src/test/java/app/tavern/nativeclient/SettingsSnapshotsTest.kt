package app.tavern.nativeclient

import app.tavern.nativeclient.backup.SettingsSnapshot
import app.tavern.nativeclient.backup.SettingsSnapshotStore
import app.tavern.nativeclient.backup.SyncSettings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SettingsSnapshotsTest {
    private val provider = Provider("custom", "Proxy", "https://service.example/v1", "old-fake-key", "model-a")
    private val note = AuthorNote("Remember the archive.", frequency = 2, depth = 5)
    private val prefs = Preferences(
        providers = listOf(provider),
        defaultSelection = provider.defaultSelection,
        theme = "dark",
        temperature = "0.76",
        maxTokens = "2048",
        scriptVariables = mapOf("story" to "prior"),
        defaultAuthorNote = note,
        voice = VoicePreferences(ttsBackend = TtsBackend.ELEVENLABS, ttsEndpoint = "https://voice.example",
            ttsApiKey = "fake-voice-token", sttBackend = SttBackend.WHISPER_API, sttEndpoint = "https://listen.example",
            sttApiKey = "fake-speech-token"),
        sync = SyncSettings(webDavUrl = "https://cloud.example/backup", webDavUsername = "author",
            webDavPassword = "fake-sync-password", lastSyncAt = 1560, lastSyncSummary = "test"),
        translationServices = TranslationServices(profiles = mapOf(TranslationSource.DEEPL to TranslationProfile(
            "https://translation.example", "fake-translation-key", "test-region"))),
        chatAppearance = ChatAppearance(showMessageTime = true, showMessageTokens = true),
        requestTokenProbabilities = true,
        replyAutomation = ReplyAutomation(autoContinue = true, targetTokens = 250),
    )

    private fun seal(secret: String) = if (secret.isEmpty()) "" else "sealed-$secret"
    private fun unseal(value: String) = value.takeIf { it.startsWith("sealed-") }?.removePrefix("sealed-").orEmpty()

    @Test
    fun nativePreferencesStillReadTheExistingStorageFormat() {
        val storage = encodePreferences(prefs, ::seal)
        assertEquals(prefs, decodePreferences(storage, ::unseal, prefs.scriptVariables))
    }

    @Test
    fun localCaptureEncryptsCredentialsAndPortableExportDropsThem() {
        val snapshot = SettingsSnapshot.capture("Before theme edit", prefs, ::seal)
        val stored = snapshot.bytes().decodeToString()
        assertTrue(stored.contains("sealed-old-fake-key"))
        val local = SettingsSnapshot.read(snapshot.bytes())
        // A local recovery point can recover a removed provider.
        val restored = local.restore(Preferences(scriptVariables = mapOf("story" to "now")), ::unseal)
        assertEquals(provider, restored.providers.single())
        assertEquals(prefs.voice, restored.voice)
        assertEquals(prefs.sync.webDavPassword, restored.sync.webDavPassword)
        assertEquals(prefs.translationServices, restored.translationServices)
        assertEquals(mapOf("story" to "now"), restored.scriptVariables)
        assertEquals(0L, restored.sync.lastSyncAt)
        assertEquals(prefs.chatAppearance, restored.chatAppearance)

        val bytes = local.portableBytes()
        for (key in listOf("old-fake-key", "fake-voice-token", "fake-speech-token", "fake-sync-password", "fake-translation-key")) {
            assertFalse(bytes.decodeToString().contains(key))
        }
        val imported = SettingsSnapshot.import(bytes, "export.json", Preferences()).restore(Preferences(), ::unseal)
        assertEquals(prefs.defaultSelection, imported.defaultSelection)
        assertTrue(imported.providers.single().apiKey.isEmpty())
        assertTrue(imported.voice.sttApiKey.isEmpty())
        assertTrue(imported.voice.ttsApiKey.isEmpty())
        assertTrue(imported.sync.webDavPassword.isEmpty())
        assertTrue(imported.translationServices.profiles.getValue(TranslationSource.DEEPL).key.isEmpty())
    }

    @Test
    fun importedSnapshotCannotAttachExistingOrEmbeddedKeysToChangedDestinations() {
        val current = prefs.copy(providers = listOf(provider.copy(apiKey = "rotated")))
        val preserved = SettingsSnapshot.capture("snapshot", prefs, ::seal).restore(current, ::unseal)
        assertEquals("rotated", preserved.providers.single().apiKey)
        assertEquals(current.sync.lastSyncAt, preserved.sync.lastSyncAt)
        val forged = JSONObject(SettingsSnapshot.capture("snapshot", prefs, ::seal).bytes().decodeToString())
        val config = forged.getJSONObject("preferences")
        config.getJSONArray("providers").getJSONObject(0).put("endpoint", "https://changed.example")
        config.getJSONObject("voice").put("ttsEndpoint", "https://changed.example").put("sttEndpoint", "https://changed.example")
        config.getJSONObject("sync").put("webDavUsername", "someone-else")
        config.getJSONObject("translationServices").getJSONObject("profiles").getJSONObject("DEEPL").put("pro", true)
        val imported = SettingsSnapshot.import(forged.toString().encodeToByteArray(), "untrusted.json", current)
        val restored = imported.restore(current, ::unseal)
        assertTrue(restored.providers.single().apiKey.isEmpty())
        assertTrue(restored.voice.ttsApiKey.isEmpty() && restored.voice.sttApiKey.isEmpty())
        assertTrue(restored.sync.webDavPassword.isEmpty())
        assertTrue(restored.translationServices.profiles.getValue(TranslationSource.DEEPL).key.isEmpty())
    }

    @Test
    fun importsKnownSettingsFromOriginalJsonAndKeepsUnspecifiedSettings() {
        val raw = jsonOf(
            "power_user" to jsonOf("font_scale" to 1.25, "waifuMode" to true, "timer_enabled" to true,
                "chat_display" to 0, "avatar_style" to 3, "hideChatAvatars_enabled" to true,
                "chat_width" to 70, "mesIDDisplay_enabled" to true, "show_swipe_num_all_messages" to true,
                "click_to_edit" to true, "compact_input_area" to true, "media_display" to "gallery",
                "reduced_motion" to true, "toastr_position" to "toast-top-right",
                "gestures" to true, "show_group_chat_queue" to true, "timestamp_model_icon" to true, "hotswap_enabled" to true,
                "send_on_enter" to 1, "quick_continue" to true, "quick_impersonate" to true,
                "auto_scroll_chat_to_bottom" to false, "continue_on_send" to true,
                "auto_save_msg_edits" to true, "confirm_message_delete" to false, "auto_fix_generated_markdown" to true,
                "play_message_sound" to true, "play_sound_unfocused" to false,
                "request_token_probabilities" to false,
                "auto_continue" to jsonOf("enabled" to true, "target_length" to 400, "allow_chat_completions" to true)),
            "swipes" to false,
            "oai_settings" to jsonOf("temperature" to 0.66, "openai_max_tokens" to 512),
            "world_info_settings" to jsonOf("world_info_recursive" to true),
            "extension_settings" to jsonOf("note" to jsonOf("default" to "Only change the note", "allowWIScan" to true),
                "regex" to JSONArray().put(jsonOf("id" to "st-rule", "scriptName" to "Prefix", "findRegex" to "abc"))),
            "background" to jsonOf("fitting" to "contain"))
        val snapshot = SettingsSnapshot.import(raw.toString().encodeToByteArray(), "settings_2026.json", prefs)
        val restored = snapshot.restore(prefs, ::unseal)
        assertEquals("settings_2026", snapshot.name)
        assertEquals("0.66", restored.temperature)
        assertEquals("512", restored.maxTokens)
        assertEquals(20f, restored.chatAppearance.fontSizeSp)
        assertEquals(ChatLayoutMode.FLAT, restored.chatAppearance.layoutMode)
        assertEquals(ChatAvatarStyle.ROUNDED, restored.chatAppearance.avatarStyle)
        assertEquals(70, restored.chatAppearance.chatWidthPercent)
        assertTrue(restored.chatAppearance.hideAvatars)
        assertTrue(restored.chatAppearance.showMessageIds)
        assertTrue(restored.chatAppearance.showAllSwipeCounts)
        assertTrue(restored.chatAppearance.clickToEdit && restored.chatAppearance.compactInput)
        assertEquals(MessageMediaDisplay.GALLERY, restored.chatAppearance.defaultMediaDisplay)
        assertTrue(restored.chatAppearance.reducedMotion)
        assertEquals(NoticePosition.TOP_RIGHT, restored.chatAppearance.noticePosition)
        assertTrue(restored.swipeGestures && restored.showGroupQueue)
        assertTrue(restored.chatAppearance.showModelIcon && restored.chatAppearance.favoriteHotswap)
        assertEquals(ChatHandling(EnterToSend.ENABLED, continueOnSend = true, quickContinue = true,
            quickImpersonate = true, autoScroll = false, autoSaveEdits = true, confirmDelete = false,
            showSwipes = false, repairMarkdown = true, replySound = true, replySoundInBackgroundOnly = false), restored.chatHandling)
        val original = JSONObject().also(restored.chatAppearance::writeOriginal)
        assertEquals(restored.chatAppearance, ChatAppearance.fromOriginal(original, prefs.chatAppearance))
        assertTrue(restored.chatAppearance.showGenerationTimer)
        assertTrue(restored.chatAppearance.showMessageTokens)
        assertFalse(restored.requestTokenProbabilities)
        assertEquals(400, restored.replyAutomation.targetTokens)
        assertTrue(restored.visualNovel && restored.loreSettings.recursive)
        assertEquals(BackgroundFit.CONTAIN, restored.backgrounds.fit)
        assertEquals(note.copy(text = "Only change the note", scan = true), restored.defaultAuthorNote)
        assertEquals("Prefix", restored.regex.global.single().name)
        assertEquals(prefs.providers, restored.providers)
        assertEquals(prefs.theme, restored.theme)
        assertEquals(prefs.scriptVariables, restored.scriptVariables)
    }

    @Test
    fun fileStoreCreatesRenamesAndDeletesAndRejectsInvalidInput() {
        val directory = Files.createTempDirectory("tavern-settings-snapshots").toFile()
        try {
            val store = SettingsSnapshotStore(directory)
            val saved = SettingsSnapshot.capture("first", prefs, ::seal)
            val id = store.insert(saved)
            store.rename(id, " Renamed ")
            assertEquals("Renamed", store.list().items.single().name)
            assertEquals(prefs.theme, store.read(id).restore(prefs, ::unseal).theme)
            assertTrue(runCatching { store.read("../other") }.isFailure)
            assertTrue(runCatching { SettingsSnapshot.import("{}".encodeToByteArray(), "other.json", prefs) }.isFailure)
            val mismatched = JSONObject(saved.bytes().decodeToString()).put("version", 99)
            assertTrue(runCatching { SettingsSnapshot.import(mismatched.toString().encodeToByteArray(), "snapshot.json", prefs) }.isFailure)
            File(directory, "ce68768b-8319-4da5-954c-dc0de3de730a.json").writeText("{")
            assertEquals(1, store.list().unreadable)
            store.delete(id)
            assertTrue(store.list().items.isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }
}
