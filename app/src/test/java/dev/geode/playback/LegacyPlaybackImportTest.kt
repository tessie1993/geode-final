package dev.geode.playback

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.playback.api.ListeningOptions
import dev.geode.feature.playback.api.Queue
import dev.geode.feature.playback.api.RepeatMode
import dev.geode.feature.playback.api.Track
import dev.geode.feature.playback.handlers.HISTORY_LIMIT
import dev.geode.feature.playback.handlers.SavedSession
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the old player kept moves into Playback's own storage once, and nothing Playback has is lost (#17, ADR-0004). */
class LegacyPlaybackImportTest {
    private val a = Track(TrackId("content://media/a"), "Alpha", "Ann", Millis(200_000))
    private val b = Track(TrackId("content://media/b"), "Beta", "Bob", Millis(180_000))
    private val legacy =
        LegacyPlayback(
            session = SavedSession(Queue(listOf(a, b), 1), Millis(42_000)),
            plays = listOf(LegacyPlay(a, Millis(1_000)), LegacyPlay(b, Millis(2_000))),
            resumePoints = mapOf(a.id to Millis(600_000)),
            options = ListeningOptions(shuffle = true, repeat = RepeatMode.ALL),
        )

    @Test
    fun `the queue, the history, the resume points and the listening choices come across`() =
        runTest {
            val storage = MemoryPlaybackStorage()

            LegacyPlaybackImport.into(storage, legacy, now = Millis(9_000))

            assertEquals(legacy.session, storage.session)
            // Recorded oldest first, so the most recent play ends up first.
            assertEquals(listOf(b.id, a.id), storage.plays.value.map { it.track.id })
            assertEquals(listOf(Millis(2_000), Millis(1_000)), storage.plays.value.map { it.playedAt })
            assertEquals(mapOf(a.id to Millis(600_000)), storage.points.value)
            assertEquals(legacy.options, storage.listening)
        }

    @Test
    fun `what Playback already keeps is never written over`() =
        runTest {
            val storage = MemoryPlaybackStorage()
            val mine = SavedSession(Queue(listOf(a), 0), Millis(5))
            storage.session = mine
            storage.listening = ListeningOptions()

            LegacyPlaybackImport.into(storage, legacy, now = Millis(9_000))

            assertEquals(mine, storage.session)
            assertEquals(ListeningOptions(), storage.listening)
        }

    @Test
    fun `nothing kept means nothing written`() =
        runTest {
            val storage = MemoryPlaybackStorage()

            LegacyPlaybackImport.into(storage, LegacyPlayback(null, emptyList(), emptyMap(), null), now = Millis(9_000))

            assertEquals(null, storage.session)
            assertEquals(emptyList<Any>(), storage.plays.value)
            assertEquals(null, storage.listening)
        }

    @Test
    fun `the old repeat numbers read as Playback's repeat`() {
        assertEquals(RepeatMode.OFF, LegacyPlaybackImport.repeatOf(0))
        assertEquals(RepeatMode.ONE, LegacyPlaybackImport.repeatOf(1))
        assertEquals(RepeatMode.ALL, LegacyPlaybackImport.repeatOf(2))
        assertEquals(RepeatMode.OFF, LegacyPlaybackImport.repeatOf(7))
    }

    @Test
    fun `the import runs once, before Playback first reads where it was left off`() =
        runTest {
            val stored = MemoryPlaybackStorage()
            var imported = false
            var reads = 0
            val storage =
                ImportingPlaybackStorage(
                    stored = stored,
                    alreadyImported = { imported },
                    markImported = { imported = true },
                    legacy = {
                        reads++
                        legacy
                    },
                    now = { Millis(9_000) },
                )

            assertEquals(legacy.session, storage.loadSession())
            assertEquals(legacy.options, storage.loadListening())
            assertEquals(legacy.session, storage.loadSession())

            assertEquals(1, reads)
            assertEquals(true, imported)
        }

    @Test
    fun `an install that already imported reads the old files no more`() =
        runTest {
            var reads = 0
            val storage =
                ImportingPlaybackStorage(
                    stored = MemoryPlaybackStorage(),
                    alreadyImported = { true },
                    markImported = {},
                    legacy = {
                        reads++
                        legacy
                    },
                    now = { Millis(9_000) },
                )

            assertEquals(null, storage.loadSession())
            assertEquals(0, reads)
        }

    @Test
    fun `Playback's storage keeps no crossfade or gapless, which are Settings'`() =
        runTest {
            val stored = MemoryPlaybackStorage()
            val storage = ImportingPlaybackStorage(stored, { true }, {}, { legacy }, { Millis(9_000) })
            stored.listening = ListeningOptions(shuffle = true, crossfade = Millis(5_000), gapless = false)

            assertEquals(ListeningOptions(shuffle = true), storage.loadListening())
            storage.saveListening(ListeningOptions(repeat = RepeatMode.ONE, crossfade = Millis(2_000), gapless = false))
            assertEquals(ListeningOptions(repeat = RepeatMode.ONE), stored.listening)
        }

    @Test
    fun `a song played several times comes across as that many plays, the last when it was last played`() {
        val plays = LegacyPlaybackImport.playsOf(listOf(LegacyPlay(a, Millis(5_000), count = 3), LegacyPlay(b, Millis(4_000))))

        assertEquals(listOf(b to Millis(4_000), a to Millis(4_998), a to Millis(4_999), a to Millis(5_000)), plays)
    }

    @Test
    fun `only the latest plays Playback keeps come across`() {
        val plays = LegacyPlaybackImport.playsOf(listOf(LegacyPlay(a, Millis(1_000_000), count = HISTORY_LIMIT + 5)))

        assertEquals(HISTORY_LIMIT, plays.size)
        assertEquals(a to Millis(1_000_000), plays.last())
    }

    @Test
    fun `an import run again after stopping part way writes no play twice`() =
        runTest {
            val storage = MemoryPlaybackStorage()
            storage.recordPlay(a, Millis(1_000))

            LegacyPlaybackImport.into(storage, legacy, now = Millis(9_000))
            LegacyPlaybackImport.into(storage, legacy, now = Millis(9_000))

            assertEquals(listOf(b.id to Millis(2_000), a.id to Millis(1_000)), storage.plays.value.map { it.track.id to it.playedAt })
        }

    @Test
    fun `the old resume points come across in the same order every time`() {
        val c = TrackId("content://media/c")
        val points = mapOf(c to Millis(3), a.id to Millis(1), b.id to Millis(2))

        assertEquals(listOf(a.id to Millis(1), b.id to Millis(2), c to Millis(3)), LegacyPlaybackImport.resumeOrder(points))
    }
}
