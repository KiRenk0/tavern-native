package app.tavern.nativeclient

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchExtensionTest {

    @Test
    fun `builds requests and parses results for all five search backends`() = runTest {
        val responses = mapOf(
            WebSearchProvider.SEARXNG to """
                {"answers":["42"],"results":[{"title":"SearX Result","url":"https://a.example","content":"<b>Hello</b> world","engine":"google"}]}
            """.trimIndent(),
            WebSearchProvider.TAVILY to """
                {"answer":"Tavily summary","results":[{"title":"Tavily Doc","url":"https://t.example","content":"Detailed info"}]}
            """.trimIndent(),
            WebSearchProvider.SERPAPI to """
                {"answer_box":{"answer":"Serp answer"},"organic_results":[{"title":"Serp Hit","link":"https://s.example","snippet":"Snippet text"}]}
            """.trimIndent(),
            WebSearchProvider.DUCKDUCKGO to """
                {"Heading":"Kotlin","AbstractText":"Modern JVM language","AbstractURL":"https://kotlinlang.org","RelatedTopics":[{"Text":"Coroutines - Async framework","FirstURL":"https://kotlinlang.org/docs/coroutines-overview.html"}]}
            """.trimIndent(),
            WebSearchProvider.BRAVE to """
                {"web":{"results":[{"title":"Brave Hit","url":"https://b.example","description":"Independent search result","meta_url":{"hostname":"b.example"}}]}}
            """.trimIndent(),
        )

        WebSearchProvider.entries.forEach { backend ->
            var capturedReq: WebSearchHttpRequest? = null
            val engine = WebSearchEngine(object : WebSearchHttpClient {
                override suspend fun execute(req: WebSearchHttpRequest): String {
                    capturedReq = req
                    return responses.getValue(backend)
                }
            })
            val cfg = WebSearchConfig(
                enabled = true,
                backend = backend,
                apiKey = if (backend.requiresApiKey) "test-secret-key" else "",
                maxResults = 3,
                summarizeResults = true,
            )

            val response = engine.search("kotlin coroutines", cfg)
            assertNotNull(capturedReq)
            assertTrue("Expected non-empty results for $backend", response.results.isNotEmpty())
            val formatted = engine.formatForPrompt(response, cfg)
            assertTrue(formatted.contains("kotlin coroutines"))
            assertTrue(formatted.contains(response.results.first().title))

            when (backend) {
                WebSearchProvider.TAVILY -> {
                    assertEquals("POST", capturedReq!!.method)
                    assertEquals("test-secret-key", capturedReq!!.body?.optString("api_key"))
                    assertEquals("Tavily summary", response.answerBox)
                }
                WebSearchProvider.BRAVE -> {
                    assertEquals("test-secret-key", capturedReq!!.headers["X-Subscription-Token"])
                }
                WebSearchProvider.SEARXNG -> {
                    assertTrue(capturedReq!!.url.contains("format=json"))
                    assertEquals("Hello world", response.results.first().snippet)
                }
                WebSearchProvider.SERPAPI -> {
                    assertTrue(capturedReq!!.url.contains("api_key=test-secret-key"))
                    assertEquals("Serp answer", response.answerBox)
                }
                WebSearchProvider.DUCKDUCKGO -> {
                    assertTrue(capturedReq!!.url.contains("format=json"))
                    assertEquals(2, response.results.size)
                }
            }
        }
    }

    @Test
    fun `evaluates manual regex and always trigger modes accurately`() {
        val engine = WebSearchEngine()

        // Manual mode ignores normal chat, triggers only on /websearch
        val manual = WebSearchConfig(enabled = true, triggerMode = WebSearchTriggerMode.MANUAL)
        assertNull(engine.shouldTrigger("Tell me about dragons", manual))
        assertEquals("SillyTavern 1.19", engine.shouldTrigger("/websearch SillyTavern 1.19", manual))

        // Keyword/regex mode extracts query on match
        val regex = WebSearchConfig(
            enabled = true,
            triggerMode = WebSearchTriggerMode.KEYWORD_REGEX,
            triggerPattern = """(?i)(?:搜索|latest news)""",
        )
        assertNull(engine.shouldTrigger("Good morning!", regex))
        assertEquals("Android 16 release", engine.shouldTrigger("搜索: Android 16 release", regex))
        assertEquals("What is the latest news on AI?", engine.shouldTrigger("What is the latest news on AI?", regex))

        // Always mode triggers on every non-empty message when enabled, never when disabled
        val always = WebSearchConfig(enabled = true, triggerMode = WebSearchTriggerMode.ALWAYS)
        assertEquals("hello world", engine.shouldTrigger("hello world", always))
        assertNull(engine.shouldTrigger("hello world", always.copy(enabled = false)))
    }
}
