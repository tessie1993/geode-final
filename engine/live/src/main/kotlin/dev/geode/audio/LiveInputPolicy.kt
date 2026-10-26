package dev.geode.audio

/**
 * The choices behind Live input that do not need a device (#297): the rates the microphone is asked
 * for, how often a failed read is retried, and the gain on the window the Visual's scenes draw.
 */
object LiveInputPolicy {
    /** The rates asked for, in order: 48 kHz first, the rate the analysis and the output run at. */
    val rates: IntArray = intArrayOf(48_000, 44_100, 22_050)

    /** Frames per read: about 5 ms at 48 kHz, so nearly every Visual frame has new audio. */
    const val READ_FRAMES = 256

    /** The retries after a failed read before Live input counts as lost for good. */
    const val MAX_RETRIES = 6

    private const val FIRST_RETRY_MS = 100L
    private const val LONGEST_RETRY_MS = 1_000L

    /**
     * How long to wait before retry [attempt] (0 for the first) after a read failed: 100 ms, doubling,
     * at most a second; null once [MAX_RETRIES] have been spent.
     */
    fun retryDelayMs(attempt: Int): Long? {
        if (attempt < 0 || attempt >= MAX_RETRIES) return null
        return (FIRST_RETRY_MS shl attempt).coerceAtMost(LONGEST_RETRY_MS)
    }

    /**
     * The gain on the microphone's window for the scenes (MilkDrop's waveforms), never on what the
     * analysis hears: a room is much quieter than a mastered track, and the UNPROCESSED source is about
     * 18 dB quieter again than VOICE_RECOGNITION.
     */
    fun windowGain(unprocessed: Boolean): Float = if (unprocessed) UNPROCESSED_GAIN else VOICE_GAIN

    /** A room heard through the voice path, raised 6 dB toward a mastered track's level. */
    const val VOICE_GAIN = 2f

    /** The UNPROCESSED source, 18 dB under the voice path: 6 dB + 18 dB. */
    const val UNPROCESSED_GAIN = 16f
}
