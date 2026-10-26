package dev.geode.feature.studio.core

import dev.geode.engine.types.Millis
import dev.geode.feature.studio.api.NewVideo
import dev.geode.feature.studio.api.Project
import dev.geode.feature.studio.api.ProjectId
import dev.geode.feature.studio.api.SaveFolder
import dev.geode.feature.studio.api.StudioCommand
import dev.geode.feature.studio.api.StudioFact
import dev.geode.feature.studio.api.StudioState
import dev.geode.feature.studio.api.Video
import dev.geode.feature.studio.api.VideoId
import dev.geode.feature.studio.api.VideoKind
import dev.geode.feature.studio.api.VideosOnDelete
import dev.geode.kernel.Emit
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.ReportFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Studio's Projects and library as a transition table (ADR-0003): each state against each event. */
class StudioReducerTest {
    private val trip = Project(ProjectId(1), "Trip", Millis(10))
    private val gigs = Project(ProjectId(2), "Gigs", Millis(20))
    private val gone = ProjectId(9)
    private val beach = video(1, trip, "content://videos/beach.mp4")
    private val dunes = video(2, trip, "content://videos/dunes.mp4")
    private val stage = video(3, gigs, "content://videos/stage.mp4")
    private val take = NewVideo(VideoKind.RECORDING, "take.mp4", "content://videos/take.mp4", null, Millis(4_000), 1080, 1920, "Teardrop")
    private val saved = Video(VideoId(7), gigs.id, take.kind, take.name, take.file, null, take.length, take.width, take.height, take.song)
    private val folder = SaveFolder.Chosen(TREE)
    private val earlier = Project(ProjectId(3), OLD_VIDEOS_PROJECT, Millis(30))
    private val old = video(8, earlier, "content://media/external/video/media/80")

    private val loading = StudioState()
    private val ready =
        StudioState(loading = false, projects = listOf(trip, gigs), videos = listOf(beach, dunes, stage), defaultProject = gigs.id)
    private val noDefault = ready.copy(defaultProject = null)
    private val withFolders = ready.copy(projects = listOf(trip.copy(folder = "Trip"), gigs.copy(folder = "Gigs")))
    private val empty = StudioState(loading = false)

    private fun video(
        id: Long,
        project: Project,
        file: String,
    ) = Video(VideoId(id), project.id, VideoKind.RENDER, file.substringAfterLast('/'), file, null, Millis(1_000), 1920, 1080, "Song")

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

    private val create = StudioCommand.CreateProject(" Holiday ", makeDefault = true)
    private val rename = StudioCommand.RenameProject(trip.id, " Holiday ")
    private val add = StudioCommand.AddVideos(listOf(take))

