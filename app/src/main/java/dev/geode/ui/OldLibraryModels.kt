package dev.geode.ui

import dev.geode.data.SmartPlaylist
import dev.geode.engine.types.TrackId
import dev.geode.feature.library.api.GroupKey
import dev.geode.feature.library.api.GroupKind
import dev.geode.feature.library.api.IndexStatus
import dev.geode.feature.library.api.LibraryCommand
import dev.geode.feature.library.api.LibraryPlaylist
import dev.geode.feature.library.api.LibraryState
import dev.geode.feature.library.api.Pick
import dev.geode.feature.library.api.Song
import dev.geode.feature.library.api.SongEdit
import dev.geode.feature.playback.api.PlaybackState

/** The old Library screens' lists besides the songs: Playback's playlists, and the old smart playlists (#146). */
data class LibraryLists(
    val playlists: List<LibraryPlaylist> = emptyList(),
    val smartPlaylists: List<SmartPlaylist> = emptyList(),
)

/**
 * The Library's state as the old Library screens draw it, and their old calls as Library Commands
 * (#17). The Library is the one source of truth: these are plain functions of its state, and each goes
 * when the last old screen that uses it is replaced by `LibraryRoute`.
 */
internal object OldLibraryModels {
    /** The songs the old screens list: every song the Library found, its excluded folders left out. */
    fun deviceTracks(state: LibraryState): List<DeviceTrack> =
        state.songs
            .filterNot { song -> state.excludedFolders.any { song.folder == it || song.folder.startsWith("$it/") } }
            .map(::deviceTrackOf)

    fun deviceTrackOf(song: Song): DeviceTrack =
        DeviceTrack(song.id, song.title, song.artist, song.album, song.folder, song.duration, song.addedAt.value / MILLIS_PER_SECOND)

    fun scanning(state: LibraryState): Boolean = state.index == IndexStatus.Scanning

    /** The Library watches the phone's music itself; it only looks again when it may not have been allowed to. */
    fun refresh(state: LibraryState): LibraryCommand? = LibraryCommand.Rescan.takeIf { state.index == IndexStatus.NeedsPermission }

    fun play(
        songs: List<TrackId>,
        from: TrackId? = null,
    ): LibraryCommand = LibraryCommand.Play(Pick.Songs(songs), from)

    fun createPlaylist(
        name: String,
        songs: List<TrackId>,
    ): LibraryCommand = LibraryCommand.CreatePlaylist(name.trim(), Pick.Songs(songs))

    /** The old screens name a playlist; the Library knows it by Playback's id. */
    fun playPlaylist(
        state: LibraryState,
        name: String,
    ): LibraryCommand? =
        playlistNamed(state, name)?.let { LibraryCommand.Play(Pick.Group(GroupKey(GroupKind.PLAYLIST, it.id.value.toString()))) }

    fun deletePlaylist(
        state: LibraryState,
        name: String,
    ): LibraryCommand? = playlistNamed(state, name)?.let { LibraryCommand.DeletePlaylist(it.id) }

    /** The old editor's title, artist and album; its other fields are only written into the file itself. */
    fun edit(
        state: LibraryState,
        song: TrackId,
        title: String,
        artist: String,
        album: String,
    ): LibraryCommand? = songOf(state, song)?.let { LibraryCommand.EditSong(song, SongEdit(title, artist, album, it.artwork)) }

    /** What the old song info editor starts from, or null for a song the Library does not have. */
    fun trackInfoOf(
        state: LibraryState,
        song: TrackId,
    ): LibraryTrack? =
        songOf(state, song)?.let {
            LibraryTrack(uri = it.id, title = it.title, artist = it.artist, duration = it.duration, album = it.album, genre = it.genre)
        }

    /** How often each song was played, from Playback's history, for the old smart playlists. */
    fun playsOf(playback: PlaybackState): Map<TrackId, Int> = playback.history.groupingBy { it.track.id }.eachCount()

    private fun songOf(
        state: LibraryState,
        song: TrackId,
    ): Song? = state.songs.firstOrNull { it.id == song }

    private fun playlistNamed(
        state: LibraryState,
        name: String,
    ): LibraryPlaylist? = state.playlists.firstOrNull { it.name == name }

    private const val MILLIS_PER_SECOND = 1_000L
}
