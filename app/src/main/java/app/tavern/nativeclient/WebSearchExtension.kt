package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

internal enum class WebSearchProvider(
    val wire: String,
    val label: String,
    val requiresApiKey: Boolean,
    val defaultEndpoint: String,
) {
    SEARXNG("searxng", "SearXNG", false, "https://searx.be/search"),
    TAVILY("tavily", "Tavily", true, "https://api.tavily.com/search"),
    SERPAPI("serpapi", "SerpApi", true, "https://serpapi.com/search.json"),
    DUCKDUCKGO("duckduckgo", "DuckDuckGo", false, "https://api.duckduckgo.com/"),
    BRAVE("brave", "Brave Search", true, "https://api.search.brave.com/res/v1/web/search"),
    ;

    companion object {
        fun from(wire: String?): WebSearchProvider =
            entries.firstOrNull { it.wire.equals(wire?.trim(), ignoreCase = true) } ?: DUCKDUCKGO
    }
}

internal enum class WebSearchTriggerMode(val wire: String, val label: String) {
    MANUAL("manual", "仅手动 (/websearch)"),
    KEYWORD_REGEX("keyword_regex", "关键词 / 正则自动触发"),
    ALWAYS("always", "每次对话自动触发"),
    ;

    companion object {
        fun from(wire: String?): WebSearchTriggerMode =
            entries.firstOrNull { it.wire.equals(wire?.trim(), ignoreCase = true) } ?: MANUAL
    }
}

internal enum class WebSearchInjectionPosition(val wire: String, val label: String) {
    BEFORE_CHAR("before_char", "角色设定之前"),
    AFTER_CHAR("after_char", "角色设定之后"),
    IN_CHAT("in_chat", "聊天历史指定深度"),
    ;

    companion object {
        fun from(wire: String?): WebSearchInjectionPosition =
            entries.firstOrNull { it.wire.equals(wire?.trim(), ignoreCase = true) } ?: IN_CHAT
    }
}

internal data class WebSearchConfig(
    val enabled: Boolean = false,
    val backend: WebSearchProvider = WebSearchProvider.DUCKDUCKGO,
    val endpoint: String = "",
    val apiKey: String = "",
    val triggerMode: WebSearchTriggerMode = WebSearchTriggerMode.MANUAL,
    val triggerPattern: String = """(?i)(?:搜索|查询|最新|search for|look up|what is the latest)""",
    val maxResults: Int = 5,
    val summarizeResults: Boolean = true,
    val maxSnippetChars: Int = 280,
    val injectionPosition: WebSearchInjectionPosition = WebSearchInjectionPosition.IN_CHAT,
    val injectionDepth: Int = 1,
    val injectionRole: String = "system",
    val injectionTemplate: String = "[Web Search Results for \"{{query}}\"]\n{{results}}",
    val language: String = "",
    val safeSearch: Boolean = true,
) {
    val effectiveEndpoint: String get() = endpoint.trim().ifEmpty { backend.defaultEndpoint }

    fun json(seal: (String) -> String = { it }): JSONObject = jsonOf(
        "enabled" to enabled,
        "backend" to backend.wire,
        "endpoint" to endpoint,
        "apiKey" to if (apiKey.isBlank()) "" else seal(apiKey),
        "triggerMode" to triggerMode.wire,
        "triggerPattern" to triggerPattern,
        "maxResults" to maxResults.coerceIn(1, 15),
        "summarizeResults" to summarizeResults,
        "maxSnippetChars" to maxSnippetChars.coerceIn(60, 2000),
        "injectionPosition" to injectionPosition.wire,
        "injectionDepth" to injectionDepth.coerceIn(0, 80),
        "injectionRole" to injectionRole,
        "injectionTemplate" to injectionTemplate,
        "language" to language,
        "safeSearch" to safeSearch,
    )

    companion object {
        fun from(j: JSONObject?, unseal: (String) -> String = { it }): WebSearchConfig {
            if (j == null) return WebSearchConfig()
            val rawKey = j.string("apiKey")
            return WebSearchConfig(
                enabled = j.optBoolean("enabled", false),
                backend = WebSearchProvider.from(j.string("backend")),
                endpoint = j.string("endpoint"),
                apiKey = if (rawKey.isBlank()) "" else runCatching { unseal(rawKey) }.getOrDefault(""),
                triggerMode = WebSearchTriggerMode.from(j.string("triggerMode")),
                triggerPattern = j.string("triggerPattern")
                    .ifBlank { """(?i)(?:搜索|查询|最新|search for|look up|what is the latest)""" },
                maxResults = j.optInt("maxResults", 5).coerceIn(1, 15),
                summarizeResults = j.optBoolean("summarizeResults", true),
                maxSnippetChars = j.optInt("maxSnippetChars", 280).coerceIn(60, 2000),
                injectionPosition = WebSearchInjectionPosition.from(j.string("injectionPosition")),
                injectionDepth = j.optInt("injectionDepth", 1).coerceIn(0, 80),
                injectionRole = j.string("injectionRole").takeIf { it in setOf("system", "user", "assistant") } ?: "system",
                injectionTemplate = j.string("injectionTemplate")
                    .ifBlank { "[Web Search Results for \"{{query}}\"]\n{{results}}" },
                language = j.string("language"),
                safeSearch = j.optBoolean("safeSearch", true),
            )
        }
    }
}

