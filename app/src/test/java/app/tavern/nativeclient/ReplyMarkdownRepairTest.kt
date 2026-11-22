package app.tavern.nativeclient

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplyMarkdownRepairTest {
    @Test
    fun `paired emphasis spacing is normalized and incomplete streaming markers are display only`() {
        assertEquals("她**说话**了，_很轻_。", repairReplyMarkdown("她** 说话 **了，_ 很轻 _。"))
        assertEquals("*停在这里", repairReplyMarkdown("*停在这里"))
        assertEquals("*停在这里*", repairReplyMarkdown("*停在这里", forDisplay = true))
        assertEquals("\"停在这里\"", repairReplyMarkdown("\"停在这里", forDisplay = true))
    }

    @Test
    fun `literal emphasis in lists rules and code is left alone`() {
        val source = "* 列表\n***\n```text\n** literal **\n```\n`* literal *`\n<span>** literal **</span>\n"
        assertEquals(source, repairReplyMarkdown(source, forDisplay = true))
    }
}
