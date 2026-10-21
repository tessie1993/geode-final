package dev.geode.design.water

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

class RipplePhysicsTest {
    private val spec =
        RippleSpec(durationMillis = 800, peakAmplitudePx = 9f, wavelengthPx = 60f, bandPx = 40f, damping = 2f)
    private val ripple = Ripple(x = 10f, y = 20f, startNanos = START, reachPx = 300f)

    private fun at(millis: Long) = START + millis * NANOS_PER_MILLI

    @Test
    fun `progress runs from 0 at the touch to 1 at the end and stays there`() {
        assertEquals(0f, RipplePhysics.progress(ripple, START, spec), EPS)
        assertEquals(0.25f, RipplePhysics.progress(ripple, at(200), spec), EPS)
        assertEquals(1f, RipplePhysics.progress(ripple, at(800), spec), EPS)
        assertEquals(1f, RipplePhysics.progress(ripple, at(5_000), spec), EPS)
    }

    @Test
    fun `a frame before the touch counts as the touch itself`() {
        assertEquals(0f, RipplePhysics.progress(ripple, START - NANOS_PER_MILLI, spec), EPS)
    }

    @Test
    fun `a ripple is alive until its duration has passed`() {
        assertTrue(RipplePhysics.isAlive(ripple, START, spec))
        assertTrue(RipplePhysics.isAlive(ripple, at(799), spec))
        assertFalse(RipplePhysics.isAlive(ripple, at(800), spec))
    }

    @Test
    fun `the wavefront starts at the touch and decelerates to the far corner`() {
        assertEquals(0f, RipplePhysics.frame(ripple, START, spec).radiusPx, EPS)
        // Quadratic ease out: at half its life the front has covered three quarters of the reach.
        assertEquals(225f, RipplePhysics.frame(ripple, at(400), spec).radiusPx, EPS)
        assertEquals(300f, RipplePhysics.frame(ripple, at(800), spec).radiusPx, EPS)
        val early = RipplePhysics.frame(ripple, at(200), spec).radiusPx
        val late = RipplePhysics.frame(ripple, at(600), spec).radiusPx - RipplePhysics.frame(ripple, at(400), spec).radiusPx
        assertTrue("the front slows as it spreads", early > late)
    }

    @Test
    fun `the height decays and is exactly still at the end`() {
        val first = RipplePhysics.frame(ripple, START, spec)
        assertEquals(9f, first.amplitudePx, EPS)
        assertEquals(1f, first.strength, EPS)
        val half = RipplePhysics.frame(ripple, at(400), spec)
        assertEquals(exp(-1f) * 0.5f, half.strength, EPS)
        assertEquals(9f * exp(-1f) * 0.5f, half.amplitudePx, EPS)
        val end = RipplePhysics.frame(ripple, at(800), spec)
        assertEquals(0f, end.amplitudePx, EPS)
        assertEquals(0f, end.strength, EPS)
    }

    @Test
    fun `strength never rises over the ripple's life`() {
        var last = Float.MAX_VALUE
        for (ms in 0L..800L step 20) {
            val s = RipplePhysics.frame(ripple, at(ms), spec).strength
            assertTrue("strength rose at $ms ms", s <= last)
            last = s
        }
    }

    @Test
    fun `without damping only the linear fade remains`() {
        val undamped = spec.copy(damping = 0f)
        assertEquals(0.5f, RipplePhysics.frame(ripple, at(400), undamped).strength, EPS)
    }

    @Test
    fun `reach is the distance to the farthest corner`() {
        assertEquals(500f, RipplePhysics.reach(0f, 0f, 300f, 400f), EPS)
        assertEquals(500f, RipplePhysics.reach(300f, 400f, 300f, 400f), EPS)
        assertEquals(500f, RipplePhysics.reach(300f, 0f, 300f, 400f), EPS)
        assertEquals(250f, RipplePhysics.reach(150f, 200f, 300f, 400f), EPS)
        assertEquals(hypot(250f, 300f), RipplePhysics.reach(50f, 100f, 300f, 400f), EPS)
    }

    @Test
    fun `displacement is zero on the wavefront and peaks a quarter wave out`() {
        val frame = RippleFrame(radiusPx = 100f, amplitudePx = 4f, strength = 1f)
        assertEquals(0f, RipplePhysics.displacement(frame, 100f, spec), EPS)
        val quarter = 115f
        val band = exp(-(15f * 15f) / (40f * 40f))
        assertEquals(4f * band, RipplePhysics.displacement(frame, quarter, spec), EPS)
        assertEquals(-4f * band, RipplePhysics.displacement(frame, 85f, spec), EPS)
    }

    @Test
    fun `displacement fades far from the wavefront`() {
        val frame = RippleFrame(radiusPx = 100f, amplitudePx = 4f, strength = 1f)
        val near = kotlin.math.abs(RipplePhysics.displacement(frame, 115f, spec))
        val far = kotlin.math.abs(RipplePhysics.displacement(frame, 235f, spec))
        assertTrue(far < near * 0.01f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a ripple must last some time`() {
        spec.copy(durationMillis = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a wavelength must be positive`() {
        spec.copy(wavelengthPx = 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a band must be positive`() {
        spec.copy(bandPx = 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an amplitude cannot be negative`() {
        spec.copy(peakAmplitudePx = -1f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `damping cannot be negative`() {
        spec.copy(damping = -0.1f)
    }

    @Test
    fun `zero amplitude and zero damping are allowed`() {
        val still = spec.copy(peakAmplitudePx = 0f, damping = 0f)
        assertEquals(0f, still.peakAmplitudePx, EPS)
    }

    private fun hypot(
        a: Float,
        b: Float,
    ) = kotlin.math.hypot(a, b)

    private companion object {
        const val START = 1_000_000_000L
        const val NANOS_PER_MILLI = 1_000_000L
        const val EPS = 1e-4f
    }
}
