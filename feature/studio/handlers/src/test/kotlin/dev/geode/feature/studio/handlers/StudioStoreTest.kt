package dev.geode.feature.studio.handlers

import dev.geode.engine.types.Millis
import dev.geode.feature.settings.api.FolderSettings
import dev.geode.feature.settings.api.FolderUri
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.settings.api.SettingsCommand
import dev.geode.feature.settings.api.SettingsState
import dev.geode.feature.settings.api.SettingsValues
import dev.geode.feature.studio.api.NewVideo
import dev.geode.feature.studio.api.Project
import dev.geode.feature.studio.api.ProjectId
import dev.geode.feature.studio.api.SaveFolder
import dev.geode.feature.studio.api.Studio
import dev.geode.feature.studio.api.StudioCommand
import dev.geode.feature.studio.api.StudioFact
import dev.geode.feature.studio.api.Video
import dev.geode.feature.studio.api.VideoId
import dev.geode.feature.studio.api.VideoKind
import dev.geode.feature.studio.api.VideosOnDelete
import dev.geode.kernel.FeatureFailure
import dev.geode.kernel.inPlaceRuntime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Studio end to end through its store, over fake storage, Settings and files. */
@OptIn(ExperimentalCoroutinesApi::class)
class StudioStoreTest {
    private val storage = FakeStudioStorage()
    private val files = FakeVideoFiles()
    private val settings = FakeSettings()
    private val failures = mutableListOf<FeatureFailure>()
    private val take = NewVideo(VideoKind.RECORDING, "take.mp4", "content://tree/videos/take.mp4", null, Millis(4_000), 1080, 1920, "Song")

    private fun store() =
        studioStore(
            StudioSources(storage, saveFolderOf(settings), files, {
                Millis(500)
            }),
            inPlaceRuntime(failures = failures),
        )

    @Test
    fun `nothing is made up on first start, no Project and no default`() {
        val studio = store()

        assertFalse(studio.state.value.loading)
        assertTrue(
            studio.state.value.projects
                .isEmpty(),
        )
        assertEquals(null, studio.state.value.defaultProject)
        assertTrue(storage.projectsById.isEmpty())
    }

    @Test
    fun `the save folder follows Settings and the first-run step is due until one is picked`() {
        val studio = store()
        assertEquals(SaveFolder.Unknown, studio.state.value.saveFolder)

        settings.ready(null)
        assertEquals(SaveFolder.NotChosen, studio.state.value.saveFolder)
        assertTrue(studio.state.value.needsSetup)

        settings.ready(FolderUri("content://tree/videos"))
        assertEquals(SaveFolder.Chosen("content://tree/videos"), studio.state.value.saveFolder)
        assertFalse(studio.state.value.needsSetup)
    }

    @Test
    fun `a new Project is saved, made the default when asked, and announced`() =
        runTest {
            val studio = store()
            val facts = mutableListOf<StudioFact>()
            val collecting = launch(UnconfinedTestDispatcher(testScheduler)) { studio.events.collect { facts += it } }

            studio.send(StudioCommand.CreateProject("Trip", makeDefault = true))

            val trip =
                studio.state.value.projects
                    .single()
            assertEquals(Project(trip.id, "Trip", Millis(500)), trip)
            assertEquals(trip.id, studio.state.value.defaultProject)
            assertEquals(trip.id, storage.defaultProject())
            assertEquals(listOf(StudioFact.ProjectCreated(trip.id)), facts)
            collecting.cancel()
        }

    @Test
    fun `the default Project the user chooses is kept by Studio across a restart`() {
        val studio = store()
        studio.send(StudioCommand.CreateProject("Trip", makeDefault = false))
        studio.send(StudioCommand.CreateProject("Gigs", makeDefault = false))
        val trip =
            studio.state.value.projects
                .single { it.name == "Trip" }

        studio.send(StudioCommand.SetDefaultProject(trip.id))

        assertEquals(trip.id, store().state.value.defaultProject)
    }