    private val loadingRows: List<Row> =
        listOf(
            row("a Command while loading waits", loading, command(rename), loading.copy(pending = listOf(rename))),
            row(
                "Commands while loading wait in order",
                loading.copy(pending = listOf(rename)),
                command(add),
                loading.copy(pending = listOf(rename, add)),
            ),
            row(
                "the library is read",
                loading,
                StudioEvent.Loaded(listOf(trip, gigs), listOf(beach, dunes, stage), gigs.id),
                ready,
                Perform(StudioWork.ImportOldVideos),
                Perform(EditWork.ListCuts),
            ),
            row(
                "the save folder known before the library is kept",
                loading.copy(saveFolder = folder),
                StudioEvent.Loaded(listOf(trip), listOf(beach), null),
                StudioState(loading = false, projects = listOf(trip), videos = listOf(beach), saveFolder = folder),
                Perform(StudioWork.ImportOldVideos),
                Perform(EditWork.ListCuts),
                Perform(StudioWork.MakeProjectFolders(TREE)),
            ),
            row(
                "a library whose Projects all have folders makes none",
                loading.copy(saveFolder = folder),
                StudioEvent.Loaded(listOf(trip.copy(folder = "Trip")), listOf(beach), null),
                StudioState(loading = false, projects = listOf(trip.copy(folder = "Trip")), videos = listOf(beach), saveFolder = folder),
                Perform(StudioWork.ImportOldVideos),
                Perform(EditWork.ListCuts),
            ),
            row(
                "what waited runs in order once the library is read",
                loading.copy(pending = listOf(rename, add)),
                StudioEvent.Loaded(listOf(trip, gigs), listOf(beach, dunes, stage), gigs.id),
                ready.copy(projects = listOf(trip.copy(name = "Holiday"), gigs)),
                Perform(StudioWork.RenameProject(trip.id, "Holiday")),
                Perform(StudioWork.AddVideos(gigs.id, listOf(take))),
                Perform(StudioWork.ImportOldVideos),
                Perform(EditWork.ListCuts),
            ),
            row(
                "the old Studio's videos arrive in their own new Project, newest first",
                ready,
                StudioEvent.OldVideosImported(earlier, listOf(old)),
                ready.copy(projects = listOf(earlier, trip, gigs), videos = listOf(beach, dunes, stage, old)),
                Emit(StudioFact.VideosAdded(earlier.id, listOf(old.id))),
            ),
            row(
                "an unreadable library shows empty, reports, and still runs what waited",
                loading.copy(pending = listOf(add, create)),
                StudioEvent.Failed(StudioFailure.LOAD),
                empty,
                ReportFailure("Could not read the Studio library"),
                Emit(StudioFact.ProjectNeeded(listOf(take))),
                Perform(StudioWork.CreateProject("Holiday", true)),
            ),
        )

    private val folderRows: List<Row> =
        listOf(
            row(
                "Settings are read without a save folder",
                loading,
                StudioEvent.SaveFolderChanged(SaveFolder.NotChosen),
                loading.copy(saveFolder = SaveFolder.NotChosen),
            ),
            row(
                "the save folder is picked, and the Projects without a folder get one",
                ready,
                StudioEvent.SaveFolderChanged(folder),
                ready.copy(saveFolder = folder),
                Perform(StudioWork.MakeProjectFolders(TREE)),
            ),
            row(
                "the save folder changes",
                ready.copy(saveFolder = folder),
                StudioEvent.SaveFolderChanged(SaveFolder.Chosen("content://tree/other")),
                ready.copy(saveFolder = SaveFolder.Chosen("content://tree/other")),
                Perform(StudioWork.MakeProjectFolders("content://tree/other")),
            ),
            row(
                "a save folder picked while the library is read waits for it",
                loading,
                StudioEvent.SaveFolderChanged(folder),
                loading.copy(saveFolder = folder),
            ),
            row(
                "a save folder picked when every Project has a folder makes none",
                withFolders,
                StudioEvent.SaveFolderChanged(folder),
                withFolders.copy(saveFolder = folder),
            ),
            row(
                "a save folder taken away makes no folder",
                ready.copy(saveFolder = folder),
                StudioEvent.SaveFolderChanged(SaveFolder.NotChosen),
                ready.copy(saveFolder = SaveFolder.NotChosen),
            ),
            row(
                "the Projects' folders are named",
                ready,
                StudioEvent.ProjectFoldersChanged(mapOf(trip.id to "Trip", gigs.id to "Gigs (2)")),
                withFolders.copy(projects = listOf(trip.copy(folder = "Trip"), gigs.copy(folder = "Gigs (2)"))),
            ),
            row(
                "a folder renamed takes its files' new uris with it, thumbnails too",
                withFolders.copy(videos = listOf(beach.copy(thumbnail = "content://videos/beach.jpg"), dunes, stage)),
                StudioEvent.ProjectFoldersChanged(
                    mapOf(trip.id to "Holiday"),
                    mapOf(
                        beach.file to "content://holiday/beach.mp4",
                        "content://videos/beach.jpg" to "content://holiday/beach.jpg",
                    ),
                ),
                withFolders.copy(
                    projects = listOf(trip.copy(folder = "Holiday"), gigs.copy(folder = "Gigs")),
                    videos =
                        listOf(
                            beach.copy(file = "content://holiday/beach.mp4", thumbnail = "content://holiday/beach.jpg"),
                            dunes,
                            stage,
                        ),
                ),
            ),
            row(
                "a Project's folder that is gone is forgotten, and a moved file is followed",
                withFolders,
                StudioEvent.ProjectFoldersChanged(mapOf(gigs.id to null), mapOf(stage.file to "content://trip/stage.mp4")),
                withFolders.copy(
                    projects = listOf(trip.copy(folder = "Trip"), gigs),
                    videos = listOf(beach, dunes, stage.copy(file = "content://trip/stage.mp4")),
                ),
            ),
        )

