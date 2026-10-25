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
    /** The year the user gave it in the app; 0 for none. */
    val year: Int = 0,
    /** The track number the user gave it in the app; 0 for none. */
    val trackNumber: Int = 0,
    /** The comment the user gave it in the app. */
    val comment: String = "",
) {
    /** The folder the file is in. */
    val folder: String get() = path.substringBeforeLast('/', "")

    /** The album artist, or the artist when the tags name none. */
    val shownAlbumArtist: String get() = albumArtist.ifBlank { artist }
}

/**
 * The song info a user can edit in the app (#37). [artwork] is a content uri, or null to keep the file's own.
 * [genre], [year], [trackNumber] and [comment] are null when left as they were; a year or track number of 0 is none.
 */
data class SongEdit(
    val title: String,
    val artist: String,
    val album: String,
    val artwork: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val comment: String? = null,
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

    /** Tracks that look like the same recording, side by side, so a spare copy can be checked and deleted. */
    DUPLICATES,
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
    SMART_PLAYLIST,
}

/**
 * One group: the album, artist, genre, composer or folder named [name] (blank when the tags name
 * none), the playlist whose [PlaylistRef] value [name] holds, or the smart playlist named [name].
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
    /** The playlist's songs, in order; a song not in the Library is there too. */
    val songs: List<TrackId> = emptyList(),
)

/** What a smart playlist rule looks at in a song (the fields of the old smart playlist editor, kept as they were). */
enum class RuleField {
    TITLE,
    ARTIST,
    ALBUM,
    FOLDER,

    /** The song's length, in minutes. */
    DURATION_MINUTES,

    /** The days since the song was added to the phone. */
    ADDED_DAYS_AGO,

    /** How often the song was played. */
    PLAY_COUNT,

    /** Whether the song is a favourite. */
    FAVOURITE,
    ;

    /** Whether the field is words, compared with [RuleOp.CONTAINS], [RuleOp.IS] or [RuleOp.IS_NOT]. */
    val isText: Boolean get() = this == TITLE || this == ARTIST || this == ALBUM || this == FOLDER

    /** Whether the field is yes or no, compared with [RuleOp.IS] or [RuleOp.IS_NOT] and no value. */
    val isFlag: Boolean get() = this == FAVOURITE

    /** The comparisons a rule on this field may use. */
    val ops: List<RuleOp>
        get() =
            when {
                isFlag -> listOf(RuleOp.IS, RuleOp.IS_NOT)
                isText -> RuleOp.entries.filter { it.forText }
                else -> RuleOp.entries.filter { it.forNumber }
            }

    /** The comparison a rule starts with when it is set to this field. */
    val defaultOp: RuleOp
        get() =
            when {
                isText -> RuleOp.CONTAINS
                isFlag -> RuleOp.IS
                else -> RuleOp.AT_LEAST
            }
}

/** How a smart playlist rule compares its field with its value. Text is compared ignoring case. */
enum class RuleOp {
    CONTAINS,
    IS,
    IS_NOT,
    AT_LEAST,
    AT_MOST,
    ;

    val forText: Boolean get() = this == CONTAINS || this == IS || this == IS_NOT

    val forNumber: Boolean get() = this == IS || this == AT_LEAST || this == AT_MOST
}

/** One rule of a smart playlist: [field] compared by [op] with [value]. A rule with a blank value is left out, except a yes-or-no one. */
data class SmartRule(
    val field: RuleField,
    val op: RuleOp,
    val value: String,
)

/**
 * A playlist the Library fills by itself from [rules] (#146): the songs that match all of them
 * ([matchAll]) or any one, at most [limit] of them (all when 0). No rules picks every song. Its
 * songs change as the Library does. The name is unique among smart playlists.
 */
data class SmartPlaylist(
    val name: String,
    val rules: List<SmartRule> = emptyList(),
    val matchAll: Boolean = true,
    val limit: Int = 0,
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

    /** Tracks that look like the same recording, a group each. */
    data class Duplicates(
        val groups: List<DuplicateGroup>,
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

/**
 * Tracks that look like the same recording: the same [title] and [artist] once case, punctuation and
 * bracketed notes are set aside, and lengths within [DUPLICATE_LENGTH_SLACK] of each other.
 */
data class DuplicateGroup(
    val title: String,
    val artist: String,
    val rows: List<SongRow>,
)

/** How far apart two copies of one recording may be in length. */
val DUPLICATE_LENGTH_SLACK = Millis(2_000)

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
    /** The smart playlists, by name. */
    val smartPlaylists: List<SmartPlaylist> = emptyList(),
    /** Playback's history, the most recent first. */
    val plays: List<Play> = emptyList(),
    /** Wall-clock time as the Library last read it, for the rules that count days; null before the first reading. */
    val now: Millis? = null,
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

    /** Opens a group of the view being shown. A playlist is played, not opened; a smart playlist opens to its songs. */
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

    /**
     * Changes a song's info in the Library. With [writeToFile] the edit is written into the file's own
     * tags first, and the Library changes only once it is; otherwise the file is left as it is.
     */
    data class EditSong(
        val song: TrackId,
        val edit: SongEdit,
        val writeToFile: Boolean = false,
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

    /** Plays [pick] with shuffle turned on. */
    data class Shuffle(
        val pick: Pick,
    ) : LibraryCommand

    data class PlayNext(
        val pick: Pick,
    ) : LibraryCommand

    data class AddToQueue(
        val pick: Pick,
    ) : LibraryCommand

    /** Saves [pick] as a playlist called [name]; with no songs, an empty one to fill later. */
    data class CreatePlaylist(
        val name: String,
        val pick: Pick,
    ) : LibraryCommand

    data class RenamePlaylist(
        val id: PlaylistRef,
        val name: String,
    ) : LibraryCommand

    /** Puts [pick] at the end of the playlist. */
    data class AddToPlaylist(
        val id: PlaylistRef,
        val pick: Pick,
    ) : LibraryCommand

    /** Moves the playlist's song at [from] to [to]; indexes are positions in the playlist. */
    data class MoveInPlaylist(
        val id: PlaylistRef,
        val from: Int,
        val to: Int,
    ) : LibraryCommand

    data class RemoveFromPlaylist(
        val id: PlaylistRef,
        val index: Int,
    ) : LibraryCommand

    data class DeletePlaylist(
        val id: PlaylistRef,
    ) : LibraryCommand

    /**
     * Saves [playlist] as a smart playlist, in place of the one named [replacing] when it is being
     * edited. Its name is trimmed, and must be neither blank nor another smart playlist's.
     */
    data class SaveSmartPlaylist(
        val playlist: SmartPlaylist,
        val replacing: String? = null,
    ) : LibraryCommand

    data class DeleteSmartPlaylist(
        val name: String,
    ) : LibraryCommand

    /**
     * Reads the M3U, PLS or XSPF playlist in [file] (a content uri) and saves the songs it names as a
     * playlist called [name] (the file's name when blank).
     */
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

    /** The phone must ask the person before [song]'s file may be changed; once it has, [edit] may be sent again. */
    data class TagsNeedConsent(
        val song: TrackId,
        val edit: SongEdit,
    ) : LibraryFact
}
