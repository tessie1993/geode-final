package dev.geode.feature.studio.ui

import dev.geode.engine.types.Millis
import dev.geode.engine.types.PresetId
import dev.geode.feature.studio.api.FadeCurve
import dev.geode.feature.studio.api.PlaylistMode
import dev.geode.feature.studio.api.ProjectId
import dev.geode.feature.studio.api.RenderAspect
import dev.geode.feature.studio.api.RenderExtras
import dev.geode.feature.studio.api.RenderFormat
import dev.geode.feature.studio.api.RenderId
import dev.geode.feature.studio.api.RenderLength
import dev.geode.feature.studio.api.RenderLook
import dev.geode.feature.studio.api.RenderLoudness
import dev.geode.feature.studio.api.RenderPhase
import dev.geode.feature.studio.api.RenderPreset
import dev.geode.feature.studio.api.RenderSize
import dev.geode.feature.studio.api.RenderStatus
import dev.geode.feature.studio.api.StudioCommand
import dev.geode.feature.studio.api.TrackJoin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Render setup's draft and the render queue as the screens draw them (#316, #325). */
class RenderModelTest {
    private val state = PreviewStudio.state
    private val playlist = PreviewStudio.choices.playlists.first()

    @Test
    fun `a new Render starts from Settings and the default Project`() {
        val start =
            RenderDraft.startingFrom(
                state.copy(renderFormat = RenderFormat(size = RenderSize.HD720), renderExtras = RenderExtras(RenderLoudness.MINUS_11_LUFS)),
            )
        assertEquals(RenderFormat(size = RenderSize.HD720), start.spec.format)
        assertEquals(RenderLoudness.MINUS_11_LUFS, start.spec.extras.loudness)
        assertEquals(PreviewStudio.gigs.id, start.spec.project)
        assertFalse(start.ready)
        // With no default the newest Project is offered; with none at all there is nowhere to save.
        assertEquals(PreviewStudio.trip.id, RenderDraft.startingFrom(state.copy(defaultProject = null)).spec.project)
        assertNull(RenderDraft.startingFrom(state.copy(projects = emptyList(), defaultProject = null)).spec.project)
    }

    @Test
    fun `a Track or a playlist is what the Render is made of`() {
        val start = RenderDraft.startingFrom(state)
        val track = start.track(PreviewStudio.night)
        assertEquals(listOf(PreviewStudio.night), track.spec.tracks)
        assertNull(track.playlist)
        assertTrue(track.ready)
        val list = track.playlist(playlist)
        assertEquals(playlist.tracks, list.spec.tracks)
        assertEquals(PlaylistMode.ONE_VIDEO_PER_TRACK, list.spec.playlist)
        assertEquals("Evening", list.playlist)
        assertNull(list.track(PreviewStudio.tide).playlist)
        assertFalse(track.copy(spec = track.spec.copy(project = null)).ready)
    }

    @Test
    fun `a preset fills the format and shows until a value is changed by hand`() {
        val shorts = RenderDraft.startingFrom(state).preset(RenderPreset.TIKTOK_AND_SHORTS)
        assertEquals(RenderPreset.TIKTOK_AND_SHORTS, shorts.preset)
        assertEquals(RenderAspect.R9_16, shorts.spec.format.aspect)
        assertEquals(FIRST_MINUTE, shorts.spec.length)
        val custom = shorts.custom(shorts.spec.copy(length = RenderLength.WholeTrack))
        assertNull(custom.preset)
        assertEquals(RenderLength.WholeTrack, custom.spec.length)
        assertEquals(RenderAspect.R9_16, custom.spec.format.aspect)
    }

    @Test
    fun `the queue groups a playlist's Renders with their overall progress and what can be cancelled`() {
        val queue = PreviewStudio.queued.toRenderQueue()
        assertEquals(listOf(RenderId(1), RenderId(2)), queue.batches.map { it.batch })
        assertEquals(1f, queue.batches[0].progress, 0f)
        assertEquals(emptySet<RenderId>(), queue.batches[0].cancellable)
        // Running 0.42, waiting 0, failed 1: 1.42 of 3.
        assertEquals(1.42f / 3, queue.batches[1].progress, 1e-6f)
        assertEquals(setOf(RenderId(2), RenderId(3)), queue.batches[1].cancellable)
        assertTrue(queue.canClear)
        assertFalse(queue.visualsOff)
    }

    @Test
    fun `a Render already stopping cannot be cancelled again, and nothing finished leaves nothing to clear`() {
        val stopping = PreviewStudio.queued.renders[1].copy(status = RenderStatus.Running(RenderPhase.RENDERING, 0.5f, cancelling = true))
        val queue = state.copy(renders = listOf(stopping), visualsOff = true).toRenderQueue()
        assertEquals(emptySet<RenderId>(), queue.batches.single().cancellable)
        assertFalse(queue.canClear)
        assertTrue(queue.visualsOff)
        assertEquals("Summer trip", projectName(queue.projects, PreviewStudio.trip.id))
        assertEquals("", projectName(queue.projects, ProjectId(9)))
    }

    @Test
    fun `a section is typed as a time`() {
        assertEquals(Millis(75_000), parseClock("1:15"))
        assertEquals(Millis(3_725_000), parseClock(" 1:02:05 "))
        assertEquals(Millis(90_000), parseClock("90"))
        assertEquals(Millis.ZERO, parseClock("0:00"))
        assertNull(parseClock(""))
        assertNull(parseClock("1:60"))
        assertNull(parseClock("1::05"))
        assertNull(parseClock("1:2:3:4"))
        assertNull(parseClock("-1:00"))
        assertNull(parseClock("1.5"))
        assertNull(parseClock("123456"))
    }

