package io.github.odinreader.reader

/** Font runs use absolute UTF-16 offsets in the unchanged chapter text. */
internal data class ReaderFontRun(val start: Int, val end: Int, val script: ReaderScript)

internal enum class ReaderScript { Chinese, English }

/**
 * Script selection shared by measured chunks and displayed pages. Context is
 * always read from the original chapter, including at a requested range edge.
 */
internal object ReaderFontRuns {
    fun runs(text: String, start: Int = 0, end: Int = text.length): List<ReaderFontRun> {
        require(start in 0..end && end <= text.length) { "Invalid font range" }
        require(isCodePointBoundary(text, start) && isCodePointBoundary(text, end)) {
            "Font ranges must not split a surrogate pair"
        }
        if (start == end) return emptyList()

        val result = ArrayList<ReaderFontRun>()
        var cursor = start
        var previous = if (start > 0) scriptAt(text, previousOffset(text, start)) else ReaderScript.English
        var afterJoiner = start > 0 && text.codePointBefore(start) == ZERO_WIDTH_JOINER
        var runStart = start
        var runScript: ReaderScript? = null
        while (cursor < end) {
            val codePoint = text.codePointAt(cursor)
            val script = if (inheritsFont(codePoint) || afterJoiner) previous else baseScript(text, cursor, codePoint)
            if (runScript != script) {
                runScript?.let { result += ReaderFontRun(runStart, cursor, it) }
                runStart = cursor
                runScript = script
            }
            previous = script
            afterJoiner = codePoint == ZERO_WIDTH_JOINER
            cursor += Character.charCount(codePoint)
        }
        result += ReaderFontRun(runStart, end, checkNotNull(runScript))
        return result
    }

    private fun scriptAt(text: String, index: Int): ReaderScript {
        var cursor = index
        // Resolve a mark/variation selector or a joined cluster against its base.
        // This happens once at a range edge, rather than once per combining mark.
        while (true) {
            val point = text.codePointAt(cursor)
            val afterJoiner = cursor > 0 && text.codePointBefore(cursor) == ZERO_WIDTH_JOINER
            if (!inheritsFont(point) && !afterJoiner) return baseScript(text, cursor, point)
            if (cursor == 0) return ReaderScript.English
            cursor = previousOffset(text, cursor)
        }
    }

    private fun baseScript(text: String, index: Int, codePoint: Int): ReaderScript = when {
        isChinese(codePoint) -> ReaderScript.Chinese
        codePoint in OPENING_QUOTES -> nearestStrong(text, index, forward = true)
            ?: nearestStrong(text, index, forward = false) ?: ReaderScript.English
        codePoint in CLOSING_QUOTES -> nearestStrong(text, index, forward = false)
            ?: nearestStrong(text, index, forward = true) ?: ReaderScript.English
        codePoint in CONTEXTUAL_PUNCTUATION -> nearestStrong(text, index, forward = false)
            ?: nearestStrong(text, index, forward = true) ?: ReaderScript.English
        else -> ReaderScript.English
    }

    private fun nearestStrong(text: String, index: Int, forward: Boolean): ReaderScript? {
        var cursor = if (forward) index + Character.charCount(text.codePointAt(index)) else index
        // Quotes around a huge punctuation/whitespace sequence have no reliable
        // nearby language. Bound this lookup to avoid quadratic punctuation input.
        repeat(64) {
            if (forward) {
                if (cursor >= text.length) return null
            } else {
                if (cursor <= 0) return null
                cursor = previousOffset(text, cursor)
            }
            val point = text.codePointAt(cursor)
            if (isChinese(point)) return ReaderScript.Chinese
            val category = Character.getType(point)
            if (Character.isLetterOrDigit(point) || category == Character.PRIVATE_USE.toInt() ||
                category == Character.OTHER_SYMBOL.toInt()
            ) return ReaderScript.English
            if (forward) cursor += Character.charCount(point)
        }
        return null
    }

    private fun isChinese(point: Int): Boolean {
        if (point in 0x2E80..0x2FFF || point in 0x3000..0x303F || point in 0x31C0..0x31EF ||
            point in 0xFE10..0xFE1F || point in 0xFE30..0xFE6F || point in 0xFF00..0xFFEF ||
            point in 0x20000..0x2FA1F || point in 0x30000..0x33FFF
        ) return true
        return when (Character.UnicodeScript.of(point)) {
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.HANGUL,
            Character.UnicodeScript.BOPOMOFO,
            -> true
            else -> false
        }
    }

    private fun inheritsFont(point: Int): Boolean = when (Character.getType(point)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
        -> true
        else -> point == ZERO_WIDTH_JOINER || point == 0x200C ||
            point in 0xFE00..0xFE0F || point in 0xE0100..0xE01EF ||
            point in 0x1F3FB..0x1F3FF || point in 0xE0020..0xE007F
    }

    private fun previousOffset(text: String, index: Int): Int = index - Character.charCount(text.codePointBefore(index))

    private fun isCodePointBoundary(text: String, index: Int): Boolean =
        index == 0 || index == text.length || !(text[index].isLowSurrogate() && text[index - 1].isHighSurrogate())

    private const val ZERO_WIDTH_JOINER = 0x200D
    private val OPENING_QUOTES = setOf(0x2018, 0x201A, 0x201C, 0x201E, 0x00AB)
    private val CLOSING_QUOTES = setOf(0x2019, 0x201B, 0x201D, 0x201F, 0x00BB)
    private val CONTEXTUAL_PUNCTUATION = setOf(0x00B7, 0x2013, 0x2014, 0x2026)
}
