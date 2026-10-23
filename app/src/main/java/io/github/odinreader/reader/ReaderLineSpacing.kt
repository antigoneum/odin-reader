package io.github.odinreader.reader

/** Shared bounds for the native text renderer, paginator and durable settings. */
object ReaderLineSpacing {
    const val Default = 1.65f
    const val Minimum = 1.2f
    const val Maximum = 2.4f

    fun sanitize(value: Float): Float =
        if (value.isFinite()) value.coerceIn(Minimum, Maximum) else Default
}
