package dev.geode.feature.visuals.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import dev.geode.design.glass.GlassSize
import dev.geode.design.glass.GlassSpacing
import dev.geode.feature.visuals.api.ImportProblem
import dev.geode.feature.visuals.api.PresetFolderPath

/**
 * The Presets browser (#144): the built-in and saved presets by folder, reached from the Visuals tab
 * beside Customize's preset chips. A tap applies a preset to the live Visual; a saved preset can be
 * renamed, moved, shared or deleted, and shared presets come in from a pasted link or a file. App UI
 * over the one live Visual, frosted like the Visuals tab. Stateless (ADR-0003): it draws [model] and
 * sends every tap to [onAction]; only which menu or dialog is open is kept here.
 */
@Composable
fun PresetsScreen(
    model: PresetsModel,
    onAction: (PresetsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(GlassSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(GlassSpacing.Medium),
    ) {
        item { PresetsHeader(model, onAction) }
        model.notice?.let { notice -> item { Notice(notice, onAction) } }
        if (model.trail.isNotEmpty()) item { Trail(model.trail, onAction) }
        if (model.loading) {
            item { Hint(R.string.presets_loading) }
        } else {
            page(model, onAction)
        }
    }
}

private fun LazyListScope.page(
    model: PresetsModel,
    onAction: (PresetsAction) -> Unit,
) {
    if (model.forCurrent.isNotEmpty()) {
        item { SectionTitle(stringResource(R.string.presets_for, model.currentName.orEmpty())) }
        presetRows(model.forCurrent, model, onAction)
    }
    item { FolderTitle(model, onAction) }
    if (model.builtInCount > 0) {
        item {
            FolderRow(
                PresetFolderItem(PresetPlace.BuiltIn, stringResource(R.string.presets_built_in), model.builtInCount),
                Icons.Rounded.AutoAwesome,
                onAction,
            )
        }
    }
    items(model.folders, key = { "folder-${it.place.key()}" }) { folder -> FolderRow(folder, Icons.Rounded.Folder, onAction) }
    if (model.presets.isNotEmpty() && model.place == PresetPlace.TOP) {
        item { SectionTitle(stringResource(R.string.presets_saved)) }
    }
    presetRows(model.presets, model, onAction)
    if (model.folders.isEmpty() && model.presets.isEmpty()) {
        item { Hint(if (model.place == PresetPlace.TOP) R.string.presets_none_saved else R.string.presets_folder_empty) }
    }
}

private fun LazyListScope.presetRows(
    presets: List<PresetItem>,
    model: PresetsModel,
    onAction: (PresetsAction) -> Unit,
) {
    items(presets, key = { "preset-${it.builtIn}-${it.id.value}" }) { preset -> PresetRow(preset, model, onAction) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetsHeader(
    model: PresetsModel,
    onAction: (PresetsAction) -> Unit,
) {
    Column(Modifier.fillMaxWidth().glass().padding(GlassSpacing.Large), verticalArrangement = Arrangement.spacedBy(GlassSpacing.Small)) {
        Text(stringResource(R.string.presets_title), style = MaterialTheme.typography.headlineMedium)
        model.currentName?.let {
            Text(
                stringResource(R.string.visuals_now_showing, it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(GlassSpacing.Small)) {
            FilledTonalButton(onClick = { onAction(PresetsAction.PasteLink) }) {
                Icon(Icons.Rounded.ContentPaste, contentDescription = null)
                Text(stringResource(R.string.presets_paste_link), Modifier.padding(start = GlassSpacing.Small))
            }
            FilledTonalButton(onClick = { onAction(PresetsAction.OpenFile) }) {
                Icon(Icons.Rounded.FileOpen, contentDescription = null)
                Text(stringResource(R.string.presets_open_file), Modifier.padding(start = GlassSpacing.Small))
            }
        }
    }
}

@Composable
private fun Notice(
    notice: PresetNotice,
    onAction: (PresetsAction) -> Unit,
) {
    val text =
        when (notice) {
            is PresetNotice.Imported -> stringResource(R.string.presets_imported, notice.name)
            is PresetNotice.NotImported ->
                when (notice.problem) {
                    ImportProblem.NO_LINK -> stringResource(R.string.presets_no_link)
                    ImportProblem.NOT_A_PRESET -> stringResource(R.string.presets_not_a_preset)
                }
            PresetNotice.ClipboardEmpty -> stringResource(R.string.presets_clipboard_empty)
        }
    Row(Modifier.fillMaxWidth().glass().padding(start = GlassSpacing.Large), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = { onAction(PresetsAction.DismissNotice) }) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.presets_dismiss))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Trail(
    trail: List<Crumb>,
    onAction: (PresetsAction) -> Unit,
) {
    FlowRow {
        TextButton(onClick = { onAction(PresetsAction.Open(PresetPlace.TOP)) }) { Text(stringResource(R.string.presets_top)) }
        for (crumb in trail.dropLast(1)) {
            Text("/", Modifier.align(Alignment.CenterVertically), color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { onAction(PresetsAction.Open(crumb.place)) }) {
                Text(crumb.name ?: stringResource(R.string.presets_built_in), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun FolderTitle(
    model: PresetsModel,
    onAction: (PresetsAction) -> Unit,
) {
    // Whether the "new folder" dialog is open: App UI state only (ADR-0003).
    var naming by rememberSaveable { mutableStateOf(false) }
    val title =
        model.trail.lastOrNull()?.let { it.name ?: stringResource(R.string.presets_built_in) } ?: stringResource(R.string.presets_folders)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { SectionTitle(title) }
        if (model.editable) TextButton(onClick = { naming = true }) { Text(stringResource(R.string.visuals_new_folder)) }
    }
    if (naming) {
        TextDialog(
            title = stringResource(R.string.visuals_new_folder),
            label = stringResource(R.string.visuals_folder_name),
            initial = "",
            onDone = { name ->
                naming = false
                name?.let { onAction(PresetsAction.CreateFolder(it)) }
            },
        )
    }
}

@Composable
private fun FolderRow(
    folder: PresetFolderItem,
    icon: ImageVector,
    onAction: (PresetsAction) -> Unit,
) {
    // Whether its menu or a dialog is open: App UI state only.
    var menu by remember { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    val saved = (folder.place as? PresetPlace.Saved)?.folder
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = GlassSize.TapTarget)
            .glass()
            .clickable { onAction(PresetsAction.Open(folder.place)) }
            .padding(horizontal = GlassSpacing.Large, vertical = GlassSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(start = GlassSpacing.Medium)) {
            Text(folder.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.visuals_folder_count, folder.count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (saved != null) {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.visuals_more, folder.name))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.visuals_rename_folder)) },
                        onClick = {
                            menu = false
                            renaming = true
                        },
                    )
                    // Only an empty folder can go: deleting never takes presets with it.
                    if (folder.count == 0) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.visuals_delete_folder)) },
                            onClick = {
                                menu = false
                                onAction(PresetsAction.DeleteFolder(saved))
                            },
                        )
                    }
                }
            }
        }
    }
    if (renaming && saved != null) {
        TextDialog(
            title = stringResource(R.string.visuals_rename_folder),
            label = stringResource(R.string.visuals_folder_name),
            initial = folder.name,
            onDone = { name ->
                renaming = false
                name?.let { onAction(PresetsAction.RenameFolder(saved, it)) }
            },
        )
    }
}

