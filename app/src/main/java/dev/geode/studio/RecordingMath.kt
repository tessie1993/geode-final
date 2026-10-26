package dev.geode.studio

import kotlin.math.roundToInt

/** The sums a Recording of the live Visual makes (#317): its size, its bit rate and its samples. */
internal object RecordingMath {
    /** The frame rate a Recording's encoder is told to expect: the live Visual's highest. */
    const val FPS = 60

    /** A Recording's size shrinks from the screen's in twentieths, down to half, to find one the encoder takes. */
    private const val TWENTIETHS = 20

    private const val SMALLEST_TWENTIETHS = 10

    /** Bits per pixel per frame, in hundredths. */
    private const val BITS_PER_PIXEL_HUNDREDTHS = 12

    private const val MIN_BIT_RATE = 2_000_000L

    private const val MAX_BIT_RATE = 40_000_000L

    /**
     * The size a Recording of the Visual drawn at [width] x [height] is made at: the screen's own
     * when the encoder takes it ([supported]), otherwise the largest step down to half that it does,
     * keeping the shape. Each side is rounded down to the encoder's alignment and to an even number.
     * Null when the encoder takes none.
     */
    fun sizeFor(
        width: Int,
        height: Int,
        alignWidth: Int,
        alignHeight: Int,
        supported: (Int, Int) -> Boolean,
    ): Pair<Int, Int>? {
        if (width <= 0 || height <= 0) return null
        for (twentieths in TWENTIETHS downTo SMALLEST_TWENTIETHS) {
            val w = aligned(width * twentieths / TWENTIETHS, alignWidth)
            val h = aligned(height * twentieths / TWENTIETHS, alignHeight)
            if (w > 0 && h > 0 && supported(w, h)) return w to h
        }
        return null
    }

    /** The bit rate a Recording of [width] x [height] is encoded at. */
    fun bitRateOf(
        width: Int,
        height: Int,
    ): Int = (width.toLong() * height * FPS * BITS_PER_PIXEL_HUNDREDTHS / 100).coerceIn(MIN_BIT_RATE, MAX_BIT_RATE).toInt()

    /** A float sample as 16-bit PCM, clipped at full scale. */
    fun pcm16(sample: Float): Short = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort()

    /** [value] rounded down to a multiple of [alignment] that is also even. */
    private fun aligned(
        value: Int,
        alignment: Int,
    ): Int {
        val step = if (alignment > 1 && alignment % 2 == 0) alignment else 2 * alignment.coerceAtLeast(1)
        return value / step * step
    }
}

/**
 * Stereo PCM brought to the Recording's one rate, [outRate] (#317): the feed's rate changes with the
 * source (another track, Live input), while an AAC track keeps one. Interpolated linearly within each
 * piece; the fraction of a frame left over is carried to the next, so the length stays exact.
 */
internal class RateMatcher(
    private val outRate: Int,
) {
    private var owed = 0.0

    /** The first [frames] stereo frames of [input], at [inRate], as interleaved frames at [outRate]. */
    fun convert(
        input: FloatArray,
        frames: Int,
        inRate: Int,
    ): FloatArray {
        if (frames <= 0) return FloatArray(0)
        if (inRate <= 0 || inRate == outRate) return input.copyOf(frames * 2)
        val exact = frames.toDouble() * outRate / inRate + owed
        val count = exact.toInt()
        owed = exact - count
        val out = FloatArray(count * 2)
        for (i in 0 until count) {
            val at = if (count == 1) 0.0 else i.toDouble() * (frames - 1) / (count - 1)
            val first = at.toInt()
            val next = minOf(first + 1, frames - 1)
            val t = (at - first).toFloat()
            out[i * 2] = input[first * 2] + (input[next * 2] - input[first * 2]) * t
            out[i * 2 + 1] = input[first * 2 + 1] + (input[next * 2 + 1] - input[first * 2 + 1]) * t
        }
        return out
    }
}
