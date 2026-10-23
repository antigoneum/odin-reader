package io.github.odinreader.ui

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.text.Layout
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import io.github.odinreader.reader.Page
import io.github.odinreader.reader.PageTurnAnimation
import io.github.odinreader.reader.ReaderFontSelection
import io.github.odinreader.reader.ReaderTypography
import io.github.odinreader.reader.ReadingMode

internal val ReaderTurnProgressKey = SemanticsPropertyKey<Float>("ReaderTurnProgress")
internal val ReaderTurnRotationKey = SemanticsPropertyKey<Float>("ReaderTurnRotation")
internal val ReaderTurnPivotKey = SemanticsPropertyKey<Float>("ReaderTurnPivot")
internal val ReaderTurnDirectionKey = SemanticsPropertyKey<Int>("ReaderTurnDirection")
internal val ReaderTurnFaceVisibleKey = SemanticsPropertyKey<Boolean>("ReaderTurnFaceVisible")

private val BookPageTurnEasing = CubicBezierEasing(0.4f, 0f, 0.6f, 1f)

internal data class ReaderPageGeometry(
    val viewportWidth: Dp,
    val height: Dp,
    val sheetWidth: Dp,
    val gap: Dp,
    val insetX: Dp = 0.dp,
    val insetY: Dp = 0.dp,
) {
    val textWidth: Dp get() = (sheetWidth - insetX * 2).coerceAtLeast(1.dp)
    val textHeight: Dp get() = (height - insetY * 2).coerceAtLeast(1.dp)
}

internal data class ReaderPageFrame(
    val chapter: Int,
    val index: Int,
    val pageCount: Int,
    val text: String,
    val primary: Page,
    val secondary: Page?,
    val typography: ReaderTypography,
    val fonts: ReaderFontSelection,
    val textSizePx: Float,
    val lineSpacing: Float,
    val letterSpacing: Float,
    val mode: ReadingMode,
    val geometry: ReaderPageGeometry,
) {
    val key: Any get() = listOf(chapter, primary.startOffset, primary.endOffset, fonts, textSizePx, lineSpacing, letterSpacing, mode, geometry)
    fun canAnimateFrom(previous: ReaderPageFrame): Boolean =
        geometry == previous.geometry && mode == previous.mode && fonts == previous.fonts &&
            textSizePx == previous.textSizePx && lineSpacing == previous.lineSpacing && letterSpacing == previous.letterSpacing
}

private data class PublishedPage<T : Any>(val frame: T, val key: Any, val turn: Int)
private data class PageTransition<T : Any>(val previous: T, val current: T, val direction: Int)
private class PagePresentation<T : Any>(val target: T, initialTransition: PageTransition<T>?) {
    var transition by mutableStateOf(initialTransition)
}

/** Measured native pages remain at full size; cropping and animation never reflow their text. */
@Composable
internal fun ReaderPagePresentation(
    frame: ReaderPageFrame?,
    animation: PageTurnAnimation,
    turn: Int,
    direction: Int,
    foreground: Color,
    background: Color,
    modifier: Modifier = Modifier,
) {
    AnimatedPagePresentation(
        frame = frame,
        frameKey = { it.key },
        frameGeometry = { it.geometry },
        frameMode = { it.mode },
        canAnimate = { target, previous -> target.canAnimateFrom(previous) },
        animation = animation,
        turn = turn,
        direction = direction,
        modifier = modifier,
        dividerColor = foreground.copy(alpha = 0.13f),
    ) { target, secondary, role, accessible, sheetModifier ->
        Sheet(target, if (secondary) target.secondary else target.primary,
            foreground, background, role, accessible, sheetModifier)
    }
}