/** Which of a preset's dialogs is open: App UI state only. */
private enum class PresetDialog { NONE, RENAME, MOVE, DELETE }

@Composable
private fun PresetRow(
    preset: PresetItem,
    model: PresetsModel,
    onAction: (PresetsAction) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf(PresetDialog.NONE) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = GlassSize.TapTarget)
            .glass()
            .clickable { onAction(PresetsAction.Apply(preset.id)) }
            .padding(start = GlassSpacing.Large, end = GlassSpacing.Small)
            .padding(vertical = GlassSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(preset.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val about =
                when {
                    preset.builtIn -> stringResource(R.string.presets_built_in_about, preset.styleName.orEmpty())
                    preset.styleName != null -> stringResource(R.string.presets_made_on, preset.styleName)
                    else -> null
                }
            about?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        if (!preset.builtIn) {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.visuals_more, preset.name))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuItem(R.string.presets_rename) {
                        menu = false
                        dialog = PresetDialog.RENAME
                    }
                    MenuItem(R.string.presets_move) {
                        menu = false
                        dialog = PresetDialog.MOVE
                    }
                    MenuItem(R.string.presets_share) {
                        menu = false
                        onAction(PresetsAction.Share(preset.id))
                    }
                    MenuItem(R.string.presets_delete) {
                        menu = false
                        dialog = PresetDialog.DELETE
                    }
                }
            }
        }
    }
    val close = { dialog = PresetDialog.NONE }
    when (dialog) {
        PresetDialog.NONE -> Unit
        PresetDialog.RENAME ->
            TextDialog(
                title = stringResource(R.string.presets_rename),
                label = stringResource(R.string.presets_name),
                initial = preset.name,
                taken = { it != preset.name && it in model.savedNames },
                onDone = { name ->
                    close()
                    name?.let { onAction(PresetsAction.Rename(preset.id, it)) }
                },
            )
        PresetDialog.MOVE ->
            MoveDialog(preset, model.moveTargets, onDismiss = close) { folder ->
                close()
                onAction(PresetsAction.Move(preset.id, folder))
            }
        PresetDialog.DELETE ->
            AlertDialog(
                onDismissRequest = close,
                title = { Text(stringResource(R.string.presets_delete_title, preset.name)) },
                text = { Text(stringResource(R.string.presets_delete_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        close()
                        onAction(PresetsAction.Delete(preset.id))
                    }) { Text(stringResource(R.string.presets_delete)) }
                },
                dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.visuals_cancel)) } },
            )
    }
}

@Composable
private fun MenuItem(
    @StringRes text: Int,
    onClick: () -> Unit,
) {
    DropdownMenuItem(text = { Text(stringResource(text)) }, onClick = onClick)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoveDialog(
    preset: PresetItem,
    targets: List<MoveTarget>,
    onDismiss: () -> Unit,
    onMove: (PresetFolderPath) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.presets_move_title, preset.name)) },
        text = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(GlassSpacing.Small)) {
                for (target in targets) {
                    FilterChip(
                        selected = false,
                        onClick = { onMove(target.folder) },
                        label = { Text(target.name.ifEmpty { stringResource(R.string.presets_top) }) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.visuals_cancel)) } },
    )
}

/** Asks for a name; [onDone] gets it, or null when cancelled. A name [taken] is refused with a note. */
@Composable
private fun TextDialog(
    title: String,
    label: String,
    initial: String,
    onDone: (String?) -> Unit,
    taken: (String) -> Boolean = { false },
) {
    var name by rememberSaveable { mutableStateOf(initial) }
    val clash = taken(name.trim())
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(label) },
                singleLine = true,
                isError = clash,
                supportingText = if (clash) ({ Text(stringResource(R.string.presets_name_taken)) }) else null,
            )
        },
        confirmButton = {
            TextButton(onClick = { onDone(name) }, enabled = name.isNotBlank() && !clash) { Text(stringResource(R.string.visuals_save)) }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text(stringResource(R.string.visuals_cancel)) } },
    )
}
