package app.tavern.nativeclient

import org.json.JSONObject
import java.net.URI
import java.util.Base64
import java.util.Locale

internal val originalImageExtensions = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "avif", "heic", "heif")
private val localImageRoots = listOf(
    "user/images/",
    "backgrounds/",
    "characters/",
    "User Avatars/",
    "user avatars/",
    "user/assets/",
    "assets/",
    "thumbnails/",
)

internal fun resolveOriginalMediaPath(reference: String): String? {
    val trimmed = reference.trim()
    if (trimmed.isEmpty()) return null
    val uri = runCatching { URI(trimmed) }.getOrNull()
    if (uri != null && (uri.isAbsolute || uri.rawAuthority != null)) return null
    val candidates = listOfNotNull(
        uri?.rawPath?.let(::decodeAndCheckOriginalPath),
        decodeAndCheckOriginalPath(trimmed),
    ).distinct()
    return candidates.firstOrNull { candidate ->
        localImageRoots.any { root -> candidate.startsWith(root, ignoreCase = true) } &&
            candidate.substringAfterLast('.').lowercase(Locale.ROOT) in originalImageExtensions
    }
}

internal fun isSupportedImageHeader(bytes: ByteArray): Boolean {
    if (bytes.size < 4) return false
    val b0 = bytes[0].toInt() and 0xFF
    val b1 = bytes[1].toInt() and 0xFF
    val b2 = bytes[2].toInt() and 0xFF
    val b3 = bytes[3].toInt() and 0xFF
    val isPng = b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47
    val isJpeg = b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF
    val isGif = b0 == 0x47 && b1 == 0x49 && b2 == 0x46 && b3 == 0x38
    val isBmp = b0 == 0x42 && b1 == 0x4D
    val isWebp = bytes.size >= 12 && b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46 &&
        bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
        bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
    val isIsoBmff = bytes.size >= 12 && bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() &&
        bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte()
    return isPng || isJpeg || isGif || isBmp || isWebp || isIsoBmff
}

