package dev.geode.feature.studio.api.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Text on an Edit: SubRip, lyric lines and text lanes, moved from the old editor unchanged (#318). */
class SubtitlesTest {
    @Test
    fun `a cue spans from its start up to its end`() {
        val cue = SubtitleCue(100, 200, "hi")
        assertTrue(cue.spans(100))
        assertTrue(cue.spans(199))
        assertFalse(cue.spans(200))
        assertFalse(cue.spans(99))
    }

    @Test
    fun `SubRip is read in time order, with either separator and short milliseconds`() {
        val srt =
            "2\r\n00:00:05,500 --> 00:00:07.25\r\nSecond\r\nline two\r\n\r\n" +
                "1\n00:00:01,000 --> 00:00:02,000\nFirst\n\n" +
                "3\n1:02:03,4 --> 1:02:04,005\nThird\n\n" +
                "bad\nno timing here\n\n" +
                "4\n00:00:09,000 --> 00:00:08,000\nBackwards\n\n" +
                "5\n00:00:10,000 --> 00:00:11,000\n   \n"
        assertEquals(
            listOf(
                SubtitleCue(1_000, 2_000, "First"),
                SubtitleCue(5_500, 7_250, "Second\nline two"),
                SubtitleCue(3_723_400, 3_724_005, "Third"),
            ),
            Subtitles.parseSrt(srt),
        )
    }

    @Test
    fun `SubRip is written numbered, in time order, with fixed-width stamps`() {
        val cues = listOf(SubtitleCue(3_723_004, 3_724_050, "Later"), SubtitleCue(1_000, 2_500, "First"))
        assertEquals(
            "1\n00:00:01,000 --> 00:00:02,500\nFirst\n\n2\n01:02:03,004 --> 01:02:04,050\nLater\n",
            Subtitles.toSrt(cues),
        )
        assertEquals(cues.sortedBy { it.startMs }, Subtitles.parseSrt(Subtitles.toSrt(cues)))
        assertEquals("", Subtitles.toSrt(emptyList()))
    }

    @Test
    fun `lyric lines become cues that last until the next line, at most eight seconds`() {
        val lines =
            listOf(
                TimedLyric(10_000, "third"),
                TimedLyric(0, "first"),
                TimedLyric(1_000, "second"),
                TimedLyric(-1, "unsynced"),
                TimedLyric(500, "  "),
                TimedLyric(30_000, "too late"),
            )
        assertEquals(
            listOf(
                SubtitleCue(0, 1_000, "first"),
                SubtitleCue(1_000, 9_000, "second"),
                SubtitleCue(10_000, 18_000, "third"),
                SubtitleCue(30_000, 30_040, "too late"),
            ),
            Subtitles.fromLyrics(lines, endMs = 30_040),
        )
        assertEquals(8_000L, Subtitles.MAX_LYRIC_CUE_MS)
    }

    @Test
    fun `a lyric line too short to read is left out`() {
        val lines = listOf(TimedLyric(0, "quick"), TimedLyric(MIN_CLIP_DURATION_MS - 1, "next"))
        assertEquals(listOf(SubtitleCue(MIN_CLIP_DURATION_MS - 1, 1_000, "next")), Subtitles.fromLyrics(lines, 1_000))
        val exact = listOf(TimedLyric(0, "quick"), TimedLyric(MIN_CLIP_DURATION_MS, "next"))
        assertEquals(2, Subtitles.fromLyrics(exact, 1_000).size)
    }

    @Test
    fun `cues come from the enabled text clips of unmuted text lanes`() {
        val lanes =
            listOf(
                Lane(
                    LaneId("t1"),
                    LaneKind.Text,
                    clips =
                        listOf(
                            Clip(ClipId("b"), ClipContent.Text("later"), 2_000, 500),
                            Clip(ClipId("a"), ClipContent.Text("sooner"), 0, 500),
                            Clip(ClipId("x"), ClipContent.Text("off"), 600, 100, enabled = false),
                        ),
                ),
                Lane(LaneId("t2"), LaneKind.Text, clips = listOf(Clip(ClipId("m"), ClipContent.Text("muted"), 0, 100)), muted = true),
                Lane(LaneId("v"), LaneKind.Media, clips = listOf(Clip(ClipId("v"), ClipContent.Video("u"), 0, 100))),
                Lane(LaneId("t3"), LaneKind.Text, clips = listOf(Clip(ClipId("s"), ClipContent.Still("u"), 1_000, 100))),
            )
        assertEquals(listOf(SubtitleCue(0, 500, "sooner"), SubtitleCue(2_000, 2_500, "later")), Subtitles.cuesFrom(lanes))
    }

    @Test
    fun `cues become text clips with fresh ids, slivers left out`() {
        var next = 0
        val cues =
            listOf(
                SubtitleCue(0, 1_000, "one"),
                SubtitleCue(1_000, 1_000 + MIN_CLIP_DURATION_MS - 1, "sliver"),
                SubtitleCue(2_000, 2_500, "two"),
            )
        val clips = Subtitles.clipsFrom(cues) { ClipId("c${next++}") }
        assertEquals(
            listOf(
                Clip(ClipId("c0"), ClipContent.Text("one"), 0, 1_000),
                Clip(ClipId("c1"), ClipContent.Text("two"), 2_000, 500),
            ),
            clips,
        )
    }
}