    private val projectRows: List<Row> =
        listOf(
            row("a new Project is saved first", ready, command(create), ready, Perform(StudioWork.CreateProject("Holiday", true))),
            row(
                "a new Project need not be the default",
                ready,
                command(StudioCommand.CreateProject("Live", makeDefault = false)),
                ready,
                Perform(StudioWork.CreateProject("Live", false)),
            ),
            row(
                "a new Project is made with its folder in the save folder",
                ready.copy(saveFolder = folder),
                command(create),
                ready.copy(saveFolder = folder),
                Perform(StudioWork.CreateProject("Holiday", true, TREE)),
            ),
            row(
                "a rename renames the Project's folder too",
                ready.copy(saveFolder = folder),
                command(rename),
                ready.copy(projects = listOf(trip.copy(name = "Holiday"), gigs), saveFolder = folder),
                Perform(StudioWork.RenameProject(trip.id, "Holiday", TREE)),
            ),
            row("a blank name creates nothing", ready, command(StudioCommand.CreateProject("  ", true)), ready),
            row(
                "a saved Project shows first and becomes the default when asked",
                ready,
                StudioEvent.ProjectCreated(Project(ProjectId(5), "Holiday", Millis(30)), isDefault = true),
                ready.copy(projects = listOf(Project(ProjectId(5), "Holiday", Millis(30)), trip, gigs), defaultProject = ProjectId(5)),
                Emit(StudioFact.ProjectCreated(ProjectId(5))),
            ),
            row(
                "a saved Project leaves the default alone otherwise",
                ready,
                StudioEvent.ProjectCreated(Project(ProjectId(5), "Live", Millis(30)), isDefault = false),
                ready.copy(projects = listOf(Project(ProjectId(5), "Live", Millis(30)), trip, gigs)),
                Emit(StudioFact.ProjectCreated(ProjectId(5))),
            ),
            row(
                "a rename shows at once and is saved behind",
                ready,
                command(rename),
                ready.copy(projects = listOf(trip.copy(name = "Holiday"), gigs)),
                Perform(StudioWork.RenameProject(trip.id, "Holiday")),
            ),
            row("a blank rename does nothing", ready, command(StudioCommand.RenameProject(trip.id, " ")), ready),
            row("renaming to the same name does nothing", ready, command(StudioCommand.RenameProject(trip.id, "Trip ")), ready),
            row("renaming a Project that is gone does nothing", ready, command(StudioCommand.RenameProject(gone, "X")), ready),
            row(
                "the user chooses the default Project",
                ready,
                command(StudioCommand.SetDefaultProject(trip.id)),
                ready.copy(defaultProject = trip.id),
                Perform(StudioWork.SetDefaultProject(trip.id)),
            ),
            row(
                "a first default Project is chosen",
                noDefault,
                command(StudioCommand.SetDefaultProject(gigs.id)),
                ready,
                Perform(StudioWork.SetDefaultProject(gigs.id)),
            ),
            row("choosing the default again does nothing", ready, command(StudioCommand.SetDefaultProject(gigs.id)), ready),
            row("a Project that is gone cannot be the default", ready, command(StudioCommand.SetDefaultProject(gone)), ready),
        )

