package dev.geode.feature.playback.handlers

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.playback.api.PlayedTrack
import dev.geode.feature.playback.api.PlaylistSummary
import dev.geode.feature.playback.api.Queue
import dev.geode.feature.playback.api.Track
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Playback's storage on a real Room database (Robolectric's SQLite). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RoomPlaybackStorageRobolectricTest {
    private val a = Track(TrackId("content://a"), "Alpha", "Ann", Millis(200_000))
    private val b = Track(TrackId("content://b"), "Beta", "Bob", Millis(180_000))
    private val database =
        Room
            .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PlaybackDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    private val storage = RoomPlaybackStorage(database.dao())

    @After
    fun close() = database.close()

    @Test
    fun `playlists keep their tracks in order and go with them`() =
        runBlocking {
            val older = storage.createPlaylist("Morning", listOf(a), Millis(1))
            val newer = storage.createPlaylist("Evening", listOf(b, a), Millis(2))

            assertEquals(listOf(PlaylistSummary(newer, "Evening", 2), PlaylistSummary(older, "Morning", 1)), storage.playlists.first())
            assertEquals(listOf(b, a), storage.playlistTracks(newer))

            storage.deletePlaylist(newer)

            assertEquals(listOf(PlaylistSummary(older, "Morning", 1)), storage.playlists.first())
            assertEquals(emptyList<Track>(), storage.playlistTracks(newer))
        }

    @Test
    fun `the history lists the latest plays first and keeps only so many`() =
        runBlocking {
            repeat(HISTORY_LIMIT + 1) { storage.recordPlay(if (it % 2 == 0) a else b, Millis(it.toLong())) }

            val history = storage.history.first()

            assertEquals(HISTORY_LIMIT, history.size)
            assertEquals(PlayedTrack(a, Millis(HISTORY_LIMIT.toLong())), history.first())
            assertEquals(PlayedTrack(b, Millis(1)), history.last())
        }

    @Test
    fun `the saved session replaces the one before`() =
        runBlocking {
            assertNull(storage.loadSession())

            storage.saveSession(Queue(listOf(a, b), 1), Millis(5_000))
            storage.saveSession(Queue(listOf(b), 0), Millis(7_000))

            assertEquals(SavedSession(Queue(listOf(b), 0), Millis(7_000)), storage.loadSession())
        }
}
