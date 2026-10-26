package dev.geode.feature.studio.core

import dev.geode.feature.studio.api.Chapters
import dev.geode.feature.studio.api.NewVideo
import dev.geode.feature.studio.api.RenderExtras
import dev.geode.feature.studio.api.RenderLength
import dev.geode.feature.studio.api.RenderTrack
import dev.geode.feature.studio.api.TrackJoin
import dev.geode.feature.studio.api.edit.Clip
import dev.geode.feature.studio.api.edit.ClipContent
import dev.geode.feature.studio.api.edit.ClipId
import dev.geode.feature.studio.api.edit.EditorProject
import dev.geode.feature.studio.api.edit.Lane
import dev.geode.feature.studio.api.edit.LaneId
import dev.geode.feature.studio.api.edit.LaneKind
import dev.geode.feature.studio.api.edit.SubtitleCue
import dev.geode.feature.studio.api.edit.Timeline

/**
 * A finished Render's extras as an Edit of it (#329): the Render's video on a media lane, the song's
 * synced lyrics as captions at the lines' times, and title and end cards with the song and artist. The
 * Visual itself stays clean: captions and cards belong to Edits, never to the Visual. The cards come
 * first where they meet a line of the lyrics. One continuous video of a playlist (#326) gets them for
 * each track inside the one video: each track's cards at its own start and end, and its own lyrics timed
 * from where it starts.
 */
object RenderFinish {
    /** How long a title or end card shows. */
    const val CARD_MS: Long = 4_000L

    /** A caption shorter than this once the cards are cut out of it is dropped. */
    const val SHORTEST_CAPTION_MS: Long = 300L

    /**
     * The Edit of [video], a Render of [track] over [length] with [extras], and [lyrics] in the song's own
     * time; null when nothing would be added.
     */
    fun cutOf(
        video: NewVideo,
        track: RenderTrack,
        length: RenderLength,
        extras: RenderExtras,
        lyrics: List<SubtitleCue>,
    ): EditorProject? {
        val lengthMs = video.length.value
        if (lengthMs <= 0) return null
        val whole = Span(0L, lengthMs)
        val cards = cardsOf(track, extras, whole, "")
        val captions = if (extras.captions) captionsOf(placed(lyrics, track, length, lengthMs), whole, cards) else emptyList()
        return projectOf(video, numbered(captions), cards)
    }

    /**
     * The Edit of [video], one continuous video of [tracks] joined by [join] (#326), with [extras] for every
     * track and each track's [lyrics] (in order, in its own time); null when nothing would be added. Each
     * track runs from where its chapter starts until the next one does; a track the video does not reach
     * gets nothing.
     */
    fun playlistCutOf(
        video: NewVideo,
        tracks: List<RenderTrack>,
        join: TrackJoin,
        extras: RenderExtras,
        lyrics: List<List<SubtitleCue>>,
    ): EditorProject? {
        val lengthMs = video.length.value
        if (lengthMs <= 0 || tracks.isEmpty()) return null
        val starts = Chapters.of(tracks, join.crossfade, join.gap).map { it.startMs }.ifEmpty { listOf(0L) }
        val cards = ArrayList<Clip>()
        val captions = ArrayList<SubtitleCue>()
        for ((index, track) in tracks.withIndex()) {
            val span = Span(starts[index], (starts.getOrNull(index + 1) ?: lengthMs).coerceAtMost(lengthMs))
            if (span.end <= span.start) continue
            val own = cardsOf(track, extras, span, "-${index + 1}")
            cards += own
            if (extras.captions) {
                val shifted = lyrics.getOrNull(index).orEmpty().map { SubtitleCue(it.startMs + span.start, it.endMs + span.start, it.text) }
                captions += captionsOf(shifted, span, own)
            }
        }
        return projectOf(video, numbered(captions), cards)
    }

    /** The part of the video one track has: from [start] to [end]. */
    private class Span(
        val start: Long,
        val end: Long,
    )

    /** The Edit of [video] with [captions] and [cards] on lanes of their own; null when there are neither. */
    private fun projectOf(
        video: NewVideo,
        captions: List<Clip>,
        cards: List<Clip>,
    ): EditorProject? {
        if (cards.isEmpty() && captions.isEmpty()) return null
        val media = Lane(LaneId("lane-1"), LaneKind.Media, MEDIA_LANE, listOf(videoClip(video)))
        val lanes =
            listOfNotNull(
                media,
                Lane(CAPTIONS, LaneKind.Text, CAPTIONS_LANE, captions).takeIf { captions.isNotEmpty() },
                Lane(LaneId("lane-3"), LaneKind.Text, CARDS_LANE, cards).takeIf { cards.isNotEmpty() },
            )
        return EditorProject(Timeline(lanes, video.length.value))
    }

