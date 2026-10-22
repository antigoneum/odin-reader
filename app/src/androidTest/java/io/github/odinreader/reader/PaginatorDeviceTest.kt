package io.github.odinreader.reader

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, context.resources.displayMetrics)

    private fun assertRenderedPages(
        text: String,
        widthPx: Int,
        heightPx: Int,
        textSizePx: Float,
        case: String,
    ) {
        val pages = Paginator.paginate(text, widthPx, heightPx, textSizePx)
        assertEquals("$case: pagination must preserve every UTF-16 code unit", text, pages.joinToString("") { it.text })
        assertEquals(0, pages.first().startOffset)
        assertEquals(text.length, pages.last().endOffset)
        pages.forEachIndexed { index, page ->
            if (index > 0) assertEquals(pages[index - 1].endOffset, page.startOffset)
            assertFalse("$case: page $index must not begin with half a surrogate", page.text.firstOrNull()?.isLowSurrogate() == true)
            assertFalse("$case: page $index must not end with half a surrogate", page.text.lastOrNull()?.isHighSurrogate() == true)
            val measurement = measureTextView(page.text, widthPx, heightPx, textSizePx)
            assertEquals("$case: TextView must lay out the entire supplied page", page.text.length, measurement.finalLineEnd)
            // A trailing newline can create an extra empty line. Its blank height is not a clipped glyph.
            assertTrue(
                "$case page=$index size=$textSizePx lastGlyphBottom=${measurement.lastGlyphBottom} viewport=$heightPx lines=${measurement.lineCount}",
                measurement.lastGlyphBottom <= heightPx,
            )
        }
        Log.i("OdinReaderValidation", "$case: ${pages.size} pages rendered without clipped glyphs")
    }

    @SuppressLint("InlinedApi") // BREAK_STRATEGY_SIMPLE is the compile-time integer 0.
    @Suppress("DEPRECATION")
    private fun measureTextView(text: String, widthPx: Int, heightPx: Int, textSizePx: Float): TextMeasurement {
        var result: TextMeasurement? = null
        instrumentation.runOnMainSync {
            val view = TextView(context).apply {
                typeface = Typeface.SERIF
                includeFontPadding = false
                setFallbackLineSpacing(true)
                isElegantTextHeight = true
                if (Build.VERSION.SDK_INT >= 35) setUseBoundsForWidth(true)
                setPadding(0, 0, 0, 0)
                setLineSpacing(0f, 1.65f)
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
            )
        }
        return checkNotNull(result)
    }

    private data class TextMeasurement(val lastGlyphBottom: Int, val lineCount: Int, val finalLineEnd: Int)
}
