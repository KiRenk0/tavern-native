package app.tavern.nativeclient

import app.tavern.nativeclient.backup.SyncSettings
import org.json.JSONArray
import org.json.JSONObject

internal fun decodePreferences(j: JSONObject, unseal: (String) -> String, globals: Map<String, String> = emptyMap()): Preferences {
    val legacy = if (j.has("providers")) null else Provider.legacy(j, unseal)
    val personas = j.optJSONArray("personas")?.objects().orEmpty().mapNotNull(PersonaProfile::fromJson)
        .distinctBy(PersonaProfile::id).ifEmpty { listOf(PersonaProfile.legacy(j.string("persona"), j.string("personaDescription"))) }
    val links = j.optJSONObject("personaConnections")
    return Preferences(
        temperature = j.string("temperature"), maxTokens = j.string("maxTokens"),
        theme = j.string("theme").ifBlank { "system" }, dynamicColor = j.optBoolean("dynamicColor"),
        server = j.string("server"), handle = j.string("handle"),
        providers = j.optJSONArray("providers")?.objects()?.map { Provider.fromJson(it, unseal) }
            ?: listOfNotNull(legacy),
        defaultSelection = ModelSelection.fromJson(j.optJSONObject("defaultSelection"))
            ?: legacy?.takeIf { it.ready }?.defaultSelection,
        fullTavernAddress = j.string("fullTavernAddress"),
        personas = personas,
        defaultPersonaId = j.string("defaultPersonaId").takeIf { id -> personas.any { it.id == id } } ?: personas.first().id,
        personaConnections = links?.keys()?.asSequence()?.associateWith(links::string).orEmpty(),
        defaultAuthorNote = AuthorNote.fromMetadata(j.optJSONObject("defaultAuthorNote")) ?: AuthorNote(),
        loreSettings = LoreSettings.from(j.optJSONObject("loreSettings")),
        defaultPresetId = j.string("defaultPresetId").takeIf(String::isNotBlank),
        regex = RegexConfig.from(j.optJSONObject("regex")),
        includeReasoning = j.optBoolean("includeReasoning"),
        quickReplies = QuickReplies.from(j.optJSONObject("quickReplies")),
        scriptVariables = globals,
        voice = VoicePreferences.from(j.optJSONObject("voice"), unseal),
        sync = SyncSettings.from(j.optJSONObject("sync"), unseal),
        cardScriptsEnabled = j.optBoolean("cardScriptsEnabled", false),
        vectors = VectorPreferences.from(j.optJSONObject("vectors")),
        drawing = DrawingSettings.from(j.optJSONObject("drawing")),
        toolCalling = j.optBoolean("toolCalling"),
        requestTokenProbabilities = j.optBoolean("requestTokenProbabilities"),
        visualNovel = j.optBoolean("visualNovel"),
        welcome = WelcomePreferences.from(j.optJSONObject("welcome")),
        replyAutomation = ReplyAutomation.from(j.optJSONObject("replyAutomation")),
        captions = CaptionSettings.from(j.optJSONObject("captions")),
        translationServices = TranslationServices.from(j.optJSONObject("translationServices"), unseal),
        summaryExtras = SummaryExtrasSettings.from(j.optJSONObject("summaryExtras"), unseal),
        library = LibraryPreferences.from(j.optJSONObject("library")),
        characterLibrary = CharacterLibraryOptions.from(j.optJSONObject("characterLibrary")),
        chatAppearance = ChatAppearance.from(j.optJSONObject("chatAppearance")),
        chatThemes = ChatThemes.from(j.optJSONObject("chatThemes")),
        swipeGestures = j.optBoolean("swipeGestures"),
        showGroupQueue = j.optBoolean("showGroupQueue"),
        chatHandling = ChatHandling.from(j.optJSONObject("chatHandling")),
        backgrounds = BackgroundLibrary.from(j.optJSONObject("backgrounds")),
        connectionProfiles = j.optJSONArray("connectionProfiles")?.objects()?.mapNotNull(ConnectionProfile::fromJson).orEmpty(),
        activeProfileId = j.string("activeProfileId").takeIf(String::isNotBlank),
        textSystem = TextSystemSettings.from(j.optJSONObject("textSystem")),
        customStops = CustomStoppingStrings.from(j.optJSONObject("customStops")),
        replyFormatting = ReplyFormatting.from(j.optJSONObject("replyFormatting")),
        exampleBudget = ExampleBudget.entries.firstOrNull { it.name == j.string("exampleBudget") }
            ?: ExampleBudget.AVAILABLE_SPACE,
    )
}

internal fun encodePreferences(prefs: Preferences, seal: (String) -> String): JSONObject =
    jsonOf(
        "providers" to JSONArray(prefs.providers.map { it.storageJson(seal) }),
        "defaultSelection" to prefs.defaultSelection?.json(),
        "personas" to JSONArray(prefs.personas.map(PersonaProfile::json)),
        "defaultPersonaId" to prefs.defaultPersonaId, "personaConnections" to JSONObject(prefs.personaConnections),
        "defaultAuthorNote" to prefs.defaultAuthorNote.json(),
        "loreSettings" to prefs.loreSettings.json(),
        "defaultPresetId" to prefs.defaultPresetId,
        "regex" to prefs.regex.json(),
        "includeReasoning" to prefs.includeReasoning,
        "quickReplies" to prefs.quickReplies.json(),
        "voice" to prefs.voice.json(seal),
        "sync" to prefs.sync.json(seal),
        "cardScriptsEnabled" to prefs.cardScriptsEnabled,
        "vectors" to prefs.vectors.json(),
        "drawing" to prefs.drawing.json(),
        "toolCalling" to prefs.toolCalling,
        "requestTokenProbabilities" to prefs.requestTokenProbabilities,
        "visualNovel" to prefs.visualNovel,
        "welcome" to prefs.welcome.json(),
        "replyAutomation" to prefs.replyAutomation.json(),
        "captions" to prefs.captions.json(),
        "translationServices" to prefs.translationServices.json(seal),
        "summaryExtras" to prefs.summaryExtras.json(seal),
        "library" to prefs.library.json(),
        "characterLibrary" to prefs.characterLibrary.json(),
        "chatAppearance" to prefs.chatAppearance.json(),
        "chatThemes" to prefs.chatThemes.json(),
        "swipeGestures" to prefs.swipeGestures,
        "showGroupQueue" to prefs.showGroupQueue,
        "chatHandling" to prefs.chatHandling.json(),
        "backgrounds" to prefs.backgrounds.json(),
        "connectionProfiles" to JSONArray(prefs.connectionProfiles.map(ConnectionProfile::json)),
        "activeProfileId" to prefs.activeProfileId,
        "textSystem" to prefs.textSystem.json(),
        "customStops" to prefs.customStops.json(),
        "replyFormatting" to prefs.replyFormatting.json(),
        "exampleBudget" to prefs.exampleBudget.name,
        "temperature" to prefs.temperature, "maxTokens" to prefs.maxTokens,
        "theme" to prefs.theme, "dynamicColor" to prefs.dynamicColor,
        "server" to prefs.server, "handle" to prefs.handle,
        "fullTavernAddress" to prefs.fullTavernAddress,
    )