    /** What the finished Edit is called: the Render's name and what was added to it. */
    fun nameOf(
        title: String,
        extras: RenderExtras,
        hasCaptions: Boolean,
    ): String {
        val cards = extras.titleCard || extras.endCard
        val added =
            when {
                hasCaptions && cards -> "lyrics and cards"
                hasCaptions -> "lyrics"
                else -> "cards"
            }
        return "$title with $added"
    }

    private fun videoClip(video: NewVideo): Clip =
        Clip(ClipId("clip-video"), ClipContent.Video(video.file), 0L, video.length.value, sourceDurationMs = video.length.value)

    /**
     * The title card at the start of [span] and the end card at its end, each as long as fits; the end card
     * never covers the title. Their ids end in [suffix].
     */
    private fun cardsOf(
        track: RenderTrack,
        extras: RenderExtras,
        span: Span,
        suffix: String,
    ): List<Clip> {
        val text = cardText(track)
        val titleEnd = if (extras.titleCard) minOf(span.start + CARD_MS, span.end) else span.start
        val endStart = maxOf(span.end - CARD_MS, titleEnd)
        return listOfNotNull(
            Clip(ClipId("card-title$suffix"), ClipContent.Text(text), span.start, titleEnd - span.start).takeIf { extras.titleCard },
            Clip(
                ClipId("card-end$suffix"),
                ClipContent.Text(text),
                endStart,
                span.end - endStart,
            ).takeIf { extras.endCard && endStart < span.end },
        )
    }

    /** Whether [project], made by [cutOf], has the lyrics as captions. */
    fun hasCaptions(project: EditorProject): Boolean = project.timeline.lane(CAPTIONS) != null

    /** "Song — Artist", or the song alone. */
    fun cardText(track: RenderTrack): String {
        val artist = track.artist.trim()
        return if (artist.isEmpty()) track.title.trim() else "${track.title.trim()} — $artist"
    }

    /**
     * The lyrics in the video's own time: shifted by a section's start, and repeated with the song for N
     * minutes.
     */
    private fun placed(
        lyrics: List<SubtitleCue>,
        track: RenderTrack,
        length: RenderLength,
        lengthMs: Long,
    ): List<SubtitleCue> {
        val offset = (length as? RenderLength.Section)?.start?.value ?: 0L
        val song = track.length.value
        val plays = if (length is RenderLength.Minutes && song > 0) (lengthMs + song - 1) / song else 1L
        return (0 until plays).flatMap { play ->
            val shift = play * song - offset
            lyrics.map { SubtitleCue(it.startMs + shift, it.endMs + shift, it.text) }
        }
    }

    /** [cues], already in the video's time, kept within [span] and out of the [cards]. */
    private fun captionsOf(
        cues: List<SubtitleCue>,
        span: Span,
        cards: List<Clip>,
    ): List<SubtitleCue> = cues.mapNotNull { fitted(it, span, cards) }

    /** [cues] as caption clips, numbered in order. */
    private fun numbered(cues: List<SubtitleCue>): List<Clip> =
        cues.mapIndexed { index, cue ->
            Clip(ClipId("caption-${index + 1}"), ClipContent.Text(cue.text), cue.startMs, cue.endMs - cue.startMs)
        }

    /** [cue] within [span] and clear of the cards, or null when too little of it is left. */
    private fun fitted(
        cue: SubtitleCue,
        span: Span,
        cards: List<Clip>,
    ): SubtitleCue? {
        var start = cue.startMs.coerceAtLeast(span.start)
        var end = cue.endMs.coerceAtMost(span.end)
        for (card in cards) {
            if (card.startMs <= start) start = maxOf(start, card.endMs) else end = minOf(end, card.startMs)
        }
        return SubtitleCue(start, end, cue.text).takeIf { end - start >= SHORTEST_CAPTION_MS }
    }

    private val CAPTIONS = LaneId("lane-2")
    private const val MEDIA_LANE = "Render"
    private const val CAPTIONS_LANE = "Lyrics"
    private const val CARDS_LANE = "Titles"
}
