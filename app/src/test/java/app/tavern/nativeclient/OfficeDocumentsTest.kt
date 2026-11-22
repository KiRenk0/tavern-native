package app.tavern.nativeclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.xml.sax.SAXException
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OfficeDocumentsTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun `word document reads its related body and preserves inline breaks`() {
        val result = read("Draft.DOCX", null,
            "_rels/.rels" to relationships(relation("body", "officeDocument", "/parts/main.xml")),
            "parts/main.xml" to """<w:document xmlns:w="$WORD"><w:body>
                <w:p><w:r><w:t>First</w:t><w:tab/><w:t>part</w:t><w:br/><w:t>with a break</w:t></w:r></w:p>
                <w:tbl><w:tr><w:tc><w:p><w:r><w:t>Table text</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
                </w:body></w:document>""",
        )
        assertEquals("First\tpart\nwith a break\nTable text", result.text)
    }

    @Test
    fun `workbook uses worksheet order and reads shared inline and formula values`() {
        val result = read("Workbook", OfficeFormat.EXCEL.mime,
            "xl/workbook.xml" to """<workbook xmlns="$SHEET" xmlns:r="$REL"><sheets>
                <sheet name="Second part first" sheetId="1" r:id="later"/>
                <sheet name="First part last" sheetId="2" r:id="earlier"/>
                </sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to relationships(
                relation("earlier", "worksheet", "worksheets/sheet1.xml"),
                relation("later", "worksheet", "../parts/sheet2.xml"),
                relation("shared", "sharedStrings", "sharedStrings.xml"),
            ),
            "xl/sharedStrings.xml" to """<sst xmlns="$SHEET"><si>
                <r><t>Har</t></r><r><t>bor</t></r><rPh><t>pronunciation</t></rPh>
                </si></sst>""",
            "parts/sheet2.xml" to """<worksheet xmlns="$SHEET"><sheetData><row r="1">
                <c r="A1" t="s"><v>0</v></c><c r="C1"><f>5+2</f><v>7</v></c><c r="D1" t="b"><v>1</v></c>
                </row></sheetData></worksheet>""",
            "xl/worksheets/sheet1.xml" to """<worksheet xmlns="$SHEET"><sheetData><row r="2">
                <c r="B2" t="inlineStr"><is><t>Inline value</t></is></c>
                </row></sheetData></worksheet>""",
        )
        assertEquals("Second part first\nHarbor\t\t7\tTRUE\n\nFirst part last\n\tInline value", result.text)
    }

    @Test
    fun `presentation extracts text in slide order rather than archive filename order`() {
        val result = read("Slides.pptx", null,
            "ppt/presentation.xml" to """<p:presentation xmlns:p="$SLIDES" xmlns:r="$REL"><p:sldIdLst>
                <p:sldId id="260" r:id="two"/><p:sldId id="261" r:id="one"/>
                </p:sldIdLst></p:presentation>""",
            "ppt/_rels/presentation.xml.rels" to relationships(
                relation("one", "slide", "slides/slide1.xml"), relation("two", "slide", "slides/slide2.xml"),
            ),
            "ppt/slides/slide1.xml" to slide("The last slide"),
            "ppt/slides/slide2.xml" to slide("The first slide"),
        )
        assertEquals("The first slide\n\nThe last slide", result.text)
    }

    @Test
    fun `OpenDocument reads body text slide paragraphs and spreadsheet repeat cells`() {
        val paragraphs = """<text:h>Title</text:h><text:p>One<text:s text:c="2"/>two<text:tab/>three</text:p>"""
        for ((ext, content) in listOf("odt" to paragraphs, "odp" to "<draw:page>$paragraphs</draw:page>")) {
            assertEquals("Title\nOne  two\tthree", read("Note.$ext", null, "content.xml" to openDocument(content)).text)
        }
        val sheet = """<table:table table:name="Stock"><table:table-row table:number-rows-repeated="2">
            <table:table-cell><text:p>Glass</text:p></table:table-cell>
            <table:table-cell table:number-columns-repeated="2"/>
            <table:table-cell office:value="2.5"/>
            <table:table-cell table:number-columns-repeated="900"/>
            </table:table-row></table:table>"""
        assertEquals("Stock\nGlass\t\t\t2.5\nGlass\t\t\t2.5", read("Stock.ods", null, "content.xml" to openDocument(sheet)).text)
    }

    @Test
    fun `embedded document type declarations are rejected before reading Office content`() {
        assertThrows(SAXException::class.java) {
            read("Draft.docx", null, "word/document.xml" to "<!DOCTYPE document><document/>")
        }
    }

    @Test
    fun `web address list retains order and signals when the batch limit is exceeded`() {
        assertEquals(listOf("https://example.com/one", "https://example.com/two"),
            webDocumentLinks(" https://example.com/one \r\n\r\nhttps://example.com/two\nhttps://example.com/one"))
        assertEquals(MAX_WEB_DOCUMENTS + 1, webDocumentLinks((1..50).joinToString("\n") { "https://example.com/$it" }).size)
    }

    private fun read(name: String, mime: String?, vararg parts: Pair<String, String>): ImportedDocument {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { archive ->
            for ((path, xml) in parts) {
                archive.putNextEntry(ZipEntry(path))
                archive.write(xml.encodeToByteArray())
                archive.closeEntry()
            }
        }
        return openDataDocument(name, mime, bytes.toByteArray(), files.root)
    }

    private fun relationships(vararg entries: String) =
        """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${entries.joinToString("")}</Relationships>"""

    private fun relation(id: String, type: String, target: String) = """<Relationship Id="$id" Type="$REL/$type" Target="$target"/>"""

    private fun slide(text: String) = """<p:sld xmlns:p="$SLIDES" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
        <p:cSld><a:p><a:r><a:t>$text</a:t></a:r></a:p></p:cSld></p:sld>"""

    private fun openDocument(content: String) = """<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
        xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0"
        xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"><office:body>$content</office:body></office:document-content>"""

    private companion object {
        const val WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        const val SHEET = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        const val SLIDES = "http://schemas.openxmlformats.org/presentationml/2006/main"
        const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    }
}
