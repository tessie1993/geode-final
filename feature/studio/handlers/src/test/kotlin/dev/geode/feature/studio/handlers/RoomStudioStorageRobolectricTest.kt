package dev.geode.feature.studio.handlers

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.geode.engine.types.Millis
import dev.geode.feature.studio.api.UNSORTED_NAME
import dev.geode.feature.studio.api.VideoKind
import dev.geode.feature.studio.core.OldVideo
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

    @After
    fun close() = database.close()

    @Test
    fun `Projects and their videos are saved, renamed, moved and deleted`() =
        runBlocking {
            val unsorted = storage.createProject(UNSORTED_NAME, null, Millis(1))
            val trip = storage.createProject("Trip", "content://tree/trip", Millis(2))
            assertEquals(listOf(trip, unsorted), storage.projects())

            val video = storage.addOldVideos(unsorted.id, listOf(OldVideo("content://v/1", "1.mp4", Millis(3_000), 1080, 1920))).single()
            assertEquals(VideoKind.RENDER, video.kind)
            assertEquals(listOf(video), storage.videos())

            storage.renameProject(trip.id, "Holiday")
            storage.moveVideo(video.id, trip.id)
            assertEquals("Holiday", storage.projects().first().name)
            assertEquals(trip.id, storage.videos().single().project)

            storage.deleteProject(trip.id)
            assertEquals(listOf(unsorted), storage.projects())
            assertEquals(emptyList<Any>(), storage.videos())
        }
}
