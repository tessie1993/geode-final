package dev.geode.feature.studio.ui

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.geode.design.glass.GlassLoading
import dev.geode.feature.studio.api.EditCommand
import dev.geode.feature.studio.api.EditSession
import dev.geode.feature.studio.api.Studio
import dev.geode.feature.studio.api.edit.ClipContent
import dev.geode.feature.studio.api.edit.ClipId
import dev.geode.feature.studio.api.edit.LaneId
import dev.geode.feature.studio.api.edit.LaneKind
import dev.geode.feature.studio.api.edit.Subtitles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Remembers the system picker for a video to open in the clip editor (#318): the read grant is kept,
 * since the Edit reads the video again when it is exported.
 */
@Composable
internal fun rememberVideoPicker(send: (EditCommand) -> Unit): () -> Unit {
    val resolver = LocalContext.current.contentResolver
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) send(EditCommand.OpenFile(kept(resolver, uri)))
        }
    return { picker.launch(arrayOf(VIDEO_TYPES)) }
}

/**
 * The editors as a place (ADR-0003): the clip editor or the Timeline over [studio]'s open Edit, with
 * the system pickers and files they need, and [tools] from `:app`. [model] is what is open.
 */
@Composable
internal fun EditPlace(
    model: EditScreenModel,
    studio: Studio,
    tools: EditorTools,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val scope = rememberCoroutineScope()
    val send: (EditCommand) -> Unit = { studio.send(it) }
    var picking by remember { mutableStateOf<Pair<LaneId, MediaPick>?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val captionLane = stringResource(laneKindLabel(LaneKind.Text))
    val srtSaved = stringResource(R.string.edit_srt_saved)
    val srtFailed = stringResource(R.string.edit_srt_failed, PLACEHOLDER)
    val chaptersSaved = stringResource(R.string.edit_chapters_saved)
    val chaptersNone = stringResource(R.string.edit_chapters_none)
    val chaptersFailed = stringResource(R.string.edit_chapters_failed, PLACEHOLDER)

    fun openCut() = studio.state.value.edit as? EditSession.Cut

    val pickVideo = rememberVideoPicker(send)
    val media =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            val target = picking
            picking = null
            if (uri != null && target != null) {
                val file = kept(resolver, uri)
                scope.launch { send(clipFor(target.first, target.second, file, lengthOf(context, file))) }
            }
        }
    val lut =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            val open = studio.state.value.edit as? EditSession.OneClip
            if (uri != null && open != null) send(EditCommand.ChangeClip(open.edit.copy(lutUri = kept(resolver, uri))))
        }
    val srtIn =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                scope.launch {
                    val text = withContext(Dispatchers.IO) { runCatching { readText(resolver, uri) }.getOrNull() }
                    val lanes =
                        openCut()
                            ?.project
                            ?.timeline
                            ?.lanes
                            .orEmpty()
                    val clips = text?.let { Subtitles.clipsFrom(Subtitles.parseSrt(it)) { ClipId("") } }.orEmpty()
                    if (clips.isNotEmpty()) {
                        send(
                            EditCommand.AddClips(
                                LaneKind.Text,
                                "$captionLane ${lanes.count { it.kind == LaneKind.Text } + 1}",
                                clips,
                                ownLane = true,
                            ),
                        )
                    }
                }
            }
        }
    val srtOut =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(SRT_TYPE)) { uri: Uri? ->
            val cut = openCut()
            if (uri != null && cut != null) {
                val srt = Subtitles.toSrt(Subtitles.cuesFrom(cut.project.timeline.lanes))
                scope.launch {
                    val failure = withContext(Dispatchers.IO) { runCatching { writeText(resolver, uri, srt) }.exceptionOrNull() }
                    notice = if (failure == null) srtSaved else srtFailed.replace(PLACEHOLDER, failure.message ?: failure.toString())
                }
            }
        }
    val chapters =
        ChapterFile.entries.associateWith { file ->
            rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(file.mimeType)) { uri: Uri? ->
                val cut = openCut()
                if (uri != null && cut != null) {
                    scope.launch {
                        val written = tools.writeChapters(cut.project.markers, cut.project.timeline.durationMs, file, uri.toString())
                        notice =
                            when (written) {
                                ChapterWrite.Written -> chaptersSaved
                                ChapterWrite.Skipped -> chaptersNone
                                is ChapterWrite.Failed -> chaptersFailed.replace(PLACEHOLDER, written.message)
                            }
                    }
                }
            }
        }

    val onAction: (EditAction) -> Unit = { action ->
        when (action) {
            is EditAction.Send -> send(action.command)
            EditAction.PickVideo -> pickVideo()
            is EditAction.PickMedia -> {
                picking = action.lane to action.kind
                media.launch(arrayOf(typesOf(action.kind)))
            }
            EditAction.PickLut -> lut.launch(arrayOf(ANY_TYPE))
            EditAction.ImportSrt -> srtIn.launch(arrayOf(SRT_TYPE, TEXT_TYPE, ANY_TEXT_TYPE))
            EditAction.ExportSrt -> srtOut.launch("geode_captions_${System.currentTimeMillis()}.srt")
            is EditAction.ExportChapters -> {
                val file = action.file
                chapters.getValue(file).launch("geode_chapters_${System.currentTimeMillis()}.${file.extension}")
            }
        }
    }
    when (model) {
        is EditScreenModel.Opening ->
            GlassLoading(
                modifier,
                stringResource(if (model.cut) R.string.edit_loading_cut else R.string.edit_reading),
            )
        is EditScreenModel.OneClip -> ClipEditorScreen(model.model, onAction, modifier, tools)
        is EditScreenModel.Cut -> TimelineScreen(model.model, onAction, modifier, tools, notice)
        is EditScreenModel.Short -> ShortScreen(model.model, onAction, modifier, tools)
    }
}

