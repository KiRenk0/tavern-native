package app.tavern.nativeclient

import org.json.JSONObject

internal enum class PromptRole(val wire: String, val label: String) {
    SYSTEM("system", "系统"), USER("user", "用户"), ASSISTANT("assistant", "助手"),
}

internal enum class PersonaPosition(val code: Int, val label: String) {
    PROMPT(0, "角色设定"), NOTE_TOP(2, "作者注释之前"), NOTE_BOTTOM(3, "作者注释之后"),
    DEPTH(4, "聊天历史指定深度"), NONE(9, "不注入描述"),
}

internal data class PersonaProfile(
    val id: String = newId(),
    val name: String = "",
    val description: String = "",
    val title: String = "",
    val position: PersonaPosition = PersonaPosition.PROMPT,
    val depth: Int = 2,
    val role: PromptRole = PromptRole.SYSTEM,
    val worldId: String = "",
    val avatar: String? = null,
) {
    fun json() = jsonOf(
        "id" to id, "name" to name, "description" to description, "title" to title,
        "position" to position.code, "depth" to depth, "role" to role.ordinal, "worldId" to worldId, "avatar" to avatar,
    )

    companion object {
        const val LEGACY_ID = "default-user"

        fun legacy(name: String = "我", description: String = "") =
            PersonaProfile(LEGACY_ID, name.ifBlank { "我" }, description)

        fun fromJson(value: JSONObject): PersonaProfile? {
            val id = value.string("id")
            val name = value.string("name")
            if (id.isBlank() || name.isBlank()) return null
            return PersonaProfile(id, name, value.string("description"), value.string("title"),
                PersonaPosition.entries.firstOrNull { it.code == value.optInt("position") } ?: PersonaPosition.PROMPT,
                value.optInt("depth", 2).coerceAtLeast(0),
                PromptRole.entries.getOrElse(value.optInt("role")) { PromptRole.SYSTEM },
                value.string("worldId"), value.string("avatar").takeIf { it.matches(Regex("[a-fA-F0-9-]{36}")) })
        }
    }
}

internal enum class NotePosition(val label: String) {
    AFTER_SCENARIO("场景之后"), DEPTH("聊天历史指定深度"), BEFORE_SCENARIO("场景之前"),
}

internal data class AuthorNote(
    val text: String = "",
    val frequency: Int = 1,
    val depth: Int = 4,
    val position: NotePosition = NotePosition.DEPTH,
    val role: PromptRole = PromptRole.SYSTEM,
    val scan: Boolean = false,
) {
    fun due(messages: List<Message>): Boolean {
        if (frequency <= 0) return false
        if (frequency == 1) return true
        val userTurns = messages.count { it.role == "user" && !it.hidden }
        return userTurns > 0 && userTurns % frequency == 0
    }

    fun applyTo(metadata: JSONObject) {
        metadata.put("note_prompt", text)
        metadata.put("note_interval", frequency)
        metadata.put("note_depth", depth)
        metadata.put("note_position", position.ordinal)
        metadata.put("note_role", role.ordinal)
        metadata.put("tavern_native_note_scan", scan)
    }

    fun json() = JSONObject().also(::applyTo)

    companion object {
        fun fromMetadata(value: JSONObject?): AuthorNote? {
            value ?: return null
            if (listOf("note_prompt", "note_interval", "note_position", "note_depth", "note_role").none(value::has)) return null
            return AuthorNote(value.string("note_prompt"), value.optInt("note_interval", 1).coerceAtLeast(0),
                value.optInt("note_depth", 4).coerceAtLeast(0),
                NotePosition.entries.getOrElse(value.optInt("note_position", 1)) { NotePosition.DEPTH },
                PromptRole.entries.getOrElse(value.optInt("note_role")) { PromptRole.SYSTEM },
                value.optBoolean("tavern_native_note_scan"))
        }
    }
}

internal enum class CharacterNoteOrder(val label: String) {
    REPLACE("替换对话注释"), BEFORE("加在对话注释之前"), AFTER("加在对话注释之后"),
}

internal data class CharacterNote(
    val text: String = "",
    val order: CharacterNoteOrder = CharacterNoteOrder.REPLACE,
) {
    fun combine(note: String) = when {
        text.isBlank() -> note
        order == CharacterNoteOrder.BEFORE -> listOf(text, note).filter(String::isNotBlank).joinToString("\n")
        order == CharacterNoteOrder.AFTER -> listOf(note, text).filter(String::isNotBlank).joinToString("\n")
        else -> text
    }

    fun json() = jsonOf("text" to text, "order" to order.ordinal)

    companion object {
        fun fromJson(value: JSONObject?): CharacterNote = CharacterNote(
            value?.string("text").orEmpty(),
            CharacterNoteOrder.entries.getOrElse(value?.optInt("order") ?: 0) { CharacterNoteOrder.REPLACE },
        )
    }
}

