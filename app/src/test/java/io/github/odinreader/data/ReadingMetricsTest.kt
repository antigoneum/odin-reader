package io.github.odinreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingMetricsTest {
    @Test
    fun textFractionUsesWholeBookCharactersAndKeepsCharacterAnchorAcrossReflow() {
        val content = textBook(1_000, 2_000)
        val first = ReadingMetrics.position(content, ReadingProgress(1, 2, 500))
        val reflowed = ReadingMetrics.position(content, ReadingProgress(1, 9, 500))
        assertEquals(1_500.0, first.positionUnits, 0.0)
        assertEquals(3_000.0, first.totalUnits, 0.0)
        assertEquals(0.5, first.fraction, 0.00001)
        assertEquals(first, reflowed)
    }

    @Test
    fun lastVisiblePageCompletesTheBookWithoutChangingItsResumeAnchor() {
        val content = textBook(1_000)
        val bookmark = ReadingProgress(0, 3, 750, completed = true)
        val record = ReadingMetrics.initialize(content, ReadingRecord("book", progress = bookmark))
        assertEquals(bookmark, record.progress)
        assertEquals(1.0, record.progressFraction, 0.0)
        assertEquals(0L, record.estimatedRemainingMillis)
    }

    @Test
    fun goingBackChangesDisplayedPercentageButNeverUnreadsTheSpeedFrontier() {
        val content = textBook(1_000, 2_000)
        val record = ReadingMetrics.initialize(content, ReadingRecord("book", ReadingProgress(1, 2, 500)))
        val back = ReadingMetrics.withProgress(content, record, ReadingProgress(0, 0, 100), 60_000L)
        assertEquals(100.0 / 3_000, back.progressFraction, 0.00001)
        assertEquals(1_500.0, back.furthestUnits, 0.0)
        assertEquals(0.0, back.speedUnits, 0.0)
    }

    @Test
    fun pdfFractionsUseOriginalPagesRatherThanCharacterOffsets() {
        val content = pdfBook(10)
        assertEquals(0.1, ReadingMetrics.position(content, ReadingProgress()).fraction, 0.00001)
        assertEquals(0.5, ReadingMetrics.position(content, ReadingProgress(pageIndex = 4, charOffset = 900)).fraction, 0.00001)
        assertEquals(1.0, ReadingMetrics.position(content, ReadingProgress(pageIndex = 9)).fraction, 0.0)
    }

    @Test
    fun defaultEstimatesAreAvailableBeforeEnoughPersonalSamples() {
        assertEquals(60_000L, ReadingMetrics.remainingMillis("TXT", 500.0, 1_000.0))
        assertEquals(810_000L, ReadingMetrics.remainingMillis("PDF", 1.0, 10.0))
        assertEquals(60_000L, ReadingMetrics.remainingMillis("TXT", 500.0, 1_000.0, 500.0, 29_999L))
        assertNull(ReadingMetrics.remainingMillis("TXT", 0.0, 0.0))
    }

    @Test
    fun aSustainedSequentialPageLearnsPersonalReadingSpeed() {
        val content = textBook(10_000)
        val record = ReadingMetrics.initialize(content, ReadingRecord("book"))
        val next = ReadingMetrics.withProgress(content, record, ReadingProgress(0, 1, 1_000), 60_000L)
        assertEquals(1_000.0, next.speedUnits, 0.0)
        assertEquals(60_000L, next.speedReadingMillis)
        assertEquals(540_000L, next.estimatedRemainingMillis)
    }

    @Test
    fun rapidFlipsAndArbitraryPageJumpsDoNotTeachAnExtremeSpeed() {
        val content = textBook(10_000)
        val record = ReadingMetrics.initialize(content, ReadingRecord("book"))
        val rapid = ReadingMetrics.withProgress(content, record, ReadingProgress(0, 1, 500), 2_000L)
        val jump = ReadingMetrics.withProgress(content, record, ReadingProgress(0, 4, 2_000), 60_000L)
        assertEquals(0.0, rapid.speedUnits, 0.0)
        assertEquals(0L, rapid.speedReadingMillis)
        assertEquals(0.0, jump.speedUnits, 0.0)
        assertEquals(1_140_000L, rapid.estimatedRemainingMillis)
    }

    @Test
    fun fontReflowToTheLastPageCannotBeMistakenForReadingForward() {
        val content = textBook(1_000)
        val record = ReadingMetrics.initialize(content, ReadingRecord("book", ReadingProgress(0, 2, 750)))
        // A font change can put this same paragraph on the final page at a new page index.
        val reflowed = ReadingMetrics.withProgress(content, record,
            ReadingProgress(0, 3, 750, completed = true), 60_000L)
        assertEquals(1.0, reflowed.progressFraction, 0.0)
        assertEquals(0.0, reflowed.speedUnits, 0.0)
        assertEquals(0L, reflowed.speedReadingMillis)
    }

    @Test
    fun chapterShoulderJumpIsNeverMistakenForSequentialReading() {
        val content = textBook(1_000, 2_000)
        val record = ReadingMetrics.initialize(content, ReadingRecord("book"))
        val jump = ReadingMetrics.withProgress(content, record, ReadingProgress(1, 0, 0), 60_000L)
        assertEquals(0.0, jump.speedUnits, 0.0)
        assertEquals(0L, jump.speedReadingMillis)
        assertEquals(1_000.0, jump.furthestUnits, 0.0)
    }

    @Test
    fun rereadingAndLongIdleIntervalsDoNotDistortTheLearnedRate() {
        val content = textBook(10_000)
        val rereading = ReadingMetrics.initialize(content, ReadingRecord("book", furthestUnits = 2_000.0))
        val next = ReadingMetrics.withProgress(content, rereading, ReadingProgress(0, 1, 500), 60_000L)
        val idle = ReadingMetrics.withProgress(content, ReadingMetrics.initialize(content, ReadingRecord("book")),
            ReadingProgress(0, 1, 500), 11 * 60_000L)
        assertEquals(0.0, next.speedUnits, 0.0)
        assertEquals(0.0, idle.speedUnits, 0.0)
    }

    @Test
    fun pdfRateLearnsOnePageAtATimeAndRejectsChapterStyleSkips() {
        val content = pdfBook(20)
        val record = ReadingMetrics.initialize(content, ReadingRecord("book"))
        val next = ReadingMetrics.withProgress(content, record, ReadingProgress(pageIndex = 1), 60_000L)
        val skipped = ReadingMetrics.withProgress(content, record, ReadingProgress(pageIndex = 10), 60_000L)
        assertEquals(1.0, next.speedUnits, 0.0)
        assertEquals(1_080_000L, next.estimatedRemainingMillis)
        assertEquals(0.0, skipped.speedUnits, 0.0)
    }

    @Test
    fun activeClockCountsEachForegroundIntervalOnceAndExcludesBackground() {
        val clock = ActiveReadingClock()
        assertEquals(0L, clock.setActive(true, 1_000L))
        assertEquals(1_000L, clock.advance(2_000L))
        assertEquals(0L, clock.advance(2_000L))
        assertEquals(500L, clock.setActive(false, 2_500L))
        assertEquals(0L, clock.advance(9_999L))
        assertEquals(0L, clock.setActive(true, 10_000L))
        assertEquals(1_000L, clock.advance(11_000L))
        assertEquals(1_000L, clock.setActive(false, 12_000L))
        assertEquals(0L, clock.setActive(false, 13_000L))
    }

    @Test
    fun backwardsClockValuesCannotProduceNegativeOrDuplicatedReadingTime() {
        val clock = ActiveReadingClock()
        clock.setActive(true, 1_000L)
        assertEquals(0L, clock.advance(900L))
        assertEquals(500L, clock.advance(1_500L))
        assertEquals(0L, clock.advance(1_400L))
        assertEquals(500L, clock.advance(2_000L))
    }

    @Test
    fun malformedValuesAreBoundedAndHugeEstimatesCannotOverflow() {
        assertEquals(1.0, ReadingMetrics.position(textBook(10, 20), ReadingProgress(99, 99, Int.MAX_VALUE)).fraction, 0.0)
        assertEquals(0.0, ReadingMetrics.position(textBook(10), ReadingProgress(-1, -1, -100)).fraction, 0.0)
        assertNull(ReadingMetrics.remainingMillis("TXT", Double.NaN, 100.0))
        assertEquals(Long.MAX_VALUE, ReadingMetrics.saturatingAdd(Long.MAX_VALUE - 1L, 50L))
        assertTrue(ReadingMetrics.remainingMillis("PDF", 0.0, Double.MAX_VALUE)!! > 0L)
    }

    private fun textBook(vararg sizes: Int): BookContent = BookContent(
        Book("book", "Test", "Author", "TXT", 0L, sizes.size),
        sizes.mapIndexed { index, size -> BookChapter("Chapter $index", "文".repeat(size)) },
    )

    private fun pdfBook(pages: Int): BookContent = BookContent(
        Book("book", "PDF", "Author", "PDF", 0L, pages), emptyList(),
    )
}
