package app.tavern.nativeclient.media

import android.net.Uri
import app.tavern.nativeclient.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

internal class MediaCaptionCoordinator(private val host: CoordinatorHost) {

    fun attachImages(chatId: String, documents: List<Uri>, ready: ((ChatImage) -> Unit)? = null, messageIndex: Int? = null) {
        if (host.current.chat?.id != chatId || documents.isEmpty()) return
        host.work {
            val chat = host.current.chat?.takeIf { it.id == chatId } ?: return@work
            val message = messageIndex?.let { chat.messages.getOrNull(it) ?: return@work }
            if (message != null) host.generation.stopAuto()
            val existing = message?.images ?: host.current.draftImages[chatId].orEmpty()
            val slots = (MAX_CHAT_IMAGES - existing.size).coerceAtLeast(0)
            var lastFailure: String? = null
            val imported = withContext(Dispatchers.IO) {
                documents.take(slots).mapNotNull { uri ->
                    val name = host.app.documentName(uri).takeUnless { it == "导入的聊天" } ?: "图片"
                    try {
                        importCaptionableImage(uri, name)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        lastFailure = "无法添加 $name · ${e.localizedMessage ?: "无法读取图片"}"
                        null
                    }
                }
            }
            val attachments = (existing + imported).distinctBy(ChatImage::id)
            if (message != null) {
                if (attachments != existing) host.chats.updateMessage(chat, messageIndex, message.copy(images = attachments, mediaIndex = 0))
            } else {
                host.mutable.update { it.copy(draftImages = it.draftImages + (chatId to attachments)) }
            }
            imported.singleOrNull()?.let { ready?.invoke(it) }
            lastFailure?.let(host::notice)
            if (lastFailure == null && documents.size > slots) host.notice("每条消息最多附带 $MAX_CHAT_IMAGES 张图片")
        }
    }

    fun removeDraftImage(chatId: String, image: ChatImage) {
        if (host.current.busy || host.current.generation != null || host.current.scriptRunning) return
        host.mutable.update { state ->
            val captions = state.draftImageCaptions[chatId].orEmpty() - image.id
            state.copy(
                draftImages = state.draftImages + (chatId to state.draftImages[chatId].orEmpty().filterNot { it.id == image.id }),
                draftImageCaptions = state.draftImageCaptions + (chatId to captions),
            )
        }
    }

    internal fun importCaptionableImage(uri: Uri, name: String): ChatImage {
        val bytes = requireNotNull(host.resolver.openInputStream(uri)) { "无法打开图片" }.use { it.readLimited(MAX_IMAGE_SOURCE) }
        return host.imageStore.import(bytes, name)
    }

    fun saveCaptionSettings(settings: CaptionSettings, done: () -> Unit) = host.work {
        host.persistPreferences(host.current.prefs.copy(captions = CaptionSettings.from(settings.json())))
        done()
    }

    fun generateImageCaption(image: ChatImage, prompt: String, done: (String) -> Unit) =
        host.utilityWork("正在生成图片描述…") {
            done(describeImage(image, prompt))
        }

    fun saveImageCaption(chatId: String, target: CaptionImageTarget, text: String, done: () -> Unit) = host.work {
        val chat = host.current.chat?.takeIf { it.id == chatId } ?: return@work
        fun updated(captions: Map<String, String>): Map<String, String> {
            val value = text.trim().take(MAX_IMAGE_CAPTION)
            return if (value.isEmpty()) captions - target.image.id else captions + (target.image.id to value)
        }
        val index = target.messageIndex
        if (index == null) {
            check(host.current.draftImages[chatId].orEmpty().any { it.id == target.image.id }) { "草稿中已没有这张图片" }
            host.mutable.update {
                it.copy(draftImageCaptions = it.draftImageCaptions + (chatId to updated(it.draftImageCaptions[chatId].orEmpty())))
            }
        } else {
            val message = chat.messages.getOrNull(index)
            check(message != null && message.displayImages.any { it.id == target.image.id }) { "消息中已没有这张图片" }
            val messages = chat.messages.toMutableList()
            messages[index] = message.copy(imageCaptions = updated(message.imageCaptions))
            host.persist(chat.copy(messages = messages, updated = System.currentTimeMillis()))
        }
        done()
    }

