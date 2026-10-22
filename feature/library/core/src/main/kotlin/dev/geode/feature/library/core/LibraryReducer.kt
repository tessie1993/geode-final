package dev.geode.feature.library.core

import dev.geode.feature.library.api.GroupKey
import dev.geode.feature.library.api.GroupKind
import dev.geode.feature.library.api.IndexStatus
import dev.geode.feature.library.api.LibraryCommand
import dev.geode.feature.library.api.LibraryFact
import dev.geode.feature.library.api.LibraryState
import dev.geode.feature.library.api.Pick
import dev.geode.feature.library.api.Place
import dev.geode.feature.library.api.SmartPlaylist
import dev.geode.feature.library.api.Song
import dev.geode.feature.library.api.SongEdit
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlaylistId
import dev.geode.feature.playback.api.Track
import dev.geode.kernel.Emit
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.Reducer
import dev.geode.kernel.ReportFailure
import dev.geode.kernel.StartTimer

/**
 * The Library's state machine (#37). The index moves Loading → Scanning → Ready (or NeedsPermission),
 * and scans again when the phone's music changes or the user asks. Browsing, searching, sorting and
 * filtering only change what [LibraryState.page] shows, which is worked out again after every event.
 *
 * Playing anything is a Command to Playback, through its `:api` (ADR-0004: Playback owns the queue).
 * The playlists and the history are Playback's too (ADR-0005), so the Library reads them from
 * Playback and asks Playback to change them.
 *
 * The smart playlists (#146) are the Library's own: their rules are kept in its database, and their
 * songs are worked out from the index whenever the page is, so they follow every change to the
 * Library. The clock is read every [CLOCK_TICK] for the rules that count days.
 */
object LibraryReducer : Reducer<LibraryState, LibraryEvent, LibraryEffect> {
    override fun reduce(
        state: LibraryState,
        event: LibraryEvent,
    ): Next<LibraryState, LibraryEffect> =
        when (event) {
            is LibraryEvent.Command -> command(state, event.command)
            is LibraryEvent.IndexRead -> indexRead(state, event)
            is LibraryEvent.SongsFound -> shown(state.copy(songs = event.songs))
            LibraryEvent.ScanFinished -> scanFinished(state)
            is LibraryEvent.ScanFailed -> scanFailed(state, event.failure)
            LibraryEvent.MediaChanged -> rescan(state)
            is LibraryEvent.PlaylistsChanged -> shown(state.copy(playlists = event.playlists))
            is LibraryEvent.PlaysChanged -> shown(state.copy(plays = event.plays))
            is LibraryEvent.PlaylistFileRead -> imported(state, event.name, event.text)
            is LibraryEvent.PlaylistFileWritten -> Next(state, listOf(Emit(LibraryFact.PlaylistExported(event.file, event.songs))))
            is LibraryEvent.StorageFailed -> storageFailed(state, event.failure)
            is LibraryEvent.ClockRead -> shown(state.copy(now = event.now), StartTimer(ClockTick, CLOCK_TICK, LibraryEvent.ClockDue))
            LibraryEvent.ClockDue -> Next(state, listOf(Perform(LibraryWork.ReadClock)))
        }

    // The index.

    private fun indexRead(
        state: LibraryState,
        event: LibraryEvent.IndexRead,
    ): Next<LibraryState, LibraryEffect> {
        val known =
            state.copy(
                favourites = event.favourites,
                excludedFolders = event.excludedFolders,
                smartPlaylists = event.smartPlaylists.sortedWith(byName),
            )
        return when (state.index) {
            IndexStatus.Loading -> scan(known.copy(songs = event.songs))
            // A scan got there first: its songs are newer than the saved ones.
            IndexStatus.Scanning, IndexStatus.Ready, IndexStatus.NeedsPermission -> shown(known)
        }
    }

    private fun scanFinished(state: LibraryState): Next<LibraryState, LibraryEffect> =
        when (state.index) {
            IndexStatus.Scanning -> Next(state.copy(index = IndexStatus.Ready))
            IndexStatus.Loading, IndexStatus.Ready, IndexStatus.NeedsPermission -> Next(state)
        }

