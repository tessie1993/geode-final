package dev.geode.feature.studio.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.geode.feature.studio.api.ProjectId
import dev.geode.feature.studio.api.Studio
import dev.geode.feature.studio.api.StudioCommand
import dev.geode.feature.studio.api.StudioFact
import dev.geode.feature.studio.api.Video
import dev.geode.feature.studio.api.VideoId
import dev.geode.feature.studio.api.VideoKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The size thumbnails are asked for. */
private val THUMBNAIL_SIZE = Size(512, 288)

/**
 * The system folder picker for the save folder. The folder is written to long after it is picked, so
 * the read and write grant is persisted before [onPicked] gets its tree uri.
 */
@Composable
private fun rememberSaveFolderPicker(onPicked: (String) -> Unit): () -> Unit {
    val resolver = LocalContext.current.contentResolver
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                onPicked(uri.toString())
            }
        }
    return { picker.launch(null) }
}

/**
 * Studio's first-run step as a place (ADR-0003). The save folder is a Settings value, and `:ui` may
 * reach only its own feature, so [folder] is what Settings > Default paths holds and [onFolderPicked]
 * hands a picked folder to whoever wires this (#17 sends Settings `FolderCommand.SetVideoFolder`).
 * [onDone] goes on with first run.
 */
@Composable
fun StudioSetupRoute(
    folder: String?,
    onFolderPicked: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pick = rememberSaveFolderPicker(onFolderPicked)
    StudioSetupScreen(
        setupModel(folder),
        onAction = { action ->
            when (action) {
                StudioSetupAction.PickFolder -> pick()
                StudioSetupAction.Continue -> onDone()
            }
        },
        modifier,
    )
}

/**
 * Studio's library place (ADR-0003): the only part that touches the Studio store and the system
 * folder picker. Which Project or video is open, the type filter and the Render setup being filled in
 * are App UI state kept here. [onSaveFolderPicked] hands a picked save folder to Settings, as in
 * [StudioSetupRoute]. [renderChoices] are the Tracks, playlists and presets a Render can be made of,
 * which `:app` reads from their own features (#316).
 */
@Composable
fun StudioRoute(
    studio: Studio,
    onSaveFolderPicked: (String) -> Unit,
    modifier: Modifier = Modifier,
    renderChoices: RenderChoices = RenderChoices(),
) {
    val state by studio.state.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf<RenderDraft?>(null) }
    var project by rememberSaveable { mutableStateOf<Long?>(null) }
    var video by rememberSaveable { mutableStateOf<Long?>(null) }
    var filter by rememberSaveable { mutableStateOf<VideoKind?>(null) }
    val pickFolder = rememberSaveFolderPicker(onSaveFolderPicked)

    LaunchedEffect(studio) {
        studio.events.collect { fact ->
            when (fact) {
                is StudioFact.ProjectCreated -> project = fact.project.value
                // Shown in the library as they arrive; whatever made them reports them itself.
                is StudioFact.VideosAdded, is StudioFact.ProjectNeeded -> Unit
            }
        }
    }

    val onAction: (StudioAction) -> Unit = { action ->
        when (action) {
            is StudioAction.Send -> studio.send(action.command)
            is StudioAction.OpenProject -> {
                project = action.project.value
                filter = null
            }
            is StudioAction.OpenVideo -> video = action.video.value
            is StudioAction.Filter -> filter = action.kind
            StudioAction.PickSaveFolder -> pickFolder()
            StudioAction.OpenRender -> draft = RenderDraft.startingFrom(state)
            StudioAction.Back -> if (video != null) video = null else project = null
        }
    }
    val thumbnail: Thumbnail = { shown, mod -> LoadedThumbnail(shown, mod) }
    val openVideo = video?.let { state.toVideoModel(VideoId(it)) }
    val openProject = project?.let { state.toProjectModel(ProjectId(it), filter) }
    BackHandler(enabled = openVideo != null || openProject != null) { onAction(StudioAction.Back) }
    when {
        openVideo != null -> VideoScreen(openVideo, onAction, modifier, thumbnail)
        openProject != null -> ProjectScreen(openProject, onAction, modifier, thumbnail)
        else -> ProjectListScreen(state.toProjectList(), onAction, modifier, state.toRenderQueue())
    }
    draft?.let { open ->
        RenderSetupDialog(
            draft = open,
            choices = renderChoices,
            projects = state.projects,
            onDraft = { draft = it },
            onRender = {
                studio.send(StudioCommand.QueueRender(open.spec))
                draft = null
            },
            onDismiss = { draft = null },
        )
    }
}

/** The video's own thumbnail, or the stored one; the placeholder until it loads or when there is none. */
@Composable
private fun LoadedThumbnail(
    video: Video,
    modifier: Modifier,
) {
    val resolver = LocalContext.current.contentResolver
    val bitmap by produceState<Bitmap?>(null, video.file, video.thumbnail) {
        value =
            withContext(Dispatchers.IO) {
                runCatching { resolver.loadThumbnail(Uri.parse(video.thumbnail ?: video.file), THUMBNAIL_SIZE, null) }.getOrNull()
            }
    }
    val loaded = bitmap
    if (loaded == null) {
        ThumbnailPlaceholder(video, modifier)
    } else {
        Image(loaded.asImageBitmap(), contentDescription = video.name, modifier = modifier, contentScale = ContentScale.Crop)
    }
}
