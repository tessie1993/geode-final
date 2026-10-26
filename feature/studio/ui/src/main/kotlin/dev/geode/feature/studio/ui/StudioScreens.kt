package dev.geode.feature.studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.geode.design.glass.GlassBackdrop
import dev.geode.design.glass.GlassCard
import dev.geode.design.glass.GlassLoading
import dev.geode.design.glass.GlassMessage
import dev.geode.design.glass.rememberPressing
import dev.geode.design.glass.rememberToggling
import dev.geode.feature.studio.api.EditCommand
import dev.geode.feature.studio.api.Project
import dev.geode.feature.studio.api.ProjectId
import dev.geode.feature.studio.api.RenderPreset
import dev.geode.feature.studio.api.Shape
import dev.geode.feature.studio.api.StudioCommand
import dev.geode.feature.studio.api.Video
import dev.geode.feature.studio.api.VideoKind
import dev.geode.feature.studio.api.VideosOnDelete

/** What draws a video's thumbnail; the route loads real ones, previews draw [ThumbnailPlaceholder]. */
typealias Thumbnail = @Composable (video: Video, modifier: Modifier) -> Unit

@Composable
fun ThumbnailPlaceholder(
    video: Video,
    modifier: Modifier,
) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Movie, contentDescription = video.name, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The library's first page: every Project with its ⋮ menu, New Project, Render and the render queue
 * (#316), and a reminder when no save folder or no default Project is chosen. Stateless (ADR-0003).
 */
@Composable
fun ProjectListScreen(
    model: ProjectListModel,
    onAction: (StudioAction) -> Unit,
    modifier: Modifier = Modifier,
    queue: RenderQueueModel = RenderQueueModel(emptyList(), canClear = false, visualsOff = false, projects = emptyList()),
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
            OutlinedButton(
                onClick = rememberPressing { onAction(StudioAction.OpenRender) },
                enabled = !model.loading && !queue.visualsOff,
            ) {
                Text(stringResource(R.string.studio_render))
            }
            Button(
                onClick = rememberPressing { naming = true },
                enabled = !model.loading,
                modifier = Modifier.padding(start = StudioDimens.gap),
            ) {
                Text(stringResource(R.string.studio_new_project))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(StudioDimens.gap)) {
            OutlinedButton(onClick = rememberPressing { onAction(StudioAction.PickVideoToEdit) }, enabled = !model.loading) {
                Text(stringResource(R.string.edit_open_video))
            }
            OutlinedButton(onClick = rememberPressing { onAction(StudioAction.Send(EditCommand.OpenCut)) }, enabled = !model.loading) {
                Text(stringResource(R.string.edit_open_cut))
            }
        }
        if (model.savedCuts.isNotEmpty()) SavedCuts(model.savedCuts, onAction)
        if (queue.visualsOff) Note(stringResource(R.string.studio_render_visuals_off))
        RenderQueue(queue, onAction)
        if (model.folderMissing) FolderMissing(onAction)
        if (model.noDefault) Note(stringResource(R.string.studio_no_default))
        when {
            model.loading -> GlassLoading(label = stringResource(R.string.studio_loading))
            model.projects.isEmpty() ->
                GlassMessage(
                    title = stringResource(R.string.studio_no_projects),
                    body = stringResource(R.string.studio_no_projects_hint),
                    icon = Icons.Rounded.VideoLibrary,
                    actionLabel = stringResource(R.string.studio_new_project),
                    onAction = { naming = true },
                )
            else -> {
                val all = model.projects.map { it.project }
                val default =
                    model.projects
                        .firstOrNull { it.isDefault }
                        ?.project
                        ?.id
                LazyColumn(verticalArrangement = Arrangement.spacedBy(StudioDimens.gap)) {
                    items(model.projects, key = { it.project.id.value }) { row ->
                        ProjectListRow(row, deletePrompt(row, all, default), onAction)
                    }
                }
            }
        }
    }
    if (naming) {
        NewProjectDialog(model.newIsDefault) { command ->
            naming = false
            command?.let { onAction(StudioAction.Send(it)) }
        }
    }
}

