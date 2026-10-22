package io.github.odinreader.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderFontSelectionTest {
    @Test
    fun oldChineseFontKeepsBothScriptsUntilTheyAreChangedSeparately() {
        assertEquals(ReaderFontSelection(ReaderFont.NotoSerif, ReaderFont.NotoSerif),
            ReaderFontSelection.fromPreferences(null, null, ReaderFont.NotoSerif.id))
    }

    @Test
    fun oldLatinOnlyFontRetainsTheChineseSystemFallback() {
        for (font in listOf(ReaderFont.Literata, ReaderFont.JetBrainsMono)) {
            assertEquals(ReaderFontSelection(ReaderFont.SystemSans, font),
                ReaderFontSelection.fromPreferences(null, null, font.id))
        }
    }

    @Test
    fun eachNewPreferenceOverridesOnlyItsOwnLanguage() {
        assertEquals(ReaderFontSelection(ReaderFont.LxgwWenKai, ReaderFont.Literata),
            ReaderFontSelection.fromPreferences(ReaderFont.LxgwWenKai.id, null, ReaderFont.Literata.id))
        assertEquals(ReaderFontSelection(ReaderFont.NotoSerif, ReaderFont.JetBrainsMono),
            ReaderFontSelection.fromPreferences(null, ReaderFont.JetBrainsMono.id, ReaderFont.NotoSerif.id))
    }

    @Test
    fun missingAndUnknownPreferencesHaveSafeIndependentFallbacks() {
        assertEquals(ReaderFontSelection(), ReaderFontSelection.fromPreferences(null, null, null))
        assertEquals(ReaderFontSelection(), ReaderFontSelection.fromPreferences("unknown", "unknown", "unknown"))
        assertEquals(ReaderFontSelection(ReaderFont.NotoSans, ReaderFont.JetBrainsMono),
            ReaderFontSelection.fromPreferences("unknown", ReaderFont.JetBrainsMono.id, ReaderFont.NotoSans.id))
        assertEquals(ReaderFontSelection(ReaderFont.SystemSerif, ReaderFont.SystemSerif),
            ReaderFontSelection.fromPreferences(ReaderFont.JetBrainsMono.id, null, null))
    }
}
