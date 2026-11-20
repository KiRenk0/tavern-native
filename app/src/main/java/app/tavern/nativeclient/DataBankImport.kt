package app.tavern.nativeclient

import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Xml
import androidx.annotation.RequiresApi
import androidx.core.text.HtmlCompat
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.ZipFile

internal const val MAX_DOCUMENT_INPUT_BYTES = 24 * 1024 * 1024
private const val MAX_WEB_BYTES = 6 * 1024 * 1024
private val invisibleHtml = Regex("(?is)<(script|style|noscript|template|svg|nav|footer|aside)\\b[^>]*>.*?</\\1\\s*>")
private val pageTitle = Regex("(?is)<title\\b[^>]*>(.*?)</title\\s*>")
private val charsetName = Regex("(?i)charset\\s*=\\s*[\"']?([a-zA-Z0-9_-]+)")

internal data class ImportedDocument(val title: String, val text: String)

internal fun openDataDocument(name: String, mime: String?, bytes: ByteArray, cacheDirectory: File): ImportedDocument {
    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val markdownOrText = setOf("txt", "text", "md", "markdown", "log", "json", "csv", "tsv", "yaml", "yml", "xml", "srt", "vtt")
    val type = mime?.substringBefore(';')?.lowercase(Locale.ROOT)
    val text = when {
        ext == "pdf" || type == "application/pdf" || bytes.hasPrefix(0x25, 0x50, 0x44, 0x46, 0x2d) -> {
            if (Build.VERSION.SDK_INT >= 35) withDocumentFile(cacheDirectory, bytes, ".pdf", ::pdfText)
            else error("提取 PDF 文本需要 Android 15 或更高版本")
        }
        ext == "epub" || type == "application/epub+zip" -> withDocumentFile(cacheDirectory, bytes, ".epub", ::epubText)
        ext in setOf("html", "htm", "xhtml") || (ext !in markdownOrText && type in setOf("text/html", "application/xhtml+xml")) ->
            htmlText(decodeDocumentText(bytes, mime, inspectMarkup = true))
        else -> {
            require(!bytes.hasPrefix(0x50, 0x4b, 0x03, 0x04)) { "无法把压缩或二进制文件作为文本导入" }
            decodeDocumentText(bytes, mime)
        }
    }
    return ImportedDocument(name.take(180), validatedDocumentText(text))
}

internal fun decodeDocumentText(bytes: ByteArray, mime: String? = null, inspectMarkup: Boolean = false): String {
    val bom = when {
        bytes.hasPrefix(0xef, 0xbb, 0xbf) -> Charsets.UTF_8 to 3
        bytes.hasPrefix(0xfe, 0xff) -> Charsets.UTF_16BE to 2
        bytes.hasPrefix(0xff, 0xfe) -> Charsets.UTF_16LE to 2
        else -> null
    }
    if (bom != null) return bom.first.newDecoder().decode(ByteBuffer.wrap(bytes, bom.second, bytes.size - bom.second)).toString()
    val declared = charsetName.find(mime.orEmpty())?.groupValues?.get(1)
        ?: if (inspectMarkup) charsetName.find(bytes.copyOf(minOf(bytes.size, 4096)).toString(Charsets.ISO_8859_1))?.groupValues?.get(1) else null
    val candidates = listOfNotNull(declared?.let { runCatching { Charset.forName(it) }.getOrNull() },
        Charsets.UTF_8, Charset.forName("GB18030")).distinct()
    for (charset in candidates) try {
        return charset.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        continue
    }
    error("无法识别文件的文本编码")
}

internal suspend fun importWebDocument(value: String): ImportedDocument {
    val url = runCatching { URI(value.trim()) }.getOrNull()
    require(url != null && url.scheme in setOf("http", "https") && url.host != null && url.rawUserInfo == null) { "请输入有效的网页地址" }
    return request(url.toString(), headers = mapOf("Accept" to "text/html, application/xhtml+xml, text/plain"), followRedirects = true) { input, type ->
        val format = type.substringBefore(';').trim().lowercase(Locale.ROOT)
        require(format.isEmpty() || format.startsWith("text/") || format == "application/xhtml+xml") { "此地址未返回网页或纯文本" }
        val html = format == "text/html" || format == "application/xhtml+xml"
        val decoded = decodeDocumentText(input.readLimited(MAX_WEB_BYTES), type, inspectMarkup = html)
        val title = if (html) pageTitle.find(decoded)?.groupValues?.get(1)?.let(::htmlText)?.trim().orEmpty() else ""
        ImportedDocument(title.ifBlank { url.host }.take(180), validatedDocumentText(if (html) htmlText(decoded) else decoded))
    }
}

