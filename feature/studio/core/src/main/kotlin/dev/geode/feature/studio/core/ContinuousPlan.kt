package dev.geode.feature.studio.core

import dev.geode.engine.types.Millis
import dev.geode.feature.studio.api.Chapters
import dev.geode.feature.studio.api.RenderFormat
import dev.geode.feature.studio.api.RenderTrack
import dev.geode.feature.studio.api.TrackJoin

/**
 * The pure decisions behind one continuous video of a playlist (#326, as the owner decided on #273): when
 * the Visual moves on to each track's own Customization, how long the video runs, and how much free storage
 * making it takes. The sound's join is [CrossfadeJoin]'s; the chapters are [Chapters]'.
 */
object ContinuousPlan {
    /** The engine's own wait for a downbeat before a change of Style (#309): a bar and a beat, at most this. */
    const val MAX_WAIT_MS: Long = 3_000L
    private const val BEATS_WAITED = 5L
    private const val MILLIS_PER_MINUTE = 60_000.0

    /** The joined sound while it is made: 16-bit stereo at 48 kHz, a second's worth. */
    const val JOINED_BYTES_PER_SECOND: Long = 192_000L

    /** The video's sound, 192 kbit/s of AAC, a second's worth; it is written twice, once on its own and once muxed. */
    const val SOUND_BYTES_PER_SECOND: Long = 24_000L

    /** Room kept free beyond what the video takes, so the phone is never left full. */
    const val HEADROOM_BYTES: Long = 64L * 1024L * 1024L

    private const val BITS_PER_BYTE = 8L
    private const val MILLIS_PER_SECOND = 1_000L

    /**
     * How long the Visual waits at most for a downbeat at the heard [bpm], as the engine's own gate does: a
     * bar and a beat, never more than [MAX_WAIT_MS]. Null when no tempo is heard: then it does not wait.
     */
    fun maxWait(bpm: Float): Long? {
        if (!(bpm > 0f) || !bpm.isFinite()) return null
        return minOf((BEATS_WAITED * MILLIS_PER_MINUTE / bpm).toLong(), MAX_WAIT_MS)
    }

    /**
     * When the Visual moves to the next track's Customization once that track has come in at [fromMs]: the
     * first of the [downbeats] (in order, in the video's time) at or after it, if one comes within
     * [maxWait]; otherwise once that wait is over; and at once when no tempo is heard.
     */
    fun changeAt(
        fromMs: Long,
        downbeats: List<Long>,
        bpm: Float,
    ): Long {
        val wait = maxWait(bpm) ?: return fromMs
        return minOf(downbeats.firstOrNull { it >= fromMs } ?: Long.MAX_VALUE, fromMs + wait)
    }

    /**
     * When the Visual moves to each track after the first, which starts at [starts] and sounds together with
     * the one before it for [overlaps] (both in the joined sound's milliseconds, in order): at the first
     * downbeat once the two have crossed over, halfway through their crossfade (see [changeAt]).
     */
    fun changesAt(
        starts: List<Long>,
        overlaps: List<Long>,
        downbeats: List<Long>,
        bpm: Float,
    ): List<Long> =
        starts.indices.drop(1).map { track ->
            val crossed = starts[track] + (overlaps.getOrNull(track) ?: 0L) / 2
            changeAt(crossed, downbeats, bpm)
        }

    /** How long a video of [tracks] joined by [join] runs: the joined playlist, or the one Track. */
    fun lengthOf(
        tracks: List<RenderTrack>,
        join: TrackJoin,
    ): Millis {
        val chapters = Chapters.of(tracks, join.crossfade, join.gap)
        return chapters.lastOrNull()?.let { Millis(it.endMs) } ?: tracks.firstOrNull()?.length ?: Millis.ZERO
    }

    /**
     * The free storage a video of [length] at [format] takes while it is made: the video at its bit rate,
     * its sound twice, the joined sound it is drawn from, and [HEADROOM_BYTES]. A continuous video has no
     * other limit (#273).
     */
    fun bytesNeeded(
        format: RenderFormat,
        length: Millis,
    ): Long {
        val millis = length.value.coerceAtLeast(0L)
        val video = RenderPlan.bitRateOf(format).toLong() * millis / MILLIS_PER_SECOND / BITS_PER_BYTE
        val sound = 2 * SOUND_BYTES_PER_SECOND * millis / MILLIS_PER_SECOND
        val joined = JOINED_BYTES_PER_SECOND * millis / MILLIS_PER_SECOND
        return video + sound + joined + HEADROOM_BYTES
    }
}