internal data class WebSearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    val source: String = "",
    val publishedDate: String = "",
)

internal data class WebSearchResponse(
    val query: String,
    val backend: WebSearchProvider,
    val results: List<WebSearchResult>,
    val answerBox: String? = null,
)

internal data class WebSearchHttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: JSONObject? = null,
)

internal interface WebSearchHttpClient {
    suspend fun execute(req: WebSearchHttpRequest): String
}

internal object DefaultWebSearchHttpClient : WebSearchHttpClient {
    override suspend fun execute(req: WebSearchHttpRequest): String =
        request(
            url = req.url,
            body = req.body,
            headers = req.headers,
            readTimeout = 20_000,
            followRedirects = true,
            method = req.method,
        ) { input, _ -> input.readLimited(4 * 1024 * 1024).decodeToString() }
}

internal class WebSearchEngine(private val http: WebSearchHttpClient = DefaultWebSearchHttpClient) {
    private val slashRegex = Regex("""(?is)^\s*/websearch(?:\s+([\s\S]+))?$""")
    private val htmlTagRegex = Regex("""<[^>]+>""")
    private val whitespaceRegex = Regex("""\s+""")

    fun parseSlashCommand(input: String): String? {
        val match = slashRegex.matchEntire(input) ?: return null
        return match.groupValues[1].trim()
    }

