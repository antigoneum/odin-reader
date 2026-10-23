package io.github.odinreader.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.odinreader.MainActivity
import io.github.odinreader.reader.PageTurnAnimation
import io.github.odinreader.reader.ReadingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** Generated colored leaves exercise the shared text/PDF engine without touching a book or history. */
@RunWith(AndroidJUnit4::class)
class BookPageTurnDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private data class Fixture(val spread: Int, val mode: ReadingMode)
    private val frame = mutableStateOf(Fixture(10, ReadingMode.DualColumn))
    private val turn = mutableStateOf(0)
    private val direction = mutableStateOf(1)
    private val geometry = ReaderPageGeometry(720.dp, 340.dp, 352.dp, 16.dp)

    @Test
    fun dualColumnTurnsOneLeafAtTheSpineInBothDirectionsWithoutMirroringOrEdgeShading() {
        verifyBookTurn(ReadingMode.DualColumn, 1)
        verifyBookTurn(ReadingMode.DualColumn, -1)
    }

    @Test
    fun anInterruptedTurnAndReversalSettleOnTheLatestLogicalSpread() {
        show(ReadingMode.DualColumn)
        compose.mainClock.autoAdvance = false
        try {
            request(11, 1)
            advanceTo(0.20f)
            // A second forward action and an immediate reverse action arrive during motion.
            compose.runOnIdle {
                frame.value = Fixture(12, ReadingMode.DualColumn)
                direction.value = 1
                turn.value++
            }
            compose.mainClock.advanceTimeByFrame()
            request(11, -1)
            advanceTo(0.25f)
            assertEquals(-1, face("front").config[ReaderTurnDirectionKey])
            assertOnlyTargetAccessible(11)
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            assertSettled(11, ReadingMode.DualColumn)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    private fun verifyBookTurn(mode: ReadingMode, sign: Int) {
        show(mode)
        val root = compose.onNodeWithTag("fixture-book-root").fetchSemanticsNode().boundsInRoot
        val initialLeft = compose.onNodeWithTag("fixture-leaf-10-primary", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val initialRight = compose.onNodeWithTag("fixture-leaf-10-secondary", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val spine = (initialLeft.right + initialRight.left) / 2f
        compose.mainClock.autoAdvance = false
        val target = 10 + sign
        try {
            request(target, sign)
            advanceTo(0.25f)
            val firstProgress = face("front").config[ReaderTurnProgressKey]
            assertTrue("The first sample must be on the front face", firstProgress < 0.5f)
            verifyStationaryLeaves(initialLeft, initialRight)
            verifyFace("front", sign, root, spine, visible = true)
            verifyFace("back", sign, root, spine, visible = false)
            assertOnlyTargetAccessible(target)
            assertFaceIsNotMirrored("front")
            captureIfRequested(mode, sign, "front")

            advanceTo(0.75f)
            assertTrue(face("back").config[ReaderTurnProgressKey] >= 0.5f)
            verifyStationaryLeaves(initialLeft, initialRight)
            verifyFace("front", sign, root, spine, visible = false)
            verifyFace("back", sign, root, spine, visible = true)
            assertOnlyTargetAccessible(target)
            assertFaceIsNotMirrored("back")
            captureIfRequested(mode, sign, "back")

            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            assertSettled(target, mode)
            val finalLeft = compose.onNodeWithTag("fixture-leaf-$target-primary", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val finalRight = compose.onNodeWithTag("fixture-leaf-$target-secondary", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            assertSameRect("The complete left leaf must retain its measured size", initialLeft, finalLeft)
            assertSameRect("The complete right leaf must retain its measured size", initialRight, finalRight)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    private fun verifyStationaryLeaves(left: Rect, right: Rect) {
        assertSameRect("The left supporting page must not rotate or translate", left,
            compose.onNodeWithTag("reader-turn-static-left", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot)
        assertSameRect("The right supporting page must not rotate or translate", right,
            compose.onNodeWithTag("reader-turn-static-right", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot)
    }

    private fun verifyFace(name: String, sign: Int, root: Rect, spine: Float, visible: Boolean) {
        val node = face(name)
        assertEquals(sign, node.config[ReaderTurnDirectionKey])
        assertEquals(visible, node.config[ReaderTurnFaceVisibleKey])
        assertEquals("The rotation axis is the book's center, including half the gutter",
            (spine - root.left) / root.width, node.config[ReaderTurnPivotKey], 0.002f)
        val rotation = node.config[ReaderTurnRotationKey]
        assertTrue("Each face must stay in its readable, non-mirrored 90-degree range: $rotation",
            abs(rotation) <= 90.01f)
        if (visible) {
            val bounds = node.boundsInRoot
            val turnsFromRight = (name == "front") == (sign > 0)
            if (turnsFromRight) {
                assertTrue("The right leaf must pivot toward the center, not move the whole spread",
                    bounds.left >= spine - 2f)
            } else {
                assertTrue("The left leaf must pivot toward the center, not move the whole spread",
                    bounds.right <= spine + 2f)
            }
            assertTrue("A projected leaf cannot become wider than its original page",
                bounds.width <= root.width / 2f + 2f)
        }
    }

    /** A readable face has a green left edge and a red right edge, even after perspective projection. */
    private fun assertFaceIsNotMirrored(name: String) {
        val rootInteraction = compose.onNodeWithTag("fixture-book-root")
        val rootBounds = rootInteraction.fetchSemanticsNode().boundsInRoot
        val faceBounds = face(name).boundsInRoot
        val bitmap = rootInteraction.captureToImage().asAndroidBitmap()
        val scaleX = bitmap.width / rootBounds.width
        val scaleY = bitmap.height / rootBounds.height
        val from = ((faceBounds.left - rootBounds.left) * scaleX).roundToInt().coerceIn(0, bitmap.width - 1)
        val until = ((faceBounds.right - rootBounds.left) * scaleX).roundToInt().coerceIn(from + 1, bitmap.width)
        val row = ((faceBounds.center.y - rootBounds.top) * scaleY).roundToInt().coerceIn(0, bitmap.height - 1)
        val green = mutableListOf<Int>()
        val red = mutableListOf<Int>()
        for (x in from until until) {
            val pixel = bitmap.getPixel(x, row)
            val r = android.graphics.Color.red(pixel)
            val g = android.graphics.Color.green(pixel)
            val b = android.graphics.Color.blue(pixel)
            if (g > r * 1.4f && g > b * 1.2f) green += x
            if (r > g * 1.4f && r > b * 1.4f) red += x
        }
        assertTrue("The facing leaf's green edge must be visible", green.size > 4)
        assertTrue("The facing leaf's red edge must be visible", red.size > 4)
        assertTrue("A back face must be readable rather than mirrored",
            green.average() < red.average())
        // The colored edge bands must keep their original color throughout the
        // turn. A gradient over the leaf would darken these samples even when
        // its geometry and text orientation were otherwise correct.
        assertFlatEdgeColor("left", bitmap.getPixel(green[green.size / 2], row), 0xff25c879.toInt())
        assertFlatEdgeColor("right", bitmap.getPixel(red[red.size / 2], row), 0xffee4433.toInt())
    }

    private fun assertFlatEdgeColor(edge: String, actual: Int, expected: Int) {
        assertTrue("The $edge edge must not acquire a shading gradient (red)",
            abs(android.graphics.Color.red(expected) - android.graphics.Color.red(actual)) <= 2)
        assertTrue("The $edge edge must not acquire a shading gradient (green)",
            abs(android.graphics.Color.green(expected) - android.graphics.Color.green(actual)) <= 2)
        assertTrue("The $edge edge must not acquire a shading gradient (blue)",
            abs(android.graphics.Color.blue(expected) - android.graphics.Color.blue(actual)) <= 2)
    }

    private fun assertOnlyTargetAccessible(target: Int) {
        val descriptions = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
            .fetchSemanticsNodes().flatMap { it.config[SemanticsProperties.ContentDescription] }
            .filter { it.startsWith("fixture-readable-") }
        assertEquals("Only the two current leaves are exposed to accessibility",
            setOf("fixture-readable-$target-left", "fixture-readable-$target-right"), descriptions.toSet())
        assertEquals("Do not announce duplicate or outgoing pages", 2, descriptions.size)
    }

    private fun assertSettled(target: Int, mode: ReadingMode) {
        assertEquals("翻页完成", compose.onNodeWithTag("reader-mode-${mode.id}")
            .fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        compose.onNodeWithTag("reader-turn-front", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("reader-turn-back", useUnmergedTree = true).assertDoesNotExist()
        assertOnlyTargetAccessible(target)
    }

    private fun face(name: String) = compose.onNodeWithTag("reader-turn-$name", useUnmergedTree = true)
        .fetchSemanticsNode()

    private fun advanceTo(progress: Float) {
        repeat(50) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val nodes = compose.onAllNodes(androidx.compose.ui.test.hasTestTag("reader-turn-front"),
                useUnmergedTree = true).fetchSemanticsNodes()
            if (nodes.firstOrNull()?.config?.getOrNull(ReaderTurnProgressKey)?.let { it >= progress } == true) return
        }
        error("The book turn did not reach phase $progress")
    }

    private fun request(target: Int, sign: Int) {
        compose.runOnIdle {
            direction.value = sign
            frame.value = Fixture(target, frame.value.mode)
            turn.value++
        }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun show(mode: ReadingMode) {
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread {
            frame.value = Fixture(10, mode)
            turn.value = 0
            direction.value = 1
            compose.activity.setContent {
                OdinReaderTheme(AppTheme.Mocha) {
                    Box(Modifier.fillMaxSize().background(Color(0xff20232d)), contentAlignment = Alignment.Center) {
                        AnimatedPagePresentation(
                            frame = frame.value,
                            frameKey = { it },
                            frameGeometry = { geometry },
                            frameMode = { it.mode },
                            canAnimate = { next, previous -> next.mode == previous.mode },
                            animation = PageTurnAnimation.PageTurn,
                            turn = turn.value,
                            direction = direction.value,
                            modifier = Modifier.size(geometry.viewportWidth, geometry.height).testTag("fixture-book-root"),
                            dividerColor = Color(0xff6a6c79),
                        ) { value, secondary, role, accessible, modifier ->
                            val tag = "fixture-leaf-${value.spread}-$role"
                            val readable = "fixture-readable-${value.spread}-${if (secondary) "right" else "left"}"
                            Box(modifier.requiredSize(geometry.sheetWidth, geometry.height)
                                .background(if (secondary) Color(0xfff2e6ce) else Color(0xfff7efd8))
                                .testTag(tag)
                                .then(if (accessible) Modifier.semantics { contentDescription = readable }
                                    else Modifier.clearAndSetSemantics { testTag = tag })) {
                                Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(60.dp)
                                    .background(Color(0xff25c879)))
                                Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(60.dp)
                                    .background(Color(0xffee4433)))
                                Text("${value.mode.label}\nSpread ${value.spread}\n${if (secondary) "RIGHT →" else "LEFT →"}\nAbc 123 中文",
                                    color = Color(0xff252531), fontSize = 22.sp, lineHeight = 35.sp,
                                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp))
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        assertSettled(10, mode)
    }

    private fun assertSameRect(message: String, expected: Rect, actual: Rect) {
        assertEquals("$message (left)", expected.left, actual.left, 1f)
        assertEquals("$message (top)", expected.top, actual.top, 1f)
        assertEquals("$message (right)", expected.right, actual.right, 1f)
        assertEquals("$message (bottom)", expected.bottom, actual.bottom, 1f)
    }

    private fun captureIfRequested(mode: ReadingMode, sign: Int, face: String) {
        if (InstrumentationRegistry.getArguments().getString("captureBookTurns") != "true") return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "book-turn-validation").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "${mode.id}-${if (sign > 0) "forward" else "backward"}-$face.png")
                .outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }
}
