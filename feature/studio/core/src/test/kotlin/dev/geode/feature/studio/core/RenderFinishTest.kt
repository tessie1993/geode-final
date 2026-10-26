package dev.geode.feature.studio.core

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.studio.api.EditCut
import dev.geode.feature.studio.api.EditJob
import dev.geode.feature.studio.api.EditSources
import dev.geode.feature.studio.api.MadeFrom
import dev.geode.feature.studio.api.NewVideo
import dev.geode.feature.studio.api.Project
import dev.geode.feature.studio.api.ProjectId
import dev.geode.feature.studio.api.RenderExtras
import dev.geode.feature.studio.api.RenderFormat
import dev.geode.feature.studio.api.RenderId
import dev.geode.feature.studio.api.RenderJob
import dev.geode.feature.studio.api.RenderLength
import dev.geode.feature.studio.api.RenderLook
import dev.geode.feature.studio.api.RenderLoudness
import dev.geode.feature.studio.api.RenderPhase
import dev.geode.feature.studio.api.RenderStatus
import dev.geode.feature.studio.api.RenderTrack
import dev.geode.feature.studio.api.StudioState
import dev.geode.feature.studio.api.VideoKind
import dev.geode.feature.studio.api.edit.ClipContent
import dev.geode.feature.studio.api.edit.EditorProject
import dev.geode.feature.studio.api.edit.LaneId
import dev.geode.feature.studio.api.edit.LaneKind
import dev.geode.feature.studio.api.edit.SubtitleCue
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.ReportFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Render's extras made as an Edit of it (#329): captions at the lyrics' times, and title and end cards. */
class RenderFinishTest {
    private val night = RenderTrack(TrackId("content://media/12"), "Night Drive", Millis(60_000), "The Neon")
    private val video = NewVideo(VideoKind.RENDER, "Night Drive", "content://media/41", null, Millis(60_000), 1920, 1080, "Night Drive")
    private val lyrics =
        listOf(
            SubtitleCue(2_000L, 5_000L, "Lights on the road"),
            SubtitleCue(12_500L, 16_000L, "We drive all night"),
            SubtitleCue(58_000L, 62_000L, "Into the dawn"),
        )
    private val captions = RenderExtras(captions = true)
    private val cards = RenderExtras(titleCard = true, endCard = true)

    /** Each text clip on [lane] as (start, end, text). */
    private fun texts(
        project: EditorProject?,
        lane: String,
    ): List<Triple<Long, Long, String>> =
        checkNotNull(project).timeline.lane(LaneId(lane))?.clips.orEmpty().map {
            Triple(it.startMs, it.endMs, (it.content as ClipContent.Text).text)
        }

    @Test
    fun `captions appear at the lines' times, within the video`() {
        val cut = RenderFinish.cutOf(video, night, RenderLength.WholeTrack, captions, lyrics)

        assertEquals(
            listOf(
                Triple(2_000L, 5_000L, "Lights on the road"),
                Triple(12_500L, 16_000L, "We drive all night"),
                Triple(58_000L, 60_000L, "Into the dawn"),
            ),
            texts(cut, "lane-2"),
        )
        val media = checkNotNull(cut).timeline.lanes.first()
        assertEquals(LaneKind.Media, media.kind)
        assertEquals(ClipContent.Video("content://media/41"), media.clips.single().content)
        assertEquals(60_000L, media.clips.single().durationMs)
        assertEquals(60_000L, cut.timeline.durationMs)
        assertTrue(RenderFinish.hasCaptions(cut))
    }

    @Test
    fun `a section's captions start where the section does`() {
        val section = video.copy(length = Millis(10_000))
        val cut = RenderFinish.cutOf(section, night, RenderLength.Section(Millis(10_000), Millis(10_000)), captions, lyrics)

        assertEquals(listOf(Triple(2_500L, 6_000L, "We drive all night")), texts(cut, "lane-2"))
    }

