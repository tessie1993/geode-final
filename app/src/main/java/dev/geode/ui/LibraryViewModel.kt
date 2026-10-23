package dev.geode.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.geode.data.NativeTags
import dev.geode.data.SmartPlaylist
import dev.geode.data.TagWriteOutcome
import dev.geode.data.TrackTagEdit
import dev.geode.engine.types.TrackId
import dev.geode.feature.library.api.LibraryFact
import dev.geode.playback.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val KEY_QUERY = "library_query"
private const val KEY_SORT = "library_sort"

/**
 * Keeps the combined state alive briefly after the last collector goes away, so a rotation or a
 * tab switch does not re-run the whole filter over the library.
 */
private val WhileUiSubscribed = SharingStarted.WhileSubscribed(5_000)

/**
 * Everything the library screen draws, in one immutable value.
 *
 * [tracks] is already searched and sorted — the screen renders what it is given rather than
 * deciding what to show, so the ordering rules live somewhere they can be reasoned about.
 *
 * There is deliberately no analysis state here. Nothing in the library waits on analysis, so
 * there is no "analysed" flag, no tempo and no key for a row to display.
 */
data class LibraryUiState(
    val tracks: List<DeviceTrack> = emptyList(),
    val query: String = "",
    val sort: LibrarySort = LibrarySort.TITLE,
    val isScanning: Boolean = false,
) {
    /** Distinguishes "your library is empty" from "nothing matched", which need different advice. */
    val isSearching: Boolean get() = query.isNotBlank()
}

/** The old Library screens over the one Library store (#17), through [OldScreenLibrary]. */
@HiltViewModel
class LibraryViewModel
    @Inject
    constructor(
        private val old: OldScreenLibrary,
        private val application: Application,
        private val savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        // Search and sort survive process death with the rest of the screen's saved state, so
        // coming back to a filtered library does not silently reset it.
        private val queryFlow: StateFlow<String> = savedStateHandle.getStateFlow(KEY_QUERY, "")
        private val sortFlow =
            savedStateHandle
                .getStateFlow(KEY_SORT, LibrarySort.TITLE.name)
                .map { stored -> LibrarySort.entries.firstOrNull { it.name == stored } ?: LibrarySort.TITLE }

        val uiState: StateFlow<LibraryUiState> =
            combine(old.deviceTracks, old.scanning, queryFlow, sortFlow) { tracks, scanning, query, sort ->
                LibraryUiState(
                    tracks = LibraryBrowse.sort(LibraryBrowse.search(tracks, query), sort),
                    query = query,
                    sort = sort,
                    isScanning = scanning,
                )
            }.stateIn(
                scope = viewModelScope,
                started = WhileUiSubscribed,
                initialValue = LibraryUiState(),
            )

        fun setQuery(query: String) {
            savedStateHandle[KEY_QUERY] = query
        }

        fun setSort(sort: LibrarySort) {
            savedStateHandle[KEY_SORT] = sort.name
        }

        val mediaRoots: StateFlow<Set<String>> get() = old.musicFolders

        val library: StateFlow<LibraryLists> get() = old.lists

        val deviceTracks: StateFlow<List<DeviceTrack>> get() = old.deviceTracks

        val libraryScanning: StateFlow<Boolean> get() = old.scanning

        val playlistsImported: Flow<LibraryFact.PlaylistImported> get() = old.playlistsImported

        fun refreshDeviceTracks() = old.refresh()

        fun play(
            songs: List<TrackId>,
            from: TrackId? = null,
        ) = old.play(songs, from)

        fun shuffle(songs: List<TrackId>) = old.shuffle(songs)

        fun playNext(song: TrackId) = old.playNext(song)

        fun enqueue(song: TrackId) = old.enqueue(song)

        /** A picked folder is remembered by Settings, and the Library looks through the phone's music again. */
        fun importFolder(treeUri: Uri) {
            runCatching { application.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            old.addMusicFolder(treeUri.toString())
        }

        fun removeMediaRoot(uriStr: String) = old.removeMusicFolder(uriStr)

        fun rescanMediaRoots() = old.rescan()

        fun trackInfoFor(uri: TrackId): LibraryTrack = old.trackInfo(uri)

        fun saveTrackInfo(
            uri: TrackId,
            title: String,
            artist: String,
            album: String,
        ) = old.saveTrackInfo(uri, title, artist, album)

        /** Writes the edit into the audio file itself, keeping its album artist; see [TagWriteOutcome] for the result. */
        suspend fun writeTrackInfo(
            uri: TrackId,
            title: String,
            artist: String,
            album: String,
            genre: String,
            year: Int,
            trackNo: Int,
            comment: String,
        ): TagWriteOutcome =
            withContext(Dispatchers.IO) {
                val target = uri.toUri()
                val resolver = application.contentResolver
                val existing = NativeTags.read(resolver, target)
                val edit =
                    TrackTagEdit(
                        title = title,
                        artist = artist,
                        album = album,
                        albumArtist = existing?.albumArtist.orEmpty(),
                        genre = genre,
                        comment = comment,
                        year = year,
                        track = trackNo,
                    )
                NativeTags.write(resolver, target, edit)
            }

        fun createMusicPlaylist(
            name: String,
            songs: List<TrackId>,
        ) = old.createPlaylist(name, songs)

        fun importPlaylistFile(uri: Uri) = old.importPlaylist(uri.toString())

        fun deleteMusicPlaylist(name: String) = old.deletePlaylist(name)

        fun playPlaylist(playlist: String) = old.playPlaylist(playlist)

        fun saveSmartPlaylist(playlist: SmartPlaylist) = old.saveSmartPlaylist(playlist)

        fun deleteSmartPlaylist(name: String) = old.deleteSmartPlaylist(name)

        fun resolveSmartPlaylist(playlist: SmartPlaylist): List<DeviceTrack> = old.resolveSmartPlaylist(playlist)

        fun playSmartPlaylist(playlist: SmartPlaylist) = old.playSmartPlaylist(playlist)
    }
