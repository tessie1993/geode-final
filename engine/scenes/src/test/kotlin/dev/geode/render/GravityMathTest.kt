package dev.geode.render

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Tilt's pure half (#315): sensor readings in device axes to the screen-space down the engine reads,
 * for every display rotation, both sensor kinds, the filters, and the readings that must count as none.
 */
class GravityMathTest {
    private val out = FloatArray(3)

    private fun down(
        rotation: Int,
        upX: Float,
        upY: Float,
        upZ: Float,
        sensor: GravitySensor = GravitySensor.GRAVITY,
    ): FloatArray {
        val result = FloatArray(3)
        assertTrue(GravityFilter().accept(sensor, floatArrayOf(upX, upY, upZ), 0L, rotation, result))
        return result
    }

    private fun assertVector(
        expected: FloatArray,
        actual: FloatArray,
    ) = assertArrayEquals(expected, actual, 1e-5f)

    @Test
    fun `upright in portrait, down is the bottom of the screen`() {
        assertVector(floatArrayOf(0f, -1f, 0f), down(GravityMath.ROTATION_0, 0f, 9.81f, 0f))
    }

    @Test
    fun `flat on a table, face up, down is into the screen`() {
        assertVector(floatArrayOf(0f, 0f, -1f), down(GravityMath.ROTATION_0, 0f, 0f, 9.81f))
    }

    @Test
    fun `turned to landscape either way, down is still the bottom of the picture`() {
        // Turned a quarter counter-clockwise: the phone's left edge is at the bottom, so the sensor's
        // up is +x, and the display reports ROTATION_90.
        assertVector(floatArrayOf(0f, -1f, 0f), down(GravityMath.ROTATION_90, 9.81f, 0f, 0f))
        // A quarter clockwise: the right edge is at the bottom, ROTATION_270.
        assertVector(floatArrayOf(0f, -1f, 0f), down(GravityMath.ROTATION_270, -9.81f, 0f, 0f))
        // Upside down.
        assertVector(floatArrayOf(0f, -1f, 0f), down(GravityMath.ROTATION_180, 0f, -9.81f, 0f))
    }

    @Test
    fun `a tilt keeps its side through each rotation`() {
        // Portrait, the right edge dipped: down leans right.
        assertVector(floatArrayOf(0.6f, -0.8f, 0f), down(GravityMath.ROTATION_0, -0.6f, 0.8f, 0f))
        // The same lean in the picture's terms, in every rotation, comes out the same.
        val lean = floatArrayOf(0.6f, -0.8f, 0f)
        assertVector(lean, down(GravityMath.ROTATION_90, 0.8f, 0.6f, 0f))
        assertVector(lean, down(GravityMath.ROTATION_180, 0.6f, -0.8f, 0f))
        assertVector(lean, down(GravityMath.ROTATION_270, -0.8f, -0.6f, 0f))
    }

    @Test
    fun `screen axes for each rotation`() {
        GravityMath.toScreen(GravityMath.ROTATION_0, 1f, 2f, 3f, out)
        assertVector(floatArrayOf(1f, 2f, 3f), out)
        GravityMath.toScreen(GravityMath.ROTATION_90, 1f, 2f, 3f, out)
        assertVector(floatArrayOf(-2f, 1f, 3f), out)
        GravityMath.toScreen(GravityMath.ROTATION_180, 1f, 2f, 3f, out)
        assertVector(floatArrayOf(-1f, -2f, 3f), out)
        GravityMath.toScreen(GravityMath.ROTATION_270, 1f, 2f, 3f, out)
        assertVector(floatArrayOf(2f, -1f, 3f), out)
        // Only the two low bits mean anything, as with Surface.ROTATION_*.
        GravityMath.toScreen(GravityMath.ROTATION_90 + 4, 1f, 2f, 3f, out)
        assertVector(floatArrayOf(-2f, 1f, 3f), out)
    }

