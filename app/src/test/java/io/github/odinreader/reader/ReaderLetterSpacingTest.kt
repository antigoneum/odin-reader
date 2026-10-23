package io.github.odinreader.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderLetterSpacingTest {
    @Test
    fun preservesNormalTrackingAndTheSupportedRange() {
        for (value in listOf(0f, 0.01f, 0.1f, 0.2f)) {
            assertEquals(value, ReaderLetterSpacing.sanitize(value), 0f)
        }
        assertEquals(0f, ReaderLetterSpacing.Default, 0f)
    }

    @Test
    fun malformedPreferencesAndExtremeValuesStaySafeForLayout() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(ReaderLetterSpacing.Default, ReaderLetterSpacing.sanitize(value), 0f)
        }
        assertEquals(0f, ReaderLetterSpacing.sanitize(-1f), 0f)
        assertEquals(0.2f, ReaderLetterSpacing.sanitize(100f), 0f)
    }
}
