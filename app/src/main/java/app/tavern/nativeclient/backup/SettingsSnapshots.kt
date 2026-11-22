package app.tavern.nativeclient.backup

import app.tavern.nativeclient.*
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal const val SETTINGS_SNAPSHOT_LIMIT = 24 * 1024 * 1024
private const val SNAPSHOT_TAG = "tavern-native-settings"

internal data class SettingsSnapshotSummary(val id: String, val name: String, val created: Long)
internal data class SettingsSnapshotIndex(val items: List<SettingsSnapshotSummary>, val unreadable: Int)

internal class SettingsSnapshot private constructor(val name: String, val created: Long, private val config: JSONObject) {
    fun renamed(value: String) = SettingsSnapshot(snapshotName(value), created, config)

    fun bytes() = jsonOf("format" to SNAPSHOT_TAG, "version" to 1, "name" to name, "created" to created,
        "preferences" to config).toString().encodeToByteArray()

    fun portableBytes() = SettingsSnapshot(name, created, encodePreferences(decodePreferences(config, { "" }), { "" })).bytes()

    fun restore(current: Preferences, unseal: (String) -> String): Preferences {
        val saved = decodePreferences(config, unseal, current.scriptVariables)
        val providers = current.providers.associateBy(Provider::id)
        val voice = saved.voice
        val currentVoice = current.voice
        val sync = saved.sync
        val currentSync = current.sync
        val sameSync = sameAddress(sync.webDavUrl, currentSync.webDavUrl) && sync.webDavUsername == currentSync.webDavUsername
        val translation = saved.translationServices
        fun retainedTtsKey(backend: TtsBackend, credentials: TtsCredentials): String =
            currentVoice.credentialsFor(backend)?.takeIf { sameAddress(it.endpoint, credentials.endpoint) }?.key ?: credentials.key
        return saved.copy(
            providers = saved.providers.distinctBy(Provider::id).map { provider ->
                val existing = providers[provider.id]?.takeIf {
                    sameAddress(it.endpoint, provider.endpoint) && it.template == provider.template && it.protocol == provider.protocol
                }
                provider.copy(apiKey = existing?.apiKey ?: provider.apiKey)
            },
            voice = voice.copy(
                ttsApiKey = voice.credentialsFor(voice.activeBackend)?.let { retainedTtsKey(voice.activeBackend, it) }.orEmpty(),
                inactiveTtsCredentials = voice.inactiveTtsCredentials.mapValues { (backend, credentials) ->
                    credentials.copy(key = retainedTtsKey(backend, credentials))
                },
                sttApiKey = if (voice.sttBackend == currentVoice.sttBackend && sameAddress(voice.sttEndpoint, currentVoice.sttEndpoint))
                    currentVoice.sttApiKey else voice.sttApiKey,
                rvc = voice.rvc.copy(
                    apiKey = if (sameAddress(voice.rvc.endpoint, currentVoice.rvc.endpoint)) currentVoice.rvc.apiKey else voice.rvc.apiKey,
                ),
            ),
            sync = sync.copy(
                webDavPassword = if (sameSync) currentSync.webDavPassword else sync.webDavPassword,
                lastSyncAt = if (sameSync) currentSync.lastSyncAt else 0,
                lastSyncSummary = if (sameSync) currentSync.lastSyncSummary else "",
            ),
            translationServices = translation.copy(profiles = translation.profiles.mapValues { (source, profile) ->
                val existing = current.translationServices.profiles[source]?.takeIf {
                    sameAddress(it.endpoint, profile.endpoint) && it.region == profile.region && it.pro == profile.pro
                }
                profile.copy(key = existing?.key ?: profile.key)
            }),
            summaryExtras = if (!config.has("summaryExtras")) current.summaryExtras else {
                val extras = saved.summaryExtras
                extras.copy(apiKey = if (sameAddress(extras.endpoint, current.summaryExtras.endpoint)) current.summaryExtras.apiKey else extras.apiKey)
            },
            extensions = if (!config.has("extensions")) current.extensions else {
                val ext = saved.extensions
                val ws = ext.webSearch
                val curWs = current.extensions.webSearch
                val key = if (ws.apiKey.isBlank() && ws.backend == curWs.backend && sameAddress(ws.effectiveEndpoint, curWs.effectiveEndpoint)) {
                    curWs.apiKey
                } else {
                    ws.apiKey
                }
                ext.copy(webSearch = ws.copy(apiKey = key))
            },
        )
    }

