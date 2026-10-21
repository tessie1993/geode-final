package dev.geode.design.water

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WateryHapticsTest {
    private val all = HapticPrimitive.entries.toSet()

    @Test
    fun `switched off in Settings, nothing plays`() {
        for (haptic in WateryHaptic.entries) {
            assertEquals(HapticPlan.Off, WateryHaptics.plan(haptic, enabled = false, supported = all))
        }
    }

    @Test
    fun `haptics start switched on`() {
        assertTrue(WateryHaptics.ENABLED_BY_DEFAULT)
    }

    @Test
    fun `every haptic has an authored pattern`() {
        assertEquals(WateryHaptic.entries.toSet(), WateryHaptics.patterns.keys)
    }

    @Test
    fun `with every primitive the authored pattern plays as written`() {
        for (haptic in WateryHaptic.entries) {
            assertEquals(
                HapticPlan.Composed(WateryHaptics.patterns.getValue(haptic)),
                WateryHaptics.plan(haptic, enabled = true, supported = all),
            )
        }
    }

    @Test
    fun `a droplet is one soft low tick`() {
        assertEquals(
            HapticPlan.Composed(listOf(HapticStep(HapticPrimitive.LowTick, 0.55f))),
            WateryHaptics.plan(WateryHaptic.Droplet, enabled = true, supported = all),
        )
    }

    @Test
    fun `a ripple spreads as three fading beats`() {
        val steps = (WateryHaptics.plan(WateryHaptic.Ripple, true, all) as HapticPlan.Composed).steps
        assertEquals(
            listOf(
                HapticStep(HapticPrimitive.LowTick, 0.6f, 0),
                HapticStep(HapticPrimitive.Tick, 0.32f, 38),
                HapticStep(HapticPrimitive.Tick, 0.14f, 76),
            ),
            steps,
        )
    }

    @Test
    fun `a settle falls then lands`() {
        assertEquals(
            HapticPlan.Composed(
                listOf(
                    HapticStep(HapticPrimitive.QuickFall, 0.35f, 0),
                    HapticStep(HapticPrimitive.Thud, 0.45f, 24),
                ),
            ),
            WateryHaptics.plan(WateryHaptic.Settle, true, all),
        )
    }

    @Test
    fun `every pattern fades rather than grows`() {
        for ((haptic, steps) in WateryHaptics.patterns) {
            if (haptic == WateryHaptic.Settle) continue
            steps.zipWithNext().forEach { (a, b) -> assertTrue("$haptic grows", b.scale <= a.scale) }
        }
    }

    @Test
    fun `a missing low tick is played as a tick`() {
        assertEquals(
            HapticPlan.Composed(listOf(HapticStep(HapticPrimitive.Tick, 0.55f))),
            WateryHaptics.plan(WateryHaptic.Droplet, true, setOf(HapticPrimitive.Tick)),
        )
    }

    @Test
    fun `missing settle primitives fall to their soft substitutes`() {
        assertEquals(
            HapticPlan.Composed(
                listOf(
                    HapticStep(HapticPrimitive.Tick, 0.35f, 0),
                    HapticStep(HapticPrimitive.Click, 0.45f, 24),
                ),
            ),
            WateryHaptics.plan(WateryHaptic.Settle, true, setOf(HapticPrimitive.Tick, HapticPrimitive.Click)),
        )
    }

    @Test
    fun `without the primitives each haptic uses the platform's own feedback`() {
        assertEquals(HapticPlan.Platform(HapticFallback.Tick), WateryHaptics.plan(WateryHaptic.Droplet, true, emptySet()))
        assertEquals(HapticPlan.Platform(HapticFallback.Click), WateryHaptics.plan(WateryHaptic.Ripple, true, emptySet()))
        assertEquals(HapticPlan.Platform(HapticFallback.Confirm), WateryHaptics.plan(WateryHaptic.Settle, true, emptySet()))
    }

    @Test
    fun `a substitute the vibrator also lacks falls back to the platform`() {
        assertEquals(
            HapticPlan.Platform(HapticFallback.Confirm),
            WateryHaptics.plan(WateryHaptic.Settle, true, setOf(HapticPrimitive.Tick)),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a scale above one is refused`() {
        HapticStep(HapticPrimitive.Tick, 1.1f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative scale is refused`() {
        HapticStep(HapticPrimitive.Tick, -0.1f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative delay is refused`() {
        HapticStep(HapticPrimitive.Tick, 0.5f, -1)
    }

    @Test
    fun `the scale's ends are allowed`() {
        assertEquals(0f, HapticStep(HapticPrimitive.Tick, 0f).scale)
        assertEquals(1f, HapticStep(HapticPrimitive.Tick, 1f).scale)
    }
}
