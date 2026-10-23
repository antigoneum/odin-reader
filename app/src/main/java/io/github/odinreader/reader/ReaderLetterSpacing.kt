package io.github.odinreader.reader

/** Android expresses tracking in em; the settings show a percentage of font size. */
object ReaderLetterSpacing {
    const val Default = 0f
    const val Minimum = 0f
    const val Maximum = 0.2f

    fun sanitize(value: Float): Float =
        if (value.isFinite()) value.coerceIn(Minimum, Maximum) else Default
}
