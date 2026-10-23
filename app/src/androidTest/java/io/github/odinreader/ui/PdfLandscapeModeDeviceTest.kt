package io.github.odinreader.ui

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.odinreader.MainActivity
import io.github.odinreader.data.Book
import io.github.odinreader.data.BookContent
import io.github.odinreader.data.ReadingProgress
import io.github.odinreader.input.ControllerAction
import io.github.odinreader.input.ControllerBindings
import io.github.odinreader.reader.PageTurnAnimation
import io.github.odinreader.reader.ReadingMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Generated cache-only PDF, with no writes to the user's library, history or settings. */
@RunWith(AndroidJUnit4::class)
class PdfLandscapeModeDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var file: File
    private lateinit var content: BookContent
    private var mode by mutableStateOf(ReadingMode.Single)
    private var initial = ReadingProgress()
    private var progress = ReadingProgress()
    private var action: ((ControllerAction) -> Boolean)? = null

    @Before
    fun createPdf() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        file = File(context.cacheDir, "landscape-pdf-${UUID.randomUUID()}.pdf")
        val document = PdfDocument()
        try {
            repeat(5) { index ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(600, 800, index + 1).create())
                page.canvas.drawColor(Color.WHITE)
                val paint = Paint().apply { color = Color.rgb(20, 60 + index * 30, 140); textSize = 44f }
                page.canvas.drawText("Original page ${index + 1}", 50f, 100f, paint)
                page.canvas.drawRect(50f, 150f, 550f, 700f, paint)
                document.finishPage(page)
            }
            file.outputStream().use(document::writeTo)
        } finally { document.close() }
        content = BookContent(Book("pdf-landscape-fixture", "横屏 PDF 测试", "Odin Reader", "PDF", 0, 5),
            emptyList(), pdfFile = file)
    }

    @After
    fun removePdf() {
        compose.runOnUiThread { compose.activity.setContent {} }
        compose.waitForIdle()
        if (::file.isInitialized) file.delete()
    }

    @Test
    fun dualColumnsAdvanceByTwoAndTheOddFinalPageRemainsReachable() {
        mode = ReadingMode.DualColumn
        show()
        awaitPage(0)
        compose.onNodeWithTag("pdf-page-secondary", useUnmergedTree = true).assertIsDisplayed()
        send(ControllerAction.NextPage)
        awaitPage(2)
        assertEquals(2, progress.pageIndex)
        assertFalse(progress.completed)
        send(ControllerAction.NextPage)
        awaitPage(4)
        assertTrue(progress.completed)
        send(ControllerAction.NextPage)
        assertEquals(4, progress.pageIndex)
        send(ControllerAction.PreviousPage)
        awaitPage(2)
        assertEquals(2, progress.pageIndex)
    }

    @Test
    fun previewingTheFinalPageDoesNotCompleteItBeforeTheFullPageIsOpened() {
        mode = ReadingMode.Preview
        initial = ReadingProgress(pageIndex = 3)
        show()
        awaitPage(3)
        compose.onNodeWithTag("reader-preview-pane", useUnmergedTree = true).assertIsDisplayed()
        assertFalse(progress.completed)
        compose.runOnIdle { mode = ReadingMode.ZoomPreview }
        awaitPage(3)
        assertFalse(progress.completed)
        send(ControllerAction.NextPage)
        awaitPage(4)
        assertTrue(progress.completed)
    }

    @Test
    fun everyModePreservesTheOriginalPageAndFooterLeavesTheGestureAreaEmpty() {
        initial = ReadingProgress(pageIndex = 1)
        show()
        awaitPage(1)
        for (choice in ReadingMode.entries) {
            compose.runOnIdle { mode = choice }
            awaitPage(1)
            assertEquals(1, progress.pageIndex)
            assertFalse(progress.completed)
        }
        val left = compose.onNodeWithTag("pdf-footer-left").fetchSemanticsNode().boundsInRoot
        val right = compose.onNodeWithTag("pdf-footer-right").fetchSemanticsNode().boundsInRoot
        val width = compose.activity.resources.displayMetrics.widthPixels
        val height = compose.activity.resources.displayMetrics.heightPixels
        assertTrue(left.right < width / 2f)
        assertTrue(right.left > width / 2f)
        assertTrue("Footer should sit close to the bottom outside the central gesture area", left.bottom > height - 40)
    }

    private fun show() {
        compose.runOnUiThread {
            compose.activity.setContent {
                OdinReaderTheme(AppTheme.Mocha) {
                    PdfReaderScreen(content, initial, ControllerBindings(), false,
                        onOpenSettings = {}, onNavigationModeChanged = {}, onBackgroundChanged = {},
                        onReadingVisible = {}, onProgress = { progress = it }, onClose = {},
                        onActionReady = { action = it }, activateFocused = { true },
                        readingMode = mode, pageAnimation = if (mode == ReadingMode.ZoomPreview)
                            PageTurnAnimation.ZoomSlide else PageTurnAnimation.None)
                }
            }
        }
    }

    private fun send(value: ControllerAction) {
        compose.runOnIdle { checkNotNull(action).invoke(value) }
        compose.waitForIdle()
    }

    private fun awaitPage(index: Int) {
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasTestTag("pdf-loading"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodes(hasContentDescription("PDF 第 ${index + 1} 页"), useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }
}
