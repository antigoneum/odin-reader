package io.github.odinreader.ui

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
import io.github.odinreader.MainActivity
import io.github.odinreader.data.Book
import io.github.odinreader.data.ReadingProgress
import io.github.odinreader.data.ReadingRecord
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Fixed in-memory book data; never opens or changes the user's reading history. */
@RunWith(AndroidJUnit4::class)
class ReadingStatsUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun coverShowsPositionElapsedTimeAndRemainingEstimateWithoutOverlap() {
        showRecord(ReadingRecord(
            bookId = BOOK_ID,
            progressFraction = 0.375,
            totalReadingMillis = 95L * 60L * 1_000L,
            estimatedRemainingMillis = 35L * 60L * 1_000L + 1L,
            lastReadAt = 1L,
        ))
        node("book-progress").assertTextEquals("37%").assertIsDisplayed()
        node("book-read-time").assertTextEquals("已读 1小时35分").assertIsDisplayed()
        node("book-remaining").assertTextEquals("预计剩余约 36分钟").assertIsDisplayed()

        val coverTitle = node("book-cover-title").fetchSemanticsNode().boundsInRoot
        val progress = node("book-progress").fetchSemanticsNode().boundsInRoot
        val title = node("book-title").fetchSemanticsNode().boundsInRoot
        val elapsed = node("book-read-time").fetchSemanticsNode().boundsInRoot
        val remaining = node("book-remaining").fetchSemanticsNode().boundsInRoot
        val card = compose.onNodeWithTag("library-book-$BOOK_ID").fetchSemanticsNode().boundsInRoot
        assertTrue("Cover title must not overlap the progress label", coverTitle.bottom <= progress.top)
        assertTrue("Cover progress must remain above book metadata", progress.bottom <= title.top)
        assertTrue("Reading durations occupy separate lines", elapsed.bottom <= remaining.top)
        assertTrue("Remaining estimate must fit within the card", remaining.bottom <= card.bottom)
    }

    @Test
    fun unreadCoverKeepsUnknownEstimateDistinctFromZero() {
        showRecord(null)
        node("book-progress").assertTextEquals("0%").assertIsDisplayed()
        node("book-read-time").assertTextEquals("已读 0秒").assertIsDisplayed()
        node("book-remaining").assertTextEquals("预计剩余：待估算").assertIsDisplayed()
    }

    @Test
    fun completedCoverShowsOneHundredPercentAndReadStatus() {
        showRecord(ReadingRecord(
            bookId = BOOK_ID,
            progress = ReadingProgress(completed = true),
            progressFraction = 0.99,
            totalReadingMillis = 30_000L,
            lastReadAt = 1L,
        ))
        node("book-progress").assertTextEquals("100%").assertIsDisplayed()
        node("book-read-time").assertTextEquals("已读 30秒").assertIsDisplayed()
        node("book-remaining").assertTextEquals("已读完").assertIsDisplayed()
    }

    private fun showRecord(record: ReadingRecord?) {
        val book = Book(
            id = BOOK_ID,
            title = "沿着很长的山脊去看远方的海",
            author = "测试作者",
            format = "PDF",
            addedAt = 0L,
            chapterCount = 120,
        )
        // MainActivity is already declared by the app, unlike Compose's optional
        // ui-test-manifest activity. Replace only its composition with fixed data.
        compose.runOnUiThread {
            compose.activity.setContent {
                OdinReaderTheme(AppTheme.Mocha) {
                    Box(Modifier.fillMaxWidth().height(360.dp)) {
                        LibraryScreen(
                            books = listOf(book), busy = false,
                            onOpen = {}, onImport = {}, onHelp = {}, onSettings = {},
                            readingRecords = if (record == null) emptyMap() else mapOf(BOOK_ID to record),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun node(prefix: String) = compose.onNodeWithTag("$prefix-$BOOK_ID", useUnmergedTree = true)

    companion object { private const val BOOK_ID = "reading-stats-ui-fixture" }
}
