package dev.geode.design.forest

import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForestMotionTest {
    private val springs = listOf(ForestMotion.Settle, ForestMotion.Bloom, ForestMotion.Press, ForestMotion.Drift)

    @Test
    fun `each feel becomes a Compose spring`() {
        for (feel in springs) {
            val expected = spring<Float>(feel.dampingRatio, feel.stiffness)
            assertEquals(expected, ForestMotion.spec<Float>(feel, reduced = false))
        }
    }

    @Test
    fun `with animations removed every spec snaps to its end`() {
        for (feel in springs) {
            assertEquals(snap<Float>(), ForestMotion.spec<Float>(feel, reduced = true))
        }
        assertEquals(snap<Float>(), ForestMotion.fade<Float>(reduced = true))
    }

    @Test
    fun `the fade is slow`() {
        assertEquals(tween<Float>(ForestMotion.FADE_MILLIS), ForestMotion.fade<Float>(reduced = false))
        assertEquals(420, ForestMotion.FADE_MILLIS)
        assertTrue(ForestMotion.REDUCED_PULSE_MILLIS < ForestMotion.FADE_MILLIS)
    }

    @Test
    fun `a duration scale of zero means the person removed animations`() {
        assertTrue(ForestMotion.isAnimationRemoved(0f))
        assertFalse(ForestMotion.isAnimationRemoved(0.5f))
        assertFalse(ForestMotion.isAnimationRemoved(1f))
        assertFalse(ForestMotion.isAnimationRemoved(Float.MIN_VALUE))
    }

    @Test
    fun `springs overshoot gently and never keep wobbling`() {
        for (feel in springs) {
            assertTrue("$feel is jelly", feel.dampingRatio >= LEAST_DAMPING)
            assertTrue("$feel overdamped", feel.dampingRatio <= 1f)
            assertTrue("$feel has no stiffness", feel.stiffness > 0f)
        }
        assertEquals(1f, ForestMotion.Drift.dampingRatio, 0f)
        assertTrue(ForestMotion.Settle.dampingRatio < 1f)
        assertTrue(ForestMotion.Bloom.dampingRatio < ForestMotion.Settle.dampingRatio)
        assertTrue(ForestMotion.Press.stiffness > ForestMotion.Settle.stiffness)
        assertTrue(ForestMotion.Drift.stiffness < ForestMotion.Settle.stiffness)
    }

    private companion object {
        const val LEAST_DAMPING = 0.5f
    }
}
