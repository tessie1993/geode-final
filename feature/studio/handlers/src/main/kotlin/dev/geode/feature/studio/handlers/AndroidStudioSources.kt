package dev.geode.feature.studio.handlers

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.util.UnstableApi
import dev.geode.engine.types.Millis
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.settings.api.SettingsState
import dev.geode.feature.studio.api.NewVideo
import dev.geode.feature.studio.api.VideoKind
import dev.geode.feature.studio.handlers.edit.AndroidEditVideos
import dev.geode.feature.studio.handlers.edit.EditDrafts
import dev.geode.feature.studio.handlers.edit.EditTools
import dev.geode.feature.studio.handlers.edit.MediaEditEngine
import dev.geode.feature.studio.handlers.edit.SoundLevels
import dev.geode.feature.studio.handlers.edit.TransitionShaders
import kotlinx.coroutines.flow.Flow

/** Where the old Studio saved its exports, as a MediaStore relative path. */
private const val OLD_STUDIO_PATH = "Movies/Geode"

/**
 * The old Studio's exports in `Movies/Geode`, read from the phone's media index. Studio's handlers
 * import them once after the upgrade (owner decision on #21, #347), leaving out what the library holds
 * already, and mark the import done so it is not repeated.
 */
class OldStudioVideos(
    private val resolver: ContentResolver,
) : OldVideos {
    /** Reads the media index: the handlers call it off the main thread. */
    override suspend fun list(): List<NewVideo> {
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
 * come from [settings], and the Projects' folders are kept in the save folder (#340); `:app` fills the [looks] and the [engine] Renders are made with (#338, #316),
 * the [recorder] of the live Visual (#317), and says whether the app is [inFront] of the person (#28).
 * The old Studio's videos are imported once from the phone's media index (#347). The editors (#318)
 * export through the Edit pipeline here, with the GL Transitions `:app` reads from the engine
 * ([transitions]), and keep the Timeline's cut in the [drafts] `:app` keeps. A Render's captions come
 * from the songs' [lyrics] and a Short's sound is levelled with the [levels] `:app` measures (#329).
 */
@OptIn(UnstableApi::class)
fun androidStudioSources(
    context: Context,
    settings: Settings,
    looks: RenderLooks,
    engine: RenderEngine,
    inFront: Flow<Boolean>,
    recorder: LiveRecorder = LiveRecorder.None,
    transitions: TransitionShaders = TransitionShaders { null },
    drafts: EditDrafts = EditDrafts.None,
    lyrics: RenderLyrics = RenderLyrics.None,
    levels: SoundLevels = SoundLevels.None,
): StudioSources =
    StudioSources(
        storage = RoomStudioStorage(StudioDatabase.open(context).dao()),
        saveFolder = saveFolderOf(settings),
        files = AndroidVideoFiles(context),
        now = { Millis(System.currentTimeMillis()) },
        renders = RenderSources(renderSettingsOf(settings), looks, engine, inFront, lyrics),
        recorder = recorder,
        edits = EditTools(drafts, AndroidEditVideos(context), MediaEditEngine(context, transitions, levels) { loopSafe(settings) }),
        oldVideos = OldStudioVideos(context.contentResolver),
        folders = AndroidProjectFolders(context.contentResolver),
    )

/** Settings' loop-safe length for exports, off until Settings are read. */
private fun loopSafe(settings: Settings): Boolean = (settings.state.value as? SettingsState.Ready)?.values?.export?.loopSafe ?: false
