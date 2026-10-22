package io.github.odinreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class BookTextParserTest {
    @Test fun `decodes common Chinese legacy encoding`() {
        val text = "第一章　山中来信\n明月照着回家的路。"
        assertEquals(text, BookTextParser.decode(text.toByteArray(Charset.forName("GB18030"))))
    }

    @Test fun `recognizes utf16 byte order marks before removing null characters`() {
        val bytes = byteArrayOf(0xff.toByte(), 0xfe.toByte()) + "山海之间".toByteArray(Charsets.UTF_16LE)
        assertEquals("山海之间", BookTextParser.decode(bytes))
    }

    @Test fun `chapter boundaries preserve preface and final chapter`() {
        val chapters = BookTextParser.chapters("前面的文字\r\n\r\n第一章 出发\r\n正文一\r\n第二章 海边\r\n正文二")
        assertEquals(listOf("正文", "第一章 出发", "第二章 海边"), chapters.map { it.title })
        assertEquals("正文二", chapters.last().text)
    }

    @Test fun `chapter word within prose does not split a paragraph`() {
        val chapters = BookTextParser.chapters("他翻到第一章，开始慢慢地读。\n这是普通的一行文字。")
        assertEquals(1, chapters.size)
        assertTrue(chapters.single().text.contains("第一章"))
    }

    @Test fun `empty file has no chapters`() {
        assertTrue(BookTextParser.chapters("\uFEFF\r\n  ").isEmpty())
    }

    @Test fun `epub relative references support parent path and preserve plus`() {
        assertEquals("OEBPS/Text/chapter+1.xhtml", EpubPaths.resolve("OEBPS/package", "../Text/chapter+1.xhtml#part"))
        assertEquals("OEBPS/第一章.xhtml", EpubPaths.resolve("OEBPS", "%E7%AC%AC%E4%B8%80%E7%AB%A0.xhtml"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `epub reference cannot escape the archive root`() {
        EpubPaths.resolve("OEBPS", "../../outside.xhtml")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `epub reference cannot load remote resources`() {
        EpubPaths.resolve("", "https://example.com/chapter.xhtml")
    }

    @Test fun `archive names keep literal percent and encoded fragments resolve`() {
        assertEquals("Text/100%.xhtml", EpubPaths.archiveEntry("Text/100%.xhtml"))
        assertEquals(EpubPaths.archiveEntry("Text/a#b.xhtml"), EpubPaths.resolve("Text", "a%23b.xhtml#anchor"))
        assertEquals("Text/a?b.xhtml", EpubPaths.archiveEntry("Text/a?b.xhtml"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `archive names cannot traverse above the root`() {
        EpubPaths.archiveEntry("../outside.xhtml")
    }

}