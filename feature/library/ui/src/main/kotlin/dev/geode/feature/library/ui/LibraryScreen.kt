package dev.geode.feature.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import dev.geode.feature.library.api.GroupKind
import dev.geode.feature.library.api.GroupRow
import dev.geode.feature.library.api.IndexStatus
import dev.geode.feature.library.api.LibraryCommand
import dev.geode.feature.library.api.LibraryView
import dev.geode.feature.library.api.Page
import dev.geode.feature.library.api.Pick
import dev.geode.feature.library.api.Place
import dev.geode.feature.library.api.PlaylistRef
import dev.geode.feature.library.api.SongRow

/**
 * The Library (#37): its views, one search, sort and filter, and every song, album, artist, folder and
 * playlist with what can be done with it. Stateless (ADR-0003): it draws [model] and sends every tap to
 * [onAction]. Which menu or editor is open is App UI state, kept here. The per-theme layouts are #74.
 */
@Composable
fun LibraryScreen(
    model: LibraryModel,
    onAction: (LibraryAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The song whose info is being edited: App UI state only (ADR-0003).
    var editing by remember { mutableStateOf<SongRow?>(null) }
    val send = { command: LibraryCommand -> onAction(LibraryAction.Send(command)) }
    Surface(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar(model, send)
            SearchField(model.query, send)
            ViewChips(model.view, send)
            Controls(model, send)
            IndexBanner(model.index, onAction)
            Content(model, onAction, onEdit = { editing = it })
        }
    }
    editing?.let { row ->
        SongEditorDialog(
            song = row.song,
            onSave = { edit ->
                send(LibraryCommand.EditSong(row.song.id, edit))
                editing = null
            },
            onDismiss = { editing = null },
            onPickArtwork = { picked -> onAction(LibraryAction.PickArtwork(picked)) },
        )
    }
}

@Composable
private fun TopBar(
    model: LibraryModel,
    send: (LibraryCommand) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = LibraryDimens.small, vertical = LibraryDimens.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (model.canGoBack) {
            IconButton(onClick = { send(LibraryCommand.Back) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.library_back))
            }
        }
        Text(
            title(model),
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = LibraryDimens.small),
        )
    }
}

@Composable
private fun title(model: LibraryModel): String =
    when (val place = model.place) {
        is Place.Group -> groupTitle(GroupRow(place.key, displayName(place.key.kind, place.key.name), "", 0, null))
        is Place.Shelf -> if (place.view == LibraryView.SONGS) stringResource(R.string.library_title) else viewName(place.view)
    }

@Composable
private fun SearchField(
    query: String,
    send: (LibraryCommand) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = { send(LibraryCommand.Search(it)) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = LibraryDimens.gutter),
        singleLine = true,
        placeholder = { Text(stringResource(R.string.library_search), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { send(LibraryCommand.Search("")) }) {
                    Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.library_search_clear))
                }
            }
        },
    )
}

@Composable
private fun ViewChips(
    selected: LibraryView,
    send: (LibraryCommand) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = LibraryDimens.gutter, vertical = LibraryDimens.small),
        horizontalArrangement = Arrangement.spacedBy(LibraryDimens.small),
    ) {
        items(LibraryView.entries) { view ->
            FilterChip(
                selected = view == selected,
                onClick = { send(LibraryCommand.Show(view)) },
                label = { Text(viewName(view)) },
            )
        }
    }
}

@Composable
private fun IndexBanner(
    index: IndexStatus,
    onAction: (LibraryAction) -> Unit,
) {
    when (index) {
        IndexStatus.Loading -> Progress(stringResource(R.string.library_loading))
        IndexStatus.Scanning -> Progress(stringResource(R.string.library_scanning))
        IndexStatus.Ready -> Unit
        IndexStatus.NeedsPermission ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(LibraryDimens.rowArtworkCorner),
                modifier = Modifier.fillMaxWidth().padding(horizontal = LibraryDimens.gutter, vertical = LibraryDimens.small),
            ) {
                Row(Modifier.padding(LibraryDimens.gap), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.library_needs_permission),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { onAction(LibraryAction.AskPermission) }) { Text(stringResource(R.string.library_allow)) }
                }
            }
    }
}

