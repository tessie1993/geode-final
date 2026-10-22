package dev.geode.feature.studio.handlers

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import dev.geode.engine.types.Millis
import dev.geode.feature.studio.core.OldVideo

/** Where the old Studio saved its exports, as a MediaStore relative path. */
private const val OLD_STUDIO_PATH = "Movies/Geode"

/** The old Studio's videos, read from the phone's media index (#21: they go into "Unsorted"). */
class MediaStoreOldVideos(
    private val resolver: ContentResolver,
) : OldStudioVideos {
    override suspend fun list(): List<OldVideo> {
        val projection =
            arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT,
            )
        val found = mutableListOf<OldVideo>()
        resolver
            .query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("$OLD_STUDIO_PATH%"),
                "${MediaStore.Video.Media.DATE_ADDED} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    found +=
                        OldVideo(
                            file = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0)).toString(),
                            name = c.getString(1).orEmpty(),
                            length = Millis(c.getLong(2)),
                            width = c.getInt(3),
                            height = c.getInt(4),
                        )
                }
            }
        return found
    }
}

/** Project files through the Storage Access Framework, and MediaStore for the old Studio's videos. */
class SafProjectFiles(
    private val context: Context,
) : ProjectFiles {
    override suspend fun delete(file: String): Boolean {
        val uri = Uri.parse(file)
        return if (uri.authority == MediaStore.AUTHORITY) {
            context.contentResolver.delete(uri, null, null) > 0
        } else {
            DocumentFile.fromSingleUri(context, uri)?.delete() == true
        }
    }

    override suspend fun release(folder: String) =
        context.contentResolver.releasePersistableUriPermission(
            Uri.parse(folder),
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
}

/** Studio's sources on the phone, for `:app` to wire (#17). */
fun androidStudioSources(context: Context): StudioSources =
    StudioSources(
        storage = RoomStudioStorage(StudioDatabase.open(context).dao()),
        oldVideos = MediaStoreOldVideos(context.contentResolver),
        files = SafProjectFiles(context),
        now = { Millis(System.currentTimeMillis()) },
    )
