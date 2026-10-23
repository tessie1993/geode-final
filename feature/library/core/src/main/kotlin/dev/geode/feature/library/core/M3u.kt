package dev.geode.feature.library.core

import dev.geode.feature.library.api.Song
import java.net.URLDecoder

/**
 * Playlist files (#37: import and export), as plain text. Extended M3U is written. Any M3U is read (an
 * entry is any line that is not blank and not a `#` comment), and so are PLS (its `FileN=` lines, in
 * number order) and XSPF (its `<location>`s), which the old Library read too (#17).
 */
internal object M3u {
    private const val HEADER = "#EXTM3U"
    private const val INFO = "#EXTINF:"
    private const val BYTE_ORDER_MARK = '\uFEFF'
    private const val FILE_SCHEME = "file://"
    private const val MILLIS_PER_SECOND = 1_000L

    /** [songs] as an extended M3U playlist, each named by its path. */
    fun write(songs: List<Song>): String =
        buildString {
            append(HEADER).append('\n')
            for (song in songs) {
                append(INFO)
                    .append(song.duration.value / MILLIS_PER_SECOND)
                    .append(',')
                    .append(song.artist)
                    .append(" - ")
                    .append(song.title)
                    .append('\n')
                append(song.path).append('\n')
            }
        }

    private val plsFile = Regex("^file(\\d+)\\s*=(.*)$", RegexOption.IGNORE_CASE)
    private val xspfLocation = Regex("<(?:\\w+:)?location\\b[^>]*>(.*?)</(?:\\w+:)?location>", RegexOption.DOT_MATCHES_ALL)

    /** The entries of the playlist [text] (M3U, PLS or XSPF), in order, as paths with forward slashes. */
    fun read(text: String): List<String> {
        val body = text.removePrefix(BYTE_ORDER_MARK.toString())
        val head = body.trimStart()
        val entries =
            when {
                head.startsWith('<') -> xspf(body)
                head.startsWith("[playlist]", ignoreCase = true) -> pls(body)
                else ->
                    body
                        .lines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith('#') }
            }
        return entries.map { it.removePrefix(FILE_SCHEME).replace('\\', '/') }
    }

    private fun pls(text: String): List<String> =
        text
            .lines()
            .mapNotNull { line -> plsFile.find(line.trim())?.destructured?.let { (number, file) -> number.toInt() to file.trim() } }
            .sortedBy { it.first }
            .map { it.second }
            .filter { it.isNotEmpty() }

    /** Each location, with its XML escapes and its url's percent escapes undone. */
    private fun xspf(text: String): List<String> =
        xspfLocation
            .findAll(text)
            .map { it.groupValues[1].trim().unescaped() }
            .filter { it.isNotEmpty() }
            .map { location -> runCatching { URLDecoder.decode(location.replace("+", "%2B"), Charsets.UTF_8) }.getOrDefault(location) }
            .toList()

    private fun String.unescaped(): String =
        replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")

    /**
     * The songs [entries] name, in order, skipping those not in [songs]. An entry names a song by its
     * whole path, by the end of its path (a playlist written beside the music), or else by its file
     * name alone.
     */
    fun match(
        entries: List<String>,
        songs: List<Song>,
    ): List<Song> {
        val byPath = songs.associateBy { it.path }
        val byFileName = songs.groupBy { fileName(it.path).lowercase() }
        return entries.mapNotNull { entry ->
            byPath[entry]
                ?: songs.firstOrNull { it.path.endsWith("/${entry.trimStart('.', '/')}") }
                ?: byFileName[fileName(entry).lowercase()]?.first()
        }
    }

    private fun fileName(path: String): String = path.substringAfterLast('/')
}
