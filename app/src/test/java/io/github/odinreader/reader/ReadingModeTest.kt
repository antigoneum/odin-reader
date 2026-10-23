package io.github.odinreader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingModeTest {
    @Test
    fun preferencesHaveStableIdsAndUnknownValuesKeepTheOriginalMode() {
        for (mode in ReadingMode.entries) assertEquals(mode, ReadingMode.fromPreference(mode.id))
        assertEquals(ReadingMode.Single, ReadingMode.fromPreference(null))
        assertEquals(ReadingMode.Single, ReadingMode.fromPreference("future-mode"))
        assertEquals(ReadingMode.DualColumn, ReadingMode.fromPreference("dual_page"))
        assertEquals(listOf("single", "dual_column", "preview", "zoom_preview"), ReadingMode.entries.map { it.id })
        for (animation in PageTurnAnimation.entries) {
            assertEquals(animation, PageTurnAnimation.fromPreference(animation.id, PageTurnAnimation.None))
        }
        assertEquals(PageTurnAnimation.ZoomSlide,
            PageTurnAnimation.fromPreference("invalid", ReadingMode.ZoomPreview.defaultAnimation))
    }

    @Test
    fun spreadAndPreviewGeometryKeepOneCompleteMeasuredPage() {
        assertEquals(1200, ReadingMode.Single.pageWidthPx(1200, 20))
        assertEquals(590, ReadingMode.DualColumn.pageWidthPx(1200, 20))
        assertEquals(786, ReadingMode.Preview.pageWidthPx(1200, 20))
        assertEquals(786, ReadingMode.ZoomPreview.pageWidthPx(1200, 20))
        for (mode in ReadingMode.entries) assertTrue(mode.pageWidthPx(1, 20) >= 1)
    }

    @Test
    fun previewsNeverCountAsASecondReadPage() {
        for (mode in listOf(ReadingMode.Preview, ReadingMode.ZoomPreview)) {
            assertTrue(mode.hasPreview)
            assertEquals(1, mode.pageStep)
            assertEquals(3, mode.visibleLastIndex(3, 5))
            assertEquals(4, mode.visibleLastIndex(4, 5))
        }
        assertFalse(ReadingMode.DualColumn.hasPreview)
        assertEquals(4, ReadingMode.DualColumn.visibleLastIndex(3, 5))
    }

    @Test
    fun oddAndEvenChaptersUseTheLastReadableSpreadWithoutSkippingText() {
        for (mode in listOf(ReadingMode.DualColumn)) {
            assertEquals(0, mode.finalSpreadStart(1))
            assertEquals(0, mode.finalSpreadStart(2))
            assertEquals(2, mode.finalSpreadStart(3))
            assertEquals(2, mode.finalSpreadStart(4))
            assertEquals(4, mode.finalSpreadStart(5))
            assertEquals(4, mode.visibleLastIndex(4, 5))
            assertEquals(4, mode.visibleLastIndex(3, 5))
        }
        assertEquals(3, ReadingMode.Preview.finalSpreadStart(4))
    }

    @Test
    fun aRestoredOddLeafCanReturnToTheFirstLeafBeforeLeavingTheChapter() {
        for (mode in listOf(ReadingMode.DualColumn)) {
            assertEquals(-1, mode.previousStart(0))
            assertEquals(0, mode.previousStart(1))
            assertEquals(0, mode.previousStart(2))
            assertEquals(1, mode.previousStart(3))
        }
        assertEquals(2, ReadingMode.Preview.previousStart(3))
    }

    @Test
    fun dualColumnAnimationKeepsExplicitChoicesBeforeLegacyChoices() {
        val mode = ReadingMode.DualColumn
        assertEquals(PageTurnAnimation.None,
            mode.animationFromPreferences("none", "page_turn", "dual_page"))
        assertEquals(PageTurnAnimation.Fade,
            mode.animationFromPreferences(null, "fade", "dual_page"))
        assertEquals(PageTurnAnimation.ZoomSlide,
            mode.animationFromPreferences("unknown", "zoom_slide", "dual_page"))
        assertEquals(PageTurnAnimation.Fade,
            mode.animationFromPreferences(null, "fade", "single"))
    }

    @Test
    fun legacyDefaultsApplyOnlyToTheReplacementMode() {
        assertEquals(PageTurnAnimation.PageTurn,
            ReadingMode.DualColumn.animationFromPreferences(null, null, "dual_page"))
        assertEquals(PageTurnAnimation.PageTurn,
            ReadingMode.DualColumn.animationFromPreferences("unknown", "invalid", "dual_page"))
        assertEquals(PageTurnAnimation.Slide,
            ReadingMode.DualColumn.animationFromPreferences(null, null, "dual_column"))
        for (mode in listOf(ReadingMode.Single, ReadingMode.Preview, ReadingMode.ZoomPreview)) {
            assertEquals(mode.defaultAnimation,
                mode.animationFromPreferences(null, "page_turn", "dual_page"))
        }
    }
}
