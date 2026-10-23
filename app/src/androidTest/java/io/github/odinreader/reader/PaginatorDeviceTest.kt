package io.github.odinreader.reader

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.graphics.Paint
import android.os.Build
import android.text.Layout
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Measures the actual Android renderer; JVM boundary tests cannot cover this. */
@RunWith(AndroidJUnit4::class)
class PaginatorDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun mixedChineseLatinAndEmojiFitAtEveryReaderFontSize() {
        val paragraph = "山风穿过书页，月光照着回家的路。中文标点：你好，世界！“山海之间”。" +
            " The quick brown fox jumps over the lazy dog. Words wrap across lines. " +
            "书本📚 星光✨ 旅行👩‍🚀 山川🏔️ 家庭👨‍👩‍👧‍👦。\n\n"
        val text = paragraph.repeat(40)
        for (sizeSp in listOf(14f, 20f, 32f)) {
            assertRenderedPages(text, widthPx = 1690, heightPx = 790, textSizePx = sp(sizeSp), case = "mixed $sizeSp sp")
            assertRenderedPages(text, widthPx = 740, heightPx = 360, textSizePx = sp(sizeSp), case = "compact $sizeSp sp")
        }
    }

    @Test
    fun longParagraphCrossesChunkBoundariesWithoutLostOrClippedText() {
        // No newline near the 16K measurement boundary. Include surrogate and ZWJ sequences.
        val text = ("沿着山脊去看海，读书使旅途变得辽阔。👩‍🚀📚 " +
            "A_long_unbroken_word_abcdefghijklmnopqrstuvwx travels through the same paragraph. ").repeat(420)
        assertTrue(text.length > 32_768)
        assertRenderedPages(text, widthPx = 1100, heightPx = 620, textSizePx = sp(20f), case = "long paragraph")
    }

    @Test
    fun explicitBlankLinesAndEmptyContentArePreserved() {
        val text = "\n\n第一段。\n\n\nSecond paragraph. 📚\n\n最后一段。\n\n"
        assertRenderedPages(text, widthPx = 740, heightPx = 360, textSizePx = sp(32f), case = "blank lines")
        val empty = Paginator.paginate("", 740, 360, sp(20f))
        assertEquals(listOf(Page(0, 0, "")), empty)
        assertRenderedPages(" \n\n   \n", widthPx = 740, heightPx = 360, textSizePx = sp(20f), case = "whitespace only")
    }

    @Test
    fun reflowFindsTheSameCharacterAtLargeFontAndNarrowViewport() {
        val text = ("每天留一点时间，把注意力交还给眼前的文字。A quiet page holds a whole world. 📖\n\n").repeat(120)
        val original = Paginator.paginate(text, 1690, 790, sp(20f))
        val offset = original[original.size / 2].startOffset + 3
        val reflowed = Paginator.paginate(text, 740, 360, sp(32f))
        val index = Paginator.pageIndexAtOffset(reflowed, offset)
        assertTrue(offset >= reflowed[index].startOffset)
        assertTrue(offset < reflowed[index].endOffset)
        assertEquals(text, reflowed.joinToString("") { it.text })
    }

    @Test
    fun bundledFontsRenderMixedScriptsWithoutLostOrClippedText() {
        val text = ("山风吹过书页，繁體漢字與中文標點：“你好，世界！”\n" +
            "The quick brown fox reads 0123456789. JetBrains Nerd: \uE0B0 \uF120 \uF013. " +
            "书本📚 旅行👩‍🚀 星光✨。\n\n").repeat(12)
        for (font in ReaderFont.entries) {
            val face = runBlocking { ReaderFonts.load(context, font) }
            if (font == ReaderFont.JetBrainsMono) {
                val paint = Paint().apply { typeface = face }
                for (icon in listOf("\uE0B0", "\uF120", "\uF013")) {
                    assertTrue("The bundled JetBrains face must retain Nerd Font icons", paint.hasGlyph(icon))
                }
            }
            for (size in listOf(14f, 20f, 32f)) {
                assertRenderedPages(text, widthPx = 1100, heightPx = 620,
                    textSizePx = sp(size), case = "${font.id} $size sp", typeface = face)
            }
        }
    }

    @Test
    fun changingFontKeepsTheSameCharacterWithinItsReflowedPage() {
        val text = ("沿着山脊去看海，阅读让旅途变得辽阔。A quiet page holds a whole world. 📖\n\n").repeat(65)
        val original = Paginator.paginate(text, 1100, 620, sp(20f))
        val offset = original[original.size / 2].startOffset + 3
        for (font in ReaderFont.entries) {
            val face = runBlocking { ReaderFonts.load(context, font) }
            val pages = Paginator.paginate(text, 1100, 620, sp(20f), typeface = face)
            val page = pages[Paginator.pageIndexAtOffset(pages, offset)]
            assertTrue("${font.id}: restored page must contain the original character", offset in page.startOffset until page.endOffset)
            assertEquals(text, pages.joinToString("") { it.text })
        }
    }

    @Test
    fun independentChineseAndEnglishFontsFitAndKeepTheSameCharacter() {
        val text = ("中文与English 123混排，繁體漢字：‘阅读’。\uD840\uDC00\uFE00 " +
            "A reader's ‘quiet’ page, cafe\u0301 and Nerd \uF120 \uE0B0. 👩‍🚀📚\n\n").repeat(16)
        val original = Paginator.paginate(text, 1100, 620, sp(20f))
        val anchor = original[original.size / 2].startOffset + 3
        for (chinese in listOf(ReaderFont.LxgwWenKai, ReaderFont.NotoSerif, ReaderFont.NotoSans)) {
            for (english in listOf(ReaderFont.JetBrainsMono, ReaderFont.Literata)) {
                val selection = ReaderFontSelection(chinese, english)
                val typography = runBlocking { ReaderFonts.load(context, selection) }
                for (size in listOf(14f, 20f, 32f)) {
                    val case = "${chinese.id} + ${english.id} $size sp"
                    assertRenderedPages(text, 1100, 620, sp(size), case, typography = typography)
                    val pages = Paginator.paginate(text, 1100, 620, sp(size), typography = typography)
                    val page = pages[Paginator.pageIndexAtOffset(pages, anchor)]
                    assertTrue("$case: the same character must stay in its restored page",
                        anchor in page.startOffset until page.endOffset)
                }
            }
        }
    }

    @Test
    fun mixedTypefaceSpansStayConsistentAcrossMeasurementChunksAndRenderedPages() {
        val text = ("山海之间“慢慢阅读”，中文與English文字交错。 " +
            "English ‘quoted’ words, cafe\u0301, Nerd \uF120 and emoji 👩‍🚀📚. ").repeat(430)
        assertTrue(text.length > 32_768)
        val typography = runBlocking {
            ReaderFonts.load(context, ReaderFontSelection(ReaderFont.LxgwWenKai, ReaderFont.JetBrainsMono))
        }
        assertRenderedPages(text, 1100, 620, sp(20f), "mixed font chunk boundaries", typography = typography)
    }

    @Test
    fun letterSpacingExtremesMatchNativeMixedFontsAtSmallAndLargeSizes() {
        val text = ("山海之间‘阅读’，繁體漢字与English 123保持连续。" +
            "JetBrains Nerd \uF120 \uE0B0, cafe\u0301, spaces and punctuation. 👩‍🚀📚\n\n").repeat(24)
        val typography = runBlocking {
            ReaderFonts.load(context, ReaderFontSelection(ReaderFont.LxgwWenKai, ReaderFont.JetBrainsMono))
        }
        val original = Paginator.paginate(text, 1100, 620, sp(20f), typography = typography)
        val anchor = original[original.size / 2].startOffset + 3
        for (size in listOf(14f, 32f)) {
            for (spacing in listOf(0f, 0.2f)) {
                val case = "mixed fonts $size sp letters=$spacing em"
                assertRenderedPages(text, 740, 620, sp(size), case,
                    typography = typography, letterSpacing = spacing, verifyWidths = true)
                val pages = Paginator.paginate(text, 740, 620, sp(size),
                    typography = typography, letterSpacing = spacing)
                val restored = pages[Paginator.pageIndexAtOffset(pages, anchor)]
                assertTrue("$case: restore the same UTF-16 character", anchor in restored.startOffset until restored.endOffset)
            }
        }
    }

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, context.resources.displayMetrics)

    private fun assertRenderedPages(
        text: String,
        widthPx: Int,
        heightPx: Int,
        textSizePx: Float,
        case: String,
        typeface: Typeface = Typeface.SERIF,
        typography: ReaderTypography? = null,
        letterSpacing: Float = 0f,
        verifyWidths: Boolean = false,
    ) {
        val pages = Paginator.paginate(text, widthPx, heightPx, textSizePx,
            typeface = typeface, typography = typography, letterSpacing = letterSpacing)
        assertEquals("$case: pagination must preserve every UTF-16 code unit", text, pages.joinToString("") { it.text })
        assertEquals(0, pages.first().startOffset)
        assertEquals(text.length, pages.last().endOffset)
        pages.forEachIndexed { index, page ->
            if (index > 0) assertEquals(pages[index - 1].endOffset, page.startOffset)
            assertFalse("$case: page $index must not begin with half a surrogate", page.text.firstOrNull()?.isLowSurrogate() == true)
            assertFalse("$case: page $index must not end with half a surrogate", page.text.lastOrNull()?.isHighSurrogate() == true)
            val display = typography?.style(text, page.startOffset, page.endOffset) ?: page.text
            assertEquals("$case: applying fonts must preserve page characters", page.text, display.toString())
            val measurement = measureTextView(display, widthPx, heightPx, textSizePx,
                typography?.englishTypeface ?: typeface, letterSpacing)
            assertEquals("$case: TextView must lay out the entire supplied page", page.text.length, measurement.finalLineEnd)
            // A trailing newline can create an extra empty line. Its blank height is not a clipped glyph.
            assertTrue(
                "$case page=$index size=$textSizePx lastGlyphBottom=${measurement.lastGlyphBottom} viewport=$heightPx lines=${measurement.lineCount}",
                measurement.lastGlyphBottom <= heightPx,
            )
            if (verifyWidths) assertTrue(
                "$case page=$index widestLine=${measurement.widestLine} viewport=$widthPx",
                measurement.widestLine <= widthPx + 1f,
            )
        }
        Log.i("OdinReaderValidation", "$case: ${pages.size} pages rendered without clipped glyphs")
    }

    @SuppressLint("InlinedApi") // BREAK_STRATEGY_SIMPLE is the compile-time integer 0.
    @Suppress("DEPRECATION")
    private fun measureTextView(text: CharSequence, widthPx: Int, heightPx: Int, textSizePx: Float, face: Typeface,
        letterSpacing: Float = 0f): TextMeasurement {
        var result: TextMeasurement? = null
        instrumentation.runOnMainSync {
            val view = TextView(context).apply {
                typeface = face
                includeFontPadding = false
                setFallbackLineSpacing(true)
                isElegantTextHeight = true
                if (Build.VERSION.SDK_INT >= 35) {
                    setUseBoundsForWidth(true)
                    setLocalePreferredLineHeightForMinimumUsed(true)
                }
                setPadding(0, 0, 0, 0)
                setLineSpacing(0f, 1.65f)
                this.letterSpacing = letterSpacing
                breakStrategy = android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE
                hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
                gravity = Gravity.TOP or Gravity.START
                textDirection = View.TEXT_DIRECTION_FIRST_STRONG_LTR
                isFocusable = false
                setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
                this.text = text
            }
            view.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, widthPx, heightPx)
            val layout = checkNotNull(view.layout)
            val lastNonBlankLine = (0 until layout.lineCount).lastOrNull { line ->
                text.substring(layout.getLineStart(line), layout.getLineEnd(line)).any { !it.isWhitespace() }
            }
            val lastGlyphBottom = lastNonBlankLine?.let { line ->
                layout.getLineBaseline(line) + layout.getLineDescent(line)
            } ?: 0
            result = TextMeasurement(
                lastGlyphBottom,
                layout.lineCount,
                layout.getLineEnd(layout.lineCount - 1),
                (0 until layout.lineCount).maxOfOrNull { line ->
                    val start = layout.getLineStart(line)
                    val end = layout.getLineEnd(line)
                    var visibleEnd = end
                    while (visibleEnd > start && text[visibleEnd - 1].isWhitespace()) visibleEnd--
                    // Android deliberately counts trailing whitespace on its LAST
                    // line even in getLineMax. Keep the original styled range and
                    // paint, but exclude those invisible characters from width.
                    if (line == layout.lineCount - 1 && visibleEnd != end) {
                        if (visibleEnd == start) 0f else Layout.getDesiredWidth(text, start, visibleEnd, view.paint)
                    } else layout.getLineMax(line)
                } ?: 0f,
            )
        }
        return checkNotNull(result)
    }

    private data class TextMeasurement(val lastGlyphBottom: Int, val lineCount: Int, val finalLineEnd: Int, val widestLine: Float)
}
