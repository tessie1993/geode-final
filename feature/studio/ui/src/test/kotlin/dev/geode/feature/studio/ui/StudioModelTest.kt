package dev.geode.feature.studio.ui

import dev.geode.engine.types.Millis
import dev.geode.feature.studio.api.ProjectId
import dev.geode.feature.studio.api.VideoId
import dev.geode.feature.studio.api.VideoKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StudioModelTest {
    private val state = PreviewStudio.state

    @Test
    fun `the Project list counts each Project's videos`() {
        assertEquals(listOf(4, 0, 0), state.toProjectList().projects.map { it.videos })
    }

    @Test
    fun `a Project's page shows the videos of the chosen type and offers the other Projects`() {
        val model = checkNotNull(state.toProjectModel(PreviewStudio.trip.id, VideoKind.RECORDING))
        assertEquals(listOf(VideoId(2)), model.videos.map { it.id })
        assertEquals(listOf(PreviewStudio.gigs, PreviewStudio.unsorted), model.others)
        assertEquals(4, state.toProjectModel(PreviewStudio.trip.id, null)?.videos?.size)
        assertNull(state.toProjectModel(ProjectId(9), null))
    }

    @Test
    fun `a video's page names its Project, and is gone with the video`() {
        assertEquals(PreviewStudio.trip, state.toVideoModel(VideoId(1))?.project)
        assertNull(state.toVideoModel(VideoId(9)))
    }

    @Test
    fun `lengths read as minutes and seconds`() {
        assertEquals("3:34", Millis(214_000).clock())
        assertEquals("0:05", Millis(5_999).clock())
    }
}
