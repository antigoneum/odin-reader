package io.github.odinreader.input

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

/**
 * Activity-level adapter for Odin's standard Android gamepad events.
 *
 * Return true from [onAction] only when the current screen handled an action.
 * Forward false results to Activity's superclass so Compose focus navigation,
 * dialogs, and Android's controller compatibility layer can work normally.
 * Call [reset] when the Activity loses focus or pauses.
 */
class ControllerInput(private val onAction: (ControllerAction) -> Boolean) {
    private data class KeyId(val deviceId: Int, val code: Int)
    private class HeldKey(val action: ControllerAction, val gate: KeyRepeatGate)
    private class MotionState {
        val resolver = AxisDirectionResolver()
        val repeater = DirectionRepeater()
        val leftTrigger = TriggerLatch()
        val rightTrigger = TriggerLatch()
        var navigationHandled = false
        var leftTriggerHandled = false
        var rightTriggerHandled = false
        var usingHat = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private val heldKeys = mutableMapOf<KeyId, HeldKey>()
    private val devices = mutableMapOf<Int, MotionState>()
    private var lastDirection: ControllerAction? = null
    private var lastDirectionDevice = Int.MIN_VALUE
    private var lastDirectionWasMotion = false
    private var lastDirectionAt = Long.MIN_VALUE
    private var lastDirectionHandled = false