internal data class ConversationOptions(
    val personaId: String? = null,
    val authorNote: AuthorNote? = null,
    val scenario: String = "",
    val worldBookId: String = "",
    val worldBookName: String = "",
    val parent: ChatPointer? = null,
    val promptPresetId: String? = null,
    val variables: Map<String, String> = emptyMap(),
    val quickReplySets: List<String> = emptyList(),
    val quickReplyNames: List<String> = emptyList(),
    val translation: TranslationOptions = TranslationOptions(),
    val memory: MemoryOptions = MemoryOptions(),
    val background: ChatImage? = null,
    val exampleOverride: String = "",
    val systemPromptOverride: String = "",
    val expressions: ExpressionSettings = ExpressionSettings(),
    val pickRerollSeed: String = "",
    val connectionProfileId: String? = null,
    val scriptInjections: List<app.tavern.nativeclient.script.ScriptPromptInjection> = emptyList(),
) {
    val hasWorldBookOverride get() = worldBookId.isNotBlank() || worldBookName.isNotBlank()

    fun worldBook(books: List<WorldBook>): WorldBook? = if (worldBookId.isNotBlank()) {
        books.firstOrNull { it.id == worldBookId }
    } else {
        books.firstOrNull { it.name == worldBookName }
    }

    fun applyTo(metadata: JSONObject) {
        if (personaId == null) metadata.remove("tavern_native_persona") else metadata.put("tavern_native_persona", personaId)
        if (scenario.isEmpty()) metadata.remove("scenario") else metadata.put("scenario", scenario)
        if (exampleOverride.isEmpty()) metadata.remove("mes_example") else metadata.put("mes_example", exampleOverride)
        if (systemPromptOverride.isEmpty()) metadata.remove("system_prompt") else metadata.put("system_prompt", systemPromptOverride)
        if (worldBookId.isEmpty()) metadata.remove("tavern_native_world_book") else metadata.put("tavern_native_world_book", worldBookId)
        if (worldBookName.isEmpty()) metadata.remove("world_info") else metadata.put("world_info", worldBookName)
        authorNote?.applyTo(metadata)
        if (parent == null) {
            metadata.remove("main_chat")
            metadata.remove("tavern_native_parent_chat")
        } else parent.write(metadata, "main_chat", "tavern_native_parent_chat")
        if (promptPresetId == null) metadata.remove("tavern_native_prompt_preset")
        else metadata.put("tavern_native_prompt_preset", promptPresetId)
        if (variables.isNotEmpty()) metadata.put("variables", JSONObject(variables)) else metadata.remove("variables")
        if (pickRerollSeed.isEmpty()) metadata.remove("pick_reroll_seed") else metadata.put("pick_reroll_seed", pickRerollSeed)
        if (quickReplySets.isNotEmpty()) metadata.put("tavern_native_quick_replies", org.json.JSONArray(quickReplySets))
        else metadata.remove("tavern_native_quick_replies")
        if (quickReplyNames.isNotEmpty()) {
            metadata.put("quickReply", jsonOf("setList" to org.json.JSONArray(quickReplyNames.map {
                jsonOf("set" to it, "isVisible" to true)
            })))
        } else metadata.remove("quickReply")
        if (translation == TranslationOptions()) metadata.remove("tavern_native_translation")
        else metadata.put("tavern_native_translation", translation.json())
        if (memory == MemoryOptions()) metadata.remove("tavern_native_memory")
        else metadata.put("tavern_native_memory", memory.json())
        if (background == null) metadata.remove("tavern_native_background") else metadata.put("tavern_native_background", background.json())
        if (expressions == ExpressionSettings()) metadata.remove(EXPRESSION_EXTENSION) else metadata.put(EXPRESSION_EXTENSION, expressions.json())
        if (connectionProfileId == null) metadata.remove("tavern_native_connection_profile")
        else metadata.put("tavern_native_connection_profile", connectionProfileId)
        if (scriptInjections.isEmpty()) metadata.remove("tavern_native_script_injections")
        else metadata.put("tavern_native_script_injections", app.tavern.nativeclient.script.ScriptPromptInjection.toArray(scriptInjections))
    }

    fun json() = JSONObject().also(::applyTo).put("variables", JSONObject(variables))
        .put("mes_example", exampleOverride).put("system_prompt", systemPromptOverride)

    companion object {
        fun fromMetadata(value: JSONObject?) = ConversationOptions(
            value?.string("tavern_native_persona")?.takeIf(String::isNotBlank),
            AuthorNote.fromMetadata(value),
            value?.string("scenario").orEmpty(),
            value?.string("tavern_native_world_book").orEmpty(),
            value?.string("world_info").orEmpty(),
            ChatPointer.from(value, "main_chat", "tavern_native_parent_chat"),
            value?.takeUnless { it.isNull("tavern_native_prompt_preset") }?.string("tavern_native_prompt_preset"),
            value?.optJSONObject("variables").variableStrings(),
            value?.optJSONArray("tavern_native_quick_replies").strings(),
            value?.optJSONObject("quickReply").quickReplyNames(),
            TranslationOptions.from(value?.optJSONObject("tavern_native_translation")),
            MemoryOptions.from(value?.optJSONObject("tavern_native_memory")),
            value?.optJSONObject("tavern_native_background")?.let(ChatImage::from),
            value?.string("mes_example").orEmpty(),
            value?.string("system_prompt").orEmpty(),
            ExpressionSettings.from(value?.optJSONObject(EXPRESSION_EXTENSION)),
            value?.string("pick_reroll_seed").orEmpty(),
            value?.takeUnless { it.isNull("tavern_native_connection_profile") }?.string("tavern_native_connection_profile"),
            app.tavern.nativeclient.script.ScriptPromptInjection.fromArray(value?.optJSONArray("tavern_native_script_injections")),
        )
    }
}