    companion object {
        fun capture(name: String, prefs: Preferences, seal: (String) -> String): SettingsSnapshot {
            val clean = prefs.copy(sync = prefs.sync.copy(lastSyncAt = 0, lastSyncSummary = ""))
            return SettingsSnapshot(snapshotName(name), System.currentTimeMillis(), encodePreferences(clean, seal))
        }

        fun read(bytes: ByteArray): SettingsSnapshot = readNative(snapshotObject(bytes))

        fun import(bytes: ByteArray, filename: String, current: Preferences): SettingsSnapshot {
            val root = snapshotObject(bytes)
            if (root.string("format") == SNAPSHOT_TAG) {
                val incoming = readNative(root)
                val safe = encodePreferences(decodePreferences(incoming.config, { "" }), { "" })
                return SettingsSnapshot(incoming.name, incoming.created, safe)
            }
            return capture(filename.substringBeforeLast('.').ifBlank { "导入的原版设置" }, applyOriginalSettings(root, current)) { "" }
        }

        private fun readNative(root: JSONObject): SettingsSnapshot {
            require(root.string("format") == SNAPSHOT_TAG && root.optInt("version") == 1) { "无法识别设置快照版本" }
            val settings = requireNotNull(root.optJSONObject("preferences")) { "快照缺少设置数据" }
            require(settings.optJSONArray("providers") != null && settings.optJSONObject("chatAppearance") != null) {
                "快照的设置数据不完整"
            }
            return SettingsSnapshot(snapshotName(root.string("name")), root.optLong("created").coerceAtLeast(0), settings)
        }
    }
}

internal class SettingsSnapshotStore(private val directory: File) {
    fun list(): SettingsSnapshotIndex {
        var unreadable = 0
        val items = directory.listFiles().orEmpty().mapNotNull { file ->
            val id = file.nameWithoutExtension.takeIf { file.isFile && file.extension == "json" && portableAccountId(it) != null }
                ?: return@mapNotNull null
            try {
                val item = read(id)
                SettingsSnapshotSummary(id, item.name, item.created)
            } catch (_: Exception) {
                unreadable++
                null
            }
        }
        return SettingsSnapshotIndex(items.sortedByDescending(SettingsSnapshotSummary::created), unreadable)
    }

    fun insert(snapshot: SettingsSnapshot) = newId().also { write(it, snapshot) }

    fun read(id: String) = file(id).inputStream().use { SettingsSnapshot.read(it.readLimited(SETTINGS_SNAPSHOT_LIMIT)) }

    fun rename(id: String, name: String) = write(id, read(id).renamed(name))

    fun delete(id: String) {
        check(file(id).delete()) { "无法删除设置快照" }
    }

