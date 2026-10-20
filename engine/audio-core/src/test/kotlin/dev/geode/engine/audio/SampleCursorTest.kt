package dev.geode.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Live analysis is driven by the samples the ring receives, not by a clock: whoever reads through a
 * cursor sees every frame once, in order, however the writes and reads interleave.
 */
class SampleCursorTest {
    private fun ring(capacity: Int = 64) = SampleRing(capacityFrames = capacity, channelCount = 2, maxWriteFrames = capacity / 4)

    /** Writes stereo frames whose left sample is [first], [first] + 1, ... and right sample is the negation. */
    private fun SampleRing.writeRamp(
        first: Int,
        count: Int,
    ) {
        val pcm = FloatArray(count * 2)
        for (i in 0 until count) {
            pcm[i * 2] = (first + i).toFloat()
            pcm[i * 2 + 1] = -(first + i).toFloat()
        }
        write(pcm, count, sourceChannelCount = 2)
    }

    /** Reads until the cursor has nothing new, [chunk] frames at a time; returns the left samples. */
    private fun SampleCursor.drain(chunk: Int = 5): List<Int> {
        val out = FloatArray(chunk * 2)
        val left = mutableListOf<Int>()
        while (true) {
            val frames = read(out)
            if (frames == 0) return left
            for (i in 0 until frames) left += out[i * channels].toInt()
        }
    }

    @Test
    fun `every frame the ring receives is read once and in order`() {
        val r = ring()
        val cursor = SampleCursor(r, startBehindFrames = 64)
        val read = mutableListOf<Int>()
        var written = 0
        while (written < 200) {
            r.writeRamp(written, 7)
            written += 7
            read += cursor.drain(chunk = 3)
        }
        assertEquals((0 until written).toList(), read)
    }

    @Test
    fun `stereo is read interleaved`() {
        val r = ring()
        val cursor = SampleCursor(r, startBehindFrames = 64)
        r.writeRamp(10, 2)
        val out = FloatArray(4)

        assertEquals(2, cursor.read(out))
        assertEquals(2, cursor.channels)
        assertEquals(listOf(10f, -10f, 11f, -11f), out.toList())
    }

    @Test
    fun `a mono source is read as one channel`() {
        val r = ring()
        val cursor = SampleCursor(r, startBehindFrames = 64)
        r.write(floatArrayOf(1f, 2f, 3f), 3, sourceChannelCount = 1)
        val out = FloatArray(8)

        assertEquals(3, cursor.read(out))
        assertEquals(1, cursor.channels)
        assertEquals(listOf(1f, 2f, 3f), out.take(3))
    }

    @Test
    fun `a cursor that starts mid-stream begins startBehindFrames behind the writer`() {
        val r = ring()
        repeat(4) { r.writeRamp(it * 10, 10) }
        val cursor = SampleCursor(r, startBehindFrames = 8)

        assertEquals((32 until 40).toList(), cursor.drain())
    }

    @Test
    fun `a new epoch is read from its first frame`() {
        val r = ring()
        val cursor = SampleCursor(r, startBehindFrames = 64)
        r.writeRamp(0, 12)
        cursor.drain()

        r.beginEpoch()
        r.writeRamp(100, 5)

        assertEquals((100 until 105).toList(), cursor.drain())
    }

    @Test
    fun `a cursor the writer lapped resumes at the oldest frame still intact`() {
        val r = ring(capacity = 16)
        val cursor = SampleCursor(r, startBehindFrames = 16)
        assertEquals(emptyList<Int>(), cursor.drain())
        repeat(10) { r.writeRamp(it * 4, 4) }

        // 40 frames written into 16 slots, 4 of them kept as the writer's runway: 28 is the oldest left.
        assertEquals((28 until 40).toList(), cursor.drain())
    }
}