    @Test
    fun `down is a unit vector opposite the reading`() {
        assertTrue(GravityMath.downOf(3f, 0f, 4f, out))
        assertVector(floatArrayOf(-0.6f, 0f, -0.8f), out)
        assertEquals(1f, sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2]), 1e-6f)
    }

    @Test
    fun `a reading with no direction is no reading`() {
        out.fill(7f)
        assertFalse(GravityMath.downOf(0f, 0f, 0f, out))
        assertFalse(GravityMath.downOf(GravityMath.MIN_LENGTH / 2f, 0f, 0f, out))
        assertFalse(GravityMath.downOf(Float.NaN, 9f, 0f, out))
        assertFalse(GravityMath.downOf(0f, Float.POSITIVE_INFINITY, 0f, out))
        assertFalse(GravityMath.downOf(0f, 0f, Float.NEGATIVE_INFINITY, out))
        assertFalse(GravityMath.downOf(3e38f, 3e38f, 0f, out))
        // Left alone, so a caller can keep the last good value.
        assertVector(floatArrayOf(7f, 7f, 7f), out)
        assertTrue(GravityMath.downOf(GravityMath.MIN_LENGTH, 0f, 0f, out))
    }

    @Test
    fun `a gravity reading it cannot read is no reading`() {
        assertFalse(GravityFilter().accept(GravitySensor.GRAVITY, floatArrayOf(0f, 9.81f), 0L, GravityMath.ROTATION_0, out))
        assertFalse(GravityFilter().accept(GravitySensor.GRAVITY, floatArrayOf(0f, Float.NaN, 0f), 0L, GravityMath.ROTATION_0, out))
        assertFalse(GravityFilter().accept(GravitySensor.GRAVITY, floatArrayOf(0f, 0f, 0f), 0L, GravityMath.ROTATION_0, out))
    }

    @Test
    fun `the one-euro filter passes a held value and its first value untouched`() {
        val euro = OneEuroFilter()
        assertEquals(5f, euro.filter(5f, 0f), 0f)
        assertEquals(5f, euro.filter(5f, 0.02f), 0f)
        assertEquals(5f, euro.filter(5f, 0.02f), 0f)
        // No time, no change.
        assertEquals(5f, euro.filter(9f, 0f), 0f)
    }

    @Test
    fun `the one-euro filter follows a step part way, by its cutoff and the step's speed`() {
        val euro = OneEuroFilter(minCutoffHz = 1f, beta = 0.8f, derivativeCutoffHz = 1f)
        euro.filter(0f, 0f)
        val dt = 0.02f
        val tau = 1f / (2f * Math.PI.toFloat())
        val alpha = 1f / (1f + tau / dt)
        val speed = (1f / dt) * alpha
        val cutoff = 1f + 0.8f * speed
        val expected = 1f / (1f + 1f / (2f * Math.PI.toFloat() * cutoff) / dt)
        assertEquals(expected, euro.filter(1f, dt), 1e-5f)
        euro.reset()
        assertEquals(-2f, euro.filter(-2f, dt), 0f)
    }

    @Test
    fun `the one-euro filter settles the same at 50 and 200 readings a second`() {
        val slow = OneEuroFilter()
        val fast = OneEuroFilter()
        slow.filter(0f, 0f)
        fast.filter(0f, 0f)
        var a = 0f
        var b = 0f
        repeat(25) { a = slow.filter(1f, 0.02f) }
        repeat(100) { b = fast.filter(1f, 0.005f) }
        assertEquals(1f, a, 1e-3f)
        assertEquals(1f, b, 1e-3f)
    }

    @Test
    fun `a jittering phone held still comes out steadier`() {
        val filter = GravityFilter()
        var worst = 0f
        for (i in 0..100) {
            val jitter = if (i % 2 == 0) 0.3f else -0.3f
            filter.accept(GravitySensor.GRAVITY, floatArrayOf(jitter, 9.81f, 0f), i * 20_000_000L, GravityMath.ROTATION_0, out)
            if (i > 50) worst = maxOf(worst, kotlin.math.abs(out[0]))
        }
        // The raw jitter leans down 0.03 either way; filtered, far less.
        assertTrue(worst < 0.01f)
    }

    @Test
    fun `the accelerometer's first reading is taken as it is`() {
        assertVector(floatArrayOf(0f, -1f, 0f), down(GravityMath.ROTATION_0, 0f, 9.81f, 0f, GravitySensor.ACCELEROMETER))
    }

    @Test
    fun `the accelerometer is low-passed on its own timestamps`() {
        val filter = GravityFilter(accelerometerTauSeconds = 0.2f, oneEuro = false)
        assertTrue(filter.accept(GravitySensor.ACCELEROMETER, floatArrayOf(0f, 9.81f, 0f), 0L, GravityMath.ROTATION_0, out))
        // A jolt sideways, 20 ms later, moves "up" only the share 1 - e^(-0.02 / 0.2) of the way.
        assertTrue(filter.accept(GravitySensor.ACCELEROMETER, floatArrayOf(9.81f, 9.81f, 0f), 20_000_000L, GravityMath.ROTATION_0, out))
        val k = 1f - kotlin.math.exp(-0.1f)
        val upX = 9.81f * k
        val length = sqrt(upX * upX + 9.81f * 9.81f)
        assertVector(floatArrayOf(-upX / length, -9.81f / length, 0f), out)
    }

    @Test
    fun `the same motion at 50 and 200 readings a second`() {
        val slow = GravityFilter(oneEuro = false)
        val fast = GravityFilter(oneEuro = false)
        val slowOut = FloatArray(3)
        val fastOut = FloatArray(3)
        val level = floatArrayOf(0f, 9.81f, 0f)
        val leaning = floatArrayOf(4f, 9f, 0f)
        slow.accept(GravitySensor.ACCELEROMETER, level, 0L, GravityMath.ROTATION_0, slowOut)
        fast.accept(GravitySensor.ACCELEROMETER, level, 0L, GravityMath.ROTATION_0, fastOut)
        for (i in 1..10) slow.accept(GravitySensor.ACCELEROMETER, leaning, i * 20_000_000L, GravityMath.ROTATION_0, slowOut)
        for (i in 1..40) fast.accept(GravitySensor.ACCELEROMETER, leaning, i * 5_000_000L, GravityMath.ROTATION_0, fastOut)
        assertArrayEquals(slowOut, fastOut, 1e-4f)
    }

    @Test
    fun `a stalled clock or a long gap is bounded`() {
        val filter = GravityFilter(oneEuro = false)
        filter.accept(GravitySensor.ACCELEROMETER, floatArrayOf(0f, 9.81f, 0f), 1_000_000_000L, GravityMath.ROTATION_0, out)
        // A timestamp from before the last one moves nothing.
        filter.accept(GravitySensor.ACCELEROMETER, floatArrayOf(9.81f, 0f, 0f), 0L, GravityMath.ROTATION_0, out)
        assertVector(floatArrayOf(0f, -1f, 0f), out)
        // A gap of a minute counts as half a second, not as "fully there".
        filter.accept(GravitySensor.ACCELEROMETER, floatArrayOf(9.81f, 0f, 0f), 60_000_000_000L, GravityMath.ROTATION_0, out)
        assertTrue(out[1] < -0.05f)
        assertTrue(out[0] < -0.9f)
    }

    @Test
    fun `a non-finite accelerometer reading is dropped and reset starts afresh`() {
        val filter = GravityFilter()
        assertFalse(filter.accept(GravitySensor.ACCELEROMETER, floatArrayOf(Float.NaN, 9.81f, 0f), 0L, GravityMath.ROTATION_0, out))
        assertTrue(filter.accept(GravitySensor.ACCELEROMETER, floatArrayOf(0f, 9.81f, 0f), 0L, GravityMath.ROTATION_0, out))
        filter.reset()
        assertTrue(filter.accept(GravitySensor.ACCELEROMETER, floatArrayOf(0f, 0f, 9.81f), 10_000_000L, GravityMath.ROTATION_0, out))
        assertVector(floatArrayOf(0f, 0f, -1f), out)
    }
}
