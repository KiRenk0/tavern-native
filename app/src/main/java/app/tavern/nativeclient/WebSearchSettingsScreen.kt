package app.tavern.nativeclient

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun WebSearchSettingsScreen(
    state: TavernState,
    model: TavernViewModel,
    close: () -> Unit,
) {
    BackHandler(onBack = close)
    val config = state.prefs.extensions.webSearch
    var enabled by rememberSaveable(config.enabled) { mutableStateOf(config.enabled) }
    var backend by rememberSaveable(config.backend) { mutableStateOf(config.backend) }
    var endpoint by rememberSaveable(config.endpoint) { mutableStateOf(config.endpoint) }
    var apiKey by rememberSaveable(config.apiKey) { mutableStateOf(config.apiKey) }
    var triggerMode by rememberSaveable(config.triggerMode) { mutableStateOf(config.triggerMode) }
    var triggerPattern by rememberSaveable(config.triggerPattern) { mutableStateOf(config.triggerPattern) }
    var maxResults by rememberSaveable(config.maxResults) { mutableStateOf(config.maxResults.toString()) }
    var summarize by rememberSaveable(config.summarizeResults) { mutableStateOf(config.summarizeResults) }
    var maxSnippetChars by rememberSaveable(config.maxSnippetChars) { mutableStateOf(config.maxSnippetChars.toString()) }
    var position by rememberSaveable(config.injectionPosition) { mutableStateOf(config.injectionPosition) }
    var depth by rememberSaveable(config.injectionDepth) { mutableStateOf(config.injectionDepth.toString()) }
    var role by rememberSaveable(config.injectionRole) { mutableStateOf(config.injectionRole) }
    var template by rememberSaveable(config.injectionTemplate) { mutableStateOf(config.injectionTemplate) }
    var language by rememberSaveable(config.language) { mutableStateOf(config.language) }
    var safeSearch by rememberSaveable(config.safeSearch) { mutableStateOf(config.safeSearch) }

    var testQuery by rememberSaveable { mutableStateOf("") }
    var testOutput by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun currentDraft(): WebSearchConfig = WebSearchConfig(
        enabled = enabled,
        backend = backend,
        endpoint = endpoint.trim(),
        apiKey = apiKey.trim(),
        triggerMode = triggerMode,
        triggerPattern = triggerPattern,
        maxResults = maxResults.toIntOrNull()?.coerceIn(1, 15) ?: 5,
        summarizeResults = summarize,
        maxSnippetChars = maxSnippetChars.toIntOrNull()?.coerceIn(60, 2000) ?: 280,
        injectionPosition = position,
        injectionDepth = depth.toIntOrNull()?.coerceIn(0, 80) ?: 1,
        injectionRole = role,
        injectionTemplate = template.ifBlank { "[Web Search Results for \"{{query}}\"]\n{{results}}" },
        language = language.trim(),
        safeSearch = safeSearch,
    )

    Form {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionIcon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", !state.busy, close)
            Text("网络搜索扩展", style = MaterialTheme.typography.titleLarge)
        }
        GroupToggle("启用网络搜索扩展", enabled, !state.busy) { enabled = it }
        SelectField("搜索引擎后端", backend, WebSearchProvider.entries, WebSearchProvider::label, !state.busy) {
            backend = it
        }
        Field(
            label = "自定义端点地址（留空使用默认）",
            value = endpoint,
            change = { endpoint = it },
            placeholder = backend.defaultEndpoint,
            enabled = !state.busy,
        )
        if (backend.requiresApiKey || backend == WebSearchProvider.SEARXNG) {
            Field(
                label = if (backend.requiresApiKey) "${backend.label} API Key" else "访问令牌（可选）",
                value = apiKey,
                change = { apiKey = it },
                enabled = !state.busy,
                secret = true,
            )
        }
        HorizontalDivider()
        FormHeading("触发方式", "手动模式支持使用 /websearch <关键词>；关键词/正则模式会在用户消息匹配时自动联网搜索。")
        SelectField("触发模式", triggerMode, WebSearchTriggerMode.entries, WebSearchTriggerMode::label, !state.busy) {
            triggerMode = it
        }
        if (triggerMode == WebSearchTriggerMode.KEYWORD_REGEX) {
            Field(
                label = "自动触发正则 / 关键词",
                value = triggerPattern,
                change = { triggerPattern = it },
                lines = 2,
                enabled = !state.busy,
            )
        }
        HorizontalDivider()
        FormHeading("结果格式化与提示词注入")
        Field("最大结果条数 (1-15)", maxResults, { maxResults = it }, enabled = !state.busy, type = KeyboardType.Number)
        GroupToggle("精简摘要与答案框优先", summarize, !state.busy) { summarize = it }
        Field("单条摘要最大字符数", maxSnippetChars, { maxSnippetChars = it }, enabled = !state.busy, type = KeyboardType.Number)
        SelectField(
            "提示词注入位置",
            position,
            WebSearchInjectionPosition.entries,
            WebSearchInjectionPosition::label,
            !state.busy,
        ) { position = it }
        if (position == WebSearchInjectionPosition.IN_CHAT) {
            Field("注入深度 (0-80)", depth, { depth = it }, enabled = !state.busy, type = KeyboardType.Number)
        }
        SelectField("注入角色", role, listOf("system", "user", "assistant"), { it }, !state.busy) { role = it }
        Field("注入模板（支持 {{query}} / {{results}}）", template, { template = it }, lines = 3, enabled = !state.busy)
        Field("语言代码（可选，如 zh-CN / en）", language, { language = it }, enabled = !state.busy)
        GroupToggle("启用安全搜索过滤", safeSearch, !state.busy) { safeSearch = it }

        Button(
            onClick = {
                val draft = currentDraft()
                model.saveExtensions(
                    state.prefs.extensions
                        .copy(webSearch = draft)
                        .withEnabled(BuiltInExtensionId.WEB_SEARCH, draft.enabled),
                )
                model.notice("网络搜索配置已保存")
            },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("保存搜索设置")
        }

        HorizontalDivider()
        FormHeading("搜索测试")
        Field("测试查询", testQuery, { testQuery = it }, placeholder = "例如：SillyTavern latest release", enabled = !testing)
        OutlinedButton(
            onClick = {
                val q = testQuery.trim()
                if (q.isEmpty()) return@OutlinedButton
                testing = true
                testOutput = ""
                scope.launch {
                    val draft = currentDraft()
                    testOutput = runCatching {
                        val response = model.webSearchEngine.search(q, draft)
                        model.webSearchEngine.formatForPrompt(response, draft).ifBlank { "未返回匹配结果" }
                    }.getOrElse { "搜索失败：${it.message ?: it.javaClass.simpleName}" }
                    testing = false
                }
            },
            enabled = !testing && testQuery.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.Search, null)
            Spacer(Modifier.width(8.dp))
            Text(if (testing) "正在搜索…" else "执行测试搜索")
        }
        if (testOutput.isNotBlank()) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Text(
                    text = testOutput,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}
