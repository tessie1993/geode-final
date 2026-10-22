package dev.geode.feature.visuals.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import dev.geode.design.forest.ForestGlass
import dev.geode.design.forest.ForestShapes
import dev.geode.design.forest.ForestSpacing
import dev.geode.feature.visuals.api.FolderId
import dev.geode.feature.visuals.api.StyleGroup

/**
 * The Visuals tab (#43): where a Style is chosen for the Visual. It is App UI drawn over the one live
 * Visual, which the app keeps running behind it, so it has no background of its own and its panels are
 * frosted glass. Stateless (ADR-0003): it draws [model] and sends every tap to [onAction]. Only whether a
 * menu or dialog is open is kept here, as App-UI-only state.
 */
@Composable
fun VisualsTabScreen(
    model: VisualsTabModel,
    onAction: (VisualsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(ForestSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(ForestSpacing.Medium),
    ) {
        item { Header(model, onAction) }
        item { SearchField(model.query, onAction) }

        val results = model.results
        when {
            results != null -> searchResults(results, model, onAction)
            model.openFolder != null -> openFolder(model, onAction)
            else -> topLevel(model, onAction)
        }
    }
}

private fun LazyListScope.searchResults(
    results: List<StyleItem>,
    model: VisualsTabModel,
    onAction: (VisualsAction) -> Unit,
) {
    if (results.isEmpty()) {
        item { Hint(R.string.visuals_search_none) }
    } else {
        styles(results, model, folder = null, onAction)
    }
}

private fun LazyListScope.topLevel(
    model: VisualsTabModel,
    onAction: (VisualsAction) -> Unit,
) {
    if (model.loading) item { Hint(R.string.visuals_loading) }
    if (model.favourites.isNotEmpty()) {
        item { SectionTitle(stringResource(R.string.visuals_favourites)) }
        styles(model.favourites, model, folder = null, onAction)
    }
    if (model.recent.isNotEmpty()) {
        item { SectionTitle(stringResource(R.string.visuals_recent)) }
        styles(model.recent, model, folder = null, onAction)
    }
    if (!model.loading) {
        item { FoldersTitle(stringResource(R.string.visuals_folders), onAction) }
        folders(model.folders, onAction)
    }
    for (section in model.groups) {
        item(key = "group-${section.group}") { SectionTitle(stringResource(section.group.label)) }
        styles(section.styles, model, folder = null, onAction)
    }
}

private fun LazyListScope.openFolder(
    model: VisualsTabModel,
    onAction: (VisualsAction) -> Unit,
) {
    item { Path(model.path, onAction) }
    item { FoldersTitle(model.path.last().name, onAction) }
    folders(model.folders, onAction)
    if (model.folders.isEmpty() && model.folderStyles.isEmpty()) item { Hint(R.string.visuals_folder_empty) }
    styles(model.folderStyles, model, folder = model.openFolder, onAction)
}

private fun LazyListScope.styles(
    styles: List<StyleItem>,
    model: VisualsTabModel,
    folder: FolderId?,
    onAction: (VisualsAction) -> Unit,
) {
    items(styles) { style -> StyleRow(style, model.allFolders, folder, onAction) }
}

private fun LazyListScope.folders(
    folders: List<FolderItem>,
    onAction: (VisualsAction) -> Unit,
) {
    items(folders, key = { "folder-${it.id.value}" }) { folder -> FolderRow(folder, onAction) }
}

@Composable
private fun Header(
    model: VisualsTabModel,
    onAction: (VisualsAction) -> Unit,
) {
    Row(Modifier.fillMaxWidth().glass().padding(ForestSpacing.Large), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.visuals_title), style = MaterialTheme.typography.headlineMedium)
            model.currentName?.let {
                Text(
                    stringResource(R.string.visuals_now_showing, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        FilledTonalButton(onClick = { onAction(VisualsAction.EnterFullScreen) }, shape = ForestShapes.Button) {
            Icon(Icons.Filled.Fullscreen, contentDescription = null)
            Text(stringResource(R.string.visuals_full_screen), Modifier.padding(start = ForestSpacing.Small))
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onAction: (VisualsAction) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = { onAction(VisualsAction.Search(it)) },
        modifier = Modifier.fillMaxWidth().glass(),
        placeholder = { Text(stringResource(R.string.visuals_search)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        singleLine = true,
        shape = ForestShapes.Chip,
    )
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        modifier = Modifier.padding(top = ForestSpacing.Small, start = ForestSpacing.Small),
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
private fun FoldersTitle(
    title: String,
    onAction: (VisualsAction) -> Unit,
) {
    // Whether the "new folder" dialog is open: App UI state only (ADR-0003).
    var naming by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { SectionTitle(title) }
        TextButton(onClick = { naming = true }) { Text(stringResource(R.string.visuals_new_folder)) }
    }
    if (naming) {
        NameDialog(
            title = stringResource(R.string.visuals_new_folder),
            initial = "",
            onDone = { name ->
                naming = false
                name?.let { onAction(VisualsAction.CreateFolder(it)) }
            },
        )
    }
}

@Composable
private fun Hint(
    @StringRes text: Int,
) {
    Text(
        stringResource(text),
        modifier = Modifier.fillMaxWidth().glass().padding(ForestSpacing.Large),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Path(
    path: List<FolderItem>,
    onAction: (VisualsAction) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(ForestSpacing.ExtraSmall), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onAction(VisualsAction.OpenFolder(null)) }) { Text(stringResource(R.string.visuals_top)) }
        for (folder in path.dropLast(1)) {
            Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { onAction(VisualsAction.OpenFolder(folder.id)) }) {
                Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun FolderRow(
    folder: FolderItem,
    onAction: (VisualsAction) -> Unit,
) {
    // Whether its menu or rename dialog is open: App UI state only.
    var menu by remember { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ForestSpacing.TapTarget)
            .glass()
            .clickable { onAction(VisualsAction.OpenFolder(folder.id)) }
            .padding(horizontal = ForestSpacing.Large, vertical = ForestSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(start = ForestSpacing.Medium)) {
            Text(folder.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.visuals_folder_count, folder.count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.visuals_more, folder.name))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.visuals_rename_folder)) },
                    onClick = {
                        menu = false
                        renaming = true
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.visuals_delete_folder)) },
                    onClick = {
                        menu = false
                        onAction(VisualsAction.DeleteFolder(folder.id))
                    },
                )
            }
        }
    }
    if (renaming) {
        NameDialog(
            title = stringResource(R.string.visuals_rename_folder),
            initial = folder.name,
            onDone = { name ->
                renaming = false
                name?.let { onAction(VisualsAction.RenameFolder(folder.id, it)) }
            },
        )
    }
}

