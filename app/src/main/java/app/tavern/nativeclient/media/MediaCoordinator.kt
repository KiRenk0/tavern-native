package app.tavern.nativeclient.media

import android.net.Uri
import app.tavern.nativeclient.*

/**
 * Facade delegating media responsibilities across cohesive sub-coordinators:
 * - [VoiceNarrationCoordinator] (TTS queue, voice validation, reply sound)
 * - [MediaCaptionCoordinator] (attachments, local/remote captioning, media import/export)
 * - [DrawingVideoCoordinator] (image/video generation, ComfyUI/Horde workflows, gallery)
 * - [ExpressionBackgroundCoordinator] (character expressions, background library & folders)
 */
internal class MediaCoordinator(private val host: CoordinatorHost) {
    val voice = VoiceNarrationCoordinator(host)
    val captions = MediaCaptionCoordinator(host)
    val drawing = DrawingVideoCoordinator(host, captions)
    val expressions = ExpressionBackgroundCoordinator(host, drawing::closeImageGallery)

    // Voice & narration
    fun activeNarrationChatId(): String? = voice.activeNarrationChatId()
    fun close() = voice.close()
    fun voiceForeground(visible: Boolean) = voice.voiceForeground(visible)
    fun playReplySound(preview: Boolean = false) = voice.playReplySound(preview)
    fun saveVoice(options: VoicePreferences, done: () -> Unit) = voice.saveVoice(options, done)
    suspend fun systemVoices(): List<DeviceVoice> = voice.systemVoices()
    fun previewVoice(options: VoicePreferences, voice: String, line: String) = this.voice.previewVoice(options, voice, line)
    fun stopNarration() = voice.stopNarration()
    fun narrate(message: Message, automatic: Boolean = false) = voice.narrate(message, automatic)
    internal fun scriptNarrate(text: String, actor: String?) = voice.scriptNarrate(text, actor)

    // Attachments, captions, media export/import
    fun attachImages(chatId: String, documents: List<Uri>, ready: ((ChatImage) -> Unit)? = null, messageIndex: Int? = null) =
        captions.attachImages(chatId, documents, ready, messageIndex)
    fun removeDraftImage(chatId: String, image: ChatImage) = captions.removeDraftImage(chatId, image)
    internal fun importCaptionableImage(uri: Uri, name: String): ChatImage = captions.importCaptionableImage(uri, name)
    fun saveCaptionSettings(settings: CaptionSettings, done: () -> Unit) = captions.saveCaptionSettings(settings, done)
    fun generateImageCaption(image: ChatImage, prompt: String, done: (String) -> Unit) = captions.generateImageCaption(image, prompt, done)
    fun saveImageCaption(chatId: String, target: CaptionImageTarget, text: String, done: () -> Unit) =
        captions.saveImageCaption(chatId, target, text, done)
    internal suspend fun askImageDescription(image: ChatImage, prompt: String = "", options: CaptionSettings = host.current.prefs.captions): String? =
        captions.askImageDescription(image, prompt, options)
    internal suspend fun prepareImageCaptions(chatId: String, images: List<ChatImage>): Map<String, String> =
        captions.prepareImageCaptions(chatId, images)
    fun downloadCaptionModel() = captions.downloadCaptionModel()
    fun importCaptionModel(uri: Uri) = captions.importCaptionModel(uri)
    fun removeCaptionModel() = captions.removeCaptionModel()
    fun exportImage(uri: Uri, image: ChatImage) = captions.exportImage(uri, image)
    fun exportVideo(uri: Uri, video: ChatVideo) = captions.exportVideo(uri, video)
    fun importVideo(chatId: String, uri: Uri, messageIndex: Int? = null) = captions.importVideo(chatId, uri, messageIndex)
    fun removeMessageMedia(chatId: String, index: Int, filename: String? = null) = captions.removeMessageMedia(chatId, index, filename)
    fun setMessageMedia(chatId: String, index: Int, display: MessageMediaDisplay? = null, selected: Int? = null) =
        captions.setMessageMedia(chatId, index, display, selected)