/** Shared text/PDF motion: each slot renders at the measured full-sheet size. */
@Composable
internal fun <T : Any> AnimatedPagePresentation(
    frame: T?,
    frameKey: (T) -> Any,
    frameGeometry: (T) -> ReaderPageGeometry,
    frameMode: (T) -> ReadingMode,
    canAnimate: (T, T) -> Boolean,
    animation: PageTurnAnimation,
    turn: Int,
    direction: Int,
    modifier: Modifier = Modifier,
    dividerColor: Color,
    sheet: @Composable (T, Boolean, String, Boolean, Modifier) -> Unit,
) {
    var published by remember { mutableStateOf<PublishedPage<T>?>(null) }
    val targetKey = frame?.let(frameKey)
    // Prepare the complete first animation frame in composition. Publishing the
    // target and resetting a shared Animatable from a LaunchedEffect lets an
    // AndroidView draw the new text at the old fraction (1) before the reset.
    val presentation = remember(targetKey, turn, animation, direction) {
        val previous = published
        if (frame == null) {
            // Keep the last page during an asynchronous turn, without accepting
            // its new turn number before the requested PDF/chapter is ready.
            previous?.takeIf { it.turn != turn }?.let { PagePresentation(it.frame, null) }
        } else {
            val moving = previous?.takeIf {
                it.key != targetKey && it.turn != turn && canAnimate(frame, it.frame) &&
                    animation != PageTurnAnimation.None
            }?.let { PageTransition(it.frame, frame, if (direction >= 0) 1 else -1) }
            PagePresentation(frame, moving)
        }
    }
    SideEffect {
        if (frame != null && (published?.key != targetKey || published?.turn != turn)) {
            published = PublishedPage(frame, requireNotNull(targetKey), turn)
        }
    }
    val fraction = remember(presentation) { Animatable(if (presentation?.transition == null) 1f else 0f) }
    LaunchedEffect(presentation) {
        if (presentation?.transition != null) {
            fraction.animateTo(1f, tween(
                durationMillis = when (animation) {
                    PageTurnAnimation.PageTurn -> BookPageTurnDurationMillis
                    PageTurnAnimation.Fade -> 240
                    PageTurnAnimation.ZoomSlide -> 420
                    else -> 360
                },
                easing = if (animation == PageTurnAnimation.PageTurn) BookPageTurnEasing else FastOutSlowInEasing,
            ))
            // Removing the outgoing view at the already-rendered endpoint does
            // not change the visible page, and releases its native/bitmap data.
            presentation.transition = null
        }
    }
    // Retain the previous page only while a requested turn is loading. During a
    // font/viewport reflow no stale native body may masquerade as the new layout.
    val target = presentation?.target ?: return
    val moving = presentation.transition
    val phase = if (moving == null) 1f else fraction.value
    val density = LocalDensity.current
    val geometry = frameGeometry(target)
    val mode = frameMode(target)
    val widthPx = with(density) { geometry.viewportWidth.toPx() }
    val sheetWidthPx = with(density) { geometry.sheetWidth.toPx() }
    val directionSign = moving?.direction ?: 1
    val zoomPreview = mode == ReadingMode.ZoomPreview && animation == PageTurnAnimation.ZoomSlide && moving != null
    val previewX = with(density) { (geometry.sheetWidth + geometry.gap).toPx() }
    val previewScale = previewScale(geometry)

    Box(modifier) {
        Box(Modifier.fillMaxSize().clipToBounds().testTag("reader-mode-${mode.id}")
            .semantics { stateDescription = if (moving == null) "翻页完成" else "翻页动画 ${animation.id}" }) {
            // Target comes first in the native view hierarchy. Accessibility and test
            // readers always encounter the current text before any outgoing artwork.
            if (moving != null && animation == PageTurnAnimation.PageTurn) {
                if (mode.isSpread) BookSpreadTurn(
                    moving.previous, target, geometry, phase, directionSign, sheet,
                ) else SingleSheetTurn(
                    moving.previous, target, geometry, mode, phase, directionSign, sheet,
                )
            } else if (zoomPreview) {
                val x = if (directionSign > 0) previewX * (1f - phase) else -sheetWidthPx * (1f - phase)
                val scale = if (directionSign > 0) previewScale + (1f - previewScale) * phase else 1f
                sheet(target, false, "primary", true,
                    Modifier.graphicsLayer {
                        translationX = x
                        scaleX = scale; scaleY = scale
                        transformOrigin = TransformOrigin(0f, 0f)
                    })
                PreviewSheet(target, geometry, mode,
                    if (directionSign > 0) phase else if (phase == 1f) 1f else 0f, false, sheet)
            } else {
                val incoming = Modifier.fillMaxSize().graphicsLayer {
                    if (moving != null) when (animation) {
                        PageTurnAnimation.None -> Unit
                        PageTurnAnimation.Slide -> translationX = directionSign * widthPx * (1f - phase)
                        // The incoming paper stays opaque beneath the fading
                        // outgoing paper, so the middle never flashes dark.
                        PageTurnAnimation.Fade -> Unit
                        PageTurnAnimation.PageTurn -> Unit // Each leaf has its own spine transform above.
                        PageTurnAnimation.ZoomSlide -> {
                            translationX = directionSign * widthPx * (1f - phase)
                            // A spread remains one fixed-size book while it moves.
                            // Scale belongs only to the dedicated preview promotion.
                            if (!mode.isSpread) {
                                scaleX = 0.96f + 0.04f * phase
                                scaleY = scaleX
                            }
                        }
                    }
                }
                Spread(target, geometry, mode, incoming, true, sheet)
            }
            if (moving != null && animation != PageTurnAnimation.PageTurn) {
                if (zoomPreview) {
                    val x = if (directionSign > 0) -sheetWidthPx * phase else previewX * phase
                    val scale = if (directionSign > 0) 1f else 1f + (previewScale - 1f) * phase
                    sheet(moving.previous, false, "outgoing", false,
                        Modifier.zIndex(1f).graphicsLayer {
                            translationX = x
                            scaleX = scale; scaleY = scale
                            alpha = if (directionSign > 0) 1f - phase else 1f
                            transformOrigin = TransformOrigin(0f, 0f)
                        })
                } else {
                    Spread(moving.previous, frameGeometry(moving.previous), frameMode(moving.previous),
                        Modifier.fillMaxSize().zIndex(1f).graphicsLayer {
                            when (animation) {
                                PageTurnAnimation.None -> alpha = 0f
                                PageTurnAnimation.Slide -> translationX = -directionSign * widthPx * phase
                                PageTurnAnimation.Fade -> alpha = 1f - phase
                                PageTurnAnimation.PageTurn -> {
                                    alpha = 0f
                                }
                                PageTurnAnimation.ZoomSlide -> {
                                    translationX = -directionSign * widthPx * phase
                                    alpha = 1f - phase
                                }
                            }
                        }.clearAndSetSemantics {}, false, sheet)
                }
            }
            if (mode == ReadingMode.DualColumn) {
                Box(Modifier.offset(x = geometry.sheetWidth + geometry.gap / 2)
                    .size(1.dp, geometry.height).background(dividerColor))
            }
            if (mode == ReadingMode.Preview) {
                Canvas(Modifier.offset(x = geometry.sheetWidth + geometry.gap / 2 - 0.5.dp)
                    .size(1.dp, geometry.height).testTag("reader-preview-divider")) {
                    drawLine(
                        color = dividerColor.copy(alpha = 0.35f),
                        start = Offset(size.width / 2f, 0f),
                        end = Offset(size.width / 2f, size.height),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
                    )
                }
            }
        }
    }
}

