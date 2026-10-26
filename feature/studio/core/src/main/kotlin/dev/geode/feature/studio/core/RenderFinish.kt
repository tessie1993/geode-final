package dev.geode.feature.studio.core

import dev.geode.feature.studio.api.NewVideo
import dev.geode.feature.studio.api.RenderExtras
import dev.geode.feature.studio.api.RenderLength
import dev.geode.feature.studio.api.RenderTrack
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
 * first where they meet a line of the lyrics.
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
        val cards = cardsOf(track, extras, lengthMs)
        val captions = if (extras.captions) captionsOf(lyrics, track, length, lengthMs, cards) else emptyList()
        if (cards.isEmpty() && captions.isEmpty()) return null
        val media = Lane(LaneId("lane-1"), LaneKind.Media, MEDIA_LANE, listOf(videoClip(video)))
        val lanes =
            listOfNotNull(
                media,
                Lane(CAPTIONS, LaneKind.Text, CAPTIONS_LANE, captions).takeIf { captions.isNotEmpty() },
                Lane(LaneId("lane-3"), LaneKind.Text, CARDS_LANE, cards).takeIf { cards.isNotEmpty() },
            )
        return EditorProject(Timeline(lanes, lengthMs))
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

    /** The title card at the start and the end card at the end, each as long as fits; the end card never covers the title. */
    private fun cardsOf(
        track: RenderTrack,
        extras: RenderExtras,
        lengthMs: Long,
    ): List<Clip> {
        val text = cardText(track)
        val titleEnd = if (extras.titleCard) minOf(CARD_MS, lengthMs) else 0L
        val endStart = maxOf(lengthMs - CARD_MS, titleEnd)
        return listOfNotNull(
            Clip(ClipId("card-title"), ClipContent.Text(text), 0L, titleEnd).takeIf { extras.titleCard },
            Clip(
                ClipId("card-end"),
                ClipContent.Text(text),
                endStart,
                lengthMs - endStart,
            ).takeIf { extras.endCard && endStart < lengthMs },
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
     * The lyrics as captions in the video's own time: shifted by a section's start, repeated with the
     * song for N minutes, kept within the video and out of the cards.
     */
    private fun captionsOf(
        lyrics: List<SubtitleCue>,
        track: RenderTrack,
        length: RenderLength,
        lengthMs: Long,
        cards: List<Clip>,
    ): List<Clip> {
        val offset = (length as? RenderLength.Section)?.start?.value ?: 0L
        val song = track.length.value
        val plays = if (length is RenderLength.Minutes && song > 0) (lengthMs + song - 1) / song else 1L
        val placed =
            (0 until plays).flatMap { play ->
                val shift = play * song - offset
                lyrics.map { SubtitleCue(it.startMs + shift, it.endMs + shift, it.text) }
            }
        return placed
            .mapNotNull { fitted(it, lengthMs, cards) }
            .mapIndexed { index, cue ->
                Clip(
                    ClipId("caption-${index + 1}"),
                    ClipContent.Text(cue.text),
                    cue.startMs,
                    cue.endMs - cue.startMs,
                )
            }
    }

    /** [cue] within the video and clear of the cards, or null when too little of it is left. */
    private fun fitted(
        cue: SubtitleCue,
        lengthMs: Long,
        cards: List<Clip>,
    ): SubtitleCue? {
        var start = cue.startMs.coerceAtLeast(0L)
        var end = cue.endMs.coerceAtMost(lengthMs)
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
