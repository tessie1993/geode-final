package dev.geode.feature.library.handlers

import dev.geode.engine.types.Millis
import dev.geode.feature.library.api.Library
import dev.geode.feature.library.api.LibraryCommand
import dev.geode.feature.library.api.LibraryFact
import dev.geode.feature.library.api.LibraryPlaylist
import dev.geode.feature.library.api.LibraryState
import dev.geode.feature.library.api.Play
import dev.geode.feature.library.api.PlaylistRef
import dev.geode.feature.library.core.LibraryEvent
import dev.geode.feature.library.core.LibraryReducer
import dev.geode.feature.library.core.LibraryWork
import dev.geode.feature.library.core.ScanFailure
import dev.geode.feature.library.core.StorageFailure
import dev.geode.feature.playback.api.Playback
import dev.geode.kernel.EffectRunner
import dev.geode.kernel.FeatureRuntime
import dev.geode.kernel.FeatureStore
import dev.geode.kernel.Perform
import dev.geode.kernel.Results
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The feature name failures are reported under. */
const val LIBRARY = "library"

/** How long the phone's music must stay unchanged before the Library looks again: changes come in bursts. */
val MEDIA_CHANGE_QUIET = Millis(2_000)

/**
 * Where the Library's handlers read and write: the phone's media index, Geode's tag reader, the
 * Library's own database, and the playlist files the user picks.
 */
class LibrarySources(
    val scanner: MediaScanner,
    val tags: TagReader,
    val storage: LibraryStorage,
    val files: PlaylistFiles,
)

/**
 * Carries out the Library's work (ADR-0005): it scans the phone's music into the index, keeps the
 * favourites, edits and excluded folders, reads and writes playlist files, and talks to Playback
 * through its `:api` only, sending it Commands and reading its playlists and history. Every failure
 * is caught here and reported as a named event.
 */
class LibraryHandlers(
    private val playback: Playback,
    private val sources: LibrarySources,
) : EffectRunner<LibraryWork, LibraryEvent> {
    private val storage get() = sources.storage

    override suspend fun run(
        work: LibraryWork,
        results: Results<LibraryEvent>,
    ) {
        when (work) {
            LibraryWork.Read ->
                guarded(results, StorageFailure.READ_INDEX) {
                    val saved = storage.read()
                    results.report(LibraryEvent.IndexRead(saved.songs, saved.favourites, saved.excludedFolders))
                }
            LibraryWork.Scan -> scan(results)
            LibraryWork.WatchMedia -> watchMedia(results)
            LibraryWork.FollowPlayback -> followPlayback(results)
            is LibraryWork.Tell -> playback.send(work.command)
            is LibraryWork.SaveFavourite ->
                guarded(results, StorageFailure.SAVE_FAVOURITE) { storage.setFavourite(work.song, work.favourite) }
            is LibraryWork.SaveEdit -> guarded(results, StorageFailure.SAVE_EDIT) { storage.saveEdit(work.song, work.edit) }
            is LibraryWork.SaveExcludedFolders ->
                guarded(results, StorageFailure.SAVE_EXCLUDED_FOLDERS) { storage.saveExcludedFolders(work.folders) }
            is LibraryWork.ReadPlaylistFile ->
                guarded(results, StorageFailure.READ_PLAYLIST_FILE) {
                    results.report(LibraryEvent.PlaylistFileRead(work.name, sources.files.read(work.file)))
                }
            is LibraryWork.WritePlaylistFile ->
                guarded(results, StorageFailure.WRITE_PLAYLIST_FILE) {
                    sources.files.write(work.file, work.text)
                    results.report(LibraryEvent.PlaylistFileWritten(work.file, work.songs))
                }
        }
    }

    /**
     * Looks through the phone's music: what the media index has is reported at once, then the tag
     * reader fills in what it left blank, and the index is reported again if that changed anything.
     */
    private suspend fun scan(results: Results<LibraryEvent>) {
        val found =
            when (val scanned = attempt { sources.scanner.scan() }) {
                null -> return results.report(LibraryEvent.ScanFailed(ScanFailure.MEDIA_INDEX))
                ScanResult.NoPermission -> return results.report(LibraryEvent.ScanFailed(ScanFailure.NO_PERMISSION))
                is ScanResult.Found -> scanned.songs
            }
        val saved = attempt { storage.replaceSongs(found) }
        if (saved == null) {
            // The index could not be saved: show what was found anyway.
            results.report(LibraryEvent.SongsFound(found))
            results.report(LibraryEvent.StorageFailed(StorageFailure.SAVE_INDEX))
        } else {
            results.report(LibraryEvent.SongsFound(saved))
            fillTags(results)
        }
        results.report(LibraryEvent.ScanFinished)
    }

    private suspend fun fillTags(results: Results<LibraryEvent>) {
        val untagged = attempt { storage.songsWithoutTags() }.orEmpty()
        if (untagged.isEmpty()) return
        var changed = false
        for (song in untagged) {
            val tags = attempt { sources.tags.read(song) }
            val filled = if (tags == null) song else song.filledFrom(tags)
            if (attempt { storage.saveTags(filled) } == null) {
                return results.report(LibraryEvent.StorageFailed(StorageFailure.SAVE_INDEX))
            }
            changed = changed || filled != song
        }
        if (changed) attempt { storage.read() }?.let { results.report(LibraryEvent.SongsFound(it.songs)) }
    }

    @OptIn(FlowPreview::class)
    private suspend fun watchMedia(results: Results<LibraryEvent>) =
        sources.scanner.changes
            .debounce(MEDIA_CHANGE_QUIET.value)
            .collect { results.report(LibraryEvent.MediaChanged) }

    private suspend fun followPlayback(results: Results<LibraryEvent>) =
        coroutineScope {
            launch {
                playback.state
                    .map { state ->
                        state.playlists.map { playlist ->
                            LibraryPlaylist(
                                PlaylistRef(playlist.id.value),
                                playlist.name,
                                playlist.trackCount,
                                playlist.tracks.map { it.id },
                            )
                        }
                    }.distinctUntilChanged()
                    .collect { results.report(LibraryEvent.PlaylistsChanged(it)) }
            }
            launch {
                playback.state
                    .map { state -> state.history.map { Play(it.track.id, it.playedAt) } }
                    .distinctUntilChanged()
                    .collect { results.report(LibraryEvent.PlaysChanged(it)) }
            }
        }

    /** [block]'s result, or null when it threw. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Handlers catch everything at their edge (ADR-0005).
    private suspend fun <T> attempt(block: suspend () -> T): T? =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            null
        }

    private suspend fun guarded(
        results: Results<LibraryEvent>,
        failure: StorageFailure,
        block: suspend () -> Unit,
    ) {
        if (attempt(block) == null) results.report(LibraryEvent.StorageFailed(failure))
    }
}

/**
 * How `:app` builds the Library's one app-scoped store (#17): it reads the saved index (and then
 * scans), watches the phone's music for changes, and follows Playback's playlists and history.
 */
fun libraryStore(
    playback: Playback,
    sources: LibrarySources,
    runtime: FeatureRuntime,
): Library =
    FeatureStore<LibraryState, LibraryCommand, LibraryEvent, LibraryWork, LibraryFact>(
        name = LIBRARY,
        initial = LibraryState(),
        reducer = LibraryReducer,
        toEvent = LibraryEvent::Command,
        runner = LibraryHandlers(playback, sources),
        runtime = runtime,
        start = listOf(Perform(LibraryWork.Read), Perform(LibraryWork.WatchMedia), Perform(LibraryWork.FollowPlayback)),
    )
