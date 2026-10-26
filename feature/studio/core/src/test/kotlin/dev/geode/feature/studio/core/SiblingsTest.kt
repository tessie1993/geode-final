package dev.geode.feature.studio.core

import dev.geode.engine.types.Millis
import dev.geode.engine.types.PresetId
import dev.geode.engine.types.TrackId
import dev.geode.feature.studio.api.EditCommand
import dev.geode.feature.studio.api.EditCut
import dev.geode.feature.studio.api.EditJob
import dev.geode.feature.studio.api.EditSession
import dev.geode.feature.studio.api.EditSource
import dev.geode.feature.studio.api.EditSources
import dev.geode.feature.studio.api.MadeFrom
import dev.geode.feature.studio.api.NewVideo
import dev.geode.feature.studio.api.PlaylistMode
import dev.geode.feature.studio.api.Project
import dev.geode.feature.studio.api.ProjectId
import dev.geode.feature.studio.api.RenderAspect
import dev.geode.feature.studio.api.RenderCodec
import dev.geode.feature.studio.api.RenderExtras
import dev.geode.feature.studio.api.RenderFormat
import dev.geode.feature.studio.api.RenderId
import dev.geode.feature.studio.api.RenderJob
import dev.geode.feature.studio.api.RenderLength
import dev.geode.feature.studio.api.RenderLook
import dev.geode.feature.studio.api.RenderLoudness
import dev.geode.feature.studio.api.RenderPhase
import dev.geode.feature.studio.api.RenderPreset
import dev.geode.feature.studio.api.RenderQuality
import dev.geode.feature.studio.api.RenderSize
import dev.geode.feature.studio.api.RenderSpec
import dev.geode.feature.studio.api.RenderStatus
import dev.geode.feature.studio.api.RenderTrack
import dev.geode.feature.studio.api.SaveFolder
import dev.geode.feature.studio.api.Shorts
import dev.geode.feature.studio.api.StudioCommand
import dev.geode.feature.studio.api.StudioState
import dev.geode.feature.studio.api.Video
import dev.geode.feature.studio.api.VideoId
import dev.geode.feature.studio.api.VideoKind
import dev.geode.feature.studio.api.edit.ClipEdit
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.ReportFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Make Short and Export as as a transition table (ADR-0003, #328). */
class SiblingsTest {
    private val trip = Project(ProjectId(1), "Trip", Millis(10))
    private val gigs = Project(ProjectId(2), "Gigs", Millis(20))
    private val night = RenderTrack(TrackId("content://media/12"), "Night Drive", Millis(215_000))
    private val look = RenderLook.Preset(PresetId("Calm"))
    private val wide = RenderFormat(RenderAspect.R16_9, RenderSize.UHD4K, 60, RenderQuality.BEST, RenderCodec.HEVC)
    private val drawn = RenderSpec(listOf(night), look, wide, RenderLength.WholeTrack, trip.id, extras = RenderExtras())
    private val render =
        Video(VideoId(4), trip.id, VideoKind.RENDER, "Night Drive.mp4", "content://media/4", null, Millis(215_000), 3840, 2160, "", drawn)
    private val take =
        Video(VideoId(5), gigs.id, VideoKind.RECORDING, "take.mp4", "content://tree/take.mp4", null, Millis(40_000), 1080, 1920, "")
    private val renderSource = EditSource(render.file, render.name, render.length, render.width, render.height)
    private val takeSource = EditSource(take.file, take.name, take.length, take.width, take.height)
    private val settingsFormat = RenderFormat(RenderAspect.R16_9, RenderSize.FHD1080, 60, RenderQuality.HIGH, RenderCodec.HEVC)
    private val shortFormat = settingsFormat.copy(aspect = RenderAspect.R9_16, size = RenderSize.FHD1080, fps = 30)
    private val shortExtras = RenderExtras(RenderLoudness.MINUS_14_LUFS)
    private val shortEdit = ClipEdit(endMs = 59_000L, ratio = RenderAspect.R9_16, quality = RenderSize.FHD1080)

    private val ready =
        StudioState(
            loading = false,
            projects = listOf(trip, gigs),
            videos = listOf(render, take),
            defaultProject = gigs.id,
            renderFormat = settingsFormat,
        )

    private fun shorting(
        session: EditSession.Short,
        from: StudioState = ready,
    ) = from.copy(edit = session)

    private val renderShort = EditSession.Short(render.id, renderSource, shortEdit, canRedraw = true)
    private val ofPlaylist = ready.copy(videos = listOf(render.copy(madeWith = drawn.copy(tracks = listOf(night, night)))))
    private val ofLoop = ready.copy(videos = listOf(render.copy(madeWith = drawn.copy(length = RenderLength.Loop))))
    private val ofSection =
        ready.copy(videos = listOf(render.copy(madeWith = drawn.copy(length = RenderLength.Section(Millis(5), Millis(90_000))))))
    private val ofLateSection =
        ready.copy(videos = listOf(render.copy(madeWith = drawn.copy(length = RenderLength.Section(Millis(60_000), Millis(90_000))))))
    private val takeShort = EditSession.Short(take.id, takeSource, ClipEdit(endMs = 40_000L, ratio = RenderAspect.R9_16))

    private fun command(command: StudioCommand) = StudioEvent.Command(command)

    private data class Row(
        val name: String,
        val from: StudioState,
        val event: StudioEvent,
        val to: Next<StudioState, StudioEffect>,
    )

    private fun row(
        name: String,
        from: StudioState,
        event: StudioEvent,
        to: StudioState,
        vararg effects: StudioEffect,
    ) = Row(name, from, event, Next(to, effects.toList()))

    private fun change(
        startMs: Long,
        endMs: Long,
        cropX: Float = 0.5f,
        cropY: Float = 0.5f,
        redraw: Boolean = false,
    ) = command(EditCommand.ChangeShort(startMs, endMs, cropX, cropY, redraw))

    private fun cropped(
        session: EditSession.Short,
        name: String,
        project: ProjectId,
        folder: String? = null,
        sources: EditSources = EditSources.REFERENCE,
    ) = Perform(
        EditWork.QueueEdit(
            NewRender(
                emptyList(),
                RenderLook.Current,
                shortFormat,
                RenderLength.WholeTrack,
                project,
                shortExtras,
                edit = EditJob(name, EditCut.OneClip(session.source, session.edit), sources, RenderLoudness.MINUS_14_LUFS),
                madeFrom = MadeFrom(session.source.file, VideoKind.SHORT),
            ),
            folder,
        ),
    )

    private val makeRows: List<Row> =
        listOf(
            row(
                "Make Short opens on a Render's first 59 seconds, cropped in the middle, and it can be drawn again",
                ready,
                command(EditCommand.MakeShort(render.id)),
                shorting(renderShort),
            ),
            row(
                "Make Short on a shorter Recording keeps all of it, and it can only be cropped",
                ready,
                command(EditCommand.MakeShort(take.id)),
                shorting(takeShort.copy(edit = takeShort.edit.copy(quality = RenderSize.FHD1080))),
            ),
            row("Make Short on a video that is gone opens nothing", ready, command(EditCommand.MakeShort(VideoId(99))), ready),
            row(
                "a Render of a playlist cannot be drawn again as a Short",
                ofPlaylist,
                command(EditCommand.MakeShort(render.id)),
                shorting(renderShort.copy(canRedraw = false), ofPlaylist),
            ),
            row(
                "a long loop cannot be drawn again as a Short",
                ofLoop,
                command(EditCommand.MakeShort(render.id)),
                shorting(renderShort.copy(canRedraw = false), ofLoop),
            ),
            row(
                "a Render of a section can be drawn again as a Short",
                ofSection,
                command(EditCommand.MakeShort(render.id)),
                shorting(renderShort, ofSection),
            ),
        )

    private val sectionRows: List<Row> =
        listOf(
            row(
                "the section moves and the crop slides",
                shorting(renderShort),
                change(10_000L, 69_999L, cropX = 0.2f),
                shorting(renderShort.copy(edit = shortEdit.copy(startMs = 10_000L, endMs = 69_999L, cropX = 0.2f))),
            ),
            row("a section of exactly 60 seconds is refused", shorting(renderShort), change(10_000L, 70_000L), shorting(renderShort)),
            row("a section longer than a minute is refused", shorting(renderShort), change(0L, 90_000L), shorting(renderShort)),
            row(
                "a section of exactly one second is kept",
                shorting(renderShort),
                change(3_000L, 4_000L),
                shorting(renderShort.copy(edit = shortEdit.copy(startMs = 3_000L, endMs = 4_000L))),
            ),
            row("a section under a second is refused", shorting(renderShort), change(3_000L, 3_999L), shorting(renderShort)),
            row("a section past the video's end is refused", shorting(takeShort), change(0L, 40_001L), shorting(takeShort)),
            row(
                "a section to the video's very end is kept",
                shorting(takeShort),
                change(1_000L, 40_000L),
                shorting(takeShort.copy(edit = takeShort.edit.copy(startMs = 1_000L))),
            ),
            row("a section before the start is refused", shorting(takeShort), change(-1L, 20_000L), shorting(takeShort)),
            row(
                "the crop stays within the picture",
                shorting(takeShort),
                change(0L, 30_000L, cropX = -2f, cropY = 7f),
                shorting(takeShort.copy(edit = takeShort.edit.copy(endMs = 30_000L, cropX = 0f, cropY = 1f))),
            ),
            row(
                "a Render is chosen to be drawn again",
                shorting(renderShort),
                change(0L, 59_000L, redraw = true),
                shorting(renderShort.copy(redraw = true)),
            ),
            row("a Recording cannot be drawn again", shorting(takeShort), change(0L, 40_000L, redraw = true), shorting(takeShort)),
            row("no section changes while Make Short is closed", ready, change(0L, 10_000L), ready),
        )

    private val exportRows: List<Row> =
        listOf(
            row(
                "a cropped Short lands as a Short in its source's Project, at the Shorts preset",
                shorting(takeShort),
                command(EditCommand.Export()),
                shorting(takeShort),
                cropped(takeShort, "take Short", gigs.id),
            ),
            row(
                "a Short lands in the Project chosen",
                shorting(takeShort),
                command(EditCommand.Export(trip.id)),
                shorting(takeShort),
                cropped(takeShort, "take Short", trip.id),
            ),
            row(
                "a Short whose source Project is gone lands in the default one",
                shorting(takeShort, ready.copy(projects = listOf(trip), defaultProject = trip.id)),
                command(EditCommand.Export()),
                shorting(takeShort, ready.copy(projects = listOf(trip), defaultProject = trip.id)),
                cropped(takeShort, "take Short", trip.id),
            ),
            row(
                "a Short with nowhere to land is refused",
                shorting(takeShort, ready.copy(projects = emptyList(), defaultProject = null)),
                command(EditCommand.Export()),
                shorting(takeShort, ready.copy(projects = emptyList(), defaultProject = null)),
                ReportFailure(NO_SHORT_PROJECT_REFUSAL),
            ),
            row(
                "a Short whose videos are copied needs a save folder",
                shorting(takeShort, ready.copy(editSources = EditSources.COPY)),
                command(EditCommand.Export()),
                shorting(takeShort, ready.copy(editSources = EditSources.COPY)),
                ReportFailure(NO_FOLDER_FOR_COPIES),
            ),
            row(
                "a Short whose videos are copied goes to the save folder",
                shorting(takeShort, ready.copy(editSources = EditSources.COPY, saveFolder = SaveFolder.Chosen("content://tree/v"))),
                command(EditCommand.Export()),
                shorting(takeShort, ready.copy(editSources = EditSources.COPY, saveFolder = SaveFolder.Chosen("content://tree/v"))),
                cropped(takeShort, "take Short", gigs.id, "content://tree/v", EditSources.COPY),
            ),
            row(
                "a Render not chosen to be drawn again is cropped",
                shorting(renderShort),
                command(EditCommand.Export()),
                shorting(renderShort),
                cropped(renderShort, "Night Drive Short", trip.id),
            ),
            row(
                "a Render drawn again as a Short is the Short's section of its Track at the Shorts preset",
                shorting(renderShort.copy(redraw = true, edit = shortEdit.copy(startMs = 30_000L, endMs = 80_000L))),
                command(EditCommand.Export()),
                shorting(renderShort.copy(redraw = true, edit = shortEdit.copy(startMs = 30_000L, endMs = 80_000L))),
                Perform(
                    StudioWork.QueueRenderAgain(
                        render.file,
                        NewRender(
                            listOf(night),
                            look,
                            shortFormat,
                            RenderLength.Section(Millis(30_000), Millis(50_000)),
                            trip.id,
                            shortExtras,
                            madeFrom = MadeFrom(render.file, VideoKind.SHORT),
                        ),
                    ),
                ),
            ),
            row(
                "a section Render drawn again as a Short starts where the section did",
                shorting(renderShort.copy(redraw = true, edit = shortEdit.copy(startMs = 1_000L, endMs = 2_000L)), ofLateSection),
                command(EditCommand.Export()),
                shorting(renderShort.copy(redraw = true, edit = shortEdit.copy(startMs = 1_000L, endMs = 2_000L)), ofLateSection),
                Perform(
                    StudioWork.QueueRenderAgain(
                        render.file,
                        NewRender(
                            listOf(night),
                            look,
                            shortFormat,
                            RenderLength.Section(Millis(61_000), Millis(1_000)),
                            trip.id,
                            shortExtras,
                            madeFrom = MadeFrom(render.file, VideoKind.SHORT),
                        ),
                    ),
                ),
            ),
            row(
                "no Short is drawn while Visuals off is on",
                shorting(renderShort.copy(redraw = true), ready.copy(visualsOff = true)),
                command(EditCommand.Export()),
                shorting(renderShort.copy(redraw = true), ready.copy(visualsOff = true)),
                ReportFailure(VISUALS_OFF_REFUSAL),
            ),
        )

    private val siblingRows: List<Row> =
        listOf(
            row(
                "a Render is drawn again at the preset, from its remembered setup, into the Project chosen",
                ready,
                command(StudioCommand.ExportAs(render.id, RenderPreset.SQUARE, gigs.id)),
                ready,
                Perform(
                    StudioWork.QueueRenderAgain(
                        render.file,
                        NewRender(
                            listOf(night),
                            look,
                            wide.copy(aspect = RenderAspect.R1_1),
                            RenderLength.WholeTrack,
                            gigs.id,
                            RenderExtras(),
                            madeFrom = MadeFrom(render.file, VideoKind.RENDER),
                        ),
                    ),
                ),
            ),
            row(
                "a Recording is cropped and trimmed to the preset",
                ready,
                command(StudioCommand.ExportAs(take.id, RenderPreset.TIKTOK_AND_SHORTS, trip.id)),
                ready,
                Perform(
                    EditWork.QueueEdit(
                        NewRender(
                            emptyList(),
                            RenderLook.Current,
                            shortFormat,
                            RenderLength.WholeTrack,
                            trip.id,
                            shortExtras,
                            edit =
                                EditJob(
                                    "take (TikTok and Shorts)",
                                    EditCut.OneClip(takeSource, ClipEdit(endMs = 40_000L, ratio = RenderAspect.R9_16)),
                                    EditSources.REFERENCE,
                                    RenderLoudness.MINUS_14_LUFS,
                                ),
                            madeFrom = MadeFrom(take.file, VideoKind.RECORDING),
                        ),
                        null,
                    ),
                ),
            ),
            row(
                "a Render is cropped while Visuals off is on",
                ready.copy(visualsOff = true),
                command(StudioCommand.ExportAs(render.id, RenderPreset.FEED, trip.id)),
                ready.copy(visualsOff = true),
                Perform(
                    EditWork.QueueEdit(
                        NewRender(
                            emptyList(),
                            RenderLook.Current,
                            settingsFormat.copy(aspect = RenderAspect.R4_5),
                            RenderLength.WholeTrack,
                            trip.id,
                            RenderExtras(),
                            edit =
                                EditJob(
                                    "Night Drive (Feed)",
                                    EditCut.OneClip(renderSource, ClipEdit(ratio = RenderAspect.R4_5)),
                                    EditSources.REFERENCE,
                                ),
                            madeFrom = MadeFrom(render.file, VideoKind.RENDER),
                        ),
                        null,
                    ),
                ),
            ),
            row(
                "a sibling of a video that is gone is nothing",
                ready,
                command(StudioCommand.ExportAs(VideoId(99), RenderPreset.SQUARE, trip.id)),
                ready,
            ),
            row(
                "a sibling with no Project is refused",
                ready.copy(defaultProject = null),
                command(StudioCommand.ExportAs(take.id, RenderPreset.SQUARE, ProjectId(42))),
                ready.copy(defaultProject = null),
                ReportFailure(NO_SIBLING_PROJECT_REFUSAL),
            ),
        )

    @Test
    fun `every row of the transition table`() {
        for (row in makeRows + sectionRows + exportRows + siblingRows) {
            assertEquals(row.name, row.to, StudioReducer.reduce(row.from, row.event))
        }
    }

    @Test
    fun `a finished Short lands as a Short, linked to its source`() {
        val job =
            RenderJob(
                RenderId(8),
                RenderId(8),
                emptyList(),
                RenderLook.Current,
                shortFormat,
                RenderLength.WholeTrack,
                gigs.id,
                shortExtras,
                RenderStatus.Running(RenderPhase.RENDERING, 0.5f),
                edit = EditJob("take Short", EditCut.OneClip(takeSource, takeShort.edit), EditSources.REFERENCE),
                madeFrom = MadeFrom(take.file, VideoKind.SHORT),
            )
        val made = NewVideo(VideoKind.EDIT, "take Short", "content://media/9", null, Millis(30_000), 1080, 1920, "")

        val next = StudioReducer.reduce(ready.copy(renders = listOf(job)), StudioEvent.RenderEnded(job.id, RenderEnd.Saved(made)))

        val added =
            next.effects
                .filterIsInstance<Perform<*>>()
                .map { it.work }
                .filterIsInstance<StudioWork.AddVideos>()
                .single()
        assertEquals(StudioWork.AddVideos(gigs.id, listOf(made.copy(kind = VideoKind.SHORT, source = take.id))), added)
    }

    @Test
    fun `a sibling whose source was deleted meanwhile lands unlinked`() {
        val job =
            RenderJob(
                RenderId(8),
                RenderId(8),
                listOf(night),
                look,
                wide,
                RenderLength.WholeTrack,
                gigs.id,
                RenderExtras(),
                RenderStatus.Running(RenderPhase.RENDERING, 0.5f),
                madeFrom = MadeFrom("content://media/gone", VideoKind.RENDER),
            )
        val made = NewVideo(VideoKind.RENDER, "Night Drive", "content://media/10", null, Millis(215_000), 3840, 2160, "Night Drive")

        val next = StudioReducer.reduce(ready.copy(renders = listOf(job)), StudioEvent.RenderEnded(job.id, RenderEnd.Saved(made)))

        val added =
            next.effects
                .filterIsInstance<Perform<*>>()
                .map { it.work }
                .filterIsInstance<StudioWork.AddVideos>()
                .single()
        val spec = RenderSpec(listOf(night), look, wide, RenderLength.WholeTrack, gigs.id, PlaylistMode.ONE_VIDEO_PER_TRACK, RenderExtras())
        assertEquals(listOf(made.copy(madeWith = spec)), added.videos)
        assertTrue(next.effects.none { it is ReportFailure })
        assertFalse(next.effects.any { it is Perform<*> && it.work is StudioWork.QueueRenders })
    }

    @Test
    fun `a Short fits under a minute and over a second, inside its source`() {
        assertTrue(Shorts.fits(0L, 59_999L, 60_000L))
        assertFalse(Shorts.fits(0L, 60_000L, 60_000L))
        assertTrue(Shorts.fits(0L, 1_000L, 1_000L))
        assertFalse(Shorts.fits(0L, 999L, 1_000L))
        assertFalse(Shorts.fits(0L, 2_000L, 1_000L))
        assertFalse(Shorts.fits(-1L, 2_000L, 5_000L))
    }

    @Test
    fun `every preset has its name in a sibling's`() {
        assertEquals(
            listOf("TikTok and Shorts", "YouTube", "Feed", "Square", "Master"),
            RenderPreset.entries.map(Siblings::nameOf),
        )
    }
}
