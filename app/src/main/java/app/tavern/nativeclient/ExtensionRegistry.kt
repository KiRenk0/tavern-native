package app.tavern.nativeclient

import app.tavern.nativeclient.script.CardSandboxPolicy
import org.json.JSONArray
import org.json.JSONObject

internal const val EXTENSIONS_PORTABLE_FORMAT = "tavern-native-extensions"
internal const val EXTENSIONS_PORTABLE_VERSION = 1

internal enum class ExtensionCategory(val label: String) {
    PROMPT("提示词增强"),
    CHAT_TOOLS("对话工具"),
    KNOWLEDGE("知识与检索"),
    MEDIA("媒体与音频"),
    ASSETS("素材资源"),
    SANDBOX("沙盒与安全"),
}

internal enum class BuiltInExtensionId(
    val id: String,
    val displayName: String,
    val description: String,
    val category: ExtensionCategory,
    val defaultEnabled: Boolean,
) {
    QUICK_PROMPT_TOGGLES(
        id = "quick_prompt_toggles",
        displayName = "快捷提示词开关",
        description = "一键开关常用系统提示、风格指令与深度注入片段。",
        category = ExtensionCategory.PROMPT,
        defaultEnabled = true,
    ),
    CHAT_STATUS_INSPECTOR(
        id = "chat_status_inspector",
        displayName = "对话状态与变量监视器",
        description = "实时检视与编辑对话变量、全局变量与 MVU 状态树。",
        category = ExtensionCategory.CHAT_TOOLS,
        defaultEnabled = true,
    ),
    CHARACTER_LORE_HELPER(
        id = "character_lore_helper",
        displayName = "角色设定与注释助手",
        description = "快速查看与切换当前角色的世界书条目、作者注释与深度提示。",
        category = ExtensionCategory.KNOWLEDGE,
        defaultEnabled = true,
    ),
    WEB_SEARCH(
        id = "web_search",
        displayName = "网络搜索扩展",
        description = "支持 SearXNG、Tavily、SerpApi、DuckDuckGo 与 Brave Search。",
        category = ExtensionCategory.KNOWLEDGE,
        defaultEnabled = false,
    ),
    DYNAMIC_AUDIO(
        id = "dynamic_audio",
        displayName = "动态音频（BGM 与环境音）",
        description = "支持循环、音量、淡入淡出及按表情/背景/关键词自动切歌。",
        category = ExtensionCategory.MEDIA,
        defaultEnabled = false,
    ),
    ASSETS_DOWNLOADER(
        id = "assets_downloader",
        displayName = "官方素材下载器",
        description = "浏览并安全下载 SillyTavern 官方主题、背景、角色与立绘素材。",
        category = ExtensionCategory.ASSETS,
        defaultEnabled = true,
    ),
    CARD_SANDBOX(
        id = "card_sandbox",
        displayName = "角色卡沙盒与权限",
        description = "按角色卡管理 TavernHelper 脚本权限、域名白名单与作用域 CSS。",
        category = ExtensionCategory.SANDBOX,
        defaultEnabled = false,
    ),
    ;

    companion object {
        fun fromId(id: String?): BuiltInExtensionId? =
            entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }
    }
}

internal data class ExtensionDescriptor(
    val id: String,
    val displayName: String,
    val description: String,
    val category: ExtensionCategory,
    val defaultEnabled: Boolean,
    val builtIn: Boolean = true,
)

internal data class QuickPromptToggleItem(
    val id: String = newId(),
    val label: String,
    val promptText: String,
    val enabled: Boolean = false,
    val depth: Int = 4,
    val role: String = "system",
) {
    fun json(): JSONObject = jsonOf(
        "id" to id,
        "label" to label,
        "promptText" to promptText,
        "enabled" to enabled,
        "depth" to depth.coerceIn(0, 80),
        "role" to role,
    )

    companion object {
        fun from(j: JSONObject?): QuickPromptToggleItem? {
            if (j == null) return null
            val label = j.string("label").trim()
            val text = j.string("promptText")
            if (label.isEmpty() || text.isBlank()) return null
            return QuickPromptToggleItem(
                id = j.string("id").ifBlank { newId() },
                label = label,
                promptText = text,
                enabled = j.optBoolean("enabled", false),
                depth = j.optInt("depth", 4).coerceIn(0, 80),
                role = j.string("role").takeIf { it in setOf("system", "user", "assistant") } ?: "system",
            )
        }
    }
}

