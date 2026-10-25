package dev.geode.design.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReducedMotionTest {
    @Test
    fun theAppPreferenceAloneReducesMotion() = assertTrue(isMotionReduced(appPreference = true, durationScale = 1f))

    @Test
    fun removedSystemAnimationsAloneReduceMotion() = assertTrue(isMotionReduced(appPreference = false, durationScale = 0f))

    @Test
    fun neitherLeavesMotionOn() {
        assertFalse(isMotionReduced(appPreference = false, durationScale = 1f))
        assertFalse(isMotionReduced(appPreference = false, durationScale = 0.5f))
    }
}
