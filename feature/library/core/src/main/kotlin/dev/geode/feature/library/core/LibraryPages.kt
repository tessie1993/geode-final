package dev.geode.feature.library.core

import dev.geode.engine.types.TrackId
import dev.geode.feature.library.api.DUPLICATE_LENGTH_SLACK
import dev.geode.feature.library.api.DuplicateGroup
import dev.geode.feature.library.api.Filter
import dev.geode.feature.library.api.GroupKey
import dev.geode.feature.library.api.GroupKind
import dev.geode.feature.library.api.GroupRow
import dev.geode.feature.library.api.LibraryState
import dev.geode.feature.library.api.LibraryView
import dev.geode.feature.library.api.Page
import dev.geode.feature.library.api.Pick
import dev.geode.feature.library.api.Place
import dev.geode.feature.library.api.SHORT_CLIP
import dev.geode.feature.library.api.SmartPlaylist
import dev.geode.feature.library.api.Song
import dev.geode.feature.library.api.SongRow
import dev.geode.feature.library.api.Sort
import dev.geode.feature.library.api.SortKey

/** The songs grouped under one name. */
private typealias Group = Map.Entry<String, List<Song>>

/** How many songs Recently added lists. */
const val RECENTLY_ADDED_LIMIT = 100

/** How many songs Recently played lists. */
const val RECENTLY_PLAYED_LIMIT = 100

/** How many songs Most played lists. */
const val MOST_PLAYED_LIMIT = 100

/**
 * What the Library shows, worked out from its state: pure functions the reducer runs after every
 * change, so [LibraryState.page] always matches the place, the search, the sort and the filter.
 */
internal object LibraryPages {
    /** The page [state] shows. */
    fun pageOf(state: LibraryState): Page {
        val shelf = Shelf(state)
        if (state.query.isNotBlank()) return shelf.found(state.query.trim())
        return when (val place = state.place) {
            is Place.Shelf -> shelf.view(place.view)
            is Place.Group -> Page.Songs(shelf.rows(shelf.sorted(shelf.inGroup(place.key))))
        }
    }

    /**
     * The songs [pick] means in [state], in order; none for a playlist, whose songs Playback keeps. A
     * smart playlist means the songs its rules pick now.
     */
    fun songsOf(
        state: LibraryState,
        pick: Pick,
    ): List<Song> {
        val shelf = Shelf(state)
        return when (pick) {
            is Pick.Songs -> {
                val byId = state.songs.associateBy { it.id }
                pick.ids.mapNotNull { byId[it] }
            }
            is Pick.Group -> shelf.sorted(shelf.inGroup(pick.key))
            Pick.Shown ->
                when (val page = state.page) {
                    is Page.Songs -> page.rows.map { it.song }
                    is Page.Found -> page.songs.map { it.song }
                    is Page.Groups -> shelf.sorted(shelf.visible)
                    is Page.Duplicates -> page.groups.flatMap { group -> group.rows.map { it.song } }
                }
        }
    }

    /** Whether [song] is in a folder the user excluded, or beneath one. */
    fun excluded(
        song: Song,
        folders: Set<String>,
    ): Boolean = folders.any { within(song.folder, it) }

    /** Whether [song] passes [filter]. */
    fun passes(
        song: Song,
        filter: Filter,
    ): Boolean =
        (filter.formats.isEmpty() || song.format in filter.formats) &&
            filter.folder.let { it == null || within(song.folder, it) } &&
            (!filter.hideShortClips || song.duration >= SHORT_CLIP)

    /** The name [song] is grouped under for [kind]; null for playlists, which are not made from tags. */
    fun nameIn(
        song: Song,
        kind: GroupKind,
    ): String? =
        when (kind) {
            GroupKind.ALBUM -> song.album
            GroupKind.ARTIST -> song.artist
            GroupKind.ALBUM_ARTIST -> song.shownAlbumArtist
            GroupKind.GENRE -> song.genre
            GroupKind.COMPOSER -> song.composer
            GroupKind.FOLDER -> song.folder
            GroupKind.PLAYLIST, GroupKind.SMART_PLAYLIST -> null
        }

    /** Whether [folder] is [parent] or beneath it. */
    private fun within(
        folder: String,
        parent: String,
    ): Boolean = folder == parent || folder.startsWith("$parent/")

