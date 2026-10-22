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
 * Studio's library place (ADR-0003): the only part that touches the Studio store and the system
 * folder picker. Which Project or video is open, and the type filter, are App UI state kept here.
 * `:app` wires it at the switch-over (#17); until then the old Studio screen stays in use.
 */
@Composable
fun StudioRoute(
    studio: Studio,
    modifier: Modifier = Modifier,
) {
    val state by studio.state.collectAsStateWithLifecycle()
    val resolver = LocalContext.current.contentResolver
    var project by rememberSaveable { mutableStateOf<Long?>(null) }
    var video by rememberSaveable { mutableStateOf<Long?>(null) }
    var filter by rememberSaveable { mutableStateOf<VideoKind?>(null) }
    // The name of the Project being created while the folder picker is open.
    val naming = remember { mutableStateOf<String?>(null) }

    val folderPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            val name = naming.value
            naming.value = null
            if (uri != null && name != null) {
                // The folder is written to long after it is picked, so the grant must survive a restart.
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                studio.send(StudioCommand.CreateProject(name, uri.toString()))
            }
        }
    LaunchedEffect(studio) {
        studio.events.collect { fact ->
            when (fact) {
                is StudioFact.ProjectCreated -> project = fact.project.value
            }
        }
    }

    val onAction: (StudioAction) -> Unit = { action ->
        when (action) {
            is StudioAction.Send -> studio.send(action.command)
            is StudioAction.NewProject -> {
                naming.value = action.name
                folderPicker.launch(null)
            }
            is StudioAction.OpenProject -> {
                project = action.project.value
                filter = null
            }
            is StudioAction.OpenVideo -> video = action.video.value
            is StudioAction.Filter -> filter = action.kind
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
        else -> ProjectListScreen(state.toProjectList(), onAction, modifier)
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
