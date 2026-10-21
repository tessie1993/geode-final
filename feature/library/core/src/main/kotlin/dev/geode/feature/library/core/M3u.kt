package dev.geode.feature.library.core

import dev.geode.feature.library.api.Song

/**
 * M3U playlists (#37: import and export), as plain text. Extended M3U is written, and any M3U is
 * read: an entry is any line that is not blank and not a `#` comment.
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

    /** The entries of the M3U playlist [text], in order, as paths with forward slashes. */
    fun read(text: String): List<String> =
        text
            .removePrefix(BYTE_ORDER_MARK.toString())
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .map { it.removePrefix(FILE_SCHEME).replace('\\', '/') }

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