/** The earlier editor's cuts (#347), each opening in the Timeline. */
@Composable
private fun SavedCuts(
    names: List<String>,
    onAction: (StudioAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(StudioDimens.gap)) {
        Note(stringResource(R.string.edit_earlier_cuts))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(StudioDimens.gap)) {
            names.forEach { name ->
                AssistChip(
                    onClick = rememberPressing { onAction(StudioAction.Send(EditCommand.OpenSavedCut(name))) },
                    label = { Text(name) },
                    leadingIcon = { Icon(Icons.Rounded.ContentCut, contentDescription = null) },
                )
            }
        }
    }
}

@Composable
private fun FolderMissing(onAction: (StudioAction) -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.padding(StudioDimens.padding), verticalArrangement = Arrangement.spacedBy(StudioDimens.gap)) {
            Text(stringResource(R.string.studio_folder_missing))
            OutlinedButton(
                onClick = rememberPressing { onAction(StudioAction.PickSaveFolder) },
            ) { Text(stringResource(R.string.studio_choose_folder)) }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ProjectListRow(
    row: ProjectRow,
    prompt: DeletePromptModel,
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
            Note(rowFacts(row))
        }
        ProjectMenu(row, prompt, onAction)
    }
}

/** "4 videos", and "Default Project" for the default one. */
@Composable
private fun rowFacts(row: ProjectRow): String {
    val count = pluralStringResource(R.plurals.studio_video_count, row.videos, row.videos)
    return if (row.isDefault) "$count · ${stringResource(R.string.studio_default)}" else count
}

/** A Project's ⋮ menu: rename, make it the default, and delete, which asks "Delete N videos too?". */
@Composable
private fun ProjectMenu(
    row: ProjectRow,
    prompt: DeletePromptModel,
    onAction: (StudioAction) -> Unit,
) {
    val project = row.project
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = rememberPressing { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.studio_more)) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            MenuItem(stringResource(R.string.studio_rename)) {
                menu = false
                renaming = true
            }
            if (!row.isDefault) {
                MenuItem(stringResource(R.string.studio_make_default)) {
                    menu = false
                    onAction(StudioAction.Send(StudioCommand.SetDefaultProject(project.id)))
                }
            }
            MenuItem(stringResource(R.string.studio_delete)) {
                menu = false
                deleting = true
            }
        }
    }
    if (renaming) {
        RenameDialog(project.name) { name ->
            renaming = false
            name?.let { onAction(StudioAction.Send(StudioCommand.RenameProject(project.id, it))) }
        }
    }
    if (deleting) {
        Dialog(onDismissRequest = { deleting = false }) {
            DeleteProjectPrompt(prompt) { choice ->
                deleting = false
                choice?.let { onAction(StudioAction.Send(StudioCommand.DeleteProject(project.id, it))) }
            }
        }
    }
}

/**
 * The delete confirmation (owner decision on #21): a Project with videos asks "Delete N videos too?"
 * and offers to keep them by moving them to another Project; nothing is deleted silently. [onChoice]
 * gets null when the user backs out. Drawn as a card so the screenshots can show it: matte glass, since
 * it opens in a dialog over anything.
 */
@Composable
internal fun DeleteProjectPrompt(
    model: DeletePromptModel,
    onChoice: (VideosOnDelete?) -> Unit,
) {
    var moveTo by remember(model) { mutableStateOf(model.moveTo) }
    GlassCard(backdrop = GlassBackdrop.Unknown, contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.padding(StudioDimens.padding), verticalArrangement = Arrangement.spacedBy(StudioDimens.gap)) {
            Text(stringResource(R.string.studio_delete_title, model.project.name), style = MaterialTheme.typography.titleLarge)
            if (model.videos == 0) {
                Text(stringResource(R.string.studio_delete_empty))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = rememberPressing { onChoice(null) }) { Text(stringResource(R.string.studio_cancel)) }
                    TextButton(
                        onClick = rememberPressing { onChoice(VideosOnDelete.Delete) },
                    ) { Text(stringResource(R.string.studio_delete)) }
                }
            } else {
                Text(pluralStringResource(R.plurals.studio_delete_videos_too, model.videos, model.videos))
                KeepChoices(model, moveTo) { moveTo = it }
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                    TextButton(onClick = rememberPressing { onChoice(VideosOnDelete.Delete) }) {
                        Text(pluralStringResource(R.plurals.studio_delete_videos_yes, model.videos, model.videos))
                    }
                    val target = model.others.firstOrNull { it.id == moveTo }
                    TextButton(
                        onClick = rememberPressing { target?.let { onChoice(VideosOnDelete.MoveTo(it.id)) } },
                        enabled =
                            target != null,
                    ) {
                        Text(stringResource(R.string.studio_delete_videos_keep, target?.name.orEmpty()))
                    }
                    TextButton(onClick = rememberPressing { onChoice(null) }) { Text(stringResource(R.string.studio_cancel)) }
                }
            }
        }
    }
}

