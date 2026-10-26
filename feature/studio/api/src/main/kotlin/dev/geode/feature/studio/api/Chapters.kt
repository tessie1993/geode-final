package dev.geode.feature.studio.api

import dev.geode.engine.types.Millis
import java.util.Locale

/** One chapter of a playlist video: where it starts, how long it runs, and what it is called. */
data class TrackChapter(
    val startMs: Long,
    val durationMs: Long,
    val title: String,
) {
    val endMs: Long get() = startMs + durationMs
}

/**
 * Chapter markers for a playlist Render (#329): one per song, at the moment it starts when the songs play
 * one after another, as a description to paste under a video, WebVTT, and FFmetadata. Geode writes them
 * beside the video, since the phone's muxer cannot put a chapter track inside it. One continuous video
 * (#326) joins its songs with the Playback settings' crossfade: each song starts as the one before it
 * begins to fade out.
 */
object Chapters {
    /**
     * One chapter per track of [tracks] in order, each named after its song and artist; none for a single
     * track. With a [crossfade], each song starts that long before the one before it ends (see [overlap]),
     * and each chapter runs until the next one starts, so the chapters tile the video.
     */
    fun of(
        tracks: List<RenderTrack>,
        crossfade: Millis = Millis.ZERO,
    ): List<TrackChapter> {
        if (tracks.size < 2) return emptyList()
        val lengths = tracks.map { it.length.value.coerceAtLeast(0L) }
        var start = 0L
        return tracks.mapIndexed { index, track ->
            val length = lengths[index]
            val next = lengths.getOrNull(index + 1)
            val shared = if (next == null) 0L else overlap(crossfade, Millis(length), Millis(next)).value
            TrackChapter(start, length - shared, titleOf(track, index)).also { start += length - shared }
        }
    }

    /**
     * How long the song [before] and the song [after] it sound together when a playlist is joined with
     * [crossfade] (#326): the crossfade, but never more than half of either song, so no song is ever heard
     * with two others at once. Nothing for no crossfade, or a song whose length is not known.
     */
    fun overlap(
        crossfade: Millis,
        before: Millis,
        after: Millis,
    ): Millis = Millis(minOf(crossfade.value, before.value / 2, after.value / 2).coerceAtLeast(0L))

    /** `0:00 Song — Artist`, a line per chapter: what a listener pastes into a video description. */
    fun description(chapters: List<TrackChapter>): String =
        chapters.joinToString(separator = "\n", postfix = if (chapters.isEmpty()) "" else "\n") { "${timestamp(it.startMs)} ${it.title}" }

    fun webVtt(chapters: List<TrackChapter>): String =
        buildString {
            append("WEBVTT\n\n")
            chapters.forEachIndexed { index, chapter ->
                append(index + 1).append('\n')
                append(vttTimestamp(chapter.startMs)).append(" --> ").append(vttTimestamp(chapter.endMs)).append('\n')
                append(chapter.title).append("\n\n")
            }
        }

    fun ffmetadata(chapters: List<TrackChapter>): String =
        buildString {
            append(";FFMETADATA1\n")
            chapters.forEach { chapter ->
                append("\n[CHAPTER]\nTIMEBASE=1/1000\n")
                append("START=").append(chapter.startMs).append('\n')
                append("END=").append(chapter.endMs).append('\n')
                append("title=").append(chapter.title.replace('\n', ' ')).append('\n')
            }
        }

    /** `m:ss` under an hour, `h:mm:ss` over: the form description fields read. */
    fun timestamp(ms: Long): String {
        val total = ms.coerceAtLeast(0L) / MILLIS_PER_SECOND
        val hours = total / SECONDS_PER_HOUR
        val minutes = total % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
        val seconds = total % SECONDS_PER_MINUTE
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }
    }

    /** `hh:mm:ss.mmm`, as WebVTT writes times. */
    fun vttTimestamp(ms: Long): String {
        val clamped = ms.coerceAtLeast(0L)
        val total = clamped / MILLIS_PER_SECOND
        return String.format(
            Locale.ROOT,
            "%02d:%02d:%02d.%03d",
            total / SECONDS_PER_HOUR,
            total % SECONDS_PER_HOUR / SECONDS_PER_MINUTE,
            total % SECONDS_PER_MINUTE,
            clamped % MILLIS_PER_SECOND,
        )
    }

    /** "Song — Artist", the song alone without an artist, or "Track N" for a song with no title. */
    private fun titleOf(
        track: RenderTrack,
        index: Int,
    ): String {
        val title = track.title.trim().ifEmpty { "Track ${index + 1}" }
        val artist = track.artist.trim()
        return if (artist.isEmpty()) title else "$title — $artist"
    }

    private const val MILLIS_PER_SECOND = 1_000L
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3_600L
}