private fun htmlText(html: String) = HtmlCompat.fromHtml(invisibleHtml.replace(html, " "), HtmlCompat.FROM_HTML_MODE_COMPACT)
    .toString().replace('\uFFFC', ' ').trim()

private fun ByteArray.hasPrefix(vararg values: Int) = size >= values.size && values.indices.all { (this[it].toInt() and 255) == values[it] }

private inline fun <T> withDocumentFile(cacheDirectory: File, bytes: ByteArray, suffix: String, extract: (File) -> T): T {
    val file = File.createTempFile("tavern-document-", suffix, cacheDirectory)
    try {
        file.writeBytes(bytes)
        return extract(file)
    } finally {
        file.delete()
    }
}

@RequiresApi(35)
private fun pdfText(file: File): String {
    val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    val renderer = try { PdfRenderer(descriptor) } catch (e: Exception) { descriptor.close(); throw e }
    return renderer.use { pdf ->
        buildString {
            for (page in 0 until pdf.pageCount) {
                pdf.openPage(page).use { current -> current.textContents.forEach { entry ->
                    appendLine(entry.text)
                    require(length <= MAX_DOCUMENT_CHARACTERS) { "PDF 文本超过 200 万字符" }
                } }
                appendLine()
            }
        }
    }
}

private fun epubText(file: File): String = ZipFile(file).use { zip ->
    require(zip.size() in 1..10_000) { "ePUB 中的文件数量超出上限" }
    var packagePath: String? = null
    epubXml(zip, "META-INF/container.xml") { element ->
        if (element.name == "rootfile" && packagePath == null) packagePath = element.getAttributeValue(null, "full-path")
    }
    val root = requireNotNull(packagePath) { "ePUB 缺少书籍清单" }
    val manifest = mutableMapOf<String, String>()
    val spine = mutableListOf<String>()
    epubXml(zip, root) { element ->
        when (element.name) {
            "item" -> {
                val id = element.getAttributeValue(null, "id")
                val href = element.getAttributeValue(null, "href")
                if (id != null && href != null) manifest[id] = href
            }
            "itemref" -> element.getAttributeValue(null, "idref")?.let(spine::add)
        }
    }
    require(spine.isNotEmpty()) { "ePUB 缺少章节顺序" }
    val prefix = URI("file", null, "/$root", null)
    buildString {
        val seen = mutableSetOf<String>()
        var remaining = 32 * 1024 * 1024
        for (id in spine) {
            val href = manifest[id] ?: continue
            val parsed = prefix.resolve(URI(href.replace(" ", "%20"))).normalize()
            val path = parsed.path?.removePrefix("/") ?: continue
            if (parsed.scheme != "file" || parsed.host != null || path.startsWith("../") || !seen.add(path)) continue
            val part = zip.getEntry(path)?.takeUnless { it.isDirectory } ?: continue
            val bytes = zip.getInputStream(part).use { it.readLimited(minOf(MAX_WEB_BYTES, remaining)) }
            remaining -= bytes.size
            val chapter = htmlText(decodeDocumentText(bytes, inspectMarkup = true))
            if (chapter.isNotBlank()) append(chapter).append("\n\n")
            require(length <= MAX_DOCUMENT_CHARACTERS) { "ePUB 文本超过 200 万字符" }
        }
    }
}

private fun epubXml(zip: ZipFile, path: String, element: (XmlPullParser) -> Unit) {
    val entry = requireNotNull(zip.getEntry(path)?.takeUnless { it.isDirectory }) { "ePUB 清单不完整" }
    val xml = zip.getInputStream(entry).use { it.readLimited(1024 * 1024) }
    val parser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        setInput(xml.inputStream(), null)
    }
    while (parser.next() != XmlPullParser.END_DOCUMENT) {
        if (parser.eventType == XmlPullParser.START_TAG) element(parser)
    }
}
