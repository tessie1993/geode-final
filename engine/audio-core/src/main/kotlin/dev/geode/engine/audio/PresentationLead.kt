package dev.geode.engine.audio

import kotlin.math.abs

/**
 * How far a player's tap runs ahead of the speaker, worked out from two media times the player's
 * audio sink knows on its own thread: the end of the audio the tap has seen, and the position being
 * heard now. ExoPlayer's tap sits before about half a second of AudioTrack buffer, so without this
 * the Visual would draw the music half a second before it is heard.
 *
 * [offer] takes every measurement and says when one is worth sending to the feed: when it moved by
 * more than [stepUs], or [refreshMs] after the last one sent, so the native call is not made for
 * every position query. One instance per sink, on the sink's thread.
 */
class PresentationLead(
    private val stepUs: Long = DEFAULT_STEP_US,
    private val refreshMs: Long = DEFAULT_REFRESH_MS,
) {
    private var sentUs = UNSENT
    private var sentAtMs = 0L

    /** The lead to send now, or null when the last one sent still holds. */
    fun offer(
        tappedEndUs: Long,
        heardUs: Long,
        nowMs: Long,
    ): Long? {
        val lead = of(tappedEndUs, heardUs)
        val moved = sentUs == UNSENT || abs(lead - sentUs) >= stepUs
        if (!moved && nowMs - sentAtMs < refreshMs) return null
        sentUs = lead
        sentAtMs = nowMs
        return lead
    }

    /** Forget what was sent: the next measurement goes out whatever it is (after a flush). */
    fun reset() {
        sentUs = UNSENT
    }

    companion object {
        /** The furthest ahead a tap is believed: a longer gap is a measurement taken mid-seek. */
        const val MAX_LEAD_US: Long = 2_000_000L
        const val DEFAULT_STEP_US: Long = 2_000L
        const val DEFAULT_REFRESH_MS: Long = 250L
        private const val UNSENT = Long.MIN_VALUE

        /** The tap's lead over the speaker, in microseconds of media time, within 0..[MAX_LEAD_US]. */
        fun of(
            tappedEndUs: Long,
            heardUs: Long,
        ): Long = (tappedEndUs - heardUs).coerceIn(0L, MAX_LEAD_US)
    }
}
