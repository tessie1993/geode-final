package dev.geode.feature.studio.core

import dev.geode.engine.types.Millis
import dev.geode.feature.studio.api.FilesOnDelete
import dev.geode.feature.studio.api.Project
import dev.geode.feature.studio.api.ProjectId
import dev.geode.feature.studio.api.StudioCommand
import dev.geode.feature.studio.api.StudioFact
import dev.geode.feature.studio.api.StudioState
import dev.geode.feature.studio.api.UNSORTED_NAME
import dev.geode.feature.studio.api.Video
import dev.geode.feature.studio.api.VideoId
import dev.geode.feature.studio.api.VideoKind
import dev.geode.kernel.Emit
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.ReportFailure
import org.junit.Assert.assertEquals
import org.junit.Test

/** Studio's Projects and library as a transition table (ADR-0003): each state against each event. */
class StudioReducerTest {
    private val trip = Project(ProjectId(1), "Trip", "content://tree/trip", Millis(10))
    private val gigs = Project(ProjectId(2), "Gigs", "content://tree/gigs", Millis(20))
    private val unsorted = Project(ProjectId(3), UNSORTED_NAME, null, Millis(30))
    private val beach = video(1, trip, "content://trip/beach.mp4")
    private val dunes = video(2, trip, "content://trip/dunes.mp4")
    private val stage = video(3, gigs, "content://gigs/stage.mp4")

    private val loading = StudioState()
    private val ready = StudioState(loading = false, projects = listOf(trip, gigs), videos = listOf(beach, dunes, stage))

    private val lookalikes = listOf(unsorted.copy(name = "Old"), unsorted.copy(id = ProjectId(4), folder = "content://tree/u"))

    private val oldA = OldVideo("content://media/a", "a.mp4", Millis(5_000), 1920, 1080)
    private val oldB = OldVideo("content://media/b", "b.mp4", Millis(6_000), 1080, 1920)

    private fun video(
        id: Long,
        project: Project,
        file: String,
    ) = Video(VideoId(id), project.id, VideoKind.RENDER, file.substringAfterLast('/'), file, null, Millis(1_000), 1920, 1080, "Song")

    private fun command(command: StudioCommand) = StudioEvent.Command(command)

    private fun reduce(
        state: StudioState,
        event: StudioEvent,
    ) = StudioReducer.reduce(state, event)

    private data class Row(
        val from: StudioState,
        val event: StudioEvent,
        val to: Next<StudioState, StudioEffect>,
    )

    private fun row(
        from: StudioState,
        event: StudioEvent,
        to: StudioState,
        vararg effects: StudioEffect,
    ) = Row(from, event, Next(to, effects.toList()))

