package io.github.odinreader.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderLineSpacingTest {
    @Test
    fun keepsTheExistingDefaultAndAllowsCompactAndGenerousSpacing() {
        for (value in listOf(1.2f, 1.4f, 1.65f, 2f, 2.4f)) {
            assertEquals(value, ReaderLineSpacing.sanitize(value), 0f)
        }
        assertEquals(1.65f, ReaderLineSpacing.Default, 0f)
    }

    @Test
    fun invalidPreferencesCannotReachNativeTextLayout() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(ReaderLineSpacing.Default, ReaderLineSpacing.sanitize(value), 0f)
        }
        assertEquals(ReaderLineSpacing.Minimum, ReaderLineSpacing.sanitize(-1f), 0f)
        assertEquals(ReaderLineSpacing.Maximum, ReaderLineSpacing.sanitize(100f), 0f)
    }
}