    private val deleteRows: List<Row> =
        listOf(
            row(
                "Delete N videos too, yes: the videos and their files go",
                ready,
                command(StudioCommand.DeleteProject(trip.id, VideosOnDelete.Delete)),
                ready.copy(projects = listOf(gigs), videos = listOf(stage)),
                Perform(StudioWork.DeleteProject(trip.id, null, listOf(beach.file, dunes.file))),
            ),
            row(
                "Delete N videos too, no: the videos move and no file is touched",
                ready,
                command(StudioCommand.DeleteProject(trip.id, VideosOnDelete.MoveTo(gigs.id))),
                ready.copy(projects = listOf(gigs), videos = listOf(beach.copy(project = gigs.id), dunes.copy(project = gigs.id), stage)),
                Perform(StudioWork.DeleteProject(trip.id, gigs.id, emptyList())),
            ),
            row(
                "deleting the default Project leaves no default",
                ready,
                command(StudioCommand.DeleteProject(gigs.id, VideosOnDelete.MoveTo(trip.id))),
                ready.copy(projects = listOf(trip), videos = listOf(beach, dunes, stage.copy(project = trip.id)), defaultProject = null),
                Perform(StudioWork.DeleteProject(gigs.id, trip.id, emptyList())),
            ),
            row(
                "an empty Project is deleted whatever was chosen",
                ready.copy(videos = listOf(stage)),
                command(StudioCommand.DeleteProject(trip.id, VideosOnDelete.MoveTo(gone))),
                ready.copy(projects = listOf(gigs), videos = listOf(stage)),
                Perform(StudioWork.DeleteProject(trip.id, null, emptyList())),
            ),
            row(
                "a deleted Project's folder goes from the save folder too",
                ready.copy(saveFolder = folder),
                command(StudioCommand.DeleteProject(trip.id, VideosOnDelete.Delete)),
                ready.copy(projects = listOf(gigs), videos = listOf(stage), saveFolder = folder),
                Perform(StudioWork.DeleteProject(trip.id, null, listOf(beach.file, dunes.file), TREE)),
            ),
            row(
                "videos cannot move to a Project that is gone",
                ready,
                command(StudioCommand.DeleteProject(trip.id, VideosOnDelete.MoveTo(gone))),
                ready,
            ),
            row(
                "videos cannot move into the Project being deleted",
                ready,
                command(StudioCommand.DeleteProject(trip.id, VideosOnDelete.MoveTo(trip.id))),
                ready,
            ),
            row(
                "deleting a Project that is gone does nothing",
                ready,
                command(StudioCommand.DeleteProject(gone, VideosOnDelete.Delete)),
                ready,
            ),
        )

