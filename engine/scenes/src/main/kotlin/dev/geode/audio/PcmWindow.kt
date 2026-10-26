package dev.geode.audio

/**
 * The last [capacityFrames] stereo frames of a track decoded in order, and the window of them that ends at
 * any frame still held: what a Render hands the scenes for each video frame (#342). Mono is heard in both
 * ears; past two channels only the first two are kept. A frame before the track's start, or not decoded
 * yet, is silence.
 */
class PcmWindow(
    private val capacityFrames: Int,
) {
    init {
        require(capacityFrames > 0) { "capacityFrames must be positive, was $capacityFrames" }
    }

    private val ring = FloatArray(capacityFrames * STEREO)

    /** How many frames have been written since the track's start. */
    var written: Long = 0L
        private set

    /** Appends [frames] frames of [interleaved] sound with [channels] channels. */
    fun write(
        interleaved: FloatArray,
        frames: Int,
        channels: Int,
    ) {
        if (channels <= 0) return
        val count = minOf(frames, interleaved.size / channels)
        for (i in 0 until count) {
            val left = interleaved[i * channels]
            val right = if (channels > 1) interleaved[i * channels + 1] else left
            val slot = (written % capacityFrames).toInt() * STEREO
            ring[slot] = left
            ring[slot + 1] = right
            written++
        }
    }

    /**
     * Fills [out] with the [frames] stereo frames that end just before frame [end], interleaved; frames
     * not held (before the start, overwritten, or not written yet) are silence. Returns [frames].
     */
    fun window(
        end: Long,
        out: FloatArray,
        frames: Int,
    ): Int {
        val count = minOf(frames, out.size / STEREO)
        val oldest = written - capacityFrames
        for (i in 0 until count) {
            val frame = end - count + i
            val held = frame >= 0 && frame >= oldest && frame < written
            val slot = if (held) (frame % capacityFrames).toInt() * STEREO else 0
            out[i * STEREO] = if (held) ring[slot] else 0f
            out[i * STEREO + 1] = if (held) ring[slot + 1] else 0f
        }
        return count
    }

    private companion object {
        const val STEREO = 2
    }
}
