package dev.geode.feature.library.handlers

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.library.api.RuleField
import dev.geode.feature.library.api.RuleOp
import dev.geode.feature.library.api.SmartPlaylist
import dev.geode.feature.library.api.SmartRule
import dev.geode.feature.library.api.Song
import dev.geode.feature.library.api.SongEdit

/**
 * What the Library keeps (ADR-0004): the index, the tags Geode's reader filled in, the user's edits,
 * the favourites, the excluded folders and the smart playlists. [RoomLibraryStorage] keeps them in
 * the Library's own Room database. Any call may throw; the handlers turn that into a named failure.
 */
interface LibraryStorage {
    /** Everything saved, the songs with the user's edits applied. */
    suspend fun read(): SavedLibrary

    /**
     * Makes [found] the index. A song whose file has not moved keeps the tags the reader filled in
     * before. Returns the index with the user's edits applied.
     */
    suspend fun replaceSongs(found: List<Song>): List<Song>

    /** The songs whose tags the reader has not filled in yet, as the media index found them. */
    suspend fun songsWithoutTags(): List<Song>

    /** Saves [song] with what the tag reader filled in, so it is not read again. */
    suspend fun saveTags(song: Song)

    suspend fun setFavourite(
        song: TrackId,
        favourite: Boolean,
    )

    suspend fun saveEdit(
        song: TrackId,
        edit: SongEdit,
    )

    /** The user's edits, by song. */
    suspend fun edits(): Map<TrackId, SongEdit>

    suspend fun saveExcludedFolders(folders: Set<String>)

    /** Saves [playlist], in place of the smart playlist named [replacing] when there is one. */
    suspend fun saveSmartPlaylist(
        playlist: SmartPlaylist,
        replacing: String?,
    )

    suspend fun deleteSmartPlaylist(name: String)
}

data class SavedLibrary(
    val songs: List<Song>,
    val favourites: Set<TrackId>,
    val excludedFolders: Set<String>,
    val smartPlaylists: List<SmartPlaylist> = emptyList(),
)

/** [LibraryStorage] in the Library's own Room database. */
class RoomLibraryStorage(
    private val dao: LibraryDao,
) : LibraryStorage {
    override suspend fun read(): SavedLibrary =
        SavedLibrary(
            songs = edited(dao.songs()),
            favourites = dao.favourites().map(::TrackId).toSet(),
            excludedFolders = dao.excludedFolders().toSet(),
            smartPlaylists = smartPlaylists(),
        )

    override suspend fun replaceSongs(found: List<Song>): List<Song> {
        val before = dao.songs().associateBy { it.trackId }
        val rows =
            found.map { song ->
                val old = before[song.id.value]
                if (old != null && old.tagsRead && old.path == song.path) {
                    song.filledFrom(FileTags(old.albumArtist, old.composer, old.genre)).toEntity(tagsRead = true)
                } else {
                    song.toEntity(tagsRead = false)
                }
            }
        dao.replaceSongs(rows)
        return edited(rows)
    }

    override suspend fun songsWithoutTags(): List<Song> = dao.songs().filterNot { it.tagsRead }.map { it.toSong() }

    override suspend fun saveTags(song: Song) = dao.putSong(song.toEntity(tagsRead = true))

    override suspend fun setFavourite(
        song: TrackId,
        favourite: Boolean,
    ) = if (favourite) dao.addFavourite(FavouriteEntity(song.value)) else dao.removeFavourite(song.value)

    override suspend fun saveEdit(
        song: TrackId,
        edit: SongEdit,
    ) = dao.putEdit(EditEntity(song.value, edit.title, edit.artist, edit.album, edit.artwork))

    override suspend fun edits(): Map<TrackId, SongEdit> =
        dao.edits().associate { TrackId(it.trackId) to SongEdit(it.title, it.artist, it.album, it.artwork) }

    override suspend fun saveExcludedFolders(folders: Set<String>) = dao.replaceExcludedFolders(folders.map(::ExcludedFolderEntity))

    override suspend fun saveSmartPlaylist(
        playlist: SmartPlaylist,
        replacing: String?,
    ) = dao.replaceSmartPlaylist(
        replacing = replacing,
        playlist = SmartPlaylistEntity(playlist.name, playlist.matchAll, playlist.limit),
        rules =
            playlist.rules.mapIndexed { position, rule ->
                SmartRuleEntity(playlist.name, position, rule.field.name, rule.op.name, rule.value)
            },
    )

    override suspend fun deleteSmartPlaylist(name: String) = dao.deleteSmartPlaylist(name)

    /**
     * The saved smart playlists. A rule whose field or comparison this version does not know, such as
     * one from a newer Geode, is left out; the rest of its playlist is kept.
     */
    private suspend fun smartPlaylists(): List<SmartPlaylist> {
        val rules = dao.smartRules().groupBy { it.playlist }
        return dao.smartPlaylists().map { row ->
            SmartPlaylist(
                name = row.name,
                rules = rules[row.name].orEmpty().sortedBy { it.position }.mapNotNull { it.toRule() },
                matchAll = row.matchAll,
                limit = row.songLimit,
            )
        }
    }

    private fun SmartRuleEntity.toRule(): SmartRule? {
        val knownField = RuleField.entries.firstOrNull { it.name == field } ?: return null
        val knownOp = RuleOp.entries.firstOrNull { it.name == op } ?: return null
        return SmartRule(knownField, knownOp, value)
    }

    private suspend fun edited(rows: List<SongEntity>): List<Song> {
        val edits = dao.edits().associateBy { it.trackId }
        return rows.map { row ->
            val song = row.toSong()
            edits[row.trackId]?.let { song.copy(title = it.title, artist = it.artist, album = it.album, artwork = it.artwork) } ?: song
        }
    }

    private fun SongEntity.toSong() =
        Song(
            id = TrackId(trackId),
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            genre = genre,
            composer = composer,
            path = path,
            format = format,
            duration = Millis(durationMs),
            addedAt = Millis(addedAtMs),
        )

    private fun Song.toEntity(tagsRead: Boolean) =
        SongEntity(
            trackId = id.value,
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            genre = genre,
            composer = composer,
            path = path,
            format = format,
            durationMs = duration.value,
            addedAtMs = addedAt.value,
            tagsRead = tagsRead,
        )
}