    private suspend fun describeImage(image: ChatImage, prompt: String, settings: CaptionSettings = host.current.prefs.captions): String {
        val bytes = withContext(Dispatchers.IO) { checkNotNull(host.imageStore.bytes(image)) { "找不到图片文件" } }
        return when (settings.source) {
            CaptionSource.LOCAL -> withContext(Dispatchers.Default) { host.localCaptioner.caption(bytes) }
            CaptionSource.HORDE -> {
                val provider = checkNotNull(settings.hordeProvider(host.current.prefs)) { "请先配置 AI Horde 提供商" }
                Horde(provider).caption(image.dataUrl(bytes))
            }
            CaptionSource.MULTIMODAL -> {
                val connection = checkNotNull(settings.connection(host.current.prefs, host.current.chat)) { "请先配置图片描述模型" }
                try {
                    captionMultimodal(connection, image, bytes, prompt.trim().take(MAX_IMAGE_CAPTION).ifBlank { settings.prompt })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error(e.localizedMessage.orEmpty().redactValue(connection.provider.apiKey).ifBlank { "无法生成图片描述" })
                }
            }
        }
    }

    internal suspend fun askImageDescription(image: ChatImage, prompt: String = "", options: CaptionSettings = host.current.prefs.captions): String? {
        val instruction = if (options.source == CaptionSource.MULTIMODAL && options.ask && prompt.isBlank()) {
            host.script.requestScriptInput(ScriptDialog(kind = "input", title = "图片描述提示词", text = image.name, initial = options.prompt))
                ?: return null
        } else prompt
        val result = describeImage(image, instruction, options)
        val approved = if (options.refine) {
            host.script.requestScriptInput(ScriptDialog(kind = "input", title = "确认图片描述", text = image.name, initial = result))
        } else result
        return approved?.trim()?.take(MAX_IMAGE_CAPTION)?.takeIf(String::isNotBlank)
    }

