package dev.geode.feature.library.api

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.kernel.Feature

/**
 * The Library as everything outside it sees it (ADR-0003, ADR-0005): the songs on the phone, where
 * the user is browsing, what that place shows, and the Commands that change it. Playing anything goes
 * to Playback as a Command (ADR-0004: Playback owns the queue).
 */
typealias Library = Feature<LibraryState, LibraryCommand, LibraryFact>

/** One song in the library index, with the user's edits to its info already applied. */
data class Song(
    val id: TrackId,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val genre: String,
    val composer: String,
    /** Where the file is, as the media index gives it: what folders and M3U playlists name. */
    val path: String,
    /** The file's format, as its lower-case extension: `mp3`, `flac`. */
    val format: String,
    val duration: Millis,
    /** When the file was added to the phone, wall-clock time. */
    val addedAt: Millis,
    /** Cover art the user chose in the app, as a content uri; null for the file's own. */
    val artwork: String? = null,
) {
    /** The folder the file is in. */
    val folder: String get() = path.substringBeforeLast('/', "")

    /** The album artist, or the artist when the tags name none. */
    val shownAlbumArtist: String get() = albumArtist.ifBlank { artist }
}

/** The song info a user can edit in the app (#37). [artwork] is a content uri, or null to keep the file's own. */
data class SongEdit(
    val title: String,
    val artist: String,
    val album: String,
    val artwork: String? = null,
)

/** The Library's views (owner decisions on #37). */
enum class LibraryView {
    SONGS,
    ALBUMS,
    ARTISTS,
    ALBUM_ARTISTS,
    GENRES,
    COMPOSERS,
    FOLDERS,
    PLAYLISTS,
    FAVOURITES,
    RECENTLY_ADDED,
    RECENTLY_PLAYED,
    MOST_PLAYED,
}

/** What songs are grouped by in the views that list groups. */
enum class GroupKind {
    ALBUM,
    ARTIST,
    ALBUM_ARTIST,
    GENRE,
    COMPOSER,
    FOLDER,
    PLAYLIST,
}

/**
 * One group: the album, artist, genre, composer or folder named [name] (blank when the tags name
 * none), or the playlist whose [PlaylistRef] value [name] holds.
 */
data class GroupKey(
    val kind: GroupKind,
    val name: String,
)

/** A playlist, which Playback keeps (ADR-0005 data ownership). */
@JvmInline
value class PlaylistRef(
    val value: Long,
) {
    override fun toString(): String = "playlist $value"
}

data class LibraryPlaylist(
    val id: PlaylistRef,
    val name: String,
    val trackCount: Int,
)

/** One play from Playback's history: [song] started at [playedAt], wall-clock time. */
data class Play(
    val song: TrackId,
    val playedAt: Millis,
)

enum class SortKey {
    NAME,
    DATE_ADDED,
    LENGTH,
    PLAYS,
}

/** How lists are ordered: by [key], smallest first unless [descending]. */
data class Sort(
    val key: SortKey = SortKey.NAME,
    val descending: Boolean = false,
)

/**
 * Which songs show anywhere in the Library: only [formats] (all when empty), only under [folder] (all
 * when null), and no clips shorter than [SHORT_CLIP] when [hideShortClips].
 */
data class Filter(
    val formats: Set<String> = emptySet(),
    val folder: String? = null,
    val hideShortClips: Boolean = false,
)

/** Songs shorter than this are short clips, such as voice notes. */
val SHORT_CLIP = Millis(30_000)

/** How far the library index has got. */
sealed interface IndexStatus {
    /** The saved index is being read. */
    data object Loading : IndexStatus

    /** The phone's music is being looked through; the songs already known show meanwhile. */
    data object Scanning : IndexStatus

    data object Ready : IndexStatus

    /** The phone does not let Geode read its music. */
    data object NeedsPermission : IndexStatus
}

/** Where the user is in the Library. */
sealed interface Place {
    /** The top of [view]. */
    data class Shelf(
        val view: LibraryView,
    ) : Place

    /** Inside one group of [view], such as an album. */
    data class Group(
        val view: LibraryView,
        val key: GroupKey,
    ) : Place
}

/** One song as a list shows it: whether it is a favourite, and how often it was played. */
data class SongRow(
    val song: Song,
    val favourite: Boolean,
    val plays: Int,
)

/** One group as a list shows it. [detail] is the album artist for albums and blank otherwise. */
data class GroupRow(
    val key: GroupKey,
    val title: String,
    val detail: String,
    val songCount: Int,
    val artwork: String?,
)

/** What the current place shows. */
sealed interface Page {
    data class Songs(
        val rows: List<SongRow>,
    ) : Page