@Composable
private fun StyleRow(
    style: StyleItem,
    folders: List<FolderItem>,
    openFolder: FolderId?,
    onAction: (VisualsAction) -> Unit,
) {
    // Whether its menu is open: App UI state only.
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ForestSpacing.TapTarget)
            .glass()
            .clickable { onAction(VisualsAction.Choose(style.id)) }
            .padding(start = ForestSpacing.Large, end = ForestSpacing.Small)
            .padding(vertical = ForestSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                style.name,
                style = MaterialTheme.typography.titleSmall,
                color = if (style.current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(style.group.label),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (style.current) {
            Icon(
                Icons.Filled.GraphicEq,
                contentDescription = stringResource(R.string.visuals_showing),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        IconButton(onClick = { onAction(VisualsAction.SetFavourite(style.id, !style.favourite)) }) {
            if (style.favourite) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = stringResource(R.string.visuals_unfavourite, style.name),
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else {
                Icon(Icons.Outlined.FavoriteBorder, contentDescription = stringResource(R.string.visuals_favourite, style.name))
            }
        }
        if (folders.isNotEmpty()) {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.visuals_more, style.name))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    openFolder?.let { folder ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.visuals_remove_from_folder)) },
                            onClick = {
                                menu = false
                                onAction(VisualsAction.RemoveFromFolder(folder, style.id))
                            },
                        )
                    }
                    for (folder in folders.filter { it.id != openFolder }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.visuals_add_to, folder.name)) },
                            onClick = {
                                menu = false
                                onAction(VisualsAction.AddToFolder(folder.id, style.id))
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Asks for a folder name; [onDone] gets it, or null when cancelled. */
@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onDone: (String?) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.visuals_folder_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onDone(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.visuals_save)) }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text(stringResource(R.string.visuals_cancel)) } },
    )
}

/** Frosted glass over the live Visual, opaque enough to carry text (ForestGlass.TEXT_OPACITY_MIN). */
@Composable
internal fun Modifier.glass(): Modifier =
    clip(ForestShapes.Card).background(MaterialTheme.colorScheme.surface.copy(alpha = ForestGlass.TEXT_OPACITY_MIN))

/** The name a family is listed under. */
@get:StringRes
internal val StyleGroup.label: Int
    get() =
        when (this) {
            StyleGroup.SHADERS -> R.string.visuals_group_shaders
            StyleGroup.LIFE -> R.string.visuals_group_life
            StyleGroup.MYCELIUM -> R.string.visuals_group_mycelium
            StyleGroup.ACID -> R.string.visuals_group_acid
            StyleGroup.FLUIDS -> R.string.visuals_group_fluids
            StyleGroup.CURL_FLOW -> R.string.visuals_group_curl_flow
            StyleGroup.WATER -> R.string.visuals_group_water
            StyleGroup.CYMATICS -> R.string.visuals_group_cymatics
            StyleGroup.MILKDROP -> R.string.visuals_group_milkdrop
        }
