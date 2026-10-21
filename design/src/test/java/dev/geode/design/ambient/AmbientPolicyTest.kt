package dev.geode.design.ambient

import org.junit.Assert.assertEquals
import org.junit.Test

class AmbientPolicyTest {
    private fun effective(
        chosen: AmbientMode,
        powerSaver: Boolean = false,
        reducedMotion: Boolean = false,
    ) = AmbientPolicy.effectiveMode(chosen, powerSaver, reducedMotion)

    @Test
    fun `drifting is the default`() {
        assertEquals(AmbientMode.Drifting, AmbientMode.Default)
    }

    @Test
    fun `with nothing in the way the choice stands`() {
        for (mode in AmbientMode.entries) assertEquals(mode, effective(mode))
    }

    @Test
    fun `battery saver turns drifting still`() {
        assertEquals(AmbientMode.Still, effective(AmbientMode.Drifting, powerSaver = true))
    }

    @Test
    fun `reduced motion turns drifting still`() {
        assertEquals(AmbientMode.Still, effective(AmbientMode.Drifting, reducedMotion = true))
        assertEquals(AmbientMode.Still, effective(AmbientMode.Drifting, powerSaver = true, reducedMotion = true))
    }

    @Test
    fun `still stays still whatever the system says`() {
        for (saver in listOf(false, true)) {
            for (reduced in listOf(false, true)) {
                assertEquals(AmbientMode.Still, effective(AmbientMode.Still, saver, reduced))
            }
        }
    }

    @Test
    fun `off always wins`() {
        for (saver in listOf(false, true)) {
            for (reduced in listOf(false, true)) {
                assertEquals(AmbientMode.Off, effective(AmbientMode.Off, saver, reduced))
            }
        }
    }
}