    private val videoRows: List<Row> =
        listOf(
            row(
                "a video moves to another Project",
                ready,
                command(StudioCommand.MoveVideo(beach.id, gigs.id)),
                ready.copy(videos = listOf(beach.copy(project = gigs.id), dunes, stage)),
                Perform(StudioWork.MoveVideo(beach.id, gigs.id)),
            ),
            row(
                "a moved video's file follows it into the other Project's folder",
                ready.copy(saveFolder = folder),
                command(StudioCommand.MoveVideo(beach.id, gigs.id)),
                ready.copy(videos = listOf(beach.copy(project = gigs.id), dunes, stage), saveFolder = folder),
                Perform(StudioWork.MoveVideo(beach.id, gigs.id, TREE)),
            ),
            row("moving a video to its own Project does nothing", ready, command(StudioCommand.MoveVideo(beach.id, trip.id)), ready),
            row("moving a video to a Project that is gone does nothing", ready, command(StudioCommand.MoveVideo(beach.id, gone)), ready),
            row("moving a video that is gone does nothing", ready, command(StudioCommand.MoveVideo(VideoId(99), gigs.id)), ready),
            row(
                "new videos go to the Project picked",
                ready,
                command(StudioCommand.AddVideos(listOf(take), trip.id)),
                ready,
                Perform(StudioWork.AddVideos(trip.id, listOf(take))),
            ),
            row(
                "new videos without a Project go to the default",
                ready,
                command(add),
                ready,
                Perform(StudioWork.AddVideos(gigs.id, listOf(take))),
            ),
            row(
                "new videos for a Project that is gone go to the default",
                ready,
                command(StudioCommand.AddVideos(listOf(take), gone)),
                ready,
                Perform(StudioWork.AddVideos(gigs.id, listOf(take))),
            ),
            row(
                "with no default Project new videos are handed back to ask about",
                noDefault,
                command(add),
                noDefault,
                Emit(StudioFact.ProjectNeeded(listOf(take))),
            ),
            row(
                "a default Project that is gone counts as none",
                ready.copy(projects = listOf(trip), videos = listOf(beach, dunes)),
                command(add),
                ready.copy(projects = listOf(trip), videos = listOf(beach, dunes)),
                Emit(StudioFact.ProjectNeeded(listOf(take))),
            ),
            row("no videos adds nothing", ready, command(StudioCommand.AddVideos(emptyList(), trip.id)), ready),
            row(
                "saved videos show with their ids",
                ready,
                StudioEvent.VideosAdded(gigs.id, listOf(saved)),
                ready.copy(videos = listOf(beach, dunes, stage, saved)),
                Emit(StudioFact.VideosAdded(gigs.id, listOf(saved.id))),
            ),
        )

    private val failureRows: List<Row> =
        listOf(
            row(
                "a change that could not be saved is reported",
                ready,
                StudioEvent.Failed(StudioFailure.SAVE),
                ready,
                ReportFailure(describe(StudioFailure.SAVE)),
            ),
            row(
                "videos that could not be added are reported",
                ready,
                StudioEvent.Failed(StudioFailure.ADD_VIDEOS),
                ready,
                ReportFailure(describe(StudioFailure.ADD_VIDEOS)),
            ),
            row(
                "files that could not be deleted are reported",
                ready,
                StudioEvent.Failed(StudioFailure.DELETE_FILES),
                ready,
                ReportFailure(describe(StudioFailure.DELETE_FILES)),
            ),
            row(
                "a late read failure only reports",
                ready,
                StudioEvent.Failed(StudioFailure.LOAD),
                ready,
                ReportFailure(describe(StudioFailure.LOAD)),
            ),
        )

    @Test
    fun `every row of the transition table`() {
        for (row in loadingRows + folderRows + projectRows + deleteRows + videoRows + failureRows) {
            assertEquals(row.name, row.to, StudioReducer.reduce(row.from, row.event))
        }
    }

    @Test
    fun `every failure has its own wording`() {
        val wordings = StudioFailure.entries.map(::describe)
        assertEquals(StudioFailure.entries.size, wordings.toSet().size)
        assertEquals("Could not save the change to the Studio library", describe(StudioFailure.SAVE))
        assertEquals("Could not bring the earlier Studio's videos into the library", describe(StudioFailure.IMPORT_OLD_VIDEOS))
        assertEquals("Could not add the new videos to the Studio library", describe(StudioFailure.ADD_VIDEOS))
        assertEquals("Some video files could not be deleted", describe(StudioFailure.DELETE_FILES))
        assertEquals("Could not make or change a Project's folder in the Save folder", describe(StudioFailure.PROJECT_FOLDER))
    }

    @Test
    fun `the first-run step is due only once Settings say no folder is picked`() {
        assertFalse(loading.needsSetup)
        assertTrue(loading.copy(saveFolder = SaveFolder.NotChosen).needsSetup)
        assertFalse(loading.copy(saveFolder = folder).needsSetup)
    }

    @Test
    fun `the library lists a Project's videos`() {
        assertEquals(listOf(beach, dunes), ready.videosIn(trip.id))
        assertEquals(emptyList<Video>(), ready.videosIn(gone))
    }
}

private const val TREE = "content://tree/videos"