    @Test
    fun `N minutes repeat the captions with the song`() {
        val long = video.copy(length = Millis(130_000))
        val cut = RenderFinish.cutOf(long, night, RenderLength.Minutes(2), captions, lyrics.take(1))

        assertEquals(
            listOf(2_000L, 62_000L, 122_000L),
            texts(cut, "lane-2").map { it.first },
        )
    }

    @Test
    fun `title and end cards show the song and artist, and lines give way to them`() {
        val cut = RenderFinish.cutOf(video, night, RenderLength.WholeTrack, cards.copy(captions = true), lyrics)

        assertEquals(
            listOf(Triple(0L, 4_000L, "Night Drive — The Neon"), Triple(56_000L, 60_000L, "Night Drive — The Neon")),
            texts(cut, "lane-3"),
        )
        assertEquals(
            listOf(Triple(4_000L, 5_000L, "Lights on the road"), Triple(12_500L, 16_000L, "We drive all night")),
            texts(cut, "lane-2"),
        )
    }

    @Test
    fun `a card for a song with no artist is the song alone, and a short video's end card leaves the title be`() {
        val short = video.copy(length = Millis(6_000))
        val cut = RenderFinish.cutOf(short, night.copy(artist = " "), RenderLength.WholeTrack, cards, emptyList())

        assertEquals(listOf(Triple(0L, 4_000L, "Night Drive"), Triple(4_000L, 6_000L, "Night Drive")), texts(cut, "lane-3"))
        assertFalse(RenderFinish.hasCaptions(checkNotNull(cut)))
    }

    @Test
    fun `an end card alone sits at the end, and none fits a video covered by its title card`() {
        val end = RenderFinish.cutOf(video, night, RenderLength.WholeTrack, RenderExtras(endCard = true), emptyList())
        assertEquals(listOf(Triple(56_000L, 60_000L, "Night Drive — The Neon")), texts(end, "lane-3"))

        val tiny = video.copy(length = Millis(3_000))
        val title = RenderFinish.cutOf(tiny, night, RenderLength.WholeTrack, cards, emptyList())
        assertEquals(listOf(Triple(0L, 3_000L, "Night Drive — The Neon")), texts(title, "lane-3"))
    }

    @Test
    fun `nothing is made when there is nothing to add`() {
        assertNull(RenderFinish.cutOf(video, night, RenderLength.WholeTrack, captions, emptyList()))
        assertNull(RenderFinish.cutOf(video, night, RenderLength.WholeTrack, RenderExtras(), lyrics))
        assertNull(RenderFinish.cutOf(video.copy(length = Millis.ZERO), night, RenderLength.WholeTrack, cards, lyrics))
        // A line that is all inside a card, or shorter than a caption may be, adds nothing either.
        assertNull(
            RenderFinish.cutOf(video, night, RenderLength.WholeTrack, captions, listOf(SubtitleCue(1_000L, 1_200L, "Oh"))),
        )
    }

    @Test
    fun `a line just long enough for a caption is kept`() {
        val cut = RenderFinish.cutOf(video, night, RenderLength.WholeTrack, captions, listOf(SubtitleCue(20_000L, 20_300L, "Hey")))

        assertEquals(listOf(Triple(20_000L, 20_300L, "Hey")), texts(cut, "lane-2"))
    }

    @Test
    fun `N minutes of a song with no length show its captions once`() {
        val cut = RenderFinish.cutOf(video, night.copy(length = Millis.ZERO), RenderLength.Minutes(1), captions, lyrics.take(1))

        assertEquals(listOf(2_000L), texts(cut, "lane-2").map { it.first })
    }

    @Test
    fun `the Edit is named after the Render and what it adds`() {
        assertEquals("Night Drive with lyrics and cards", RenderFinish.nameOf("Night Drive", cards, hasCaptions = true))
        assertEquals("Night Drive with lyrics", RenderFinish.nameOf("Night Drive", captions, hasCaptions = true))
        assertEquals("Night Drive with cards", RenderFinish.nameOf("Night Drive", RenderExtras(endCard = true), hasCaptions = false))
        assertEquals("Night Drive with cards", RenderFinish.nameOf("Night Drive", RenderExtras(titleCard = true), hasCaptions = false))
    }

