package app.tavern.nativeclient

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class GeminiVideoTest {
    @get:Rule val files = TemporaryFolder()

    private val mp4 = byteArrayOf(0, 0, 0, 16) + "ftypisom".encodeToByteArray() + byteArrayOf(0, 0, 0, 0)

    @Test
    fun `Omni URI output polls Files API then streams the MP4 through a redirect`() = runBlocking {
        val requests = CopyOnWriteArrayList<Pair<String, String?>>()
        val bodies = CopyOnWriteArrayList<JSONObject>()
        val polls = AtomicInteger()
        withServer { origin, exchange ->
            requests += exchange.requestURI.toString() to exchange.requestHeaders.getFirst("x-goog-api-key")
            when (exchange.requestURI.path) {
                "/v1beta/interactions" -> {
                    bodies += JSONObject(exchange.requestBody.readBytes().decodeToString())
                    exchange.json("""{"output_video":{"mime_type":"video/mp4","uri":"$origin/v1beta/files/clip-1:download?alt=media"}}""")
                }
                "/v1beta/files/clip-1" -> exchange.json(if (polls.incrementAndGet() == 1) """{"state":"PROCESSING"}""" else """{"state":"ACTIVE"}""")
                "/v1beta/files/clip-1:download" -> {
                    exchange.responseHeaders.add("Location", "/fixture.mp4")
                    exchange.sendResponseHeaders(302, -1)
                }
                "/fixture.mp4" -> exchange.reply("video/mp4", mp4)
                else -> exchange.sendResponseHeaders(404, -1)
            }
        }.use { connection ->
            val store = ChatVideoStore(files.newFolder())
            val provider = provider(connection.origin)
            val image = ChatImage("fixture", "Reference", ImageFormat.PNG)
            val profile = VideoProfile(provider.id, VideoAspect.PORTRAIT, VideoResolution.FULL_HD, 9)
            val video = GeminiVideoService(1).generate(VideoRequest(provider, profile, "Make a clip", listOf(image)),
                { mp4 }) { store.import(it, "Generated.mp4") }
            assertArrayEquals(mp4, store.file(video).readBytes())
            assertEquals(listOf("/v1beta/interactions", "/v1beta/files/clip-1", "/v1beta/files/clip-1",
                "/v1beta/files/clip-1:download?alt=media", "/fixture.mp4"), requests.map { it.first })
            assertEquals(setOf("fixture-key"), requests.map { it.second }.toSet())
            val body = bodies.single()
            assertEquals(DEFAULT_VIDEO_MODEL, body.getString("model"))
            assertFalse(body.getBoolean("store"))
            assertEquals("Make a clip", body.getJSONArray("input").getJSONObject(0).getString("text"))
            assertEquals("image/png", body.getJSONArray("input").getJSONObject(1).getString("mime_type"))
            val format = body.getJSONObject("response_format")
            assertEquals("uri", format.getString("delivery"))
            assertEquals("9:16", format.getString("aspect_ratio"))
            assertEquals("1080p", format.getString("resolution"))
            assertEquals("9s", format.getString("duration"))
        }
    }

    @Test
    fun `inline model output is supported and attachment ZIP restores the same content by digest`() = runBlocking {
        val encoded = Base64.getEncoder().encodeToString(mp4)
        withServer { _, exchange ->
            exchange.json("""{"steps":[{"type":"model_output","content":[{"type":"video","mime_type":"video/mp4","data":"$encoded"}]}]}""")
        }.use { connection ->
            val source = ChatVideoStore(files.newFolder())
            val video = GeminiVideoService(1).generate(VideoRequest(provider(connection.origin), VideoProfile(), "Make a clip"),
                { null }) { source.import(it, "Clip.mp4") }
            val chat = Chat("chat", "rina", "Video chat", messages = listOf(Message("system", "视频生成", "", videos = listOf(video))))
            val transcript = chat.jsonl("Rina", "Mio")
            val archive = ByteArrayOutputStream()
            ChatMediaArchive.write(archive, transcript, chat, readVideo = source::input) { null }
            val target = ChatVideoStore(files.newFolder())
            val restored = ChatMediaArchive.read(archive.toByteArray().inputStream(), {
                importJsonLinesChat(it.decodeToString(), chat.title)
            }, restoreVideo = { item, stream -> target.restore(item, stream) }) { _, _ -> error("Unexpected image") }
            assertEquals(video, restored.messages.single().videos.single())
            assertArrayEquals(mp4, target.file(video).readBytes())
            val tampered = mp4.copyOf().apply { this[lastIndex] = 1 }
            assertThrows(IllegalArgumentException::class.java) { target.restore(video, tampered.inputStream()) }
            assertArrayEquals(mp4, target.file(video).readBytes())
        }
    }

    @Test
    fun `Omni video download rejects foreign Files URI and untrusted redirect`() = runBlocking {
        val api = URI("https://generativelanguage.googleapis.com/v1beta")
        assertEquals("files/example-1", geminiVideoFile(api, "files/example-1"))
        assertThrows(IllegalArgumentException::class.java) { geminiVideoFile(api, "https://example.com/v1beta/files/clip") }
        assertThrows(IllegalArgumentException::class.java) { geminiVideoFile(api, "/v1beta/files/invalid%2fpath") }
        val redirected = AtomicInteger()
        withServer { _, exchange ->
            redirected.incrementAndGet()
            exchange.sendResponseHeaders(404, -1)
        }.use { other ->
            withServer { _, exchange ->
                when (exchange.requestURI.path) {
                    "/v1beta/interactions" -> exchange.json("""{"output_video":{"uri":"files/clip"}}""")
                    "/v1beta/files/clip" -> exchange.json("""{"state":"ACTIVE"}""")
                    else -> {
                        exchange.responseHeaders.add("Location", "${other.origin}/fixture.mp4")
                        exchange.sendResponseHeaders(302, -1)
                    }
                }
            }.use { connection ->
                val result = runCatching {
                    GeminiVideoService(1).generate(VideoRequest(provider(connection.origin), VideoProfile(), "Make a clip"), { null }) {
                        error("Unexpected video")
                    }
                }
                assertEquals("视频下载重定向地址无效", result.exceptionOrNull()?.message)
                assertEquals(0, redirected.get())
            }
        }
    }

    @Test
    fun `original account message restores native video and relative original MP4 media`() {
        val profile = files.newFolder()
        val source = ChatVideoStore(files.newFolder())
        val clip = source.import(mp4.inputStream(), "Clip.mp4")
        val local = profile.resolve("$NATIVE_BACKUP_IMAGES/${clip.filename}").apply { requireNotNull(parentFile).mkdirs() }
        local.writeBytes(mp4)
        profile.resolve("user/images/original clip.mp4").writeBytes(mp4)
        val receiver = ChatVideoStore(files.newFolder())
        val media = OriginalLibraryMedia(FolderOriginalLibrary(profile), null, null, {}, receiver)
        val raw = Message("system", "视频生成", "", videos = listOf(clip))
        assertEquals(listOf(clip), media.message(raw, nativeBackup = true).videos)
        assertArrayEquals(mp4, receiver.file(clip).readBytes())
        val original = Message("assistant", "Rina", "Watch this", raw =
            """{"extra":{"media":[{"type":"video","url":"/user/images/original%20clip.mp4"}]}}""")
        assertEquals(clip.id, media.message(original).videos.single().id)
        local.delete()
        val fresh = OriginalLibraryMedia(FolderOriginalLibrary(profile), null, null, {}, receiver)
        assertEquals(emptyList<ChatVideo>(), fresh.message(raw, nativeBackup = true).videos)
        assertEquals(1, fresh.missing)
    }

    private fun provider(origin: String) = Provider("google", "Google", origin, "fixture-key", protocol = ApiProtocol.GEMINI_NATIVE)

    private class Connection(private val server: HttpServer, val origin: String) : AutoCloseable {
        override fun close() = server.stop(0)
    }

    private fun withServer(handler: (String, HttpExchange) -> Unit): Connection {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val origin = "http://127.0.0.1:${server.address.port}"
        server.createContext("/") { exchange -> exchange.use { handler(origin, it) } }
        server.start()
        return Connection(server, origin)
    }

    private fun HttpExchange.json(body: String) = reply("application/json", body.encodeToByteArray())

    private fun HttpExchange.reply(contentType: String, body: ByteArray) {
        responseHeaders.add("Content-Type", contentType)
        sendResponseHeaders(200, body.size.toLong())
        responseBody.write(body)
    }
}
