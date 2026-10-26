package dev.geode.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** A Render's sound window over a track decoded in order (#342). */
class PcmWindowTest {
    private fun window(
        pcm: PcmWindow,
        end: Long,
        frames: Int,
    ): FloatArray = FloatArray(frames * 2).also { assertEquals(frames, pcm.window(end, it, frames)) }

    @Test
    fun `the window ends just before the frame asked for, interleaved stereo`() {
        val pcm = PcmWindow(8)
        pcm.write(floatArrayOf(1f, -1f, 2f, -2f, 3f, -3f, 4f, -4f), 4, 2)
        assertEquals(4L, pcm.written)
        assertArrayEquals(floatArrayOf(2f, -2f, 3f, -3f), window(pcm, 3, 2), 0f)
        assertArrayEquals(floatArrayOf(3f, -3f, 4f, -4f), window(pcm, 4, 2), 0f)
    }

    @Test
    fun `before the start and past what is decoded is silence`() {
        val pcm = PcmWindow(8)
        pcm.write(floatArrayOf(1f, 1f, 2f, 2f), 2, 2)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 0f, 1f, 1f), window(pcm, 1, 3), 0f)
        assertArrayEquals(floatArrayOf(2f, 2f, 0f, 0f), window(pcm, 3, 2), 0f)
    }

    @Test
    fun `mono is heard in both ears and only two channels are kept`() {
        val mono = PcmWindow(4)
        mono.write(floatArrayOf(0.5f, 0.25f), 2, 1)
        assertArrayEquals(floatArrayOf(0.5f, 0.5f, 0.25f, 0.25f), window(mono, 2, 2), 0f)
        val surround = PcmWindow(4)
        surround.write(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), 2, 3)
        assertArrayEquals(floatArrayOf(1f, 2f, 4f, 5f), window(surround, 2, 2), 0f)
    }

    @Test
    fun `the ring keeps only the newest frames`() {
        val pcm = PcmWindow(2)
        pcm.write(floatArrayOf(1f, 1f, 2f, 2f, 3f, 3f), 3, 2)
        assertArrayEquals(floatArrayOf(0f, 0f, 2f, 2f, 3f, 3f), window(pcm, 3, 3), 0f)
    }

    @Test
    fun `a short write or no channels writes what it can`() {
        val pcm = PcmWindow(4)
        pcm.write(floatArrayOf(1f, 1f), 5, 2)
        assertEquals(1L, pcm.written)
        pcm.write(floatArrayOf(1f, 1f), 1, 0)
        assertEquals(1L, pcm.written)
        // The window is never longer than its buffer.
        assertEquals(1, pcm.window(1, FloatArray(2), 4))
    }
}
