package dev.geode.feature.studio.core

import dev.geode.feature.studio.api.RenderLoudness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The gain that brings a Render's sound to its loudness target. */
class LoudnessTest {
    private val minus14 = RenderLoudness.MINUS_14_LUFS
    private val minus11 = RenderLoudness.MINUS_11_LUFS

    private fun gain(
        target: RenderLoudness,
        lufs: Double,
        peak: Double,
    ) = Loudness.gainDb(target, lufs, peak)

    @Test
    fun `each target has its own window`() {
        assertNull(Loudness.windowOf(RenderLoudness.LEAVE_AS_IS))
        assertEquals(LoudnessWindow(-14.0, -15.0, -13.0, -1.0), Loudness.windowOf(minus14))
        assertEquals(LoudnessWindow(-11.0, -12.0, -10.0, -1.0), Loudness.windowOf(minus11))
    }

    @Test
    fun `sound left as mixed gets no gain`() {
        assertEquals(0.0, gain(RenderLoudness.LEAVE_AS_IS, -30.0, 0.0), 0.0)
    }

    @Test
    fun `sound inside the window with a safe peak is left alone`() {
        assertEquals(0.0, gain(minus14, -15.0, -3.0), 0.0)
        assertEquals(0.0, gain(minus14, -13.0, -3.0), 0.0)
        assertEquals(0.0, gain(minus14, -14.5, -0.96), 0.0)
    }

    @Test
    fun `quiet sound is raised towards the aim`() {
        assertEquals(6.0, gain(minus14, -20.0, -10.0), 1e-9)
        assertEquals(5.0, gain(minus11, -16.0, -10.0), 1e-9)
    }

    @Test
    fun `a raise stops at the peak ceiling`() {
        assertEquals(2.0, gain(minus14, -20.0, -3.0), 1e-9)
    }

    @Test
    fun `loud sound is lowered to the aim`() {
        assertEquals(-4.0, gain(minus14, -10.0, -6.0), 1e-9)
    }

    @Test
    fun `a peak over the ceiling is pulled down even when the level is right`() {
        assertEquals(-0.5, gain(minus14, -14.0, -0.5), 1e-9)
        assertEquals(-1.5, gain(minus14, -15.0, 0.5), 1e-9)
    }

    @Test
    fun `a gain too small to hear is none`() {
        assertEquals(0.0, gain(minus14, -20.0, -1.05), 0.0)
        assertEquals(0.1, gain(minus14, -20.0, -1.1), 1e-9)
    }

    @Test
    fun `a gain in dB is a factor on the samples`() {
        assertEquals(1.0, Loudness.factorOf(0.0), 1e-12)
        assertEquals(10.0, Loudness.factorOf(20.0), 1e-9)
        assertEquals(0.5011872336, Loudness.factorOf(-6.0), 1e-9)
    }

    @Test
    fun `a scaled sample rounds and clips at the 16-bit range rather than wrapping`() {
        assertEquals(200.toShort(), Loudness.scaled(100, 2.0))
        assertEquals((-150).toShort(), Loudness.scaled(-300, 0.5))
        assertEquals(51.toShort(), Loudness.scaled(101, 0.5))
        assertEquals(Short.MAX_VALUE, Loudness.scaled(20_000, 2.0))
        assertEquals(Short.MIN_VALUE, Loudness.scaled(-20_000, 2.0))
        assertEquals(Short.MAX_VALUE, Loudness.scaled(Short.MAX_VALUE, 1.0))
    }
}