    @Test
    fun `extras finish a Render only when they add captions or cards`() {
        assertFalse(RenderExtras(RenderLoudness.MINUS_14_LUFS).finishing)
        assertTrue(RenderExtras(captions = true).finishing)
        assertTrue(RenderExtras(titleCard = true).finishing)
        assertTrue(RenderExtras(endCard = true).finishing)
    }

    private val trip = Project(ProjectId(1), "Trip", Millis(10))
    private val format = RenderFormat()
    private val ready = StudioState(loading = false, projects = listOf(trip), defaultProject = trip.id)

    private fun job(
        extras: RenderExtras,
        tracks: List<RenderTrack> = listOf(night),
    ) = RenderJob(
        RenderId(3),
        RenderId(3),
        tracks,
        RenderLook.Current,
        format,
        RenderLength.WholeTrack,
        trip.id,
        extras,
        RenderStatus.Running(RenderPhase.RENDERING, 0.9f),
    )

    private fun ended(job: RenderJob) =
        StudioReducer.reduce(ready.copy(renders = listOf(job)), StudioEvent.RenderEnded(job.id, RenderEnd.Saved(video)))

    private fun finishes(next: Next<StudioState, StudioEffect>): List<StudioWork.FinishRender> =
        next.effects
            .filterIsInstance<Perform<*>>()
            .map { it.work }
            .filterIsInstance<StudioWork.FinishRender>()

    @Test
    fun `a finished Render with extras reads its lyrics to make them, after it lands in the library`() {
        val withCards = job(cards)
        val next = ended(withCards)

        val made = video.copy(madeWith = withCards.toNewRender().toSpec())
        assertEquals(listOf(StudioWork.FinishRender(withCards, made)), finishes(next))
        assertTrue(next.effects.indexOfFirst { it is Perform<*> && it.work is StudioWork.AddVideos } < next.effects.size - 1)
    }

    @Test
    fun `a Render without extras, a playlist's continuous video and an Edit are not finished`() {
        assertTrue(finishes(ended(job(RenderExtras(RenderLoudness.MINUS_14_LUFS)))).isEmpty())
        assertTrue(finishes(ended(job(cards, listOf(night, night)))).isEmpty())
        val edit = job(cards).copy(edit = EditJob("cut", EditCut.Cut(EditorProject()), EditSources.REFERENCE))
        assertTrue(finishes(ended(edit)).isEmpty())
    }

    @Test
    fun `the extras Edit is queued into the Render's Project at its format, linked to its video`() {
        val withCards = job(cards)

        val next = StudioReducer.reduce(ready, StudioEvent.FinishReady(withCards, video, emptyList()))

        val cut = checkNotNull(RenderFinish.cutOf(video, night, RenderLength.WholeTrack, cards, emptyList()))
        val render =
            NewRender(
                emptyList(),
                RenderLook.Current,
                format,
                RenderLength.WholeTrack,
                trip.id,
                RenderExtras(),
                edit = EditJob("Night Drive with cards", EditCut.Cut(cut), EditSources.REFERENCE),
                madeFrom = MadeFrom(video.file, VideoKind.EDIT),
            )
        assertEquals(Next(ready, listOf(Perform(EditWork.QueueEdit(render, null)))), next)
    }

    @Test
    fun `extras with nothing to add queue nothing, and a Project that is gone is reported`() {
        assertEquals(Next(ready), StudioReducer.reduce(ready, StudioEvent.FinishReady(job(captions), video, emptyList())))
        assertEquals(
            Next(ready),
            StudioReducer.reduce(ready, StudioEvent.FinishReady(job(cards, listOf(night, night)), video, emptyList())),
        )
        val gone = ready.copy(projects = emptyList(), defaultProject = null)
        assertEquals(
            Next(gone, listOf(ReportFailure(NO_PROJECT_REFUSAL))),
            StudioReducer.reduce(gone, StudioEvent.FinishReady(job(cards), video, emptyList())),
        )
    }
}
