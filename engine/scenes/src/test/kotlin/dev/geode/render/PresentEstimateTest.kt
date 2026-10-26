package dev.geode.render

import org.junit.Assert.assertEquals
import org.junit.Test

/** When a frame asked for at a vsync is expected on screen. */
class PresentEstimateTest {
    private val vsync = 1_000_000_000L
    private val period = 8_333_333L

    @Test
    fun `the platform's frame timeline is believed when it lies just ahead`() {
        assertEquals(vsync + 20_000_000L, PresentEstimate.expectedPresentNanos(vsync, period, vsync + 20_000_000L))
        assertEquals(vsync + 1L, PresentEstimate.expectedPresentNanos(vsync, period, vsync + 1L))
        assertEquals(
            vsync + PresentEstimate.MAX_LEAD_NANOS,
            PresentEstimate.expectedPresentNanos(vsync, period, vsync + PresentEstimate.MAX_LEAD_NANOS),
        )
    }

    @Test
    fun `without a timeline the frame shows two refreshes after its vsync`() {
        assertEquals(vsync + 2 * period, PresentEstimate.expectedPresentNanos(vsync, period, 0L))
    }

    @Test
    fun `a timeline behind the vsync or too far ahead is not believed`() {
        val estimate = vsync + 2 * period
        assertEquals(estimate, PresentEstimate.expectedPresentNanos(vsync, period, vsync))
        assertEquals(estimate, PresentEstimate.expectedPresentNanos(vsync, period, vsync - 5L))
        assertEquals(estimate, PresentEstimate.expectedPresentNanos(vsync, period, vsync + PresentEstimate.MAX_LEAD_NANOS + 1))
    }

    @Test
    fun `an unmeasured panel is taken to refresh at 60 Hz`() {
        val expected = vsync + 2 * PresentEstimate.DEFAULT_VSYNC_NANOS
        assertEquals(expected, PresentEstimate.expectedPresentNanos(vsync, 0L, 0L))
        assertEquals(expected, PresentEstimate.expectedPresentNanos(vsync, -1L, 0L))
    }
}
