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
    fun `a PLS playlist is read in the order of its numbers`() {
        val text = "[playlist]\nFile2=/music/jazz/beta.flac\nTitle2=Beta\nfile1 = file:///music/rock/alpha.mp3\nFile3=\nNumberOfEntries=2\n"

        assertEquals(listOf("/music/rock/alpha.mp3", "/music/jazz/beta.flac"), M3u.read(text))
    }

    @Test
    fun `an XSPF playlist is read by its locations, escapes undone`() {
        val text =
            "<?xml version=\"1.0\"?>\n<playlist version=\"1\" xmlns=\"http://xspf.org/ns/0/\"><trackList>" +
                "<track><location>file:///music/rock/alpha.mp3</location><title>Alpha</title></track>" +
                "<track><location>file:///music/My%20Jazz/b&amp;b+c.flac</location></track>" +
                "<track><location> </location></track>" +
                "<track><location>/music/&lt;1&gt; &quot;Q&apos;s&quot;.mp3</location></track>" +
                "<track><location>/music/100%.mp3</location></track>" +
                "</trackList></playlist>"

        assertEquals(
            listOf("/music/rock/alpha.mp3", "/music/My Jazz/b&b+c.flac", "/music/<1> \"Q's\".mp3", "/music/100%.mp3"),
            M3u.read(text),
        )
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
