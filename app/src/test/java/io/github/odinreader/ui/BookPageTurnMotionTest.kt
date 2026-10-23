package io.github.odinreader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookPageTurnMotionTest {
    @Test
    fun forwardChangesFaceAtTheSpineWithoutTurningBothLeavesOrMirroringText() {
        val before = bookPageTurnMotion(0.25f, 1, 400f, 20f, 820f)
        assertTrue(before.frontOnRight)
        assertEquals(-45f, before.frontRotation, 0f)
        assertEquals(90f, before.backRotation, 0f)
        assertTrue(before.frontVisible)
        assertFalse(before.backVisible)
        val after = bookPageTurnMotion(0.75f, 1, 400f, 20f, 820f)
        assertFalse(after.frontVisible)
        assertTrue(after.backVisible)
        assertEquals(-90f, after.frontRotation, 0f)
        assertEquals(45f, after.backRotation, 0f)
    }

    @Test
    fun reverseUsesTheOppositeLeafWithTheSamePhysicalHingeIncludingTheGutter() {
        val motion = bookPageTurnMotion(0.25f, -1, 400f, 20f, 820f)
        assertFalse(motion.frontOnRight)
        assertEquals(45f, motion.frontRotation, 0f)
        assertEquals(-90f, motion.backRotation, 0f)
        val leftHinge = motion.leftPivot * 400f
        val rightHinge = 420f + motion.rightPivot * 400f
        assertEquals(leftHinge, rightHinge, 0.0001f)
        assertEquals(motion.spineFraction * 820f, leftHinge, 0.0001f)
        assertEquals(0.5f, motion.spineFraction, 0f)
    }

    @Test
    fun endpointsAndTheEdgeOnFaceNeverShowTheBackwardsSide() {
        for (direction in listOf(-1, 1)) {
            val start = bookPageTurnMotion(-1f, direction, 400f, 0f, 800f)
            val middle = bookPageTurnMotion(0.5f, direction, 400f, 0f, 800f)
            val finish = bookPageTurnMotion(2f, direction, 400f, 0f, 800f)
            assertEquals(0f, start.progress, 0f)
            assertEquals(0f, start.frontRotation, 0f)
            assertFalse(middle.frontVisible)
            assertTrue(middle.backVisible)
            assertEquals(1f, finish.progress, 0f)
            assertEquals(0f, finish.backRotation, 0f)
            assertFalse(finish.frontVisible)
            assertTrue(finish.backVisible)
        }
    }
}
