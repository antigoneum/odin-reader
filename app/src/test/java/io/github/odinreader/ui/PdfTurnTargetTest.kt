package io.github.odinreader.ui

import io.github.odinreader.reader.ReadingMode
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfTurnTargetTest {
    @Test
    fun spreadsVisitOddFinalPagesAndStopAtTheLastFullyDisplayedSpread() {
        assertEquals(2, pdfTurnTarget(0, 5, ReadingMode.DualColumn, 1))
        assertEquals(4, pdfTurnTarget(2, 5, ReadingMode.DualColumn, 1))
        assertEquals(4, pdfTurnTarget(4, 5, ReadingMode.DualColumn, 1))
        assertEquals(2, pdfTurnTarget(4, 5, ReadingMode.DualColumn, -1))
        assertEquals(2, pdfTurnTarget(2, 4, ReadingMode.DualColumn, 1))
        assertEquals(0, pdfTurnTarget(0, 2, ReadingMode.DualColumn, 1))
    }

    @Test
    fun aVisiblePreviewNeverSkipsItsFullReadingPage() {
        for (mode in listOf(ReadingMode.Single, ReadingMode.Preview, ReadingMode.ZoomPreview)) {
            assertEquals(1, pdfTurnTarget(0, 5, mode, 1))
            assertEquals(4, pdfTurnTarget(3, 5, mode, 1))
            assertEquals(4, pdfTurnTarget(4, 5, mode, 1))
            assertEquals(3, pdfTurnTarget(4, 5, mode, -1))
        }
    }

    @Test
    fun selectedOddPageIsPreservedAndFastJumpsRemainTenOriginalPages() {
        assertEquals(3, pdfTurnTarget(1, 20, ReadingMode.DualColumn, 1))
        assertEquals(0, pdfTurnTarget(1, 20, ReadingMode.DualColumn, -1))
        assertEquals(10, pdfTurnTarget(0, 20, ReadingMode.DualColumn, 10))
        assertEquals(19, pdfTurnTarget(0, 20, ReadingMode.DualColumn, Int.MAX_VALUE))
        assertEquals(0, pdfTurnTarget(19, 20, ReadingMode.DualColumn, Int.MIN_VALUE))
    }
}
