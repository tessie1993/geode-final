package dev.geode.ui

import dev.geode.feature.settings.api.BehaviorSettings
import dev.geode.feature.settings.api.TiltAmount
import org.junit.Assert.assertEquals
import org.junit.Test

/** Settings' Tilt as the weight the engine fades to (#315): reduce motion always wins. */
class TiltWeightTest {
    @Test
    fun `each amount has its weight, and Subtle is the default`() {
        assertEquals(0.5f, tiltWeight(BehaviorSettings()), 0f)
        assertEquals(0f, tiltWeight(BehaviorSettings(tilt = TiltAmount.OFF)), 0f)
        assertEquals(0.5f, tiltWeight(BehaviorSettings(tilt = TiltAmount.SUBTLE)), 0f)
        assertEquals(1f, tiltWeight(BehaviorSettings(tilt = TiltAmount.FULL)), 0f)
    }

    @Test
    fun `reduce motion stills tilt whatever the setting says`() {
        for (amount in TiltAmount.entries) {
            assertEquals(0f, tiltWeight(BehaviorSettings(tilt = amount, reducedMotion = true)), 0f)
        }
    }
}
