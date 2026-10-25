package dev.geode.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import dev.geode.R
import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.library.api.LibraryState
import dev.geode.feature.playback.api.Track

/**
 * The browse tree Android Auto and other browsers walk. Folder ids are fixed words or `<kind>/<name>`;
 * a playable id is `<parentId>|<uri>` so the parent list can be rebuilt when one of its rows is tapped.
 * The songs and favourites are the Library's state, [library]; [recent] is Playback's play history, the
 * most recent first (#17). A playlist is named by its id, which keeps across renames.
 */
class LibraryTree(
    private val context: Context,
    private val library: () -> LibraryState,
    private val recent: () -> List<Track>,
) {
    private data class Row(
        val uri: TrackId,
        val title: String,
        val artist: String,
        val album: String,
        val duration: Millis = Millis.ZERO,
    )

    fun root(): MediaItem = folder(ROOT, context.getString(R.string.app_name), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    fun item(mediaId: String): MediaItem? =
        when {
            mediaId == ROOT -> root()
            mediaId in TOP_LEVEL -> topLevel().firstOrNull { it.mediaId == mediaId }
            mediaId.contains(SEPARATOR) -> playable(mediaId)
            else -> children(mediaId.substringBefore('/')).firstOrNull { it.mediaId == mediaId }
        }

    fun children(parentId: String): List<MediaItem> =
        when (parentId) {
            ROOT -> topLevel()
            TRACKS -> tracks().map { playable(parentId, it) }
            ALBUMS -> groups(ALBUMS, tracks().map { it.album }, MediaMetadata.MEDIA_TYPE_ALBUM)
            ARTISTS -> groups(ARTISTS, tracks().map { it.artist }, MediaMetadata.MEDIA_TYPE_ARTIST)
            PLAYLISTS ->
                library().playlists.map { folder("$PLAYLISTS/${it.id.value}", it.name, MediaMetadata.MEDIA_TYPE_PLAYLIST) }
            FAVOURITES -> tracks().filter { it.uri in library().favourites }.map { playable(parentId, it) }
            RECENT ->
                recent()
                    .distinctBy { it.id }
                    .take(
                        RECENT_LIMIT,
                    ).map { playable(parentId, Row(it.id, it.title, it.artist, "", it.duration)) }
            else -> groupChildren(parentId)
        }

    /** The playable rows of the folder a tapped id came from, and where the tapped one sits. */
    fun queueFor(mediaId: String): Pair<List<MediaItem>, Int>? {
        val parent = mediaId.substringBeforeLast(SEPARATOR, "")
        if (parent.isEmpty()) return null
        val siblings = children(parent).filter { it.mediaMetadata.isPlayable == true }
        val index = siblings.indexOfFirst { it.mediaId == mediaId }
        return if (index < 0) null else siblings to index
    }

    fun playable(mediaId: String): MediaItem? {
        val raw = mediaId.substringAfterLast(SEPARATOR, "")
        if (raw.isEmpty()) return null
        val uri = TrackId(raw)
        val row = rowsFor(listOf(uri)).firstOrNull() ?: Row(uri, raw.substringAfterLast('/'), "", "")
        return playable(mediaId.substringBeforeLast(SEPARATOR), row)
    }

    private fun topLevel(): List<MediaItem> =
        listOf(
            folder(TRACKS, context.getString(R.string.library_tab_tracks), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            folder(ALBUMS, context.getString(R.string.library_tab_albums), MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
            folder(ARTISTS, context.getString(R.string.library_tab_artists), MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
            folder(PLAYLISTS, context.getString(R.string.library_tab_playlists), MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
            folder(FAVOURITES, context.getString(R.string.auto_favourites), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            folder(RECENT, context.getString(R.string.auto_recently_played), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
        )

    private fun groupChildren(parentId: String): List<MediaItem> {
        val kind = parentId.substringBefore('/')
        val name = parentId.substringAfter('/', "")
        val rows =
            when (kind) {
                ALBUMS -> tracks().filter { it.album == name }
                ARTISTS -> tracks().filter { it.artist == name }
                PLAYLISTS ->
                    library()
                        .playlists
                        .firstOrNull { it.id.value.toString() == name }
                        ?.songs
                        ?.let(::rowsFor)
                        .orEmpty()
                else -> emptyList()
            }
        return rows.map { playable(parentId, it) }
    }

    private fun groups(
        kind: String,
        names: List<String>,
        mediaType: Int,
    ): List<MediaItem> = names.distinct().sortedBy { it.lowercase() }.map { folder("$kind/$it", it, mediaType) }

    /** The Library's songs, with the user's edits in, outside the folders the user left out. */
    private fun tracks(): List<Row> {
        val state = library()
        return state.songs
            .filterNot { song -> state.excludedFolders.any { song.folder == it || song.folder.startsWith("$it/") } }
            .map { Row(it.id, it.title, it.artist, it.album, it.duration) }
    }

    private fun rowsFor(uris: List<TrackId>): List<Row> {
        val byUri = tracks().associateBy { it.uri }
        return uris.mapNotNull { uri -> byUri[uri] }
    }

    private fun playable(
        parentId: String,
        row: Row,
    ): MediaItem =
        MediaItem
            .Builder()
            .setMediaId("$parentId$SEPARATOR${row.uri.value}")
            .setUri(row.uri.value)
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(row.title)
                    .setArtist(row.artist.ifBlank { null })
                    .setAlbumTitle(row.album.ifBlank { null })
                    .apply { if (row.duration > Millis.ZERO) setDurationMs(row.duration.value) }
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setExtras(MediaArtwork.embeddedArtExtras(row.uri))
                    .build(),
            ).build()

    private fun folder(
        id: String,
        title: String,
        mediaType: Int,
    ): MediaItem =
        MediaItem
            .Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(mediaType)
                    .build(),
            ).build()

    companion object {
        const val ROOT = "root"
        const val TRACKS = "tracks"
        const val ALBUMS = "albums"
        const val ARTISTS = "artists"
        const val PLAYLISTS = "playlists"
        const val FAVOURITES = "favourites"
        const val RECENT = "recent"
        private const val SEPARATOR = "|"
        private const val RECENT_LIMIT = 50
        private val TOP_LEVEL = setOf(TRACKS, ALBUMS, ARTISTS, PLAYLISTS, FAVOURITES, RECENT)
    }
}
