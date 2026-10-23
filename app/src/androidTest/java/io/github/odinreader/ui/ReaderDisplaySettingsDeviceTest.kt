package io.github.odinreader.ui

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.SystemClock
import android.text.Spanned
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
import io.github.odinreader.reader.PageTurnAnimation
import io.github.odinreader.reader.ReaderFont
import io.github.odinreader.reader.ReaderFontSelection
import io.github.odinreader.reader.ReaderFonts
import io.github.odinreader.reader.ReadingMode
import io.github.odinreader.reader.ReaderLineSpacing
import io.github.odinreader.reader.ReaderLetterSpacing
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

/** Settings and character bookmarks are isolated from every imported user book. */
@RunWith(AndroidJUnit4::class)
class ReaderDisplaySettingsDeviceTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences: SharedPreferences
        get() = context.getSharedPreferences("reader_settings", Context.MODE_PRIVATE)
    private val historyGuard by lazy { ReadingHistoryTestGuard(context, DemoBook.ID) }
    private lateinit var savedSettings: Map<String, *>
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
            // The inner Activity rule has ended and its final IO writes must drain
            // before restoring the demo's record and the user's complete settings.
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
    fun attachActualModel() {
        model = compose.runOnIdle { ViewModelProvider(compose.activity)[ReaderViewModel::class.java] }
        historyGuard.attachModel(model)
    }

    @Test
    fun gamepadChoosesModeAndEachAnimationSurvivesReloadAndRecreation() {
        awaitLibrary()
        gamepad(KeyEvent.KEYCODE_BUTTON_START)
        compose.onNodeWithTag("reader-font-size-increase").performClick()
        assertEquals(22f, freshSettings().fontSize, 0f)
        compose.onNodeWithTag("reader-font-size-decrease").performClick()
        assertEquals(20f, freshSettings().fontSize, 0f)
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("reader-line-spacing-increase"))
        compose.onNodeWithTag("reader-line-spacing-increase").performClick()
        assertEquals(1.75f, preferences.getFloat("line_spacing", 0f), 0.0001f)
        assertEquals(1.75f, freshSettings().lineSpacing, 0.0001f)
        compose.onNodeWithTag("reader-line-spacing-default").performClick()
        assertEquals(ReaderLineSpacing.Default, preferences.getFloat("line_spacing", 0f), 0f)
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("reader-letter-spacing-increase"))
        compose.onNodeWithTag("reader-letter-spacing-increase").performClick()
        assertEquals(0.01f, preferences.getFloat("letter_spacing", 0f), 0.0001f)
        assertEquals(0.01f, freshSettings().letterSpacing, 0.0001f)
        compose.onNodeWithTag("reader-letter-spacing-default").performClick()
        assertEquals(ReaderLetterSpacing.Default, preferences.getFloat("letter_spacing", -1f), 0f)
        openDisplayPicker()
        compose.onNodeWithTag("reading-mode-dual_page").assertDoesNotExist()
        chooseMode(ReadingMode.DualColumn)
        compose.onNodeWithTag(animationTag(PageTurnAnimation.Fade)).performClick().assertIsSelected()
        chooseMode(ReadingMode.ZoomPreview)
        compose.onNodeWithTag(animationTag(PageTurnAnimation.ZoomSlide)).assertIsSelected()
        compose.onNodeWithTag(animationTag(PageTurnAnimation.None)).performClick().assertIsSelected()
        chooseMode(ReadingMode.DualColumn)
        compose.onNodeWithTag(animationTag(PageTurnAnimation.Fade)).assertIsSelected()
        assertEquals(ReadingMode.DualColumn.id, preferences.getString("reading_mode", null))
        val reloaded = freshSettings()
        assertEquals(ReadingMode.DualColumn, reloaded.readingMode)
        assertEquals(PageTurnAnimation.Fade, reloaded.pageAnimations[ReadingMode.DualColumn])
        assertEquals(PageTurnAnimation.None, reloaded.pageAnimations[ReadingMode.ZoomPreview])
        assertEquals(ReadingMode.Single.defaultAnimation, reloaded.pageAnimations[ReadingMode.Single])

        gamepad(KeyEvent.KEYCODE_BUTTON_B)
        compose.onNodeWithTag("reader-display-picker").assertIsFocused()
        compose.activityRule.scenario.recreate()
        awaitSettings()
        openDisplayPicker()
        compose.onNodeWithTag(modeTag(ReadingMode.DualColumn)).assertIsSelected()
        compose.onNodeWithTag(animationTag(PageTurnAnimation.Fade)).assertIsSelected()
    }

    @Test
    fun invalidStoredChoicesFallBackIndependentlyAndPreserveFontSelection() {
        awaitLibrary()
        check(preferences.edit().putString("reading_mode", "unknown-future-mode")
            .putString("page_animation.dual_column", "unknown-future-animation")
            .putString("page_animation.single", PageTurnAnimation.None.id)
            .putString("page_animation.zoom_preview", PageTurnAnimation.Fade.id)
            .putFloat("line_spacing", Float.NaN)
            .putFloat("letter_spacing", Float.POSITIVE_INFINITY)
            .putString("reader_font_chinese", ReaderFont.LxgwWenKai.id)
            .putString("reader_font_english", ReaderFont.JetBrainsMono.id).commit())
        val reloaded = freshSettings()
        assertEquals(ReadingMode.Single, reloaded.readingMode)
        assertEquals(ReaderLineSpacing.Default, reloaded.lineSpacing, 0f)
        assertEquals(ReaderLetterSpacing.Default, reloaded.letterSpacing, 0f)
        assertEquals(ReadingMode.DualColumn.defaultAnimation, reloaded.pageAnimations[ReadingMode.DualColumn])
        assertEquals(PageTurnAnimation.None, reloaded.pageAnimations[ReadingMode.Single])
        assertEquals(PageTurnAnimation.Fade, reloaded.pageAnimations[ReadingMode.ZoomPreview])
        assertEquals(ReaderFontSelection(ReaderFont.LxgwWenKai, ReaderFont.JetBrainsMono), reloaded.readerFonts)
        assertEquals("Invalid future values should not be rewritten during loading", "unknown-future-mode",
            preferences.getString("reading_mode", null))
    }

    @Test
    fun legacyDualPageBecomesDualColumnAndKeepsItsBookTurnDefaultAfterAnExplicitModeChange() {
        awaitLibrary()
        check(preferences.edit().putString("reading_mode", "dual_page").commit())
        val inherited = freshSettings()
        assertEquals(ReadingMode.DualColumn, inherited.readingMode)
        assertEquals(PageTurnAnimation.PageTurn, inherited.pageAnimations[ReadingMode.DualColumn])
        assertEquals("Loading legacy settings must be read-only", "dual_page",
            preferences.getString("reading_mode", null))
        assertTrue("Loading must not create a new animation setting",
            !preferences.contains("page_animation.dual_column"))

        // Activity recreation usually retains its ViewModel. Discard that
        // instance here to exercise loading the legacy disk state on restart.
        compose.runOnIdle { compose.activity.viewModelStore.clear() }
        compose.activityRule.scenario.recreate()
        awaitLibrary()
        model = compose.runOnIdle { ViewModelProvider(compose.activity)[ReaderViewModel::class.java] }
        historyGuard.attachModel(model)
        gamepad(KeyEvent.KEYCODE_BUTTON_START)
        openDisplayPicker()
        compose.onNodeWithTag("reading-mode-dual_page").assertDoesNotExist()
        compose.onNodeWithTag(modeTag(ReadingMode.DualColumn)).assertIsSelected()
        compose.onNodeWithTag(animationTag(PageTurnAnimation.PageTurn)).assertIsSelected()
        chooseMode(ReadingMode.Preview)
        chooseMode(ReadingMode.DualColumn)
        compose.onNodeWithTag(animationTag(PageTurnAnimation.PageTurn)).assertIsSelected()
        assertEquals(ReadingMode.DualColumn.id, preferences.getString("reading_mode", null))
        assertEquals(PageTurnAnimation.PageTurn.id, preferences.getString("page_animation.dual_column", null))
        val reloaded = freshSettings()
        assertEquals(ReadingMode.DualColumn, reloaded.readingMode)
        assertEquals(PageTurnAnimation.PageTurn, reloaded.pageAnimations[ReadingMode.DualColumn])

        compose.activityRule.scenario.recreate()
        awaitSettings()
        openDisplayPicker()
        compose.onNodeWithTag(modeTag(ReadingMode.DualColumn)).assertIsSelected()
        compose.onNodeWithTag(animationTag(PageTurnAnimation.PageTurn)).assertIsSelected()
    }

    @Test
    fun legacyAnimationIsInheritedUnlessAValidExplicitDualColumnChoiceExists() {
        awaitLibrary()
        check(preferences.edit().putString("reading_mode", "dual_page")
            .putString("page_animation.dual_page", PageTurnAnimation.Fade.id).commit())
        assertEquals(PageTurnAnimation.Fade, freshSettings().pageAnimations[ReadingMode.DualColumn])
        check(preferences.edit().putString("page_animation.dual_column", "unknown-future-animation").commit())
        assertEquals("An invalid new choice must not discard a valid legacy animation", PageTurnAnimation.Fade,
            freshSettings().pageAnimations[ReadingMode.DualColumn])
        check(preferences.edit().putString("page_animation.dual_column", PageTurnAnimation.None.id).commit())
        val reloaded = freshSettings()
        assertEquals(ReadingMode.DualColumn, reloaded.readingMode)
        assertEquals("An explicit dual-column choice takes precedence", PageTurnAnimation.None,
            reloaded.pageAnimations[ReadingMode.DualColumn])
        assertEquals("Loading must preserve the legacy value", PageTurnAnimation.Fade.id,
            preferences.getString("page_animation.dual_page", null))
        assertEquals("Loading must preserve the explicit new value", PageTurnAnimation.None.id,
            preferences.getString("page_animation.dual_column", null))
        assertEquals("dual_page", preferences.getString("reading_mode", null))
    }

    @Test
    fun typographyPreviewStaysVisibleAndUsesEveryLiveSettingIncludingBothFonts() {
        awaitLibrary()
        gamepad(KeyEvent.KEYCODE_BUTTON_START)
        val fonts = ReaderFontSelection(ReaderFont.LxgwWenKai, ReaderFont.JetBrainsMono)
        compose.runOnIdle {
            model.setChineseFont(fonts.chinese)
            model.setEnglishFont(fonts.english)
        }
        awaitTypographyPreview("reader-typography-preview-text", fonts, 20f, 1.65f, 0f)
        val previewBounds = compose.onNodeWithTag("reader-typography-preview").fetchSemanticsNode().boundsInRoot
        val fontControls = compose.onNodeWithTag("reader-font-size-increase").fetchSemanticsNode().boundsInRoot
        assertTrue("Landscape controls and preview must be visible side by side", previewBounds.left > fontControls.right)

        compose.onNodeWithTag("reader-font-size-increase").performClick()
        awaitTypographyPreview("reader-typography-preview-text", fonts, 22f, 1.65f, 0f)
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("reader-line-spacing-increase"))
        compose.onNodeWithTag("reader-line-spacing-increase").performClick()
        awaitTypographyPreview("reader-typography-preview-text", fonts, 22f, 1.75f, 0f)
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("reader-letter-spacing-increase"))
        compose.onNodeWithTag("reader-letter-spacing-increase").performClick()
        awaitTypographyPreview("reader-typography-preview-text", fonts, 22f, 1.75f, 0.01f)
        assertEquals("Scrolling controls must not scroll away the preview", previewBounds,
            compose.onNodeWithTag("reader-typography-preview").fetchSemanticsNode().boundsInRoot)
        captureTypographyIfRequested("live")

        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("reader-font-picker"))
        compose.onNodeWithTag("reader-font-picker").performClick()
        awaitTypographyPreview("reader-font-preview-text", fonts, 22f, 1.75f, 0.01f)
        captureTypographyIfRequested("fonts")
        gamepad(KeyEvent.KEYCODE_BUTTON_B)
        compose.onNodeWithTag("reader-font-picker").assertIsFocused()
        awaitTypographyPreview("reader-typography-preview-text", fonts, 22f, 1.75f, 0.01f)
    }

    @Test
    fun largestTypographyPreviewLaysOutItsFullSampleAndDoesNotCaptureGamepadFocus() {
        awaitLibrary()
        gamepad(KeyEvent.KEYCODE_BUTTON_START)
        val fonts = ReaderFontSelection(ReaderFont.NotoSerif, ReaderFont.JetBrainsMono)
        compose.runOnIdle {
            model.setChineseFont(fonts.chinese)
            model.setEnglishFont(fonts.english)
            model.setFontSize(32f)
            model.setLineSpacing(2.4f)
            model.setLetterSpacing(0.2f)
        }
        awaitTypographyPreview("reader-typography-preview-text", fonts, 32f, 2.4f, 0.2f)
        captureTypographyIfRequested("large")
        compose.runOnIdle {
            val native = requireNotNull(previewText("reader-typography-preview-text"))
            val layout = requireNotNull(native.layout)
            assertEquals("The scrollable preview must lay out all sample characters", native.text.length,
                layout.getLineEnd(layout.lineCount - 1))
            assertTrue("The sample should visibly exercise multiple lines", layout.lineCount > 3)
            assertEquals(null, native.ellipsize)
            assertTrue("The preview must not steal the controller's settings focus", !native.isFocusable)
        }
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("reader-font-size-decrease"))
        gamepad(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("reader-font-size-decrease")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }.assertIsFocused()
        gamepad(KeyEvent.KEYCODE_BUTTON_A)
        awaitTypographyPreview("reader-typography-preview-text", fonts, 30f, 2.4f, 0.2f)
    }

    @Test
    fun switchingEveryModeKeepsExactCharacterAndLastModeResumesAfterReopening() {
        awaitLibrary()
        val store = BookRepository(context)
        val bookmark = ReadingProgress(chapterIndex = 0, pageIndex = 0, charOffset = 600)
        assertTrue(DemoBook.content.chapters.first().text.length > bookmark.charOffset)
        store.saveProgress(DemoBook.ID, bookmark)
        openDemo()
        awaitBody(ReadingMode.Single)
        historyGuard.awaitWrites()
        val original = store.getProgress(DemoBook.ID)
        assertEquals(bookmark.charOffset, original.charOffset)
        for (mode in listOf(ReadingMode.DualColumn,
            ReadingMode.Preview, ReadingMode.ZoomPreview, ReadingMode.Single, ReadingMode.ZoomPreview)) {
            compose.runOnIdle { model.setReadingMode(mode) }
            awaitBody(mode)
            historyGuard.awaitWrites()
            val after = store.getProgress(DemoBook.ID)
            assertEquals("Mode $mode must keep the chapter", original.chapterIndex, after.chapterIndex)
            assertEquals("Mode $mode must keep the exact character", original.charOffset, after.charOffset)
        }
        gamepad(KeyEvent.KEYCODE_BUTTON_B)
        awaitLibrary()
        historyGuard.awaitWrites()
        assertEquals(ReadingMode.ZoomPreview, freshSettings().readingMode)
        openDemo()
        awaitBody(ReadingMode.ZoomPreview)
        historyGuard.awaitWrites()
        assertEquals(original.charOffset, store.getProgress(DemoBook.ID).charOffset)
        compose.activityRule.scenario.recreate()
        awaitBody(ReadingMode.ZoomPreview)
        historyGuard.awaitWrites()
        assertEquals(original.charOffset, store.getProgress(DemoBook.ID).charOffset)
    }

    private fun freshSettings(): ReaderUiState = compose.runOnIdle {
        val models = ViewModelStore()
        try {
            val factory = ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application)
            val fresh = ViewModelProvider(models, factory)[ReaderViewModel::class.java]
            historyGuard.attachModel(fresh)
            fresh.state.value
        } finally { models.clear() }
    }

    private fun awaitTypographyPreview(tag: String, fonts: ReaderFontSelection, size: Float, lines: Float, letters: Float) {
        compose.waitUntil(TIMEOUT) {
            compose.runOnIdle {
                val view = previewText(tag) ?: return@runOnIdle false
                val typography = ReaderFonts.getCached(fonts) ?: return@runOnIdle false
                val expectedSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, view.resources.displayMetrics)
                if (kotlin.math.abs(view.textSize - expectedSize) > 0.01f ||
                    view.lineSpacingMultiplier != lines || view.letterSpacing != letters ||
                    view.typeface != typography.englishTypeface || view.width <= 0) return@runOnIdle false
                val chineseIndex = view.text.indexOfFirst { it in '\u4E00'..'\u9FFF' }
                val latinIndex = view.text.indexOfFirst { it in 'A'..'Z' || it in 'a'..'z' }
                if (chineseIndex < 0 || latinIndex < 0) return@runOnIdle false
                val spans = (view.text as? Spanned)?.getSpans(chineseIndex, chineseIndex + 1, TypefaceSpan::class.java).orEmpty()
                spans.any { it.typeface == typography.chineseTypeface }
            }
        }
        compose.waitForIdle()
    }

    private fun previewText(tag: String): TextView? = descendants(compose.activity.window.decorView)
        .filterIsInstance<TextView>().firstOrNull { it.tag == tag }

    private fun captureTypographyIfRequested(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureTypography") != "true") return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(context.cacheDir, "typography-validation").apply { mkdirs() }
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "$name.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { image.recycle() }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun openDemo() {
        compose.onNodeWithTag("library-book-grid").performScrollToNode(hasTestTag("library-book-${DemoBook.ID}"))
        compose.onNodeWithTag("library-book-${DemoBook.ID}").performClick()
    }

    private fun openDisplayPicker() {
        awaitSettings()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("reader-display-picker"))
        compose.onNodeWithTag("reader-display-picker").performClick()
        awaitTag("reading-mode-list")
    }

    private fun chooseMode(mode: ReadingMode) {
        // Use actual Activity gamepad dispatch, leaving touch mode before focus
        // requests so the same Material control used by the handheld is activated.
        gamepad(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("reading-mode-list").performScrollToNode(hasTestTag(modeTag(mode)))
        compose.onNodeWithTag(modeTag(mode)).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag(modeTag(mode)).assertIsFocused()
        gamepad(KeyEvent.KEYCODE_BUTTON_A)
        compose.onNodeWithTag(modeTag(mode)).assertIsSelected()
    }

    private fun awaitBody(mode: ReadingMode) {
        awaitTag("reader-mode-${mode.id}")
        awaitTag("reader-page-primary")
        compose.waitForIdle()
    }

    private fun awaitLibrary() {
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodes(hasTestTag("library-import")).fetchSemanticsNodes().any {
                it.config.getOrNull(SemanticsProperties.Disabled) == null
            }
        }
        compose.waitForIdle()
    }

    private fun awaitSettings() = awaitTag("settings-list")

    private fun awaitTag(tag: String) {
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
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

    private fun modeTag(mode: ReadingMode) = "reading-mode-${mode.id}"
    private fun animationTag(animation: PageTurnAnimation) = "page-animation-${animation.id}"
    private companion object { const val TIMEOUT = 20_000L }
}