    @Test
    fun `any section can be rendered, not only the first minute`() {
        val draft = RenderDraft.startingFrom(state).preset(RenderPreset.YOUTUBE).section(Millis(62_000), Millis(45_000))
        assertEquals(RenderLength.Section(Millis(62_000), Millis(45_000)), draft.spec.length)
        assertNull(draft.preset)
        assertTrue(isOwnSection(draft.spec.length))
        assertTrue(isOwnSection(FIRST_SECTION))
        assertFalse(isOwnSection(FIRST_MINUTE))
        assertFalse(isOwnSection(RenderLength.WholeTrack))
    }

    @Test
    fun `Render again opens the setup a Render was made from, and queues it as made again`() {
        val made =
            RenderDraft
                .startingFrom(state)
                .track(PreviewStudio.night)
                .spec
                .copy(project = PreviewStudio.trip.id)
        val video = state.videos.first().copy(madeWith = made)
        val again = checkNotNull(RenderDraft.again(video, state))
        assertEquals(made, again.spec)
        assertEquals(video.id, again.again)
        assertEquals(video.name, again.againName)
        val square = again.custom(again.spec.copy(format = again.spec.format.copy(aspect = RenderAspect.R1_1)))
        assertEquals(StudioCommand.RenderAgain(video.id, square.spec), square.command)
        // A fresh setup queues a new Render.
        val fresh = RenderDraft.startingFrom(state).track(PreviewStudio.night)
        assertEquals(StudioCommand.QueueRender(fresh.spec), fresh.command)
        // Its Project gone, it goes to the default one; a video Studio did not render cannot be made again.
        val gone = video.copy(madeWith = made.copy(project = ProjectId(99)))
        assertEquals(PreviewStudio.gigs.id, RenderDraft.again(gone, state)?.spec?.project)
        assertNull(RenderDraft.again(video.copy(madeWith = null), state))
    }

    @Test
    fun `an N-minute Render is typed in whole minutes, up to ten hours`() {
        assertEquals(90, parseMinutes(" 90 "))
        assertEquals(1, parseMinutes("1"))
        assertEquals(600, parseMinutes("600"))
        assertNull(parseMinutes("0"))
        assertNull(parseMinutes("601"))
        assertNull(parseMinutes("1.5"))
        assertNull(parseMinutes(""))
        assertEquals(RenderLength.Minutes(60), AN_HOUR)
    }

    @Test
    fun `a playlist's chapters come in each file, and a single Track has none`() {
        val tracks = listOf(PreviewStudio.night, PreviewStudio.tide)
        assertEquals("0:00 Midnight City\n4:04 Teardrop\n", chapterText(tracks, ChapterFile.DESCRIPTION))
        assertTrue(chapterText(tracks, ChapterFile.WEB_VTT).startsWith("WEBVTT"))
        assertTrue(chapterText(tracks, ChapterFile.FFMETADATA).startsWith(";FFMETADATA1"))
        assertEquals("", chapterText(listOf(PreviewStudio.night), ChapterFile.WEB_VTT))
    }

    @Test
    fun `a playlist can be one continuous video, joined by the Playback settings' crossfade`() {
        val join = TrackJoin(Millis(4_000), FadeCurve.SMOOTH)
        val start = RenderDraft.startingFrom(state.copy(renderJoin = join))
        assertEquals(join, start.spec.join)
        val list = start.playlist(playlist)
        assertFalse(list.continuous)
        val joined = list.continuous(true)
        assertTrue(joined.continuous)
        assertEquals(PlaylistMode.ONE_CONTINUOUS_VIDEO, joined.spec.playlist)
        assertFalse(joined.continuous(false).continuous)
        // Choosing another playlist keeps the choice; a single Track is never continuous.
        assertTrue(joined.playlist(playlist).continuous)
        assertFalse(joined.track(PreviewStudio.night).continuous)
    }

    @Test
    fun `each track of a continuous video can have its own look, and a new playlist starts afresh`() {
        val calm = RenderLook.Preset(PresetId("Calm"))
        val joined = RenderDraft.startingFrom(state).playlist(playlist).continuous(true)
        val second = joined.trackLook(1, calm)
        assertEquals(listOf(RenderLook.Current, calm), second.spec.looksOfTracks.take(2))
        assertEquals(
            calm,
            second
                .trackLook(0, calm)
                .spec.looksOfTracks
                .first(),
        )
        // Choosing the look for the whole video gives it to every track.
        val all = second.look(calm)
        assertEquals(calm, all.spec.look)
        assertEquals(List(playlist.tracks.size) { calm }, all.spec.looksOfTracks)
        assertEquals(emptyList<RenderLook>(), second.playlist(playlist).spec.trackLooks)
        assertEquals(emptyList<RenderLook>(), second.track(PreviewStudio.night).spec.trackLooks)
        // A track past the playlist changes nothing.
        assertEquals(joined, joined.trackLook(playlist.tracks.size, calm))
        assertEquals(joined, joined.trackLook(-1, calm))
    }

    @Test
    fun `a continuous video's chapters start where each track fades in`() {
        val tracks = listOf(PreviewStudio.night, PreviewStudio.tide)
        assertEquals("0:00 Midnight City\n4:00 Teardrop\n", chapterText(tracks, ChapterFile.DESCRIPTION, TrackJoin(Millis(4_000))))
        assertEquals("0:00 Midnight City\n4:04 Teardrop\n", chapterText(tracks, ChapterFile.DESCRIPTION, TrackJoin(gap = Millis(200))))
    }
}
