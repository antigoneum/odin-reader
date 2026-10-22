package io.github.odinreader.input

import kotlin.math.abs

internal enum class NavigationDirection(val action: ControllerAction) {
    Up(ControllerAction.MoveUp),
    Down(ControllerAction.MoveDown),
    Left(ControllerAction.MoveLeft),
    Right(ControllerAction.MoveRight),
}

/** Hysteresis keeps a resting or slightly drifting stick from moving focus. */
internal class AxisDirectionResolver(
    private val enterDeadZone: Float = 0.45f,
    private val exitDeadZone: Float = 0.25f,
) {
    private var active: NavigationDirection? = null

    fun resolve(x: Float, y: Float, hatX: Float = 0f, hatY: Float = 0f): NavigationDirection? {
        val useHat = abs(hatX) >= 0.5f || abs(hatY) >= 0.5f
        val horizontal = if (useHat) hatX else x
        val vertical = if (useHat) hatY else y
        val candidate = if (abs(horizontal) >= abs(vertical)) {
            if (horizontal < 0) NavigationDirection.Left else NavigationDirection.Right
        } else {
            if (vertical < 0) NavigationDirection.Up else NavigationDirection.Down
        }
        val magnitude = maxOf(abs(horizontal), abs(vertical))
        val threshold = when {
            useHat -> 0.5f
            candidate == active -> exitDeadZone
            else -> enterDeadZone
        }
        active = candidate.takeIf { magnitude >= threshold }
        return active
    }

    fun reset() {
        active = null
    }
}

/** The UI repeats held directions even when Android sends no further axis events. */
internal class DirectionRepeater(
    private val initialDelayMillis: Long = 360L,
    private val repeatIntervalMillis: Long = 140L,
) {
    private var direction: NavigationDirection? = null
    var nextRepeatAt: Long? = null
        private set

    fun update(next: NavigationDirection?, now: Long): NavigationDirection? {
        if (next == direction) return null
        direction = next
        nextRepeatAt = next?.let { now + initialDelayMillis }
        return next
    }

    fun poll(now: Long): NavigationDirection? {
        val nextTime = nextRepeatAt ?: return null
        if (now < nextTime) return null
        // Never catch up a backlog after the UI thread was busy.
        nextRepeatAt = now + repeatIntervalMillis
        return direction
    }

    fun reset() {
        direction = null
        nextRepeatAt = null
    }
}

internal class KeyRepeatGate(
    private val initialDelayMillis: Long = 360L,
    private val repeatIntervalMillis: Long = 140L,
) {
    private var nextRepeatAt = Long.MIN_VALUE

    fun press(now: Long, isRepeat: Boolean): Boolean {
        if (!isRepeat) {
            nextRepeatAt = now + initialDelayMillis
            return true
        }
        if (now < nextRepeatAt) return false
        nextRepeatAt = now + repeatIntervalMillis
        return true
    }
}

/** A trigger can report BOTH a key and an axis. Merge them into one press. */
internal class TriggerLatch {
    private var digitalPressed = false
    var analogPressed = false
        private set
    val isPressed: Boolean get() = digitalPressed || analogPressed

    fun setDigital(pressed: Boolean): Boolean {
        val wasPressed = isPressed
        digitalPressed = pressed
        return !wasPressed && isPressed
    }

    fun updateAxis(value: Float): Boolean {
        val wasPressed = isPressed
        analogPressed = if (analogPressed) value > 0.35f else value >= 0.65f
        return !wasPressed && isPressed
    }
}