internal data class ExtensionHubConfig(
    val enabledExtensions: Map<String, Boolean> = BuiltInExtensionId.entries.associate { it.id to it.defaultEnabled },
    val quickPromptToggles: List<QuickPromptToggleItem> = emptyList(),
    val webSearch: WebSearchConfig = WebSearchConfig(),
    val dynamicAudio: DynamicAudioConfig = DynamicAudioConfig(),
    val assets: AssetsConfig = AssetsConfig(),
    val cardSandboxPolicies: Map<String, CardSandboxPolicy> = emptyMap(),
    val customSettings: Map<String, String> = emptyMap(),
) {
    fun isEnabled(extension: BuiltInExtensionId): Boolean =
        when (extension) {
            BuiltInExtensionId.WEB_SEARCH -> enabledExtensions[extension.id] ?: webSearch.enabled
            BuiltInExtensionId.DYNAMIC_AUDIO -> enabledExtensions[extension.id] ?: dynamicAudio.enabled
            else -> enabledExtensions[extension.id] ?: extension.defaultEnabled
        }

    fun isEnabled(id: String): Boolean {
        BuiltInExtensionId.fromId(id)?.let { return isEnabled(it) }
        return enabledExtensions[id] ?: false
    }

    fun withEnabled(extension: BuiltInExtensionId, enabled: Boolean): ExtensionHubConfig {
        val nextMap = enabledExtensions.toMutableMap().apply { put(extension.id, enabled) }
        return copy(
            enabledExtensions = nextMap,
            webSearch = if (extension == BuiltInExtensionId.WEB_SEARCH) webSearch.copy(enabled = enabled) else webSearch,
            dynamicAudio = if (extension == BuiltInExtensionId.DYNAMIC_AUDIO) dynamicAudio.copy(enabled = enabled) else dynamicAudio,
        )
    }

    fun policyForCard(cardId: String?, globalScriptsEnabled: Boolean): CardSandboxPolicy {
        val specific = cardId?.let(cardSandboxPolicies::get)
        if (specific != null) return specific
        return CardSandboxPolicy(allowScripts = globalScriptsEnabled)
    }

    fun withCardPolicy(cardId: String, policy: CardSandboxPolicy): ExtensionHubConfig =
        copy(cardSandboxPolicies = cardSandboxPolicies + (cardId to policy))

    fun activeQuickPrompts(): List<QuickPromptToggleItem> =
        if (!isEnabled(BuiltInExtensionId.QUICK_PROMPT_TOGGLES)) emptyList()
        else quickPromptToggles.filter { it.enabled && it.promptText.isNotBlank() }

    fun json(seal: (String) -> String = { it }): JSONObject = jsonOf(
        "enabledExtensions" to JSONObject().apply {
            enabledExtensions.forEach { (k, v) -> put(k, v) }
        },
        "quickPromptToggles" to JSONArray(quickPromptToggles.map(QuickPromptToggleItem::json)),
        "webSearch" to webSearch.json(seal),
        "dynamicAudio" to dynamicAudio.json(),
        "assets" to assets.json(),
        "cardSandboxPolicies" to JSONObject().apply {
            cardSandboxPolicies.forEach { (cardId, policy) -> put(cardId, policy.json()) }
        },
        "customSettings" to JSONObject().apply {
            customSettings.forEach { (k, v) -> put(k, v) }
        },
    )

    /**
     * Exports a portable JSON envelope compatible with Tavern Native Settings Snapshots and
     * SillyTavern `extension_settings`. Sensitive secrets (such as Web Search API keys) are stripped.
     */
    fun exportPortableJson(): JSONObject {
        val sanitized = json(seal = { "" })
        return jsonOf(
            "format" to EXTENSIONS_PORTABLE_FORMAT,
            "version" to EXTENSIONS_PORTABLE_VERSION,
            "extensions" to sanitized,
            "extension_settings" to jsonOf(
                "tavern_native_extensions" to sanitized,
                "web_search" to webSearch.json(seal = { "" }),
                "dynamic_audio" to dynamicAudio.json(),
            ),
        )
    }

    companion object {
        fun from(j: JSONObject?, unseal: (String) -> String = { it }): ExtensionHubConfig {
            if (j == null) return ExtensionHubConfig()
            val enabledObj = j.optJSONObject("enabledExtensions")
            val enabledMap = BuiltInExtensionId.entries.associate { it.id to it.defaultEnabled }.toMutableMap()
            enabledObj?.keys()?.forEach { key ->
                enabledMap[key] = enabledObj.optBoolean(key, enabledMap[key] ?: false)
            }
            val webSearch = WebSearchConfig.from(j.optJSONObject("webSearch"), unseal)
            val dynamicAudio = DynamicAudioConfig.from(j.optJSONObject("dynamicAudio"))
            if (enabledObj == null || !enabledObj.has(BuiltInExtensionId.WEB_SEARCH.id)) {
                enabledMap[BuiltInExtensionId.WEB_SEARCH.id] = webSearch.enabled
            }
            if (enabledObj == null || !enabledObj.has(BuiltInExtensionId.DYNAMIC_AUDIO.id)) {
                enabledMap[BuiltInExtensionId.DYNAMIC_AUDIO.id] = dynamicAudio.enabled
            }
            val policiesObj = j.optJSONObject("cardSandboxPolicies")
            val policies = buildMap {
                policiesObj?.keys()?.forEach { cardId ->
                    policiesObj.optJSONObject(cardId)?.let { put(cardId, CardSandboxPolicy.from(it)) }
                }
            }
            val customObj = j.optJSONObject("customSettings")
            val custom = buildMap {
                customObj?.keys()?.forEach { key ->
                    val v = customObj.string(key)
                    if (v.isNotEmpty()) put(key, v)
                }
            }
            return ExtensionHubConfig(
                enabledExtensions = enabledMap,
                quickPromptToggles = j.optJSONArray("quickPromptToggles")?.objects().orEmpty()
                    .mapNotNull(QuickPromptToggleItem::from),
                webSearch = webSearch,
                dynamicAudio = dynamicAudio,
                assets = AssetsConfig.from(j.optJSONObject("assets")),
                cardSandboxPolicies = policies,
                customSettings = custom,
            )
        }

        fun importPortableJson(
            rawJson: String,
            current: ExtensionHubConfig = ExtensionHubConfig(),
        ): ExtensionHubConfig {
            val root = runCatching { JSONObject(rawJson.trim().trimStart('\uFEFF')) }.getOrNull()
                ?: error("无法解析扩展设置 JSON")

            val payload: JSONObject? = when {
                root.string("format") == EXTENSIONS_PORTABLE_FORMAT -> root.optJSONObject("extensions")
                root.string("format") == "tavern-native-settings" ->
                    root.optJSONObject("preferences")?.optJSONObject("extensions")
                root.optJSONObject("extension_settings")?.optJSONObject("tavern_native_extensions") != null ->
                    root.optJSONObject("extension_settings")?.optJSONObject("tavern_native_extensions")
                root.has("enabledExtensions") || root.has("webSearch") || root.has("dynamicAudio") -> root
                else -> null
            }

            if (payload != null) {
                val parsed = from(payload, unseal = { "" })
                // Preserve existing API key if backend + endpoint match and incoming key was stripped
                val retainedKey = if (parsed.webSearch.apiKey.isBlank() &&
                    parsed.webSearch.backend == current.webSearch.backend &&
                    parsed.webSearch.effectiveEndpoint == current.webSearch.effectiveEndpoint
                ) {
                    current.webSearch.apiKey
                } else {
                    parsed.webSearch.apiKey
                }
                return parsed.copy(webSearch = parsed.webSearch.copy(apiKey = retainedKey))
            }

            val stExt = root.optJSONObject("extension_settings")
                ?: error("JSON 中未包含可识别的扩展配置数据")
            val webSearch = stExt.optJSONObject("web_search")?.let { WebSearchConfig.from(it, unseal = { "" }) }
                ?: current.webSearch
            val dynamicAudio = stExt.optJSONObject("dynamic_audio")?.let(DynamicAudioConfig::from)
                ?: current.dynamicAudio
            return current.copy(webSearch = webSearch, dynamicAudio = dynamicAudio)
        }
    }
}

internal object ExtensionRegistry {
    private val customDescriptors = linkedMapOf<String, ExtensionDescriptor>()

    val builtInDescriptors: List<ExtensionDescriptor> = BuiltInExtensionId.entries.map { ext ->
        ExtensionDescriptor(
            id = ext.id,
            displayName = ext.displayName,
            description = ext.description,
            category = ext.category,
            defaultEnabled = ext.defaultEnabled,
            builtIn = true,
        )
    }

    @Synchronized
    fun register(descriptor: ExtensionDescriptor) {
        require(descriptor.id.isNotBlank()) { "扩展 ID 不能为空" }
        customDescriptors[descriptor.id] = descriptor
    }

    @Synchronized
    fun unregister(id: String): Boolean = customDescriptors.remove(id) != null

    @Synchronized
    fun allDescriptors(): List<ExtensionDescriptor> =
        builtInDescriptors + customDescriptors.values.filter { c -> builtInDescriptors.none { it.id == c.id } }

    fun find(id: String): ExtensionDescriptor? = allDescriptors().firstOrNull { it.id == id }
}
