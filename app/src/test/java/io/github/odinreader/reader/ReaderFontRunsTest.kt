package io.github.odinreader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderFontRunsTest {
    @Test
    fun mixedChineseLatinDigitsAndNerdSymbolsUseTheirOwnFonts() {
        val text = "山海 Reader 123，Nerd:\uF120\uE0B0!"
        assertEquals(listOf("山海", "，"), chinesePieces(text))
        assertEquals(text, ReaderFontRuns.runs(text).joinToString("") { text.substring(it.start, it.end) })
        val glyphs = scriptsByCodeUnit(text)
        assertEquals(ReaderScript.English, glyphs[text.indexOf('R')])
        assertEquals(ReaderScript.English, glyphs[text.indexOf('1')])
        assertEquals(ReaderScript.English, glyphs[text.indexOf('\uF120')])
    }

    @Test
    fun chinesePunctuationFullWidthAndOtherCjkScriptsUseChineseFace() {
        val text = "《山海》、。（ＡＢＣ１２３）\uFE10\uFE35かなカナ한글ㄅ"
        assertEquals(listOf(text), chinesePieces(text))
        assertEquals(listOf(ReaderFontRun(0, text.length, ReaderScript.Chinese)), ReaderFontRuns.runs(text))
    }

    @Test
    fun supplementaryHanAndVariationSelectorStayInOneUtf16Run() {
        val text = "A\uD840\uDC00\uDB40\uDD00B"
        val runs = ReaderFontRuns.runs(text)
        assertEquals(listOf(ReaderFontRun(0, 1, ReaderScript.English),
            ReaderFontRun(1, 5, ReaderScript.Chinese), ReaderFontRun(5, 6, ReaderScript.English)), runs)
        assertFalse(runs.any { it.start in listOf(2, 4) || it.end in listOf(2, 4) })
    }

    @Test
    fun combiningMarksInheritTheirBaseEvenAtRequestedRangeStart() {
        val text = "中\u0301\uFE0F e\u0301"
        assertEquals(listOf("中\u0301\uFE0F"), chinesePieces(text))
        assertEquals(listOf(ReaderFontRun(1, 3, ReaderScript.Chinese)), ReaderFontRuns.runs(text, 1, 3))
        assertEquals(listOf(ReaderFontRun(5, 6, ReaderScript.English)), ReaderFontRuns.runs(text, 5, 6))
    }

    @Test
    fun joinedEmojiSkinToneAndKeycapsAreNotSplitByChineseTypefaceSpans() {
        val emoji = "👩🏽‍🚀 1\uFE0F\u20E3 🏳️‍🌈"
        val text = "中${emoji}文"
        assertEquals(listOf(ReaderFontRun(0, 1, ReaderScript.Chinese),
            ReaderFontRun(1, 1 + emoji.length, ReaderScript.English),
            ReaderFontRun(1 + emoji.length, text.length, ReaderScript.Chinese)), ReaderFontRuns.runs(text))
    }

    @Test
    fun smartQuotesAndSharedPunctuationFollowTheNearbyLanguage() {
        val text = "“山海” ‘中文’ “English” don’t 中文·姓名 English—words"
        assertEquals(listOf("“山海”", "‘中文’", "中文·姓名"), chinesePieces(text))
        val scripts = scriptsByCodeUnit(text)
        assertEquals(ReaderScript.English, scripts[text.indexOf("English") - 1])
        assertEquals(ReaderScript.English, scripts[text.indexOf('’', text.indexOf("don"))])
    }

    @Test
    fun chunkAndPageRangesProduceTheSameCharacterAssignmentsAsTheWholeChapter() {
        val text = "中\u0301 ‘山海’ \uD840\uDC00\uDB40\uDD00 English “words” don’t 👩🏽‍🚀，123\uF120末"
        val whole = scriptsByCodeUnit(text)
        val boundaries = mutableListOf(0)
        var cursor = 0
        while (cursor < text.length) {
            cursor += Character.charCount(text.codePointAt(cursor))
            boundaries += cursor
        }
        for (start in boundaries) {
            for (end in boundaries.filter { it >= start }) {
                val ranged = ReaderFontRuns.runs(text, start, end).flatMap { run ->
                    List(run.end - run.start) { run.script }
                }
                assertEquals("Range $start..$end must retain chapter context", whole.subList(start, end), ranged)
            }
        }
    }

    @Test
    fun whitespaceAndAsciiPunctuationRemainEnglishWhileEmptyRangesStayEmpty() {
        val text = "中文 \n,.;:!? 123"
        assertEquals(listOf("中文"), chinesePieces(text))
        assertTrue(ReaderFontRuns.runs("").isEmpty())
        assertTrue(ReaderFontRuns.runs(text, 2, 2).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesRangesThatSplitSupplementaryCodePoints() {
        ReaderFontRuns.runs("\uD840\uDC00", 1, 2)
    }

    private fun chinesePieces(text: String): List<String> = ReaderFontRuns.runs(text)
        .filter { it.script == ReaderScript.Chinese }.map { text.substring(it.start, it.end) }

    private fun scriptsByCodeUnit(text: String): List<ReaderScript> = ReaderFontRuns.runs(text).flatMap { run ->
        List(run.end - run.start) { run.script }
    }
}
