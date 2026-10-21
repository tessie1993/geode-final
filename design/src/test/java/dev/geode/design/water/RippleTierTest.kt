package dev.geode.design.water

import org.junit.Assert.assertEquals
import org.junit.Test

class RippleTierTest {
    @Test
    fun `Android 13 and later ripple with the shader`() {
        assertEquals(RippleTier.Shader, RippleTier.select(sdkInt = 33, animationsEnabled = true))
        assertEquals(RippleTier.Shader, RippleTier.select(sdkInt = 36, animationsEnabled = true))
    }

    @Test
    fun `Android 12 ripples with the pre-rendered ring`() {
        assertEquals(RippleTier.Ring, RippleTier.select(sdkInt = 31, animationsEnabled = true))
        assertEquals(RippleTier.Ring, RippleTier.select(sdkInt = 32, animationsEnabled = true))
    }

    @Test
    fun `removed animations always get the still pulse`() {
        assertEquals(RippleTier.Pulse, RippleTier.select(sdkInt = 31, animationsEnabled = false))
        assertEquals(RippleTier.Pulse, RippleTier.select(sdkInt = 36, animationsEnabled = false))
    }

    @Test
    fun `the shader needs AGSL`() {
        assertEquals(33, RippleTier.SHADER_MIN_SDK)
    }
}