/**
 * Four page faces form a book, but only one sheet moves. For a forward turn the
 * old left and new right pages remain still; the old right face folds to the
 * spine and its new left back unfolds from it. The reverse is exactly mirrored.
 */
@Composable
private fun <T : Any> BookSpreadTurn(
    previous: T,
    target: T,
    geometry: ReaderPageGeometry,
    phase: Float,
    direction: Int,
    sheet: @Composable (T, Boolean, String, Boolean, Modifier) -> Unit,
) {
    val density = LocalDensity.current
    val width = with(density) { geometry.sheetWidth.toPx() }
    val motion = bookPageTurnMotion(phase, direction, width,
        with(density) { geometry.gap.toPx() }, with(density) { geometry.viewportWidth.toPx() })
    val backOnRight = !motion.frontOnRight

    // Current primary/secondary are the first two native views and the only
    // accessible faces, even while their decorative predecessors still exist.
    for (right in listOf(false, true)) {
        if (right == backOnRight) {
            TurningLeaf(geometry, right, motion.backRotation,
                if (right) motion.rightPivot else motion.leftPivot,
                motion.spineFraction, phase, direction, motion.backVisible, "back") {
                sheet(target, right, if (right) "secondary" else "primary", true, Modifier)
            }
        } else {
            StaticLeaf(geometry, right, "static-${if (right) "right" else "left"}") {
                sheet(target, right, if (right) "secondary" else "primary", true, Modifier)
            }
        }
    }
    val oldStaticOnRight = !motion.frontOnRight
    StaticLeaf(geometry, oldStaticOnRight, "static-${if (oldStaticOnRight) "right" else "left"}") {
        sheet(previous, oldStaticOnRight, "outgoing-static", false, Modifier)
    }
    TurningLeaf(geometry, motion.frontOnRight, motion.frontRotation,
        if (motion.frontOnRight) motion.rightPivot else motion.leftPivot,
        motion.spineFraction, phase, direction, motion.frontVisible, "front") {
        sheet(previous, motion.frontOnRight, "outgoing", false, Modifier)
    }
}

