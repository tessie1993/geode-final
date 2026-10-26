package dev.geode.studio.media

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The header of an uncompressed 16-bit AIFF file (#326), the form a continuous video's joined sound is kept
 * in while the video is made: the Render's analysis, its sound for the scenes and its encoder all read AIFF
 * directly ([dev.geode.audio.AiffPcm]), and an AIFF counts its sound in frames, not bytes, so it holds more
 * than a day of sound where a WAV file stops at four gigabytes.
 */
internal object AiffHeader {
    const val SIZE: Int = 54
    const val BITS: Int = 16

    private const val COMM_SIZE = 18
    private const val SSND_HEADER = 8
    private const val FORM_FIXED = 4 + 8 + COMM_SIZE + 8 + SSND_HEADER
    private const val EXPONENT_BIAS = 16_383
    private const val LONG_BITS = 63
    private const val MAX_FRAMES = 0xFFFF_FFFFL

    /** The longest sound the header can count, in frames. */
    val maxFrames: Long get() = MAX_FRAMES

    /** The header for [frames] frames of [channels] channels at [sampleRate] Hz, sizes held to what it can say. */
    fun bytes(
        channels: Int,
        frames: Long,
        sampleRate: Int,
    ): ByteArray {
        val counted = frames.coerceIn(0L, MAX_FRAMES)
        val data = counted * channels * (BITS / 8)
        val buffer = ByteBuffer.allocate(SIZE).order(ByteOrder.BIG_ENDIAN)
        buffer.put("FORM".toByteArray(Charsets.US_ASCII))
        buffer.putInt(clamped(FORM_FIXED + data))
        buffer.put("AIFF".toByteArray(Charsets.US_ASCII))
        buffer.put("COMM".toByteArray(Charsets.US_ASCII))
        buffer.putInt(COMM_SIZE)
        buffer.putShort(channels.toShort())
        buffer.putInt(counted.toInt())
        buffer.putShort(BITS.toShort())
        buffer.put(extended(sampleRate))
        buffer.put("SSND".toByteArray(Charsets.US_ASCII))
        buffer.putInt(clamped(SSND_HEADER + data))
        buffer.putInt(0)
        buffer.putInt(0)
        return buffer.array()
    }

    /** [rate] as the 80-bit extended float AIFF writes a sample rate in; a rate under 1 is written as 0. */
    fun extended(rate: Int): ByteArray {
        val buffer = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
        if (rate <= 0) return buffer.array()
        val power = LONG_BITS - java.lang.Long.numberOfLeadingZeros(rate.toLong())
        buffer.putShort((EXPONENT_BIAS + power).toShort())
        buffer.putLong(rate.toLong() shl (LONG_BITS - power))
        return buffer.array()
    }

    /** A chunk size, which AIFF keeps in 32 bits: a longer sound reads on to the end of the file. */
    private fun clamped(size: Long): Int = size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
