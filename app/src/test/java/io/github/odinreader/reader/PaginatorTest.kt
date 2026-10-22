package io.github.odinreader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaginatorTest {
    @Test
    fun groupsWholeMeasuredLinesWithinTheViewport() {
        val collector = PageCollector("abcdef", heightPx = 20)
        collector.addLine(0, 2, 10)
        collector.addLine(2, 4, 10)
        collector.addLine(4, 6, 10)

        val pages = collector.finish()
        assertEquals(listOf("abcd", "ef"), pages.map { it.text })
        assertEquals(listOf(0, 4), pages.map { it.startOffset })
        assertEquals(listOf(4, 6), pages.map { it.endOffset })
    }

    @Test
    fun preservesParagraphsWhitespaceAndSupplementaryUnicode() {
        val text = "第一行\n\n \uD83C\uDF19\n末行"
        val collector = PageCollector(text, heightPx = 20)
        collector.addLine(0, 4, 12)
        collector.addLine(4, 5, 12)
        collector.addLine(5, 9, 12)
        collector.addLine(9, text.length, 12)

        val pages = collector.finish()
        assertEquals(text, pages.joinToString("") { it.text })
        pages.zipWithNext().forEach { (left, right) ->
            assertEquals(left.endOffset, right.startOffset)
        }
        assertTrue(pages.none { it.text.lastOrNull()?.isHighSurrogate() == true })
    }

    @Test
    fun oversizedLineStillMakesProgress() {
        val collector = PageCollector("ab", heightPx = 1)
        collector.addLine(0, 1, 20)
        collector.addLine(1, 2, 20)
        assertEquals(listOf("a", "b"), collector.finish().map { it.text })
    }

    @Test
    fun emptyTextProducesAnEmptyPage() {
        assertEquals(listOf(Page(0, 0, "")), PageCollector("", 20).finish())
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesGapsInTheOriginalText() {
        PageCollector("abc", 20).addLine(1, 2, 10)
    }

    @Test
    fun resolvesThePageContainingAnOffsetIncludingBoundaries() {
        val pages = listOf(Page(0, 3, "abc"), Page(3, 6, "def"), Page(6, 8, "gh"))
        assertEquals(0, Paginator.pageIndexAtOffset(pages, -1))
        assertEquals(0, Paginator.pageIndexAtOffset(pages, 2))
        assertEquals(1, Paginator.pageIndexAtOffset(pages, 3))
        assertEquals(2, Paginator.pageIndexAtOffset(pages, 8))
        assertEquals(2, Paginator.pageIndexAtOffset(pages, Int.MAX_VALUE))
        assertEquals(0, Paginator.pageIndexAtOffset(emptyList(), 0))
    }
}