    fun shouldTrigger(message: String, config: WebSearchConfig): String? {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) return null
        val slashQuery = parseSlashCommand(trimmed)
        if (slashQuery != null) return slashQuery.takeIf(String::isNotBlank)
        if (!config.enabled) return null
        return when (config.triggerMode) {
            WebSearchTriggerMode.MANUAL -> null
            WebSearchTriggerMode.ALWAYS -> trimmed.take(400)
            WebSearchTriggerMode.KEYWORD_REGEX -> {
                val pattern = config.triggerPattern.trim()
                if (pattern.isEmpty()) return null
                val regex = runCatching { Regex(pattern) }.getOrNull() ?: return null
                if (!regex.containsMatchIn(trimmed)) return null
                // Remove leading trigger phrase if it acts as a prefix command, otherwise pass full query
                val stripped = trimmed.replace(Regex("""(?i)^(?:搜索|查询|search for|look up)\s*[:：]?\s*"""), "").trim()
                stripped.ifBlank { trimmed }.take(400)
            }
        }
    }

    fun buildRequest(query: String, config: WebSearchConfig): WebSearchHttpRequest {
        val cleanQuery = query.trim()
        require(cleanQuery.isNotEmpty()) { "搜索关键词不能为空" }
        val encodedQuery = URLEncoder.encode(cleanQuery, "UTF-8")
        val max = config.maxResults.coerceIn(1, 15)
        val endpoint = config.effectiveEndpoint
        externalWebAddress(endpoint)

        return when (config.backend) {
            WebSearchProvider.SEARXNG -> {
                val base = endpoint.trimEnd('?', '&')
                val sep = if ('?' in base) '&' else '?'
                val langParam = config.language.trim().takeIf(String::isNotEmpty)
                    ?.let { "&language=${URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
                val safe = if (config.safeSearch) "1" else "0"
                WebSearchHttpRequest(
                    url = "$base${sep}q=$encodedQuery&format=json&safesearch=$safe$langParam",
                    method = "GET",
                    headers = buildMap {
                        put("Accept", "application/json")
                        put("User-Agent", "TavernNative/1.0")
                        if (config.apiKey.isNotBlank()) put("Authorization", "Bearer ${config.apiKey.trim()}")
                    },
                )
            }
            WebSearchProvider.TAVILY -> {
                require(config.apiKey.isNotBlank()) { "Tavily 需要配置 API Key" }
                val body = jsonOf(
                    "api_key" to config.apiKey.trim(),
                    "query" to cleanQuery,
                    "max_results" to max,
                    "include_answer" to config.summarizeResults,
                    "search_depth" to "basic",
                )
                WebSearchHttpRequest(
                    url = endpoint,
                    method = "POST",
                    headers = mapOf(
                        "Accept" to "application/json",
                        "Content-Type" to "application/json; charset=utf-8",
                        "Authorization" to "Bearer ${config.apiKey.trim()}",
                    ),
                    body = body,
                )
            }
            WebSearchProvider.SERPAPI -> {
                require(config.apiKey.isNotBlank()) { "SerpApi 需要配置 API Key" }
                val base = endpoint.trimEnd('?', '&')
                val sep = if ('?' in base) '&' else '?'
                val encodedKey = URLEncoder.encode(config.apiKey.trim(), "UTF-8")
                val hl = config.language.trim().takeIf(String::isNotEmpty)
                    ?.let { "&hl=${URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
                val safe = if (config.safeSearch) "&safe=active" else ""
                WebSearchHttpRequest(
                    url = "$base${sep}q=$encodedQuery&api_key=$encodedKey&num=$max&engine=google$hl$safe",
                    method = "GET",
                    headers = mapOf("Accept" to "application/json"),
                )
            }
            WebSearchProvider.DUCKDUCKGO -> {
                val base = endpoint.trimEnd('?', '&')
                val sep = if ('?' in base) '&' else '?'
                WebSearchHttpRequest(
                    url = "$base${sep}q=$encodedQuery&format=json&no_html=1&skip_disambig=1",
                    method = "GET",
                    headers = mapOf("Accept" to "application/json", "User-Agent" to "TavernNative/1.0"),
                )
            }
            WebSearchProvider.BRAVE -> {
                require(config.apiKey.isNotBlank()) { "Brave Search 需要配置 Subscription Token (API Key)" }
                val base = endpoint.trimEnd('?', '&')
                val sep = if ('?' in base) '&' else '?'
                val safe = if (config.safeSearch) "moderate" else "off"
                val lang = config.language.trim().takeIf(String::isNotEmpty)
                    ?.let { "&search_lang=${URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
                WebSearchHttpRequest(
                    url = "$base${sep}q=$encodedQuery&count=$max&safesearch=$safe$lang",
                    method = "GET",
                    headers = mapOf(
                        "Accept" to "application/json",
                        "X-Subscription-Token" to config.apiKey.trim(),
                    ),
                )
            }
        }
    }

    fun parseResponse(backend: WebSearchProvider, rawBody: String, maxResults: Int = 5): Pair<List<WebSearchResult>, String?> {
        val limit = maxResults.coerceIn(1, 15)
        val root = runCatching { JSONObject(rawBody.trim()) }.getOrNull() ?: return emptyList<WebSearchResult>() to null
        return when (backend) {
            WebSearchProvider.SEARXNG -> {
                val items = root.optJSONArray("results")?.objects().orEmpty().take(limit).mapNotNull { row ->
                    val title = cleanSnippet(row.string("title"))
                    val url = row.string("url").trim()
                    val content = cleanSnippet(row.string("content").ifBlank { row.string("snippet") })
                    if (title.isBlank() && content.isBlank()) null
                    else WebSearchResult(
                        title = title.ifBlank { url },
                        url = url,
                        snippet = content,
                        source = row.string("engine"),
                        publishedDate = row.string("publishedDate"),
                    )
                }
                val answer = root.optJSONArray("answers")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }.firstOrNull()
                }
                items to answer
            }
            WebSearchProvider.TAVILY -> {
                val items = root.optJSONArray("results")?.objects().orEmpty().take(limit).mapNotNull { row ->
                    val title = cleanSnippet(row.string("title"))
                    val url = row.string("url").trim()
                    val content = cleanSnippet(row.string("content"))
                    if (title.isBlank() && content.isBlank()) null
                    else WebSearchResult(
                        title = title.ifBlank { url },
                        url = url,
                        snippet = content,
                        source = "tavily",
                        publishedDate = row.string("published_date"),
                    )
                }
                val answer = root.string("answer").takeIf(String::isNotBlank)
                items to answer
            }
            WebSearchProvider.SERPAPI -> {
                val items = root.optJSONArray("organic_results")?.objects().orEmpty().take(limit).mapNotNull { row ->
                    val title = cleanSnippet(row.string("title"))
                    val url = row.string("link").trim()
                    val snippet = cleanSnippet(row.string("snippet"))
                    if (title.isBlank() && snippet.isBlank()) null
                    else WebSearchResult(
                        title = title.ifBlank { url },
                        url = url,
                        snippet = snippet,
                        source = row.string("source").ifBlank { row.string("displayed_link") },
                        publishedDate = row.string("date"),
                    )
                }
                val answerBox = root.optJSONObject("answer_box")?.let { box ->
                    box.string("answer").ifBlank { box.string("snippet") }.ifBlank { box.string("result") }
                }?.takeIf(String::isNotBlank)
                items to answerBox
            }
            WebSearchProvider.DUCKDUCKGO -> {
                val results = mutableListOf<WebSearchResult>()
                val abstractText = cleanSnippet(root.string("AbstractText"))
                val abstractUrl = root.string("AbstractURL").trim()
                val heading = cleanSnippet(root.string("Heading"))
                val answer = root.string("Answer").takeIf(String::isNotBlank) ?: abstractText.takeIf(String::isNotBlank)
                if (abstractText.isNotBlank()) {
                    results += WebSearchResult(
                        title = heading.ifBlank { "DuckDuckGo Instant Answer" },
                        url = abstractUrl,
                        snippet = abstractText,
                        source = root.string("AbstractSource").ifBlank { "duckduckgo" },
                    )
                }
                fun collectRelated(array: JSONArray?) {
                    if (array == null) return
                    for (i in 0 until array.length()) {
                        if (results.size >= limit) return
                        val obj = array.optJSONObject(i) ?: continue
                        val nested = obj.optJSONArray("Topics")
                        if (nested != null) {
                            collectRelated(nested)
                            continue
                        }
                        val text = cleanSnippet(obj.string("Text"))
                        val url = obj.string("FirstURL").trim()
                        if (text.isNotBlank()) {
                            val title = text.substringBefore(" - ").take(100)
                            results += WebSearchResult(title = title, url = url, snippet = text, source = "duckduckgo")
                        }
                    }
                }
                collectRelated(root.optJSONArray("Results"))
                collectRelated(root.optJSONArray("RelatedTopics"))
                results.take(limit) to answer
            }
            WebSearchProvider.BRAVE -> {
                val webObj = root.optJSONObject("web")
                val items = webObj?.optJSONArray("results")?.objects().orEmpty().take(limit).mapNotNull { row ->
                    val title = cleanSnippet(row.string("title"))
                    val url = row.string("url").trim()
                    val desc = cleanSnippet(row.string("description"))
                    if (title.isBlank() && desc.isBlank()) null
                    else WebSearchResult(
                        title = title.ifBlank { url },
                        url = url,
                        snippet = desc,
                        source = row.optJSONObject("meta_url")?.string("hostname").orEmpty(),
                        publishedDate = row.string("age"),
                    )
                }
                val summary = root.optJSONObject("summarizer")?.string("key")?.takeIf(String::isNotBlank)
                items to summary
            }
        }
    }

    suspend fun search(query: String, config: WebSearchConfig): WebSearchResponse {
        val req = buildRequest(query, config)
        val raw = http.execute(req)
        val (results, answerBox) = parseResponse(config.backend, raw, config.maxResults)
        return WebSearchResponse(
            query = query.trim(),
            backend = config.backend,
            results = results,
            answerBox = answerBox,
        )
    }

    fun formatForPrompt(response: WebSearchResponse, config: WebSearchConfig): String {
        if (response.results.isEmpty() && response.answerBox.isNullOrBlank()) return ""
        val maxLen = config.maxSnippetChars.coerceIn(60, 2000)
        val body = buildString {
            if (config.summarizeResults && !response.answerBox.isNullOrBlank()) {
                append("Summary: ")
                append(truncateText(response.answerBox, maxLen))
                if (response.results.isNotEmpty()) append("\n")
            }
            response.results.forEachIndexed { idx, item ->
                if (idx > 0) append("\n")
                append("${idx + 1}. ${item.title}")
                if (item.url.isNotBlank()) append(" (${item.url})")
                val snippet = if (config.summarizeResults) truncateText(item.snippet, maxLen) else item.snippet
                if (snippet.isNotBlank()) append("\n   $snippet")
            }
        }
        return config.injectionTemplate
            .replace("{{query}}", response.query)
            .replace("{{results}}", body)
            .replace("{{backend}}", response.backend.label)
            .trim()
    }

    private fun cleanSnippet(raw: String): String =
        htmlTagRegex.replace(raw, " ").replace(whitespaceRegex, " ").trim()

    private fun truncateText(text: String, maxChars: Int): String {
        val cleaned = cleanSnippet(text)
        return if (cleaned.length <= maxChars) cleaned else cleaned.take(maxChars - 1).trimEnd() + "…"
    }
}
