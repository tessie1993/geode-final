package dev.geode.studio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A Recording's size, bit rate and samples (#317). */
class RecordingMathTest {
    @Test
    fun `the screen's own size when the encoder takes it`() {
        assertEquals(1080 to 2400, RecordingMath.sizeFor(1080, 2400, 2, 2) { _, _ -> true })
    }

    @Test
    fun `sides are rounded down to the encoder's alignment and kept even`() {
        assertEquals(1072 to 2400, RecordingMath.sizeFor(1080, 2401, 16, 2) { _, _ -> true })
        assertEquals(1078 to 2400, RecordingMath.sizeFor(1079, 2401, 1, 1) { _, _ -> true })
    }

    @Test
    fun `a size the encoder refuses steps down, keeping the shape`() {
        val tried = mutableListOf<Pair<Int, Int>>()
        val size =
            RecordingMath.sizeFor(1000, 2000, 2, 2) { w, h ->
                tried += w to h
                w <= 800
            }
        assertEquals(800 to 1600, size)
        assertEquals(listOf(1000 to 2000, 950 to 1900, 900 to 1800, 850 to 1700, 800 to 1600), tried)
    }

    @Test
    fun `no smaller than half, and nothing when the encoder takes none`() {
        val tried = mutableListOf<Pair<Int, Int>>()
        assertNull(
            RecordingMath.sizeFor(1000, 2000, 2, 2) { w, h ->
                tried += w to h
                false
            },
        )
        assertEquals(500 to 1000, tried.last())
        assertEquals(11, tried.size)
        assertNull(RecordingMath.sizeFor(0, 2000, 2, 2) { _, _ -> true })
        assertNull(RecordingMath.sizeFor(1000, 0, 2, 2) { _, _ -> true })
    }

    @Test
    fun `the bit rate follows the size, within its bounds`() {
        assertEquals(18_662_400, RecordingMath.bitRateOf(1080, 2400))
        assertEquals(2_000_000, RecordingMath.bitRateOf(100, 100))
        assertEquals(40_000_000, RecordingMath.bitRateOf(4000, 4000))
    }

    @Test
    fun `samples become 16-bit, clipped at full scale`() {
        assertEquals(0.toShort(), RecordingMath.pcm16(0f))
        assertEquals(Short.MAX_VALUE, RecordingMath.pcm16(1f))
        assertEquals(Short.MAX_VALUE, RecordingMath.pcm16(3f))
        assertEquals((-Short.MAX_VALUE).toShort(), RecordingMath.pcm16(-2f))
        assertEquals(16384.toShort(), RecordingMath.pcm16(0.5f))
    }

    @Test
    fun `PCM at the Recording's rate passes as it is`() {
        val matcher = RateMatcher(48_000)
        val pcm = floatArrayOf(0.1f, -0.1f, 0.2f, -0.2f, 9f, 9f)
        assertArrayEquals(floatArrayOf(0.1f, -0.1f, 0.2f, -0.2f), matcher.convert(pcm, 2, 48_000), 0f)
        assertArrayEquals(floatArrayOf(0.1f, -0.1f), matcher.convert(pcm, 1, 0), 0f)
        assertEquals(0, matcher.convert(pcm, 0, 44_100).size)
    }

    @Test
    fun `PCM at another rate is interpolated to the Recording's`() {
        val matcher = RateMatcher(4)
        val pcm = floatArrayOf(0f, 0f, 1f, -1f)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f / 3, -1f / 3, 2f / 3, -2f / 3, 1f, -1f), matcher.convert(pcm, 2, 2), 1e-6f)
    }

    @Test
    fun `the part of a frame left over is carried, so the length stays exact`() {
        val matcher = RateMatcher(3)
        val pcm = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f)
        val lengths = List(4) { matcher.convert(pcm, 2, 4).size / 2 }
        assertEquals(listOf(1, 2, 1, 2), lengths)
        assertArrayEquals(floatArrayOf(0.5f, 0.5f), RateMatcher(1).convert(pcm, 2, 2), 0f)
    }
}
