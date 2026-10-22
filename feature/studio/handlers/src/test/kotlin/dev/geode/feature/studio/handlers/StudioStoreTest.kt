package dev.geode.feature.studio.handlers

import dev.geode.engine.types.Millis
import dev.geode.feature.studio.api.FilesOnDelete
import dev.geode.feature.studio.api.Project
import dev.geode.feature.studio.api.ProjectId
import dev.geode.feature.studio.api.StudioCommand
import dev.geode.feature.studio.api.StudioFact
import dev.geode.feature.studio.api.UNSORTED_NAME
import dev.geode.feature.studio.api.Video
import dev.geode.feature.studio.api.VideoId
import dev.geode.feature.studio.api.VideoKind
import dev.geode.feature.studio.core.OldVideo
import dev.geode.kernel.FeatureFailure
import dev.geode.kernel.inPlaceRuntime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Studio end to end through its store, over fake storage, old videos and files. */
@OptIn(ExperimentalCoroutinesApi::class)
class StudioStoreTest {
    private val storage = FakeStudioStorage()
    private val files = FakeProjectFiles()
    private var old = listOf(OldVideo("content://media/external/video/media/7", "old.mp4", Millis(9_000), 1920, 1080))
    private val failures = mutableListOf<FeatureFailure>()

    private fun store() = studioStore(StudioSources(storage, { old }, files, { Millis(500) }), inPlaceRuntime(failures = failures))

    @Test
    fun `old Studio videos land in a new Unsorted Project, once`() {
        val studio = store()

        val unsorted = studio.state.value.projects.single()
        assertEquals(UNSORTED_NAME, unsorted.name)
        assertEquals(null, unsorted.folder)
        assertEquals(listOf(old.single().file), studio.state.value.videosIn(unsorted.id).map { it.file })

        // Read again: nothing new to import.
        val again = store()
        assertEquals(1, again.state.value.projects.size)
        assertEquals(1, again.state.value.videos.size)
    }

    @Test
    fun `a new Project is saved with its folder and announced`() =
        runTest {
            old = emptyList()
            val studio = store()
            val facts = mutableListOf<StudioFact>()
            val collecting = launch(UnconfinedTestDispatcher(testScheduler)) { studio.events.collect { facts += it } }

            studio.send(StudioCommand.CreateProject("Trip", "content://tree/trip"))

            val trip = studio.state.value.projects.single()
            assertEquals(Project(trip.id, "Trip", "content://tree/trip", Millis(500)), trip)
            assertEquals(listOf(StudioFact.ProjectCreated(trip.id)), facts)
            assertEquals(listOf(trip), storage.projects())
            collecting.cancel()
        }

    @Test
    fun `rename and move are saved`() {
        val studio = store()
        val unsorted = studio.state.value.projects.single()
        studio.send(StudioCommand.CreateProject("Trip", "content://tree/trip"))
        val trip = studio.state.value.projects.first()
        val video = studio.state.value.videos.single()

        studio.send(StudioCommand.RenameProject(unsorted.id, "Old exports"))
        studio.send(StudioCommand.MoveVideo(video.id, trip.id))

        assertEquals("Old exports", storage.projectsById.getValue(unsorted.id).name)
        assertEquals(trip.id, storage.videosById.getValue(video.id).project)
        assertEquals(studio.state.value.videos, storage.videosById.values.toList())
    }

