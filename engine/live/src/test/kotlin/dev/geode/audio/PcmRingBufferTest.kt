package dev.geode.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The mono ring the Styles' waveforms read: what arrived since the last look, and the newest samples. */
class PcmRingBufferTest {
    @Test
    fun `stereo is mixed down and the side kept apart`() {
        val ring = PcmRingBuffer(capacity = 16)
        ring.write(floatArrayOf(1f, 0f, 0.5f, 0.5f, -1f, 1f), 3, 2)
        val mono = FloatArray(3)
        val side = FloatArray(3)
        assertTrue(ring.snapshotLatest(mono))
        assertTrue(ring.snapshotLatestSide(side))
        assertArrayEquals(floatArrayOf(0.5f, 0.5f, 0f), mono, 0f)
        assertArrayEquals(floatArrayOf(0.5f, 0f, -1f), side, 0f)
    }

    @Test
    fun `a reader copies what arrived since its last look, newest last`() {
        val ring = PcmRingBuffer(capacity = 16)
        val out = FloatArray(8)
        assertEquals(0, ring.copyNewSince(0L, out))
        ring.write(floatArrayOf(1f, 2f, 3f), 3, 1)
        assertEquals(3, ring.copyNewSince(0L, out))
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), out.copyOf(3), 0f)
        assertEquals(3L, ring.lastCopyEndIndex)
        ring.write(floatArrayOf(4f, 5f), 2, 1)
        assertEquals(2, ring.copyNewSince(ring.lastCopyEndIndex, out))
        assertArrayEquals(floatArrayOf(4f, 5f), out.copyOf(2), 0f)
        assertEquals(0, ring.copyNewSince(ring.lastCopyEndIndex, out))
    }

    @Test
    fun `a reader that fell far behind resumes near the writer`() {
        val ring = PcmRingBuffer(capacity = 16)
        ring.write(FloatArray(40) { it.toFloat() }, 40, 1)
        val out = FloatArray(32)
        // Never more than three quarters of the ring, so the seam the writer is on is never read.
        assertEquals(12, ring.copyNewSince(0L, out))
        assertArrayEquals(FloatArray(12) { (28 + it).toFloat() }, out.copyOf(12), 0f)
        assertEquals(40L, ring.currentWriteIndex())
    }

    @Test
    fun `a snapshot needs that many samples written`() {
        val ring = PcmRingBuffer(capacity = 16)
        assertFalse(ring.snapshotLatest(FloatArray(4)))
        ring.write(floatArrayOf(1f, 2f, 3f, 4f), 4, 1)
        assertTrue(ring.snapshotLatest(FloatArray(4)))
        assertFalse(ring.snapshotLatest(FloatArray(5)))
        assertFalse(ring.snapshotLatest(FloatArray(32)))
    }
}
