package dev.geode.audio

import dev.geode.engine.audio.SampleCursor
import dev.geode.engine.audio.SampleRing

/**
 * What a Recording hears (#317): the sound the Visual is driven by, read forward from the session's
 * stereo ring in order and once, as interleaved stereo. While nothing arrives for longer than the
 * player's own lead over the speaker explains (a pause, nothing playing), it gives silence for the time
 * that passed, so the Recording's sound never falls behind its picture.
 *
 * The first [take] marks where the Recording starts and gives nothing. One thread takes. [rateHz] is
 * the rate of what the ring receives now (the player's or the microphone's); a take gives its frames
 * at that rate, which the Recording brings to its own.
 */
class RecordingTap(
    ring: SampleRing,
    private val rateHz: () -> Int,
    private val nowNs: () -> Long = System::nanoTime,
) {
    private val cursor = SampleCursor(ring, startBehindFrames = 0)
    private var started = false
    private var startNs = 0L
    private var delivered = 0L
    private var scratch = FloatArray(0)

    /** The rate of the frames the last [take] gave. */
    var lastRateHz: Int = FALLBACK_RATE_HZ
        private set

    /**
     * Up to half of [out]'s length in stereo frames, interleaved, heard since the last take, or silence for
     * the time nothing was heard; returns the frames written.
     */
    fun take(out: FloatArray): Int {
        val now = nowNs()
        val rate = rateHz().takeIf { it > 0 } ?: lastRateHz
        lastRateHz = rate
        if (!started) {
            started = true
            startNs = now
            // Whatever the ring holds from before the start is not the Recording's.
            cursor.read(scratchFor(out.size))
            return 0
        }
        val capacity = out.size / STEREO
        if (capacity <= 0) return 0
        val read = cursor.read(scratchFor(capacity * STEREO))
        if (read > 0) {
            val channels = cursor.channels
            for (i in 0 until read) {
                val left = scratch[i * channels]
                val right = if (channels > 1) scratch[i * channels + 1] else left
                out[i * STEREO] = left
                out[i * STEREO + 1] = right
            }
            delivered += read
            return read
        }
        // Nothing new. The player writes ahead of the speaker, so a short lull is not silence; only once
        // the Recording has fallen a lead and more behind the clock is the gap filled with silence.
        val expected = (now - startNs) * rate / NANOS_PER_SECOND
        val behind = expected - delivered
        if (behind.toDouble() <= MAX_LEAD_SECONDS * rate) return 0
        val frames = minOf(behind - (KEEP_LEAD_SECONDS * rate).toLong(), capacity.toLong()).toInt()
        if (frames <= 0) return 0
        out.fill(0f, 0, frames * STEREO)
        delivered += frames
        return frames
    }

    private fun scratchFor(size: Int): FloatArray {
        if (scratch.size < size) scratch = FloatArray(size)
        return scratch
    }

    companion object {
        /** The rate silence is counted at until the ring has had one. */
        const val FALLBACK_RATE_HZ = 48_000

        /** How far the sound may run behind the clock before the gap is taken for silence: ExoPlayer's tap leads by about half a second. */
        const val MAX_LEAD_SECONDS = 0.5

        /** How far behind the clock the filled silence stops, so the sound that resumes is not pushed late. */
        const val KEEP_LEAD_SECONDS = 0.25

        private const val STEREO = 2
        private const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