    private fun scanFailed(
        state: LibraryState,
        failure: ScanFailure,
    ): Next<LibraryState, LibraryEffect> =
        when (state.index) {
            IndexStatus.Scanning ->
                when (failure) {
                    ScanFailure.NO_PERMISSION -> Next(state.copy(index = IndexStatus.NeedsPermission))
                    ScanFailure.MEDIA_INDEX -> Next(state.copy(index = IndexStatus.Ready), listOf(ReportFailure(describe(failure))))
                }
            IndexStatus.Loading, IndexStatus.Ready, IndexStatus.NeedsPermission -> Next(state)
        }

    private fun rescan(state: LibraryState): Next<LibraryState, LibraryEffect> =
        when (state.index) {
            // The scan that follows the read, or the one under way, will see the change.
            IndexStatus.Loading, IndexStatus.Scanning -> Next(state)
            IndexStatus.Ready, IndexStatus.NeedsPermission -> scan(state)
        }

    private fun scan(state: LibraryState): Next<LibraryState, LibraryEffect> =
        shown(state.copy(index = IndexStatus.Scanning), Perform(LibraryWork.Scan))

    private fun storageFailed(
        state: LibraryState,
        failure: StorageFailure,
    ): Next<LibraryState, LibraryEffect> {
        val report = ReportFailure(describe(failure))
        // Without the saved index, the scan finds the songs anyway.
        return if (failure == StorageFailure.READ_INDEX && state.index == IndexStatus.Loading) {
            val scanning = scan(state)
            Next(scanning.state, scanning.effects + report)
        } else {
            Next(state, listOf(report))
        }
    }

    // Commands.

    private fun command(
        state: LibraryState,
        command: LibraryCommand,
    ): Next<LibraryState, LibraryEffect> =
        when (command) {
            is LibraryCommand.Show -> shown(state.copy(place = Place.Shelf(command.view), query = ""))
            is LibraryCommand.Open -> open(state, command)
            LibraryCommand.Back -> back(state)
            is LibraryCommand.Search -> shown(state.copy(query = command.query))
            is LibraryCommand.SortBy -> shown(state.copy(sort = command.sort))
            is LibraryCommand.FilterBy -> shown(state.copy(filter = command.filter))
            LibraryCommand.Rescan -> rescan(state)
            is LibraryCommand.ToggleFavourite -> toggleFavourite(state, command)
            is LibraryCommand.EditSong -> edit(state, command)
            is LibraryCommand.ExcludeFolder -> exclude(state, command.folder)
            is LibraryCommand.IncludeFolder -> include(state, command.folder)
            is LibraryCommand.Play -> play(state, command)
            is LibraryCommand.PlayNext -> enqueue(state, command.pick, next = true)
            is LibraryCommand.AddToQueue -> enqueue(state, command.pick, next = false)
            is LibraryCommand.CreatePlaylist -> createPlaylist(state, command)
            is LibraryCommand.DeletePlaylist -> Next(state, listOf(tell(PlaybackCommand.DeletePlaylist(PlaylistId(command.id.value)))))
            is LibraryCommand.SaveSmartPlaylist -> saveSmart(state, command)
            is LibraryCommand.DeleteSmartPlaylist -> deleteSmart(state, command.name)
            is LibraryCommand.ImportPlaylist -> import(state, command)
            is LibraryCommand.ExportPlaylist -> export(state, command)
        }

    private fun open(
        state: LibraryState,
        command: LibraryCommand.Open,
    ): Next<LibraryState, LibraryEffect> {
        // A playlist's songs are Playback's: it is played rather than opened.
        if (command.key.kind == GroupKind.PLAYLIST) return Next(state)
        val view =
            when (val place = state.place) {
                is Place.Shelf -> place.view
                is Place.Group -> place.view
            }
        return shown(state.copy(place = Place.Group(view, command.key), query = ""))
    }

    private fun back(state: LibraryState): Next<LibraryState, LibraryEffect> {
        if (state.query.isNotEmpty()) return shown(state.copy(query = ""))
        return when (val place = state.place) {
            is Place.Shelf -> Next(state)
            is Place.Group -> shown(state.copy(place = Place.Shelf(place.view)))
        }
    }

