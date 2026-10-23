package io.github.odinreader.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.odinreader.MainActivity
import io.github.odinreader.data.Book
import io.github.odinreader.data.ReadingRecord
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Uses only generated cover files and in-memory book metadata, never the user's library. */
@RunWith(AndroidJUnit4::class)
class BookCoverUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var temporaryDirectory: File

    @Before
    fun createIsolatedDirectory() {
        temporaryDirectory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "cover-ui-${UUID.randomUUID()}")
        check(temporaryDirectory.mkdirs())
    }

    @After
    fun removeIsolatedFiles() {
        if (::temporaryDirectory.isInitialized) temporaryDirectory.deleteRecursively()
    }

    @Test
    fun realCoverRemainsVisibleAboveSeparateProgressAndMetadata() {
        val file = File(temporaryDirectory, "cover.jpg")
        val bitmap = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(230, 210, 160))
            canvas.drawRect(20f, 20f, 180f, 200f, Paint().apply { color = Color.rgb(30, 80, 70) })
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        } finally {
            bitmap.recycle()
        }
        showCover(file)
        compose.waitUntil(10_000L) { compose.onAllNodes(
            androidx.compose.ui.test.hasTestTag("book-cover-image-$BOOK_ID"), useUnmergedTree = true,
        ).fetchSemanticsNodes().isNotEmpty() }
        node("book-cover-image").assertIsDisplayed()
        node("book-cover-placeholder").assertDoesNotExist()
        assertStats()
        val image = node("book-cover-image").fetchSemanticsNode().boundsInRoot
        val progress = node("book-progress").fetchSemanticsNode().boundsInRoot
        val title = node("book-title").fetchSemanticsNode().boundsInRoot
        assertTrue("Progress must not obscure the cover artwork", image.bottom <= progress.top)
        assertTrue("Progress must remain above book metadata", progress.bottom <= title.top)
    }

    @Test
    fun missingCoverUsesTheReadableTitlePlaceholder() {
        showCover(File(temporaryDirectory, "missing.jpg"))
        node("book-cover-placeholder").assertIsDisplayed()
        node("book-cover-title").assertTextEquals(TITLE)
        node("book-cover-image").assertDoesNotExist()
        assertStats()
    }

    @Test
    fun corruptCoverUsesTheReadableTitlePlaceholder() {
        val file = File(temporaryDirectory, "corrupt.jpg").apply { writeText("not an image") }
        showCover(file)
        node("book-cover-placeholder").assertIsDisplayed()
        node("book-cover-title").assertTextEquals(TITLE)
        node("book-cover-image").assertDoesNotExist()
        assertStats()
    }

    private fun showCover(file: File) {
        val book = Book(BOOK_ID, TITLE, "测试作者", "EPUB", 0L, 12, coverFile = file)
        val record = ReadingRecord(BOOK_ID, progressFraction = 0.37,
            totalReadingMillis = 300_000L, estimatedRemainingMillis = 840_000L, lastReadAt = 1L)
        compose.runOnUiThread {
            compose.activity.setContent {
                OdinReaderTheme(AppTheme.Mocha) {
                    Box(Modifier.fillMaxWidth().height(360.dp)) {
                        LibraryScreen(books = listOf(book), busy = false,
                            onOpen = {}, onImport = {}, onHelp = {}, onSettings = {},
                            readingRecords = mapOf(BOOK_ID to record))
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertStats() {
        node("book-progress").assertTextEquals("37%").assertIsDisplayed()
        node("book-read-time").assertTextEquals("已读 5分钟").assertIsDisplayed()
        node("book-remaining").assertTextEquals("预计剩余约 14分钟").assertIsDisplayed()
    }

    private fun node(prefix: String) = compose.onNodeWithTag("$prefix-$BOOK_ID", useUnmergedTree = true)

    private companion object {
        const val BOOK_ID = "book-cover-ui-fixture"
        const val TITLE = "山海之间的阅读旅程"
    }
}
