package io.github.odinreader.input

import android.view.KeyEvent

/** Physical gamepad buttons. D-pad, stick and system navigation stay fixed. */
enum class ControllerButton(val label: String, val keyCode: Int, val defaultAction: ControllerAction) {
    A("A", KeyEvent.KEYCODE_BUTTON_A, ControllerAction.Confirm),
    B("B", KeyEvent.KEYCODE_BUTTON_B, ControllerAction.Back),
    X("X", KeyEvent.KEYCODE_BUTTON_X, ControllerAction.ToggleControls),
    Y("Y", KeyEvent.KEYCODE_BUTTON_Y, ControllerAction.OpenContents),
    L1("L1", KeyEvent.KEYCODE_BUTTON_L1, ControllerAction.PreviousPage),
    R1("R1", KeyEvent.KEYCODE_BUTTON_R1, ControllerAction.NextPage),
    L2("L2", KeyEvent.KEYCODE_BUTTON_L2, ControllerAction.PreviousChapter),
    R2("R2", KeyEvent.KEYCODE_BUTTON_R2, ControllerAction.NextChapter),
    Select("SELECT", KeyEvent.KEYCODE_BUTTON_SELECT, ControllerAction.ToggleControls),
    Start("START", KeyEvent.KEYCODE_BUTTON_START, ControllerAction.OpenSettings),
}

/** Immutable, validated reading-mode overrides; stable enum names are persisted. */
@ConsistentCopyVisibility
data class ControllerBindings private constructor(private val overrides: Map<ControllerButton, ControllerAction>) {
    constructor() : this(emptyMap())

    fun actionFor(button: ControllerButton): ControllerAction = overrides[button] ?: button.defaultAction

    fun withBinding(button: ControllerButton, action: ControllerAction): ControllerBindings {
        require(action in assignableActions) { "Directional navigation cannot be reassigned" }
        return ControllerBindings(
            if (action == button.defaultAction) overrides - button else overrides + (button to action),
        )
    }

    fun labelFor(action: ControllerAction): String = ControllerButton.entries
        .filter { actionFor(it) == action }.joinToString(" / ") { it.label }.ifEmpty { "未分配" }

    /** Menus deliberately retain A/B and standard bindings so settings stay reachable. */
    fun actionForKey(code: Int, useDefaults: Boolean = false): ControllerAction? {
        ControllerButton.entries.firstOrNull { it.keyCode == code }?.let {
            return if (useDefaults) it.defaultAction else actionFor(it)
        }
        return when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> ControllerAction.MoveUp
            KeyEvent.KEYCODE_DPAD_DOWN -> ControllerAction.MoveDown
            KeyEvent.KEYCODE_DPAD_LEFT -> ControllerAction.MoveLeft
            KeyEvent.KEYCODE_DPAD_RIGHT -> ControllerAction.MoveRight
            KeyEvent.KEYCODE_PAGE_UP -> ControllerAction.PreviousPage
            KeyEvent.KEYCODE_PAGE_DOWN -> ControllerAction.NextPage
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> ControllerAction.Confirm
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> ControllerAction.Back
            else -> null
        }
    }

    fun toPreferences(): Map<String, String> = ControllerButton.entries.associate { it.name to actionFor(it).name }

    companion object {
        val assignableActions: List<ControllerAction> = listOf(
            ControllerAction.PreviousPage,
            ControllerAction.NextPage,
            ControllerAction.PreviousChapter,
            ControllerAction.NextChapter,
            ControllerAction.Confirm,
            ControllerAction.Back,
            ControllerAction.ToggleControls,
            ControllerAction.OpenContents,
            ControllerAction.OpenSettings,
        )

        /** Unknown or invalid entries fall back individually, including newer future actions. */
        fun fromPreferences(values: Map<String, String>): ControllerBindings = ControllerBindings(
            ControllerButton.entries.mapNotNull { button ->
                assignableActions.firstOrNull { it.name == values[button.name] }
                    ?.takeIf { it != button.defaultAction }?.let { button to it }
            }.toMap(),
        )
    }
}