    private fun toggleFavourite(
        state: LibraryState,
        command: LibraryCommand.ToggleFavourite,
    ): Next<LibraryState, LibraryEffect> {
        if (state.songs.none { it.id == command.song }) return Next(state)
        val favourite = command.song !in state.favourites
        val favourites = if (favourite) state.favourites + command.song else state.favourites - command.song
        return shown(state.copy(favourites = favourites), Perform(LibraryWork.SaveFavourite(command.song, favourite)))
    }

    private fun edit(
        state: LibraryState,
        command: LibraryCommand.EditSong,
    ): Next<LibraryState, LibraryEffect> {
        val edit =
            SongEdit(
                title = command.edit.title.trim(),
                artist = command.edit.artist.trim(),
                album = command.edit.album.trim(),
                artwork = command.edit.artwork?.takeIf { it.isNotBlank() },
            )
        if (edit.title.isEmpty() || state.songs.none { it.id == command.song }) return Next(state)
        val songs = state.songs.map { if (it.id == command.song) it.edited(edit) else it }
        return shown(state.copy(songs = songs), Perform(LibraryWork.SaveEdit(command.song, edit)))
    }

    private fun exclude(
        state: LibraryState,
        folder: String,
    ): Next<LibraryState, LibraryEffect> {
        if (folder.isBlank() || folder in state.excludedFolders) return Next(state)
        val folders = state.excludedFolders + folder
        return shown(state.copy(excludedFolders = folders), Perform(LibraryWork.SaveExcludedFolders(folders)))
    }

    private fun include(
        state: LibraryState,
        folder: String,
    ): Next<LibraryState, LibraryEffect> {
        if (folder !in state.excludedFolders) return Next(state)
        val folders = state.excludedFolders - folder
        return shown(state.copy(excludedFolders = folders), Perform(LibraryWork.SaveExcludedFolders(folders)))
    }

    private fun play(
        state: LibraryState,
        command: LibraryCommand.Play,
    ): Next<LibraryState, LibraryEffect> {
        val pick = command.pick
        if (pick is Pick.Group && pick.key.kind == GroupKind.PLAYLIST) {
            val id = pick.key.name.toLongOrNull() ?: return Next(state)
            return Next(state, listOf(tell(PlaybackCommand.PlayPlaylist(PlaylistId(id)))))
        }
        val songs = LibraryPages.songsOf(state, pick)
        if (songs.isEmpty()) return Next(state)
        val startAt = songs.indexOfFirst { it.id == command.from }.coerceAtLeast(0)
        return Next(state, listOf(tell(PlaybackCommand.Play(songs.map { it.toTrack() }, startAt))))
    }

    private fun enqueue(
        state: LibraryState,
        pick: Pick,
        next: Boolean,
    ): Next<LibraryState, LibraryEffect> {
        val songs = LibraryPages.songsOf(state, pick)
        if (songs.isEmpty()) return Next(state)
        val tracks = songs.map { it.toTrack() }
        val command = if (next) PlaybackCommand.PlayNext(tracks) else PlaybackCommand.AddToQueue(tracks)
        return Next(state, listOf(tell(command), Emit(LibraryFact.Queued(songs.size, next))))
    }

    private fun createPlaylist(
        state: LibraryState,
        command: LibraryCommand.CreatePlaylist,
    ): Next<LibraryState, LibraryEffect> {
        val songs = LibraryPages.songsOf(state, command.pick)
        if (command.name.isBlank() || songs.isEmpty()) return Next(state)
        return Next(state, listOf(tell(PlaybackCommand.CreatePlaylist(command.name.trim(), songs.map { it.toTrack() }))))
    }

    private fun saveSmart(
        state: LibraryState,
        command: LibraryCommand.SaveSmartPlaylist,
    ): Next<LibraryState, LibraryEffect> {
        val playlist = SmartPlaylists.tidied(command.playlist)
        val others = state.smartPlaylists.filterNot { it.name == command.replacing }
        if (playlist.name.isEmpty() || others.any { it.name == playlist.name }) return Next(state)
        val place = state.place
        // The smart playlist being shown keeps showing under its new name.
        val renamed =
            if (place is Place.Group && place.key == smartKey(command.replacing)) place.copy(key = GroupKey(GroupKind.SMART_PLAYLIST, playlist.name)) else place
        return shown(
            state.copy(smartPlaylists = (others + playlist).sortedWith(byName), place = renamed),
            Perform(LibraryWork.SaveSmartPlaylist(playlist, command.replacing)),
        )
    }

