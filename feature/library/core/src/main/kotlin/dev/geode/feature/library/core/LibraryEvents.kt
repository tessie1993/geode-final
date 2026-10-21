package dev.geode.feature.library.core

import dev.geode.engine.types.TrackId
import dev.geode.feature.library.api.LibraryCommand
import dev.geode.feature.library.api.LibraryFact
import dev.geode.feature.library.api.LibraryPlaylist
import dev.geode.feature.library.api.Play
import dev.geode.feature.library.api.Song
import dev.geode.feature.library.api.SongEdit
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.kernel.Effect

/** Everything the Library's reducer handles: Commands, and what its handlers find out. */
sealed interface LibraryEvent {
    data class Command(
        val command: LibraryCommand,
    ) : LibraryEvent

    /** The saved index: the songs found last time, the favourites and the excluded folders. */
    data class IndexRead(
        val songs: List<Song>,
        val favourites: Set<TrackId>,
        val excludedFolders: Set<String>,
    ) : LibraryEvent

    /** The index as the scan has it so far, the user's edits applied. A scan may report this more than once. */
    data class SongsFound(
        val songs: List<Song>,
    ) : LibraryEvent

    data object ScanFinished : LibraryEvent

    data class ScanFailed(
        val failure: ScanFailure,
    ) : LibraryEvent

    /** The phone's music changed while the app runs (ADR-0004: Android's media-change notice). */
    data object MediaChanged : LibraryEvent

    /** Playback's playlists, read through its `:api`. */
    data class PlaylistsChanged(
        val playlists: List<LibraryPlaylist>,
    ) : LibraryEvent

    /** Playback's history, read through its `:api`, the most recent first. */
    data class PlaysChanged(
        val plays: List<Play>,
    ) : LibraryEvent

    /** The M3U file being imported as the playlist [name] holds [text]. */
    data class PlaylistFileRead(
        val name: String,
        val text: String,
    ) : LibraryEvent

    data class PlaylistFileWritten(
        val file: String,
        val songs: Int,
    ) : LibraryEvent

    data class StorageFailed(
        val failure: StorageFailure,
    ) : LibraryEvent
}

/** Why a scan of the phone's music did not finish. */
enum class ScanFailure {
    /** The phone does not let Geode read its music. */
    NO_PERMISSION,

    /** The phone's media index could not be read. */
    MEDIA_INDEX,
}

/** What went wrong with the Library's database or a playlist file, as the handlers report it. */
enum class StorageFailure {
    READ_INDEX,
    SAVE_INDEX,
    SAVE_FAVOURITE,
    SAVE_EDIT,
    SAVE_EXCLUDED_FOLDERS,
    READ_PLAYLIST_FILE,
    WRITE_PLAYLIST_FILE,
}

/** Work for the Library's handlers. */
sealed interface LibraryWork {
    /** Read the saved index, favourites and excluded folders. */
    data object Read : LibraryWork

    /**
     * Look through the phone's music (ADR-0004: MediaStore finds the files, Geode's tag reader fills in
     * what is missing), save it as the index, and report it.
     */
    data object Scan : LibraryWork

    /** Report each change to the phone's music for the life of the store. */
    data object WatchMedia : LibraryWork

    /** Follow Playback's playlists and history for the life of the store. */
    data object FollowPlayback : LibraryWork

    /** Send [command] to Playback, through its `:api`. */
    data class Tell(
        val command: PlaybackCommand,
    ) : LibraryWork

    data class SaveFavourite(
        val song: TrackId,
        val favourite: Boolean,
    ) : LibraryWork

    data class SaveEdit(
        val song: TrackId,
        val edit: SongEdit,
    ) : LibraryWork

    data class SaveExcludedFolders(
        val folders: Set<String>,
    ) : LibraryWork

    data class ReadPlaylistFile(
        val file: String,
        val name: String,
    ) : LibraryWork

    data class WritePlaylistFile(
        val file: String,
        val text: String,
        val songs: Int,
    ) : LibraryWork
}

typealias LibraryEffect = Effect<LibraryWork, LibraryEvent, LibraryFact>
