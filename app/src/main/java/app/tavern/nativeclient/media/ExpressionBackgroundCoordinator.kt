package app.tavern.nativeclient.media

import android.net.Uri
import app.tavern.nativeclient.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.io.InputStream

internal class ExpressionBackgroundCoordinator(
    private val host: CoordinatorHost,
    private val closeGallery: () -> Unit,
) {
    private val expressionCache = linkedMapOf<ExpressionInference, String>()

    suspend fun expressionSprites(card: Card): List<ExpressionSprite> = withContext(Dispatchers.IO) {
        val saved = card.expressionSprites ?: host.store.gallery(card.id, null).mapNotNull { item ->
            val label = item.prompt.takeIf { it.startsWith("表情 · ") }?.removePrefix("表情 · ")?.let(::spriteLabel)
            label?.let { ExpressionSprite(it, item.image) }
        }
        saved.distinctBy { it.label to it.image.id }.take(MAX_EXPRESSION_SPRITES).filter { host.imageStore.file(it.image).isFile }
    }

    suspend fun expressionFrame(chatId: String, subject: ExpressionSubject, forScene: Boolean = false): ExpressionFrame? {
        val snapshot = host.current
        val chat = snapshot.chat?.takeIf { it.id == chatId } ?: return null
        val settings = chat.options.expressions
        if (settings.mode == ExpressionMode.OFF && !forScene) return null
        val sprites = expressionSprites(subject.card)
        val seed = (subject.reply?.date ?: chatId).hashCode() + (subject.reply?.index ?: 0)
        if (settings.mode == ExpressionMode.OFF) return chooseExpression(sprites, null, "neutral", seed)
        if (sprites.isEmpty() && settings.fallback != EXPRESSION_EMOJI) return null
        val manual = snapshot.manualExpressions[subject.card.id]?.takeIf { it.chatId == chatId && it.reply == subject.reply }
        val reply = subject.reply?.text
        val connection = snapshot.prefs.connectionFor(chat)
        val label = manual?.label ?: when {
            settings.mode == ExpressionMode.LOCAL && reply != null && snapshot.localExpressionInstalled -> inferLocalExpression(reply)
            settings.mode == ExpressionMode.MAIN_API && reply != null && connection != null -> {
                val labels = (sprites.map(ExpressionSprite::label) + if (settings.fallback == EXPRESSION_EMOJI) expressionEmoji.keys else emptySet())
                    .distinct().take(MAX_EXPRESSION_SPRITES)
                inferExpression(connection, settings, labels, reply)
            }
            else -> null
        }
        return chooseExpression(sprites, label, settings.fallback, seed, manual?.imageId)
    }

    private suspend fun inferExpression(connection: ModelConnection, settings: ExpressionSettings, labels: List<String>, reply: String): String? {
        val text = reply.takeLast(3000)
        val key = ExpressionInference(connection.provider.id, connection.model, settings.instruction, labels, text)
        return cachedExpression(key) {
            val answer = try {
                withTimeoutOrNull(18_000) { ModelService.utility(connection, expressionPrompt(settings, labels, text), null) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            answer?.let { expressionAnswer(it, labels) }
        }
    }

    private suspend fun inferLocalExpression(reply: String): String? {
        val text = reply.takeLast(3000)
        val key = ExpressionInference("", EXPRESSION_MODEL_URL, "", emptyList(), text)
        return cachedExpression(key) {
            try {
                withContext(Dispatchers.Default) { host.localExpressions.classify(text) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                host.mutable.update { it.copy(localExpressionFailure = "本地表情模型读取失败，可以重新下载或导入模型。") }
                null
            } catch (_: LinkageError) {
                host.mutable.update { it.copy(localExpressionFailure = "设备无法加载本地表情运行库。") }
                null
            }
        }
    }

    private suspend fun cachedExpression(key: ExpressionInference, classify: suspend () -> String?): String? {
        synchronized(expressionCache) { expressionCache[key] }?.let { return it }
        val label = classify() ?: return null
        synchronized(expressionCache) {
            if (expressionCache.size >= 128) expressionCache.remove(expressionCache.keys.first())
            expressionCache[key] = label
        }
        return label
    }

    fun downloadExpressionModel() = host.utilityWork("正在下载本地表情模型…") {
        installExpressionModel { install ->
            request(
                EXPRESSION_MODEL_URL,
                headers = mapOf("Accept" to "application/octet-stream"),
                readTimeout = 45_000,
                followRedirects = true,
            ) { input, _ -> install(input) }
        }
    }

    fun importExpressionModel(uri: Uri) = host.utilityWork("正在导入本地表情模型…") {
        installExpressionModel { install ->
            withContext(Dispatchers.IO) {
                requireNotNull(host.resolver.openInputStream(uri)) { "无法打开模型文件" }.use(install)
            }
        }
    }

    private suspend fun installExpressionModel(read: suspend ((InputStream) -> Unit) -> Unit) {
        val context = currentCoroutineContext()
        host.mutable.update { it.copy(localExpressionProgress = 0, localExpressionFailure = null) }
        var lastPercent = 0
        try {
            read { input ->
                host.localExpressions.install(input) { received ->
                    context.ensureActive()
                    val percent = (received * 100 / EXPRESSION_MODEL_BYTES).toInt()
                    if (lastPercent != percent) {
                        lastPercent = percent
                        host.mutable.update { it.copy(localExpressionProgress = percent) }
                    }
                }
            }
            host.notice("本地表情模型已就绪")
        } finally {
            host.mutable.update { it.copy(localExpressionProgress = null, localExpressionInstalled = host.localExpressions.installed()) }
        }
    }

    fun removeExpressionModel() = host.work {
        withContext(Dispatchers.IO) { host.localExpressions.remove() }
        host.mutable.update { it.copy(localExpressionInstalled = false, localExpressionFailure = null) }
        host.notice("已移除本地表情模型")
    }

    fun saveExpressionSettings(settings: ExpressionSettings) = host.work {
        val chat = host.current.chat ?: return@work
        host.persist(chat.copy(options = chat.options.copy(expressions = settings.copy(instruction = settings.instruction.trim().take(2000)))))
    }

    fun importExpressionSprites(cardId: String, files: List<Uri>, pack: Boolean, label: String) = host.work {
        if (files.isEmpty()) return@work
        val card = requireNotNull(host.current.cards.firstOrNull { it.id == cardId }) { "找不到角色" }
        val explicit = if (label.isBlank() || pack) null else requireNotNull(expressionLabel(label)) { "表情标签无效" }
        val pictures = withContext(Dispatchers.IO) {
            files.distinct().flatMap { uri ->
                currentCoroutineContext().ensureActive()
                requireNotNull(host.resolver.openInputStream(uri)) { "无法打开表情图片" }.use { input ->
                    if (pack) readSpritePack(input, host.imageStore::import) else {
                        val name = host.app.documentName(uri).takeUnless { it == "导入的聊天" } ?: "neutral"
                        val key = explicit ?: requireNotNull(spriteLabel(name)) { "表情文件名无效" }
                        listOf(ExpressionSprite(key, host.imageStore.import(input.readLimited(MAX_IMAGE_SOURCE), name)))
                    }
                }
            }
        }
        val merged = (expressionSprites(card) + pictures).distinctBy { it.label to it.image.id }
        require(merged.size <= MAX_EXPRESSION_SPRITES) { "每个角色最多保存 $MAX_EXPRESSION_SPRITES 张表情" }
        saveExpressionSprites(card, merged)
        host.notice("已导入 ${pictures.size} 张表情")
    }

    fun removeExpressionSprite(cardId: String, sprite: ExpressionSprite) = host.work {
        val card = host.current.cards.firstOrNull { it.id == cardId } ?: return@work
        saveExpressionSprites(card, expressionSprites(card).filterNot { it == sprite })
    }

    private suspend fun saveExpressionSprites(card: Card, sprites: List<ExpressionSprite>) {
        val cards = withContext(Dispatchers.IO) { host.store.save(card.withExpressionSprites(sprites)); host.store.cards() }
        host.mutable.update { it.copy(cards = cards) }
    }

    fun showExpression(cardId: String, sprite: ExpressionSprite, done: () -> Unit = {}) = host.work {
        setManualExpression(cardId, sprite.label, sprite.image.id)
        done()
    }

    private suspend fun setManualExpression(cardId: String, label: String, imageId: String? = null): String {
        val chat = requireNotNull(host.current.chat) { "请先打开对话" }
        val subject = requireNotNull(host.current.expressionSubjects(chat).firstOrNull { it.card.id == cardId }) { "该角色不在对话中" }
        val settings = chat.options.expressions
        check(
            expressionSprites(subject.card).any { it.label == label && (imageId == null || it.image.id == imageId) } ||
                (settings.fallback == EXPRESSION_EMOJI && label in expressionEmoji),
        ) { "找不到表情 $label" }
        if (settings.mode == ExpressionMode.OFF) host.persist(chat.copy(options = chat.options.copy(expressions = settings.copy(mode = ExpressionMode.MANUAL))))
        host.mutable.update { state ->
            val choices = state.manualExpressions.filterValues { it.chatId == chat.id }
            state.copy(manualExpressions = choices + (cardId to ManualExpression(chat.id, subject.reply, label, imageId)))
        }
        return label
    }

    internal suspend fun scriptExpression(command: String, named: Map<String, String>, input: String): String {
        val chat = requireNotNull(host.current.chat) { "请先打开对话" }
        val subjects = host.current.expressionSubjects(chat)
        val requested = named["name"]
        val subject = if (requested == null) host.current.regexCard(chat.messages.lastOrNull { it.role == "assistant" && !it.hidden })
            ?.let { actor -> subjects.firstOrNull { it.card.id == actor.id } }
        else subjects.singleOrNull { it.card.name.equals(requested, true) || it.card.id == requested }
        val card = requireNotNull(subject) { "找不到表情对应的角色" }.card
        if (command == "list") return JSONArray(expressionSprites(card).map(ExpressionSprite::label).distinct()).toString()
        return setManualExpression(card.id, requireNotNull(expressionLabel(input)) { "请填写表情标签" })
    }

    fun clearDrawingBackground(chatId: String) = host.work {
        val chat = host.current.chat?.takeIf { it.id == chatId } ?: return@work
        host.persist(chat.copy(options = chat.options.copy(background = null)))
    }

    fun setDrawingBackground(chatId: String, image: ChatImage) = host.work {
        val chat = host.current.chat?.takeIf { it.id == chatId } ?: return@work
        require(backgroundFile(image).isFile) { "找不到背景图片" }
        host.persist(chat.copy(options = chat.options.copy(background = image)))
        closeGallery()
        host.notice("已应用对话背景")
    }

    fun importChatBackground(chatId: String, uri: Uri) = host.work {
        val chat = host.current.chat?.takeIf { it.id == chatId } ?: return@work
        host.generation.stopAuto()
        val image = withContext(Dispatchers.IO) {
            val name = host.app.documentName(uri).takeUnless { it == "导入的聊天" } ?: "对话背景"
            requireNotNull(host.resolver.openInputStream(uri)) { "无法打开背景图片" }.use {
                host.imageStore.import(it.readLimited(MAX_IMAGE_SOURCE), name)
            }
        }
        host.persist(chat.copy(options = chat.options.copy(background = image)))
        host.notice("已应用对话背景")
    }

    fun backgroundFile(image: ChatImage) = host.imageStore.file(image).takeIf { it.isFile } ?: host.savedImages.file(image)

    fun importBackgrounds(uris: List<Uri>, folder: String = "") = host.utilityWork("正在导入背景图片…") {
        val targetFolder = normalizeBackgroundFolder(folder)
        val (added, failures) = withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            var failures = 0
            val entries = uris.distinct().mapNotNull { uri ->
                context.ensureActive()
                try {
                    val name = host.app.documentName(uri).takeUnless { it == "导入的聊天" } ?: "背景图片"
                    val image = requireNotNull(host.resolver.openInputStream(uri)) { "无法打开背景图片" }.use {
                        host.savedImages.import(it.readLimited(MAX_IMAGE_SOURCE), name)
                    }
                    BackgroundEntry(image, folder = targetFolder)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    failures++
                    null
                }
            }
            entries to failures
        }
        val prefs = host.current.prefs
        val library = prefs.backgrounds.add(added)
        if (library != prefs.backgrounds) host.persistPreferences(prefs.copy(backgrounds = library))
        val failed = if (failures == 0) "" else "，$failures 张无法读取"
        host.notice("已加入 ${library.items.size - prefs.backgrounds.items.size} 张背景$failed")
    }

    fun addLibraryBackground(image: ChatImage) = host.work {
        val prefs = host.current.prefs
        val existing = prefs.backgrounds.items.any { it.image.id == image.id }
        if (existing) return@work
        withContext(Dispatchers.IO) {
            if (!host.savedImages.file(image).isFile) {
                val bytes = requireNotNull(host.imageStore.bytes(image)) { "找不到背景图片" }
                host.savedImages.restore(image, bytes)
            }
        }
        host.persistPreferences(prefs.copy(backgrounds = prefs.backgrounds.add(listOf(BackgroundEntry(image)))))
        host.notice("已加入背景库")
    }

    fun selectLibraryBackground(id: String?) = changeBackgroundLibrary {
        require(id == null || items.any { it.image.id == id }) { "找不到这张背景" }
        copy(selectedId = id)
    }

    fun renameLibraryBackground(id: String, name: String) = changeBackgroundLibrary { renamed(id, name) }

    fun removeLibraryBackground(id: String) = changeBackgroundLibrary { remove(id) }

    fun setBackgroundFit(fit: BackgroundFit) = changeBackgroundLibrary { copy(fit = fit) }

    fun createBackgroundFolder(path: String) = changeBackgroundLibrary { createFolder(path) }

    fun moveLibraryBackground(id: String, folder: String) = changeBackgroundLibrary { moveItem(id, folder) }

    fun renameBackgroundFolder(oldPath: String, newName: String) = changeBackgroundLibrary { renameFolder(oldPath, newName) }

    fun deleteBackgroundFolder(path: String) = changeBackgroundLibrary { deleteFolder(path) }

    private fun changeBackgroundLibrary(update: BackgroundLibrary.() -> BackgroundLibrary) = host.work {
        val prefs = host.current.prefs
        val library = prefs.backgrounds.update()
        if (library != prefs.backgrounds) host.persistPreferences(prefs.copy(backgrounds = library))
    }
}
