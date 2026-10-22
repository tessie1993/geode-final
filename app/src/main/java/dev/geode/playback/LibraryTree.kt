package dev.geode.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import dev.geode.R
import dev.geode.data.FavouritesStore
import dev.geode.data.GeodePrefsFiles
import dev.geode.data.MusicPlaylistStore
import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.playback.api.Track
import dev.geode.ui.DeviceTrack
import dev.geode.ui.DeviceTrackQuery
import dev.geode.ui.TrackLibrary

/**
 * The browse tree Android Auto and other browsers walk. Folder ids are fixed words or `<kind>/<name>`;
 * a playable id is `<parentId>|<uri>` so the parent list can be rebuilt when one of its rows is tapped.
 * [recent] is Playback's play history, the most recent first (#17).
 */
class LibraryTree(
    private val context: Context,
    private val recent: () -> List<Track>,
) {
    private data class Row(
        val uri: TrackId,
        val title: String,
        val artist: String,
        val album: String,
        val duration: Millis = Millis.ZERO,
    )

    // tracks() used to re-run TrackLibrary + DeviceTrackQuery on every call, and children()/rowsFor()/
    // queueFor() each call it independently, so a single browse of e.g. an album folder scanned the
    // MediaStore several times over. Cache the merged row list per instance (this tree is built once in
    // PlaybackService.onCreate) and invalidate it from onGetLibraryRoot, the one place Media3 re-enters
    // browsing from the top.
    @Volatile
    private var cachedTracks: List<Row>? = null

    fun root(): MediaItem = folder(ROOT, context.getString(R.string.app_name), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    /** Coarse cache invalidation: called when a browser re-enters at the root. */
    fun invalidate() {
        cachedTracks = null
    }

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
                MusicPlaylistStore(context).list().map { folder("$PLAYLISTS/${it.name}", it.name, MediaMetadata.MEDIA_TYPE_PLAYLIST) }
            FAVOURITES -> rowsFor(FavouritesStore(GeodePrefsFiles(context).favourites).all()).map { playable(parentId, it) }
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
                    MusicPlaylistStore(context)
                        .list()
                        .firstOrNull { it.name == name }
                        ?.trackUris
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

    /** Memoised merge of device tracks and imported documents; Media3 calls its callbacks on its own executor. */
    private fun tracks(): List<Row> =
        cachedTracks ?: synchronized(this) {
            cachedTracks ?: buildTracks().also { cachedTracks = it }
        }

    /** Device tracks first, then imported documents the MediaStore does not index; app-side title edits win. */
    private fun buildTracks(): List<Row> {
        val overrides = TrackLibrary(context).list().associateBy { it.uri }
        val device = DeviceTrackQuery.query(context).map { it.row(overrides) }
        val seen = device.mapTo(HashSet()) { it.uri }
        val imported = overrides.values.filter { it.uri !in seen }.map { Row(it.uri, it.title, it.artist, it.album, it.duration) }
        return device + imported
    }

    private fun rowsFor(uris: List<TrackId>): List<Row> {
        val byUri = tracks().associateBy { it.uri }
        return uris.mapNotNull { uri -> byUri[uri] }
    }

    private fun DeviceTrack.row(overrides: Map<TrackId, dev.geode.ui.LibraryTrack>): Row {
        val stored = overrides[uri]
        return Row(
            uri,
            stored?.title?.ifBlank { null } ?: title,
            stored?.artist?.ifBlank { null } ?: artist,
            stored?.album?.ifBlank { null } ?: album,
            duration,
        )
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