@Composable
private fun StaticLeaf(
    geometry: ReaderPageGeometry,
    right: Boolean,
    tag: String,
    content: @Composable () -> Unit,
) {
    Box(Modifier.offset(x = if (right) geometry.sheetWidth + geometry.gap else 0.dp)
        .requiredSize(geometry.sheetWidth, geometry.height)
        .testTag("reader-turn-$tag")) { content() }
}

/** A face is switched at 90 degrees rather than ever displaying reversed type. */
@Composable
private fun TurningLeaf(
    geometry: ReaderPageGeometry,
    right: Boolean,
    angle: Float,
    localPivot: Float,
    spineFraction: Float,
    phase: Float,
    direction: Int,
    visible: Boolean,
    face: String,
    content: @Composable () -> Unit,
) {
    val widthPx = with(LocalDensity.current) { geometry.sheetWidth.toPx() }
    Box(Modifier.offset(x = if (right) geometry.sheetWidth + geometry.gap else 0.dp)
        .requiredSize(geometry.sheetWidth, geometry.height).zIndex(3f)
        .graphicsLayer {
            rotationY = angle
            alpha = if (visible) 1f else 0f
            // Keep perspective subtle and never let a wide page cross the eye.
            cameraDistance = widthPx * 6f
            transformOrigin = TransformOrigin(localPivot, 0.5f)
        }
        .testTag("reader-turn-$face").semantics {
            this[ReaderTurnProgressKey] = phase
            this[ReaderTurnRotationKey] = angle
            this[ReaderTurnPivotKey] = spineFraction
            this[ReaderTurnDirectionKey] = if (direction >= 0) 1 else -1
            this[ReaderTurnFaceVisibleKey] = visible
        }) {
        content()
    }
}

/** A single visible page folds off its reading edge while the next stays below. */
@Composable
private fun <T : Any> SingleSheetTurn(
    previous: T,
    target: T,
    geometry: ReaderPageGeometry,
    mode: ReadingMode,
    phase: Float,
    direction: Int,
    sheet: @Composable (T, Boolean, String, Boolean, Modifier) -> Unit,
) {
    sheet(target, false, "primary", true, Modifier)
    if (mode.hasPreview) {
        PreviewSheet(target, geometry, mode, 1f, false, sheet)
        PreviewSheet(previous, geometry, mode, 1f - phase, false) { frame, secondary, _, accessible, modifier ->
            sheet(frame, secondary, "outgoing-preview", accessible, modifier)
        }
    }
    TurningLeaf(geometry, false, -direction * 90f * phase, if (direction > 0) 0f else 1f,
        if (direction > 0) 0f else geometry.sheetWidth / geometry.viewportWidth,
        phase, direction, phase < 1f, "front") {
        sheet(previous, false, "outgoing", false, Modifier)
    }
}

@Composable
private fun <T> Spread(
    frame: T, geometry: ReaderPageGeometry, mode: ReadingMode, modifier: Modifier, accessible: Boolean,
    sheet: @Composable (T, Boolean, String, Boolean, Modifier) -> Unit,
) {
    Box(modifier) {
        sheet(frame, false, "primary", accessible, Modifier)
        when {
            mode.isSpread -> {
                sheet(frame, true, "secondary", accessible,
                    Modifier.offset(x = geometry.sheetWidth + geometry.gap))
            }
            mode.hasPreview -> PreviewSheet(frame, geometry, mode, 1f, false, sheet)
        }
    }
}

