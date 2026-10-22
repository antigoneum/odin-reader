package io.github.odinreader.reader

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.SpannedString
import android.text.style.TypefaceSpan

/** The same two native faces and script spans are used for measuring and drawing. */
class ReaderTypography(val chineseTypeface: Typeface, val englishTypeface: Typeface) {
    fun style(text: String, start: Int = 0, end: Int = text.length): CharSequence {
        require(start in 0..end && end <= text.length) { "Invalid font range" }
        val portion = text.substring(start, end)
        if (chineseTypeface == englishTypeface || portion.isEmpty()) return portion
        val runs = ReaderFontRuns.runs(text, start, end)
        if (runs.none { it.script == ReaderScript.Chinese }) return portion
        val styled = SpannableString(portion)
        runs.forEach { run ->
            if (run.script == ReaderScript.Chinese) {
                styled.setSpan(TypefaceSpan(chineseTypeface), run.start - start, run.end - start,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return SpannedString(styled)
    }
}