    data class Groups(
        val rows: List<GroupRow>,
    ) : Page

    /** What one search found, across songs, albums, artists, folders and playlists. */
    data class Found(
        val query: String,
        val songs: List<SongRow>,
        val albums: List<GroupRow>,
        val artists: List<GroupRow>,
        val folders: List<GroupRow>,
        val playlists: List<GroupRow>,
    ) : Page {
        val isEmpty: Boolean
            get() = listOf(songs, albums, artists, folders, playlists).all { it.isEmpty() }
    }
}

/** Everything the Library lets the outside read. [page] is always what [place], [query], [sort] and [filter] show. */
data class LibraryState(
    val index: IndexStatus = IndexStatus.Loading,
    /** Every song found, folders the user excluded included. */
    val songs: List<Song> = emptyList(),
    val place: Place = Place.Shelf(LibraryView.SONGS),
    /** A search across the Library; blank when not searching. */
    val query: String = "",
    val sort: Sort = Sort(),
    val filter: Filter = Filter(),
    val favourites: Set<TrackId> = emptySet(),
    /** Folders hidden from the Library, such as ringtones; everything under them is hidden too. */
    val excludedFolders: Set<String> = emptySet(),
    val playlists: List<LibraryPlaylist> = emptyList(),
    /** Playback's history, the most recent first. */
    val plays: List<Play> = emptyList(),
    val page: Page = Page.Songs(emptyList()),
) {
    /** The formats the songs come in, for the format filter. */
    val formats: List<String> get() = songs.map { it.format }.distinct().sorted()

    /** The folders songs are in, for the folder filter and for excluding folders. */
    val folders: List<String> get() = songs.map { it.folder }.distinct().sorted()
}

/** Which songs a Command means. */
sealed interface Pick {
    /** These songs, in this order. */
    data class Songs(
        val ids: List<TrackId>,
    ) : Pick

    /** Every song of the group, in the order its page lists them. */
    data class Group(
        val key: GroupKey,
    ) : Pick

    /** The songs the current page lists, in order. */
    data object Shown : Pick
}

sealed interface LibraryCommand {
    data class Show(
        val view: LibraryView,
    ) : LibraryCommand

    /** Opens a group of the view being shown. A playlist is played, not opened. */
    data class Open(
        val key: GroupKey,
    ) : LibraryCommand

    /** Out of a search, or out of a group to its view. */
    data object Back : LibraryCommand

    data class Search(
        val query: String,
    ) : LibraryCommand

    data class SortBy(
        val sort: Sort,
    ) : LibraryCommand

    data class FilterBy(
        val filter: Filter,
    ) : LibraryCommand

    /** Looks through the phone's music again, for instance once it may be read. */
    data object Rescan : LibraryCommand

    data class ToggleFavourite(
        val song: TrackId,
    ) : LibraryCommand

    /** Changes a song's info in the Library. The file itself is left as it is. */
    data class EditSong(
        val song: TrackId,
        val edit: SongEdit,
    ) : LibraryCommand

    data class ExcludeFolder(
        val folder: String,
    ) : LibraryCommand

    data class IncludeFolder(
        val folder: String,
    ) : LibraryCommand

    /** Plays [pick], from the song [from] when it is one of them. */
    data class Play(
        val pick: Pick,
        val from: TrackId? = null,
    ) : LibraryCommand

    data class PlayNext(
        val pick: Pick,
    ) : LibraryCommand

    data class AddToQueue(
        val pick: Pick,
    ) : LibraryCommand

    data class CreatePlaylist(
        val name: String,
        val pick: Pick,
    ) : LibraryCommand

    data class DeletePlaylist(
        val id: PlaylistRef,
    ) : LibraryCommand

    /** Reads the M3U playlist in [file] (a content uri) and saves the songs it names as a playlist called [name]. */
    data class ImportPlaylist(
        val file: String,
        val name: String,
    ) : LibraryCommand

    /** Writes [pick] as an M3U playlist into [file] (a content uri). */
    data class ExportPlaylist(
        val pick: Pick,
        val file: String,
    ) : LibraryCommand
}

/** One-off facts the Library announces. */
sealed interface LibraryFact {
    /** [songs] songs were put after the current one ([next]) or at the end of the queue. */
    data class Queued(
        val songs: Int,
        val next: Boolean,
    ) : LibraryFact

    /** An M3U file became the playlist [name]: [found] of its songs are in the Library, [missing] are not. */
    data class PlaylistImported(
        val name: String,
        val found: Int,
        val missing: Int,
    ) : LibraryFact

    data class PlaylistExported(
        val file: String,
        val songs: Int,
    ) : LibraryFact
}
