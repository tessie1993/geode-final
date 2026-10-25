package dev.geode.library

import android.content.Context
import android.content.SharedPreferences
import dev.geode.data.FavouritesStore
import dev.geode.data.GeodePrefsFiles
import dev.geode.data.MusicPlaylistStore
import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.library.api.SongEdit
import dev.geode.feature.library.handlers.LibraryStorage
import dev.geode.feature.library.handlers.SavedLibrary
import dev.geode.feature.playback.api.Track
import dev.geode.feature.playback.handlers.PlaybackStorage
import dev.geode.playback.TrackLength
import dev.geode.ui.DeviceTrack
import dev.geode.ui.DeviceTrackQuery
import dev.geode.ui.LibraryTrack
import dev.geode.ui.TrackLibrary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the old Library kept (#17), as the new features' terms: the playlists (Playback's, ADR-0005),
 * the favourites and the user's edits to song info (the Library's). The music folders are Settings',
 * which took them over itself.
 */
data class LegacyLibrary(
    /** In the order the old Library listed them. */
    val playlists: List<LegacyPlaylist>,
    val favourites: List<TrackId>,
    val edits: Map<TrackId, SongEdit>,
)

data class LegacyPlaylist(
    val name: String,
    val tracks: List<Track>,
)

/**
 * Moves what the old Library kept into the Library's and Playback's own databases, once (ADR-0004:
 * every saved format is migrated and nothing is lost). The old files are read and left as they are.
 */
object LegacyLibraryImport {
    /**
     * Writes [legacy] into [library] and [playback], leaving anything they already keep in place: a
     * playlist whose name Playback has, a favourite already kept, and a song already edited are left
     * alone. Safe to run again after a run that stopped part way.
     */
    suspend fun into(
        library: LibraryStorage,
        playback: PlaybackStorage,
        legacy: LegacyLibrary,
        now: Millis,
    ) {
        val taken =
            playback.playlists
                .first()
                .map { it.name }
                .toSet()
        // Playback lists the newest first, so the first one is saved as the newest to keep the old order.
        legacy.playlists
            .filterNot { it.name in taken }
            .forEachIndexed { index, playlist -> playback.createPlaylist(playlist.name, playlist.tracks, now - Millis(index.toLong())) }
        val favourites = library.read().favourites
        legacy.favourites.filterNot { it in favourites }.forEach { library.setFavourite(it, favourite = true) }
        val edited = library.edits().keys
        legacy.edits
            .filterKeys { it !in edited }
            .toSortedMap(compareBy<TrackId> { it.value })
            .forEach { (song, edit) -> library.saveEdit(song, edit) }
    }

    /**
     * The old Library's edits as the Library's: only songs the phone's media index has (the Library
     * finds nothing else), a field the old Library left blank keeps what the index says, and an entry
     * that changes nothing (a file the old Library merely listed) is no edit.
     */
    fun editsOf(
        old: List<LibraryTrack>,
        device: Map<TrackId, DeviceTrack>,
    ): Map<TrackId, SongEdit> =
        old
            .mapNotNull { track ->
                val found = device[track.uri] ?: return@mapNotNull null
                val edit =
                    SongEdit(
                        title = track.title.ifBlank { found.title },
                        artist = track.artist.ifBlank { found.artist },
                        album = track.album.ifBlank { found.album },
                    )
                val changed = edit.title != found.title || edit.artist != found.artist || edit.album != found.album
                if (changed) track.uri to edit else null
            }.toMap()

    /** Reads what the old Library kept on this phone. */
    fun read(context: Context): LegacyLibrary {
        val device = DeviceTrackQuery.query(context).associateBy { it.uri }

        fun trackOf(id: TrackId): Track =
            device[id]?.let { Track(id, it.title, it.artist, it.duration) }
                ?: Track(id, "", "", TrackLength.orRead(context, id, Millis.ZERO))

        val playlists = MusicPlaylistStore(context).list().map { LegacyPlaylist(it.name, it.trackUris.map(::trackOf)) }
        val favourites = FavouritesStore(GeodePrefsFiles(context).favourites).all()
        return LegacyLibrary(playlists, favourites, editsOf(TrackLibrary(context).list(), device))
    }
}

/**
 * The Library's storage that first brings in what the old Library kept, once per install, before the
 * Library reads its saved index. A failed import is tried again on the next start.
 */
class ImportingLibraryStorage(
    private val stored: LibraryStorage,
    private val playback: PlaybackStorage,
    private val alreadyImported: () -> Boolean,
    private val markImported: () -> Unit,
    private val legacy: () -> LegacyLibrary,
    private val now: () -> Millis,
) : LibraryStorage by stored {
    private val once = Mutex()

    @Volatile
    private var ready = false

    override suspend fun read(): SavedLibrary {
        if (!ready) {
            once.withLock {
                if (!ready) {
                    if (!alreadyImported()) {
                        LegacyLibraryImport.into(stored, playback, legacy(), now())
                        markImported()
                    }
                    ready = true
                }
            }
        }
        return stored.read()
    }

    companion object {
        private const val IMPORTED = "library_imported_v1"

        /** Over [stored], importing from this phone's old Library files, remembered in [marks]. */
        fun of(
            context: Context,
            stored: LibraryStorage,
            playback: PlaybackStorage,
            marks: SharedPreferences,
        ): ImportingLibraryStorage =
            ImportingLibraryStorage(
                stored = stored,
                playback = playback,
                alreadyImported = { marks.getBoolean(IMPORTED, false) },
                markImported = { marks.edit().putBoolean(IMPORTED, true).apply() },
                legacy = { LegacyLibraryImport.read(context) },
                now = { Millis(System.currentTimeMillis()) },
            )
    }
}
