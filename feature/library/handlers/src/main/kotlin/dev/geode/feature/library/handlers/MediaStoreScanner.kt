package dev.geode.feature.library.handlers

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore.Audio.Media
import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.library.api.Song
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.IOException

/**
 * [MediaScanner] over Android's MediaStore: every audio file on the phone's shared storage, voice
 * notes and ringtones included, which the Library's filters and excluded folders then hide.
 */
class MediaStoreScanner(
    private val context: Context,
) : MediaScanner {
    private val resolver: ContentResolver get() = context.contentResolver

    override suspend fun scan(): ScanResult {
        if (context.checkSelfPermission(readPermission()) != PackageManager.PERMISSION_GRANTED) return ScanResult.NoPermission
        val cursor =
            resolver.query(Media.EXTERNAL_CONTENT_URI, COLUMNS, null, null, null)
                ?: throw IOException("the media index returned nothing")
        return ScanResult.Found(cursor.use { songsIn(it) })
    }

    override val changes: Flow<Unit> =
        callbackFlow {
            val observer =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        trySend(Unit)
                    }
                }
            resolver.registerContentObserver(Media.EXTERNAL_CONTENT_URI, true, observer)
            awaitClose { resolver.unregisterContentObserver(observer) }
        }

    private fun songsIn(cursor: Cursor): List<Song> {
        val id = cursor.getColumnIndexOrThrow(Media._ID)
        val title = cursor.getColumnIndexOrThrow(Media.TITLE)
        val artist = cursor.getColumnIndexOrThrow(Media.ARTIST)
        val album = cursor.getColumnIndexOrThrow(Media.ALBUM)
        val albumArtist = cursor.getColumnIndexOrThrow(Media.ALBUM_ARTIST)
        val genre = cursor.getColumnIndexOrThrow(Media.GENRE)
        val composer = cursor.getColumnIndexOrThrow(Media.COMPOSER)
        val path = cursor.getColumnIndexOrThrow(Media.DATA)
        val mimeType = cursor.getColumnIndexOrThrow(Media.MIME_TYPE)
        val duration = cursor.getColumnIndexOrThrow(Media.DURATION)
        val added = cursor.getColumnIndexOrThrow(Media.DATE_ADDED)
        val songs = ArrayList<Song>(cursor.count)
        while (cursor.moveToNext()) {
            val file = cursor.getString(path).orEmpty()
            songs +=
                Song(
                    id = TrackId(ContentUris.withAppendedId(Media.EXTERNAL_CONTENT_URI, cursor.getLong(id)).toString()),
                    title = cursor.getString(title)?.takeIf { it.isNotBlank() } ?: file.substringAfterLast('/').substringBeforeLast('.'),
                    artist = cursor.text(artist),
                    album = cursor.text(album),
                    albumArtist = cursor.text(albumArtist),
                    genre = cursor.text(genre),
                    composer = cursor.text(composer),
                    path = file,
                    format = formatOf(file, cursor.getString(mimeType)),
                    duration = Millis(cursor.getLong(duration)),
                    addedAt = Millis(cursor.getLong(added) * MILLIS_PER_SECOND),
                )
        }
        return songs
    }

    /** MediaStore writes this for a tag the file does not have. */
    private fun Cursor.text(column: Int): String = getString(column)?.takeUnless { it == UNKNOWN_TAG }.orEmpty()

    companion object {
        private val COLUMNS =
            arrayOf(
                Media._ID,
                Media.TITLE,
                Media.ARTIST,
                Media.ALBUM,
                Media.ALBUM_ARTIST,
                Media.GENRE,
                Media.COMPOSER,
                Media.DATA,
                Media.MIME_TYPE,
                Media.DURATION,
                Media.DATE_ADDED,
            )

        private const val MILLIS_PER_SECOND = 1_000L
        private const val UNKNOWN_TAG = "<unknown>"

        /** The permission that lets Geode read the phone's music. */
        fun readPermission(): String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }

        /** A file's format, from its extension or else its mime type: `mp3`, `flac`. */
        fun formatOf(
            path: String,
            mimeType: String?,
        ): String {
            val name = path.substringAfterLast('/')
            return if ('.' in name) name.substringAfterLast('.').lowercase() else mimeType?.substringAfterLast('/')?.lowercase().orEmpty()
        }
    }
}

/** [PlaylistFiles] over content uris the user picked. */
class ContentPlaylistFiles(
    private val resolver: ContentResolver,
) : PlaylistFiles {
    override suspend fun read(file: String): String =
        resolver.openInputStream(Uri.parse(file))?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IOException("could not open $file")

    override suspend fun write(
        file: String,
        text: String,
    ) {
        val stream = resolver.openOutputStream(Uri.parse(file), WRITE_TRUNCATE) ?: throw IOException("could not open $file")
        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    private companion object {
        const val WRITE_TRUNCATE = "wt"
    }
}
