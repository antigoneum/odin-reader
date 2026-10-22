package io.github.odinreader.ui

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.odinreader.MainActivity
import io.github.odinreader.data.BookRepository
import io.github.odinreader.data.DemoBook
import io.github.odinreader.data.ReadingProgress
import io.github.odinreader.input.ControllerAction
import io.github.odinreader.input.ControllerButton
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/** Actual Android UI checks; hardware controller axes still require device verification. */
@RunWith(AndroidJUnit4::class)
class SettingsUiDeviceTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val settings: SharedPreferences
        get() = context.getSharedPreferences("reader_settings", Context.MODE_PRIVATE)
    private val progress: SharedPreferences
        get() = context.getSharedPreferences("reading_progress", Context.MODE_PRIVATE)
    private lateinit var savedSettings: Map<String, Any?>
    private lateinit var savedProgress: Map<String, Any?>
    private val settingsKeys = listOf("font_size", "paper_style", "app_theme") +
        ControllerButton.entries.map { "binding.${it.name}" }
    private val progressKeys = listOf("chapter", "page", "offset").map { "${DemoBook.ID}.$it" }
    private val isolatedPreferences = object : ExternalResource() {
        override fun before() {
            savedSettings = settingsKeys.associateWith { settings.all[it] }
            savedProgress = progressKeys.associateWith { progress.all[it] }
            val editor = settings.edit()
            settingsKeys.forEach { editor.remove(it) }
            check(editor.putFloat("font_size", 20f).putInt("paper_style", PaperStyle.Theme.ordinal).commit())
            BookRepository(context).saveProgress(DemoBook.ID, ReadingProgress())
        }

        override fun after() {
            restore(settings, savedSettings)
            restore(progress, savedProgress)
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule
    val rules: TestRule = RuleChain.outerRule(isolatedPreferences).around(compose)

    @Test
    fun allFourThemesSaveTheirSelectionAndThemeReadingBackground() {
        awaitLibrary()
        compose.onAllNodes(hasClickAction() and hasText("沿着山脊去看海"))[0].performClick()
        awaitPage(1)
        gamepad(KeyEvent.KEYCODE_BUTTON_START)
        awaitSettings()
        for (theme in listOf(AppTheme.Latte, AppTheme.Frappe, AppTheme.Macchiato, AppTheme.Mocha)) {
            compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("theme-${theme.name}"))
            compose.onNodeWithTag("theme-${theme.name}").performClick().assertIsSelected()
            assertEquals(theme.name, settings.getString("app_theme", null))
            assertEquals(PaperStyle.Theme.ordinal, settings.getInt("paper_style", -1))
            val restored = settingsFromFreshModel()
            assertEquals("A fresh model must reload the selected palette", theme, restored.appTheme)
            assertEquals("A fresh model must reload the reading background", PaperStyle.Theme, restored.paperStyle)
            gamepad(KeyEvent.KEYCODE_BUTTON_B)
            awaitPage(1)
            val expectedColor = catppuccinColorScheme(theme).onBackground.toArgb()
            compose.waitUntil(TIMEOUT) {
                compose.runOnIdle {
                    descendants(compose.activity.window.decorView).filterIsInstance<TextView>()
                        .firstOrNull()?.currentTextColor == expectedColor
                }
            }
            gamepad(KeyEvent.KEYCODE_BUTTON_START)
            awaitSettings()
        }
        compose.activityRule.scenario.recreate()
        awaitSettings()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("theme-Mocha"))
        compose.onNodeWithTag("theme-Mocha").assertIsSelected()
        gamepad(KeyEvent.KEYCODE_BUTTON_B)
        awaitPage(1)
    }

    @Test
    fun remappedShoulderTurnsPagesAndSettingsKeepDefaultNavigation() {
        awaitLibrary()
        gamepad(KeyEvent.KEYCODE_BUTTON_START)
        awaitSettings()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("binding-L1"))
        compose.onNodeWithTag("binding-L1").performClick()
        compose.onNodeWithTag("action-NextPage").performClick()
        assertEquals("NextPage", settings.getString("binding.L1", null))
        assertEquals("A fresh model must reload controller mappings", ControllerAction.NextPage,
            settingsFromFreshModel().controllerBindings.actionFor(ControllerButton.L1))

        // Remapping the reading action must not prevent B from closing settings.
        gamepad(KeyEvent.KEYCODE_BUTTON_B)
        awaitLibrary()
        compose.onAllNodes(hasClickAction() and hasText("沿着山脊去看海"))[0].performClick()
        awaitPage(1)
        gamepad(KeyEvent.KEYCODE_BUTTON_L1)
        awaitPage(2)

        gamepad(KeyEvent.KEYCODE_BUTTON_START)
        awaitSettings()
        // The settings back button receives initial focus; A still confirms it.
        gamepad(KeyEvent.KEYCODE_BUTTON_A)
        awaitPage(2)
    }

    /** Unlike ActivityScenario.recreate(), this uses a new model and no retained ViewModelStore. */
    private fun settingsFromFreshModel(): ReaderUiState = compose.runOnIdle {
        val store = ViewModelStore()
        try {
            val factory = ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application)
            ViewModelProvider(store, factory)[ReaderViewModel::class.java].state.value
        } finally {
            store.clear()
        }
    }

    private fun awaitLibrary() {
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodesWithText("导入书籍").fetchSemanticsNodes().any {
                it.config.getOrNull(SemanticsProperties.Disabled) == null
            }
        }
    }

    private fun awaitSettings() {
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodes(hasTestTag("settings-back")).fetchSemanticsNodes().isNotEmpty()
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

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
    }

    private fun restore(preferences: SharedPreferences, values: Map<String, Any?>) {
        val editor = preferences.edit()
        values.forEach { (key, value) ->
            when (value) {
                null -> editor.remove(key)
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Float -> editor.putFloat(key, value)
                else -> error("Unexpected preference type for $key")
            }
        }
        check(editor.commit())
    }

    private companion object { const val TIMEOUT = 15_000L }
}
