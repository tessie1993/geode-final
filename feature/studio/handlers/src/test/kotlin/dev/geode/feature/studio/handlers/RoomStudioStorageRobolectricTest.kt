package dev.geode.feature.studio.handlers

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.geode.engine.types.Millis
import dev.geode.feature.studio.api.NewVideo
import dev.geode.feature.studio.api.VideoKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Studio's storage on a real Room database (Robolectric's SQLite). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RoomStudioStorageRobolectricTest {
    private val database =
        Room
            .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StudioDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    private val storage = RoomStudioStorage(database.dao())

    private val take =
        NewVideo(
            VideoKind.RECORDING,
            "take.mp4",
            "content://tree/videos/take.mp4",
            "content://thumb",
            Millis(3_000),
            1080,
            1920,
            "Teardrop",
        )

    @After
    fun close() = database.close()

    @Test
    fun `a new library has no Project and no default`() =
        runBlocking {
            assertEquals(emptyList<Any>(), storage.projects())
            assertEquals(null, storage.defaultProject())
        }

    @Test
    fun `Projects and their videos are saved, renamed and moved, with every field kept`() =
        runBlocking {
            val trip = storage.createProject("Trip", Millis(1), makeDefault = true)
            val gigs = storage.createProject("Gigs", Millis(2), makeDefault = false)
            assertEquals(listOf(gigs, trip), storage.projects())
            assertEquals(trip.id, storage.defaultProject())

            val video = storage.addVideos(trip.id, listOf(take)).single()
            assertEquals(listOf(video), storage.videos())
            assertEquals(
                take,
                NewVideo(video.kind, video.name, video.file, video.thumbnail, video.length, video.width, video.height, video.song),
            )

            storage.renameProject(trip.id, "Holiday")
            storage.moveVideo(video.id, gigs.id)
            storage.setDefaultProject(gigs.id)
            assertEquals("Holiday", storage.projects().last().name)
            assertEquals(gigs.id, storage.videos().single().project)
            assertEquals(gigs.id, storage.defaultProject())
        }

    @Test
    fun `deleting a Project moves its videos or forgets them, and clears it as the default`() =
        runBlocking {
            val trip = storage.createProject("Trip", Millis(1), makeDefault = true)
            val gigs = storage.createProject("Gigs", Millis(2), makeDefault = false)
            storage.addVideos(trip.id, listOf(take))

            storage.deleteProject(trip.id, moveTo = gigs.id)
            assertEquals(listOf(gigs), storage.projects())
            assertEquals(gigs.id, storage.videos().single().project)
            assertEquals(null, storage.defaultProject())

            storage.setDefaultProject(gigs.id)
            storage.deleteProject(gigs.id, moveTo = null)
            assertEquals(emptyList<Any>(), storage.projects())
            assertEquals(emptyList<Any>(), storage.videos())
            assertEquals(null, storage.defaultProject())
        }
}
