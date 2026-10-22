package io.github.odinreader.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerModeTransitionTest {
    @Test fun `held analog trigger cannot acquire its menu action when it opens the toolbar`() {
        val trigger = TriggerActionGate()
        var defaultMode = false
        val bindings = ControllerBindings().withBinding(ControllerButton.R2, ControllerAction.Confirm)
        val actions = mutableListOf<ControllerAction>()
        val dispatch = {
            actions += if (defaultMode) ControllerButton.R2.defaultAction else bindings.actionFor(ControllerButton.R2)
            true
        }

        trigger.updateAxis(0.9f, dispatch)
        assertEquals(listOf(ControllerAction.Confirm), actions)
        defaultMode = true
        trigger.suppressUntilRelease()
        trigger.updateAxis(0.9f, dispatch)
        trigger.updateAxis(0.8f, dispatch)
        assertTrue(trigger.handled)
        assertEquals(listOf(ControllerAction.Confirm), actions)

        trigger.updateAxis(0f, dispatch)
        assertFalse(trigger.handled)
        trigger.updateAxis(0.9f, dispatch)
        assertEquals(listOf(ControllerAction.Confirm, ControllerAction.NextChapter), actions)
    }

    @Test fun `mixed trigger stream needs both releases before a changed binding can fire`() {
        val trigger = TriggerActionGate()
        val actions = mutableListOf<ControllerAction>()
        var action = ControllerAction.OpenSettings
        val dispatch = { actions += action; true }

        trigger.setDigital(true, dispatch)
        trigger.updateAxis(0.9f, dispatch)
        action = ControllerAction.NextChapter
        trigger.suppressUntilRelease()
        trigger.setDigital(false, dispatch)
        assertTrue(trigger.isPressed)
        action = ControllerAction.NextPage
        trigger.suppressUntilRelease()
        trigger.setDigital(true, dispatch)
        trigger.updateAxis(0f, dispatch)
        assertTrue(trigger.isPressed)
        trigger.setDigital(false, dispatch)
        assertFalse(trigger.isPressed)
        assertEquals(listOf(ControllerAction.OpenSettings), actions)

        trigger.updateAxis(0.9f, dispatch)
        trigger.setDigital(true, dispatch)
        assertEquals(listOf(ControllerAction.OpenSettings, ControllerAction.NextPage), actions)
    }

    @Test fun `a held unhandled trigger is consumed through a screen transition until release`() {
        val trigger = TriggerActionGate()
        var calls = 0
        val unhandled = { calls++; false }
        trigger.updateAxis(0.9f, unhandled)
        assertFalse(trigger.handled)
        trigger.suppressUntilRelease()
        assertTrue(trigger.handled)
        trigger.setDigital(true, unhandled)
        trigger.updateAxis(0.7f, unhandled)
        assertEquals(1, calls)
        trigger.setDigital(false, unhandled)
        trigger.updateAxis(0f, unhandled)
        assertFalse(trigger.handled)

        trigger.updateAxis(0.9f) { calls++; true }
        assertEquals(2, calls)
        assertTrue(trigger.handled)
    }

    @Test fun `mode change suppresses a held page key repeat until the next physical press`() {
        val gate = KeyRepeatGate()
        assertTrue(gate.press(0, isRepeat = false))
        gate.suppressUntilRelease()
        assertFalse(gate.press(1_000, isRepeat = true))
        assertFalse(gate.press(2_000, isRepeat = true))
        assertTrue(gate.press(2_100, isRepeat = false))
        assertFalse(gate.press(2_200, isRepeat = true))
        assertTrue(gate.press(2_460, isRepeat = true))
    }
}
