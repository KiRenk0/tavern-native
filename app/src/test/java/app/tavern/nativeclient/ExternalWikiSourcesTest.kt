package app.tavern.nativeclient

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class ExternalWikiSourcesTest {
    private fun startServer(handler: (String, String?) -> Pair<Int, String>): Pair<HttpServer, String> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.use {
                val (status, body) = handler(it.requestURI.path, it.requestURI.rawQuery)
                val bytes = body.encodeToByteArray()
                it.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
                it.sendResponseHeaders(status, bytes.size.toLong())
                it.responseBody.write(bytes)
            }
        }
        server.start()
        return server to "http://127.0.0.1:${server.address.port}"
    }

    @Test
    fun youTubeVideoIdParsingCaptionExtractionAndTimedTextFormatsWork() = runBlocking {
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=12s"))
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("https://m.youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("https://www.youtube.com/embed/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("youtube:dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("dQw4w9WgXcQ"))
        assertNull(extractYouTubeVideoId("https://example.com/watch?v=notyoutube"))

        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ&lang=zh",
            buildYouTubeImportUrl("dQw4w9WgXcQ", "zh"),
        )

        val watchHtml = """
            <html><script>
            var ytInitialPlayerResponse = {
              "videoDetails": {"title": "Worldbuilding {101}"},
              "captions": {
                "playerCaptionsTracklistRenderer": {
                  "captionTracks": [
                    {"baseUrl": "https://www.youtube.com/api/timedtext?v=dQw4w9WgXcQ\u0026lang=en", "languageCode": "en", "kind": "asr", "name": {"simpleText": "English (auto)"}},
                    {"baseUrl": "https://www.youtube.com/api/timedtext?v=dQw4w9WgXcQ\u0026lang=zh-CN", "languageCode": "zh-CN", "name": {"simpleText": "中文"}}
                  ]
                }
              }
            };
            </script></html>
        """.trimIndent()

        val meta = parseYouTubeWatchPage(watchHtml)
        assertEquals("Worldbuilding {101}", meta.title)
        assertEquals(2, meta.tracks.size)
        assertEquals("zh-CN", selectCaptionTrack(meta.tracks, "zh")?.languageCode)
        assertEquals("en", selectCaptionTrack(meta.tracks, "en")?.languageCode)

        val xmlCaptions = """
            <?xml version="1.0" encoding="utf-8" ?>
            <transcript>
              <text start="0.0" dur="2.5">Welcome to the &amp;#39;Tavern&amp;#39;</text>
              <text start="2.5" dur="3.0">Magic &amp;amp; Lore</text>
            </transcript>
        """.trimIndent()
        assertEquals("Welcome to the 'Tavern'\nMagic & Lore", parseYouTubeTimedTextXml(xmlCaptions))

        val format3Xml = """
            <timedtext format="3"><body>
              <p t="0" d="1000"><s>First </s><s>line</s></p>
              <p t="1000" d="1000"><s>Second line</s></p>
            </body></timedtext>
        """.trimIndent()
        assertEquals("First line\nSecond line", parseYouTubeTimedTextXml(format3Xml))

        val json3Captions = """{"events":[{"segs":[{"utf8":"Hello "},{"utf8":"world"}]},{"segs":[{"utf8":"Line two"}]}]}"""
        assertEquals("Hello world\nLine two", parseYouTubeTimedTextJson3(json3Captions))

        // Full importer with stubbed fetcher
        val doc = importYouTubeTranscript("https://youtu.be/dQw4w9WgXcQ", "zh") { url ->
            when {
                url.contains("/watch?") -> watchHtml
                url.contains("timedtext") -> xmlCaptions
                else -> error("unexpected $url")
            }
        }
        assertEquals("YouTube · Worldbuilding {101}", doc.title)
        assertTrue(doc.text.contains("Welcome to the 'Tavern'"))
        assertTrue(doc.text.contains("Language: zh-CN"))

        // Rejects non-YouTube caption URL
        val evilWatch = """<script>var ytInitialPlayerResponse = {"videoDetails":{"title":"X"},"captions":{"playerCaptionsTracklistRenderer":{"captionTracks":[{"baseUrl":"https://evil.example/cap","languageCode":"en"}]}}};</script>"""
        assertTrue(runCatching { importYouTubeTranscript("dQw4w9WgXcQ") { evilWatch } }.isFailure)
    }

    @Test
    fun fandomWikiUrlParsingAndArticleExtractionWork() = runBlocking {
        val ref = parseFandomUrl("https://elderscrolls.fandom.com/zh/wiki/%E5%A4%A9%E9%99%85%E7%9C%81#History")
        assertNotNull(ref)
        assertEquals(ExternalSourceKind.FANDOM, ref!!.kind)
        assertEquals("https://elderscrolls.fandom.com/zh/api.php", ref.apiUrl)
        assertEquals("天际省", ref.title)
        assertEquals("https://elderscrolls.fandom.com/wiki/Skyrim", buildFandomImportUrl("elderscrolls", "Skyrim"))

        val (server, origin) = startServer { path, query ->
            assertEquals("/api.php", path)
            assertTrue(query.orEmpty().contains("action=parse"))
            200 to """
                {
                  "parse": {
                    "title": "Skyrim",
                    "displaytitle": "<i>Skyrim</i>",
                    "text": "<div class=\"toc\">Contents</div><p>Skyrim is a northern province[1].<span class=\"mw-editsection\">[edit]</span></p><script>alert(1)</script><div class=\"navbox\">Nav</div><p>Home of the Nords.</p>"
                  }
                }
            """.trimIndent()
        }
        try {
            val localRef = WikiPageRef(ExternalSourceKind.FANDOM, "$origin/api.php", "Skyrim", "$origin/wiki/Skyrim", true)
            val doc = importFandomArticle(localRef)
            assertEquals("Skyrim", doc.title)
            assertTrue(doc.text.contains("Skyrim is a northern province."))
            assertTrue(doc.text.contains("Home of the Nords."))
            assertTrue(!doc.text.contains("alert(1)"))
            assertTrue(!doc.text.contains("Nav"))
            assertTrue(!doc.text.contains("[1]"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun mediaWikiUrlParsingSupportsWikipediaAndCustomWikisWithFallback() = runBlocking {
        val wp = parseMediaWikiUrl("https://en.m.wikipedia.org/wiki/Role-playing_game")
        assertNotNull(wp)
        assertEquals("https://en.wikipedia.org/w/api.php", wp!!.apiUrl)
        assertEquals("Role-playing game", wp.title)
        assertTrue(wp.strictMediaWiki)

        val custom = parseMediaWikiUrl("https://lore.example.org/w/index.php?title=Arcane_Academy")
        assertNotNull(custom)
        assertEquals("https://lore.example.org/w/api.php", custom!!.apiUrl)
        assertEquals("Arcane Academy", custom.title)

        assertEquals("https://wiki.example.org/wiki/Moon_Tower", buildMediaWikiImportUrl("https://wiki.example.org", "Moon Tower"))

        val (server, origin) = startServer { _, query ->
            when {
                query.orEmpty().contains("action=query") -> {
                    if (query.orEmpty().contains("titles=NoExtracts")) {
                        200 to """{"query":{"pages":[{"pageid":1,"title":"NoExtracts"}]}}"""
                    } else {
                        200 to """{"query":{"pages":[{"pageid":42,"title":"Role-playing game","extract":"A role-playing game is a game in which players assume the roles of characters."}]}}"""
                    }
                }
                query.orEmpty().contains("action=parse") -> {
                    200 to """{"parse":{"title":"NoExtracts","text":"<p>Parsed fallback content from MediaWiki.</p>"}}"""
                }
                else -> 404 to "{}"
            }
        }
        try {
            val extractDoc = importMediaWikiPage(
                WikiPageRef(ExternalSourceKind.MEDIAWIKI, "$origin/w/api.php", "Role-playing game", "$origin/wiki/Role-playing_game", true),
            )
            assertEquals("Role-playing game", extractDoc.title)
            assertTrue(extractDoc.text.contains("assume the roles of characters"))

            // Fallback to action=parse when extracts extension isn't installed
            val fallbackDoc = importMediaWikiPage(
                WikiPageRef(ExternalSourceKind.MEDIAWIKI, "$origin/api.php", "NoExtracts", "$origin/wiki/NoExtracts", false),
            )
            assertEquals("NoExtracts", fallbackDoc.title)
            assertEquals("Parsed fallback content from MediaWiki.", fallbackDoc.text)

            // Non-MediaWiki custom /wiki/ site returns null gracefully so standard HTML import runs
            val nonWiki = importExternalSourceIfMatched("https://blog.example.com/wiki/my-post") { "<html>not json</html>" }
            assertNull(nonWiki)
        } finally {
            server.stop(0)
        }
    }
}
