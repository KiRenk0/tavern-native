package app.tavern.nativeclient

import java.io.ByteArrayOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal const val REPLY_PNG_FIXTURE =
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4L2AAAANRAUAX5QvAAAAAAElFTkSuQmCC"

class ReplyMediaTest {
    @Test
    fun `variant images and replay metadata survive switching and attachment ZIP transfer`() {
        val firstBytes = decodeGeneratedImage(REPLY_PNG_FIXTURE)
        val secondBytes = decodeGeneratedImage("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGMQSPgPAAHzAXA3eHo2AAAAAElFTkSuQmCC")
        val first = ChatImage(ChatImageStore.digest(firstBytes), "First", ImageFormat.PNG, originalReply = true)
        val second = ChatImage(ChatImageStore.digest(secondBytes), "Second", ImageFormat.PNG, originalReply = true)
        val replay = GeminiReplyReplay("google", "gemini-nano-banana-2.1",
            listOf(ReplyContentPart(imageKey = first.id, signature = "signature")))
        val reply = Message("assistant", "Rina", "", replyMedia = StoredReplyMedia(listOf(first), replay))
            .withAlternative("New picture", media = StoredReplyMedia(listOf(second)))
        val chat = Chat("test", "rina", "Images", messages = listOf(reply))
        val zip = ByteArrayOutputStream()
        ChatMediaArchive.write(zip, chat.jsonl("Rina", "Mio"), chat) { image ->
            if (image.id == first.id) firstBytes else secondBytes
        }
        val restoredImages = mutableSetOf<ChatImage>()
        val read = ChatMediaArchive.read(zip.toByteArray().inputStream(), { bytes ->
            importJsonLinesChat(bytes.decodeToString(), "Restored")
        }) { image, _ -> restoredImages += image }
        assertEquals(setOf(first, second), restoredImages)
        val restored = read.messages.single()
        assertEquals(listOf(second), restored.displayImages)
        val selected = restored.select(0)
        assertEquals(listOf(first), selected.displayImages)
        assertEquals(replay, selected.replyMedia?.gemini)
        assertNull(selected.withText("New text").replyMedia?.gemini)
    }

    @Test
    fun `image options follow original preset fields and do not request images from Hydrogen`() {
        val source = JSONObject("""{"prompts":[],"request_images":true,"request_image_aspect_ratio":"16:9","request_image_resolution":"2K"}""")
        val preset = PromptPreset.read(source, "Images")
        val options = preset.chatCompletion.images
        assertEquals(ReplyImageOptions(true, "16:9", "2K"), options)
        assertEquals(options, PromptPreset.read(preset.json(), "Images").chatCompletion.images)
        val provider = Provider("google", protocol = ApiProtocol.GEMINI_NATIVE)
        val ordinary = JSONObject()
        options.applyTo(ModelConnection(provider, "gemini-4-hydrogen"), ordinary)
        assertFalse(ordinary.has(REPLY_IMAGE_OPTIONS))
        val drawing = JSONObject()
        options.applyTo(ModelConnection(provider, "models/gemini-nano-banana-2.1"), drawing)
        assertTrue(drawing.has(REPLY_IMAGE_OPTIONS))
        assertEquals("2K", drawing.getJSONObject(REPLY_IMAGE_OPTIONS).getString("imageSize"))
    }
}
