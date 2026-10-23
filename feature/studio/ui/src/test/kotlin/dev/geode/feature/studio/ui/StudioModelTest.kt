package dev.geode.feature.studio.ui

import dev.geode.engine.types.Millis
import dev.geode.feature.studio.api.ProjectId
import dev.geode.feature.studio.api.SaveFolder
import dev.geode.feature.studio.api.StudioState
import dev.geode.feature.studio.api.VideoId
import dev.geode.feature.studio.api.VideoKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioModelTest {
    private val state = PreviewStudio.state
    private val trip = PreviewStudio.trip
    private val gigs = PreviewStudio.gigs
    private val drafts = PreviewStudio.drafts

    @Test
    fun `the Project list counts each Project's videos and marks the default`() {
        val list = state.toProjectList()
        assertEquals(listOf(4, 0, 0), list.projects.map { it.videos })
        assertEquals(listOf(false, true, false), list.projects.map { it.isDefault })
        assertFalse(list.folderMissing)
        assertFalse(list.noDefault)
        assertFalse(list.newIsDefault)
    }

    @Test
    fun `the Project list says when no save folder or default Project is chosen`() {
        val list = state.copy(defaultProject = null, saveFolder = SaveFolder.NotChosen).toProjectList()
        assertTrue(list.folderMissing)
        assertTrue(list.noDefault)
        assertTrue(list.newIsDefault)
        // Settings still loading is not a missing folder, and no Projects needs no default yet.
        assertFalse(StudioState(loading = false).toProjectList().folderMissing)
        assertFalse(StudioState(loading = false).toProjectList().noDefault)
    }

    @Test
    fun `a Project's page shows the videos of the chosen type and offers the other Projects`() {
        val model = checkNotNull(state.toProjectModel(trip.id, VideoKind.RECORDING))
        assertEquals(listOf(VideoId(2)), model.videos.map { it.id })
        assertEquals(listOf(gigs, drafts), model.others)
        assertEquals(ProjectRow(trip, 4, false), model.row)
        assertEquals(4, state.toProjectModel(trip.id, null)?.videos?.size)
        assertNull(state.toProjectModel(ProjectId(9), null))
    }

    @Test
    fun `deleting offers to move the videos to the default Project first`() {
        val prompt = checkNotNull(state.toProjectModel(trip.id, null)).deletePrompt
        assertEquals(DeletePromptModel(trip, 4, listOf(gigs, drafts), gigs.id), prompt)
    }

    @Test
    fun `deleting the default Project offers the newest other one`() {
        val prompt = checkNotNull(state.toProjectModel(gigs.id, null)).deletePrompt
        assertEquals(DeletePromptModel(gigs, 0, listOf(trip, drafts), trip.id), prompt)
    }

    @Test
    fun `deleting the only Project leaves nowhere to move to`() {
        val alone = StudioState(loading = false, projects = listOf(trip), videos = PreviewStudio.videos)
        assertEquals(DeletePromptModel(trip, 4, emptyList(), null), checkNotNull(alone.toProjectModel(trip.id, null)).deletePrompt)
    }

    @Test
    fun `a video's page names its Project, and is gone with the video`() {
        assertEquals(trip, state.toVideoModel(VideoId(1))?.project)
        assertNull(state.toVideoModel(VideoId(9)))
    }

    @Test
    fun `a picked folder reads as its path`() {
        assertEquals("Movies/Geode", folderLabel(PreviewStudio.FOLDER))
        assertEquals("Movies/Geode", setupModel(PreviewStudio.FOLDER).folder)
        assertEquals("content://elsewhere/x", folderLabel("content://elsewhere/x"))
        assertEquals("1234-ABCD", folderLabel("content://com.android.externalstorage.documents/tree/1234-ABCD%3A"))
        assertNull(setupModel(null).folder)
    }

    @Test
    fun `lengths read as minutes and seconds`() {
        assertEquals("3:34", Millis(214_000).clock())
        assertEquals("0:05", Millis(5_999).clock())
    }
}