    private val table: List<Row> =
        listOf(
            // Commands before the library is read are dropped.
            row(loading, command(StudioCommand.CreateProject("New", "content://tree/new")), loading),
            row(loading, command(StudioCommand.RenameProject(trip.id, "Holiday")), loading),
            row(loading, command(StudioCommand.DeleteProject(trip.id, FilesOnDelete.DELETE)), loading),
            row(loading, command(StudioCommand.MoveVideo(beach.id, gigs.id)), loading),
            // Reading the library, with no old videos or only ones already in a Project.
            row(loading, StudioEvent.Loaded(listOf(trip), listOf(beach), emptyList()), StudioState(false, listOf(trip), listOf(beach))),
            row(
                loading,
                StudioEvent.Loaded(listOf(trip), listOf(beach), listOf(OldVideo(beach.file, "x", Millis(1), 1, 1))),
                StudioState(false, listOf(trip), listOf(beach)),
            ),
            // Old videos go into a new Unsorted Project, or the one there is.
            row(
                loading,
                StudioEvent.Loaded(listOf(trip), listOf(beach), listOf(oldA, OldVideo(beach.file, "x", Millis(1), 1, 1), oldB)),
                StudioState(false, listOf(trip), listOf(beach)),
                Perform(StudioWork.Import(null, listOf(oldA, oldB))),
            ),
            row(
                loading,
                StudioEvent.Loaded(listOf(trip, unsorted), emptyList(), listOf(oldA)),
                StudioState(false, listOf(trip, unsorted), emptyList()),
                Perform(StudioWork.Import(unsorted, listOf(oldA))),
            ),
            // A renamed Unsorted, or a Project named Unsorted with a folder, is the user's own.
            row(
                loading,
                StudioEvent.Loaded(lookalikes, emptyList(), listOf(oldA)),
                StudioState(false, lookalikes, emptyList()),
                Perform(StudioWork.Import(null, listOf(oldA))),
            ),
            row(
                loading,
                StudioEvent.Failed(StudioFailure.LOAD),
                StudioState(loading = false),
                ReportFailure("Could not read the Studio library"),
            ),
            // Create: saved first, shown with its id once saved.
            row(
                ready,
                command(StudioCommand.CreateProject("  Live  ", "content://tree/live")),
                ready,
                Perform(StudioWork.CreateProject("Live", "content://tree/live")),
            ),
            row(ready, command(StudioCommand.CreateProject("  ", "content://tree/live")), ready),
            row(ready, command(StudioCommand.CreateProject("Live", " ")), ready),
            row(
                ready,
                StudioEvent.ProjectCreated(unsorted),
                ready.copy(projects = listOf(unsorted, trip, gigs)),
                Emit(StudioFact.ProjectCreated(unsorted.id)),
            ),
            // Rename.
            row(
                ready,
                command(StudioCommand.RenameProject(trip.id, " Holiday ")),
                ready.copy(projects = listOf(trip.copy(name = "Holiday"), gigs)),
                Perform(StudioWork.RenameProject(trip.id, "Holiday")),
            ),
            row(ready, command(StudioCommand.RenameProject(trip.id, " ")), ready),
            row(ready, command(StudioCommand.RenameProject(trip.id, "Trip")), ready),
            row(ready, command(StudioCommand.RenameProject(ProjectId(9), "Holiday")), ready),
            // Delete keeps the files, or deletes them too: exactly what was chosen.
            row(
                ready,
                command(StudioCommand.DeleteProject(trip.id, FilesOnDelete.KEEP)),
                ready.copy(projects = listOf(gigs), videos = listOf(stage)),
                Perform(StudioWork.DeleteProject(trip.id, trip.folder, emptyList())),
            ),
            row(
                ready,
                command(StudioCommand.DeleteProject(trip.id, FilesOnDelete.DELETE)),
                ready.copy(projects = listOf(gigs), videos = listOf(stage)),
                Perform(StudioWork.DeleteProject(trip.id, trip.folder, listOf(beach.file, dunes.file))),
            ),
            row(ready, command(StudioCommand.DeleteProject(ProjectId(9), FilesOnDelete.DELETE)), ready),
            // Move.
            row(
                ready,
                command(StudioCommand.MoveVideo(beach.id, gigs.id)),
                ready.copy(videos = listOf(beach.copy(project = gigs.id), dunes, stage)),
                Perform(StudioWork.MoveVideo(beach.id, gigs.id)),
            ),
            row(ready, command(StudioCommand.MoveVideo(beach.id, trip.id)), ready),
            row(ready, command(StudioCommand.MoveVideo(beach.id, ProjectId(9))), ready),
            row(ready, command(StudioCommand.MoveVideo(VideoId(9), gigs.id)), ready),
            // Imported videos join the library, with their Project when it is new.
            row(
                ready,
                StudioEvent.Imported(unsorted, listOf(video(4, unsorted, oldA.file))),
                ready.copy(projects = listOf(trip, gigs, unsorted), videos = listOf(beach, dunes, stage, video(4, unsorted, oldA.file))),
            ),
            row(
                ready.copy(projects = listOf(trip, unsorted)),
                StudioEvent.Imported(unsorted, listOf(video(4, unsorted, oldA.file))),
                ready.copy(projects = listOf(trip, unsorted), videos = listOf(beach, dunes, stage, video(4, unsorted, oldA.file))),
            ),
            // Failures after the read only report.
            row(ready, StudioEvent.Failed(StudioFailure.SAVE), ready, ReportFailure("Could not save the change to the Studio library")),
            row(ready, StudioEvent.Failed(StudioFailure.IMPORT), ready, ReportFailure("Could not add the old Studio videos")),
            row(ready, StudioEvent.Failed(StudioFailure.DELETE_FILES), ready, ReportFailure("Some video files could not be deleted")),
            row(loading, StudioEvent.Failed(StudioFailure.SAVE), loading, ReportFailure("Could not save the change to the Studio library")),
        )

    @Test
    fun `every row of the table`() {
        table.forEach { (from, event, to) ->
            assertEquals("$event from $from", to, reduce(from, event))
        }
    }

    @Test
    fun `a Project's videos are the ones in it`() {
        assertEquals(listOf(beach, dunes), ready.videosIn(trip.id))
        assertEquals(emptyList<Video>(), ready.videosIn(unsorted.id))
    }
}
