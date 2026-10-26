package dev.geode.feature.studio.handlers

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import dev.geode.engine.types.Millis
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.studio.api.NewVideo
import dev.geode.feature.studio.api.VideoKind

/** Where the old Studio saved its exports, as a MediaStore relative path. */
private const val OLD_STUDIO_PATH = "Movies/Geode"

/**
 * The old Studio's exports in `Movies/Geode`, read from the phone's media index. Studio never reads
 * them itself: the switch-over (#17) imports them once (owner decision on #21) by sending
 * [dev.geode.feature.studio.api.StudioCommand.AddVideos] with what [list] finds, and marks the import
 * done so it is not repeated.
 */
class OldStudioVideos(
    private val resolver: ContentResolver,
) {
    /** Reads the media index: call it off the main thread. */
    fun list(): List<NewVideo> {
        val projection =
            arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT,
            )
        val found = mutableListOf<NewVideo>()
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
                        NewVideo(
                            // The old Studio saved finished exports: Renders.
                            kind = VideoKind.RENDER,
                            name = c.getString(1).orEmpty(),
                            file = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0)).toString(),
                            thumbnail = null,
                            length = Millis(c.getLong(2)),
                            width = c.getInt(3),
                            height = c.getInt(4),
                            song = "",
                        )
                }
            }
        return found
    }
}

/**
 * Video files through the Storage Access Framework (the save folder), and MediaStore for old-Studio
 * exports imported at the switch-over.
 */
class AndroidVideoFiles(
    private val context: Context,
) : VideoFiles {
    override suspend fun delete(file: String): Boolean {
        val uri = Uri.parse(file)
        return if (uri.authority == MediaStore.AUTHORITY) {
            context.contentResolver.delete(uri, null, null) > 0
        } else {
            DocumentFile.fromSingleUri(context, uri)?.delete() == true
        }
    }
}

/**
 * Studio's sources on the phone, for `:app` to wire (#17): the save folder and the Renders' settings
 * come from [settings]; `:app` fills the [looks] and the [engine] Renders are made with (#338, #316).
 */
fun androidStudioSources(
    context: Context,
    settings: Settings,
    looks: RenderLooks,
    engine: RenderEngine,
): StudioSources =
    StudioSources(
        storage = RoomStudioStorage(StudioDatabase.open(context).dao()),
        saveFolder = saveFolderOf(settings),
        files = AndroidVideoFiles(context),
        now = { Millis(System.currentTimeMillis()) },
        renders = RenderSources(renderSettingsOf(settings), looks, engine),
    )
