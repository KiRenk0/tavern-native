package app.tavern.nativeclient

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.random.Random

class GroupFeaturesTest {
    @Test
    fun groupAvatarUsesAnOriginalCompatibleDataUrlAndRoundTripsWithMembers() {
        val snapshot = ByteArrayOutputStream().use {
            assertTrue(ImageIO.write(BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB), "jpeg", it))
            it.toByteArray()
        }
        val url = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(snapshot)
        val avatar = requireNotNull(GroupAvatar.fromDataUrl(url))
        val saved = TavernGroup(name = "Moon Inn", memberIds = listOf("a", "b"), mutedIds = setOf("b"), avatar = avatar)
        val json = saved.json()
        assertEquals(url, json.getString("avatar_url"))
        assertEquals(saved, TavernGroup.from(saved.id, json.toString()))
        assertArrayEquals(snapshot, originalGroupAvatarData(url))
        json.remove("avatar_url")
        assertNull(TavernGroup.from(saved.id, json.toString()).avatar)
        assertEquals("", saved.copy(avatar = null).json().getString("avatar_url"))
        assertNull(GroupAvatar.fromDataUrl("data:image/jpeg;base64,abc!"))
        assertNull(GroupAvatar.fromDataUrl("data:image/png;base64," + Base64.getEncoder().encodeToString(snapshot)))
    }

    @Test
    fun avatarSourcesAreLimitedToOriginalLocalImagesAndCropStaysWithinTheImage() {
        assertEquals("user/images/42/Moon Inn.jpg", originalGroupAvatarPath("/user/images/42/Moon%20Inn.jpg"))
        assertNull(originalGroupAvatarPath("https://example.org/user/images/42/icon.jpg"))
        assertNull(originalGroupAvatarPath("//example.org/user/images/42/icon.jpg"))
        assertNull(originalGroupAvatarPath("user/images/%2E%2E/settings.jpg"))
        assertNull(originalGroupAvatarPath("user/images/42/group.json"))
        val crop = GroupAvatarCrop()
        assertEquals(GroupAvatarSquare(150, 0, 600), crop.square(900, 600))
        val zoom = crop.move(900, 600, 0f, 0f, 2f)
        assertEquals(GroupAvatarSquare(300, 150, 300), zoom.square(900, 600))
        assertEquals(GroupAvatarSquare(0, 300, 300), zoom.move(900, 600, 3f, -3f, 1f).square(900, 600))
        assertEquals(GroupAvatarSquare(0, 0, 1), crop.move(1, 1, 0f, 0f, 4f).square(1, 1))
    }

    @Test
    fun newGroupChatUsesCardGreetingsInMemberOrderWithGroupMacrosAndVariables() {
        val rin = Card("rin", jsonOf("name" to "Rin", "first_mes" to
            "{{setvar::place::inn}}Hi {{user}}, we are {{group}}.").toString())
        val mio = Card("mio", jsonOf("name" to "Mio", "alternate_greetings" to
            JSONArray(listOf("{{char}} at the {{getvar::place}}. Active: {{groupNotMuted}}."))).toString())
        val empty = Card("silent", jsonOf("name" to "Silent").toString())
        val group = TavernGroup(name = "Group", memberIds = listOf("rin", "missing", "mio", "silent"), mutedIds = setOf("mio"))
        val persona = PersonaProfile(id = "traveler", name = "Traveler")
        val prefs = Preferences(personas = listOf(persona), defaultPersonaId = persona.id)
        val chat = Chat("session", null, "Group", groupId = group.id)
        val (messages, changes) = group.openingMessages(chat, listOf(mio, empty, rin), prefs, null, Random(7))
        assertEquals(listOf("rin", "mio"), messages.map(Message::characterId))
        assertEquals("Hi Traveler, we are Rin, Mio, Silent.", messages[0].text)
        assertEquals("Mio at the inn. Active: Rin, Silent.", messages[1].text)
        assertEquals(mapOf("place" to "inn"), changes.local)
        assertTrue(messages.all { it.role == "assistant" && it.groupTurnId != null })
        assertNotEquals(messages[0].groupTurnId, messages[1].groupTurnId)
    }
}
