package dev.geode.studio.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

/** The pure parts of a continuous video's joined sound (#326): the change of rate, and the AIFF header. */
class PlaylistSoundTest {
    private fun resampled(
        from: Int,
        to: Int,
        channels: Int,
        vararg blocks: FloatArray,
    ): FloatArray {
        val heard = ArrayList<Float>()
        val resampler = LinearResampler(from, to, channels)
        blocks.forEach { block ->
            resampler.process(block, block.size / channels) { out, frames -> heard.addAll(out.take(frames * channels)) }
        }
        return heard.toFloatArray()
    }

    @Test
    fun `halving the rate keeps every other frame, across blocks`() {
        val heard = resampled(2, 1, 1, floatArrayOf(0f, 1f, 2f, 3f, 4f, 5f), floatArrayOf(6f, 7f, 8f, 9f))
        assertArrayEquals(floatArrayOf(0f, 2f, 4f, 6f, 8f), heard, 1e-6f)
    }

    @Test
    fun `doubling the rate reads between frames, across blocks`() {
        val heard = resampled(1, 2, 1, floatArrayOf(0f, 1f, 2f), floatArrayOf(3f, 4f))
        assertArrayEquals(floatArrayOf(0f, 0.5f, 1f, 1.5f, 2f, 2.5f, 3f, 3.5f), heard, 1e-6f)
    }

    @Test
    fun `every channel is read on its own`() {
        val heard = resampled(1, 2, 2, floatArrayOf(0f, 10f, 2f, 30f))
        assertArrayEquals(floatArrayOf(0f, 10f, 1f, 20f), heard, 1e-6f)
    }

    @Test
    fun `an empty block makes nothing`() {
        assertArrayEquals(floatArrayOf(), resampled(44_100, 48_000, 2, floatArrayOf()), 0f)
    }

    @Test
    fun `the header says AIFF, its channels, frames, bits and rate`() {
        val header = ByteBuffer.wrap(AiffHeader.bytes(channels = 2, frames = 1_000L, sampleRate = 44_100))
        assertEquals(AiffHeader.SIZE, header.capacity())
        assertEquals("FORM", ascii(header, 0))
        assertEquals(46 + 4_000, header.getInt(4))
        assertEquals("AIFF", ascii(header, 8))
        assertEquals("COMM", ascii(header, 12))
        assertEquals(18, header.getInt(16))
        assertEquals(2, header.getShort(20).toInt())
        assertEquals(1_000, header.getInt(22))
        assertEquals(16, header.getShort(26).toInt())
        assertEquals("SSND", ascii(header, 38))
        assertEquals(8 + 4_000, header.getInt(42))
        assertEquals(0, header.getInt(46))
        assertEquals(0, header.getInt(50))
    }

    @Test
    fun `the rate is an 80-bit extended float, as AIFF readers decode it`() {
        for (rate in listOf(8_000, 22_050, 44_100, 48_000, 96_000, 1)) {
            assertEquals(rate, decodeExtended(AiffHeader.extended(rate)))
        }
        assertArrayEquals(ByteArray(10), AiffHeader.extended(0))
    }

    @Test
    fun `a sound too long for the chunk sizes still counts its frames`() {
        val header = ByteBuffer.wrap(AiffHeader.bytes(channels = 2, frames = 1_000_000_000L, sampleRate = 48_000))
        assertEquals(Int.MAX_VALUE, header.getInt(4))
        assertEquals(1_000_000_000, header.getInt(22))
        assertEquals(Int.MAX_VALUE, header.getInt(42))
        val most = ByteBuffer.wrap(AiffHeader.bytes(channels = 2, frames = Long.MAX_VALUE, sampleRate = 48_000))
        assertEquals(AiffHeader.maxFrames, most.getInt(22).toLong() and 0xFFFF_FFFFL)
    }

    private fun ascii(
        buffer: ByteBuffer,
        at: Int,
    ): String = String(ByteArray(4) { buffer.get(at + it) }, Charsets.US_ASCII)

    /** As [dev.geode.audio.AiffPcm] reads it. */
    private fun decodeExtended(bytes: ByteArray): Int {
        val buffer = ByteBuffer.wrap(bytes)
        val exponent = buffer.getShort().toInt() and 0x7FFF
        val high = buffer.getInt().toLong() and 0xFFFFFFFFL
        val shift = 63 - (exponent - 16383)
        return ((high shl 32) ushr shift).toInt()
    }
}
