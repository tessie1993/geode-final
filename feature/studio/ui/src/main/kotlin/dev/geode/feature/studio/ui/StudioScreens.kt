package dev.geode.feature.studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.geode.feature.studio.api.FilesOnDelete
import dev.geode.feature.studio.api.Project
import dev.geode.feature.studio.api.Shape
import dev.geode.feature.studio.api.StudioCommand
import dev.geode.feature.studio.api.Video
import dev.geode.feature.studio.api.VideoKind

/** Spacing and sizes of the Studio screens. */
private object StudioDimens {
    val padding = 16.dp
    val gap = 8.dp
    val thumbnailWidth = 96.dp

    /** Thumbnails are drawn 16:9 in lists. */
    const val THUMBNAIL_RATIO = 16f / 9f

    /** Text beside a control takes the room the control leaves. */
    const val FILL = 1f
}

/** What draws a video's thumbnail; the route loads real ones, previews draw [ThumbnailPlaceholder]. */
typealias Thumbnail = @Composable (video: Video, modifier: Modifier) -> Unit

@Composable
fun ThumbnailPlaceholder(
    video: Video,
    modifier: Modifier,
) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Movie, contentDescription = video.name, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The library's first page: every Project, with New Project and each row's ⋮ menu. Stateless (ADR-0003). */
@Composable
fun ProjectListScreen(
    model: ProjectListModel,
    onAction: (StudioAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var naming by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxSize().padding(StudioDimens.padding),
        verticalArrangement = Arrangement.spacedBy(StudioDimens.gap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.studio_title),
                Modifier.weight(StudioDimens.FILL),
                style = MaterialTheme.typography.headlineSmall,
            )
            Button(onClick = { naming = true }, enabled = !model.loading) { Text(stringResource(R.string.studio_new_project)) }
        }
        when {
            model.loading -> Text(stringResource(R.string.studio_loading))
            model.projects.isEmpty() -> Text(stringResource(R.string.studio_no_projects))
            else ->
                LazyColumn(verticalArrangement = Arrangement.spacedBy(StudioDimens.gap)) {
                    items(model.projects, key = { it.project.id.value }) { row -> ProjectListRow(row, onAction) }
                }
        }
    }
    if (naming) {
        NameDialog(
            title = stringResource(R.string.studio_new_project),
            initial = "",
            confirm = stringResource(R.string.studio_choose_folder),
            onDone = { name ->
                naming = false
                onAction(StudioAction.NewProject(name))
            },
            onDismiss = { naming = false },
        )
    }
}

@Composable
private fun ProjectListRow(
    row: ProjectRow,
    onAction: (StudioAction) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onAction(StudioAction.OpenProject(row.project.id)) }
            .padding(vertical = StudioDimens.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(StudioDimens.FILL)) {
            Text(row.project.name, style = MaterialTheme.typography.titleMedium)
            Text(
                pluralStringResource(R.plurals.studio_video_count, row.videos, row.videos),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ProjectMenu(row.project, onAction)
    }
}

/** A Project's ⋮ menu: rename, and delete, which asks whether to keep the files. */
@Composable
private fun ProjectMenu(
    project: Project,
    onAction: (StudioAction) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.studio_more)) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            MenuItem(stringResource(R.string.studio_rename)) {
                menu = false
                renaming = true
            }
            MenuItem(stringResource(R.string.studio_delete)) {
                menu = false
                deleting = true
            }
        }
    }
    if (renaming) {
        NameDialog(
            title = stringResource(R.string.studio_rename),
            initial = project.name,
            confirm = stringResource(R.string.studio_save),
            onDone = { name ->
                renaming = false
                onAction(StudioAction.Send(StudioCommand.RenameProject(project.id, name)))
            },
            onDismiss = { renaming = false },
        )
    }
    if (deleting) {
        DeleteDialog(project.name) { files ->
            deleting = false
            files?.let { onAction(StudioAction.Send(StudioCommand.DeleteProject(project.id, it))) }
        }
    }
}

/** Asks "keep the files or delete them too"; [onChoice] gets null when the user backs out. */
@Composable
internal fun DeleteDialog(
    name: String,
    onChoice: (FilesOnDelete?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onChoice(null) },
        title = { Text(stringResource(R.string.studio_delete_title, name)) },
        text = { Text(stringResource(R.string.studio_delete_question)) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { onChoice(FilesOnDelete.KEEP) }) { Text(stringResource(R.string.studio_keep_files)) }
                TextButton(onClick = { onChoice(FilesOnDelete.DELETE) }) { Text(stringResource(R.string.studio_delete_files)) }
                TextButton(onClick = { onChoice(null) }) { Text(stringResource(R.string.studio_cancel)) }
            }
        },
    )
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.studio_project_name)) },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { onDone(name) }, enabled = name.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.studio_cancel)) } },
    )
}

