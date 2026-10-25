package dev.geode.feature.library.handlers

import dev.geode.engine.types.TrackId
import dev.geode.feature.library.api.SmartPlaylist
import dev.geode.feature.library.api.Song
import dev.geode.feature.library.api.SongEdit
import dev.geode.feature.library.core.edited
import dev.geode.feature.playback.api.Playback
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlaybackFact
import dev.geode.feature.playback.api.PlaybackState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

/** Playback as the Library sees it through its `:api`: a state to read, and the Commands it was sent. */
class FakePlayback : Playback {
    override val state = MutableStateFlow(PlaybackState())
    override val events: Flow<PlaybackFact> = emptyFlow()
    val sent = mutableListOf<PlaybackCommand>()

    override fun send(command: PlaybackCommand) {
        sent += command
    }
}

class FakeScanner : MediaScanner {
    /** What the next scan finds; null makes it throw. */
    var result: ScanResult? = ScanResult.Found(emptyList())
    var scans = 0
    override val changes = MutableSharedFlow<Unit>()

    override suspend fun scan(): ScanResult {
        scans++
        return result ?: error("the media index is gone")
    }
}

/** [LibraryStorage] in memory, with the same rules as the Room one; [failing] makes every call throw. */
class FakeLibraryStorage : LibraryStorage {
    var failing = false
    val songs = mutableListOf<Song>()
    val tagged = mutableSetOf<TrackId>()
    val edits = mutableMapOf<TrackId, SongEdit>()
    val favourites = mutableSetOf<TrackId>()
    val excludedFolders = mutableSetOf<String>()
    val smartPlaylists = mutableListOf<SmartPlaylist>()

    override suspend fun read(): SavedLibrary =
        guard { SavedLibrary(songs.map(::edited), favourites.toSet(), excludedFolders.toSet(), smartPlaylists.toList()) }

    override suspend fun replaceSongs(found: List<Song>): List<Song> =
        guard {
            val before = songs.associateBy { it.id }
            val kept = found.map { song -> before[song.id]?.takeIf { it.id in tagged && it.path == song.path }?.let { song.keptTags(it) } }
            tagged.retainAll(kept.filterNotNull().map { it.id }.toSet())
            songs.clear()
            songs += found.zip(kept) { song, old -> old ?: song }
            songs.map(::edited)
        }

    override suspend fun songsWithoutTags(): List<Song> = guard { songs.filterNot { it.id in tagged } }

    override suspend fun saveTags(song: Song) =
        guard {
            songs.replaceAll { if (it.id == song.id) song else it }
            tagged += song.id
            Unit
        }

    override suspend fun setFavourite(
        song: TrackId,
        favourite: Boolean,
    ) = guard {
        if (favourite) favourites += song else favourites -= song
        Unit
    }

    override suspend fun saveEdit(
        song: TrackId,
        edit: SongEdit,
    ) = guard {
        edits[song] = edit
        Unit
    }

    override suspend fun edits(): Map<TrackId, SongEdit> = guard { edits.toMap() }

    override suspend fun saveExcludedFolders(folders: Set<String>) =
        guard {
            excludedFolders.clear()
            excludedFolders += folders
            Unit
        }

    override suspend fun saveSmartPlaylist(
        playlist: SmartPlaylist,
        replacing: String?,
    ) = guard {
        smartPlaylists.removeAll { it.name == replacing || it.name == playlist.name }
        smartPlaylists += playlist
        Unit
    }

    override suspend fun deleteSmartPlaylist(name: String) =
        guard {
            smartPlaylists.removeAll { it.name == name }
            Unit
        }

    private fun Song.keptTags(old: Song) = filledFrom(FileTags(old.albumArtist, old.composer, old.genre))

    private fun edited(song: Song): Song = edits[song.id]?.let { song.edited(it) } ?: song

    private fun <T> guard(block: () -> T): T = if (failing) error("the database is gone") else block()
}

class FakePlaylistFiles : PlaylistFiles {
    val files = mutableMapOf<String, String>()

    override suspend fun read(file: String): String = files[file] ?: error("no file $file")

    override suspend fun write(
        file: String,
        text: String,
    ) {
        if (file.startsWith(READ_ONLY)) error("read only")
        files[file] = text
    }

    companion object {
        const val READ_ONLY = "content://read-only/"
    }
}
