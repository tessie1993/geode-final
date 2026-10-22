package dev.geode.playback

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.playback.api.ListeningOptions
import dev.geode.feature.playback.api.Queue
import dev.geode.feature.playback.api.RepeatMode
import dev.geode.feature.playback.api.Track
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
            plays = listOf(a to Millis(1_000), b to Millis(2_000)),
            resumePoints = mapOf(a.id to Millis(600_000)),
            options = ListeningOptions(shuffle = true, repeat = RepeatMode.ALL, crossfade = Millis(3_000), gapless = false),
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
}