    private val repeatRunnable = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            devices.forEach { (deviceId, state) ->
                if (state.navigationHandled) {
                    state.repeater.poll(now)?.let { direction ->
                        state.navigationHandled = emit(direction.action, deviceId, now, fromMotion = true)
                    }
                }
            }
            scheduleRepeat()
        }
    }

    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val action = actionForKey(event.keyCode) ?: return false
        if (!isController(event) && !isKeyboardNavigation(event.keyCode)) return false
        val id = KeyId(event.deviceId, event.keyCode)
        if (event.action == KeyEvent.ACTION_UP) {
            updateDigitalTrigger(event, pressed = false)
            return heldKeys.remove(id) != null
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false

        val held = heldKeys[id]
        if (held != null) {
            if (event.repeatCount > 0 && held.action.isRepeatable() &&
                held.gate.press(event.eventTime, isRepeat = true)
            ) {
                emit(held.action, event.deviceId, event.eventTime, fromMotion = false)
            }
            return true
        }
        // Preserve the superclass's handling for the whole stream if the first
        // key-down was not handled here.
        if (event.repeatCount > 0) return false

        val handled = if (action.isTrigger()) {
            updateDigitalTrigger(event, pressed = true)
        } else {
            emit(action, event.deviceId, event.eventTime, fromMotion = false)
        }
        if (handled) {
            val gate = KeyRepeatGate().also { it.press(event.eventTime, isRepeat = false) }
            heldKeys[id] = HeldKey(action, gate)
            if (action.isDirection()) {
                // Some firmware sends both a HAT event and a D-pad key.
                devices[event.deviceId]?.takeIf { it.usingHat }?.let {
                    it.repeater.reset()
                    it.resolver.reset()
                    it.navigationHandled = false
                }
                scheduleRepeat()
            }
        }
        return handled
    }

    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_MOVE ||
            !(event.isFromSource(InputDevice.SOURCE_JOYSTICK) ||
                event.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
                event.isFromSource(InputDevice.SOURCE_DPAD))
        ) return false

        val state = devices.getOrPut(event.deviceId) { MotionState() }
        val previouslyHandled = state.navigationHandled ||
            (state.leftTrigger.analogPressed && state.leftTriggerHandled) ||
            (state.rightTrigger.analogPressed && state.rightTriggerHandled)
        val hasHeldDpad = heldKeys.any { (id, key) ->
            id.deviceId == event.deviceId && key.action.isDirection()
        }
        val hatX = if (hasHeldDpad) 0f else centeredAxis(event, MotionEvent.AXIS_HAT_X)
        val hatY = if (hasHeldDpad) 0f else centeredAxis(event, MotionEvent.AXIS_HAT_Y)
        state.usingHat = abs(hatX) >= 0.5f || abs(hatY) >= 0.5f
        val direction = state.resolver.resolve(
            x = centeredAxis(event, MotionEvent.AXIS_X),
            y = centeredAxis(event, MotionEvent.AXIS_Y),
            hatX = hatX,
            hatY = hatY,
        )
        state.repeater.update(direction, event.eventTime)?.let {
            state.navigationHandled = emit(it.action, event.deviceId, event.eventTime, fromMotion = true)
        }
        if (direction == null) state.navigationHandled = false

        val leftPressed = state.leftTrigger.updateAxis(
            maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE)),
        )
        val rightPressed = state.rightTrigger.updateAxis(
            maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS)),
        )
        if (leftPressed) state.leftTriggerHandled = onAction(ControllerAction.PreviousChapter)
        if (rightPressed) state.rightTriggerHandled = onAction(ControllerAction.NextChapter)
        if (!state.leftTrigger.isPressed) state.leftTriggerHandled = false
        if (!state.rightTrigger.isPressed) state.rightTriggerHandled = false
        scheduleRepeat()
        return previouslyHandled || state.navigationHandled ||
            (state.leftTrigger.analogPressed && state.leftTriggerHandled) ||
            (state.rightTrigger.analogPressed && state.rightTriggerHandled)
    }

    fun reset() {
        handler.removeCallbacks(repeatRunnable)
        heldKeys.clear()
        devices.clear()
        lastDirection = null
    }

    private fun updateDigitalTrigger(event: KeyEvent, pressed: Boolean): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BUTTON_L2 && event.keyCode != KeyEvent.KEYCODE_BUTTON_R2) {
            return false
        }
        val state = devices.getOrPut(event.deviceId) { MotionState() }
        val isLeft = event.keyCode == KeyEvent.KEYCODE_BUTTON_L2
        val latch = if (isLeft) state.leftTrigger else state.rightTrigger
        if (latch.setDigital(pressed)) {
            val handled = onAction(if (isLeft) ControllerAction.PreviousChapter else ControllerAction.NextChapter)
            if (isLeft) state.leftTriggerHandled = handled else state.rightTriggerHandled = handled
        }
        if (!latch.isPressed) {
            if (isLeft) state.leftTriggerHandled = false else state.rightTriggerHandled = false
        }
        return if (isLeft) state.leftTriggerHandled else state.rightTriggerHandled
    }

    private fun emit(action: ControllerAction, deviceId: Int, now: Long, fromMotion: Boolean): Boolean {
        if (!action.isDirection()) return onAction(action)
        if (lastDirection == action && lastDirectionDevice == deviceId &&
            lastDirectionWasMotion != fromMotion && now - lastDirectionAt in 0L..80L
        ) return lastDirectionHandled
        val handled = onAction(action)
        lastDirection = action
        lastDirectionDevice = deviceId
        lastDirectionWasMotion = fromMotion
        lastDirectionAt = now
        lastDirectionHandled = handled
        return handled
    }

    private fun scheduleRepeat() {
        handler.removeCallbacks(repeatRunnable)
        val nextAt = devices.values.filter { it.navigationHandled }
            .mapNotNull { it.repeater.nextRepeatAt }.minOrNull() ?: return
        handler.postDelayed(repeatRunnable, (nextAt - SystemClock.uptimeMillis()).coerceAtLeast(1L))
    }

    private fun centeredAxis(event: MotionEvent, axis: Int): Float {
        val value = event.getAxisValue(axis)
        val flat = event.device?.getMotionRange(axis, event.source)?.flat ?: 0f
        return if (abs(value) > flat) value else 0f
    }

    private fun isController(event: KeyEvent): Boolean =
        event.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
            event.isFromSource(InputDevice.SOURCE_JOYSTICK) ||
            event.isFromSource(InputDevice.SOURCE_DPAD)

    private fun isKeyboardNavigation(code: Int): Boolean = code in setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_ESCAPE,
    )

    private fun actionForKey(code: Int): ControllerAction? = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> ControllerAction.MoveUp
        KeyEvent.KEYCODE_DPAD_DOWN -> ControllerAction.MoveDown
        KeyEvent.KEYCODE_DPAD_LEFT -> ControllerAction.MoveLeft
        KeyEvent.KEYCODE_DPAD_RIGHT -> ControllerAction.MoveRight
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_PAGE_UP -> ControllerAction.PreviousPage
        KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_PAGE_DOWN -> ControllerAction.NextPage
        KeyEvent.KEYCODE_BUTTON_L2 -> ControllerAction.PreviousChapter
        KeyEvent.KEYCODE_BUTTON_R2 -> ControllerAction.NextChapter
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> ControllerAction.Confirm
        KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> ControllerAction.Back
        KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_SELECT -> ControllerAction.ToggleControls
        KeyEvent.KEYCODE_BUTTON_Y -> ControllerAction.OpenContents
        KeyEvent.KEYCODE_BUTTON_START -> ControllerAction.OpenSettings
        else -> null
    }
}

private fun ControllerAction.isDirection(): Boolean = when (this) {
    ControllerAction.MoveUp, ControllerAction.MoveDown,
    ControllerAction.MoveLeft, ControllerAction.MoveRight -> true
    else -> false
}

private fun ControllerAction.isTrigger(): Boolean =
    this == ControllerAction.PreviousChapter || this == ControllerAction.NextChapter

private fun ControllerAction.isRepeatable(): Boolean =
    isDirection() || this == ControllerAction.PreviousPage || this == ControllerAction.NextPage
