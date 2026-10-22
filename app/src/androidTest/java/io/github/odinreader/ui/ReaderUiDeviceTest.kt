package io.github.odinreader.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToLog
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.odinreader.MainActivity
import io.github.odinreader.data.BookRepository
import io.github.odinreader.data.DemoBook
import io.github.odinreader.data.ReadingProgress
import io.github.odinreader.input.ControllerButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * Exercises the installed app on Android, including its real TextView renderer.
 * Controller events are software-injected with Android gamepad/D-pad sources;
 * these tests do not certify physical buttons, analog axes, or hardware dead zones.
 */
@RunWith(AndroidJUnit4::class)
class ReaderUiDeviceTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var savedProgressValues: Map<String, Any?>
    private lateinit var savedSettingValues: Map<String, Any?>

    private val isolatedDemoState = object : ExternalResource() {
        override fun before() {
            val progress = context.getSharedPreferences(PROGRESS_PREFS, Context.MODE_PRIVATE)
            val settings = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            savedProgressValues = progressKeys.associateWith { progress.all[it] }
            savedSettingValues = settingKeys.associateWith { settings.all[it] }
            BookRepository(context).saveProgress(DemoBook.ID, ReadingProgress())
            val editor = settings.edit()
            settingKeys.forEach { editor.remove(it) }
            check(editor.putFloat("font_size", 20f).putInt("paper_style", 1).commit())
        }

        override fun after() {
            // The inner Compose/Activity rule has already closed the reader and
            // disposed its effects. Restore only these demo/settings keys, not
            // the user's imported books or their reading positions.
            restorePreferences(context.getSharedPreferences(PROGRESS_PREFS, Context.MODE_PRIVATE), savedProgressValues)
            restorePreferences(context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE), savedSettingValues)
        }
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(isolatedDemoState).around(compose)

    @Test
    fun gamepadPaginationAndChapterBoundaryRoundTrip() {
        openDemo()
        val first = awaitCounter(chapter = 1, page = 1)
        assertTrue("The Odin demo must span multiple pages", first.pageCount > 1)

        gamepad(KeyEvent.KEYCODE_BUTTON_R1)
        awaitCounter(chapter = 1, page = 2)
        gamepad(KeyEvent.KEYCODE_BUTTON_L1)
        awaitCounter(chapter = 1, page = 1)

        gamepad(KeyEvent.KEYCODE_BUTTON_R2)
        awaitCounter(chapter = 2, page = 1)
        gamepad(KeyEvent.KEYCODE_BUTTON_L2)
        awaitCounter(chapter = 1, page = 1)

        for (page in 2..first.pageCount) {
            gamepad(KeyEvent.KEYCODE_BUTTON_R1)
            awaitCounter(chapter = 1, page = page)
        }
        gamepad(KeyEvent.KEYCODE_BUTTON_R1)
        awaitCounter(chapter = 2, page = 1)
        gamepad(KeyEvent.KEYCODE_BUTTON_L1)
        awaitCounter(chapter = 1, page = first.pageCount)
    }

    @Test
    fun controllerFocusAndPanelRoutes() {
        awaitLibrary()
        focusDemoWithDpad()
        gamepad(KeyEvent.KEYCODE_BUTTON_A)
        awaitCounter(chapter = 1, page = 1)

        gamepad(KeyEvent.KEYCODE_BUTTON_X)
        awaitDescription("隐藏控制栏", present = true)
        key(KeyEvent.KEYCODE_DPAD_RIGHT, InputDevice.SOURCE_DPAD)
        gamepad(KeyEvent.KEYCODE_BUTTON_A)
        awaitText("章节目录")

        gamepad(KeyEvent.KEYCODE_BUTTON_B)
        awaitDescription("隐藏控制栏", present = true)
        gamepad(KeyEvent.KEYCODE_BUTTON_Y)
        awaitText("章节目录")
        gamepad(KeyEvent.KEYCODE_BUTTON_B)
        gamepad(KeyEvent.KEYCODE_BUTTON_START)
        awaitText("阅读设置")
        gamepad(KeyEvent.KEYCODE_BUTTON_B)
        awaitDescription("隐藏控制栏", present = true)
        gamepad(KeyEvent.KEYCODE_BUTTON_X)
        awaitDescription("隐藏控制栏", present = false)
        gamepad(KeyEvent.KEYCODE_BUTTON_B)
        awaitLibrary()
    }

    @Test
    fun nativeTextViewEdgeTapsAndSwipesFlipExactlyOnePage() {
        openDemo()
        val bounds = nativeBodyBounds()
        compose.onRoot().performTouchInput { click(point(bounds, 0.84f)) }
        awaitCounter(chapter = 1, page = 2)
        compose.onRoot().performTouchInput { click(point(bounds, 0.16f)) }
        awaitCounter(chapter = 1, page = 1)
        compose.onRoot().performTouchInput {
            swipe(point(bounds, 0.84f), point(bounds, 0.16f), durationMillis = 300L)
        }
        awaitCounter(chapter = 1, page = 2)
        compose.onRoot().performTouchInput {
            swipe(point(bounds, 0.16f), point(bounds, 0.84f), durationMillis = 300L)
        }
        awaitCounter(chapter = 1, page = 1)
    }

    @Test
    fun closingAndRecreatingReaderPreserveTheVisiblePassage() {
        openDemo()
        gamepad(KeyEvent.KEYCODE_BUTTON_R2)
        awaitCounter(chapter = 2, page = 1)
        gamepad(KeyEvent.KEYCODE_BUTTON_R1)
        val expectedCounter = awaitCounter(chapter = 2, page = 2)
        val expectedText = nativeBodyText()
        val expectedProgress = BookRepository(context).getProgress(DemoBook.ID)

        gamepad(KeyEvent.KEYCODE_BUTTON_B)
        awaitLibrary()
        openDemo(expectedChapter = 2, expectedPage = 2)
        assertEquals(expectedCounter, counterOrNull())
        assertEquals(expectedText, nativeBodyText())
        assertEquals(expectedProgress, BookRepository(context).getProgress(DemoBook.ID))

        compose.activityRule.scenario.recreate()
        awaitCounter(chapter = 2, page = 2)
        assertEquals(expectedText, nativeBodyText())
        assertEquals(expectedProgress, BookRepository(context).getProgress(DemoBook.ID))
    }

    @Test
    fun fontSizeRoundTripPreservesPageAndCharacterAnchor() {
        openDemo()
        gamepad(KeyEvent.KEYCODE_BUTTON_R1)
        val expectedCounter = awaitCounter(chapter = 1, page = 2)
        val expectedText = nativeBodyText()
        val repository = BookRepository(context)
        val expectedProgress = repository.getProgress(DemoBook.ID)
        assertTrue(expectedProgress.charOffset > 0)

        gamepad(KeyEvent.KEYCODE_BUTTON_START)
        awaitText("阅读设置")
        compose.onNodeWithText("A+").performClick()
        awaitText("22 sp")
        compose.waitUntil(TIMEOUT) {
            val current = nativeBodyText()
            current.isNotBlank() && current != expectedText
        }
        compose.waitForIdle()
        assertEquals("Font reflow must not replace the anchor with an earlier page start",
            expectedProgress.charOffset, repository.getProgress(DemoBook.ID).charOffset)

        compose.onNodeWithText("A−").performClick()
        awaitText("20 sp")
        compose.waitUntil(TIMEOUT) { nativeBodyText() == expectedText }
        compose.waitForIdle()
        assertEquals(expectedCounter, counterOrNull())
        assertEquals(expectedProgress, repository.getProgress(DemoBook.ID))
        gamepad(KeyEvent.KEYCODE_BUTTON_B)
        awaitCounter(chapter = 1, page = 2)
    }

    private fun focusDemoWithDpad() {
        val bookCard = hasClickAction() and SemanticsMatcher("book card metadata") { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty()
                .any { Regex(""" · \d+ 章$""").containsMatchIn(it.text) }
        }
        val focusedBook = bookCard and isFocused()
        val demoCard = hasClickAction() and hasText(DEMO_TITLE)
        fun demoHasFocus(): Boolean =
            compose.onAllNodes(demoCard and isFocused()).fetchSemanticsNodes().isNotEmpty()

        try {
            key(KeyEvent.KEYCODE_DPAD_DOWN, InputDevice.SOURCE_DPAD)
            compose.waitUntil(TIMEOUT) {
                compose.onAllNodes(focusedBook).fetchSemanticsNodes().isNotEmpty()
            }
            // Imports precede the demo. Follow actual card geometry rather
            // than assuming one Down from the import button selects the demo.
            repeat(12) {
                if (demoHasFocus()) return
                val current = compose.onAllNodes(focusedBook).fetchSemanticsNodes().firstOrNull()
                val target = compose.onAllNodes(demoCard).fetchSemanticsNodes().firstOrNull()
                val direction = when {
                    current == null || target == null -> KeyEvent.KEYCODE_DPAD_DOWN
                    target.boundsInRoot.center.y > current.boundsInRoot.center.y + 1f ->
                        KeyEvent.KEYCODE_DPAD_DOWN
                    target.boundsInRoot.center.y < current.boundsInRoot.center.y - 1f ->
                        KeyEvent.KEYCODE_DPAD_UP
                    target.boundsInRoot.center.x > current.boundsInRoot.center.x ->
                        KeyEvent.KEYCODE_DPAD_RIGHT
                    else -> KeyEvent.KEYCODE_DPAD_LEFT
                }
                key(direction, InputDevice.SOURCE_DPAD)
            }
            assertTrue("D-pad navigation must reach the demo before A confirms it", demoHasFocus())
        } catch (failure: Throwable) {
            runCatching { compose.onRoot(useUnmergedTree = true).printToLog("OdinReaderUiTest") }
            throw failure
        }
    }

    private fun awaitLibrary() {
        awaitText("把时间，留给阅读")
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodesWithText("导入书籍").fetchSemanticsNodes().any {
                it.config.getOrNull(SemanticsProperties.Disabled) == null
            }
        }
    }

    private fun openDemo(expectedChapter: Int = 1, expectedPage: Int = 1) {
        awaitLibrary()
        compose.onAllNodes(hasClickAction() and hasText(DEMO_TITLE)).fetchSemanticsNodes()
            .also { assertTrue("Demo book must be visible in the library", it.isNotEmpty()) }
        compose.onAllNodes(hasClickAction() and hasText(DEMO_TITLE))[0].performClick()
        awaitCounter(chapter = expectedChapter, page = expectedPage)
        compose.waitUntil(TIMEOUT) { nativeBodyText().isNotBlank() }
    }

    private fun gamepad(code: Int) = key(code, InputDevice.SOURCE_GAMEPAD)

    private fun key(code: Int, source: Int) {
        compose.runOnIdle {
            val time = SystemClock.uptimeMillis()
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                compose.activity.dispatchKeyEvent(
                    KeyEvent(time, time, action, code, 0, 0,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, source),
                )
            }
        }
        compose.waitForIdle()
    }

    private fun awaitText(text: String) {
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitDescription(description: String, present: Boolean) {
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() == present
        }
    }

    private fun awaitCounter(chapter: Int, page: Int): Counter {
        compose.waitUntil(TIMEOUT) {
            counterOrNull()?.let { it.chapter == chapter && it.page == page } == true
        }
        compose.waitForIdle()
        return checkNotNull(counterOrNull())
    }

    private fun counterOrNull(): Counter? {
        val texts = compose.onAllNodes(pageCounterMatcher).fetchSemanticsNodes()
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
        val match = texts.firstNotNullOfOrNull { COUNTER.matchEntire(it.text) } ?: return null
        val values = match.groupValues.drop(1).map(String::toInt)
        return Counter(values[0], values[1], values[2], values[3])
    }

    private fun nativeBodyText(): String = compose.runOnIdle {
        descendants(compose.activity.window.decorView).filterIsInstance<TextView>()
            .firstOrNull()?.text?.toString().orEmpty()
    }

    private fun nativeBodyBounds(): Rect = compose.runOnIdle {
        val views = descendants(compose.activity.window.decorView).toList()
        val body = views.filterIsInstance<TextView>().first()
        val composeView = views.filterIsInstance<AbstractComposeView>().first()
        val bodyLocation = IntArray(2)
        val composeLocation = IntArray(2)
        body.getLocationOnScreen(bodyLocation)
        composeView.getLocationOnScreen(composeLocation)
        val left = (bodyLocation[0] - composeLocation[0]).toFloat()
        val top = (bodyLocation[1] - composeLocation[1]).toFloat()
        assertTrue("Native text viewport must be laid out", body.width > 0 && body.height > 0)
        Rect(left, top, left + body.width, top + body.height)
    }

    private fun point(bounds: Rect, fractionX: Float): Offset =
        Offset(bounds.left + bounds.width * fractionX, bounds.top + bounds.height * 0.5f)

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
    }

    private fun restorePreferences(preferences: SharedPreferences, values: Map<String, Any?>) {
        val editor = preferences.edit()
        values.forEach { (key, value) ->
            when (value) {
                null -> editor.remove(key)
                is Int -> editor.putInt(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                else -> error("Unexpected preference type for $key")
            }
        }
        check(editor.commit())
    }

    private data class Counter(val chapter: Int, val chapterCount: Int, val page: Int, val pageCount: Int)

    private companion object {
        const val TIMEOUT = 15_000L
        const val DEMO_TITLE = "沿着山脊去看海"
        const val PROGRESS_PREFS = "reading_progress"
        const val SETTINGS_PREFS = "reader_settings"
        val progressKeys = listOf(DemoBook.ID + ".chapter", DemoBook.ID + ".page", DemoBook.ID + ".offset")
        val settingKeys = listOf("font_size", "paper_style", "app_theme") +
            ControllerButton.entries.map { "binding.${it.name}" }
        val COUNTER = Regex("""第 (\d+) / (\d+) 章 · (\d+) / (\d+) 页""")
        val pageCounterMatcher = SemanticsMatcher("reader chapter/page counter") { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { COUNTER.matches(it.text) }
        }
    }
}