/** The new clip a picked [file] of [kind] makes on [lane]: a video or a sound as long as it is, a still or an overlay for a few seconds. */
internal fun clipFor(
    lane: LaneId,
    kind: MediaPick,
    file: String,
    lengthMs: Long,
): EditCommand.AddClip =
    when (kind) {
        MediaPick.VIDEO -> EditCommand.AddClip(lane, ClipContent.Video(file), lengthMs, lengthMs)
        MediaPick.AUDIO -> EditCommand.AddClip(lane, ClipContent.Audio(file), lengthMs, lengthMs)
        MediaPick.STILL -> EditCommand.AddClip(lane, ClipContent.Still(file), STILL_MS)
        MediaPick.OVERLAY -> EditCommand.AddClip(lane, ClipContent.Overlay(file), OVERLAY_MS)
    }

/** The file types each kind of clip is picked from. */
internal fun typesOf(kind: MediaPick): String =
    when (kind) {
        MediaPick.VIDEO -> VIDEO_TYPES
        MediaPick.STILL, MediaPick.OVERLAY -> IMAGE_TYPES
        MediaPick.AUDIO -> AUDIO_TYPES
    }

/** [uri] as a string, its read grant kept so the Edit can read it again later. */
private fun kept(
    resolver: ContentResolver,
    uri: Uri,
): String {
    runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    return uri.toString()
}

/** How long the video or sound [file] is, 0 when it cannot be read. */
private suspend fun lengthOf(
    context: Context,
    file: String,
): Long =
    withContext(Dispatchers.IO) {
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(file))
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                retriever.release()
            }
        }.getOrDefault(0L)
    }

private fun readText(
    resolver: ContentResolver,
    uri: Uri,
): String? = resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }

private fun writeText(
    resolver: ContentResolver,
    uri: Uri,
    text: String,
) {
    checkNotNull(resolver.openOutputStream(uri)) { "the file could not be opened" }.use { it.write(text.toByteArray(Charsets.UTF_8)) }
}

private const val VIDEO_TYPES = "video/*"
private const val IMAGE_TYPES = "image/*"
private const val AUDIO_TYPES = "audio/*"
private const val ANY_TYPE = "*/*"
private const val SRT_TYPE = "application/x-subrip"
private const val TEXT_TYPE = "text/plain"
private const val ANY_TEXT_TYPE = "text/*"
private const val PLACEHOLDER = "\u0000"
private const val STILL_MS = 3_000L
private const val OVERLAY_MS = 4_000L
