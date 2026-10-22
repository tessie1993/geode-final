package dev.geode.feature.playback.handlers

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.playback.api.Bookmark
import dev.geode.feature.playback.api.ListeningOptions
import dev.geode.feature.playback.api.PlayedTrack
import dev.geode.feature.playback.api.PlaylistSummary
import dev.geode.feature.playback.api.Queue
import dev.geode.feature.playback.api.QueueEnd
import dev.geode.feature.playback.api.RepeatMode
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

    @Test
    fun `bookmarks list the newest first and can be deleted`() =
        runBlocking {
            val first = storage.addBookmark(a, Millis(10_000), Millis(1))
            val second = storage.addBookmark(b, Millis(20_000), Millis(2))

            assertEquals(listOf(Bookmark(second, b, Millis(20_000)), Bookmark(first, a, Millis(10_000))), storage.bookmarks.first())

            storage.deleteBookmark(second)

            assertEquals(listOf(Bookmark(first, a, Millis(10_000))), storage.bookmarks.first())
        }

    @Test
    fun `a resume point replaces the one before, is forgotten, and only so many are kept`() =
        runBlocking {
            storage.saveResumePoint(a.id, Millis(10_000), Millis(1))
            storage.saveResumePoint(a.id, Millis(20_000), Millis(2))
            assertEquals(mapOf(a.id to Millis(20_000)), storage.resumePoints.first())

            storage.forgetResumePoint(a.id)
            assertEquals(emptyMap<TrackId, Millis>(), storage.resumePoints.first())

            repeat(RESUME_POINT_LIMIT + 1) { storage.saveResumePoint(TrackId("content://$it"), Millis(30_000), Millis(it.toLong())) }
            val kept = storage.resumePoints.first()
            assertEquals(RESUME_POINT_LIMIT, kept.size)
            assertEquals(false, TrackId("content://0") in kept)
        }

    @Test
    fun `the listening options are saved and read back`() =
        runBlocking {
            assertNull(storage.loadListening())
            val options =
                ListeningOptions(
                    shuffle = true,
                    repeat = RepeatMode.ONE,
                    crossfade = Millis(3_000),
                    gapless = false,
                    queueEnd = QueueEnd.SIMILAR,
                )

            storage.saveListening(options)

            assertEquals(options, storage.loadListening())
        }

    @Test
    fun `a choice from a newer version reads as the default`() =
        runBlocking {
            val newer = ListeningRow(shuffle = false, repeatMode = "SOMETIMES", crossfadeMs = 0, gapless = true, queueEnd = "ASK")
            database.dao().putListening(newer)

            assertEquals(ListeningOptions(), storage.loadListening())
        }

    @Test
    fun `the known songs are the most played first, then the playlists', each once`() =
        runBlocking {
            val c = Track(TrackId("content://c"), "Gamma", "Cy", Millis(240_000))
            storage.recordPlay(a, Millis(1))
            storage.recordPlay(b, Millis(2))
            storage.recordPlay(b, Millis(3))
            storage.createPlaylist("Mix", listOf(c, a), Millis(4))

            assertEquals(listOf(b, a, c), storage.knownTracks())
        }
}
