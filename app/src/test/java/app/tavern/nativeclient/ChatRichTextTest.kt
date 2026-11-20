package app.tavern.nativeclient

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRichTextTest {

    @Test
    fun `parseRichBlocks parses headings lists quotes rules tables and code blocks`() {
        val md = """
            # 第一章
            ## 子标题

            > 这是一段引用
            > 第二行引用

            - 项目一
            * 项目二

            1. 步骤一
            2) 步骤二

            ---

            | 角色 | 好感 |
            | --- | :---: |
            | 艾琳 | 80 |

            ```kotlin
            val hp = 100
            ```
        """.trimIndent()

        val blocks = parseRichBlocks(md, renderHtml = true)
        assertTrue(blocks[0] is RichBlock.Heading && (blocks[0] as RichBlock.Heading).level == 1)
        assertTrue(blocks[1] is RichBlock.Heading && (blocks[1] as RichBlock.Heading).level == 2)
        assertTrue(blocks[2] is RichBlock.BlockQuote && (blocks[2] as RichBlock.BlockQuote).lines.size == 2)
        assertTrue(blocks[3] is RichBlock.BulletList && (blocks[3] as RichBlock.BulletList).items == listOf("项目一", "项目二"))
        assertTrue(blocks[4] is RichBlock.OrderedList && (blocks[4] as RichBlock.OrderedList).items.size == 2)
        assertTrue(blocks[5] is RichBlock.HorizontalRule)
        val table = blocks[6] as RichBlock.MarkdownTable
        assertEquals(listOf("角色", "好感"), table.headers)
        assertEquals(listOf(listOf("艾琳", "80")), table.rows)
        val code = blocks[7] as RichBlock.CodeBlock
        assertEquals("kotlin", code.language)
        assertEquals("val hp = 100", code.code)
    }

    @Test
    fun `parseRichBlocks parses details and html status cards safely`() {
        val input = """
            她轻轻点头。

            <details open>
            <summary>状态面板</summary>

            - 生命：95/100
            - 魔力：40/50

            </details>

            <style>.card { border: 1px solid #ccc; }</style>
            <div class="card" style="padding:8px"><h3>角色属性</h3><progress value="80" max="100"></progress></div>
        """.trimIndent()

        val blocks = parseRichBlocks(input, renderHtml = true)
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is RichBlock.Paragraph)
        val details = blocks[1] as RichBlock.DetailsBlock
        assertEquals("状态面板", details.summary)
        assertTrue(details.openByDefault)
        assertTrue(details.body.contains("生命：95/100"))

        val htmlCard = blocks[2] as RichBlock.HtmlCardBlock
        assertEquals("角色属性", htmlCard.label)
        assertTrue(htmlCard.rawHtml.contains("<style>"))
        assertTrue(htmlCard.rawHtml.contains("<progress"))
    }

    @Test
    fun `sanitizeCardHtml strips scripts event handlers and external network urls`() {
        val raw = """
            <div onclick="alert(1)" style="background: url('https://evil.com/bg.png')">
                <script>fetch('https://evil.com')</script>
                <img src="https://tracker.example/pixel.png" onerror="steal()" />
                <a href="javascript:alert(2)">link</a>
                <iframe src="https://evil.com"></iframe>
                <span>安全文本</span>
            </div>
        """.trimIndent()

        val cleaned = sanitizeCardHtml(raw, darkTheme = true)
        assertFalse(cleaned.contains("<script", ignoreCase = true))
        assertFalse(cleaned.contains("onclick", ignoreCase = true))
        assertFalse(cleaned.contains("onerror", ignoreCase = true))
        assertFalse(cleaned.contains("<iframe", ignoreCase = true))
        assertFalse(cleaned.contains("https://evil.com", ignoreCase = true))
        assertFalse(cleaned.contains("https://tracker.example", ignoreCase = true))
        assertFalse(cleaned.contains("javascript:", ignoreCase = true))
        assertTrue(cleaned.contains("安全文本"))
        assertTrue(cleaned.contains("color-scheme: dark"))
    }

    @Test
    fun `buildRichInline highlights quotes outside inline code and preserves bold italic`() {
        val quoteColor = Color.Red
        val codeBg = Color.Gray
        val linkColor = Color.Blue
        val text = "他说：「你好，**勇者**」并运行了 `echo \"not quoted\"`。"

        val annotated = buildRichInline(text, highlightQuotes = true, quoteColor = quoteColor, codeBg = codeBg, linkColor = linkColor)
        assertTrue(annotated.text.contains("你好，勇者"))
        assertTrue(annotated.text.contains("echo \"not quoted\""))

        val quoteSpans = annotated.spanStyles.filter { it.item.color == quoteColor }
        assertEquals(1, quoteSpans.size)
        val highlighted = annotated.text.substring(quoteSpans.single().start, quoteSpans.single().end)
        assertEquals("「你好，勇者」", highlighted)
    }
}