    private fun write(id: String, snapshot: SettingsSnapshot) {
        val target = file(id)
        val bytes = snapshot.bytes()
        require(bytes.size <= SETTINGS_SNAPSHOT_LIMIT) { "设置快照超过文件大小限制" }
        Files.createDirectories(directory.toPath())
        val staged = Files.createTempFile(directory.toPath(), ".snapshot-", ".tmp")
        try {
            Files.write(staged, bytes)
            try {
                Files.move(staged, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staged, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(staged)
        }
    }

    private fun file(id: String): File {
        require(portableAccountId(id) != null) { "找不到设置快照" }
        return File(directory, "$id.json")
    }
}

private fun snapshotObject(bytes: ByteArray): JSONObject =
    boundedJsonObject(bytes.decodeToString().trimStart('\uFEFF'), "无法读取设置快照 JSON")

private fun snapshotName(value: String): String = value.filterNot(Char::isISOControl).trim().take(80)
    .dropLastWhile(Char::isHighSurrogate).also { require(it.isNotBlank()) { "请输入快照名称" } }

private fun sameAddress(first: String, second: String) = first.trim() == second.trim()

private fun applyOriginalSettings(root: JSONObject, current: Preferences): Preferences {
    val power = root.optJSONObject("power_user")
    val extensions = root.optJSONObject("extension_settings")
    val completion = root.optJSONObject("oai_settings")
    val lore = root.optJSONObject("world_info_settings")
    val background = root.optJSONObject("background")
    require(root.has("swipes") || listOf(power, extensions, completion, lore, background).any { it != null }) {
        "文件不是 Tavern Native 或 SillyTavern 设置快照"
    }

    val appearance = power?.optJSONObject("tavern_native_chat_appearance")?.let(ChatAppearance::from)
        ?: ChatAppearance.fromOriginal(power, current.chatAppearance)
    val automation = current.replyAutomation.json()
    overlaySettings(automation, power)
    val scan = current.loreSettings.json()
    overlaySettings(scan, lore)
    val author = extensions?.optJSONObject("note")
    val note = current.defaultAuthorNote
    val importedNote = if (author == null) note else AuthorNote.fromMetadata(note.json().apply {
        mapOf("default" to "note_prompt", "defaultInterval" to "note_interval", "defaultDepth" to "note_depth",
            "defaultPosition" to "note_position", "defaultRole" to "note_role", "allowWIScan" to "tavern_native_note_scan")
            .forEach { (old, key) -> if (!author.isNull(old)) put(key, author.get(old)) }
    }) ?: note
    val regex = extensions?.optJSONArray("regex")?.objects()?.mapNotNull(RegexRule::from)
    val temperature = completion?.string("temperature")?.takeIf { it.toDoubleOrNull()?.let { n -> n in 0.0..2.0 } == true }
    val output = completion?.string("openai_max_tokens")?.takeIf { (it.toIntOrNull() ?: 0) > 0 }
    val fit = background?.string("fitting")?.takeIf(String::isNotEmpty)?.let(BackgroundFit::from)
    val originalSystem = power?.optJSONObject("sysprompt")
    val originalReasoning = power?.optJSONObject("reasoning")
    val formatting = current.replyFormatting
    val reasoningFormat = originalReasoning?.let {
        runCatching { ReasoningFormat.from(it, formatting.reasoning.name) }.getOrNull()
    }
    val selectedSystem = originalSystem?.let {
        runCatching { TextSystemPreset.from(it, "原版系统提示") }.getOrNull()
    }
    val system = current.textSystem
    val stops = power?.takeIf { it.has("custom_stopping_strings") }?.let {
        runCatching { CustomStoppingStrings.parse(it.string("custom_stopping_strings"),
            it.optBoolean("custom_stopping_strings_macro", current.customStops.macros)) }.getOrNull()
    } ?: current.customStops
    return current.copy(
        temperature = temperature ?: current.temperature,
        maxTokens = output ?: current.maxTokens,
        visualNovel = power?.optBoolean("waifuMode", current.visualNovel) ?: current.visualNovel,
        chatAppearance = appearance,
        swipeGestures = power?.optBoolean("gestures", current.swipeGestures) ?: current.swipeGestures,
        showGroupQueue = power?.optBoolean("show_group_chat_queue", current.showGroupQueue) ?: current.showGroupQueue,
        chatHandling = ChatHandling.fromOriginal(root, current.chatHandling),
        characterLibrary = CharacterLibraryOptions.from(power, current.characterLibrary),
        exampleBudget = when {
            power?.optBoolean("strip_examples") == true -> ExampleBudget.OMITTED
            power?.optBoolean("pin_examples") == true -> ExampleBudget.PINNED
            power?.has("strip_examples") == true || power?.has("pin_examples") == true -> ExampleBudget.AVAILABLE_SPACE
            else -> current.exampleBudget
        },
        replyAutomation = ReplyAutomation.from(automation),
        loreSettings = LoreSettings.from(scan),
        defaultAuthorNote = importedNote,
        regex = current.regex.copy(global = regex ?: current.regex.global),
        backgrounds = if (fit == null) current.backgrounds else current.backgrounds.copy(fit = fit),
        textSystem = system.copy(
            selected = selectedSystem ?: system.selected,
            enabled = originalSystem?.optBoolean("enabled", system.enabled) ?: system.enabled,
            preferCharacterPrompt = power?.optBoolean("prefer_character_prompt", system.preferCharacterPrompt)
                ?: system.preferCharacterPrompt,
            preferCharacterPostHistory = power?.optBoolean("prefer_character_jailbreak", system.preferCharacterPostHistory)
                ?: system.preferCharacterPostHistory,
        ),
        customStops = stops,
        requestTokenProbabilities = power?.optBoolean("request_token_probabilities", current.requestTokenProbabilities)
            ?: current.requestTokenProbabilities,
        includeReasoning = originalReasoning?.optBoolean("add_to_prompts", current.includeReasoning) ?: current.includeReasoning,
        replyFormatting = formatting.copy(
            prefix = power?.takeIf { it.has("user_prompt_bias") }?.string("user_prompt_bias")?.take(REPLY_PREFIX_LIMIT)
                ?: formatting.prefix,
            showPrefix = power?.optBoolean("show_user_prompt_bias", formatting.showPrefix) ?: formatting.showPrefix,
            reasoning = reasoningFormat ?: formatting.reasoning,
            parseReasoning = originalReasoning?.optBoolean("auto_parse", formatting.parseReasoning) ?: formatting.parseReasoning,
            maxReasoning = originalReasoning?.optInt("max_additions", formatting.maxReasoning)?.coerceIn(0, 80) ?: formatting.maxReasoning,
            autoExpand = originalReasoning?.optBoolean("auto_expand", formatting.autoExpand) ?: formatting.autoExpand,
        ),
    )
}

private fun overlaySettings(target: JSONObject, source: JSONObject?) {
    if (source == null) return
    target.keys().asSequence().toList().forEach { key -> if (!source.isNull(key)) target.put(key, source.get(key)) }
}
