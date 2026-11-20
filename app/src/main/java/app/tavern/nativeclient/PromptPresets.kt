package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject

internal enum class PromptSection(val id: String, val label: String, val marker: Boolean = true) {
    MAIN("main", "主提示词", false),
    LORE_BEFORE("worldInfoBefore", "前置世界书"),
    PERSONA("personaDescription", "用户人设"),
    DESCRIPTION("charDescription", "角色描述"),
    PERSONALITY("charPersonality", "角色性格"),
    SCENARIO("scenario", "场景"),
    ENHANCE("enhanceDefinitions", "补充角色设定", false),
    AUXILIARY("nsfw", "辅助提示词", false),
    LORE_AFTER("worldInfoAfter", "后置世界书"),
    EXAMPLES("dialogueExamples", "对话示例"),
    HISTORY("chatHistory", "聊天历史"),
    AFTER("jailbreak", "聊天历史之后的指令", false),
}

internal data class PresetPrompt(
    val id: String = newId(),
    val name: String = "",
    val text: String = "",
    val role: PromptRole = PromptRole.SYSTEM,
    val enabled: Boolean = true,
    val included: Boolean = true,
    val atDepth: Boolean = false,
    val depth: Int = 4,
    val priority: Int = 100,
    val triggers: List<String> = emptyList(),
    val forbidOverrides: Boolean = false,
    val raw: String = "{}",
) {
    val section get() = PromptSection.entries.firstOrNull { it.id == id }

    fun applies(trigger: String) = included && enabled && (triggers.isEmpty() || trigger in triggers)

    fun definition() = JSONObject(raw).apply {
        put("identifier", id)
        put("name", name)
        put("content", text)
        put("role", role.wire)
        put("system_prompt", section != null || optBoolean("system_prompt"))
        put("marker", section?.marker ?: optBoolean("marker"))
        put("injection_position", if (atDepth) 1 else 0)
        put("injection_depth", depth)
        put("injection_order", priority)
        put("injection_trigger", JSONArray(triggers))
        put("forbid_overrides", forbidOverrides)
    }

    companion object {
        fun from(json: JSONObject): PresetPrompt? {
            val id = json.string("identifier").ifBlank { return null }
            val section = PromptSection.entries.firstOrNull { it.id == id }
            return PresetPrompt(id = id, name = json.string("name").ifBlank { section?.label ?: id },
                text = json.string("content"), role = PromptRole.entries.firstOrNull { it.wire == json.string("role") }
                    ?: PromptRole.SYSTEM,
                enabled = json.optBoolean("enabled", true), atDepth = json.optInt("injection_position") == 1 &&
                    section !in setOf(PromptSection.HISTORY, PromptSection.EXAMPLES),
                depth = json.optInt("injection_depth", 4).coerceIn(0, 10_000),
                priority = json.optInt("injection_order", 100),
                triggers = json.optJSONArray("injection_trigger").strings().distinct(),
                forbidOverrides = json.optBoolean("forbid_overrides"), raw = json.toString())
        }
    }
}

internal enum class PresetUtility(val key: String, val label: String, val fallback: String = "") {
    NEW_CHAT("new_chat_prompt", "新建单人对话"),
    NEW_GROUP("new_group_chat_prompt", "新建群组对话"),
    EXAMPLE("new_example_chat_prompt", "示例对话开头"),
    GROUP_NUDGE("group_nudge_prompt", "指定群聊发言者", "请只以 {{char}} 的身份发言。"),
    CONTINUE("continue_nudge_prompt", "续写提示", "接着写上一条回复，从原文结束的位置继续。避免重复已经写出的内容。"),
    IMPERSONATE("impersonation_prompt", "代写提示", "用 {{user}} 的视角和语言风格写一条发言。请只输出消息正文。"),
    SCENARIO_FORMAT("scenario_format", "场景模板", "{{scenario}}"),
    PERSONALITY_FORMAT("personality_format", "性格模板", "{{personality}}"),
    WORLD_FORMAT("wi_format", "世界书模板", "{0}"),
}

internal enum class PresetSampling(val key: String, val wire: String, val label: String) {
    TEMPERATURE("temperature", "temperature", "温度"),
    TOP_P("top_p", "top_p", "Top P"),
    MAX_TOKENS("openai_max_tokens", "max_tokens", "最大输出 Token"),
    FREQUENCY("frequency_penalty", "frequency_penalty", "频率惩罚"),
    PRESENCE("presence_penalty", "presence_penalty", "存在惩罚"),
    SEED("seed", "seed", "随机种子"),
}

internal fun PresetSampling.number(value: String): Number? = when (this) {
    PresetSampling.MAX_TOKENS -> value.toIntOrNull()?.takeIf { it > 0 }
    PresetSampling.SEED -> value.toLongOrNull()?.takeIf { it >= 0 }
    PresetSampling.TEMPERATURE -> value.toDoubleOrNull()?.takeIf { it in 0.0..2.0 }
    PresetSampling.TOP_P -> value.toDoubleOrNull()?.takeIf { it in 0.0..1.0 }
    PresetSampling.FREQUENCY, PresetSampling.PRESENCE -> value.toDoubleOrNull()?.takeIf { it in -2.0..2.0 }
}

