package io.github.odinreader.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerBindingsTest {
    @Test fun `reading remaps do not change menu confirmation or back`() {
        val bindings = ControllerBindings()
            .withBinding(ControllerButton.A, ControllerAction.NextPage)
            .withBinding(ControllerButton.B, ControllerAction.PreviousPage)
            .withBinding(ControllerButton.Start, ControllerAction.OpenContents)

        assertEquals(ControllerAction.NextPage, bindings.actionForKey(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(ControllerAction.PreviousPage, bindings.actionForKey(KeyEvent.KEYCODE_BUTTON_B))
        assertEquals(ControllerAction.OpenContents, bindings.actionForKey(KeyEvent.KEYCODE_BUTTON_START))
        assertEquals(ControllerAction.Confirm, bindings.actionForKey(KeyEvent.KEYCODE_BUTTON_A, useDefaults = true))
        assertEquals(ControllerAction.Back, bindings.actionForKey(KeyEvent.KEYCODE_BUTTON_B, useDefaults = true))
        assertEquals(ControllerAction.OpenSettings, bindings.actionForKey(KeyEvent.KEYCODE_BUTTON_START, useDefaults = true))
    }

    @Test fun `direction and keyboard navigation stay available after button remaps`() {
        val bindings = ControllerBindings()
            .withBinding(ControllerButton.A, ControllerAction.Back)
            .withBinding(ControllerButton.B, ControllerAction.Confirm)
            .withBinding(ControllerButton.L1, ControllerAction.OpenSettings)

        assertEquals(ControllerAction.MoveLeft, bindings.actionForKey(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(ControllerAction.MoveRight, bindings.actionForKey(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(ControllerAction.MoveUp, bindings.actionForKey(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(ControllerAction.MoveDown, bindings.actionForKey(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(ControllerAction.Confirm, bindings.actionForKey(KeyEvent.KEYCODE_ENTER))
        assertEquals(ControllerAction.Confirm, bindings.actionForKey(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(ControllerAction.Back, bindings.actionForKey(KeyEvent.KEYCODE_BACK))
        assertEquals(ControllerAction.Back, bindings.actionForKey(KeyEvent.KEYCODE_ESCAPE))
        assertEquals(ControllerAction.PreviousPage, bindings.actionForKey(KeyEvent.KEYCODE_PAGE_UP))
        assertEquals(ControllerAction.NextPage, bindings.actionForKey(KeyEvent.KEYCODE_PAGE_DOWN))
        assertNull(bindings.actionForKey(KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test fun `serialized remaps round trip without changing defaults or the source value`() {
        val defaults = ControllerBindings()
        val customized = defaults
            .withBinding(ControllerButton.L1, ControllerAction.NextPage)
            .withBinding(ControllerButton.R1, ControllerAction.PreviousPage)
            .withBinding(ControllerButton.L2, ControllerAction.ToggleControls)

        assertEquals(ControllerAction.PreviousPage, defaults.actionFor(ControllerButton.L1))
        assertEquals(customized, ControllerBindings.fromPreferences(customized.toPreferences()))
        assertEquals(ControllerAction.ToggleControls.name, customized.toPreferences()[ControllerButton.L2.name])
        assertEquals(ControllerAction.NextChapter, customized.actionFor(ControllerButton.R2))
        assertEquals(defaults, customized
            .withBinding(ControllerButton.L1, ControllerButton.L1.defaultAction)
            .withBinding(ControllerButton.R1, ControllerButton.R1.defaultAction)
            .withBinding(ControllerButton.L2, ControllerButton.L2.defaultAction))
    }

    @Test fun `invalid stored entries fall back individually without discarding valid remaps`() {
        val bindings = ControllerBindings.fromPreferences(mapOf(
            "A" to "NextPage",
            "B" to "UnknownFutureAction",
            "L1" to "MoveUp",
            "Y" to "",
            "UnknownFutureButton" to "PreviousPage",
        ))

        assertEquals(ControllerAction.NextPage, bindings.actionFor(ControllerButton.A))
        assertEquals(ControllerAction.Back, bindings.actionFor(ControllerButton.B))
        assertEquals(ControllerAction.PreviousPage, bindings.actionFor(ControllerButton.L1))
        assertEquals(ControllerAction.OpenContents, bindings.actionFor(ControllerButton.Y))
        assertEquals(ControllerBindings(), ControllerBindings.fromPreferences(emptyMap()))
    }

    @Test fun `hints show duplicate assignments and indicate an unavailable shortcut`() {
        val bindings = ControllerBindings()
            .withBinding(ControllerButton.Y, ControllerAction.NextPage)
            .withBinding(ControllerButton.X, ControllerAction.NextPage)
            .withBinding(ControllerButton.Select, ControllerAction.NextPage)
        assertEquals("X / Y / R1 / SELECT", bindings.labelFor(ControllerAction.NextPage))
        assertEquals("未分配", bindings.labelFor(ControllerAction.OpenContents))
        assertEquals("未分配", bindings.labelFor(ControllerAction.ToggleControls))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `directional actions cannot replace a button shortcut`() {
        ControllerBindings().withBinding(ControllerButton.A, ControllerAction.MoveDown)
    }

    @Test fun `triggers resolve their physical mapping independently of chapter action names`() {
        val bindings = ControllerBindings()
            .withBinding(ControllerButton.L2, ControllerAction.NextPage)
            .withBinding(ControllerButton.R2, ControllerAction.OpenSettings)
            .withBinding(ControllerButton.A, ControllerAction.PreviousChapter)
        assertEquals(ControllerAction.NextPage, bindings.actionForKey(KeyEvent.KEYCODE_BUTTON_L2))
        assertEquals(ControllerAction.OpenSettings, bindings.actionForKey(KeyEvent.KEYCODE_BUTTON_R2))
        assertEquals(ControllerAction.PreviousChapter, bindings.actionForKey(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(ControllerAction.PreviousChapter, bindings.actionForKey(KeyEvent.KEYCODE_BUTTON_L2, useDefaults = true))
        assertEquals(ControllerAction.NextChapter, bindings.actionForKey(KeyEvent.KEYCODE_BUTTON_R2, useDefaults = true))
        assertTrue(ControllerAction.OpenSettings in ControllerBindings.assignableActions)
        assertFalse(ControllerAction.MoveUp in ControllerBindings.assignableActions)
    }
}