@Composable
private fun Progress(label: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = LibraryDimens.gutter, vertical = LibraryDimens.tiny)) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = LibraryDimens.tiny),
        )
    }
}

@Composable
private fun Content(
    model: LibraryModel,
    onAction: (LibraryAction) -> Unit,
    onEdit: (SongRow) -> Unit,
) {
    val send = { command: LibraryCommand -> onAction(LibraryAction.Send(command)) }
    val page = model.page
    if (isEmpty(page)) {
        Empty(page, model.index)
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LibraryDimens.gutter)) {
        when (page) {
            is Page.Songs -> {
                val place = model.place
                if (place is Place.Group) {
                    item { GroupHeader(place, page.rows.size, onAction) }
                }
                songs(page.rows, onAction, onEdit) { row -> send(LibraryCommand.Play(Pick.Shown, from = row.song.id)) }
            }
            is Page.Groups -> {
                if (model.view == LibraryView.PLAYLISTS) {
                    item { PlaylistsHeader(onAction) }
                }
                groups(page.rows, send)
            }
            is Page.Found -> found(page, onAction, onEdit)
        }
    }
}

private fun isEmpty(page: Page): Boolean =
    when (page) {
        is Page.Songs -> page.rows.isEmpty()
        // The playlists page always has its import button.
        is Page.Groups -> false
        is Page.Found -> page.isEmpty
    }