    @Test
    fun `videos without a Project go to the default and are announced with their ids`() =
        runTest {
            val studio = store()
            studio.send(StudioCommand.CreateProject("Trip", makeDefault = true))
            val facts = mutableListOf<StudioFact>()
            val collecting = launch(UnconfinedTestDispatcher(testScheduler)) { studio.events.collect { facts += it } }

            studio.send(StudioCommand.AddVideos(listOf(take)))

            val added =
                studio.state.value.videos
                    .single()
            assertEquals(studio.state.value.defaultProject, added.project)
            assertEquals(take.file, added.file)
            assertEquals(listOf(added), storage.videos())
            assertEquals(listOf(StudioFact.VideosAdded(added.project, listOf(added.id))), facts)
            collecting.cancel()
        }

    @Test
    fun `videos without a Project and no default are handed back, not dropped`() =
        runTest {
            val studio = store()
            val facts = mutableListOf<StudioFact>()
            val collecting = launch(UnconfinedTestDispatcher(testScheduler)) { studio.events.collect { facts += it } }

            studio.send(StudioCommand.AddVideos(listOf(take)))

            assertEquals(listOf(StudioFact.ProjectNeeded(listOf(take))), facts)
            assertTrue(storage.videosById.isEmpty())
            collecting.cancel()
        }

    @Test
    fun `rename and move are saved`() {
        val studio = store()
        studio.send(StudioCommand.CreateProject("Trip", makeDefault = true))
        studio.send(StudioCommand.CreateProject("Gigs", makeDefault = false))
        val (gigs, trip) = studio.state.value.projects
        studio.send(StudioCommand.AddVideos(listOf(take)))
        val video =
            studio.state.value.videos
                .single()

        studio.send(StudioCommand.RenameProject(trip.id, "Holiday"))
        studio.send(StudioCommand.MoveVideo(video.id, gigs.id))

        assertEquals("Holiday", storage.projectsById.getValue(trip.id).name)
        assertEquals(gigs.id, storage.videosById.getValue(video.id).project)
        assertEquals(studio.state.value.videos, storage.videosById.values.toList())
    }

    @Test
    fun `Delete N videos too, yes, deletes the files with the Project`() =
        runTest {
            val (studio, trip) = projectWithTake()

            studio.send(StudioCommand.DeleteProject(trip, VideosOnDelete.Delete))

            assertEquals(listOf(take.file), files.deleted)
            assertTrue(storage.projectsById.isEmpty())
            assertTrue(storage.videosById.isEmpty())
            assertEquals(null, storage.defaultProject())
            assertTrue(failures.isEmpty())
        }

    @Test
    fun `Delete N videos too, no, moves them and touches no file`() {
        val (studio, trip) = projectWithTake()
        studio.send(StudioCommand.CreateProject("Gigs", makeDefault = false))
        val gigs =
            studio.state.value.projects
                .first()
                .id

        studio.send(StudioCommand.DeleteProject(trip, VideosOnDelete.MoveTo(gigs)))

        assertEquals(emptyList<String>(), files.deleted)
        assertEquals(listOf(gigs), storage.projectsById.keys.toList())
        assertEquals(
            gigs,
            storage.videosById.values
                .single()
                .project,
        )
        assertEquals(studio.state.value.videos, storage.videosById.values.toList())
    }

    @Test
    fun `files that cannot be deleted are reported`() {
        val (studio, trip) = projectWithTake()
        files.refuse = true

        studio.send(StudioCommand.DeleteProject(trip, VideosOnDelete.Delete))

        assertEquals(listOf("Some video files could not be deleted"), failures.map { it.message })
        assertEquals(STUDIO, failures.single().feature)
    }

    @Test
    fun `a Project that cannot be forgotten keeps its files`() {
        val (studio, trip) = projectWithTake()
        storage.broken = true

        studio.send(StudioCommand.DeleteProject(trip, VideosOnDelete.Delete))

        assertEquals(emptyList<String>(), files.deleted)
        assertEquals(listOf("Could not save the change to the Studio library"), failures.map { it.message })
    }

    @Test
    fun `videos that cannot be saved are reported`() {
        val studio = store()
        studio.send(StudioCommand.CreateProject("Trip", makeDefault = true))
        storage.broken = true

        studio.send(StudioCommand.AddVideos(listOf(take)))

        assertEquals(listOf("Could not add the new videos to the Studio library"), failures.map { it.message })
    }

