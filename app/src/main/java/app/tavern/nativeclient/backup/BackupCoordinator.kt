package app.tavern.nativeclient.backup

import android.net.Uri
import app.tavern.nativeclient.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File

internal class BackupCoordinator(private val host: CoordinatorHost) {
    private var server: TavernServer? = null

    fun importEmbedded() = host.work {
        val message = withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            host.embedded.import(host.store) { context.ensureActive() }
        }
        refreshImportedLibrary()
        host.notice(message)
    }

    fun importOriginalBackup(uri: Uri) = host.utilityWork("正在读取 SillyTavern 备份…") {
        val context = currentCoroutineContext()
        try {
            val reports = withContext(Dispatchers.IO) {
                val filename = host.app.documentName(uri)
                requireNotNull(host.resolver.openInputStream(uri)) { "无法打开备份 ZIP" }.use { input ->
                    readOriginalBackup(input, host.app.cacheDir, filename, { context.ensureActive() }) { account, source ->
                        host.mutable.update { it.copy(utility = "正在导入账户 $account…") }
                        EmbeddedLibrary(source, host.savedDocuments, host.savedImages, account).import(host.store) { context.ensureActive() }
                    }
                }
            }
            host.notice(reports.joinToString("\n"))
        } finally {
            refreshImportedLibrary()
        }
    }

    fun exportOriginalBackup(uri: Uri) = host.utilityWork("正在打包本机数据…") {
        val result = withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            val archive = File.createTempFile("tavern-backup-", ".zip", host.app.cacheDir)
            try {
                val exported = archive.outputStream().buffered().use { output ->
                    writeOriginalBackup(output, host.store, host.savedDocuments, host.savedImages) { context.ensureActive() }
                }
                context.ensureActive()
                withContext(NonCancellable) {
                    host.mutable.update { it.copy(utility = "正在保存备份 ZIP…") }
                    requireNotNull(host.resolver.openOutputStream(uri, "wt")) { "无法写入所选文件" }.use { output ->
                        archive.inputStream().buffered().use { it.copyTo(output) }
                    }
                }
                exported
            } finally {
                archive.delete()
            }
        }
        val missing = if (result.missing == 0) "" else "。${result.missing} 个本机附件已缺失"
        host.notice("已导出 ${result.cards} 个角色、${result.groups} 个群组、${result.chats} 段聊天和 ${result.books} 本世界书$missing")
    }

    private suspend fun refreshImportedLibrary() {
        val library = withContext(NonCancellable + Dispatchers.IO) {
            val prefs = host.store.prefs()
            TavernState(
                cards = host.store.cards(),
                groups = host.store.groups(),
                books = host.store.books(),
                prefs = prefs,
                presets = host.store.promptPresets(),
                recentChats = host.store.recentChats(prefs.welcome.pinnedIds),
                dataDocuments = host.store.dataDocuments(),
            )
        }
        host.mutable.update {
            it.copy(
                cards = library.cards,
                groups = library.groups,
                books = library.books,
                prefs = library.prefs,
                presets = library.presets,
                recentChats = library.recentChats,
                dataDocuments = library.dataDocuments,
            )
        }
    }

    fun listRemote(address: String, handle: String, password: String, basicUser: String, basicPassword: String) = host.work {
        val remote = TavernServer(address.trim(), handle.trim(), basicUser.trim(), basicPassword)
        val cards = remote.list(password)
        server = remote
        val prefs = host.current.prefs.copy(server = address.trim(), handle = handle.trim())
        host.persistPreferences(prefs)
        host.mutable.update { it.copy(remoteCards = cards, prefs = prefs) }
    }

    fun clearRemote() {
        server = null
        host.mutable.update { it.copy(remoteCards = null) }
    }

    fun importRemote(card: RemoteCard, withChats: Boolean) = host.work {
        val remote = requireNotNull(server) { "请重新连接 SillyTavern" }
        val (download, chats) = remote.download(card, withChats)
        val (cards, count, missing) = withContext(Dispatchers.IO) {
            val existing = host.store.chats(download.card.id).mapTo(mutableSetOf(), ChatSummary::id)
            val uploaded = mutableMapOf<String, ChatDocument?>()
            var failures = 0
            val hydrated = chats.filterNot { it.id in existing }.map { chat ->
                val (result, missing) = hydrateOriginalDocuments(chat) { file, title ->
                    val document = if (uploaded.containsKey(file)) uploaded[file] else {
                        try {
                            host.documentStore.import(ImportedDocument(title, decodeDocumentText(remote.documentFile(file))))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }.also { uploaded[file] = it }
                    }
                    document?.copy(name = title.take(180))
                }
                failures += missing
                result
            }
            val imported = host.store.import(download, hydrated)
            Triple(host.store.cards(), imported, failures)
        }
        host.mutable.update { it.copy(cards = cards) }
        host.notice("角色已导入 · 新增 $count 个聊天" + if (missing > 0) " · $missing 份文档无法读取" else "")
    }

    fun saveSyncSettings(settings: SyncSettings, done: () -> Unit = {}) = host.work {
        host.persistPreferences(host.current.prefs.copy(sync = settings))
        host.notice("同步配置已保存")
        done()
    }

    fun testWebDavConnection(settings: SyncSettings) = host.work {
        val message = RawWebDavClient.testConnection(settings)
        host.notice(message)
    }

    fun runWebDavSync(settings: SyncSettings) = host.utilityWork("正在执行 WebDAV 增量同步…") {
        val report = try {
            val activePrefs = host.current.prefs.copy(sync = settings)
            host.persistPreferences(activePrefs)
            val (updatedSettings, syncReport) = withContext(Dispatchers.IO) {
                SyncEngine.syncWebDav(host.app, host.store, activePrefs)
            }
            host.persistPreferences(host.current.prefs.copy(sync = updatedSettings))
            syncReport
        } finally {
            refreshImportedLibrary()
        }
        host.notice(report.summary)
    }

    fun syncLanServer(
        address: String,
        handle: String,
        password: String,
        basicUser: String,
        basicPass: String,
        includeChats: Boolean,
    ) = host.utilityWork("正在与 SillyTavern 双向同步…") {
        val report = try {
            withContext(Dispatchers.IO) {
                SyncEngine.syncLanTwoWay(
                    store = host.store,
                    prefs = host.current.prefs,
                    address = address.trim(),
                    handle = handle.trim(),
                    password = password,
                    basicUser = basicUser.trim(),
                    basicPass = basicPass,
                    includeChats = includeChats,
                )
            }
        } finally {
            refreshImportedLibrary()
        }
        host.notice(report.summary)
    }

    internal suspend fun hydrateOriginalDocuments(
        chat: Chat,
        destination: ChatDocumentStore = host.documentStore,
        fetch: suspend (String, String) -> ChatDocument? = { _, _ -> null },
    ): Pair<Chat, Int> {
        var failures = 0
        val messages = chat.messages.map { message ->
            val resolved = mutableMapOf<Int, ChatDocument>()
            for (reference in message.originalChatFiles()) {
                currentCoroutineContext().ensureActive()
                val attachment = try {
                    val inline = reference.inline
                    if (inline != null) destination.import(ImportedDocument(reference.name, inline))
                    else reference.file?.let { fetch(it, reference.name) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (attachment != null) resolved[reference.index] = attachment else failures++
            }
            message.withOriginalDocuments(resolved)
        }
        return chat.copy(messages = messages) to failures
    }
}
