package dev.geode.audio

import dev.geode.engine.audio.SampleRing
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** What a Recording hears: the ring's frames in order and once, and silence for the time nothing came. */
class RecordingTapTest {
    private val ring = SampleRing(capacityFrames = 1 shl 12, channelCount = 2)
    private var nowNs = 0L
    private var rate = 48_000
    private val tap = RecordingTap(ring, rateHz = { rate }, nowNs = { nowNs })
    private val out = FloatArray(2 * 100)

    private fun ramp(
        first: Int,
        frames: Int,
    ) = ring.write(FloatArray(frames * 2) { i -> if (i % 2 == 0) (first + i / 2).toFloat() else -(first + i / 2).toFloat() }, frames, 2)

    @Test
    fun `the first take marks the start and gives nothing`() {
        ramp(0, 10)
        assertEquals(0, tap.take(out))
        assertEquals(48_000, tap.lastRateHz)
        // What came before the start is not the Recording's.
        assertEquals(0, tap.take(out))
    }

    @Test
    fun `each take gives what arrived since the last, as interleaved stereo`() {
        tap.take(out)
        ramp(100, 5)
        assertEquals(5, tap.take(out))
        assertArrayEquals(floatArrayOf(100f, -100f, 101f, -101f), out.copyOf(4), 0f)
        assertEquals(104f, out[8], 0f)
        assertEquals(0, tap.take(out))
    }

    @Test
    fun `a mono source is heard on both channels`() {
        tap.take(out)
        ring.write(floatArrayOf(0.5f, 0.25f), 2, 1)
        assertEquals(2, tap.take(out))
        assertArrayEquals(floatArrayOf(0.5f, 0.5f, 0.25f, 0.25f), out.copyOf(4), 0f)
    }

    @Test
    fun `a take gives no more than the buffer holds and the rest next time`() {
        tap.take(out)
        ramp(0, 150)
        assertEquals(100, tap.take(out))
        assertEquals(0f, out[0], 0f)
        assertEquals(50, tap.take(out))
        assertEquals(100f, out[0], 0f)
    }

    @Test
    fun `a short lull is not silence, since the player writes ahead of the speaker`() {
        tap.take(out)
        nowNs = 400_000_000L
        assertEquals(0, tap.take(out))
    }

    @Test
    fun `once nothing has come for longer than the lead, silence fills the time that passed`() {
        tap.take(out)
        // 0.6 s without audio: 28,800 frames behind, filled up to a quarter second behind the clock.
        nowNs = 600_000_000L
        val big = FloatArray(2 * 48_000) { 9f }
        assertEquals(28_800 - 12_000, tap.take(big))
        assertEquals(0f, big[0], 0f)
        assertEquals(0f, big[2 * (28_800 - 12_000) - 1], 0f)
        assertEquals(9f, big[2 * (28_800 - 12_000)], 0f)
        // Caught up: nothing more until time passes again.
        assertEquals(0, tap.take(big))
        // Audio that arrives afterwards goes on from where the silence left off.
        ramp(7, 3)
        assertEquals(3, tap.take(big))
        assertEquals(7f, big[0], 0f)
    }

    @Test
    fun `silence is counted at the rate the ring receives now`() {
        rate = 24_000
        tap.take(out)
        nowNs = 1_000_000_000L
        val big = FloatArray(2 * 48_000)
        assertEquals(24_000 - 6_000, tap.take(big))
        assertEquals(24_000, tap.lastRateHz)
    }

    @Test
    fun `a new epoch of the ring starts the tap over with it`() {
        tap.take(out)
        ramp(0, 10)
        assertEquals(10, tap.take(out))
        ring.beginEpoch()
        ramp(500, 3)
        assertEquals(3, tap.take(out))
        assertEquals(500f, out[0], 0f)
    }
}
