package io.github.odinreader.ui

import android.view.View
import android.graphics.Bitmap
import android.view.ViewGroup
import android.widget.TextView
import android.text.Layout
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.odinreader.MainActivity
import io.github.odinreader.data.Book
import io.github.odinreader.data.BookChapter
import io.github.odinreader.data.BookContent
import io.github.odinreader.data.ReadingProgress
import io.github.odinreader.input.ControllerAction
import io.github.odinreader.input.ControllerBindings
import io.github.odinreader.reader.PageTurnAnimation
import io.github.odinreader.reader.ReaderFont
import io.github.odinreader.reader.ReaderFontSelection
import io.github.odinreader.reader.ReadingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** All text is generated in memory; these tests never edit a library book or its history. */
@RunWith(AndroidJUnit4::class)
class ReaderModesDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val mode = mutableStateOf(ReadingMode.Single)
    private val animation = mutableStateOf(PageTurnAnimation.None)
    private val fontSize = mutableStateOf(20f)
    private val lineSpacing = mutableStateOf(1.65f)
    private val letterSpacing = mutableStateOf(0f)
    private var progress: ReadingProgress? = null
    private var action: ((ControllerAction) -> Boolean)? = null
    private val firstChapter = (1..70).joinToString("\n\n") {
        "第 $it 段，山川与海岸之间的阅读测试。中文 English 123，沿着远方的小路继续向前，" +
            "保持每个字符与标点连续。This paragraph measures native reader text without a missing line."
    }
    private val content = BookContent(
        Book("reader-modes-fixture", "横屏阅读验证", "Odin Reader", "TXT", 0L, 2),
        listOf(BookChapter("第一章", firstChapter), BookChapter("第二章", firstChapter.take(1200))),
    )
    private val fonts = ReaderFontSelection(ReaderFont.LxgwWenKai, ReaderFont.JetBrainsMono)

    @Test
    fun everyModeKeepsTheCharacterAnchorAndFitsBothNativeTextColumns() {
        show(ReadingMode.Single)
        turn(ControllerAction.NextPage)
        val anchor = requireNotNull(progress).charOffset
        assertTrue(anchor > 0)
        for (choice in ReadingMode.entries) {
            chooseMode(choice)
            assertEquals("Changing mode must preserve the exact character anchor", anchor, progress?.charOffset)
            captureModeIfRequested(choice)
            val native = pages()
            assertTrue(native.isNotEmpty())
            native.forEach { page -> assertNotClipped(page) }
            val current = native.first { it.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_YES }
            val range = current.tag as RenderedReaderPageKey
            assertTrue(anchor in range.start until range.end)
        }
        for (size in listOf(14f, 32f)) {
            compose.runOnIdle { fontSize.value = size }
            compose.waitForIdle()
            compose.waitUntil(15_000) { pages().firstOrNull()?.textSize == size * compose.activity.resources.displayMetrics.scaledDensity }
            pages().forEach { assertNotClipped(it) }
            assertEquals(anchor, progress?.charOffset)
        }
        val footer = compose.onNodeWithTag("reader-footer").fetchSemanticsNode().boundsInRoot
        val left = compose.onNodeWithTag("reader-footer-hints").fetchSemanticsNode().boundsInRoot
        val right = compose.onNodeWithTag("reader-page-counter").fetchSemanticsNode().boundsInRoot
        assertTrue(left.right < footer.center.x)
        assertTrue(right.left > footer.center.x)
        assertTrue("Keep the gesture handle's central area empty", right.left - left.right >= 120f)
    }

    @Test
    fun aTwoColumnSpreadAdvancesWithoutOmittingOrDuplicatingItsRightColumn() {
        show(ReadingMode.DualColumn)
        val shown = StringBuilder()
        var spreads = 0
        while (requireNotNull(progress).chapterIndex == 0) {
            val visible = pages().filter { it.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_YES }
            assertTrue(visible.size in 1..2)
            visible.forEach { shown.append(it.text) }
            if (visible.size == 2) {
                val left = visible[0].tag as RenderedReaderPageKey
                val right = visible[1].tag as RenderedReaderPageKey
                assertEquals(left.end, right.start)
            }
            turn(ControllerAction.NextPage)
            assertTrue("The test chapter must finish", ++spreads < 100)
        }
        assertEquals(firstChapter, shown.toString())
        turn(ControllerAction.PreviousPage)
        assertEquals(0, progress?.chapterIndex)
        val last = pages().filter { it.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_YES }.last()
        assertEquals(firstChapter.length, (last.tag as RenderedReaderPageKey).end)
    }

    @Test
    fun previewClipsTheLeftHalfOfACompleteNextPageAndDoesNotAdvanceReading() {
        show(ReadingMode.Preview)
        val native = pages()
        val current = native.first { (it.tag as RenderedReaderPageKey).role == "primary" }
        val preview = native.first { (it.tag as RenderedReaderPageKey).role == "preview" }
        assertEquals(current.width, preview.width)
        val pane = compose.onNodeWithTag("reader-preview-pane", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val divider = compose.onNodeWithTag("reader-preview-divider").fetchSemanticsNode().boundsInRoot
        val sheet = compose.onNodeWithTag("reader-sheet-primary").fetchSemanticsNode().boundsInRoot
        assertTrue(divider.center.x > sheet.right && divider.center.x < pane.left)
        val location = IntArray(2)
        compose.runOnIdle { preview.getLocationOnScreen(location) }
        assertTrue("Preview must be cropped rather than reflowed", preview.width > pane.width * 1.9f)
        assertTrue("Crop starts at the beginning of the next page", kotlin.math.abs(location[0] - pane.left) <= 2f)
        val nextText = preview.text.toString()
        val end = (current.tag as RenderedReaderPageKey).end
        assertEquals(0, progress?.pageIndex)
        assertFalse(requireNotNull(progress).completed)
        turn(ControllerAction.NextPage)
        assertEquals(end, progress?.charOffset)
        assertEquals(nextText, pages().first().text.toString())
    }

    @Test
    fun lineSpacingExtremesReflowEveryModeWithoutClippingOrChangingTheAnchor() {
        show(ReadingMode.Single)
        turn(ControllerAction.NextPage)
        val anchor = requireNotNull(progress).charOffset
        for (choice in ReadingMode.entries) {
            chooseMode(choice)
            for ((spacing, letters) in listOf(1.2f to 0f, 2.4f to 0.2f)) {
                compose.runOnIdle { lineSpacing.value = spacing; letterSpacing.value = letters }
                compose.waitForIdle()
                compose.waitUntil(15_000) { pages().isNotEmpty() && pages().all {
                    it.lineSpacingMultiplier == spacing && it.letterSpacing == letters
                } }
                compose.waitForIdle()
                assertEquals("Line and letter spacing must keep the character anchor", anchor, progress?.charOffset)
                pages().forEach { assertNotClipped(it) }
            }
            if (choice != ReadingMode.Preview) {
                compose.onNodeWithTag("reader-preview-divider").assertDoesNotExist()
            }
        }
    }

    @Test
    fun zoomPreviewMovesToTheLeftAndRapidTurnsKeepEveryLogicalPageAction() {
        show(ReadingMode.ZoomPreview)
        compose.runOnIdle { animation.value = PageTurnAnimation.ZoomSlide }
        compose.waitForIdle()
        val original = compose.onNodeWithTag("reader-page-primary", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { requireNotNull(action)(ControllerAction.NextPage) }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(96)
            val moving = compose.onNodeWithTag("reader-mode-zoom_preview").fetchSemanticsNode()
            assertEquals("翻页动画 zoom_slide", moving.config[SemanticsProperties.StateDescription])
            val incoming = compose.onNodeWithTag("reader-page-primary", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(incoming.left > original.left)
            assertTrue(incoming.width < original.width)
            compose.runOnIdle {
                requireNotNull(action)(ControllerAction.NextPage)
                requireNotNull(action)(ControllerAction.NextPage)
            }
            compose.mainClock.advanceTimeBy(600)
            compose.waitForIdle()
            assertEquals(3, progress?.pageIndex)
            assertEquals("翻页完成", compose.onNodeWithTag("reader-mode-zoom_preview").fetchSemanticsNode()
                .config[SemanticsProperties.StateDescription])
            compose.onNodeWithTag("reader-sheet-outgoing", useUnmergedTree = true).assertDoesNotExist()
        } finally { compose.mainClock.autoAdvance = true }
    }

    private fun show(choice: ReadingMode) {
        mode.value = choice
        compose.runOnUiThread {
            compose.activity.setContent {
                OdinReaderTheme(AppTheme.Mocha) {
                    ReaderScreen(content, ReadingProgress(), fontSize.value, fonts, PaperStyle.Theme,
                        ControllerBindings(), false, {}, {}, {}, {}, { progress = it }, {},
                        { action = it }, { false }, mode.value, animation.value, lineSpacing.value, letterSpacing.value)
                }
            }
        }
        awaitMode(choice)
    }

    private fun captureModeIfRequested(choice: ReadingMode) {
        if (InstrumentationRegistry.getArguments().getString("captureReadingModes") != "true") return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "reading-mode-validation").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "${choice.id}.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    private fun chooseMode(choice: ReadingMode) {
        compose.runOnIdle { mode.value = choice }
        awaitMode(choice)
    }

    private fun awaitMode(choice: ReadingMode) {
        compose.waitUntil(15_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("reader-mode-${choice.id}"), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty() && progress != null
        }
        compose.waitForIdle()
    }

    private fun turn(value: ControllerAction) {
        compose.runOnIdle { requireNotNull(action)(value) }
        compose.waitForIdle()
        compose.waitUntil(15_000) {
            val saved = progress
            val current = pages().firstOrNull { it.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_YES }
            val range = current?.tag as? RenderedReaderPageKey
            saved != null && range != null && range.chapter == saved.chapterIndex &&
                saved.charOffset in range.start until range.end
        }
        compose.waitForIdle()
    }

    private fun pages(): List<TextView> = compose.runOnIdle {
        descendants(compose.activity.window.decorView).filterIsInstance<TextView>()
            .filter { it.tag is RenderedReaderPageKey }.toList()
    }

    private fun assertNotClipped(view: TextView) = compose.runOnIdle {
        val layout = requireNotNull(view.layout)
        val key = view.tag as RenderedReaderPageKey
        val diagnostic = "mode=${mode.value.id} role=${key.role} chapter=${key.chapter} " +
            "range=${key.start}..${key.end} viewport=${view.width}x${view.height} " +
            "textSize=${view.textSize} spacing=${view.lineSpacingMultiplier} letters=${view.letterSpacing} lines=${layout.lineCount}"
        assertEquals("$diagnostic: every UTF-16 code unit must be laid out",
            view.text.length, layout.getLineEnd(layout.lineCount - 1))
        // A final newline adds an empty line in TextView. Check rendered glyphs,
        // exactly as PaginatorDeviceTest does, rather than blank trailing space.
        val lastNonBlankLine = (0 until layout.lineCount).lastOrNull { line ->
            view.text.subSequence(layout.getLineStart(line), layout.getLineEnd(line)).any { !it.isWhitespace() }
        }
        val lastGlyphBottom = lastNonBlankLine?.let { layout.getLineBaseline(it) + layout.getLineDescent(it) } ?: 0
        assertTrue("$diagnostic lastGlyphBottom=$lastGlyphBottom: native glyphs exceed measured height",
            lastGlyphBottom <= view.height)
        val widestLine = (0 until layout.lineCount).maxOfOrNull { line ->
            val start = layout.getLineStart(line)
            val end = layout.getLineEnd(line)
            var visibleEnd = end
            while (visibleEnd > start && view.text[visibleEnd - 1].isWhitespace()) visibleEnd--
            // getLineMax intentionally retains LAST-line trailing whitespace.
            // Re-measure only that styled visible range with the same TextPaint.
            if (line == layout.lineCount - 1 && visibleEnd != end) {
                if (visibleEnd == start) 0f else Layout.getDesiredWidth(view.text, start, visibleEnd, view.paint)
            } else layout.getLineMax(line)
        } ?: 0f
        assertTrue("$diagnostic widestLine=$widestLine: native line exceeds measured width",
            widestLine <= view.width + 1f)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
