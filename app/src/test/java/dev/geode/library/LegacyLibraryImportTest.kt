package dev.geode.library

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.library.api.SongEdit
import dev.geode.feature.playback.api.Track
import dev.geode.playback.MemoryPlaybackStorage
import dev.geode.ui.DeviceTrack
import dev.geode.ui.LibraryTrack
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the old Library kept moves into the Library's and Playback's own storage once, and nothing they have is lost (#17). */
class LegacyLibraryImportTest {
    private val a = Track(TrackId("content://media/a"), "Alpha", "Ann", Millis(200_000))
    private val b = Track(TrackId("content://media/b"), "Beta", "Bob", Millis(180_000))
    private val c = Track(TrackId("content://media/c"), "Gamma", "Cid", Millis(160_000))
    private val legacy =
        LegacyLibrary(
            playlists = listOf(LegacyPlaylist("Evening", listOf(b, a, c)), LegacyPlaylist("Morning", listOf(c))),
            favourites = listOf(b.id, a.id),
            edits = mapOf(a.id to SongEdit("Alpha (live)", "Ann", "Live")),
        )

    private suspend fun MemoryPlaybackStorage.names() = playlists.first().map { it.name }

    @Test
    fun `the playlists, favourites and edits come across, each playlist's songs in their order`() =
        runTest {
            val library = MemoryLibraryStorage()
            val playback = MemoryPlaybackStorage()

            LegacyLibraryImport.into(library, playback, legacy, now = Millis(9_000))

            // Playback lists the newest first: the old Library's order is kept.
            assertEquals(listOf("Evening", "Morning"), playback.names())
            val evening = playback.playlists.first().first { it.name == "Evening" }
            assertEquals(listOf(b, a, c), playback.playlistTracks(evening.id))
            assertEquals(setOf(a.id, b.id), library.favourites)
            assertEquals(legacy.edits, library.edits)
        }

    @Test
    fun `what the Library and Playback already keep is never written over`() =
        runTest {
            val library = MemoryLibraryStorage()
            val playback = MemoryPlaybackStorage()
            playback.createPlaylist("Evening", listOf(c), Millis(1))
            val mine = SongEdit("Mine", "Me", "Mine")
            library.edits[a.id] = mine

            LegacyLibraryImport.into(library, playback, legacy, now = Millis(9_000))

            val evening = playback.playlists.first().single { it.name == "Evening" }
            assertEquals(listOf(c), playback.playlistTracks(evening.id))
            assertEquals(mine, library.edits[a.id])
        }

    @Test
    fun `running it again changes nothing`() =
        runTest {
            val library = MemoryLibraryStorage()
            val playback = MemoryPlaybackStorage()

            LegacyLibraryImport.into(library, playback, legacy, now = Millis(9_000))
            LegacyLibraryImport.into(library, playback, legacy, now = Millis(10_000))

            assertEquals(listOf("Evening", "Morning"), playback.names())
            assertEquals(setOf(a.id, b.id), library.favourites)
            assertEquals(legacy.edits, library.edits)
        }

    @Test
    fun `an old entry is an edit only for a song the phone has, and only where it changes something`() {
        val device =
            mapOf(
                a.id to DeviceTrack(a.id, "Alpha", "Ann", "First", "/music", a.duration),
                b.id to DeviceTrack(b.id, "Beta", "Bob", "Second", "/music", b.duration),
            )
        val old =
            listOf(
                // A new title; the blank album keeps the index's.
                LibraryTrack(uri = a.id, title = "Alpha (live)", artist = "Ann"),
                // Just listed, as the file says.
                LibraryTrack(uri = b.id, title = "Beta", artist = "Bob", album = "Second"),
                // A file the phone's index does not know.
                LibraryTrack(uri = TrackId("content://docs/x"), title = "Elsewhere"),
            )

        assertEquals(mapOf(a.id to SongEdit("Alpha (live)", "Ann", "First")), LegacyLibraryImport.editsOf(old, device))
    }

    @Test
    fun `the import runs once, before the Library first reads its index`() =
        runTest {
            val stored = MemoryLibraryStorage()
            var imported = false
            var reads = 0
            val storage =
                ImportingLibraryStorage(
                    stored = stored,
                    playback = MemoryPlaybackStorage(),
                    alreadyImported = { imported },
                    markImported = { imported = true },
                    legacy = {
                        reads++
                        legacy
                    },
                    now = { Millis(9_000) },
                )

            assertEquals(setOf(a.id, b.id), storage.read().favourites)
            storage.read()

            assertEquals(1, reads)
            assertEquals(true, imported)
        }

    @Test
    fun `an install that already imported reads the old files no more`() =
        runTest {
            var reads = 0
            val storage =
                ImportingLibraryStorage(
                    stored = MemoryLibraryStorage(),
                    playback = MemoryPlaybackStorage(),
                    alreadyImported = { true },
                    markImported = {},
                    legacy = {
                        reads++
                        legacy
                    },
                    now = { Millis(9_000) },
                )

            assertEquals(emptySet<TrackId>(), storage.read().favourites)
            assertEquals(0, reads)
        }
}