/** A Project's page: its videos, filterable by type, each with its ⋮ menu. Stateless (ADR-0003). */
@Composable
fun ProjectScreen(
    model: ProjectModel,
    onAction: (StudioAction) -> Unit,
    modifier: Modifier = Modifier,
    thumbnail: Thumbnail = { video, mod -> ThumbnailPlaceholder(video, mod) },
) {
    Column(
        modifier.fillMaxSize().padding(StudioDimens.padding),
        verticalArrangement = Arrangement.spacedBy(StudioDimens.gap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackButton(onAction)
            Text(model.project.name, Modifier.weight(StudioDimens.FILL), style = MaterialTheme.typography.headlineSmall)
            ProjectMenu(model.project, onAction)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(StudioDimens.gap)) {
            KindChip(stringResource(R.string.studio_all), model.filter == null) { onAction(StudioAction.Filter(null)) }
            VideoKind.entries.forEach { kind ->
                KindChip(stringResource(kind.label), model.filter == kind) { onAction(StudioAction.Filter(kind)) }
            }
        }
        if (model.videos.isEmpty()) {
            Text(stringResource(R.string.studio_no_videos))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(StudioDimens.gap)) {
                items(model.videos, key = { it.id.value }) { video ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onAction(StudioAction.OpenVideo(video.id)) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(StudioDimens.gap),
                    ) {
                        thumbnail(video, Modifier.width(StudioDimens.thumbnailWidth).aspectRatio(StudioDimens.THUMBNAIL_RATIO))
                        Column(Modifier.weight(StudioDimens.FILL)) {
                            Text(video.name, style = MaterialTheme.typography.titleSmall)
                            VideoFacts(video)
                        }
                        MoveMenu(video, model.others, onAction)
                    }
                }
            }
        }
    }
}

@Composable
private fun KindChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

/** A video's page: its thumbnail, length, shape and song, and Move to Project. Stateless (ADR-0003). */
@Composable
fun VideoScreen(
    model: VideoModel,
    onAction: (StudioAction) -> Unit,
    modifier: Modifier = Modifier,
    thumbnail: Thumbnail = { video, mod -> ThumbnailPlaceholder(video, mod) },
) {
    val video = model.video
    Column(
        modifier.fillMaxSize().padding(StudioDimens.padding),
        verticalArrangement = Arrangement.spacedBy(StudioDimens.gap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackButton(onAction)
            Text(video.name, Modifier.weight(StudioDimens.FILL), style = MaterialTheme.typography.titleLarge)
            MoveMenu(video, model.others, onAction)
        }
        thumbnail(video, Modifier.fillMaxWidth().aspectRatio(StudioDimens.THUMBNAIL_RATIO))
        Text(stringResource(video.kind.label), style = MaterialTheme.typography.titleMedium)
        VideoFacts(video)
        Text(stringResource(R.string.studio_in_project, model.project.name), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Length, shape and song. */
@Composable
private fun VideoFacts(video: Video) {
    val song = video.song.ifBlank { stringResource(R.string.studio_no_song) }
    Text(
        listOf(video.length.clock(), stringResource(video.shape.label), song).joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MoveMenu(
    video: Video,
    others: List<Project>,
    onAction: (StudioAction) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.studio_more)) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            MenuItem(stringResource(R.string.studio_move), enabled = others.isNotEmpty()) {
                menu = false
                moving = true
            }
        }
        DropdownMenu(expanded = moving, onDismissRequest = { moving = false }) {
            others.forEach { project ->
                MenuItem(project.name) {
                    moving = false
                    onAction(StudioAction.Send(StudioCommand.MoveVideo(video.id, project.id)))
                }
            }
        }
    }
}

@Composable
private fun MenuItem(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    DropdownMenuItem(text = { Text(label) }, onClick = onClick, enabled = enabled)
}

@Composable
private fun BackButton(onAction: (StudioAction) -> Unit) {
    IconButton(onClick = { onAction(StudioAction.Back) }) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.studio_back))
    }
}

private val VideoKind.label: Int
    get() =
        when (this) {
            VideoKind.RENDER -> R.string.studio_kind_render
            VideoKind.RECORDING -> R.string.studio_kind_recording
            VideoKind.EDIT -> R.string.studio_kind_edit
            VideoKind.SHORT -> R.string.studio_kind_short
        }

private val Shape.label: Int
    get() =
        when (this) {
            Shape.LANDSCAPE -> R.string.studio_shape_landscape
            Shape.PORTRAIT -> R.string.studio_shape_portrait
            Shape.SQUARE -> R.string.studio_shape_square
        }
