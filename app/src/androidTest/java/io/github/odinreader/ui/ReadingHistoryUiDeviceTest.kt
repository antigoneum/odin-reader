package io.github.odinreader.ui

import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.odinreader.MainActivity
import io.github.odinreader.data.BookRepository
import io.github.odinreader.data.DemoBook
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/** Exercises real lifecycle-driven time accounting, durable bookmarks and cover summaries. */
@RunWith(AndroidJUnit4::class)
class ReadingHistoryUiDeviceTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val historyGuard = ReadingHistoryTestGuard(context, DemoBook.ID)
    private lateinit var model: ReaderViewModel
    private lateinit var savedSettings: Map<String, *>
    private val isolatedDemo = object : ExternalResource() {
        override fun before() {
            historyGuard.before()
            val settings = context.getSharedPreferences("reader_settings", Context.MODE_PRIVATE)
            savedSettings = settings.all
            // Page and durable-offset assertions below intentionally exercise
            // the default single-page renderer and typography.
            check(settings.edit().clear().commit())
        }

        override fun after() {
            historyGuard.after()
            val editor = context.getSharedPreferences("reader_settings", Context.MODE_PRIVATE).edit().clear()
            savedSettings.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    null -> Unit
                    else -> error("Unexpected setting type for $key")
                }
            }
            check(editor.commit())
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(isolatedDemo).around(compose)

    @Before
    fun captureActualModel() {
        model = compose.runOnIdle { ViewModelProvider(compose.activity)[ReaderViewModel::class.java] }
        historyGuard.attachModel(model)
    }

    @Test
    fun closingWhileBookLoadsDoesNotReopenReader() {
        awaitLibrary()
        compose.runOnIdle {
            model.openBook(DemoBook.content.book)
            model.closeBook()
        }
        runBlocking { model.awaitHistoryWrites() }
        compose.runOnIdle {
            assertEquals("Queued loading must not reopen a closed book", null, model.state.value.content)
        }
        awaitLibrary()
    }

    @Test
    fun readingTimePausesInSettingsAndBookmarkRestoresFromDatabase() {
        openDemo()
        awaitPage(1)
        key(KeyEvent.KEYCODE_PAGE_DOWN)
        awaitPage(2)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        runBlocking { model.awaitHistoryWrites() }
        val store = BookRepository(context).readingHistory
        val backgroundTime = requireNotNull(store.getRecord(DemoBook.ID)).totalReadingMillis
        SystemClock.sleep(400)
        runBlocking { model.awaitHistoryWrites() }
        assertEquals("Background is not reading time", backgroundTime,
            requireNotNull(store.getRecord(DemoBook.ID)).totalReadingMillis)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        awaitPage(2)
        // The wall clock progresses while only the rendered book is visible.
        SystemClock.sleep(1_250)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithContentDescription("阅读设置").performClick()
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodesWithText("阅读设置").fetchSemanticsNodes().isNotEmpty()
        }
        runBlocking { model.awaitHistoryWrites() }
        val paused = requireNotNull(store.getRecord(DemoBook.ID))
        assertTrue("Visible foreground reading should accumulate elapsed time", paused.totalReadingMillis >= 1_000L)
        assertTrue(paused.progress.charOffset > 0)
        assertEquals(1, paused.progress.pageIndex)
        assertTrue(paused.progressFraction in 0.0..1.0 && paused.progressFraction > 0.0)
        assertNotNull(paused.estimatedRemainingMillis)
        SystemClock.sleep(400)
        runBlocking { model.awaitHistoryWrites() }
        assertEquals("Settings are not reading time", paused.totalReadingMillis,
            requireNotNull(store.getRecord(DemoBook.ID)).totalReadingMillis)

        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        awaitPage(2)
        compose.onNodeWithContentDescription("返回书库").performClick()
        awaitLibrary()
        runBlocking { model.awaitHistoryWrites() }
        compose.onNodeWithTag("library-book-grid")
            .performScrollToNode(hasTestTag("library-book-${DemoBook.ID}"))
        compose.onNodeWithTag("book-progress-${DemoBook.ID}", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("book-read-time-${DemoBook.ID}", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("book-remaining-${DemoBook.ID}", useUnmergedTree = true).assertExists()
        val saved = requireNotNull(store.getRecord(DemoBook.ID))
        assertEquals(saved.totalReadingMillis, store.getSessions(DemoBook.ID).sumOf { it.durationMillis })

        openDemo()
        awaitPage(2)
        assertEquals(saved.progress, BookRepository(context).getProgress(DemoBook.ID))
        compose.activityRule.scenario.recreate()
        awaitPage(2)
        runBlocking { model.awaitHistoryWrites() }
        val restored = requireNotNull(BookRepository(context).readingHistory.getRecord(DemoBook.ID))
        assertEquals(saved.progress, restored.progress)
        assertTrue(restored.totalReadingMillis >= saved.totalReadingMillis)
    }

    private fun awaitLibrary() {
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodes(hasTestTag("library-book-grid")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun openDemo() {
        awaitLibrary()
        val tag = "library-book-${DemoBook.ID}"
        compose.onNodeWithTag("library-book-grid").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
    }

    private fun awaitPage(page: Int) {
        val counter = Regex("第 1 / \\d+ 章 · $page / \\d+ 页")
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodes(SemanticsMatcher("reading page") { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { counter.matches(it.text) }
            }).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun key(code: Int) {
        compose.runOnIdle {
            val time = SystemClock.uptimeMillis()
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                compose.activity.dispatchKeyEvent(
                    KeyEvent(time, time, action, code, 0, 0,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD),
                )
            }
        }
        compose.waitForIdle()
    }

    companion object { private const val TIMEOUT = 15_000L }
}
