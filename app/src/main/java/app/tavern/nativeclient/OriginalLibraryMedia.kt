package app.tavern.nativeclient

import org.json.JSONObject
import java.net.URI
import java.util.Base64

private val imageExtensions = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "avif", "heic", "heif")
private val localImageRoots = listOf("user/images/", "backgrounds/", "characters/")

internal class OriginalLibraryMedia(
    private val source: OriginalLibrarySource,
    private val documents: ChatDocumentStore?,
    private val images: ChatImageStore?,
    private val checkActive: () -> Unit,
) {
    private val restoredImages = mutableMapOf<String, ChatImage?>()
    private val nativeImages = mutableMapOf<String, Boolean>()
    private val nativeDocuments = mutableMapOf<String, Boolean>()
    private var inlineCount = 0
    var missing = 0
        private set
    val imageCount get() = restoredImages.values.count { it != null } + nativeImages.values.count { it } + inlineCount

    fun document(filename: String): OriginalLibraryFile? = source.file("user/files/$filename") ?: source.file("files/$filename")

    fun message(raw: Message, nativeBackup: Boolean = false): Message {
        checkActive()
        val message = if (nativeBackup) raw.copy(
            images = raw.images.filter { nativeImage(it) != null },
            documents = raw.documents.filter(::nativeDocument),
            drawing = raw.drawing?.availableBackupImages { nativeImage(it) != null },
        ) else raw
        val restored = documents?.let { store ->
            message.originalChatFiles().mapNotNull { ref ->
                val attachment = asset {
                    val text = ref.inline ?: ref.file?.let(::document)?.bytes(MAX_STORED_CHAT_DOCUMENT)?.let(::decodeDocumentText)
                    text?.let { store.import(ImportedDocument(ref.name, it)) }
                }
                attachment?.let { ref.index to it }
            }.toMap()
        }.orEmpty()
        val withDocuments = message.withOriginalDocuments(restored)
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

    fun background(value: String): ChatImage? =
        value.trim().removePrefix("url(").removeSuffix(")").trim().trim('"', '\'').takeIf(String::isNotBlank)?.let(::image)

    fun nativeImage(image: ChatImage): ChatImage? {
        val store = images ?: return null
        val available = nativeImages.getOrPut(image.filename) {
            asset {
                val file = source.file("$NATIVE_BACKUP_IMAGES/${image.filename}") ?: return@asset null
                store.restore(image, file.bytes(MAX_STORED_IMAGE))
            } != null
        }
        return image.takeIf { available }
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
            val path = "user/images/$name"
            source.files(path).filter { it.extension.lowercase() in imageExtensions }.mapNotNull { entry ->
                imageAt("$path/${entry.name}")?.let { GalleryImage(it, "", entry.modified.coerceAtLeast(0)) }
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
        val decoded = runCatching { URI(reference).path }.getOrNull()
        val candidates = listOfNotNull(decoded, reference).distinct().map { it.removePrefix("./").removePrefix("/") }
        val path = candidates.firstOrNull { candidate ->
            safeOriginalPath(candidate) && localImageRoots.any(candidate::startsWith) &&
                candidate.substringAfterLast('.').lowercase() in imageExtensions
        } ?: return null
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
