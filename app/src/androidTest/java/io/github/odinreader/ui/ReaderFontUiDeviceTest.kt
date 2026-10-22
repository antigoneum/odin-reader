package io.github.odinreader.ui

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.text.Spanned
import android.text.style.TypefaceSpan
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.odinreader.MainActivity
import io.github.odinreader.data.BookRepository
import io.github.odinreader.data.DemoBook
import io.github.odinreader.data.ReadingProgress
import io.github.odinreader.reader.Paginator
import io.github.odinreader.reader.ReaderFont
import io.github.odinreader.reader.ReaderFontSelection
import io.github.odinreader.reader.ReaderFonts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/** Native font selection, durable settings and font-dependent pagination on the actual device. */
@RunWith(AndroidJUnit4::class)
class ReaderFontUiDeviceTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences: SharedPreferences
        get() = context.getSharedPreferences("reader_settings", Context.MODE_PRIVATE)
    private lateinit var savedSettings: Map<String, *>
    private val historyGuard by lazy { ReadingHistoryTestGuard(context, DemoBook.ID) }
    private lateinit var model: ReaderViewModel
    private val isolatedState = object : ExternalResource() {
        override fun before() {
            historyGuard.before()
            savedSettings = preferences.all
            check(preferences.edit().clear().putFloat("font_size", 20f)
                .putString("reader_font", ReaderFont.SystemSerif.id).commit())
            BookRepository(context).saveProgress(DemoBook.ID, ReadingProgress())
        }

        override fun after() {
            historyGuard.after()
            val editor = preferences.edit().clear()
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
    @get:Rule val rules: TestRule = RuleChain.outerRule(isolatedState).around(compose)

    @Before
    fun captureActualModel() {
        model = compose.runOnIdle { ViewModelProvider(compose.activity)[ReaderViewModel::class.java] }
        historyGuard.attachModel(model)
    }

    @Test
    fun gamepadChoosesIndependentFontsAndFreshModelReloadsBoth() {
        awaitLibrary()
        gamepad(KeyEvent.KEYCODE_BUTTON_START)
        openFontPicker()
        focusFont(ReaderFont.SystemSerif)
        gamepad(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag(fontTag(ReaderFont.SystemSans)).assertIsFocused()
        gamepad(KeyEvent.KEYCODE_BUTTON_A)
        compose.onNodeWithTag(fontTag(ReaderFont.SystemSans)).assertIsSelected()
        assertEquals(ReaderFont.SystemSans.id, preferences.getString("reader_font_chinese", null))
        assertEquals("Changing Chinese must leave English unchanged", ReaderFont.SystemSerif,
            compose.runOnIdle { model.state.value.readerFonts.english })
        selectFont(ReaderFont.LxgwWenKai, chinese = true)
        selectFont(ReaderFont.JetBrainsMono, chinese = false)
        val selected = ReaderFontSelection(ReaderFont.LxgwWenKai, ReaderFont.JetBrainsMono)
        assertEquals(ReaderFont.JetBrainsMono.id, preferences.getString("reader_font_english", null))
        assertEquals(selected, freshSettings().readerFonts)

        // Closing the font subpage returns to settings; its entry remains the controller target.
        gamepad(KeyEvent.KEYCODE_BUTTON_B)
        compose.onNodeWithTag("reader-font-picker").assertIsFocused()
        compose.activityRule.scenario.recreate()
        awaitSettings()
        openFontPicker()
        chooseLanguage(chinese = false)
        compose.onNodeWithTag("reader-font-list")
            .performScrollToNode(hasTestTag(fontTag(ReaderFont.JetBrainsMono)))
        compose.onNodeWithTag(fontTag(ReaderFont.JetBrainsMono)).assertIsSelected()
        awaitPreview(selected)
    }

    @Test
    fun changingChineseFontKeepsEnglishAndReflowsWithoutLosingCharacter() {
        awaitLibrary()
        compose.onNodeWithTag("library-book-grid")
            .performScrollToNode(hasTestTag("library-book-${DemoBook.ID}"))
        compose.onNodeWithTag("library-book-${DemoBook.ID}").performClick()
        awaitBody(ReaderFontSelection())
        gamepad(KeyEvent.KEYCODE_BUTTON_R1)
        awaitPage(2)
        val store = BookRepository(context)
        val original = store.getProgress(DemoBook.ID)
        assertTrue("The font test must start from a nonzero bookmark", original.charOffset > 0)
        val originalText = compose.runOnIdle { requireNotNull(body()).text.toString() }

        for (font in listOf(ReaderFont.LxgwWenKai, ReaderFont.NotoSerif)) {
            gamepad(KeyEvent.KEYCODE_BUTTON_START)
            openFontPicker()
            selectFont(font, chinese = true)
            if (font == ReaderFont.LxgwWenKai) {
                selectFont(ReaderFont.JetBrainsMono, chinese = false)
            } else {
                assertEquals("Changing only Chinese must leave English selected", ReaderFont.JetBrainsMono,
                    compose.runOnIdle { model.state.value.readerFonts.english })
            }
            gamepad(KeyEvent.KEYCODE_BUTTON_B)
            gamepad(KeyEvent.KEYCODE_BUTTON_B)
            val selection = ReaderFontSelection(font, ReaderFont.JetBrainsMono)
            awaitBody(selection)
            assertEquals("Changing Chinese must preserve the chosen English font",
                ReaderFont.JetBrainsMono.id, preferences.getString("reader_font_english", null))
            val progress = store.getProgress(DemoBook.ID)
            assertEquals("Changing font must preserve the chapter", original.chapterIndex, progress.chapterIndex)
            assertEquals("Changing font must preserve the exact character anchor", original.charOffset, progress.charOffset)
            assertBodyMatchesPagination(selection, progress)
        }

        compose.activityRule.scenario.recreate()
        awaitBody(ReaderFontSelection(ReaderFont.NotoSerif, ReaderFont.JetBrainsMono))
        assertEquals(original.charOffset, store.getProgress(DemoBook.ID).charOffset)

        gamepad(KeyEvent.KEYCODE_BUTTON_START)
        openFontPicker()
        selectFont(ReaderFont.SystemSerif, chinese = true)
        selectFont(ReaderFont.SystemSerif, chinese = false)
        gamepad(KeyEvent.KEYCODE_BUTTON_B)
        gamepad(KeyEvent.KEYCODE_BUTTON_B)
        awaitBody(ReaderFontSelection())
        val restored = store.getProgress(DemoBook.ID)
        assertEquals("A font round trip must preserve the original page", original.pageIndex, restored.pageIndex)
        assertEquals(original.charOffset, restored.charOffset)
        assertEquals(originalText, compose.runOnIdle { requireNotNull(body()).text.toString() })
    }

    @Test
    fun freshModelMigratesLegacyFontAndHonorsOnlyTheExplicitSlot() {
        awaitLibrary()
        check(preferences.edit().remove("reader_font_chinese").remove("reader_font_english")
            .putString("reader_font", ReaderFont.NotoSerif.id).commit())
        assertEquals(ReaderFontSelection(ReaderFont.NotoSerif, ReaderFont.NotoSerif), freshSettings().readerFonts)
        check(preferences.edit().putString("reader_font", ReaderFont.JetBrainsMono.id).commit())
        assertEquals(ReaderFontSelection(ReaderFont.SystemSans, ReaderFont.JetBrainsMono), freshSettings().readerFonts)
        check(preferences.edit().putString("reader_font_chinese", ReaderFont.LxgwWenKai.id).commit())
        assertEquals(ReaderFontSelection(ReaderFont.LxgwWenKai, ReaderFont.JetBrainsMono), freshSettings().readerFonts)
        check(preferences.edit().putString("reader_font_english", "unknown-future-font").commit())
        assertEquals("An invalid slot must fall back without overriding the valid other slot",
            ReaderFontSelection(ReaderFont.LxgwWenKai, ReaderFont.JetBrainsMono), freshSettings().readerFonts)
    }

    private fun assertBodyMatchesPagination(selection: ReaderFontSelection, progress: ReadingProgress) {
        val measurements = compose.runOnIdle {
            val view = requireNotNull(body())
            BodyMeasurement(view.width, view.height, view.textSize, view.text.toString())
        }
        val typography = requireNotNull(ReaderFonts.getCached(selection))
        val pages = Paginator.paginate(DemoBook.content.chapters[progress.chapterIndex].text,
            measurements.width, measurements.height, measurements.textSize, typography = typography)
        assertEquals("Displayed body must use the selected font's measured page", pages[progress.pageIndex].text, measurements.text)
        assertTrue(progress.charOffset in pages[progress.pageIndex].startOffset until pages[progress.pageIndex].endOffset)
    }

    private fun freshSettings(): ReaderUiState = compose.runOnIdle {
        val models = ViewModelStore()
        try {
            val factory = ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application)
            val fresh = ViewModelProvider(models, factory)[ReaderViewModel::class.java]
            historyGuard.attachModel(fresh)
            fresh.state.value
        } finally {
            models.clear()
        }
    }

    private fun openFontPicker() {
        awaitSettings()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("reader-font-picker"))
        compose.onNodeWithTag("reader-font-picker").performClick()
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodes(hasTestTag("reader-font-list")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun chooseLanguage(chinese: Boolean) {
        compose.onNodeWithTag(if (chinese) "font-language-chinese" else "font-language-english")
            .performClick().assertIsSelected()
    }

    private fun selectFont(font: ReaderFont, chinese: Boolean) {
        chooseLanguage(chinese)
        focusFont(font)
        gamepad(KeyEvent.KEYCODE_BUTTON_A)
        compose.onNodeWithTag(fontTag(font)).assertIsSelected()
        assertEquals(font.id, preferences.getString(if (chinese) "reader_font_chinese" else "reader_font_english", null))
        awaitPreview(compose.runOnIdle { model.state.value.readerFonts })
    }

    private fun focusFont(font: ReaderFont) {
        // A real controller event leaves touch mode before requesting a Material button's focus.
        // Switch modes before scrolling: moving the old focus afterwards can scroll the target away.
        gamepad(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("reader-font-list").performScrollToNode(hasTestTag(fontTag(font)))
        compose.onNodeWithTag(fontTag(font)).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag(fontTag(font)).assertIsFocused()
    }

    private fun awaitPreview(selection: ReaderFontSelection) {
        compose.waitUntil(TIMEOUT) {
            compose.runOnIdle {
                descendants(compose.activity.window.decorView).filterIsInstance<TextView>()
                    .firstOrNull { it.tag == "reader-font-preview-text" }?.let { matchesTypography(it, selection) } == true
            }
        }
        compose.onNodeWithTag("reader-font-preview").assertExists()
    }

    private fun awaitBody(selection: ReaderFontSelection) {
        compose.waitUntil(TIMEOUT) {
            compose.runOnIdle {
                body()?.let { matchesTypography(it, selection) } == true
            }
        }
        compose.waitForIdle()
        historyGuard.awaitWrites()
    }

    /** Both the English base and CJK span must change before a Chinese reflow is ready. */
    private fun matchesTypography(view: TextView, selection: ReaderFontSelection): Boolean {
        val typography = ReaderFonts.getCached(selection) ?: return false
        if (view.text.isEmpty() || view.typeface != typography.englishTypeface) return false
        val chineseIndex = view.text.indexOfFirst { it in '\u4E00'..'\u9FFF' }
        if (chineseIndex >= 0) {
            val spans = (view.text as? Spanned)?.getSpans(chineseIndex, chineseIndex + 1, TypefaceSpan::class.java).orEmpty()
            if (typography.chineseTypeface == typography.englishTypeface) {
                if (spans.isNotEmpty()) return false
            } else if (spans.none { it.typeface == typography.chineseTypeface }) return false
        }
        val latinIndex = view.text.indexOfFirst { it in 'A'..'Z' || it in 'a'..'z' }
        if (latinIndex >= 0) {
            val spans = (view.text as? Spanned)?.getSpans(latinIndex, latinIndex + 1, TypefaceSpan::class.java).orEmpty()
            if (spans.any { it.typeface != typography.englishTypeface }) return false
        }
        return true
    }

    private fun body(): TextView? = descendants(compose.activity.window.decorView)
        .filterIsInstance<TextView>().firstOrNull { it.tag != "reader-font-preview-text" }

    private fun awaitLibrary() {
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodes(hasTestTag("library-import")).fetchSemanticsNodes().any {
                it.config.getOrNull(SemanticsProperties.Disabled) == null
            }
        }
        compose.waitForIdle()
    }

    private fun awaitSettings() {
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodes(hasTestTag("settings-list")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun awaitPage(page: Int) {
        val counter = Regex("""第 1 / \d+ 章 · $page / \d+ 页""")
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodes(SemanticsMatcher("reader page counter") { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { counter.matches(it.text) }
            }).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        historyGuard.awaitWrites()
    }

    private fun gamepad(code: Int) {
        compose.runOnIdle {
            val time = SystemClock.uptimeMillis()
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                compose.activity.dispatchKeyEvent(KeyEvent(time, time, action, code, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_GAMEPAD))
            }
        }
        compose.waitForIdle()
    }

    private fun fontTag(font: ReaderFont) = "reader-font-${font.id}"

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private data class BodyMeasurement(val width: Int, val height: Int, val textSize: Float, val text: String)
    private companion object { const val TIMEOUT = 20_000L }
}
