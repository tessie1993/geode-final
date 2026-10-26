package dev.geode.feature.studio.ui

import dev.geode.feature.studio.api.PlaylistMode
import dev.geode.feature.studio.api.ProjectId
import dev.geode.feature.studio.api.RenderAspect
import dev.geode.feature.studio.api.RenderExtras
import dev.geode.feature.studio.api.RenderFormat
import dev.geode.feature.studio.api.RenderId
import dev.geode.feature.studio.api.RenderLength
import dev.geode.feature.studio.api.RenderLoudness
import dev.geode.feature.studio.api.RenderPhase
import dev.geode.feature.studio.api.RenderPreset
import dev.geode.feature.studio.api.RenderSize
import dev.geode.feature.studio.api.RenderStatus
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
}