    @Test
    fun `delete keeping the files forgets the Project and touches no file`() {
        val studio = store()
        val unsorted = studio.state.value.projects.single()

        studio.send(StudioCommand.DeleteProject(unsorted.id, FilesOnDelete.KEEP))

        assertTrue(storage.projectsById.isEmpty())
        assertTrue(storage.videosById.isEmpty())
        assertEquals(emptyList<String>(), files.deleted)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `delete with the files deletes them and gives up the folder`() {
        old = emptyList()
        val studio = store()
        studio.send(StudioCommand.CreateProject("Trip", "content://tree/trip"))
        val trip = studio.state.value.projects.single()
        storage.videosById[VideoId(50)] = video(50, trip.id, "content://tree/trip/a.mp4")
        val reread = store()

        reread.send(StudioCommand.DeleteProject(trip.id, FilesOnDelete.DELETE))

        assertEquals(listOf("content://tree/trip/a.mp4"), files.deleted)
        assertEquals(listOf("content://tree/trip"), files.released)
        assertTrue(storage.projectsById.isEmpty())
    }

    @Test
    fun `files that cannot be deleted are reported`() {
        val studio = store()
        files.refuse = true

        studio.send(StudioCommand.DeleteProject(studio.state.value.projects.single().id, FilesOnDelete.DELETE))

        assertEquals(listOf("Some video files could not be deleted"), failures.map { it.message })
        assertEquals(STUDIO, failures.single().feature)
    }

    @Test
    fun `a Project that cannot be forgotten keeps its files`() {
        val studio = store()
        storage.broken = true

        studio.send(StudioCommand.DeleteProject(studio.state.value.projects.single().id, FilesOnDelete.DELETE))

        assertEquals(emptyList<String>(), files.deleted)
        assertEquals(listOf("Could not save the change to the Studio library"), failures.map { it.message })
    }

    @Test
    fun `a library that cannot be read shows empty and is reported`() {
        storage.broken = true

        val studio = store()

        assertFalse(studio.state.value.loading)
        assertTrue(studio.state.value.projects.isEmpty())
        assertEquals(listOf("Could not read the Studio library"), failures.map { it.message })
    }

    @Test
    fun `the library shows even when the old videos cannot be listed`() {
        val studio =
            studioStore(
                StudioSources(storage, { error("no media index") }, files, { Millis(1) }),
                inPlaceRuntime(failures = failures),
            )

        assertFalse(studio.state.value.loading)
        assertTrue(failures.isEmpty())
    }

    private fun video(
        id: Long,
        project: ProjectId,
        file: String,
    ) = Video(VideoId(id), project, VideoKind.RECORDING, "a.mp4", file, null, Millis(1), 1080, 1920, "Song")
}

private class FakeStudioStorage : StudioStorage {
    val projectsById = LinkedHashMap<ProjectId, Project>()
    val videosById = LinkedHashMap<VideoId, Video>()
    var broken = false
    private var nextId = 1L

    private fun ensureWorking() = check(!broken) { "storage broken" }

    override suspend fun projects(): List<Project> {
        ensureWorking()
        return projectsById.values.sortedByDescending { it.createdAt }
    }

    override suspend fun videos(): List<Video> {
        ensureWorking()
        return videosById.values.toList()
    }

    override suspend fun createProject(
        name: String,
        folder: String?,
        createdAt: Millis,
    ): Project {
        ensureWorking()
        return Project(ProjectId(nextId++), name, folder, createdAt).also { projectsById[it.id] = it }
    }

    override suspend fun renameProject(
        project: ProjectId,
        name: String,
    ) {
        ensureWorking()
        projectsById[project] = projectsById.getValue(project).copy(name = name)
    }

    override suspend fun deleteProject(project: ProjectId) {
        ensureWorking()
        projectsById.remove(project)
        videosById.values.removeAll { it.project == project }
    }

    override suspend fun moveVideo(
        video: VideoId,
        to: ProjectId,
    ) {
        ensureWorking()
        videosById[video] = videosById.getValue(video).copy(project = to)
    }

    override suspend fun addOldVideos(
        into: ProjectId,
        videos: List<OldVideo>,
    ): List<Video> {
        ensureWorking()
        return videos.map {
            Video(VideoId(nextId++), into, VideoKind.RENDER, it.name, it.file, null, it.length, it.width, it.height, "")
                .also { video -> videosById[video.id] = video }
        }
    }
}

private class FakeProjectFiles : ProjectFiles {
    val deleted = mutableListOf<String>()
    val released = mutableListOf<String>()
    var refuse = false

    override suspend fun delete(file: String): Boolean {
        if (refuse) return false
        deleted += file
        return true
    }

    override suspend fun release(folder: String) {
        released += folder
    }
}
