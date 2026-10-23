package dev.geode.feature.library.handlers

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.library.api.Song
import dev.geode.feature.library.api.SongEdit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Library's storage on a real Room database (Robolectric's SQLite). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RoomLibraryStorageRobolectricTest {
    private val a = song("a", "/music/a.mp3")
    private val b = song("b", "/music/b.mp3")
    private val database =
        Room
            .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    private val storage = RoomLibraryStorage(database.dao())

    @After
    fun close() = database.close()

    @Test
    fun `the index is replaced by what the scan finds, keeping tags already read for files that stayed`() =
        runBlocking {
            storage.replaceSongs(listOf(a, b))
            storage.saveTags(a.copy(composer = "Cole"))
            storage.saveTags(b.copy(composer = "Bach"))

            // b moved, and a new song c arrived; a's file stayed where it was.
            val c = song("c", "/music/c.mp3")
            val index = storage.replaceSongs(listOf(a, b.copy(path = "/music/moved/b.mp3"), c))

            assertEquals(listOf(a.copy(composer = "Cole"), b.copy(path = "/music/moved/b.mp3"), c), index)
            assertEquals(setOf(b.id, c.id), storage.songsWithoutTags().map { it.id }.toSet())
            assertEquals(index.toSet(), storage.read().songs.toSet())
        }

    @Test
    fun `edits show over the index, and survive a scan`() =
        runBlocking {
            storage.replaceSongs(listOf(a))
            storage.saveEdit(a.id, SongEdit("New", "Artist", "Album", "content://art"))

            val edited = a.copy(title = "New", artist = "Artist", album = "Album", artwork = "content://art")
            assertEquals(listOf(edited), storage.read().songs)
            assertEquals(listOf(edited), storage.replaceSongs(listOf(a)))
            assertEquals(mapOf(a.id to SongEdit("New", "Artist", "Album", "content://art")), storage.edits())
            // The tag reader works on the song as the file has it.
            assertEquals(listOf(a), storage.songsWithoutTags())
        }

    @Test
    fun `favourites and excluded folders are kept`() =
        runBlocking {
            storage.setFavourite(a.id, favourite = true)
            storage.setFavourite(b.id, favourite = true)
            storage.setFavourite(a.id, favourite = false)
            storage.saveExcludedFolders(setOf("/ringtones", "/notes"))
            storage.saveExcludedFolders(setOf("/ringtones"))

            val saved = storage.read()
            assertEquals(setOf(b.id), saved.favourites)
            assertEquals(setOf("/ringtones"), saved.excludedFolders)
        }

    private fun song(
        id: String,
        path: String,
    ) = Song(
        id = TrackId("content://$id"),
        title = id.uppercase(),
        artist = "Ann",
        album = "First",
        albumArtist = "",
        genre = "",
        composer = "",
        path = path,
        format = "mp3",
        duration = Millis(100_000),
        addedAt = Millis(1),
    )
}
