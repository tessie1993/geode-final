package dev.geode.feature.library.core

import dev.geode.feature.library.core.Fixtures.alpha
import dev.geode.feature.library.core.Fixtures.beta
import dev.geode.feature.library.core.Fixtures.gamma
import dev.geode.feature.library.core.Fixtures.songs
import org.junit.Assert.assertEquals
import org.junit.Test

/** M3U playlists in and out (#37). */
class M3uTest {
    @Test
    fun `songs are written as extended M3U, each named by its path`() {
        assertEquals(
            "#EXTM3U\n" +
                "#EXTINF:200,Ann - Alpha\n/music/rock/alpha.mp3\n" +
                "#EXTINF:180,Bob - beta\n/music/jazz/beta.flac\n",
            M3u.write(listOf(alpha, beta)),
        )
        assertEquals("#EXTM3U\n", M3u.write(emptyList()))
    }

    @Test
    fun `reading keeps the entries and drops comments, blanks, a byte order mark and file urls`() {
        val text =
            "\uFEFF#EXTM3U\r\n#EXTINF:200,Ann - Alpha\r\n  /music/rock/alpha.mp3  \r\n\r\n" +
                "file:///music/jazz/beta.flac\nMusic\\Rock\\gamma.mp3"

        assertEquals(listOf("/music/rock/alpha.mp3", "/music/jazz/beta.flac", "Music/Rock/gamma.mp3"), M3u.read(text))
    }

    @Test
    fun `an entry names a song by its path, the end of its path, or its file name`() {
        val entries =
            listOf(
                "/music/rock/alpha.mp3",
                "../jazz/beta.flac",
                "/sdcard/old/GAMMA.MP3",
                "/nowhere/missing.ogg",
            )

        assertEquals(listOf(alpha, beta, gamma), M3u.match(entries, songs))
    }

    @Test
    fun `an entry that names no song is skipped`() {
        assertEquals(listOf(alpha), M3u.match(listOf("rock/alpha.mp3"), songs))
        assertEquals(emptyList<Any>(), M3u.match(listOf("ock/alpha2.mp3"), songs))
    }

    @Test
    fun `what is written reads back to the same songs`() {
        val written = M3u.write(listOf(gamma, alpha))

        assertEquals(listOf(gamma, alpha), M3u.match(M3u.read(written), songs))
    }
}
