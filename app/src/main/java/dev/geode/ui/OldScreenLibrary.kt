package dev.geode.ui

import android.app.Application
import dev.geode.data.SmartPlaylist
import dev.geode.data.SmartPlaylistMatcher
import dev.geode.data.SmartPlaylistStore
import dev.geode.engine.types.TrackId
import dev.geode.feature.library.api.Library
import dev.geode.feature.library.api.LibraryCommand
import dev.geode.feature.library.api.LibraryFact
import dev.geode.feature.library.api.LibraryState
import dev.geode.feature.library.api.Pick
import dev.geode.feature.playback.api.Playback
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.settings.api.FolderCommand
import dev.geode.feature.settings.api.FolderUri
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.settings.api.SettingsState
import dev.geode.geodeContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Library as the old Library screens still use it, until `LibraryRoute` replaces them (#17).
 *
 * Every call becomes a Command to the one app-scoped [Library] store, and what the old screens show is
 * read back from its state (ADR-0003: one source of truth). The music folders are Settings', and the
 * old smart playlists keep their own files until #146 replaces their editor.
 */
@Singleton
class OldScreenLibrary internal constructor(
    private val library: Library,
    private val playback: Playback,
    private val settings: Settings,
    private val smart: SmartPlaylistStore,
    private val scope: CoroutineScope,
) {
    @Inject
    constructor(library: Library, playback: Playback, settings: Settings, application: Application) :
        this(library, playback, settings, SmartPlaylistStore(application), application.geodeContainer.appScope)

    private val smartPlaylists = MutableStateFlow<List<SmartPlaylist>>(emptyList())

    val state: StateFlow<LibraryState> get() = library.state

    val deviceTracks: StateFlow<List<DeviceTrack>> = follow(OldLibraryModels::deviceTracks)

    val favourites: StateFlow<Set<TrackId>> = follow { it.favourites }

    val scanning: StateFlow<Boolean> = follow(OldLibraryModels::scanning)

    val lists: StateFlow<LibraryLists> =
        combine(library.state, smartPlaylists) { state, smart -> LibraryLists(state.playlists, smart) }
            .stateIn(scope, SharingStarted.Eagerly, LibraryLists(library.state.value.playlists))

    /** The folders the user picked for their music, kept by Settings. */
    val musicFolders: StateFlow<Set<String>> =
        settings.state
            .map { state -> (state as? SettingsState.Ready)?.values?.folders?.musicFolders.orEmpty().mapTo(mutableSetOf()) { it.value } }
            .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** Each M3U file the Library turned into a playlist. */
    val playlistsImported: Flow<LibraryFact.PlaylistImported> = library.events.filterIsInstance()

    init {
        reloadSmartPlaylists()
    }

    fun refresh() {
        OldLibraryModels.refresh(library.state.value)?.let(library::send)
    }

    fun rescan() = library.send(LibraryCommand.Rescan)

    fun play(
        songs: List<TrackId>,
        from: TrackId? = null,
    ) = library.send(OldLibraryModels.play(songs, from))

    /** The old "Shuffle" button turns shuffle on, then plays the songs. */
    fun shuffle(songs: List<TrackId>) {
        playback.send(PlaybackCommand.SetShuffle(true))
        play(songs)
    }

    fun playNext(song: TrackId) = library.send(LibraryCommand.PlayNext(Pick.Songs(listOf(song))))

    fun enqueue(song: TrackId) = library.send(LibraryCommand.AddToQueue(Pick.Songs(listOf(song))))

    fun toggleFavourite(song: TrackId) = library.send(LibraryCommand.ToggleFavourite(song))

    fun createPlaylist(
        name: String,
        songs: List<TrackId>,
    ) = library.send(OldLibraryModels.createPlaylist(name, songs))

    fun playPlaylist(name: String) {
        OldLibraryModels.playPlaylist(library.state.value, name)?.let(library::send)
    }

    fun deletePlaylist(name: String) {
        OldLibraryModels.deletePlaylist(library.state.value, name)?.let(library::send)
    }

    /** Reads the M3U file the user picked into a playlist; [playlistsImported] tells how it went. */
    fun importPlaylist(file: String) = library.send(LibraryCommand.ImportPlaylist(file, name = ""))

    fun addMusicFolder(folder: String) {
        settings.send(FolderCommand.AddMusicFolder(FolderUri(folder)))
        library.send(LibraryCommand.Rescan)
    }

    fun removeMusicFolder(folder: String) = settings.send(FolderCommand.RemoveMusicFolder(FolderUri(folder)))

    /** What the old song info editor starts from. */
    fun trackInfo(song: TrackId): LibraryTrack =
        OldLibraryModels.trackInfoOf(library.state.value, song) ?: LibraryTrack(song, OldScreenModels.fileNameOf(song))

    fun saveTrackInfo(
        song: TrackId,
        title: String,
        artist: String,
        album: String,
    ) {
        OldLibraryModels.edit(library.state.value, song, title, artist, album)?.let(library::send)
    }

    /** A smart playlist's songs; "play count" counts the plays in Playback's history (#146 replaces this). */
    fun resolveSmartPlaylist(playlist: SmartPlaylist): List<DeviceTrack> {
        val plays = OldLibraryModels.playsOf(playback.state.value)
        return SmartPlaylistMatcher.resolve(playlist, deviceTracks.value, { plays[it] ?: 0 }, favourites.value)
    }

    fun playSmartPlaylist(playlist: SmartPlaylist) {
        val songs = resolveSmartPlaylist(playlist).map { it.uri }
        if (songs.isNotEmpty()) play(songs, songs.first())
    }

    fun saveSmartPlaylist(playlist: SmartPlaylist) = changeSmartPlaylists { smart.save(playlist) }

    fun deleteSmartPlaylist(name: String) = changeSmartPlaylists { smart.delete(name) }

    private fun changeSmartPlaylists(change: () -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) { change() }
            reloadSmartPlaylists()
        }
    }

    private fun reloadSmartPlaylists() {
        scope.launch { smartPlaylists.value = withContext(Dispatchers.IO) { smart.list() } }
    }

    private fun <T> follow(read: (LibraryState) -> T): StateFlow<T> =
        library.state.map(read).stateIn(scope, SharingStarted.Eagerly, read(library.state.value))
}
