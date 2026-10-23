package io.github.odinreader.ui

internal const val BookPageTurnDurationMillis = 620

/** One leaf changes face at the spine. Text is never drawn through its mirrored back. */
internal data class BookPageTurnMotion(
    val progress: Float,
    val direction: Int,
    val frontOnRight: Boolean,
    val frontRotation: Float,
    val backRotation: Float,
    val frontVisible: Boolean,
    val backVisible: Boolean,
    val leftPivot: Float,
    val rightPivot: Float,
    val spineFraction: Float,
)

internal fun bookPageTurnMotion(
    progress: Float,
    direction: Int,
    sheetWidth: Float,
    gutter: Float,
    viewportWidth: Float,
): BookPageTurnMotion {
    require(sheetWidth > 0f && viewportWidth > 0f && gutter >= 0f)
    val phase = progress.coerceIn(0f, 1f)
    val sign = if (direction >= 0) 1 else -1
    // Each leaf's pivot lies half a gutter outside its rectangle. Both faces
    // therefore share the same physical centre, including a nonzero page gap.
    return BookPageTurnMotion(
        progress = phase,
        direction = sign,
        frontOnRight = sign > 0,
        frontRotation = -sign * 180f * phase.coerceAtMost(0.5f),
        backRotation = sign * 180f * (1f - phase.coerceAtLeast(0.5f)),
        frontVisible = phase < 0.5f,
        backVisible = phase >= 0.5f,
        leftPivot = 1f + gutter / (2f * sheetWidth),
        rightPivot = -gutter / (2f * sheetWidth),
        spineFraction = (sheetWidth + gutter / 2f) / viewportWidth,
    )
}
