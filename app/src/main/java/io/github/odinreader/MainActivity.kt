package io.github.odinreader

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.odinreader.input.ControllerAction
import io.github.odinreader.input.ControllerInput
import io.github.odinreader.ui.OdinReaderApp

class MainActivity : ComponentActivity() {
    var onControllerAction: (ControllerAction) -> Boolean = { false }
    private val controller = ControllerInput { onControllerAction(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { OdinReaderApp(this) }
    }

    // This public Activity hook inherits a class-level restriction from AndroidX core.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        controller.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        controller.onGenericMotionEvent(event) || super.dispatchGenericMotionEvent(event)

    /** Compose clickable controls use DPAD_CENTER; normalize the gamepad's A button. */
    @SuppressLint("RestrictedApi") // Same inherited framework dispatch hook as above.
    fun activateFocusedControl(): Boolean {
        val down = super.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
        val up = super.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
        return down || up
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) controller.reset()
    }

    override fun onPause() {
        controller.reset()
        super.onPause()
    }
}
