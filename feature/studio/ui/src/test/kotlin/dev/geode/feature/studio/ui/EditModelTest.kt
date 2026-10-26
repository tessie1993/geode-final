package dev.geode.feature.studio.ui

import dev.geode.engine.types.Millis
import dev.geode.feature.studio.api.EditCommand
import dev.geode.feature.studio.api.EditCut
import dev.geode.feature.studio.api.EditJob
import dev.geode.feature.studio.api.EditSession
import dev.geode.feature.studio.api.EditSource
import dev.geode.feature.studio.api.EditSources
import dev.geode.feature.studio.api.ProjectId
import dev.geode.feature.studio.api.RenderExtras
import dev.geode.feature.studio.api.RenderFormat
import dev.geode.feature.studio.api.RenderId
import dev.geode.feature.studio.api.RenderJob
import dev.geode.feature.studio.api.RenderLength
import dev.geode.feature.studio.api.RenderLook
import dev.geode.feature.studio.api.RenderPhase
import dev.geode.feature.studio.api.RenderStatus
import dev.geode.feature.studio.api.StudioState
import dev.geode.feature.studio.api.edit.ClipContent
import dev.geode.feature.studio.api.edit.ClipEdit
import dev.geode.feature.studio.api.edit.EditError
import dev.geode.feature.studio.api.edit.EditorHistory
import dev.geode.feature.studio.api.edit.EditorProject
import dev.geode.feature.studio.api.edit.LaneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the editors draw from Studio's state (#318). */
class EditModelTest {
    private val source = EditSource("content://videos/beach.mp4", "beach.mp4", Millis(9_000), 1920, 1080)
    private val ready = StudioState(loading = false)

    private fun job(
        id: Long,
        status: RenderStatus,
        edit: EditJob? = EditJob("beach", EditCut.OneClip(source, ClipEdit()), EditSources.REFERENCE),
    ) = RenderJob(
        RenderId(id),
        RenderId(id),
        emptyList(),
        RenderLook.Current,
        RenderFormat(),
        RenderLength.WholeTrack,
        ProjectId(1),
        RenderExtras(),
        status,
        edit = edit,
    )

    @Test
    fun `nothing is drawn while no editor is open`() {
        assertNull(ready.toEditModel())
    }

    @Test
    fun `an editor that is opening says which`() {
        assertEquals(EditScreenModel.Opening(cut = false), ready.copy(edit = EditSession.Reading).toEditModel())
        assertEquals(EditScreenModel.Opening(cut = true), ready.copy(edit = EditSession.LoadingCut).toEditModel())
    }

    @Test
    fun `the clip editor draws its video and edit, and the Edits on their way`() {
        val running = job(1, RenderStatus.Running(RenderPhase.RENDERING, 0.4f))
        val waiting = job(2, RenderStatus.Queued)
        val render = job(3, RenderStatus.Queued, edit = null)
        val done = job(4, RenderStatus.Done)
        val state =
            ready.copy(
                edit = EditSession.OneClip(source, ClipEdit(startMs = 1_000L)),
                renders = listOf(running, waiting, render, done),
            )

        val model = (state.toEditModel() as EditScreenModel.OneClip).model

        assertEquals(source, model.source)
        assertEquals(listOf(EditExportRow("beach", 0.4f), EditExportRow("beach", null)), model.exports)
    }

    @Test
    fun `the Timeline draws its cut, playhead, undo and redo, and why a change was refused`() {
        val after = EditorProject().copy(timeline = EditorProject().timeline.copy(durationMs = 5L))
        val history = EditorHistory(EditorProject()).push(after)
        val state = ready.copy(edit = EditSession.Cut(history, 700L, EditError.TooShort))

        val model = (state.toEditModel() as EditScreenModel.Cut).model

        assertEquals(TimelineModel(after, 700L, canUndo = true, canRedo = false, refused = EditError.TooShort), model)
    }

    @Test
    fun `the clip editor knows what its trim keeps`() {
        val whole = ClipEditorModel(source, ClipEdit())
        assertTrue(whole.atDefaults)
        assertTrue(whole.canExport)
        assertEquals(9_000L, whole.outMs)
        val trimmed = ClipEditorModel(source, ClipEdit(startMs = 2_000L, endMs = 5_000L))
        assertFalse(trimmed.atDefaults)
        assertEquals(5_000L, trimmed.outMs)
        assertFalse(ClipEditorModel(source, ClipEdit(startMs = 9_000L)).canExport)
        assertEquals(1L, ClipEditorModel(source.copy(length = Millis.ZERO), ClipEdit()).lengthMs)
    }

    @Test
    fun `a picked file becomes the clip its lane takes`() {
        val lane = LaneId("lane-1")
        assertEquals(EditCommand.AddClip(lane, ClipContent.Video("f"), 4_000L, 4_000L), clipFor(lane, MediaPick.VIDEO, "f", 4_000L))
        assertEquals(EditCommand.AddClip(lane, ClipContent.Audio("f"), 4_000L, 4_000L), clipFor(lane, MediaPick.AUDIO, "f", 4_000L))
        assertEquals(EditCommand.AddClip(lane, ClipContent.Still("f"), 3_000L), clipFor(lane, MediaPick.STILL, "f", 4_000L))
        assertEquals(EditCommand.AddClip(lane, ClipContent.Overlay("f"), 4_000L), clipFor(lane, MediaPick.OVERLAY, "f", 9_000L))
        assertEquals(listOf("video/*", "image/*", "image/*", "audio/*"), MediaPick.entries.map(::typesOf))
    }

    @Test
    fun `times read as seconds under a minute and minutes after`() {
        assertEquals("1.2s", clockLabel(1_234L))
        assertEquals("1:05", clockLabel(65_000L))
        assertEquals("beach.mp4", fileLabel("content://media/external/primary:Movies/beach.mp4"))
    }

    @Test
    fun `the Timeline's scale maps time to pixels and back within its content`() {
        val scale = TimelineScale(0.5f, 10_000L)
        assertEquals(500f, scale.xOf(1_000L), 0f)
        assertEquals(2_000L, scale.msOf(1_000f))
        assertEquals(10_000L, scale.msOf(99_999f))
        assertEquals(5_000f, scale.contentPx, 0f)
    }
}
