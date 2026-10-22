package io.github.odinreader.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.odinreader.MainActivity
import io.github.odinreader.data.Book
import io.github.odinreader.data.BookRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking

/** Imports and deletes only its own generated PDF, leaving existing books/settings intact. */
@RunWith(AndroidJUnit4::class)
class PdfReaderUiDeviceTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var fixture: File
    private lateinit var book: Book
    private var testModel: ReaderViewModel? = null
    private val isolatedBook = object : ExternalResource() {
        override fun before() {
            fixture = File(context.cacheDir, "PDF-device-${UUID.randomUUID()}.pdf")
            val document = PdfDocument()
            try {
                repeat(3) { index ->
                    val page = document.startPage(PdfDocument.PageInfo.Builder(600, 800, index + 1).create())
                    page.canvas.drawColor(Color.WHITE)
                    val paint = Paint().apply { color = Color.rgb(40, 80 + index * 40, 150); textSize = 32f }
                    page.canvas.drawRect(80f, 180f, 520f, 540f, paint)
                    page.canvas.drawText("PDF page ${index + 1}", 80f, 100f, paint)
                    document.finishPage(page)
                }
                fixture.outputStream().use(document::writeTo)
            } finally {
                document.close()
            }
            book = BookRepository(context).importBook(Uri.fromFile(fixture))
        }

        override fun after() {
            runBlocking { testModel?.awaitHistoryWrites() }
            if (::book.isInitialized) BookRepository(context).deleteBook(book.id)
            if (::fixture.isInitialized) fixture.delete()
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule
    val rules: TestRule = RuleChain.outerRule(isolatedBook).around(compose)

    @Before
    fun captureReadingModel() {
        compose.runOnIdle { testModel = ViewModelProvider(compose.activity)[ReaderViewModel::class.java] }
    }

    @Test
    fun pdfPagesRenderWithControlsZoomAndPersistentPosition() {
        openPdf()
        awaitPage(1)
        compose.onNodeWithTag("pdf-page").assertExists()
        gamepad(KeyEvent.KEYCODE_BUTTON_R1)
        awaitPage(2)
        gamepad(KeyEvent.KEYCODE_BUTTON_R1)
        awaitPage(3)
        val finished = requireNotNull(BookRepository(context).readingHistory.getRecord(book.id))
        assertTrue(finished.progress.completed)
        assertEquals(1.0, finished.progressFraction, 0.0)
        gamepad(KeyEvent.KEYCODE_BUTTON_L1)
        awaitPage(2)

        gamepad(KeyEvent.KEYCODE_BUTTON_Y)
        compose.onNodeWithTag("pdf-page-option-2").performClick()
        awaitPage(3)
        gamepad(KeyEvent.KEYCODE_BUTTON_L1)
        awaitPage(2)

        gamepad(KeyEvent.KEYCODE_BUTTON_X)
        compose.onNodeWithContentDescription("放大").performClick()
        compose.onNodeWithContentDescription("适合页面").performClick()
        gamepad(KeyEvent.KEYCODE_BUTTON_START)
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodesWithText("阅读设置").fetchSemanticsNodes().isNotEmpty()
        }
        gamepad(KeyEvent.KEYCODE_BUTTON_B)
        awaitPage(2)
        // The first B hides the toolbar, the second returns to the library.
        gamepad(KeyEvent.KEYCODE_BUTTON_B)
        gamepad(KeyEvent.KEYCODE_BUTTON_B)
        openPdf()
        awaitPage(2)
        assertEquals(1, BookRepository(context).getProgress(book.id).pageIndex)
        compose.activityRule.scenario.recreate()
        awaitPage(2)
        compose.onNodeWithTag("pdf-page").assertExists()
    }

    private fun openPdf() {
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodes(hasTestTag("library-book-grid")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("library-book-grid").performScrollToNode(hasTestTag("library-book-${book.id}"))
        compose.onAllNodes(hasClickAction() and hasText(book.title))[0].performClick()
    }

    private fun awaitPage(page: Int) {
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodesWithText("第 $page / 3 页").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        runBlocking { testModel?.awaitHistoryWrites() }
    }

    private fun gamepad(code: Int) {
        compose.runOnIdle {
            val time = SystemClock.uptimeMillis()
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                compose.activity.dispatchKeyEvent(
                    KeyEvent(time, time, action, code, 0, 0,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_GAMEPAD),
                )
            }
        }
        compose.waitForIdle()
    }

    companion object { private const val TIMEOUT = 15_000L }
}
