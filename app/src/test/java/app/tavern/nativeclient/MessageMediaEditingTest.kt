package app.tavern.nativeclient

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageMediaEditingTest {
    private val first = ChatImage("a".repeat(64), "First", ImageFormat.PNG, originalReply = true)
    private val second = ChatImage("b".repeat(64), "Second", ImageFormat.PNG, originalReply = true)
    private val video = ChatVideo("c".repeat(64), "Clip.mp4", 512)

    @Test
    fun `removing current media clears its replay and retains assets on the other reply`() {
        val replay = GeminiReplyReplay("google", "gemini-nano-banana-2.1",
            listOf(ReplyContentPart(imageKey = second.id, signature = "signature")))
        val message = Message("assistant", "Rina", "First reply", replyMedia = StoredReplyMedia(listOf(first)))
            .withAlternative("Second reply", media = StoredReplyMedia(listOf(second), replay))
            .copy(videos = listOf(video), imageCaptions = mapOf(first.id to "One", second.id to "Two"),
                mediaDisplay = MessageMediaDisplay.GALLERY, mediaIndex = 1)

        val withoutImage = Message.from(message.withoutDisplayedMedia(second.filename).toJson())
        assertNull(withoutImage.replyMedia)
        assertTrue(withoutImage.displayImages.isEmpty())
        assertEquals(listOf(video), withoutImage.videos)
        assertEquals(0, withoutImage.selectedMediaIndex)
        assertEquals(listOf(first), withoutImage.select(0).displayImages)
        assertEquals(mapOf(first.id to "One"), withoutImage.imageCaptions)

        val cleared = Message.from(withoutImage.withoutDisplayedMedia().toJson())
        assertEquals(0, cleared.mediaCount)
        assertEquals(listOf(first), cleared.select(0).displayImages)
    }

    @Test
    fun `gallery selection and expanded actions round trip through original fields`() {
        val source = JSONObject("""{"mes":"Photos","extra":{"media_display":"gallery","media_index":1}}""")
        val message = Message.from(source).copy(images = listOf(first), videos = listOf(video))
        val restored = Message.from(message.toJson())
        assertEquals(MessageMediaDisplay.GALLERY, restored.mediaDisplay)
        assertEquals(1, restored.selectedMediaIndex)
        assertNull(Message.from(Message("user", "Mio", "").toJson()).mediaDisplay)

        val power = JSONObject().put("expand_message_actions", true)
        assertTrue(ChatAppearance.from(ChatAppearance.fromOriginal(power).json()).expandMessageActions)
        assertFalse(ChatAppearance.from(JSONObject()).expandMessageActions)
    }

    @Test
    fun `prompt images use the selected gallery item and honor a per-message list override`() {
        val message = Message("user", "Mio", "Attachments", images = listOf(first, second), videos = listOf(video), mediaIndex = 1)
        fun attached(value: Message) = promptImages(listOf(value), false, MessageMediaDisplay.GALLERY) { byteArrayOf(1) }
            .values.flatMap { it.keys }

        assertEquals(listOf(second.id), attached(message))
        assertEquals(listOf(first.id, second.id), attached(message.copy(mediaDisplay = MessageMediaDisplay.LIST)))
        assertTrue(attached(message.copy(mediaIndex = 2)).isEmpty())
    }
}
