package dev.geode.feature.studio.api

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A playlist Render's chapters (#329): one per song at the right start time, in each file format. */
class ChaptersTest {
    private val night = RenderTrack(TrackId("content://media/12"), "Night Drive", Millis(215_000), "The Neon")
    private val tide = RenderTrack(TrackId("content://media/7"), "Low Tide", Millis(187_500))
    private val blank = RenderTrack(TrackId("content://media/3"), " ", Millis(3_600_000))
    private val chapters = Chapters.of(listOf(night, tide, blank))

    @Test
    fun `one chapter per song, each starting where the songs before it end`() {
        assertEquals(
            listOf(
                TrackChapter(0L, 215_000L, "Night Drive — The Neon"),
                TrackChapter(215_000L, 187_500L, "Low Tide"),
                TrackChapter(402_500L, 3_600_000L, "Track 3"),
            ),
            chapters,
        )
        assertEquals(4_002_500L, chapters.last().endMs)
    }

    @Test
    fun `a single song has no chapters`() {
        assertTrue(Chapters.of(listOf(night)).isEmpty())
        assertTrue(Chapters.of(emptyList()).isEmpty())
        assertEquals("", Chapters.description(emptyList()))
    }

    @Test
    fun `the description is a line per song with its start`() {
        assertEquals("0:00 Night Drive — The Neon\n3:35 Low Tide\n6:42 Track 3\n", Chapters.description(chapters))
    }

    @Test
    fun `WebVTT has a numbered cue per song`() {
        assertEquals(
            "WEBVTT\n\n" +
                "1\n00:00:00.000 --> 00:03:35.000\nNight Drive — The Neon\n\n" +
                "2\n00:03:35.000 --> 00:06:42.500\nLow Tide\n\n" +
                "3\n00:06:42.500 --> 01:06:42.500\nTrack 3\n\n",
            Chapters.webVtt(chapters),
        )
    }

    @Test
    fun `FFmetadata has a chapter block per song in milliseconds`() {
        assertEquals(
            ";FFMETADATA1\n" +
                "\n[CHAPTER]\nTIMEBASE=1/1000\nSTART=0\nEND=215000\ntitle=Night Drive — The Neon\n" +
                "\n[CHAPTER]\nTIMEBASE=1/1000\nSTART=215000\nEND=402500\ntitle=Low Tide\n" +
                "\n[CHAPTER]\nTIMEBASE=1/1000\nSTART=402500\nEND=4002500\ntitle=Track 3\n",
            Chapters.ffmetadata(chapters),
        )
    }

    @Test
    fun `times past an hour carry the hours, and a negative time reads as the start`() {
        assertEquals("1:00:00", Chapters.timestamp(3_600_000L))
        assertEquals("1:02:03", Chapters.timestamp(3_723_999L))
        assertEquals("0:59", Chapters.timestamp(59_999L))
        assertEquals("0:00", Chapters.timestamp(-5L))
        assertEquals("00:00:00.000", Chapters.vttTimestamp(-5L))
        assertEquals("02:00:01.250", Chapters.vttTimestamp(7_201_250L))
    }

    @Test
    fun `a title with a line break stays on one line in FFmetadata`() {
        val split = Chapters.of(listOf(night.copy(title = "Night\nDrive", artist = ""), tide))
        assertTrue(Chapters.ffmetadata(split).contains("title=Night Drive\n"))
    }

    @Test
    fun `with a crossfade each song starts as the one before it fades out, and the chapters tile the video`() {
        // 215 s and 187.5 s overlap by the 4 s crossfade; 187.5 s and 3600 s too.
        val joined = Chapters.of(listOf(night, tide, blank), Millis(4_000))
        assertEquals(
            listOf(
                TrackChapter(0L, 211_000L, "Night Drive — The Neon"),
                TrackChapter(211_000L, 183_500L, "Low Tide"),
                TrackChapter(394_500L, 3_600_000L, "Track 3"),
            ),
            joined,
        )
        assertEquals(3_994_500L, joined.last().endMs)
    }

    @Test
    fun `a crossfade never takes more than half of either song`() {
        assertEquals(Millis(4_000), Chapters.overlap(Millis(4_000), Millis(215_000), Millis(187_500)))
        assertEquals(Millis(3_000), Chapters.overlap(Millis(12_000), Millis(6_000), Millis(187_500)))
        assertEquals(Millis(2_500), Chapters.overlap(Millis(12_000), Millis(187_500), Millis(5_000)))
        assertEquals(Millis.ZERO, Chapters.overlap(Millis.ZERO, Millis(215_000), Millis(187_500)))
        assertEquals(Millis.ZERO, Chapters.overlap(Millis(-1_000), Millis(215_000), Millis(187_500)))
        assertEquals(Millis.ZERO, Chapters.overlap(Millis(4_000), Millis.ZERO, Millis(187_500)))
        assertEquals(Millis.ZERO, Chapters.overlap(Millis(4_000), Millis(215_000), Millis(-5)))
        val short = tide.copy(length = Millis(6_000))
        assertEquals(listOf(0L, 212_000L), Chapters.of(listOf(night, short), Millis(12_000)).map { it.startMs })
    }
}
