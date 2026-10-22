package dev.geode.feature.playback.core

import dev.geode.engine.types.Millis
import dev.geode.feature.playback.api.Lyrics
import dev.geode.feature.playback.api.LyricsSource.LRC_FILE
import dev.geode.feature.playback.api.LyricsSource.TRACK_TAGS
import dev.geode.feature.playback.api.SyncedLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Lyrics are read as the old player read them (#147): timed `.lrc` lines, else plain lines. */
class LyricsParserTest {
    @Test
    fun `timed lines follow the music in time order`() {
        val lyrics = parseLyrics("[00:12.50]Second\n[00:03.00]First\n[01:02.123]Third", LRC_FILE)

        assertEquals(
            Lyrics.Synced(
                listOf(
                    SyncedLine(Millis(3_000), "First"),
                    SyncedLine(Millis(12_500), "Second"),
                    SyncedLine(Millis(62_123), "Third"),
                ),
                LRC_FILE,
            ),
            lyrics,
        )
    }

    @Test
    fun `a fraction of one, two or three digits is tenths, hundredths or thousandths`() {
        val lyrics = parseLyrics("[0:01]none\n[0:01.5]tenths\n[0:01.25]hundredths\n[0:01:125]thousandths", LRC_FILE)

        assertEquals(
            listOf(Millis(1_000), Millis(1_125), Millis(1_250), Millis(1_500)),
            (lyrics as Lyrics.Synced).lines.map { it.at },
        )
        assertEquals(listOf("none", "thousandths", "hundredths", "tenths"), lyrics.lines.map { it.text })
    }

    @Test
    fun `minutes count sixty seconds each`() {
        val lyrics = parseLyrics("[123:45.67]Late", TRACK_TAGS) as Lyrics.Synced

        assertEquals(listOf(SyncedLine(Millis(123 * 60_000L + 45_000 + 670), "Late")), lyrics.lines)
        assertEquals(TRACK_TAGS, lyrics.source)
    }

    @Test
    fun `a line with several times is sung at each of them`() {
        val lyrics = parseLyrics("[00:10.00][00:30.00]Chorus\n[00:20.00]Verse", LRC_FILE) as Lyrics.Synced

        assertEquals(
            listOf(SyncedLine(Millis(10_000), "Chorus"), SyncedLine(Millis(20_000), "Verse"), SyncedLine(Millis(30_000), "Chorus")),
            lyrics.lines,
        )
    }

    @Test
    fun `tags, blank lines and times with no words are left out`() {
        val lyrics = parseLyrics("[ar:Ann]\n[ti:Alpha]\n\n[00:01.00]\n[00:02.00]   \n  [00:03.00]  Words  \n", LRC_FILE)

        assertEquals(Lyrics.Synced(listOf(SyncedLine(Millis(3_000), "Words")), LRC_FILE), lyrics)
    }

    @Test
    fun `untimed lines are plain lyrics, in their own order`() {
        val lyrics = parseLyrics("[ar:Ann]\n  Zulu \n\nAlpha\n", TRACK_TAGS)

        assertEquals(Lyrics.Plain(listOf("Zulu", "Alpha"), TRACK_TAGS), lyrics)
    }

    @Test
    fun `timed lines win over untimed ones`() {
        val lyrics = parseLyrics("Title\n[00:05.00]Sung", LRC_FILE)

        assertEquals(Lyrics.Synced(listOf(SyncedLine(Millis(5_000), "Sung")), LRC_FILE), lyrics)
    }

    @Test
    fun `text with no words is no lyrics`() {
        assertNull(parseLyrics("", LRC_FILE))
        assertNull(parseLyrics("  \n \n", LRC_FILE))
        assertNull(parseLyrics("[ar:Ann]\n[00:01.00]", LRC_FILE))
    }

    @Test
    fun `a bracket that is not a time stays part of the words`() {
        val lyrics = parseLyrics("[Chorus] again\n[00:01.00][x]", LRC_FILE) as Lyrics.Synced

        assertEquals(listOf(SyncedLine(Millis(1_000), "[x]")), lyrics.lines)
        assertEquals(Lyrics.Plain(listOf("[Chorus] again"), LRC_FILE), parseLyrics("[Chorus] again", LRC_FILE))
    }
}
