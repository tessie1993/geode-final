package dev.geode.design.water

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RippleFieldTest {
    private val spec =
        RippleSpec(durationMillis = 100, peakAmplitudePx = 3f, wavelengthPx = 20f, bandPx = 10f, damping = 1f)

    private fun ripple(startMillis: Long) = Ripple(0f, 0f, startMillis * NANOS_PER_MILLI, 50f)

    @Test
    fun `an empty field is idle`() {
        assertTrue(RippleField().isIdle)
        assertEquals(RippleField.MAX_RIPPLES, RippleField().capacity)
    }

    @Test
    fun `a touch wakes the field`() {
        val field = RippleField().touched(ripple(0))
        assertFalse(field.isIdle)
        assertEquals(listOf(ripple(0)), field.ripples)
    }

    @Test
    fun `a touch on a full field retires the oldest ripple`() {
        val field = RippleField().touched(ripple(0)).touched(ripple(10)).touched(ripple(20))
        assertEquals(listOf(ripple(10), ripple(20)), field.ripples)
    }

    @Test
    fun `a field of one keeps only the newest ripple`() {
        val field = RippleField(capacity = 1).touched(ripple(0)).touched(ripple(10))
        assertEquals(listOf(ripple(10)), field.ripples)
    }

    @Test
    fun `pruning drops finished ripples and goes idle when all are still`() {
        val field = RippleField().touched(ripple(0)).touched(ripple(50))
        val mid = field.prunedAt(120 * NANOS_PER_MILLI, spec)
        assertEquals(listOf(ripple(50)), mid.ripples)
        assertTrue(mid.prunedAt(150 * NANOS_PER_MILLI, spec).isIdle)
    }

    @Test
    fun `pruning a field where everything still moves returns the same field`() {
        val field = RippleField().touched(ripple(0))
        assertSame(field, field.prunedAt(10 * NANOS_PER_MILLI, spec))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a field holds at least one ripple`() {
        RippleField(capacity = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a field holds no more ripples than the shader has slots`() {
        RippleField(capacity = RippleField.MAX_RIPPLES + 1)
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
