package dev.geode.design.elements.water

import dev.geode.design.elements.water.WaterTouchTiming.BLOOM_MS
import dev.geode.design.elements.water.WaterTouchTiming.BLOOM_START
import dev.geode.design.elements.water.WaterTouchTiming.REDUCED_FADE_MS
import dev.geode.design.elements.water.WaterTouchTiming.RING_MS
import dev.geode.design.elements.water.WaterTouchTiming.bloomAlpha
import dev.geode.design.elements.water.WaterTouchTiming.bloomSpread
import dev.geode.design.elements.water.WaterTouchTiming.isFinished
import dev.geode.design.elements.water.WaterTouchTiming.progressAt
import dev.geode.design.elements.water.WaterTouchTiming.ringAlpha
import dev.geode.design.elements.water.WaterTouchTiming.ringTravel
import dev.geode.design.elements.water.WaterTouchTiming.runMs
import dev.geode.design.elements.water.WaterTouchTiming.sinkDp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The timing of Water's touch (#154): the ink bloom, the ripple ring ahead of it, and the sink. */
class WaterTouchTimingTest {
    private val steps = (0..100).map { it / 100f }

    @Test
    fun `the bloom runs 600 ms and the ring 450 ms, or a 150 ms fade under reduced motion`() {
        assertEquals(600, BLOOM_MS)
        assertEquals(450, RING_MS)
        assertEquals(150, REDUCED_FADE_MS)
        assertEquals(BLOOM_MS, runMs(reducedMotion = false))
        assertEquals(REDUCED_FADE_MS, runMs(reducedMotion = true))
    }

    @Test
    fun `progress is the share of the run gone, held between 0 and 1`() {
        assertEquals(0f, progressAt(0L, reducedMotion = false), 0f)
        assertEquals(0.5f, progressAt(300L, reducedMotion = false), 1e-6f)
        assertEquals(1f, progressAt(600L, reducedMotion = false), 0f)
        assertEquals(1f, progressAt(5_000L, reducedMotion = false), 0f)
        assertEquals(0f, progressAt(-20L, reducedMotion = false), 0f)
        assertEquals(0.5f, progressAt(75L, reducedMotion = true), 1e-6f)
        assertEquals(1f, progressAt(150L, reducedMotion = true), 0f)
    }

    @Test
    fun `the bloom spreads from the touch point with a fast start and a soft end`() {
        assertEquals(BLOOM_START, bloomSpread(0f, reducedMotion = false), 1e-6f)
        assertEquals(1f, bloomSpread(1f, reducedMotion = false), 1e-6f)
        val span = 1f - BLOOM_START
        // Fast start: past half its spread in the first quarter of the run.
        assertTrue(bloomSpread(0.25f, reducedMotion = false) - BLOOM_START > span / 2f)
        // Soft end: the last tenth adds almost nothing.
        assertTrue(bloomSpread(1f, reducedMotion = false) - bloomSpread(0.9f, reducedMotion = false) < span * 0.01f)
        steps.zipWithNext().forEach { (a, b) ->
            assertTrue("the bloom shrank between $a and $b", bloomSpread(b, false) >= bloomSpread(a, false))
        }
    }

    @Test
    fun `the bloom fades to nothing as it spreads`() {
        assertEquals(1f, bloomAlpha(0f), 0f)
        assertEquals(0.5f, bloomAlpha(0.5f), 1e-6f)
        assertEquals(0f, bloomAlpha(1f), 0f)
        assertEquals(0f, bloomAlpha(2f), 0f)
    }

    @Test
    fun `the ring runs out over 450 ms, always ahead of the bloom`() {
        val ringEnd = RING_MS.toFloat() / BLOOM_MS
        assertEquals(1f, ringTravel(ringEnd), 1e-6f)
        assertEquals(1f, ringTravel(1f), 0f)
        assertEquals(1f, ringAlpha(0f, reducedMotion = false), 0f)
        assertEquals(0f, ringAlpha(ringEnd, reducedMotion = false), 1e-6f)
        assertEquals(0f, ringAlpha(1f, reducedMotion = false), 0f)
        assertTrue(ringAlpha(ringEnd / 2f, reducedMotion = false) in 0.49f..0.51f)
        steps.forEach { p ->
            assertTrue("the ring fell behind the bloom at $p", ringTravel(p) >= bloomSpread(p, reducedMotion = false))
        }
    }

    @Test
    fun `reduced motion has no spread and no travel, only a fade in place`() {
        steps.forEach { p ->
            assertEquals(1f, bloomSpread(p, reducedMotion = true), 0f)
            assertEquals(0f, ringAlpha(p, reducedMotion = true), 0f)
        }
        assertEquals(1f, bloomAlpha(progressAt(0L, reducedMotion = true)), 0f)
        assertEquals(0f, bloomAlpha(progressAt(150L, reducedMotion = true)), 0f)
    }

    @Test
    fun `a pressed pebble sinks 1 dp, the primary one 2 dp, and none under reduced motion or at rest`() {
        assertEquals(1f, sinkDp(pressed = true, deep = false, reducedMotion = false), 0f)
        assertEquals(2f, sinkDp(pressed = true, deep = true, reducedMotion = false), 0f)
        assertEquals(0f, sinkDp(pressed = true, deep = true, reducedMotion = true), 0f)
        assertEquals(0f, sinkDp(pressed = false, deep = true, reducedMotion = false), 0f)
    }

    @Test
    fun `the touch costs nothing once its run is over`() {
        assertFalse(isFinished(0f))
        assertFalse(isFinished(0.99f))
        assertTrue(isFinished(1f))
        assertTrue(isFinished(progressAt(BLOOM_MS.toLong(), reducedMotion = false)))
    }
}
