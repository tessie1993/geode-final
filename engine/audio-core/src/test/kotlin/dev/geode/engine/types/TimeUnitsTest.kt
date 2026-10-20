package dev.geode.engine.types

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The units replace raw `Long`s that were converted by hand all over the app, so what is pinned
 * here is the arithmetic those call sites relied on: exact scaling one way, truncation toward
 * zero the other, and frames counted at a sample rate.
 */
class TimeUnitsTest {
    @Test
    fun `milliseconds scale to microseconds exactly`() {
        assertEquals(Micros(1_500_000L), Millis(1_500L).toMicros())
        assertEquals(Micros(-2_000L), Millis(-2L).toMicros())
    }

    @Test
    fun `microseconds to milliseconds drops the sub-millisecond part`() {
        assertEquals(Millis(1_234L), Micros(1_234_999L).toMillis())
        assertEquals(Millis(0L), Micros(999L).toMillis())
        assertEquals(Millis(-1L), Micros(-1_999L).toMillis())
    }

    @Test
    fun `a second of frames at 48 kHz is a thousand milliseconds`() {
        assertEquals(Millis(1_000L), Frames(48_000L).toMillis(Hz(48_000)))
    }

    @Test
    fun `frames to milliseconds truncates a partial millisecond`() {
        // 44_099 frames at 44.1 kHz is 999.977... ms.
        assertEquals(Millis(999L), Frames(44_099L).toMillis(Hz(44_100)))
        // 441 frames is exactly 10 ms; one frame short of that is 9.977... ms.
        assertEquals(Millis(10L), Frames(441L).toMillis(Hz(44_100)))
        assertEquals(Millis(9L), Frames(440L).toMillis(Hz(44_100)))
    }

    @Test
    fun `frames to microseconds truncates a partial microsecond`() {
        // One frame at 44.1 kHz is 22.675... us.
        assertEquals(Micros(22L), Frames(1L).toMicros(Hz(44_100)))
        assertEquals(Micros(1_000_000L), Frames(44_100L).toMicros(Hz(44_100)))
    }

    @Test
    fun `milliseconds to frames truncates a partial frame`() {
        // 10 ms at 44.1 kHz is exactly 441 frames; 1 ms is 44.1 frames.
        assertEquals(Frames(441L), Millis(10L).toFrames(Hz(44_100)))
        assertEquals(Frames(44L), Millis(1L).toFrames(Hz(44_100)))
        assertEquals(Frames(96_000L), Millis(2_000L).toFrames(Hz(48_000)))
    }

    @Test
    fun `microseconds to frames truncates a partial frame`() {
        // 1 ms is 48 frames at 48 kHz; 30 us is 1.44 frames.
        assertEquals(Frames(48L), Micros(1_000L).toFrames(Hz(48_000)))
        assertEquals(Frames(1L), Micros(30L).toFrames(Hz(48_000)))
    }

    @Test
    fun `a long track does not overflow on the way to frames`() {
        // Ten hours at 192 kHz.
        assertEquals(Frames(6_912_000_000L), Millis(36_000_000L).toFrames(Hz(192_000)))
        assertEquals(Millis(36_000_000L), Frames(6_912_000_000L).toMillis(Hz(192_000)))
    }

    @Test
    fun `milliseconds add, subtract and order`() {
        assertEquals(Millis(1_250L), Millis(1_000L) + Millis(250L))
        assertEquals(Millis(750L), Millis(1_000L) - Millis(250L))
        assertTrue(Millis(999L) < Millis(1_000L))
        assertFalse(Millis(1_000L) < Millis(1_000L))
        assertTrue(Millis(1_000L) > Millis(-1L))
        assertEquals(Millis(500L), Millis(900L).coerceIn(Millis(0L), Millis(500L)))
        assertEquals(Millis(0L), Millis(-3L).coerceAtLeast(Millis.ZERO))
    }

    @Test
    fun `microseconds add, subtract and order`() {
        assertEquals(Micros(1_250L), Micros(1_000L) + Micros(250L))
        assertEquals(Micros(750L), Micros(1_000L) - Micros(250L))
        assertTrue(Micros(1L) < Micros(2L))
        assertTrue(Micros(2L) > Micros(1L))
        assertEquals(0, Micros(5L).compareTo(Micros(5L)))
    }

    @Test
    fun `frames add, subtract and order`() {
        assertEquals(Frames(1_250L), Frames(1_000L) + Frames(250L))
        assertEquals(Frames(750L), Frames(1_000L) - Frames(250L))
        assertTrue(Frames(1L) < Frames(2L))
        assertTrue(Frames(2L) > Frames(1L))
        assertEquals(0, Frames(5L).compareTo(Frames(5L)))
    }

    @Test
    fun `zero is zero`() {
        assertEquals(0L, Millis.ZERO.value)
        assertEquals(0L, Micros.ZERO.value)
    }

    @Test
    fun `units print with their suffix`() {
        assertEquals("1500ms", Millis(1_500L).toString())
        assertEquals("20us", Micros(20L).toString())
        assertEquals("48000Hz", Hz(48_000).toString())
        assertEquals("441 frames", Frames(441L).toString())
    }
}
