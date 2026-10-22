package dev.geode.feature.search.core

import dev.geode.feature.library.api.Song
import dev.geode.feature.playback.api.PlaylistSummary
import dev.geode.feature.search.api.PlaylistRef
import dev.geode.feature.search.api.PlaylistResult
import dev.geode.feature.search.api.SearchCatalogue
import dev.geode.feature.search.api.SearchResults
import dev.geode.feature.search.api.SongResult

/**
 * Today's matching, as the old Search overlay's `SearchMatcher` did it (#143: "the same matching as
 * today"): the query is split into words at spaces, and a result matches when every word is found,
 * ignoring case, in at least one of its fields. Songs match on title, artist, album, genre and folder;
 * playlists and presets on their name.
 */
object SearchMatch {
    private val SPACES = Regex("\\s+")

    /** The words of [query]; none when it holds only spaces. */
    fun terms(query: String): List<String> = query.split(SPACES).filter { it.isNotEmpty() }

    /** What [query] finds in [catalogue]: nothing to show when it has no words. */
    fun found(
        query: String,
        catalogue: SearchCatalogue,
    ): SearchResults {
        val terms = terms(query)
        if (terms.isEmpty()) return SearchResults.NotSearching
        return SearchResults.Found(
            query = query,
            songs =
                catalogue.songs
                    .filter { matches(terms, listOf(it.title, it.artist, it.album, it.genre, it.folder)) }
                    .take(SearchResults.SONG_LIMIT),
            playlists = catalogue.playlists.filter { matches(terms, listOf(it.name)) },
            presets = catalogue.presets.filter { matches(terms, listOf(it.name)) },
        )
    }

    /** The Library's [songs] as Search lists them: by title, ignoring case, without [excludedFolders] and what is under them. */
    fun songs(
        songs: List<Song>,
        excludedFolders: Set<String>,
    ): List<SongResult> =
        songs
            .filterNot { song -> excludedFolders.any { within(song.folder, it) } }
            .map { SongResult(it.id, it.title, it.artist, it.album, it.genre, it.folder) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })

    /** Playback's [playlists] as Search lists them: by name, ignoring case. */
    fun playlists(playlists: List<PlaylistSummary>): List<PlaylistResult> =
        playlists
            .map { PlaylistResult(PlaylistRef(it.id.value), it.name, it.trackCount) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    private fun matches(
        terms: List<String>,
        fields: List<String>,
    ): Boolean = terms.all { term -> fields.any { it.contains(term, ignoreCase = true) } }

    /** Whether [folder] is [parent] or inside it, as the Library hides excluded folders. */
    private fun within(
        folder: String,
        parent: String,
    ): Boolean = folder == parent || folder.startsWith("$parent/")
}