    private fun deleteSmart(
        state: LibraryState,
        name: String,
    ): Next<LibraryState, LibraryEffect> {
        if (state.smartPlaylists.none { it.name == name }) return Next(state)
        val place = state.place
        // Out of the smart playlist being shown, to its view.
        val left = if (place is Place.Group && place.key == smartKey(name)) Place.Shelf(place.view) else place
        return shown(
            state.copy(smartPlaylists = state.smartPlaylists.filterNot { it.name == name }, place = left),
            Perform(LibraryWork.DeleteSmartPlaylist(name)),
        )
    }

    private fun import(
        state: LibraryState,
        command: LibraryCommand.ImportPlaylist,
    ): Next<LibraryState, LibraryEffect> {
        if (command.file.isBlank()) return Next(state)
        val name = command.name.trim().ifEmpty { command.file.substringAfterLast('/').substringBeforeLast('.') }
        return Next(state, listOf(Perform(LibraryWork.ReadPlaylistFile(command.file, name))))
    }

    private fun imported(
        state: LibraryState,
        name: String,
        text: String,
    ): Next<LibraryState, LibraryEffect> {
        val entries = M3u.read(text)
        val found = M3u.match(entries, state.songs)
        val fact = Emit(LibraryFact.PlaylistImported(name, found.size, entries.size - found.size))
        val save = if (found.isEmpty()) emptyList() else listOf(tell(PlaybackCommand.CreatePlaylist(name, found.map { it.toTrack() })))
        return Next(state, save + fact)
    }

    private fun export(
        state: LibraryState,
        command: LibraryCommand.ExportPlaylist,
    ): Next<LibraryState, LibraryEffect> {
        val songs = LibraryPages.songsOf(state, command.pick)
        if (command.file.isBlank() || songs.isEmpty()) return Next(state)
        return Next(state, listOf(Perform(LibraryWork.WritePlaylistFile(command.file, M3u.write(songs), songs.size))))
    }

    // Helpers.

    /** [state] with its page worked out again, and [effects]. */
    private fun shown(
        state: LibraryState,
        vararg effects: LibraryEffect,
    ): Next<LibraryState, LibraryEffect> = Next(state.copy(page = LibraryPages.pageOf(state)), effects.toList())

    private fun tell(command: PlaybackCommand): LibraryEffect = Perform(LibraryWork.Tell(command))

    private fun Song.edited(edit: SongEdit): Song =
        copy(title = edit.title, artist = edit.artist, album = edit.album, artwork = edit.artwork)

    private fun smartKey(name: String?): GroupKey? = name?.let { GroupKey(GroupKind.SMART_PLAYLIST, it) }

    /** Smart playlists by name, ignoring case, as the old editor listed them. */
    private val byName: Comparator<SmartPlaylist> = compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
}

/** A song as Playback plays it. */
fun Song.toTrack(): Track = Track(id, title, artist, duration)

/** What the error channel's banner says about [failure]. */
fun describe(failure: ScanFailure): String =
    when (failure) {
        ScanFailure.NO_PERMISSION -> "Geode may not read the music on this phone"
        ScanFailure.MEDIA_INDEX -> "Could not look through the music on this phone"
    }

/** What the error channel's banner says about [failure]. */
fun describe(failure: StorageFailure): String =
    when (failure) {
        StorageFailure.READ_INDEX -> "Could not read the saved library"
        StorageFailure.SAVE_INDEX -> "Could not save the library"
        StorageFailure.SAVE_FAVOURITE -> "Could not save the favourite"
        StorageFailure.SAVE_EDIT -> "Could not save the song info"
        StorageFailure.SAVE_EXCLUDED_FOLDERS -> "Could not save the excluded folders"
        StorageFailure.READ_PLAYLIST_FILE -> "Could not read the playlist file"
        StorageFailure.WRITE_PLAYLIST_FILE -> "Could not write the playlist file"
        StorageFailure.SAVE_SMART_PLAYLIST -> "Could not save the smart playlist"
        StorageFailure.DELETE_SMART_PLAYLIST -> "Could not delete the smart playlist"
    }
