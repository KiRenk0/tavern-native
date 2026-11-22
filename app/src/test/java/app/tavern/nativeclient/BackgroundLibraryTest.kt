package app.tavern.nativeclient

import app.tavern.nativeclient.script.ScriptExtensions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackgroundLibraryTest {
    private val harbor = ChatImage("a".repeat(64), "夜间港口.jpg", ImageFormat.JPEG)
    private val castle = ChatImage("b".repeat(64), "城堡.jpg", ImageFormat.JPEG)
    private val gallery = ChatImage("c".repeat(64), "花园.png", ImageFormat.PNG)
    private val library = BackgroundLibrary(listOf(BackgroundEntry(harbor, 1), BackgroundEntry(castle, 2)),
        selectedId = harbor.id, fit = BackgroundFit.CONTAIN)

    @Test
    fun libraryRoundTripAndChatLock() {
        assertEquals(library, BackgroundLibrary.from(library.json()))
        assertEquals(harbor, library.effective(null))
        val solo = Chat("solo", "character", "单聊", options = ConversationOptions(background = castle))
        val group = Chat("group", null, "群聊", groupId = "party")
        assertEquals(castle, library.effective(solo))
        assertEquals(harbor, library.effective(group))
        val removed = library.remove(castle.id)
        assertEquals(castle, removed.effective(solo))
        assertNull(removed.remove(harbor.id).selected)
    }

    @Test
    fun searchesAndRenamesContentAddressedEntries() {
        val updated = library.add(listOf(BackgroundEntry(harbor.copy(name = "重复"), 8), BackgroundEntry(gallery, 9)))
        assertEquals(3, updated.items.size)
        assertEquals("夜间港口.jpg", updated.find(harbor.filename)?.title)
        assertEquals(harbor, updated.find("港口")?.image)
        assertEquals(harbor, updated.find("夜间港口")?.image)
        assertEquals(castle, updated.renamed(castle.id, "  新城堡  ").find("新城")?.image?.copy(name = castle.name))
        assertNull(updated.find("找不到"))
    }

    @Test
    fun scriptSwitchesGlobalOrLockedBackgroundAndRetainsGalleryFallback() = runBlocking {
        var state = TavernState(chat = Chat("solo", "character", "单聊"), prefs = Preferences(backgrounds = library))
        suspend fun run(command: String, text: String = "") = ScriptExtensions.execute(command, emptyMap(), text, state,
            saveChat = { state = state.copy(chat = it) },
            savePrefs = { state = state.copy(prefs = it) },
            galleryImages = { listOf(GalleryImage(gallery, "一座花园")) })
        assertEquals(harbor.filename, run("bg"))
        assertEquals(castle.filename, run("bg", "城堡"))
        assertNull(state.chat?.options?.background)
        assertEquals(castle.id, state.prefs.backgrounds.selectedId)
        assertEquals(castle.filename, run("lockbg"))
        assertEquals(harbor.filename, run("bg", "港口"))
        assertEquals(harbor, state.chat?.options?.background)
        assertEquals(castle.id, state.prefs.backgrounds.selectedId)
        assertEquals("", run("unlockbg"))
        assertEquals(castle.filename, run("bg"))
        assertEquals(gallery.filename, run("bg", "花园"))
        assertEquals(gallery, state.chat?.options?.background)
        assertEquals("", run("bg", "clear"))
        assertNull(state.chat?.options?.background)
    }

    @Test
    fun findsAProfileContainingOnlyBackgroundImages() {
        val directory = Files.createTempDirectory("tavern-background-backup").toFile()
        try {
            val archive = File(directory, "backup.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("data/story/backgrounds/castle.jpg"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
            val result = archive.inputStream().use { input ->
                readOriginalBackup(input, directory, archive.name, {}) { account, source ->
                    assertEquals("story", account)
                    assertArrayEquals(byteArrayOf(1, 2, 3), source.files("backgrounds").single().bytes())
                }
            }
            assertEquals(1, result.size)
        } finally {
            directory.deleteRecursively()
        }
    }
}