    /** One state's songs as the Library may show them, with what lists need to know about them. */
    private class Shelf(
        val state: LibraryState,
    ) {
        val visible: List<Song> = state.songs.filter { !excluded(it, state.excludedFolders) && passes(it, state.filter) }

        private val playCounts: Map<TrackId, Int> = state.plays.groupingBy { it.song }.eachCount()

        fun view(view: LibraryView): Page =
            when (view) {
                LibraryView.SONGS -> Page.Songs(rows(sorted(visible)))
                LibraryView.FAVOURITES -> Page.Songs(rows(sorted(visible.filter { it.id in state.favourites })))
                LibraryView.RECENTLY_ADDED -> Page.Songs(rows(recentlyAdded().take(RECENTLY_ADDED_LIMIT)))
                LibraryView.RECENTLY_PLAYED -> Page.Songs(rows(recentlyPlayed().take(RECENTLY_PLAYED_LIMIT)))
                LibraryView.MOST_PLAYED -> Page.Songs(rows(mostPlayed().take(MOST_PLAYED_LIMIT)))
                LibraryView.ALBUMS -> Page.Groups(groups(GroupKind.ALBUM))
                LibraryView.ARTISTS -> Page.Groups(groups(GroupKind.ARTIST))
                LibraryView.ALBUM_ARTISTS -> Page.Groups(groups(GroupKind.ALBUM_ARTIST))
                LibraryView.GENRES -> Page.Groups(groups(GroupKind.GENRE))
                LibraryView.COMPOSERS -> Page.Groups(groups(GroupKind.COMPOSER))
                LibraryView.FOLDERS -> Page.Groups(groups(GroupKind.FOLDER))
                // Playback's in its order, the newest first, then the smart playlists by name.
                LibraryView.PLAYLISTS -> Page.Groups(playlists())
                LibraryView.DUPLICATES -> Page.Duplicates(duplicates())
            }

        /** What [query] finds: songs by title, artist, album or folder, and groups by name (as the old search found them). */
        fun found(query: String): Page.Found {
            val terms = LibrarySearch.terms(query)

            fun matches(vararg fields: String) = LibrarySearch.matches(terms, fields.toList())
            return Page.Found(
                query = query,
                songs = rows(sorted(visible.filter { matches(it.title, it.artist, it.album, it.folder) })),
                albums = groups(GroupKind.ALBUM).filter { matches(it.title) },
                artists = groups(GroupKind.ARTIST).filter { matches(it.title) },
                folders = groups(GroupKind.FOLDER).filter { matches(it.key.name) },
                playlists = playlists().filter { matches(it.title) },
            )
        }

        fun inGroup(key: GroupKey): List<Song> =
            if (key.kind == GroupKind.SMART_PLAYLIST) {
                state.smartPlaylists
                    .firstOrNull { it.name == key.name }
                    ?.let(::smart)
                    .orEmpty()
            } else {
                visible.filter { nameIn(it, key.kind) == key.name }
            }

        /**
         * The songs [playlist] picks now, from the songs the Library may show. Its limit counts them in
         * order of name, as the old smart playlists did; a page then sorts them as it sorts any group.
         */
        private fun smart(playlist: SmartPlaylist): List<Song> =
            SmartPlaylists.resolve(playlist, visible.sortedWith(byTitle), { playCounts[it] ?: 0 }, state.favourites, state.now)

        fun sorted(songs: List<Song>): List<Song> {
            val order =
                when (state.sort.key) {
                    SortKey.NAME -> byTitle
                    SortKey.DATE_ADDED -> compareBy<Song> { it.addedAt.value }.then(byTitle)
                    SortKey.LENGTH -> compareBy<Song> { it.duration.value }.then(byTitle)
                    SortKey.PLAYS -> compareBy<Song> { plays(it) }.then(byTitle)
                    SortKey.ARTIST -> compareBy<Song, String>(String.CASE_INSENSITIVE_ORDER) { it.artist }.then(byTitle)
                    SortKey.ALBUM -> compareBy<Song, String>(String.CASE_INSENSITIVE_ORDER) { it.album }.then(byTitle)
                }
            return songs.sortedWith(order.directed(state.sort))
        }

        /**
         * Tracks that look like the same recording: the same title and artist once normalised, then
         * split wherever two lengths lie more than [DUPLICATE_LENGTH_SLACK] apart. Each group lists its
         * copies by folder, and the groups go by title.
         */
        fun duplicates(): List<DuplicateGroup> =
            visible
                .groupBy { normalised(it.title) to normalised(it.artist) }
                .values
                .flatMap { same -> byLength(same.sortedBy { it.duration.value }) }
                .filter { it.size > 1 }
                .map { copies -> DuplicateGroup(copies.first().title, copies.first().artist, rows(copies.sortedBy { it.folder })) }
                .sortedBy { it.title.lowercase() }

        private fun byLength(sorted: List<Song>): List<List<Song>> {
            val runs = mutableListOf<MutableList<Song>>()
            for (song in sorted) {
                val run = runs.lastOrNull()
                if (run != null && song.duration.value - run.last().duration.value <= DUPLICATE_LENGTH_SLACK.value) {
                    run += song
                } else {
                    runs += mutableListOf(song)
                }
            }
            return runs
        }

        fun rows(songs: List<Song>): List<SongRow> = songs.map { SongRow(it, it.id in state.favourites, plays(it)) }

        private fun plays(song: Song): Int = playCounts[song.id] ?: 0

        private fun recentlyAdded(): List<Song> = visible.sortedWith(compareByDescending<Song> { it.addedAt.value }.then(byTitle))

        private fun recentlyPlayed(): List<Song> {
            val byId = visible.associateBy { it.id }
            return state.plays
                .map { it.song }
                .distinct()
                .mapNotNull { byId[it] }
        }

        /** The songs played at least once, the most played first; between equals, the one played last first. */
        private fun mostPlayed(): List<Song> {
            val lastPlayed = recentlyPlayed()
            return lastPlayed.sortedByDescending { plays(it) }
        }

        private fun groups(kind: GroupKind): List<GroupRow> {
            val groups = visible.groupBy { nameIn(it, kind).orEmpty() }
            val order: Comparator<Group> =
                when (state.sort.key) {
                    SortKey.NAME -> byName
                    SortKey.DATE_ADDED -> compareBy<Group> { group -> group.value.maxOf { it.addedAt.value } }.then(byName)
                    SortKey.LENGTH -> compareBy<Group> { group -> group.value.sumOf { it.duration.value } }.then(byName)
                    SortKey.PLAYS -> compareBy<Group> { group -> group.value.sumOf { plays(it) } }.then(byName)
                    SortKey.ARTIST -> byFirst(Song::artist)
                    SortKey.ALBUM -> byFirst(Song::album)
                }
            val folderLabels = if (kind == GroupKind.FOLDER) FolderLabels.of(groups.keys) else emptyMap()
            return groups.entries.sortedWith(order.directed(state.sort)).map { (name, songs) ->
                GroupRow(
                    key = GroupKey(kind, name),
                    title = folderLabels[name] ?: name,
                    detail = if (kind == GroupKind.ALBUM) songs.first().shownAlbumArtist else "",
                    songCount = songs.size,
                    artwork = songs.firstNotNullOfOrNull { it.artwork },
                )
            }
        }

        private fun playlists(): List<GroupRow> =
            state.playlists.map {
                GroupRow(
                    key = GroupKey(GroupKind.PLAYLIST, it.id.value.toString()),
                    title = it.name,
                    detail = "",
                    songCount = it.trackCount,
                    artwork = null,
                )
            } +
                state.smartPlaylists.map { playlist ->
                    val songs = smart(playlist)
                    GroupRow(
                        key = GroupKey(GroupKind.SMART_PLAYLIST, playlist.name),
                        title = playlist.name,
                        detail = "",
                        songCount = songs.size,
                        artwork = songs.firstNotNullOfOrNull { it.artwork },
                    )
                }
    }

    /** Groups by the [field] of theirs that sorts first, then by name. */
    private fun byFirst(field: (Song) -> String): Comparator<Group> =
        compareBy<Group, String>(String.CASE_INSENSITIVE_ORDER) { group -> group.value.minOfWith(String.CASE_INSENSITIVE_ORDER, field) }
            .then(byName)

    private val byTitle: Comparator<Song> =
        compareBy<Song, String>(String.CASE_INSENSITIVE_ORDER) { it.title }.thenBy { it.id.value }

    private val byName: Comparator<Group> = compareBy<Group, String>(String.CASE_INSENSITIVE_ORDER) { it.key }

    private fun <T> Comparator<T>.directed(sort: Sort): Comparator<T> = if (sort.descending) reversed() else this
}

/** [text] with case, punctuation and bracketed notes ("(Remastered)", "[Live]") set aside. */
internal fun normalised(text: String): String = text.lowercase().replace(NOISE, " ").replace(SPACES, " ").trim()

private val NOISE = Regex("""[\[(].*?[\])]|[^\p{L}\p{N}\s]""")
private val SPACES = Regex("""\s+""")
