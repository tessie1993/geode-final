package dev.geode.feature.library.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.geode.design.glass.rememberPressing
import dev.geode.feature.library.api.LibraryCommand
import dev.geode.feature.library.api.SortKey

/**
 * Sort and filter (#37): by name, date, length or plays; by format or folder; short clips such as
 * voice notes hidden; and the folders the user hid, to show again. Which menu is open is App UI state.
 */
@Composable
internal fun Controls(
    model: LibraryModel,
    send: (LibraryCommand) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = LibraryDimens.gutter),
        horizontalArrangement = Arrangement.spacedBy(LibraryDimens.small),
    ) {
        SortChip(model, send)
        FormatChip(model, send)
        FolderChip(model, send)
        FilterChip(
            selected = model.filter.hideShortClips,
            onClick = rememberPressing { send(LibraryCommand.FilterBy(model.filter.copy(hideShortClips = !model.filter.hideShortClips))) },
            label = { Text(stringResource(R.string.library_filter_short_clips)) },
        )
    }
}

@Composable
private fun SortChip(
    model: LibraryModel,
    send: (LibraryCommand) -> Unit,
) {
    MenuChip(
        label = stringResource(R.string.library_sort) + ": " + sortName(model.sort.key),
        selected = false,
    ) { close ->
        SortKey.entries.forEach { key ->
            Choice(sortName(key), chosen = key == model.sort.key) {
                close()
                send(LibraryCommand.SortBy(model.sort.copy(key = key)))
            }
        }
        HorizontalDivider()
        Choice(stringResource(R.string.library_sort_descending), chosen = model.sort.descending) {
            close()
            send(LibraryCommand.SortBy(model.sort.copy(descending = !model.sort.descending)))
        }
    }
}

@Composable
private fun FormatChip(
    model: LibraryModel,
    send: (LibraryCommand) -> Unit,
) {
    val formats = model.filter.formats
    MenuChip(
        label = if (formats.isEmpty()) stringResource(R.string.library_filter_format) else formats.sorted().joinToString(", "),
        selected = formats.isNotEmpty(),
    ) { close ->
        Choice(stringResource(R.string.library_filter_all_formats), chosen = formats.isEmpty()) {
            close()
            send(LibraryCommand.FilterBy(model.filter.copy(formats = emptySet())))
        }
        model.formats.forEach { format ->
            Choice(format, chosen = format in formats) {
                send(LibraryCommand.FilterBy(model.filter.copy(formats = if (format in formats) formats - format else formats + format)))
            }
        }
    }
}

@Composable
private fun FolderChip(
    model: LibraryModel,
    send: (LibraryCommand) -> Unit,
) {
    val folder = model.filter.folder
    MenuChip(
        label = folder?.substringAfterLast('/') ?: stringResource(R.string.library_filter_folder),
        selected = folder != null,
    ) { close ->
        Choice(stringResource(R.string.library_filter_all_folders), chosen = folder == null) {
            close()
            send(LibraryCommand.FilterBy(model.filter.copy(folder = null)))
        }
        model.folders.filterNot { it in model.excludedFolders }.forEach { path ->
            Choice(path, chosen = path == folder) {
                close()
                send(LibraryCommand.FilterBy(model.filter.copy(folder = path)))
            }
        }
        if (model.excludedFolders.isNotEmpty()) {
            HorizontalDivider()
            model.excludedFolders.sorted().forEach { path ->
                Choice(stringResource(R.string.library_action_include_folder) + ": " + path, chosen = false) {
                    close()
                    send(LibraryCommand.IncludeFolder(path))
                }
            }
        }
    }
}

/** A chip that opens a menu; [content] gets a function that closes it. */
@Composable
private fun MenuChip(
    label: String,
    selected: Boolean,
    content: @Composable (close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected,
            onClick = rememberPressing { open = true },
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            content { open = false }
        }
    }
}

@Composable
private fun Choice(
    label: String,
    chosen: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        trailingIcon = { if (chosen) Icon(Icons.Rounded.Check, contentDescription = null) },
    )
}

@Composable
private fun sortName(key: SortKey): String =
    stringResource(
        when (key) {
            SortKey.NAME -> R.string.library_sort_name
            SortKey.DATE_ADDED -> R.string.library_sort_date_added
            SortKey.LENGTH -> R.string.library_sort_length
            SortKey.PLAYS -> R.string.library_sort_plays
        },
    )
