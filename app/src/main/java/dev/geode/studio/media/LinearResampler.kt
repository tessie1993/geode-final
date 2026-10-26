package dev.geode.studio.media

import kotlin.math.floor

/**
 * Brings a track's interleaved sound from [from] Hz to [to] Hz as it streams (#326), so the tracks of one
 * continuous video, which may each have their own rate, join at one: each new frame is read between the
 * two nearest frames of the old, carried across the blocks it arrives in. Not thread-safe.
 */
internal class LinearResampler(
    from: Int,
    to: Int,
    private val channels: Int,
) {
    private val step = from.toDouble() / to

    // Where the next frame falls, in frames of the current block; -1 is the last frame of the block before.
    private var at = 0.0
    private val previous = FloatArray(channels)
    private var out = FloatArray(0)

    /** [frames] of [input] at the old rate; hands [sink] what they make at the new rate, which it must copy. */
    fun process(
        input: FloatArray,
        frames: Int,
        sink: (FloatArray, Int) -> Unit,
    ) {
        if (frames <= 0) return
        val most = ((frames + 1) / step).toInt() + 2
        if (out.size < most * channels) out = FloatArray(most * channels)
        var made = 0
        while (true) {
            val index = floor(at).toInt()
            if (index + 1 >= frames) break
            val fraction = (at - index).toFloat()
            for (c in 0 until channels) {
                val a = if (index < 0) previous[c] else input[index * channels + c]
                val b = input[(index + 1) * channels + c]
                out[made * channels + c] = a + (b - a) * fraction
            }
            made++
            at += step
        }
        at -= frames
        System.arraycopy(input, (frames - 1) * channels, previous, 0, channels)
        if (made > 0) sink(out, made)
    }
}
