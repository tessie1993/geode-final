package dev.geode.feature.playback.api

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackApiTest {
    private val a = Track(TrackId("content://a"), "Alpha", "Ann", Millis(200_000))
    private val b = Track(TrackId("content://b"), "Beta", "Bob", Millis(180_000))

    @Test
    fun `a queue knows its current track and its neighbours`() {
        val first = Queue(listOf(a, b), 0)

        assertEquals(a, first.current)
        assertTrue(first.hasNext)
        assertFalse(first.hasPrevious)
        assertEquals(Queue(listOf(a, b), 1), first.next())
        assertEquals(b, first.next().current)
        assertFalse(first.next().hasNext)
        assertTrue(first.next().hasPrevious)
        assertEquals(first, first.next().previous())
        assertEquals(first, first.next().first())
    }

    @Test
    fun `a queue is never empty and never points outside itself`() {
        assertThrows(IllegalArgumentException::class.java) { Queue(emptyList(), 0) }
        assertThrows(IllegalArgumentException::class.java) { Queue(listOf(a), 1) }
        assertThrows(IllegalArgumentException::class.java) { Queue(listOf(a), -1) }
    }

    @Test
    fun `a moving mark advances with the clock and a still one does not`() {
        val moving = PlayMark(Millis(10_000), Millis(500_000), moving = true)

        assertEquals(Millis(12_500), moving.positionAt(Millis(502_500)))
        assertEquals(Millis(10_000), moving.copy(moving = false).positionAt(Millis(502_500)))
        assertEquals(Millis(3_000), PlayMark.still(Millis(3_000)).positionAt(Millis(900_000)))
    }

    @Test
    fun `tracks join ahead of time with gapless or a crossfade`() {
        assertTrue(ListeningOptions().joinsTracks)
        assertTrue(ListeningOptions(gapless = false, crossfade = Millis(1)).joinsTracks)
        assertFalse(ListeningOptions(gapless = false).joinsTracks)
        assertEquals("bookmark 4", BookmarkId(4).toString())
    }

    @Test
    fun `the line being sung is the last one whose time has come`() {
        val lyrics =
            Lyrics.Synced(
                listOf(SyncedLine(Millis(1_000), "one"), SyncedLine(Millis(2_000), "two"), SyncedLine(Millis(4_000), "three")),
                LyricsSource.LRC_FILE,
            )

        assertEquals(-1, lyrics.lineAt(Millis(999)))
        assertEquals(0, lyrics.lineAt(Millis(1_000)))
        assertEquals(0, lyrics.lineAt(Millis(1_999)))
        assertEquals(1, lyrics.lineAt(Millis(2_000)))
        assertEquals(1, lyrics.lineAt(Millis(3_999)))
        assertEquals(2, lyrics.lineAt(Millis(4_000)))
        assertEquals(2, lyrics.lineAt(Millis(900_000)))
        assertEquals(-1, Lyrics.Synced(emptyList(), LyricsSource.TRACK_TAGS).lineAt(Millis(5_000)))
    }

    @Test
    fun `a lookup belongs to the track it was made for`() {
        val lyrics = Lyrics.Plain(listOf("one"), LyricsSource.TRACK_TAGS)

        assertEquals(null, TrackLookup.Idle.track)
        assertEquals(a.id, TrackLookup.Looking(a.id).track)
        assertEquals(b.id, TrackLookup.Found(b.id, lyrics).track)
        assertEquals(a.id, TrackLookup.Missing(a.id).track)
        assertEquals(lyrics, TrackLookup.Found(b.id, lyrics).value)
    }
}