internal data class PromptPreset(
    val id: String = newId(),
    val name: String,
    val prompts: List<PresetPrompt> = defaultPrompts(),
    val utilities: Map<PresetUtility, String> = emptyMap(),
    val sampling: Map<PresetSampling, String> = emptyMap(),
    val continuePrefill: Boolean = false,
    val squashSystem: Boolean = false,
    val regexRules: List<RegexRule> = emptyList(),
) {
    fun text(utility: PresetUtility) = utilities[utility] ?: utility.fallback

    fun json() = jsonOf(
        "name" to name,
        "prompts" to JSONArray(prompts.map(PresetPrompt::definition)),
        "prompt_order" to JSONArray().put(jsonOf("character_id" to 100001,
            "order" to JSONArray(prompts.filter(PresetPrompt::included).map {
                jsonOf("identifier" to it.id, "enabled" to it.enabled)
            }))),
        "continue_prefill" to continuePrefill,
        "squash_system_messages" to squashSystem,
    ).apply {
        PresetUtility.entries.forEach { put(it.key, text(it)) }
        sampling.forEach { (option, value) -> option.number(value)?.let { put(option.key, it) } }
        if (regexRules.isNotEmpty()) {
            put("extensions", jsonOf("regex_scripts" to JSONArray(regexRules.map(RegexRule::json))))
        }
    }

    fun applySampling(payload: JSONObject, template: ProviderTemplate?) {
        for ((option, value) in sampling) {
            val supported = when (option) {
                PresetSampling.SEED, PresetSampling.FREQUENCY, PresetSampling.PRESENCE -> template == null
                else -> true
            }
            if (supported) option.number(value)?.let { payload.put(option.wire, it) }
        }
    }

    companion object {
        fun defaultPrompts() = PromptSection.entries.map { section ->
            val content = if (section == PromptSection.MAIN) {
                "以 {{char}} 的身份与 {{user}} 对话。保持角色一致，并用对方使用的语言回复。"
            } else ""
            PresetPrompt(section.id, section.label, content, enabled = section != PromptSection.ENHANCE)
        }

        fun read(input: JSONObject, fallbackName: String, id: String = newId()): PromptPreset {
            val json = input.optJSONObject("data") ?: input.optJSONObject("oai_settings") ?: input
            val definitions = json.optJSONArray("prompts")
            val legacyKeys = mapOf("main_prompt" to PromptSection.MAIN, "nsfw_prompt" to PromptSection.AUXILIARY,
                "jailbreak_prompt" to PromptSection.AFTER)
            require(definitions != null || legacyKeys.keys.any(json::has) || PresetSampling.entries.any { json.has(it.key) }) {
                "JSON 中没有可导入的 SillyTavern 提示词预设"
            }
            val library = defaultPrompts().associateBy(PresetPrompt::id).toMutableMap()
            definitions?.objects()?.mapNotNull(PresetPrompt::from)?.forEach { library[it.id] = it }
            legacyKeys.forEach { (key, section) ->
                val legacy = json.string(key)
                if (legacy.isNotBlank()) library[section.id] = library.getValue(section.id).copy(text = legacy)
            }

            val groups = json.optJSONArray("prompt_order")?.objects()
            val ordered = groups?.firstOrNull { it.string("character_id") == "100001" }?.optJSONArray("order")
                ?: groups?.firstNotNullOfOrNull { it.optJSONArray("order") }
                ?: json.optJSONObject("prompt_order")?.optJSONArray("order")
                ?: json.optJSONArray("prompt_order")?.takeIf { it.length() > 0 && groups.orEmpty().none { it.has("order") } }
            val entries = if (ordered == null) library.values.toList() else buildList {
                val inOrder = ordered.objects().distinctBy { it.string("identifier") }
                inOrder.forEach { row ->
                    library.remove(row.string("identifier"))?.let { add(it.copy(enabled = row.optBoolean("enabled", true))) }
                }
                addAll(library.values.map { it.copy(included = false) })
            }
            return PromptPreset(id, input.string("name").ifBlank { fallbackName }.ifBlank { "导入的提示词预设" },
                entries, PresetUtility.entries.filterNot { json.isNull(it.key) }.associateWith { json.string(it.key) },
                PresetSampling.entries.mapNotNull { option ->
                    val value = json.string(option.key)
                    if (option.number(value) != null) option to value else null
                }.toMap(),
                json.optBoolean("continue_prefill"), json.optBoolean("squash_system_messages"),
                RegexRule.fromArray(json.optJSONObject("extensions")?.optJSONArray("regex_scripts")))
        }
    }
}