@Composable
private fun Empty(
    page: Page,
    index: IndexStatus,
) {
    if (index == IndexStatus.Loading) return
    Column(
        Modifier.fillMaxSize().padding(LibraryDimens.gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LibraryDimens.small, Alignment.CenterVertically),
    ) {
        Icon(
            Icons.Filled.LibraryMusic,
            contentDescription = null,
            modifier = Modifier.size(LibraryDimens.headerIcon),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (page is Page.Found) {
            Text(stringResource(R.string.library_nothing_found, page.query), style = MaterialTheme.typography.titleMedium)
        } else {
            Text(stringResource(R.string.library_empty), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.library_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun LazyListScope.songs(
    rows: List<SongRow>,
    onAction: (LibraryAction) -> Unit,
    onEdit: (SongRow) -> Unit,
    onPlay: (SongRow) -> Unit,
) {
    items(rows, key = { "song:" + it.song.id.value }) { row ->
        SongItem(row, onAction, onEdit, onPlay)
    }
}

private fun LazyListScope.groups(
    rows: List<GroupRow>,
    send: (LibraryCommand) -> Unit,
) {
    items(rows, key = { "group:" + it.key.kind + it.key.name }) { row -> GroupItem(row, send) }
}

private fun LazyListScope.found(
    page: Page.Found,
    onAction: (LibraryAction) -> Unit,
    onEdit: (SongRow) -> Unit,
) {
    val send = { command: LibraryCommand -> onAction(LibraryAction.Send(command)) }
    if (page.songs.isNotEmpty()) {
        item { SectionTitle(R.string.library_found_songs) }
        songs(page.songs, onAction, onEdit) { row -> send(LibraryCommand.Play(Pick.Shown, from = row.song.id)) }
    }
    listOf(
        R.string.library_found_albums to page.albums,
        R.string.library_found_artists to page.artists,
        R.string.library_found_folders to page.folders,
        R.string.library_found_playlists to page.playlists,
    ).filter { (_, rows) -> rows.isNotEmpty() }
        .forEach { (title, rows) ->
            item { SectionTitle(title) }
            groups(rows, send)
        }
}

@Composable
private fun SectionTitle(title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            Modifier.padding(
                start = LibraryDimens.gutter,
                end = LibraryDimens.gutter,
                top = LibraryDimens.gap,
                bottom = LibraryDimens.tiny,
            ),
    )
}

@Composable
private fun SongItem(
    row: SongRow,
    onAction: (LibraryAction) -> Unit,
    onEdit: (SongRow) -> Unit,
    onPlay: (SongRow) -> Unit,
) {
    val send = { command: LibraryCommand -> onAction(LibraryAction.Send(command)) }
    val song = row.song
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onPlay(row) }
            .padding(start = LibraryDimens.gutter, top = LibraryDimens.small, bottom = LibraryDimens.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LibraryDimens.gap),
    ) {
        Artwork(Icons.Filled.MusicNote)
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(song.artist.ifBlank { stringResource(R.string.library_unknown_artist) }, song.album)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(formatLength(song.duration), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(onClick = { send(LibraryCommand.ToggleFavourite(song.id)) }) {
            Icon(
                if (row.favourite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription =
                    stringResource(if (row.favourite) R.string.library_action_unfavourite else R.string.library_action_favourite),
                tint = if (row.favourite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.library_action_more))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                val pick = Pick.Songs(listOf(song.id))
                MenuItem(R.string.library_action_play_next) {
                    menu = false
                    send(LibraryCommand.PlayNext(pick))
                }
                MenuItem(R.string.library_action_add_to_queue) {
                    menu = false
                    send(LibraryCommand.AddToQueue(pick))
                }
                MenuItem(R.string.library_action_edit) {
                    menu = false
                    onEdit(row)
                }
                MenuItem(R.string.library_action_exclude_folder) {
                    menu = false
                    send(LibraryCommand.ExcludeFolder(song.folder))
                }
            }
        }
    }
}

@Composable
private fun GroupItem(
    row: GroupRow,
    send: (LibraryCommand) -> Unit,
) {
    val playlist = row.key.kind == GroupKind.PLAYLIST
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { send(if (playlist) LibraryCommand.Play(Pick.Group(row.key)) else LibraryCommand.Open(row.key)) }
            .padding(start = LibraryDimens.gutter, top = LibraryDimens.small, bottom = LibraryDimens.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LibraryDimens.gap),
    ) {
        Artwork(iconOf(row.key.kind))
        Column(Modifier.weight(1f)) {
            Text(groupTitle(row), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(row.detail, pluralStringResource(R.plurals.library_song_count, row.songCount, row.songCount))
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.library_action_more))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                val pick = Pick.Group(row.key)
                MenuItem(R.string.library_action_play) {
                    menu = false
                    send(LibraryCommand.Play(pick))
                }
                if (playlist) {
                    MenuItem(R.string.library_action_delete_playlist) {
                        menu = false
                        val id = row.key.name.toLongOrNull()
                        if (id != null) send(LibraryCommand.DeletePlaylist(PlaylistRef(id)))
                    }
                } else {
                    MenuItem(R.string.library_action_play_next) {
                        menu = false
                        send(LibraryCommand.PlayNext(pick))
                    }
                    MenuItem(R.string.library_action_add_to_queue) {
                        menu = false
                        send(LibraryCommand.AddToQueue(pick))
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(
    place: Place.Group,
    songCount: Int,
    onAction: (LibraryAction) -> Unit,
) {
    val send = { command: LibraryCommand -> onAction(LibraryAction.Send(command)) }
    val pick = Pick.Group(place.key)
    val name = groupTitle(GroupRow(place.key, displayName(place.key.kind, place.key.name), "", songCount, null))
    Column(
        Modifier.fillMaxWidth().padding(LibraryDimens.gutter),
        verticalArrangement = Arrangement.spacedBy(LibraryDimens.gap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LibraryDimens.gutter)) {
            Box(
                Modifier
                    .size(LibraryDimens.headerArtwork)
                    .clip(RoundedCornerShape(LibraryDimens.headerArtworkCorner))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    iconOf(place.key.kind),
                    contentDescription = null,
                    modifier = Modifier.size(LibraryDimens.headerIcon),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column {
                Text(name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    pluralStringResource(R.plurals.library_song_count, songCount, songCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(LibraryDimens.small)) {
            Button(onClick = { send(LibraryCommand.Play(pick)) }) { Text(stringResource(R.string.library_action_play)) }
            OutlinedButton(onClick = { send(LibraryCommand.PlayNext(pick)) }) { Text(stringResource(R.string.library_action_play_next)) }
            OutlinedButton(onClick = { send(LibraryCommand.AddToQueue(pick)) }) {
                Text(stringResource(R.string.library_action_add_to_queue))
            }
        }
        OutlinedButton(onClick = { onAction(LibraryAction.ExportPlaylist(pick, name)) }) {
            Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null)
            Text(stringResource(R.string.library_action_export), Modifier.padding(start = LibraryDimens.small))
        }
        HorizontalDivider()
    }
}

@Composable
private fun PlaylistsHeader(onAction: (LibraryAction) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = LibraryDimens.gutter, vertical = LibraryDimens.small),
        horizontalArrangement = Arrangement.spacedBy(LibraryDimens.small),
    ) {
        OutlinedButton(onClick = { onAction(LibraryAction.ImportPlaylist) }) {
            Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null)
            Text(stringResource(R.string.library_action_import), Modifier.padding(start = LibraryDimens.small))
        }
    }
}

@Composable
internal fun MenuItem(
    label: Int,
    onClick: () -> Unit,
) {
    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = onClick)
}

@Composable
private fun Artwork(icon: ImageVector) {
    Box(
        Modifier
            .size(LibraryDimens.rowArtwork)
            .clip(RoundedCornerShape(LibraryDimens.rowArtworkCorner))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(LibraryDimens.rowIcon),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun iconOf(kind: GroupKind): ImageVector =
    when (kind) {
        GroupKind.ALBUM -> Icons.Filled.Album
        GroupKind.ARTIST, GroupKind.ALBUM_ARTIST, GroupKind.COMPOSER -> Icons.Filled.Person
        GroupKind.GENRE -> Icons.Filled.Category
        GroupKind.FOLDER -> Icons.Filled.Folder
        GroupKind.PLAYLIST -> Icons.AutoMirrored.Filled.QueueMusic
    }

/** A group's name as shown: its own, or "Unknown …" when the tags name none. */
@Composable
private fun groupTitle(row: GroupRow): String =
    row.title.ifBlank {
        stringResource(
            when (row.key.kind) {
                GroupKind.ALBUM -> R.string.library_unknown_album
                GroupKind.ARTIST, GroupKind.ALBUM_ARTIST -> R.string.library_unknown_artist
                GroupKind.GENRE -> R.string.library_unknown_genre
                GroupKind.COMPOSER -> R.string.library_unknown_composer
                GroupKind.FOLDER, GroupKind.PLAYLIST -> R.string.library_unknown_folder
            },
        )
    }

/** What a group named [name] is titled: a folder by its last part. */
private fun displayName(
    kind: GroupKind,
    name: String,
): String = if (kind == GroupKind.FOLDER) name.substringAfterLast('/') else name

@Composable
internal fun viewName(view: LibraryView): String =
    stringResource(
        when (view) {
            LibraryView.SONGS -> R.string.library_view_songs
            LibraryView.ALBUMS -> R.string.library_view_albums
            LibraryView.ARTISTS -> R.string.library_view_artists
            LibraryView.ALBUM_ARTISTS -> R.string.library_view_album_artists
            LibraryView.GENRES -> R.string.library_view_genres
            LibraryView.COMPOSERS -> R.string.library_view_composers
            LibraryView.FOLDERS -> R.string.library_view_folders
            LibraryView.PLAYLISTS -> R.string.library_view_playlists
            LibraryView.FAVOURITES -> R.string.library_view_favourites
            LibraryView.RECENTLY_ADDED -> R.string.library_view_recently_added
            LibraryView.RECENTLY_PLAYED -> R.string.library_view_recently_played
            LibraryView.MOST_PLAYED -> R.string.library_view_most_played
        },
    )
