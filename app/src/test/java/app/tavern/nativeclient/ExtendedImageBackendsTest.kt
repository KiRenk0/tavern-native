package app.tavern.nativeclient

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

class ExtendedImageBackendsTest {
    private val samplePng = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNgYAAAAAMAASsJTYQAAAAASUVORK5CYII=",
    )
    private val samplePngB64 = Base64.getEncoder().encodeToString(samplePng)

    private data class RecordedCall(
        val method: String,
        val path: String,
        val query: String?,
        val contentType: String?,
        val auth: String?,
        val xApiKey: String?,
        val googKey: String?,
        val rawBody: ByteArray,
    ) {
        val bodyText get() = rawBody.decodeToString()
        val jsonBody get() = bodyText.takeIf(String::isNotBlank)?.let(::JSONObject)
    }

    private data class MockReply(val contentType: String, val body: ByteArray, val status: Int = 200)

    private fun startServer(handler: (RecordedCall) -> MockReply): Pair<HttpServer, String> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.use {
                val raw = it.requestBody.readBytes()
                val call = RecordedCall(
                    method = it.requestMethod,
                    path = it.requestURI.path,
                    query = it.requestURI.rawQuery,
                    contentType = it.requestHeaders.getFirst("Content-Type"),
                    auth = it.requestHeaders.getFirst("Authorization"),
                    xApiKey = it.requestHeaders.getFirst("x-api-key"),
                    googKey = it.requestHeaders.getFirst("x-goog-api-key"),
                    rawBody = raw,
                )
                val reply = handler(call)
                it.responseHeaders.set("Content-Type", reply.contentType)
                it.sendResponseHeaders(reply.status, reply.body.size.toLong())
                it.responseBody.write(reply.body)
            }
        }
        server.start()
        return server to "http://127.0.0.1:${server.address.port}"
    }

    @Test
    fun protocolResolutionAndAvailabilityWorkForAllNineExtendedBackends() {
        assertEquals(ImageProtocol.POLLINATIONS, resolveImageProtocol("pollinations"))
        assertEquals(ImageProtocol.STABILITY, resolveImageProtocol("stabilityai"))
        assertEquals(ImageProtocol.DRAW_THINGS, resolveImageProtocol("draw-things"))
        assertEquals(ImageProtocol.SD_NEXT, resolveImageProtocol("sd.next"))
        assertEquals(ImageProtocol.TOGETHER, resolveImageProtocol("together.ai"))
        assertEquals(ImageProtocol.IMAGEN, resolveImageProtocol("vertex"))
        assertEquals(ImageProtocol.XAI, resolveImageProtocol("grok"))
        assertEquals(ImageProtocol.NANOGPT, resolveImageProtocol("nano-gpt"))
        assertEquals(ImageProtocol.HUGGINGFACE, resolveImageProtocol("hf"))
        assertNull(resolveImageProtocol("nonexistent-backend"))

        // Builtin Pollinations works without configuring any custom provider
        val builtin = pollinationsBuiltinProvider()
        assertTrue(ImageProtocol.POLLINATIONS.available(builtin))
        assertFalse(ImageProtocol.STABILITY.available(builtin))
        val profile = resolveImageProfileForSource("pollinations", emptyList(), DrawingSettings())
        assertEquals(POLLINATIONS_BUILTIN_ID, profile.providerId)
        assertEquals(ImageProtocol.POLLINATIONS, profile.protocol)
        assertEquals("flux", profile.model)
        assertNotNull(profile.resolve(emptyList(), "a cat"))

        // Horde provider remains isolated
        val horde = Provider("horde", "AI Horde", "https://aihorde.net/api", protocol = ApiProtocol.HORDE_TEXT)
        assertEquals(listOf(ImageProtocol.HORDE), ImageProtocol.entries.filter { it.available(horde) })

        // Suggested models exist for all new cloud backends
        for (proto in listOf(
            ImageProtocol.POLLINATIONS, ImageProtocol.STABILITY, ImageProtocol.TOGETHER,
            ImageProtocol.IMAGEN, ImageProtocol.XAI, ImageProtocol.NANOGPT, ImageProtocol.HUGGINGFACE,
        )) {
            assertTrue("Expected suggested models for $proto", suggestedImageModels(proto).isNotEmpty())
        }
    }

    @Test
    fun pollinationsGeneratesViaGetWithParametersAndOptionalAuth() = runBlocking {
        val calls = CopyOnWriteArrayList<RecordedCall>()
        val (server, origin) = startServer { call ->
            calls += call
            MockReply("image/png", samplePng)
        }
        try {
            val provider = Provider("p1", "Pollinations", origin, apiKey = "poll-key", defaultModel = "flux")
            val req = ImageRequest(
                provider = provider,
                protocol = ImageProtocol.POLLINATIONS,
                model = "flux-anime",
                shape = ImageShape.PORTRAIT,
                prompt = "moonlit shrine",
                tuning = ImageTuning(negative = "blurry", width = 768, height = 1024, seed = 42L),
            )
            val result = ImageService().generate(req) { null }
            assertArrayEquals(samplePng, result)
            val call = calls.single()
            assertEquals("GET", call.method)
            assertTrue(call.path.startsWith("/prompt/"))
            assertTrue(call.query.orEmpty().contains("model=flux-anime"))
            assertTrue(call.query.orEmpty().contains("width=768"))
            assertTrue(call.query.orEmpty().contains("height=1024"))
            assertTrue(call.query.orEmpty().contains("seed=42"))
            assertTrue(call.query.orEmpty().contains("nologo=true"))
            assertEquals("Bearer poll-key", call.auth)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun stabilitySupportsBothV2BetaMultipartAndV1Json() = runBlocking {
        val calls = CopyOnWriteArrayList<RecordedCall>()
        val (server, origin) = startServer { call ->
            calls += call
            if (call.path.startsWith("/v2beta/")) {
                MockReply("image/png", samplePng)
            } else {
                val json = jsonOf("artifacts" to JSONArray().put(jsonOf("base64" to samplePngB64)))
                MockReply("application/json", json.toString().encodeToByteArray())
            }
        }
        try {
            val provider = Provider("st", "Stability", origin, apiKey = "sk-stab")
            // v2beta sd3.5
            val v2Req = ImageRequest(
                provider = provider,
                protocol = ImageProtocol.STABILITY,
                model = "sd3.5-large",
                shape = ImageShape.LANDSCAPE,
                prompt = "crystal forest",
                tuning = ImageTuning(negative = "lowres", seed = 7L, cfg = 6.5),
            )
            assertArrayEquals(samplePng, ImageService().generate(v2Req) { null })
            assertEquals("/v2beta/stable-image/generate/sd3", calls[0].path)
            assertEquals("Bearer sk-stab", calls[0].auth)
            assertTrue(calls[0].contentType.orEmpty().startsWith("multipart/form-data; boundary="))
            assertTrue(calls[0].bodyText.contains("name=\"prompt\""))
            assertTrue(calls[0].bodyText.contains("crystal forest"))
            assertTrue(calls[0].bodyText.contains("name=\"negative_prompt\""))
            assertTrue(calls[0].bodyText.contains("name=\"model\""))

            // v1 engine
            val v1Req = v2Req.copy(model = "stable-diffusion-xl-1024-v1-0", tuning = ImageTuning(steps = 30, cfg = 7.0))
            assertArrayEquals(samplePng, ImageService().generate(v1Req) { null })
            assertEquals("/v1/generation/stable-diffusion-xl-1024-v1-0/text-to-image", calls[1].path)
            assertEquals(30, calls[1].jsonBody!!.getInt("steps"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun drawThingsAndSdNextSendProperPayloadsAndSupportImg2ImgAndAuth() = runBlocking {
        val calls = CopyOnWriteArrayList<RecordedCall>()
        val (server, origin) = startServer { call ->
            calls += call
            val json = jsonOf("images" to JSONArray().put(samplePngB64))
            MockReply("application/json", json.toString().encodeToByteArray())
        }
        try {
            // Draw Things txt2img
            val dtProvider = Provider("dt", "Draw Things", origin)
            val dtReq = ImageRequest(
                provider = dtProvider,
                protocol = ImageProtocol.DRAW_THINGS,
                model = "juggernaut_xl",
                shape = ImageShape.SQUARE,
                prompt = "cyberpunk alley",
                tuning = ImageTuning(steps = 20, cfg = 5.5, sampler = "DPM++ 2M", seed = 99L),
            )
            assertArrayEquals(samplePng, ImageService().generate(dtReq) { null })
            assertEquals("/sdapi/v1/txt2img", calls[0].path)
            assertEquals(5.5, calls[0].jsonBody!!.getDouble("guidance_scale"), 0.01)
            assertEquals("juggernaut_xl", calls[0].jsonBody!!.getString("model"))

            // Draw Things img2img when reference attached
            val refImage = ChatImage("0".repeat(64), "ref.png", ImageFormat.PNG)
            assertArrayEquals(samplePng, ImageService().generate(dtReq.copy(references = listOf(refImage))) { samplePng })
            assertEquals("/sdapi/v1/img2img", calls[1].path)
            assertEquals(samplePngB64, calls[1].jsonBody!!.getJSONArray("init_images").getString(0))

            // SD.Next with Basic auth & override_settings
            val sdnProvider = Provider("sdn", "SD.Next", origin, apiKey = "admin:pass123")
            val sdnReq = ImageRequest(
                provider = sdnProvider,
                protocol = ImageProtocol.SD_NEXT,
                model = "flux_dev.safetensors",
                shape = ImageShape.PORTRAIT,
                prompt = "stained glass window",
                tuning = ImageTuning(steps = 25, cfg = 4.0, sampler = "Euler", scheduler = "Karras", vae = "ae.safetensors", skip = 2),
            )
            assertArrayEquals(samplePng, ImageService().generate(sdnReq) { null })
            assertEquals("/sdapi/v1/txt2img", calls[2].path)
            assertTrue(calls[2].auth.orEmpty().startsWith("Basic "))
            val body = calls[2].jsonBody!!
            assertEquals("Euler", body.getString("sampler_name"))
            assertEquals("Karras", body.getString("scheduler"))
            val overrides = body.getJSONObject("override_settings")
            assertEquals("flux_dev.safetensors", overrides.getString("sd_model_checkpoint"))
            assertEquals("ae.safetensors", overrides.getString("sd_vae"))
            assertEquals(2, overrides.getInt("CLIP_stop_at_last_layers"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun togetherImagenXAiNanoGptAndHuggingFaceWorkEndToEnd() = runBlocking {
        val calls = CopyOnWriteArrayList<RecordedCall>()
        val (server, origin) = startServer { call ->
            calls += call
            when {
                call.path == "/v1/images/generations" -> MockReply(
                    "application/json",
                    jsonOf("data" to JSONArray().put(jsonOf("b64_json" to samplePngB64))).toString().encodeToByteArray(),
                )
                call.path.endsWith(":predict") -> MockReply(
                    "application/json",
                    jsonOf("predictions" to JSONArray().put(jsonOf("bytesBase64Encoded" to samplePngB64))).toString().encodeToByteArray(),
                )
                call.path == "/api/generate-image" -> MockReply(
                    "application/json",
                    jsonOf("image" to samplePngB64).toString().encodeToByteArray(),
                )
                call.path.startsWith("/hf-inference/") -> MockReply("image/png", samplePng)
                else -> MockReply("text/plain", "not found".encodeToByteArray(), 404)
            }
        }
        try {
            // Together AI
            val together = Provider("tog", "Together", "$origin/v1", apiKey = "tog-key")
            assertArrayEquals(
                samplePng,
                ImageService().generate(
                    ImageRequest(together, ImageProtocol.TOGETHER, "black-forest-labs/FLUX.1-schnell-Free", ImageShape.SQUARE, "aurora"),
                ) { null },
            )
            assertEquals("Bearer tog-key", calls[0].auth)
            assertEquals("b64_json", calls[0].jsonBody!!.getString("response_format"))

            // Imagen (Gemini API key style)
            val imagen = Provider("img", "Google", "$origin/v1beta/openai", apiKey = "goog-key", template = ProviderTemplate.GOOGLE)
            assertArrayEquals(
                samplePng,
                ImageService().generate(
                    ImageRequest(imagen, ImageProtocol.IMAGEN, "imagen-3.0-generate-002", ImageShape.PORTRAIT, "mountain temple", tuning = ImageTuning(negative = "fog")),
                ) { null },
            )
            assertEquals("/v1beta/models/imagen-3.0-generate-002:predict", calls[1].path)
            assertEquals("goog-key", calls[1].googKey)
            assertEquals("3:4", calls[1].jsonBody!!.getJSONObject("parameters").getString("aspectRatio"))
            assertEquals("fog", calls[1].jsonBody!!.getJSONObject("parameters").getString("negativePrompt"))

            // Vertex AI request builder check
            val vertexProvider = Provider(
                "vtx",
                "Vertex",
                "https://us-central1-aiplatform.googleapis.com/v1/projects/p/locations/us-central1",
                apiKey = "ya29.token",
            )
            val (vUrl, _, vHeaders) = ExtendedImageBackends.buildImagenRequest(
                ImageRequest(vertexProvider, ImageProtocol.IMAGEN, "imagen-3.0-generate-002", ImageShape.LANDSCAPE, "sea"),
            )
            assertTrue(vUrl.endsWith("/publishers/google/models/imagen-3.0-generate-002:predict"))
            assertEquals("Bearer ya29.token", vHeaders["Authorization"])

            // xAI
            val xai = Provider("xai", "xAI", "$origin/v1", apiKey = "xai-key")
            assertArrayEquals(
                samplePng,
                ImageService().generate(ImageRequest(xai, ImageProtocol.XAI, "grok-2-image", ImageShape.SQUARE, "spaceship")) { null },
            )
            assertEquals("Bearer xai-key", calls[2].auth)
            assertEquals("grok-2-image", calls[2].jsonBody!!.getString("model"))

            // NanoGPT
            val nano = Provider("nano", "NanoGPT", "$origin/api", apiKey = "nano-key")
            assertArrayEquals(
                samplePng,
                ImageService().generate(
                    ImageRequest(nano, ImageProtocol.NANOGPT, "flux-pro", ImageShape.SQUARE, "lanterns", tuning = ImageTuning(steps = 18, cfg = 4.5)),
                ) { null },
            )
            assertEquals("nano-key", calls[3].xApiKey)
            assertEquals(18, calls[3].jsonBody!!.getInt("steps"))
            assertEquals(4.5, calls[3].jsonBody!!.getDouble("scale"), 0.01)

            // HuggingFace Inference
            val hf = Provider("hf", "HuggingFace", "$origin/hf-inference", apiKey = "hf_token")
            assertArrayEquals(
                samplePng,
                ImageService().generate(
                    ImageRequest(hf, ImageProtocol.HUGGINGFACE, "black-forest-labs/FLUX.1-schnell", ImageShape.SQUARE, "watercolor fox", tuning = ImageTuning(steps = 4)),
                ) { null },
            )
            assertEquals("/hf-inference/black-forest-labs/FLUX.1-schnell", calls[4].path)
            assertEquals("Bearer hf_token", calls[4].auth)
            assertEquals("watercolor fox", calls[4].jsonBody!!.getString("inputs"))
            assertEquals(4, calls[4].jsonBody!!.getJSONObject("parameters").getInt("num_inference_steps"))
        } finally {
            server.stop(0)
        }
    }
}
