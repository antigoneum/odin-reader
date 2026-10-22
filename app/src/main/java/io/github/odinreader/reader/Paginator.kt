package io.github.odinreader.reader

import android.annotation.SuppressLint
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint

/** A half-open range in the original chapter. Offsets count UTF-16 code units. */
data class Page(
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
)

/**
 * Measures real font glyphs, so Chinese, Latin text, punctuation and explicit
 * paragraph breaks all paginate using their actual displayed widths.
 *
 * The renderer must use the same typeface, no font padding, SIMPLE line breaking, no
 * hyphenation, and the same text size and line-spacing multiplier. A TextView
 * inside AndroidView provides the closest match to these Android measurements.
 * Call on a worker dispatcher; text layout can be expensive for large chapters.
 */
object Paginator {
    private const val CHUNK_SIZE = 16_384

    // SIMPLE is the compile-time integer 0 on all supported Android versions.
    @SuppressLint("InlinedApi")
    fun paginate(
        text: String,
        widthPx: Int,
        heightPx: Int,
        textSizePx: Float,
        lineSpacingMultiplier: Float = 1.65f,
        typeface: Typeface = Typeface.SERIF,
        typography: ReaderTypography? = null,
    ): List<Page> {
        require(widthPx > 0) { "Page width must be positive" }
        require(heightPx > 0) { "Page height must be positive" }
        require(textSizePx.isFinite() && textSizePx > 0f) { "Text size must be positive" }
        require(lineSpacingMultiplier.isFinite() && lineSpacingMultiplier >= 1f) {
            "Line spacing must be at least 1"
        }
        if (text.isEmpty()) return listOf(Page(0, 0, ""))

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.typeface = typography?.englishTypeface ?: typeface
            textSize = textSizePx
            isElegantTextHeight = true
        }
        val pages = PageCollector(text, heightPx)
        var cursor = 0
        while (cursor < text.length) {
            val chunkEnd = chunkEnd(text, cursor)
            val chunk = typography?.style(text, cursor, chunkEnd) ?: text.substring(cursor, chunkEnd)
            val builder = StaticLayout.Builder.obtain(chunk, 0, chunk.length, paint, widthPx)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setUseLineSpacingFromFallbacks(true)
                .setLineSpacing(0f, lineSpacingMultiplier)
                .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            if (android.os.Build.VERSION.SDK_INT >= 35) {
                builder.setUseBoundsForWidth(true)
                // TextView reserves locale-preferred metrics, even when a shorter
                // CJK TypefaceSpan overrides the Latin face on a particular line.
                // Match that minimum here so mixed-font pages cannot gain extra lines.
                val minimum = Paint.FontMetrics()
                paint.getFontMetricsForLocale(minimum)
                builder.setMinimumFontMetrics(minimum)
            }
            val layout = builder.build()

            // At an artificial chunk boundary the last line may be incomplete.
            // Measure it again with the next chunk, preserving word wrapping.
            val hasPartialLastLine = chunkEnd < text.length && chunk.last() != '\n'
            val lineCount = if (hasPartialLastLine && layout.lineCount > 1) {
                layout.lineCount - 1
            } else {
                layout.lineCount
            }
            var consumed = 0
            for (line in 0 until lineCount) {
                val lineStart = layout.getLineStart(line)
                val lineEnd = layout.getLineEnd(line)
                // StaticLayout adds an empty final line after a trailing newline.
                // The newline already belongs to the preceding nonempty range.
                if (lineEnd > lineStart) {
                    pages.addLine(
                        cursor + lineStart,
                        cursor + lineEnd,
                        layout.getLineBottom(line) - layout.getLineTop(line),
                    )
                }
                consumed = lineEnd
            }
            check(consumed > 0) { "Text layout did not advance" }
            cursor += consumed
        }
        return pages.finish()
    }

    /** Preserve the visible passage when a font size or viewport changes. */
    fun pageIndexAtOffset(pages: List<Page>, offset: Int): Int {
        if (pages.isEmpty()) return 0
        var low = 0
        var high = pages.lastIndex
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (pages[middle].startOffset <= offset) low = middle else high = middle - 1
        }
        return low
    }

    private fun chunkEnd(text: String, start: Int): Int {
        var end = (start.toLong() + CHUNK_SIZE).coerceAtMost(text.length.toLong()).toInt()
        if (end == text.length) return end
        val newline = text.lastIndexOf('\n', end - 1)
        if (newline >= start + CHUNK_SIZE / 2) return newline + 1
        // Never hand StaticLayout half of a supplementary Unicode character.
        if (end > start && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        return end
    }
}

/** Pure page grouping, independent of the Android font-measurement runtime. */
internal class PageCollector(
    private val text: String,
    private val heightPx: Int,
) {
    private val pages = mutableListOf<Page>()
    private var pageStart = 0
    private var pageEnd = 0
    private var pageHeight = 0L

    fun addLine(startOffset: Int, endOffset: Int, lineHeightPx: Int) {
        require(startOffset == pageEnd) { "Measured lines must cover the text contiguously" }
        require(endOffset > startOffset && endOffset <= text.length)
        require(lineHeightPx > 0)
        if (pageEnd > pageStart && pageHeight + lineHeightPx > heightPx) flush()
        pageEnd = endOffset
        pageHeight += lineHeightPx
    }

    fun finish(): List<Page> {
        check(pageEnd == text.length) { "Measured lines did not cover the complete chapter" }
        if (pageEnd > pageStart) flush()
        if (pages.isEmpty()) pages += Page(0, 0, "")
        return pages.toList()
    }

    private fun flush() {
        pages += Page(pageStart, pageEnd, text.substring(pageStart, pageEnd))
        pageStart = pageEnd
        pageHeight = 0L
    }
}