/** Where the videos can go instead of being deleted. */
@Composable
private fun KeepChoices(
    model: DeletePromptModel,
    moveTo: ProjectId?,
    onPick: (ProjectId) -> Unit,
) {
    if (model.others.isEmpty()) {
        Note(stringResource(R.string.studio_delete_nowhere))
        return
    }
    Note(stringResource(R.string.studio_delete_keep_in))
    model.others.forEach { other ->
        Row(
            Modifier
                .fillMaxWidth()
                .selectable(selected = other.id == moveTo, role = Role.RadioButton, onClick = rememberPressing { onPick(other.id) }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = other.id == moveTo, onClick = null)
            Text(other.name, Modifier.padding(start = StudioDimens.gap))
        }
    }
}

/** Asks for a new Project's name and whether it is the default; [onDone] gets null when the user backs out. */
@Composable
private fun NewProjectDialog(
    defaultFirst: Boolean,
    onDone: (StudioCommand.CreateProject?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var makeDefault by remember { mutableStateOf(defaultFirst) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(stringResource(R.string.studio_new_project)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(StudioDimens.gap)) {
                NameField(name) { name = it }
                Row(
                    Modifier.toggleable(value = makeDefault, role = Role.Checkbox, onValueChange = rememberToggling { makeDefault = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = makeDefault, onCheckedChange = null)
                    Text(stringResource(R.string.studio_use_as_default), Modifier.padding(start = StudioDimens.gap))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = rememberPressing { onDone(StudioCommand.CreateProject(name, makeDefault)) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.studio_create))
            }
        },
        dismissButton = { TextButton(onClick = rememberPressing { onDone(null) }) { Text(stringResource(R.string.studio_cancel)) } },
    )
}

@Composable
private fun RenameDialog(
    initial: String,
    onDone: (String?) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(stringResource(R.string.studio_rename)) },
        text = { NameField(name) { name = it } },
        confirmButton = {
            TextButton(
                onClick = rememberPressing { onDone(name) },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.studio_save)) }
        },
        dismissButton = { TextButton(onClick = rememberPressing { onDone(null) }) { Text(stringResource(R.string.studio_cancel)) } },
    )
}

