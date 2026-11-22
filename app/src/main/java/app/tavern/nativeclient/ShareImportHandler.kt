package app.tavern.nativeclient

import android.content.Intent
import android.net.Uri
import android.os.Build
import java.util.Locale

internal enum class ShareCardFormat(val extension: String, val label: String) {
    PNG("png", "PNG 角色卡"),
    JSON("json", "JSON 角色卡"),
    CHARX("charx", "CharX 角色包"),
    BYAF("byaf", "BYAF 角色包"),
    ;

    companion object {
        fun fromExtension(ext: String?): ShareCardFormat? {
            val clean = ext?.trim()?.removePrefix(".")?.lowercase(Locale.ROOT).orEmpty()
            return entries.firstOrNull { it.extension == clean }
        }
    }
}

internal data class ShareImportCandidate(
    val uri: Uri?,
    val filename: String,
    val mimeType: String?,
    val format: ShareCardFormat,
)

internal data class ShareImportPlan(
    val candidates: List<ShareImportCandidate>,
    val rejectedCount: Int = 0,
    val rejectedReasons: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = candidates.isEmpty()
    val uris: List<Uri> get() = candidates.mapNotNull(ShareImportCandidate::uri)
}

internal object ShareImportHandler {
    private val supportedExtensions = setOf("png", "json", "charx", "byaf")
    private val genericMimeTypes = setOf(
        "application/octet-stream",
        "application/zip",
        "application/x-zip-compressed",
        "binary/octet-stream",
        "*/*",
    )

    fun detectFormat(filename: String?, mimeType: String?): ShareCardFormat? {
        val cleanName = filename?.trim().orEmpty()
        val ext = cleanName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            .takeIf { '.' in cleanName && it.isNotEmpty() }
        ShareCardFormat.fromExtension(ext)?.let { return it }

        // If an explicit unsupported extension is present (e.g. .mp4, .pdf, .apk), reject it
        if (ext != null && ext !in supportedExtensions) {
            return null
        }

        return when (mimeType?.trim()?.lowercase(Locale.ROOT)?.substringBefore(';')) {
            "image/png" -> ShareCardFormat.PNG
            "application/json", "text/json" -> ShareCardFormat.JSON
            "application/x-charx", "application/vnd.charx" -> ShareCardFormat.CHARX
            "application/x-byaf", "application/vnd.byaf" -> ShareCardFormat.BYAF
            else -> null
        }
    }

    fun classifyCandidate(
        uri: Uri?,
        filename: String?,
        mimeType: String?,
    ): ShareImportCandidate? {
        val format = detectFormat(filename, mimeType) ?: return null
        val displayName = filename?.trim().orEmpty().ifBlank { "shared.${format.extension}" }
        return ShareImportCandidate(
            uri = uri,
            filename = displayName,
            mimeType = mimeType,
            format = format,
        )
    }

    fun isShareIntent(intent: Intent?): Boolean {
        val action = intent?.action ?: return false
        return action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE
    }

    fun extractUris(intent: Intent?): List<Uri> {
        if (intent == null || !isShareIntent(intent)) return emptyList()
        val uris = linkedSetOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                parcelableUri(intent, Intent.EXTRA_STREAM)?.let(uris::add)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                parcelableUriList(intent, Intent.EXTRA_STREAM).forEach(uris::add)
            }
        }
        val clip = intent.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i)?.uri?.let(uris::add)
            }
        }
        return uris.toList()
    }

    fun buildImportPlan(
        intent: Intent?,
        resolveName: (Uri) -> String,
        resolveMime: (Uri) -> String?,
    ): ShareImportPlan {
        val uris = extractUris(intent)
        if (uris.isEmpty()) return ShareImportPlan(emptyList())
        val fallbackMime = intent?.type
        val accepted = mutableListOf<ShareImportCandidate>()
        val rejected = mutableListOf<String>()
        for (uri in uris) {
            val name = runCatching { resolveName(uri) }.getOrDefault("")
            val mime = runCatching { resolveMime(uri) }.getOrNull() ?: fallbackMime
            val effectiveMime = if (mime in genericMimeTypes && name.isNotBlank()) mime else mime
            val candidate = classifyCandidate(uri, name, effectiveMime)
            if (candidate != null) {
                accepted += candidate
            } else {
                rejected += "不支持的分享文件：${name.ifBlank { uri.lastPathSegment ?: "未知文件" }}"
            }
        }
        return ShareImportPlan(
            candidates = accepted,
            rejectedCount = rejected.size,
            rejectedReasons = rejected,
        )
    }

    fun buildImportPlanFromItems(
        items: List<Pair<String, String?>>,
    ): ShareImportPlan {
        val accepted = mutableListOf<ShareImportCandidate>()
        val rejected = mutableListOf<String>()
        items.forEach { (filename, mimeType) ->
            val candidate = classifyCandidate(null, filename, mimeType)
            if (candidate != null) accepted += candidate
            else rejected += "不支持的分享文件：${filename.ifBlank { "未知文件" }}"
        }
        return ShareImportPlan(accepted, rejected.size, rejected)
    }

    @Suppress("DEPRECATION")
    private fun parcelableUri(intent: Intent, key: String): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, Uri::class.java)
        } else {
            intent.getParcelableExtra(key) as? Uri
        }

    @Suppress("DEPRECATION")
    private fun parcelableUriList(intent: Intent, key: String): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(key, Uri::class.java).orEmpty()
        } else {
            intent.getParcelableArrayListExtra<Uri>(key).orEmpty()
        }
}
