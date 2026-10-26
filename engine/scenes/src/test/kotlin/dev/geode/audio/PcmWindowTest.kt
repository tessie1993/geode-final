package dev.geode.audio

import dev.geode.render.offscreen.PcmSpan
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

    @Test
    fun `a frame's span is its window and every frame heard since the last`() {
        val pcm = PcmWindow(16)
        pcm.write(FloatArray(20) { (it / 2 + 1).toFloat() }, 10, 2)
        val out = FloatArray(16)
        // The first frame, at frame 4: everything from the start is new, before a window of 2.
        assertEquals(PcmSpan(6, 4), pcm.span(4, -1, out, 2))
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 0f, 1f, 1f, 2f, 2f, 3f, 3f, 4f, 4f), out.copyOf(12), 0f)
        // The next, at frame 7: three new frames before the window, ending at frame 7.
        assertEquals(PcmSpan(5, 3), pcm.span(7, 4, out, 2))
        assertArrayEquals(floatArrayOf(3f, 3f, 4f, 4f, 5f, 5f, 6f, 6f, 7f, 7f), out.copyOf(10), 0f)
        // A long gap keeps only what the buffer holds; the same moment again has nothing new.
        assertEquals(PcmSpan(8, 6), pcm.span(10, 1, out, 2))
        assertEquals(PcmSpan(2, 0), pcm.span(10, 10, out, 2))
        // A window wider than the buffer still fills the buffer, with nothing counted new.
        assertEquals(PcmSpan(8, 0), pcm.span(10, 9, out, 20))
    }
}