internal class OriginalLibraryMedia(
    private val source: OriginalLibrarySource,
    private val documents: ChatDocumentStore?,
    private val images: ChatImageStore?,
    private val checkActive: () -> Unit,
    private val videos: ChatVideoStore? = null,
) {
    private val restoredImages = mutableMapOf<String, ChatImage?>()
    private val nativeImages = mutableMapOf<String, Boolean>()
    private val nativeDocuments = mutableMapOf<String, Boolean>()
    private val nativeVideos = mutableMapOf<String, Boolean>()
    private val restoredVideos = mutableMapOf<String, ChatVideo?>()
    private var inlineCount = 0
    var personaAvatarCount = 0
        private set
    var missing = 0
        private set
    val imageCount get() = restoredImages.values.count { it != null } + nativeImages.values.count { it } + inlineCount

    fun userAvatarFiles(): Map<String, OriginalLibraryFile> {
        val result = linkedMapOf<String, OriginalLibraryFile>()
        for (dir in listOf("User Avatars", "user avatars")) {
            for (entry in source.files(dir)) {
                if (!safeOriginalPath(entry.relativePath)) continue
                if (entry.extension.lowercase(Locale.ROOT) !in originalImageExtensions) continue
                result.putIfAbsent(entry.name, entry)
            }
        }
        return result
    }

    fun readPersonaAvatar(file: OriginalLibraryFile): ByteArray? = asset {
        require(safeOriginalPath(file.relativePath)) { "不安全的人设头像路径" }
        val bytes = file.bytes(MAX_IMAGE_SOURCE)
        require(isSupportedImageHeader(bytes)) { "人设头像不是有效的图片格式" }
        personaAvatarCount++
        bytes
    }

    fun document(filename: String): OriginalLibraryFile? = source.file("user/files/$filename") ?: source.file("files/$filename")

    fun message(raw: Message, nativeBackup: Boolean = false): Message {
        checkActive()
        val message = if (nativeBackup) raw.copy(
            images = raw.images.filter { nativeImage(it) != null },
            documents = raw.documents.filter(::nativeDocument),
            videos = raw.videos.filter(::nativeVideo),
            drawing = raw.drawing?.availableBackupImages { nativeImage(it) != null },
        ).withAvailableReplyImages { nativeImage(it) != null } else raw
        val restored = documents?.let { store ->
            message.originalChatFiles().mapNotNull { ref ->
                val attachment = asset {
                    val text = ref.inline ?: ref.file?.let(::document)?.bytes(MAX_STORED_CHAT_DOCUMENT)?.let(::decodeDocumentText)
                    text?.let { store.import(ImportedDocument(ref.name, it)) }
                }
                attachment?.let { ref.index to it }
            }.toMap()
        }.orEmpty()
        val withDocuments = message.withOriginalDocuments(restored).withOriginalVideos(nativeBackup)
        if (images == null || withDocuments.images.size >= MAX_CHAT_IMAGES) return withDocuments
        val extra = withDocuments.raw?.let(::JSONObject)?.optJSONObject("extra") ?: return withDocuments
        val references = extra.optJSONArray("media")?.objects().orEmpty().mapNotNull { value ->
            val url = value.string("url").takeIf(String::isNotBlank) ?: return@mapNotNull null
            if (value.string("type") !in listOf("", "image")) return@mapNotNull null
            val isCaption = value.optBoolean("captioned") || value.optBoolean("append_title") || value.string("source") == "captioned"
            url to value.string("title").takeIf { isCaption }
        }.toMutableList()
        val old = extra.opt("image") as? String
        if (!old.isNullOrBlank() && references.none { it.first == old }) {
            references += old to extra.string("title").takeIf { extra.optBoolean("append_title") || extra.optBoolean("captioned") }
        }
        val attachments = withDocuments.images.toMutableList()
        val captions = withDocuments.imageCaptions.toMutableMap()
        for ((url, description) in references.distinctBy { it.first }) {
            if (attachments.size == MAX_CHAT_IMAGES) break
            if (nativeBackup && url.startsWith("$NATIVE_BACKUP_IMAGES/")) continue
            val image = image(url) ?: continue
            if (attachments.none { it.id == image.id }) attachments += image
            val caption = description?.trim()?.take(MAX_IMAGE_CAPTION)?.takeIf { it.isNotBlank() && it != message.text.trim() }
            if (caption != null) captions.putIfAbsent(image.id, caption)
        }
        return withDocuments.copy(images = attachments, imageCaptions = captions)
    }

    private fun Message.withOriginalVideos(nativeBackup: Boolean): Message {
        val store = this@OriginalLibraryMedia.videos ?: return this
        val rows = raw?.let(::JSONObject)?.optJSONObject("extra")?.optJSONArray("media")?.objects().orEmpty()
        if (rows.isEmpty() || videos.size == MAX_VIDEOS_PER_MESSAGE) return this
        val attached = videos.associateByTo(linkedMapOf(), ChatVideo::id)
        for (row in rows) {
            if (attached.size == MAX_VIDEOS_PER_MESSAGE) break
            if (!row.string("type").equals("video", true)) continue
            val uri = runCatching { URI(row.string("url")) }.getOrNull() ?: continue
            val path = uri.path?.removePrefix("./")?.removePrefix("/") ?: continue
            if (uri.isAbsolute || uri.rawAuthority != null || !safeOriginalPath(path) || !path.startsWith("user/images/") ||
                !path.endsWith(".mp4", true) || nativeBackup && path.startsWith("$NATIVE_BACKUP_IMAGES/")) continue
            val video = restoredVideos.getOrPut(path) {
                asset { source.file(path)?.let { file -> file.streaming { store.import(it, file.name, checkActive) } } }
            } ?: continue
            attached.putIfAbsent(video.id, video)
        }
        return copy(videos = attached.values.toList())
    }

    private fun nativeVideo(video: ChatVideo): Boolean {
        val store = videos ?: return false
        return nativeVideos.getOrPut(video.filename) {
            asset {
                val file = source.file("$NATIVE_BACKUP_IMAGES/${video.filename}") ?: return@asset null
                file.streaming { store.restore(video, it, checkActive) }
            } != null
        }
    }

    fun background(value: String): ChatImage? =
        value.trim().removePrefix("url(").removeSuffix(")").trim().trim('"', '\'').takeIf(String::isNotBlank)?.let(::image)

    fun backgroundLibrary(settings: JSONObject?, portableFiles: JSONObject?): BackgroundLibrary {
        val store = images ?: return BackgroundLibrary()
        val configured = settings?.string("name").orEmpty()
        var active: String? = null
        val backgroundFiles = source.allFiles("backgrounds").ifEmpty { source.files("backgrounds") }
        val items = backgroundFiles.filter {
            it.name != "__transparent.png" && safeOriginalPath(it.relativePath) &&
                it.extension.lowercase(Locale.ROOT) in originalImageExtensions
        }.mapNotNull { file ->
            val relName = file.relativePath.removePrefix("backgrounds/")
            val saved = (portableFiles?.optJSONObject(relName) ?: portableFiles?.optJSONObject(file.name))
                ?.let(BackgroundEntry::from)
            val entry = if (saved == null) imageAt(file.relativePath)?.let { BackgroundEntry(it, file.modified) } else {
                asset {
                    store.restore(saved.image, file.bytes(saved.image.storageLimit))
                    nativeImages[saved.image.filename] = true
                    saved
                }
            }
            if (entry != null && (file.name == configured || relName == configured)) active = entry.image.id
            entry
        }
        return BackgroundLibrary(items.distinctBy { it.image.id }, active, BackgroundFit.from(settings?.string("fitting").orEmpty()))
    }

    fun nativeImage(image: ChatImage): ChatImage? {
        val store = images ?: return null
        val available = nativeImages.getOrPut(image.filename) {
            asset {
                val file = source.file("$NATIVE_BACKUP_IMAGES/${image.filename}") ?: return@asset null
                store.restore(image, file.bytes(image.storageLimit))
            } != null
        }
        return image.takeIf { available }
    }

    fun groupAvatar(reference: String): GroupAvatar? {
        if (reference.isBlank()) return null
        return asset {
            val bytes = originalGroupAvatarData(reference) ?: originalGroupAvatarPath(reference)?.let {
                source.file(it)?.bytes(MAX_IMAGE_SOURCE)
            } ?: return@asset null
            val bitmap = decodeBoundedImage(bytes, 1024)
            try {
                GroupAvatar.create(bitmap).also { inlineCount++ }
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun nativeDocument(document: ChatDocument): Boolean {
        val store = documents ?: return false
        return nativeDocuments.getOrPut(document.filename) {
            asset {
                val file = source.file("user/files/${document.filename}") ?: return@asset null
                store.restore(document, file.bytes(MAX_STORED_CHAT_DOCUMENT))
            } != null
        }
    }

    fun nativeGallery(owner: String): List<GalleryImage> {
        val entries = source.file("TavernNative Gallery/$owner.json") ?: return emptyList()
        val rows = JSONObject(entries.bytes().decodeToString()).optJSONArray("items")
        return rows?.objects().orEmpty().mapNotNull { row ->
            val image = row.optJSONObject("image")?.let(ChatImage::from)?.let(::nativeImage) ?: return@mapNotNull null
            GalleryImage(image, row.string("prompt"), row.optLong("created"))
        }
    }

    fun gallery(names: List<String>): List<GalleryImage> {
        if (images == null) return emptyList()
        return names.filter { safeOriginalPath(it) && '/' !in it }.distinct().flatMap { name ->
            val roots = listOf("user/images/$name", "user/assets/$name", "assets/$name")
            roots.flatMap { root ->
                val entries = source.allFiles(root).ifEmpty { source.files(root) }
                entries.filter {
                    safeOriginalPath(it.relativePath) && it.extension.lowercase(Locale.ROOT) in originalImageExtensions
                }.mapNotNull { entry ->
                    imageAt(entry.relativePath)?.let { GalleryImage(it, "", entry.modified.coerceAtLeast(0)) }
                }
            }
        }.distinctBy { it.image.id }
    }

    fun extraAssetImages(excludedPrefixes: Set<String> = emptySet()): List<GalleryImage> {
        if (images == null) return emptyList()
        val roots = listOf("user/assets", "assets")
        return roots.flatMap { root ->
            source.allFiles(root).filter { entry ->
                safeOriginalPath(entry.relativePath) &&
                    entry.extension.lowercase(Locale.ROOT) in originalImageExtensions &&
                    excludedPrefixes.none { prefix -> entry.relativePath.startsWith(prefix, ignoreCase = true) }
            }.mapNotNull { entry ->
                imageAt(entry.relativePath)?.let { GalleryImage(it, entry.relativePath, entry.modified.coerceAtLeast(0)) }
            }
        }.distinctBy { it.image.id }
    }

    private fun image(reference: String): ChatImage? {
        val store = images ?: return null
        if (reference.startsWith("data:image/", true)) {
            return asset {
                val header = reference.substringBefore(',')
                require(header.endsWith(";base64", true))
                val encoded = reference.substringAfter(',', "")
                require(encoded.length <= 4 * ((MAX_IMAGE_SOURCE + 2) / 3))
                val bytes = Base64.getDecoder().decode(encoded)
                require(bytes.size <= MAX_IMAGE_SOURCE)
                store.import(bytes, "聊天图片").also { inlineCount++ }
            }
        }
        val path = resolveOriginalMediaPath(reference) ?: return null
        return imageAt(path)
    }

    private fun imageAt(path: String): ChatImage? {
        if (path in restoredImages) return restoredImages[path]
        val result = asset {
            source.file(path)?.let { images?.import(it.bytes(MAX_IMAGE_SOURCE), it.name) }
        }
        restoredImages[path] = result
        return result
    }

    private fun <T> asset(load: () -> T?): T? {
        checkActive()
        val result = try {
            load()
        } catch (_: java.io.IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IllegalStateException) {
            null
        }
        if (result == null) missing++
        return result
    }
}
