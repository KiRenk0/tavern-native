package app.tavern.nativeclient

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2
import java.io.File
import java.net.URI
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory

internal enum class OfficeFormat(val extension: String, val mime: String) {
    WORD("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    EXCEL("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    POWERPOINT("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
    OPEN_TEXT("odt", "application/vnd.oasis.opendocument.text"),
    OPEN_SHEET("ods", "application/vnd.oasis.opendocument.spreadsheet"),
    OPEN_SLIDES("odp", "application/vnd.oasis.opendocument.presentation");

    companion object {
        fun find(extension: String, mime: String?) = entries.firstOrNull { it.extension == extension }
            ?: entries.firstOrNull { it.mime == mime }
    }
}

internal fun officeText(file: File, format: OfficeFormat): String = ZipFile(file).use { zip ->
    require(zip.size() in 1..10_000) { "文档内的文件数量超出上限" }
    val source = OfficePackage(zip)
    when (format) {
        OfficeFormat.WORD -> source.paragraphs(source.mainPart("word/document.xml"), "wordprocessingml")
        OfficeFormat.EXCEL -> source.workbook()
        OfficeFormat.POWERPOINT -> source.presentation()
        OfficeFormat.OPEN_TEXT, OfficeFormat.OPEN_SLIDES -> source.openDocument()
        OfficeFormat.OPEN_SHEET -> source.openSpreadsheet()
    }
}

private const val OPEN_TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
private const val OPEN_TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0"
private const val OPEN_OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"
private const val OPEN_DRAW = "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
private const val MAX_COLUMN = 16_384

private class OfficePackage(private val zip: ZipFile) {
    private var remainingXml = 48 * 1024 * 1024

    fun mainPart(fallback: String): String = relationships("").values.firstOrNull { it.type == "officeDocument" }?.path ?: fallback

    fun paragraphs(part: String, namespace: String): String {
        val output = DocumentTextBuffer()
        var text = false
        xml(part, start = { uri, name, _ ->
            if (isOfficeNamespace(uri, namespace)) when (name) {
                "t" -> text = true
                "tab" -> output.append("\t")
                "br", "cr" -> output.line()
                "noBreakHyphen" -> output.append("‑")
            }
        }, end = { uri, name ->
            if (isOfficeNamespace(uri, namespace)) when (name) {
                "t" -> text = false
                "p" -> output.line()
            }
        }, text = { if (text) output.append(it) })
        return output.toString().trim()
    }

    fun presentation(): String {
        val main = mainPart("ppt/presentation.xml")
        val relations = relationships(main)
        val slides = mutableListOf<String>()
        xml(main, start = { uri, name, attributes ->
            if (name == "sldId" && isOfficeNamespace(uri, "presentationml")) {
                val relation = relations[attributes.relationshipId()]
                require(relation?.type == "slide") { "演示文档缺少幻灯片" }
                slides += relation.path
            }
        })
        return DocumentTextBuffer().apply {
            for (slide in slides) {
                val text = paragraphs(slide, "drawingml")
                if (text.isNotBlank()) line(text).line()
            }
        }.toString()
    }

    fun workbook(): String {
        val main = mainPart("xl/workbook.xml")
        val relations = relationships(main)
        val strings = relations.values.firstOrNull { it.type == "sharedStrings" }?.let { sharedStrings(it.path) }.orEmpty()
        val sheets = mutableListOf<Pair<String, String>>()
        xml(main, start = { uri, name, attributes ->
            if (name == "sheet" && isOfficeNamespace(uri, "spreadsheetml")) {
                val relation = relations[attributes.relationshipId()]
                if (relation?.type == "worksheet") sheets += attributes.getValue("name").orEmpty() to relation.path
            }
        })
        return DocumentTextBuffer().apply {
            for ((name, part) in sheets) {
                val text = worksheet(part, strings)
                if (text.isNotBlank()) {
                    if (name.isNotBlank()) line(name)
                    line(text).line()
                }
            }
        }.toString()
    }

    private fun sharedStrings(part: String): List<String> {
        val strings = mutableListOf<String>()
        val value = DocumentTextBuffer()
        var text = false
        var phonetic = false
        var total = 0
        xml(part, start = { uri, name, _ ->
            if (isOfficeNamespace(uri, "spreadsheetml")) when (name) {
                "si" -> value.clear()
                "rPh" -> phonetic = true
                "t" -> text = !phonetic
            }
        }, end = { uri, name ->
            if (isOfficeNamespace(uri, "spreadsheetml")) when (name) {
                "si" -> {
                    val string = value.toString()
                    total += string.length
                    require(strings.size < 500_000 && total <= MAX_DOCUMENT_CHARACTERS) { "工作簿的共享文字超出上限" }
                    strings += string
                }
                "rPh" -> phonetic = false
                "t" -> text = false
            }
        }, text = { if (text) value.append(it) })
        return strings
    }

    private fun worksheet(part: String, strings: List<String>): String {
        val output = DocumentTextBuffer()
        val row = DocumentTextBuffer()
        val cell = DocumentTextBuffer()
        var kind = ""
        var column = 0
        var nextColumn = 0
        var emitted = 0
        var inCell = false
        var text = false
        var phonetic = false
        xml(part, start = { uri, name, attributes ->
            if (isOfficeNamespace(uri, "spreadsheetml")) when (name) {
                "row" -> { row.clear(); nextColumn = 0; emitted = 0 }
                "c" -> {
                    cell.clear()
                    inCell = true
                    kind = attributes.getValue("t").orEmpty()
                    column = columnNumber(attributes.getValue("r")) ?: nextColumn
                    nextColumn = column + 1
                }
                "rPh" -> phonetic = true
                "v", "t" -> text = inCell && !phonetic
            }
        }, end = { uri, name ->
            if (isOfficeNamespace(uri, "spreadsheetml")) when (name) {
                "c" -> {
                    inCell = false
                    val raw = cell.toString()
                    val value = when (kind) {
                        "s" -> raw.trim().toIntOrNull()?.let(strings::getOrNull).orEmpty()
                        "b" -> when (raw.trim()) { "1" -> "TRUE"; "0" -> "FALSE"; else -> raw }
                        else -> raw
                    }
                    if (value.isNotBlank()) {
                        val tabs = (column - emitted + if (emitted == 0) 0 else 1).coerceIn(0, MAX_COLUMN)
                        row.append("\t".repeat(tabs)).append(value)
                        emitted = column + 1
                    }
                }
                "row" -> if (row.isNotEmpty()) output.line(row.toString()) else Unit
                "rPh" -> phonetic = false
                "v", "t" -> text = false
            }
        }, text = { if (text) cell.append(it) })
        return output.toString().trimEnd()
    }

    fun openDocument(): String {
        val output = DocumentTextBuffer()
        var paragraph = 0
        xml("content.xml", start = { uri, name, attributes ->
            if (uri == OPEN_TEXT) when (name) {
                "p", "h" -> paragraph++
                else -> if (paragraph > 0) output.append(openTextSpacing(name, attributes))
            }
        }, end = { uri, name ->
            if (uri == OPEN_TEXT && (name == "p" || name == "h")) {
                paragraph--
                output.line()
            } else if (uri == OPEN_DRAW && name == "page") output.line()
        }, text = { if (paragraph > 0) output.append(it) })
        return output.toString()
    }

    fun openSpreadsheet(): String {
        val output = DocumentTextBuffer()
        val row = DocumentTextBuffer()
        val cell = DocumentTextBuffer()
        var rowCopies = 1
        var cellCopies = 1
        var tabs = 0
        var cells = 0
        var paragraph = false
        var fallback = ""
        var sheetName = ""
        xml("content.xml", start = { uri, name, attributes ->
            when (uri) {
                OPEN_TABLE -> when (name) {
                    "table" -> sheetName = attributes.getValue(OPEN_TABLE, "name").orEmpty()
                    "table-row" -> {
                        row.clear()
                        tabs = 0
                        cells = 0
                        rowCopies = attributes.count(OPEN_TABLE, "number-rows-repeated", MAX_DOCUMENT_CHARACTERS)
                    }
                    "table-cell", "covered-table-cell" -> {
                        cell.clear()
                        cellCopies = attributes.count(OPEN_TABLE, "number-columns-repeated", MAX_COLUMN)
                        fallback = listOf("string-value", "value", "date-value", "time-value", "boolean-value")
                            .firstNotNullOfOrNull { attributes.getValue(OPEN_OFFICE, it) }.orEmpty()
                    }
                }
                OPEN_TEXT -> if (name == "p") paragraph = true else if (paragraph) cell.append(openTextSpacing(name, attributes))
            }
        }, end = { uri, name ->
            if (uri == OPEN_TEXT && name == "p") {
                paragraph = false
                cell.line()
            } else if (uri == OPEN_TABLE) when (name) {
                "table-cell", "covered-table-cell" -> {
                    val value = cell.toString().trimEnd('\n').ifEmpty { fallback }
                    val copies = minOf(cellCopies, MAX_COLUMN - cells)
                    if (value.isBlank()) {
                        tabs += copies
                    } else {
                        repeat(copies) {
                            row.append("\t".repeat(tabs)).append(value)
                            tabs = 1
                        }
                    }
                    cells += copies
                }
                "table-row" -> if (row.isNotEmpty()) {
                    if (sheetName.isNotBlank()) { output.line(sheetName); sheetName = "" }
                    val rowText = row.toString()
                    repeat(rowCopies) { output.line(rowText) }
                }
                "table" -> if (output.isNotEmpty()) output.line() else Unit
            }
        }, text = { if (paragraph) cell.append(it) })
        return output.toString()
    }

    private data class Relationship(val type: String, val path: String)

    private fun relationships(part: String): Map<String, Relationship> {
        val directory = part.substringBeforeLast('/', "").let { if (it.isEmpty()) it else "$it/" }
        val path = directory + "_rels/" + part.substringAfterLast('/') + ".rels"
        if (zip.getEntry(path) == null) return emptyMap()
        return buildMap {
            xml(path, start = { _, name, attributes ->
                if (name == "Relationship" && !attributes.getValue("TargetMode").equals("External", true)) {
                    val id = attributes.getValue("Id")
                    val type = attributes.getValue("Type")?.substringAfterLast('/')
                    val target = resolvePart(directory, attributes.getValue("Target").orEmpty())
                    if (id != null && type != null && target != null) put(id, Relationship(type, target))
                }
            })
        }
    }

    private fun xml(path: String, start: (String, String, Attributes) -> Unit,
                    end: (String, String) -> Unit = { _, _ -> }, text: (String) -> Unit = {}) {
        val entry = requireNotNull(zip.getEntry(path)?.takeUnless { it.isDirectory }) { "文档缺少正文或清单" }
        val limit = minOf(16 * 1024 * 1024, remainingXml)
        val xml = zip.getInputStream(entry).use { it.readLimited(limit) }
        remainingXml -= xml.size
        val handler = object : DefaultHandler2() {
            override fun startDTD(name: String?, publicId: String?, systemId: String?) {
                throw SAXException("文档 XML 不支持 DTD")
            }

            override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) = start(uri, localName, attributes)
            override fun endElement(uri: String, localName: String, qName: String) = end(uri, localName)
            override fun characters(chars: CharArray, offset: Int, count: Int) = text(String(chars, offset, count))
        }
        val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().xmlReader
        reader.setFeature("http://xml.org/sax/features/external-general-entities", false)
        reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
        reader.contentHandler = handler
        reader.parse(InputSource(xml.inputStream()))
    }
}

private fun Attributes.relationshipId(): String? =
    getValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
        ?: getValue("http://purl.oclc.org/ooxml/officeDocument/relationships", "id")

private fun isOfficeNamespace(uri: String, name: String): Boolean =
    uri == "http://schemas.openxmlformats.org/$name/2006/main" || uri == "http://purl.oclc.org/ooxml/$name/main"

private fun resolvePart(directory: String, target: String): String? = runCatching {
    val link = URI(target)
    if (link.isAbsolute || link.rawAuthority != null || link.rawQuery != null || link.rawFragment != null) return null
    val path = URI("file", null, "/$directory", null).resolve(link).normalize().path.removePrefix("/")
    path.takeIf { it.isNotEmpty() && '\\' !in it && it.split('/').none { segment -> segment in setOf("", ".", "..") } }
}.getOrNull()

private fun columnNumber(reference: String?): Int? {
    val letters = reference?.takeWhile { it in 'A'..'Z' } ?: return null
    if (letters.length !in 1..3) return null
    val number = letters.fold(0) { column, letter -> column * 26 + (letter - 'A') + 1 }
    return (number - 1).takeIf { it in 0 until MAX_COLUMN }
}

private fun Attributes.count(uri: String, name: String, max: Int): Int = getValue(uri, name)?.toLongOrNull()?.coerceIn(1, max.toLong())?.toInt() ?: 1

private fun openTextSpacing(name: String, attributes: Attributes): String = when (name) {
    "s" -> " ".repeat(attributes.count(OPEN_TEXT, "c", MAX_DOCUMENT_CHARACTERS))
    "tab" -> "\t"
    "line-break" -> "\n"
    else -> ""
}

private class DocumentTextBuffer {
    private val buffer = StringBuilder()
    fun clear() { buffer.clear() }
    fun isNotEmpty() = buffer.isNotEmpty()
    fun append(text: String): DocumentTextBuffer {
        require(text.length <= MAX_DOCUMENT_CHARACTERS - buffer.length) { "文档正文超过 200 万字符" }
        buffer.append(text)
        return this
    }
    fun line(text: String = "") = append(text).append("\n")
    override fun toString() = buffer.toString()
}