    internal suspend fun prepareImageCaptions(chatId: String, images: List<ChatImage>): Map<String, String> {
        val prepared = host.current.draftImageCaptions[chatId].orEmpty().filterKeys { id -> images.any { it.id == id } }.toMutableMap()
        val options = host.current.prefs.captions
        if (!options.automatic || images.all { it.id in prepared }) return prepared
        if (!host.current.captionReady(options)) {
            host.notice("请先配置自动图片描述的模型")
            return prepared
        }
        for (image in images) {
            if (image.id in prepared) continue
            try {
                askImageDescription(image, options = options)?.let { prepared[image.id] = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                host.notice("图片描述生成失败 · ${e.localizedMessage ?: image.name}")
            }
        }
        return prepared
    }

    fun downloadCaptionModel() = host.utilityWork("正在下载本地图片描述模型…") {
        installCaptionModel { progress ->
            var completed = 0L
            for (part in CaptionWeights.entries) {
                if (!host.localCaptioner.installed(part)) {
                    downloadModelWeights(part.path, { input -> host.localCaptioner.install(part, input) { progress(completed + it) } }) {
                        host.mutable.update { it.copy(utility = "正在从镜像下载本地图片描述模型…") }
                    }
                }
                completed += part.size
                progress(completed)
            }
        }
    }

    fun importCaptionModel(uri: Uri) = host.utilityWork("正在导入本地图片描述模型…") {
        installCaptionModel { progress ->
            withContext(Dispatchers.IO) {
                requireNotNull(host.resolver.openInputStream(uri)) { "无法打开模型包" }.use { host.localCaptioner.importArchive(it, progress) }
            }
        }
    }

    private suspend fun installCaptionModel(read: suspend ((Long) -> Unit) -> Unit) {
        val context = currentCoroutineContext()
        val total = CaptionWeights.entries.sumOf(CaptionWeights::size)
        host.mutable.update { it.copy(localCaptionProgress = 0) }
        var last = 0
        try {
            read { bytes ->
                context.ensureActive()
                val percent = (bytes * 100 / total).toInt()
                if (percent != last) {
                    last = percent
                    host.mutable.update { it.copy(localCaptionProgress = percent) }
                }
            }
            host.notice("本地图片描述模型已就绪")
        } finally {
            host.mutable.update { it.copy(localCaptionInstalled = host.localCaptioner.installed(), localCaptionProgress = null) }
        }
    }

    fun removeCaptionModel() = host.work {
        withContext(Dispatchers.IO) { host.localCaptioner.remove() }
        host.mutable.update { it.copy(localCaptionInstalled = false) }
    }

    fun exportImage(uri: Uri, image: ChatImage) = host.work {
        withContext(Dispatchers.IO) {
            host.writeUri(uri, requireNotNull(host.imageStore.bytes(image)) { "图片文件不可用" })
        }
        host.notice("图片已保存")
    }

    fun exportVideo(uri: Uri, video: ChatVideo) = host.work {
        withContext(Dispatchers.IO) {
            requireNotNull(host.videoStore.input(video)) { "视频文件不可用" }.use { input ->
                requireNotNull(host.resolver.openOutputStream(uri, "wt")) { "无法写入所选文件" }.use { out ->
                    check(copyVideo(input, out, video.byteCount) == video.byteCount) { "视频文件大小已变化" }
                }
            }
        }
        host.notice("视频已保存")
    }

    fun importVideo(chatId: String, uri: Uri, messageIndex: Int? = null) = host.utilityWork("正在导入视频…") {
        val chat = host.current.chat?.takeIf { it.id == chatId } ?: return@utilityWork
        val message = messageIndex?.let { chat.messages.getOrNull(it) ?: return@utilityWork }
        require(message == null || message.videos.size < MAX_VIDEOS_PER_MESSAGE) { "每条消息最多附带 $MAX_VIDEOS_PER_MESSAGE 段视频" }
        val store = host.videoStore
        val video = withContext(Dispatchers.IO) {
            val name = host.app.documentName(uri)
            val context = currentCoroutineContext()
            requireNotNull(host.resolver.openInputStream(uri)) { "无法打开视频文件" }.use { store.import(it, name) { context.ensureActive() } }
        }
        if (message != null) {
            if (message.videos.none { it.id == video.id }) {
                host.chats.updateMessage(chat, messageIndex, message.copy(videos = message.videos + video))
            }
        } else appendVideo(chat, video, "附件视频")
    }

    fun removeMessageMedia(chatId: String, index: Int, filename: String? = null) = host.work {
        val chat = host.current.chat?.takeIf { it.id == chatId } ?: return@work
        val message = chat.messages.getOrNull(index) ?: return@work
        host.generation.stopAuto()
        host.chats.updateMessage(chat, index, message.withoutDisplayedMedia(filename))
    }

    fun setMessageMedia(chatId: String, index: Int, display: MessageMediaDisplay? = null, selected: Int? = null) = host.work {
        val chat = host.current.chat?.takeIf { it.id == chatId } ?: return@work
        val message = chat.messages.getOrNull(index) ?: return@work
        if (message.mediaCount < 2) return@work
        val target = (selected ?: message.selectedMediaIndex).coerceIn(0, message.mediaCount - 1)
        host.chats.updateMessage(chat, index, message.copy(mediaDisplay = display ?: message.mediaDisplay, mediaIndex = target))
    }

    internal suspend fun appendVideo(chat: Chat, video: ChatVideo, name: String) {
        val current = host.current.chat?.takeIf { it.id == chat.id } ?: return
        val message = Message("system", name, "", videos = listOf(video))
        host.persist(current.copy(messages = current.messages + message, updated = System.currentTimeMillis()))
    }
}
