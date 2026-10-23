package io.github.odinreader.ui

import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.odinreader.MainActivity
import io.github.odinreader.reader.Page
import io.github.odinreader.reader.PageTurnAnimation
import io.github.odinreader.reader.ReaderFontSelection
import io.github.odinreader.reader.ReaderTypography
import io.github.odinreader.reader.ReadingMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Observe the first committed native view tree, before waiting for animation effects.
 * Merely sampling the first nonzero animation phase misses a target-only frame that
 * was already applied and then replaced by the prepared transition.
 * All pages are fixtures; no library, settings or reading history is read or edited.
 */
@RunWith(AndroidJUnit4::class)
class PageTransitionFirstFrameDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private data class Fixture(val page: Int, val mode: ReadingMode)
    private data class Leaf(val page: Int, val role: String, val accessible: Boolean)
    private data class Commit(val request: Int, val target: Int?, val leaves: List<Leaf>)
    private data class Request(
        val case: Int,
        val mode: ReadingMode,
        val animation: PageTurnAnimation,
        val frame: Fixture? = Fixture(10, mode),
        val turn: Int = 0,
        val direction: Int = 1,
    )

    private val request = mutableStateOf(Request(0, ReadingMode.Single, PageTurnAnimation.Slide))
    private val commits = mutableListOf<Commit>()
    private val animations = PageTurnAnimation.entries.filter { it != PageTurnAnimation.None }
    private var nextCase = 0

    @Test
    fun everyAnimationPreparesOldAndNewNativePagesInTheSameFirstCommit() {
        mountFixture()
        for (mode in ReadingMode.entries) for (animation in animations) for (sign in listOf(1, -1)) {
            reset(mode, animation)
            compose.mainClock.autoAdvance = false
            try {
                val sequence = changeFrame(10 + sign, sign)
                assertPreparedFirstCommit(sequence, 10 + sign, 10, "$mode/$animation/$sign")
                finish()
                assertSettledOn(10 + sign)
            } finally {
                compose.mainClock.autoAdvance = true
            }
        }
    }

    @Test
    fun delayedPagesKeepTheOldNativeTextUntilTheArrivalIsReadyToAnimate() {
        mountFixture()
        // A null frame is the asynchronous PDF/chapter loading boundary. Preparing
        // the request must not consume its turn number or empty the current page.
        for (mode in listOf(ReadingMode.DualColumn, ReadingMode.ZoomPreview)) for (animation in animations) {
            reset(mode, animation)
            compose.mainClock.autoAdvance = false
            try {
                val sequence = compose.runOnUiThread {
                    val value = request.value
                    request.value = value.copy(frame = null, turn = value.turn + 1)
                    request.value.turn
                }
                compose.mainClock.advanceTimeByFrame()
                compose.runOnIdle {
                    val first = commits.first { it.request == sequence && it.target == null }
                    assertTrue("A pending page must retain its predecessor: $first", first.leaves.isNotEmpty())
                    assertTrue(first.leaves.all { it.page == 10 })
                    commits.clear()
                    request.value = request.value.copy(frame = Fixture(11, mode))
                }
                compose.mainClock.advanceTimeByFrame()
                assertPreparedFirstCommit(sequence, 11, 10, "delayed $mode/$animation")
                finish()
                assertSettledOn(11)
            } finally {
                compose.mainClock.autoAdvance = true
            }
        }
    }

    @Test
    fun repeatedAndReversedTurnsNeverPublishAFuturePageBeforePreparingItsPredecessor() {
        mountFixture()
        for (mode in listOf(ReadingMode.DualColumn, ReadingMode.ZoomPreview)) for (animation in animations) {
            reset(mode, animation)
            compose.mainClock.autoAdvance = false
            try {
                assertPreparedFirstCommit(changeFrame(11, 1), 11, 10, "first $mode/$animation")
                compose.mainClock.advanceTimeBy(64)
                assertPreparedFirstCommit(changeFrame(12, 1), 12, 11, "repeat $mode/$animation")
                compose.mainClock.advanceTimeByFrame()
                assertPreparedFirstCommit(changeFrame(11, -1), 11, 12, "reverse $mode/$animation")
                finish()
                assertSettledOn(11)
            } finally {
                compose.mainClock.autoAdvance = true
            }
        }
    }

    @Test
    fun anExistingNativeBookFaceIsNeverRewrittenWithTheNextPagesText() {
        val nativeFrame = mutableStateOf(nativeFrame(0, ReadingMode.Single))
        val nativeAnimation = mutableStateOf(PageTurnAnimation.Slide)
        val nativeTurn = mutableStateOf(0)
        var case = 0
        val nativeCase = mutableStateOf(case)
        compose.runOnUiThread {
            compose.activity.setContent {
                OdinReaderTheme(AppTheme.Mocha) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        key(nativeCase.value) {
                            ReaderPagePresentation(nativeFrame.value, nativeAnimation.value,
                                nativeTurn.value, 1, Color.Black, Color.White,
                                Modifier.requiredSize(nativeFrame.value.geometry.viewportWidth,
                                    nativeFrame.value.geometry.height))
                        }
                    }
                }
            }
        }
        for (mode in ReadingMode.entries) for (animation in animations) {
            compose.mainClock.autoAdvance = true
            compose.runOnIdle {
                nativeFrame.value = nativeFrame(0, mode)
                nativeAnimation.value = animation
                nativeTurn.value = 0
                nativeCase.value = ++case
            }
            compose.waitForIdle()
            val previousViews = compose.runOnIdle {
                nativeViews().filter { it.tag is RenderedReaderPageKey }.map { it to it.text.toString() }
            }
            assertTrue("The native fixture must create book text", previousViews.isNotEmpty())
            compose.mainClock.autoAdvance = false
            try {
                compose.runOnUiThread {
                    nativeFrame.value = nativeFrame(2, mode)
                    nativeTurn.value++
                }
                // Check the original Java TextView objects, including detached
                // ones. Looking up the new current semantics node hides reuse.
                repeat(3) {
                    compose.mainClock.advanceTimeByFrame()
                    compose.runOnIdle {
                        previousViews.forEach { (view, original) ->
                            assertEquals("An old native face changed text during $mode/$animation", original,
                                view.text.toString())
                        }
                    }
                }
                finish()
                compose.runOnIdle {
                    assertTrue(nativeViews().filter { it.tag is RenderedReaderPageKey }.any {
                        it.text.toString().startsWith("Page C")
                    })
                }
            } finally {
                compose.mainClock.autoAdvance = true
            }
        }
    }

    private fun mountFixture() {
        compose.runOnUiThread {
            compose.activity.setContent {
                OdinReaderTheme(AppTheme.Mocha) {
                    val value = request.value
                    val geometry = geometry(value.mode)
                    Box(Modifier.fillMaxSize().background(Color(0xff20232d)), contentAlignment = Alignment.Center) {
                        key(value.case) {
                            AnimatedPagePresentation(
                                frame = value.frame,
                                frameKey = { it },
                                frameGeometry = { geometry(it.mode) },
                                frameMode = { it.mode },
                                canAnimate = { target, previous -> target.mode == previous.mode },
                                animation = value.animation,
                                turn = value.turn,
                                direction = value.direction,
                                modifier = Modifier.requiredSize(geometry.viewportWidth, geometry.height)
                                    .testTag("first-frame-fixture"),
                                dividerColor = Color.Gray,
                            ) { page, secondary, role, accessible, modifier ->
                                val leaf = Leaf(page.page, role, accessible)
                                // Native views participate in the same Compose
                                // apply pass as production TextView book faces.
                                key(leaf, secondary) {
                                    AndroidView(
                                        factory = { context -> TextView(context).apply {
                                            textSize = 22f
                                            setTextColor(android.graphics.Color.BLACK)
                                            setBackgroundColor(if (page.page % 2 == 0) 0xffffdfaa.toInt() else 0xffaaddff.toInt())
                                        } },
                                        update = { view ->
                                            view.tag = leaf
                                            view.text = "Page ${page.page} ${if (secondary) "right" else "left"} 中文 Abc"
                                        },
                                        modifier = modifier.requiredSize(geometry.sheetWidth, geometry.height),
                                    )
                                }
                            }
                        }
                    }
                    // SideEffect sees the *applied* Android view tree. It runs
                    // before LaunchedEffect can fix an incorrectly published
                    // target-only tree, unlike waitForIdle/animation snapshots.
                    SideEffect {
                        commits += Commit(value.turn, value.frame?.page,
                            nativeViews().mapNotNull { it.tag as? Leaf })
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun reset(mode: ReadingMode, animation: PageTurnAnimation) {
        compose.mainClock.autoAdvance = true
        compose.runOnIdle {
            request.value = Request(++nextCase, mode, animation)
            commits.clear()
        }
        compose.waitForIdle()
        assertSettledOn(10)
        compose.runOnIdle { commits.clear() }
    }

    private fun changeFrame(target: Int, direction: Int): Int {
        // runOnUiThread intentionally does not synchronize through idle after
        // mutation. We need the first apply pass, not an already-started effect.
        val sequence = compose.runOnUiThread {
            request.value = request.value.copy(frame = Fixture(target, request.value.mode),
                turn = request.value.turn + 1, direction = direction)
            request.value.turn
        }
        compose.mainClock.advanceTimeByFrame()
        return sequence
    }

    private fun assertPreparedFirstCommit(sequence: Int, target: Int, previous: Int, label: String) {
        compose.runOnIdle {
            val first = commits.firstOrNull { it.request == sequence && it.target == target }
            requireNotNull(first) { "No first native commit was captured for $label: $commits" }
            assertTrue("$label: the target was committed without its outgoing page: $first",
                first.leaves.any { it.page == previous && !it.accessible })
            assertTrue("$label: the incoming page must be ready in that same commit: $first",
                first.leaves.any { it.page == target && it.role == "primary" && it.accessible })
        }
    }

    private fun finish() {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun assertSettledOn(target: Int) = compose.runOnIdle {
        val leaves = nativeViews().mapNotNull { it.tag as? Leaf }
        assertTrue("The settled fixture must contain a native page", leaves.isNotEmpty())
        assertTrue("Only the final page should remain: $leaves", leaves.all { it.page == target })
        assertTrue("The final page must have a readable primary face: $leaves",
            leaves.any { it.role == "primary" && it.accessible })
    }

    private fun geometry(mode: ReadingMode): ReaderPageGeometry {
        val width = 720
        val gap = if (mode == ReadingMode.Single) 0 else 16
        return ReaderPageGeometry(width.dp, 300.dp, mode.pageWidthPx(width, gap).dp, gap.dp)
    }

    private fun nativeFrame(index: Int, mode: ReadingMode): ReaderPageFrame {
        val paragraphs = listOf("Page A 原来的正文\nAbc 123", "Page B 下一页预览\nDef 456",
            "Page C 新的正文\nGhi 789", "Page D 后续正文\nJkl 012")
        val source = paragraphs.joinToString("")
        fun page(at: Int): Page {
            val start = paragraphs.take(at).sumOf { it.length }
            return Page(start, start + paragraphs[at].length, paragraphs[at])
        }
        return ReaderPageFrame(0, index, paragraphs.size, source, page(index), page(index + 1),
            ReaderTypography(Typeface.SERIF, Typeface.SERIF), ReaderFontSelection(), 36f, 1.65f, 0f,
            mode, geometry(mode))
    }

    private fun nativeViews(): List<TextView> = descendants(compose.activity.window.decorView)
        .filterIsInstance<TextView>().toList()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