@Composable
private fun NameField(
    name: String,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = name,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.studio_project_name)) },
        singleLine = true,
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
    val project = model.row.project
    Column(
        modifier.fillMaxSize().padding(StudioDimens.padding),
        verticalArrangement = Arrangement.spacedBy(StudioDimens.gap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackButton(onAction)
            Column(Modifier.weight(StudioDimens.FILL)) {
                Text(project.name, style = MaterialTheme.typography.headlineSmall)
                if (model.row.isDefault) Note(stringResource(R.string.studio_default))
            }
            ProjectMenu(model.row, model.deletePrompt, onAction)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(StudioDimens.gap)) {
            KindChip(stringResource(R.string.studio_all), model.filter == null) { onAction(StudioAction.Filter(null)) }
            VideoKind.entries.forEach { kind ->
                KindChip(stringResource(kind.label), model.filter == kind) { onAction(StudioAction.Filter(kind)) }
            }
        }
        if (model.videos.isEmpty()) {
            GlassMessage(
                title = stringResource(R.string.studio_no_videos),
                body = stringResource(R.string.studio_no_videos_hint),
                icon = Icons.Rounded.Movie,
            )
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
    FilterChip(selected = selected, onClick = rememberPressing(onClick), label = { Text(label) })
}

/**
 * A video's page: its thumbnail, length, shape and song, what it was made from, Move to Project, and
 * what can be made of it: an Edit, a Short, a sibling at another preset, the Render again, and Share.
 * Stateless (ADR-0003).
 */
@Composable
fun VideoScreen(
    model: VideoModel,
    onAction: (StudioAction) -> Unit,
    modifier: Modifier = Modifier,
    thumbnail: Thumbnail = { video, mod -> ThumbnailPlaceholder(video, mod) },
) {
    val video = model.video
    var exporting by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(StudioDimens.padding),
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
        model.source?.let { source ->
            TextButton(onClick = rememberPressing { onAction(StudioAction.OpenVideo(source.id)) }) {
                Text(stringResource(R.string.studio_made_from, source.name))
            }
        }
        Button(onClick = rememberPressing { onAction(StudioAction.Share(video)) }) {
            Icon(Icons.Rounded.Share, contentDescription = null)
            Text(stringResource(R.string.studio_share), Modifier.padding(start = StudioDimens.gap))
        }
        OutlinedButton(onClick = rememberPressing { onAction(StudioAction.Send(EditCommand.OpenVideo(video.id))) }) {
            Text(stringResource(R.string.edit_this_video))
        }
        if (model.canMakeShort) {
            OutlinedButton(onClick = rememberPressing { onAction(StudioAction.Send(EditCommand.MakeShort(video.id))) }) {
                Text(stringResource(R.string.studio_make_short))
            }
        }
        OutlinedButton(onClick = rememberPressing { exporting = true }) { Text(stringResource(R.string.studio_export_as)) }
        if (video.madeWith != null) {
            OutlinedButton(onClick = rememberPressing { onAction(StudioAction.RenderAgain(video.id)) }) {
                Text(stringResource(R.string.studio_render_again))
            }
        }
    }
    if (exporting) {
        ExportAsDialog(model) { command ->
            exporting = false
            command?.let { onAction(StudioAction.Send(it)) }
        }
    }
}

/**
 * Export as… (#328): a preset and the Project the sibling lands in, which must be chosen (the video's
 * own first). Says whether the Render is drawn again or the video cropped. [onDone] gets null when the
 * person backs out.
 */
@Composable
internal fun ExportAsDialog(
    model: VideoModel,
    onDone: (StudioCommand.ExportAs?) -> Unit,
) {
    var preset by remember { mutableStateOf(RenderPreset.TIKTOK_AND_SHORTS) }
    var project by remember(model.project.id) { mutableStateOf(model.project.id) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(stringResource(R.string.export_as_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(StudioDimens.gap)) {
                Note(stringResource(if (model.redraws) R.string.export_as_redraw else R.string.export_as_crop))
                Text(stringResource(R.string.export_as_preset), style = MaterialTheme.typography.titleSmall)
                RenderPreset.entries.forEach { choice ->
                    ChoiceRow(presetLabel(choice), choice == preset) { preset = choice }
                }
                Text(stringResource(R.string.export_as_project), style = MaterialTheme.typography.titleSmall)
                model.projects.forEach { choice ->
                    ChoiceRow(choice.name, choice.id == project) { project = choice.id }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = rememberPressing { onDone(StudioCommand.ExportAs(model.video.id, preset, project)) }) {
                Text(stringResource(R.string.export_as_go))
            }
        },
        dismissButton = { TextButton(onClick = rememberPressing { onDone(null) }) { Text(stringResource(R.string.studio_cancel)) } },
    )
}

@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    onPick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = rememberPressing(onPick)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = StudioDimens.gap))
    }
}

/** Length, shape and song. */
@Composable
private fun VideoFacts(video: Video) {
    val song = video.song.ifBlank { stringResource(R.string.studio_no_song) }
    Note(listOf(video.length.clock(), stringResource(video.shape.label), song).joinToString(" · "))
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
        IconButton(onClick = rememberPressing { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.studio_more)) }
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
    IconButton(onClick = rememberPressing { onAction(StudioAction.Back) }) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.studio_back))
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
