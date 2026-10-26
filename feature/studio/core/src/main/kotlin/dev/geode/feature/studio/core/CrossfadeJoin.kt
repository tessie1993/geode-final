package dev.geode.feature.studio.core

import dev.geode.feature.studio.api.FadeCurve
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * One continuous video's sound (#326): its tracks, decoded one after another, joined as the Playback
 * settings join them. With a crossfade of [fadeFrames], each track fades in over the end of the one before
 * it as that one fades out, along [curve], the way Playback's mixer crosses them; with none they follow one
 * another gaplessly, or parted by [gapFrames] of silence when Playback would leave a gap. A crossfade never takes more than half of either track (see
 * [dev.geode.feature.studio.api.Chapters.overlap]), so no track is ever heard with two others.
 *
 * The sound streams through: the newest [fadeFrames] of the track being written are held back until it is
 * known whether they end it, and everything older goes to [out], [channels] interleaved, in blocks of up to
 * [blockSize] frames. [out] is handed the same array each time and must copy what it keeps. Where each track
 * started in the joined sound, and how long it overlapped the one before, are kept in [starts] and
 * [overlaps]. Not thread-safe.
 */
class CrossfadeJoin(
    private val fadeFrames: Int,
    private val channels: Int,
    private val curve: FadeCurve,
    blockSize: Int = BLOCK_FRAMES,
    private val gapFrames: Int = 0,
    private val out: (samples: FloatArray, frames: Int) -> Unit,
) {
    private val pending = FloatArray(blockSize.coerceAtLeast(1) * channels)
    private var pendingFrames = 0

    // The newest frames of the track being written, a ring of up to fadeFrames.
    private val held = FloatArray(fadeFrames.coerceAtLeast(0) * channels)
    private var heldFirst = 0
    private var heldCount = 0

    // The end of the track before, fading out over the start of this one: overlap frames from tailFirst.
    private val tail = FloatArray(held.size)
    private var tailFirst = 0
    private var overlap = 0
    private var tailDone = 0

    private var started = false
    private var trackFrames = 0L
    private val frame = FloatArray(channels)
    private val silence = FloatArray(channels)

    private val trackStarts = ArrayList<Long>()
    private val trackOverlaps = ArrayList<Long>()

    /** Where each track started in the joined sound, in frames, in order. */
    val starts: List<Long> get() = trackStarts

    /** How many frames each track sounded together with the one before it; none for the first. */
    val overlaps: List<Long> get() = trackOverlaps

    /** How many frames of joined sound have gone out so far, or are on their way in the current block. */
    var written: Long = 0L
        private set

    /**
     * Ends the track before, if any, and starts the next. [expectedFrames] is how long it is said to be, so
     * that a crossfade takes no more than half of it; 0 when that is not known.
     */
    fun startTrack(expectedFrames: Long = 0L) {
        val tailLength = if (started) endTrack() else 0
        val shared = if (expectedFrames > 0) minOf(tailLength.toLong(), expectedFrames / 2).toInt() else tailLength
        // The part of the tail that is not crossfaded plays out as it was.
        for (i in 0 until tailLength - shared) emit(tail, i * channels)
        // Tracks that do not overlap are parted by the gap, if any; nothing comes before the first.
        if (started && shared == 0) repeat(gapFrames) { emit(silence, 0) }
        tailFirst = tailLength - shared
        overlap = shared
        tailDone = 0
        trackFrames = 0
        started = true
        trackStarts += written
        trackOverlaps += shared.toLong()
    }

    /** The next [frames] of the current track, [channels] interleaved; nothing before a track is started. */
    fun write(
        samples: FloatArray,
        frames: Int,
    ) {
        if (!started) return
        for (f in 0 until frames) {
            val base = f * channels
            if (tailDone < overlap) {
                mixed(samples, base)
                hold(frame, 0)
            } else {
                hold(samples, base)
            }
            trackFrames++
        }
    }

    /** Ends the last track: what was held back of it goes out unfaded, and the last block is handed over. */
    fun finish() {
        fadeTailAlone()
        while (heldCount > 0) release()
        started = false
        if (pendingFrames > 0) out(pending, pendingFrames)
        pendingFrames = 0
    }

    /**
     * Ends the current track: the tail of the track before fades out alone if this one ended inside it, and
     * this one's own tail, no more than half of it, is kept to fade into the next. Returns its length.
     */
    private fun endTrack(): Int {
        fadeTailAlone()
        val keep = minOf(heldCount.toLong(), trackFrames / 2).toInt()
        while (heldCount > keep) release()
        for (i in 0 until keep) {
            System.arraycopy(held, ringOffset(i), tail, i * channels, channels)
        }
        heldCount = 0
        heldFirst = 0
        return keep
    }

    /** The rest of the track before's tail, faded out over silence: the current track ended inside it. */
    private fun fadeTailAlone() {
        while (tailDone < overlap) {
            mixed(silence, 0)
            hold(frame, 0)
            trackFrames++
        }
    }

    /** Into [frame]: the next frame of the tail fading out, with the frame of [samples] at [base] fading in. */
    private fun mixed(
        samples: FloatArray,
        base: Int,
    ) {
        val t = (tailDone + HALF) / overlap
        val gone = fadeOut(t, curve)
        val coming = fadeIn(t, curve)
        val from = (tailFirst + tailDone) * channels
        for (c in 0 until channels) frame[c] = tail[from + c] * gone + samples[base + c] * coming
        tailDone++
    }

    /** Holds back the frame of [samples] at [base], letting the oldest held frame go once the ring is full. */
    private fun hold(
        samples: FloatArray,
        base: Int,
    ) {
        if (held.isEmpty()) return emit(samples, base)
        if (heldCount == fadeFrames) release()
        System.arraycopy(samples, base, held, ringOffset(heldCount), channels)
        heldCount++
    }

    /** Lets the oldest held frame go out. */
    private fun release() {
        emit(held, heldFirst * channels)
        heldFirst = (heldFirst + 1) % fadeFrames
        heldCount--
    }

    private fun ringOffset(index: Int): Int = (heldFirst + index) % fadeFrames * channels

    private fun emit(
        samples: FloatArray,
        base: Int,
    ) {
        System.arraycopy(samples, base, pending, pendingFrames * channels, channels)
        pendingFrames++
        written++
        if (pendingFrames * channels == pending.size) {
            out(pending, pendingFrames)
            pendingFrames = 0
        }
    }

    companion object {
        /** How many frames go to the sink at a time. */
        const val BLOCK_FRAMES: Int = 4_096

        private const val HALF = 0.5f
        private const val HALF_PI = (PI / 2).toFloat()
        private const val SMOOTH_A = 3f
        private const val SMOOTH_B = 2f

        /** The level of the track coming in, [t] (0..1) of the way through the crossfade, as Playback's mixer sets it. */
        fun fadeIn(
            t: Float,
            curve: FadeCurve,
        ): Float =
            when (curve) {
                FadeCurve.LINEAR -> t
                FadeCurve.EQUAL_POWER -> sin(t * HALF_PI)
                FadeCurve.SMOOTH -> t * t * (SMOOTH_A - SMOOTH_B * t)
            }

        /** The level of the track going out, [t] (0..1) of the way through the crossfade. */
        fun fadeOut(
            t: Float,
            curve: FadeCurve,
        ): Float =
            when (curve) {
                FadeCurve.EQUAL_POWER -> cos(t * HALF_PI)
                FadeCurve.LINEAR, FadeCurve.SMOOTH -> 1f - fadeIn(t, curve)
            }
    }
}
