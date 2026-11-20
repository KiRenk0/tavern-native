package app.tavern.nativeclient

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import app.tavern.nativeclient.backup.BackupCoordinator
import app.tavern.nativeclient.chat.ChatGenerationCoordinator
import app.tavern.nativeclient.chat.ChatManagementCoordinator
import app.tavern.nativeclient.library.LibraryCoordinator
import app.tavern.nativeclient.media.MediaCoordinator
import app.tavern.nativeclient.provider.ProviderCoordinator
import app.tavern.nativeclient.script.ScriptCoordinator
import app.tavern.nativeclient.world.WorldMemoryCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

internal interface CoordinatorHost {
    val app: Application
    val scope: CoroutineScope
    val store: TavernStore
    val resolver: ContentResolver
    val mutable: MutableStateFlow<TavernState>
    val current: TavernState
    val savedImages: ChatImageStore
    val savedDocuments: ChatDocumentStore
    var temporaryMedia: TemporaryChatMedia?
    val imageStore: ChatImageStore
    val documentStore: ChatDocumentStore
    val embedded: EmbeddedLibrary
    val imageService: ImageService
    val translationService: TranslationService
    val localExpressions: LocalExpressions
    val localEmbeddings: LocalEmbeddings
    val localCaptioner: LocalCaptioner
    val loreTokenizers: LoreTokenizers
    val embeddingService: EmbeddingService
    val vectorSearch: VectorSearch
    var draftInProgress: String?

    val media: MediaCoordinator
    val provider: ProviderCoordinator
    val library: LibraryCoordinator
    val world: WorldMemoryCoordinator
    val script: ScriptCoordinator
    val chats: ChatManagementCoordinator
    val generation: ChatGenerationCoordinator
    val backup: BackupCoordinator

    fun notice(text: String?)
    fun work(completed: () -> Unit = {}, action: suspend () -> Unit)
    fun utilityWork(label: String, action: suspend () -> Unit): Boolean
    fun utilityConnection(chat: Chat): ModelConnection
    fun cancelUtility()
    fun writeUri(uri: Uri, bytes: ByteArray)
    suspend fun persist(chat: Chat, drawing: GalleryImage? = null, scriptGlobals: Map<String, String>? = null)
    suspend fun persistPreferences(prefs: Preferences)
    suspend fun persistMacroVariables(chatId: String?, changes: MacroVariableChanges)
    suspend fun refreshRecentChats()
    suspend fun refreshLibraryUsage()
    suspend fun refreshDataBank()
    suspend fun refreshInstalledEmbeddingModels()
    fun clearDraftAttachments(chatId: String)
    fun discardTemporaryChat()
    fun showChat(chat: Chat, history: List<ChatSummary>, resetPersona: Boolean = false)
}
