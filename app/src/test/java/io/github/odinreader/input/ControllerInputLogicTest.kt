package io.github.odinreader.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerInputLogicTest {
    @Test fun `resting stick and small drift do not navigate`() {
        val resolver = AxisDirectionResolver()
        assertNull(resolver.resolve(0f, 0f))
        assertNull(resolver.resolve(0.2f, -0.1f))
        assertNull(resolver.resolve(-0.44f, 0f))
    }

    @Test fun `stick hysteresis holds direction until release threshold`() {
        val resolver = AxisDirectionResolver()
        assertEquals(NavigationDirection.Right, resolver.resolve(0.6f, 0f))
        assertEquals(NavigationDirection.Right, resolver.resolve(0.3f, 0f))
        assertNull(resolver.resolve(0.2f, 0f))
        assertNull(resolver.resolve(0.3f, 0f))
        assertEquals(NavigationDirection.Left, resolver.resolve(-0.7f, 0f))
    }

    @Test fun `hat takes priority and diagonal emits a single dominant direction`() {
        val resolver = AxisDirectionResolver()
        assertEquals(NavigationDirection.Up, resolver.resolve(0.9f, 0f, hatY = -1f))
        assertEquals(NavigationDirection.Down, resolver.resolve(0.6f, 0.9f))
    }

    @Test fun `held stick repeats after delay and release cancels it`() {
        val repeater = DirectionRepeater(initialDelayMillis = 300, repeatIntervalMillis = 120)
        assertEquals(NavigationDirection.Right, repeater.update(NavigationDirection.Right, 0))
        assertNull(repeater.update(NavigationDirection.Right, 100))
        assertNull(repeater.poll(299))
        assertEquals(NavigationDirection.Right, repeater.poll(300))
        assertNull(repeater.poll(419))
        assertEquals(NavigationDirection.Right, repeater.poll(420))
        assertNull(repeater.update(null, 430))
        assertNull(repeater.poll(10_000))
        assertNull(repeater.nextRepeatAt)
    }

    @Test fun `changing direction responds immediately and late polling does not catch up`() {
        val repeater = DirectionRepeater()
        repeater.update(NavigationDirection.Left, 0)
        assertEquals(NavigationDirection.Down, repeater.update(NavigationDirection.Down, 100))
        assertEquals(NavigationDirection.Down, repeater.poll(10_000))
        assertNull(repeater.poll(10_001))
        assertEquals(10_140L, repeater.nextRepeatAt)
        repeater.reset()
        assertNull(repeater.poll(20_000))
    }

    @Test fun `digital key repeats are rate limited`() {
        val gate = KeyRepeatGate(initialDelayMillis = 300, repeatIntervalMillis = 120)
        assertTrue(gate.press(0, isRepeat = false))
        assertFalse(gate.press(100, isRepeat = true))
        assertTrue(gate.press(300, isRepeat = true))
        assertFalse(gate.press(350, isRepeat = true))
        assertTrue(gate.press(420, isRepeat = true))
        assertTrue(gate.press(430, isRepeat = false))
    }

    @Test fun `trigger analog and digital signals cause a single action in either order`() {
        val digitalFirst = TriggerLatch()
        assertTrue(digitalFirst.setDigital(true))
        assertFalse(digitalFirst.updateAxis(0.9f))
        assertFalse(digitalFirst.setDigital(false))
        assertTrue(digitalFirst.isPressed)
        assertFalse(digitalFirst.updateAxis(0f))
        assertFalse(digitalFirst.isPressed)
        assertTrue(digitalFirst.updateAxis(0.9f))

        val analogFirst = TriggerLatch()
        assertTrue(analogFirst.updateAxis(0.9f))
        assertFalse(analogFirst.setDigital(true))
        assertFalse(analogFirst.updateAxis(0f))
        assertTrue(analogFirst.isPressed)
        assertFalse(analogFirst.setDigital(false))
        assertFalse(analogFirst.isPressed)
        assertTrue(analogFirst.setDigital(true))
    }

    @Test fun `trigger hysteresis ignores pressure jitter`() {
        val latch = TriggerLatch()
        assertFalse(latch.updateAxis(0.6f))
        assertTrue(latch.updateAxis(0.7f))
        assertFalse(latch.updateAxis(0.5f))
        assertTrue(latch.isPressed)
        assertFalse(latch.updateAxis(0.3f))
        assertFalse(latch.isPressed)
        assertFalse(latch.updateAxis(0.5f))
        assertTrue(latch.updateAxis(0.7f))
    }
}
