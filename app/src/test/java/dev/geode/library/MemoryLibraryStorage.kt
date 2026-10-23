package dev.geode.library

import dev.geode.engine.types.TrackId
import dev.geode.feature.library.api.SmartPlaylist
import dev.geode.feature.library.api.Song
import dev.geode.feature.library.api.SongEdit
import dev.geode.feature.library.handlers.LibraryStorage
import dev.geode.feature.library.handlers.SavedLibrary

/** The Library's storage in memory, for the switch-over's tests (#17). */
class MemoryLibraryStorage : LibraryStorage {
    val songs = mutableListOf<Song>()
    val edits = mutableMapOf<TrackId, SongEdit>()
    val favourites = mutableSetOf<TrackId>()
    var excludedFolders: Set<String> = emptySet()
    val smartPlaylists = mutableListOf<SmartPlaylist>()

    override suspend fun read(): SavedLibrary =
        SavedLibrary(
            songs.map(::edited),
            favourites.toSet(),
            excludedFolders,
            smartPlaylists.toList(),
        )

    override suspend fun replaceSongs(found: List<Song>): List<Song> {
        songs.clear()
        songs += found
        return songs.map(::edited)
    }

    override suspend fun songsWithoutTags(): List<Song> = emptyList()

    override suspend fun saveTags(song: Song) = Unit

    override suspend fun setFavourite(
        song: TrackId,
        favourite: Boolean,
    ) {
        if (favourite) favourites += song else favourites -= song
    }

    override suspend fun saveEdit(
        song: TrackId,
        edit: SongEdit,
    ) {
        edits[song] = edit
    }

    override suspend fun edits(): Map<TrackId, SongEdit> = edits.toMap()

    override suspend fun saveExcludedFolders(folders: Set<String>) {
        excludedFolders = folders
    }

    override suspend fun saveSmartPlaylist(
        playlist: SmartPlaylist,
        replacing: String?,
    ) {
        smartPlaylists.removeAll { it.name == replacing || it.name == playlist.name }
        smartPlaylists += playlist
    }

    override suspend fun deleteSmartPlaylist(name: String) {
        smartPlaylists.removeAll { it.name == name }
    }

    private fun edited(song: Song): Song =
        edits[song.id]?.let { song.copy(title = it.title, artist = it.artist, album = it.album, artwork = it.artwork) } ?: song
}