    @Test
    fun `changes that cannot be saved are reported`() {
        val studio = store()
        studio.send(StudioCommand.CreateProject("Trip", makeDefault = false))
        studio.send(StudioCommand.CreateProject("Gigs", makeDefault = false))
        val (gigs, trip) = studio.state.value.projects
        storage.broken = true

        studio.send(StudioCommand.CreateProject("Live", makeDefault = false))
        studio.send(StudioCommand.RenameProject(trip.id, "Holiday"))
        studio.send(StudioCommand.SetDefaultProject(gigs.id))

        assertEquals(3, failures.count { it.message == "Could not save the change to the Studio library" })
    }

    @Test
    fun `a library that cannot be read shows empty and is reported`() {
        storage.broken = true

        val studio = store()

        assertFalse(studio.state.value.loading)
        assertTrue(
            studio.state.value.projects
                .isEmpty(),
        )
        assertEquals(listOf("Could not read the Studio library"), failures.map { it.message })
    }

    @Test
    fun `Settings still loading reads as an unknown save folder`() =
        runTest {
            assertEquals(SaveFolder.Unknown, saveFolderOf(FakeSettings()).first())
        }

    private fun projectWithTake(): Pair<Studio, ProjectId> {
        val studio = store()
        studio.send(StudioCommand.CreateProject("Trip", makeDefault = true))
        studio.send(StudioCommand.AddVideos(listOf(take)))
        return studio to
            studio.state.value.projects
                .single()
                .id
    }
}

/** Settings as Studio sees it: only its state is read. */
private class FakeSettings : Settings {
    private val mutable = MutableStateFlow<SettingsState>(SettingsState.Loading())
    override val state: StateFlow<SettingsState> = mutable
    override val events: Flow<Nothing> = emptyFlow()

    override fun send(command: SettingsCommand) = Unit

    fun ready(videoFolder: FolderUri?) {
        mutable.value = SettingsState.Ready(SettingsValues(folders = FolderSettings(videoFolder = videoFolder)))
    }
}

private class FakeStudioStorage : StudioStorage {
    val projectsById = LinkedHashMap<ProjectId, Project>()
    val videosById = LinkedHashMap<VideoId, Video>()
    private var default: ProjectId? = null
    var broken = false
    private var nextId = 1L

    private fun ensureWorking() = check(!broken) { "storage broken" }

    override suspend fun projects(): List<Project> {
        ensureWorking()
        return projectsById.values.sortedByDescending { it.createdAt.value * 1_000 + it.id.value }
    }

    override suspend fun videos(): List<Video> {
        ensureWorking()
        return videosById.values.toList()
    }

    override suspend fun defaultProject(): ProjectId? {
        ensureWorking()
        return default
    }

    override suspend fun createProject(
        name: String,
        createdAt: Millis,
        makeDefault: Boolean,
    ): Project {
        ensureWorking()
        val project = Project(ProjectId(nextId++), name, createdAt).also { projectsById[it.id] = it }
        if (makeDefault) default = project.id
        return project
    }

    override suspend fun renameProject(
        project: ProjectId,
        name: String,
    ) {
        ensureWorking()
        projectsById[project] = projectsById.getValue(project).copy(name = name)
    }

    override suspend fun setDefaultProject(project: ProjectId) {
        ensureWorking()
        default = project
    }

    override suspend fun deleteProject(
        project: ProjectId,
        moveTo: ProjectId?,
    ) {
        ensureWorking()
        projectsById.remove(project)
        if (moveTo == null) {
            videosById.values.removeAll { it.project == project }
        } else {
            videosById.replaceAll { _, video -> if (video.project == project) video.copy(project = moveTo) else video }
        }
        if (default == project) default = null
    }

    override suspend fun moveVideo(
        video: VideoId,
        to: ProjectId,
    ) {
        ensureWorking()
        videosById[video] = videosById.getValue(video).copy(project = to)
    }

    override suspend fun addVideos(
        project: ProjectId,
        videos: List<NewVideo>,
    ): List<Video> {
        ensureWorking()
        return videos.map {
            Video(VideoId(nextId++), project, it.kind, it.name, it.file, it.thumbnail, it.length, it.width, it.height, it.song)
                .also { video -> videosById[video.id] = video }
        }
    }
}

private class FakeVideoFiles : VideoFiles {
    val deleted = mutableListOf<String>()
    var refuse = false

    override suspend fun delete(file: String): Boolean {
        if (refuse) return false
        deleted += file
        return true
    }
}