    // Image/video generation & gallery
    fun cancelImage() = drawing.cancelImage()
    fun generateVideo(chatId: String, request: VideoRequest): Boolean = drawing.generateVideo(chatId, request)
    fun generateImage(chatId: String, request: ImageRequest, mode: DrawingMode, target: Int?, remember: Boolean): Boolean =
        drawing.generateImage(chatId, request, mode, target, remember)
    fun generateNanoBanana(chatId: String, preset: NanoBananaPreset, note: String, includeReferences: Boolean): Boolean =
        drawing.generateNanoBanana(chatId, preset, note, includeReferences)
    fun draftDrawingPrompt(chat: Chat, mode: DrawingMode, text: String, done: (String) -> Unit): Boolean =
        drawing.draftDrawingPrompt(chat, mode, text, done)
    fun saveDrawingSettings(settings: DrawingSettings, done: () -> Unit) = drawing.saveDrawingSettings(settings, done)
    fun readComfyWorkflow(uri: Uri, done: (ComfyWorkflow) -> Unit) = drawing.readComfyWorkflow(uri, done)
    fun saveComfyWorkflow(workflow: ComfyWorkflow, done: (ComfyWorkflow) -> Unit) = drawing.saveComfyWorkflow(workflow, done)
    fun deleteComfyWorkflow(workflow: ComfyWorkflow, done: () -> Unit) = drawing.deleteComfyWorkflow(workflow, done)
    fun exportComfyWorkflow(uri: Uri, workflow: ComfyWorkflow) = drawing.exportComfyWorkflow(uri, workflow)
    fun testComfy(provider: Provider, protocol: ImageProtocol) = drawing.testComfy(provider, protocol)
    fun loadComfyOptions(provider: Provider, done: (ComfyCatalog) -> Unit) = drawing.loadComfyOptions(provider, done)
    fun loadHordeImageModels(provider: Provider, done: (List<String>) -> Unit) = drawing.loadHordeImageModels(provider, done)
    fun showImageGallery() = drawing.showImageGallery()
    fun closeImageGallery() = drawing.closeImageGallery()
    internal suspend fun loadImageGallery(): String = drawing.loadImageGallery()
    fun previousDrawing(chatId: String, index: Int) = drawing.previousDrawing(chatId, index)
    fun nextDrawing(chatId: String, index: Int): Boolean = drawing.nextDrawing(chatId, index)
    internal fun launchDrawing(chatId: String, video: Boolean = false, action: suspend (Chat) -> Unit): Boolean =
        drawing.launchDrawing(chatId, video, action)
    internal fun drawingCard(chat: Chat, target: Int? = null): Card? = drawing.drawingCard(chat, target)
    internal suspend fun drawingPrompt(chat: Chat, mode: DrawingMode, text: String, extendFree: Boolean): String =
        drawing.drawingPrompt(chat, mode, text, extendFree)
    internal fun prepareDrawing(
        request: ImageRequest,
        chat: Chat,
        mode: DrawingMode,
        target: Int?,
        card: Card? = drawingCard(chat, target),
    ): ImageRequest = drawing.prepareDrawing(request, chat, mode, target, card)
    internal suspend fun renderDrawing(
        chat: Chat,
        request: ImageRequest,
        target: Int? = null,
        quiet: Boolean = false,
        gallery: Boolean = true,
        background: Boolean = false,
        previous: DrawnImageSeries? = null,
        card: Card? = drawingCard(chat, target),
        groupTurnId: String? = null,
        caption: String = request.prompt,
    ): ChatImage = drawing.renderDrawing(chat, request, target, quiet, gallery, background, previous, card, groupTurnId, caption)
    internal fun drawingTool(prefs: Preferences, chat: Chat, card: Card?): ModelTool? = drawing.drawingTool(prefs, chat, card)

    // Expressions & background library
    suspend fun expressionSprites(card: Card): List<ExpressionSprite> = expressions.expressionSprites(card)
    suspend fun expressionFrame(chatId: String, subject: ExpressionSubject, forScene: Boolean = false): ExpressionFrame? =
        expressions.expressionFrame(chatId, subject, forScene)
    fun downloadExpressionModel() = expressions.downloadExpressionModel()
    fun importExpressionModel(uri: Uri) = expressions.importExpressionModel(uri)
    fun removeExpressionModel() = expressions.removeExpressionModel()
    fun saveExpressionSettings(settings: ExpressionSettings) = expressions.saveExpressionSettings(settings)
    fun importExpressionSprites(cardId: String, files: List<Uri>, pack: Boolean, label: String) =
        expressions.importExpressionSprites(cardId, files, pack, label)
    fun removeExpressionSprite(cardId: String, sprite: ExpressionSprite) = expressions.removeExpressionSprite(cardId, sprite)
    fun showExpression(cardId: String, sprite: ExpressionSprite, done: () -> Unit = {}) = expressions.showExpression(cardId, sprite, done)
    internal suspend fun scriptExpression(command: String, named: Map<String, String>, input: String): String =
        expressions.scriptExpression(command, named, input)
    fun clearDrawingBackground(chatId: String) = expressions.clearDrawingBackground(chatId)
    fun setDrawingBackground(chatId: String, image: ChatImage) = expressions.setDrawingBackground(chatId, image)
    fun importChatBackground(chatId: String, uri: Uri) = expressions.importChatBackground(chatId, uri)
    fun backgroundFile(image: ChatImage) = expressions.backgroundFile(image)
    fun importBackgrounds(uris: List<Uri>, folder: String = "") = expressions.importBackgrounds(uris, folder)
    fun addLibraryBackground(image: ChatImage) = expressions.addLibraryBackground(image)
    fun selectLibraryBackground(id: String?) = expressions.selectLibraryBackground(id)
    fun renameLibraryBackground(id: String, name: String) = expressions.renameLibraryBackground(id, name)
    fun removeLibraryBackground(id: String) = expressions.removeLibraryBackground(id)
    fun setBackgroundFit(fit: BackgroundFit) = expressions.setBackgroundFit(fit)
    fun createBackgroundFolder(path: String) = expressions.createBackgroundFolder(path)
    fun moveLibraryBackground(id: String, folder: String) = expressions.moveLibraryBackground(id, folder)
    fun renameBackgroundFolder(oldPath: String, newName: String) = expressions.renameBackgroundFolder(oldPath, newName)
    fun deleteBackgroundFolder(path: String) = expressions.deleteBackgroundFolder(path)
}
