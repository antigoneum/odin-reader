package io.github.odinreader.ui

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.odinreader.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Top-level navigation never edits preferences or opens the user's books. */
@RunWith(AndroidJUnit4::class)
class HandheldMenuDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun shouldersCycleHomeViewsAndBackReturnsToLibrary() {
        awaitPage("Library")
        gamepad(KeyEvent.KEYCODE_BUTTON_R1)
        awaitPage("Settings")
        gamepad(KeyEvent.KEYCODE_BUTTON_R1)
        awaitPage("Help")
        gamepad(KeyEvent.KEYCODE_BUTTON_R1)
        awaitPage("Library")
        gamepad(KeyEvent.KEYCODE_BUTTON_L1)
        awaitPage("Help")
        gamepad(KeyEvent.KEYCODE_BUTTON_B)
        awaitPage("Library")
        gamepad(KeyEvent.KEYCODE_BUTTON_R1)
        awaitPage("Settings")
        gamepad(KeyEvent.KEYCODE_BUTTON_B)
        awaitPage("Library")
    }

    @Test
    fun homeSelectionSurvivesActivityRecreation() {
        awaitPage("Library")
        gamepad(KeyEvent.KEYCODE_BUTTON_R1)
        awaitPage("Settings")
        compose.activityRule.scenario.recreate()
        awaitPage("Settings")
        gamepad(KeyEvent.KEYCODE_BUTTON_R1)
        awaitPage("Help")
        compose.activityRule.scenario.recreate()
        awaitPage("Help")
        gamepad(KeyEvent.KEYCODE_BUTTON_B)
        awaitPage("Library")
    }

    @Test
    fun horizontalTabsSupportTouchAndDirectionalFocusBeforeConfirming() {
        awaitPage("Library")
        compose.onNodeWithTag("home-tab-Help").performClick()
        awaitPage("Help")
        compose.onNodeWithTag("home-tab-Library").performClick()
        awaitPage("Library")

        // Input mode is changed by the real event, never by the test. RequestFocus
        // only supplies a predictable starting tab before actual Left/Right/A.
        key(KeyEvent.KEYCODE_DPAD_RIGHT, InputDevice.SOURCE_DPAD)
        compose.onNodeWithTag("home-tab-Library")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_RIGHT, InputDevice.SOURCE_DPAD)
        compose.onNodeWithTag("home-tab-Settings").assertIsFocused()
        awaitPage("Library")
        gamepad(KeyEvent.KEYCODE_BUTTON_A)
        awaitPage("Settings")

        // B returns to the library without accidentally confirming a focused
        // setting or changing any persisted preference.
        gamepad(KeyEvent.KEYCODE_BUTTON_B)
        awaitPage("Library")
    }

    private fun awaitPage(name: String) {
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodes(hasTestTag("home-page-$name")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("home-tab-$name").assertIsSelected()
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

    companion object { private const val TIMEOUT = 15_000L }
}