@Composable
private fun <T> PreviewSheet(
    frame: T, geometry: ReaderPageGeometry, mode: ReadingMode, alpha: Float, accessible: Boolean,
    sheet: @Composable (T, Boolean, String, Boolean, Modifier) -> Unit,
) {
    val rightWidth = (geometry.viewportWidth - geometry.sheetWidth - geometry.gap).coerceAtLeast(1.dp)
    Box(Modifier.offset(x = geometry.sheetWidth + geometry.gap).size(rightWidth, geometry.height)
        .clipToBounds().testTag("reader-preview-pane")) {
        if (mode == ReadingMode.Preview) {
            sheet(frame, true, "preview", accessible,
                Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).graphicsLayer { this.alpha = alpha })
        } else {
            val scale = previewScale(geometry)
            sheet(frame, true, "preview", accessible,
                Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).graphicsLayer {
                    scaleX = scale; scaleY = scale; this.alpha = alpha
                    transformOrigin = TransformOrigin(0f, 0f)
                })
        }
    }
}

private fun previewScale(geometry: ReaderPageGeometry): Float =
    ((geometry.viewportWidth - geometry.sheetWidth - geometry.gap) / geometry.sheetWidth).coerceIn(0.1f, 1f)

@Composable
private fun Sheet(
    frame: ReaderPageFrame,
    page: Page?,
    foreground: Color,
    background: Color,
    role: String,
    accessible: Boolean,
    modifier: Modifier = Modifier,
) {
    val geometry = frame.geometry
    Box(modifier.requiredSize(geometry.sheetWidth, geometry.height).background(background)
        .testTag("reader-sheet-$role")
        .then(if (accessible) Modifier else Modifier.clearAndSetSemantics {})) {
        if (page != null) {
            NativeReaderPage(frame, page, foreground, role, accessible,
                Modifier.offset(x = geometry.insetX, y = geometry.insetY)
                    .requiredSize(geometry.textWidth, geometry.textHeight))
        } else {
            Text(if (frame.mode.hasPreview) "本章结束" else "",
                color = foreground.copy(alpha = 0.45f), style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.Center))
        }
    }
}

internal data class RenderedReaderPageKey(
    val chapter: Int, val start: Int, val end: Int, val fonts: ReaderFontSelection, val role: String,
)

@SuppressLint("InlinedApi")
@Composable
private fun NativeReaderPage(frame: ReaderPageFrame, page: Page, foreground: Color, role: String, accessible: Boolean, modifier: Modifier) {
    val renderedKey = RenderedReaderPageKey(frame.chapter, page.startOffset, page.endOffset, frame.fonts, role)
    // A native view belongs to one immutable page face. In particular, the
    // currently visible TextView must never be updated to the incoming text
    // while Compose is installing the outgoing/incoming layer hierarchy.
    key(frame.key, renderedKey) {
        AndroidView(
            factory = { context -> TextView(context).apply {
                typeface = Typeface.SERIF
                includeFontPadding = false
                setFallbackLineSpacing(true)
                isElegantTextHeight = true
                if (android.os.Build.VERSION.SDK_INT >= 35) {
                    setUseBoundsForWidth(true)
                    setLocalePreferredLineHeightForMinimumUsed(true)
                }
                setPadding(0, 0, 0, 0)
                setLineSpacing(0f, frame.lineSpacing)
                letterSpacing = frame.letterSpacing
                breakStrategy = android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE
                hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
                gravity = Gravity.TOP or Gravity.START
                textDirection = View.TEXT_DIRECTION_FIRST_STRONG_LTR
                isFocusable = false
            } },
            modifier = modifier.testTag("reader-page-$role").semantics {
                stateDescription = "章节 ${frame.chapter}，字符 ${page.startOffset} 至 ${page.endOffset}"
            },
            update = { view ->
                view.importantForAccessibility = if (accessible) View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO
                if (view.typeface !== frame.typography.englishTypeface) view.typeface = frame.typography.englishTypeface
                view.setTextSize(TypedValue.COMPLEX_UNIT_PX, frame.textSizePx)
                view.setLineSpacing(0f, frame.lineSpacing)
                view.letterSpacing = frame.letterSpacing
                view.setTextColor(foreground.toArgb())
                if (view.text.toString() != page.text || view.tag != renderedKey) {
                    view.text = frame.typography.style(frame.text, page.startOffset, page.endOffset)
                    view.tag = renderedKey
                }
            },
        )
    }
}
