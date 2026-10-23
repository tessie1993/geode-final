package dev.geode.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.geode.R
import dev.geode.design.CrystalButton
import dev.geode.design.CrystalOverline
import dev.geode.design.CrystalSegmented
import dev.geode.design.CrystalTabs
import dev.geode.design.GlowTitle
import dev.geode.design.theme.StoneIcon
import dev.geode.design.theme.StoneIconArt
import dev.geode.design.theme.accentTextColor
import dev.geode.engine.types.TrackId
import dev.geode.feature.library.api.LibraryFact

/** Playlists sit last and behave differently: hand-ordered, so search and sort do not apply. */
private const val PLAYLISTS_TAB = 4
private const val DUPLICATES_TAB = 5

@Composable
fun LibraryScreen(onOpenSearch: () -> Unit) {
    val libraryViewModel: LibraryViewModel = geodeViewModel()
    val context = LocalContext.current
    val permission =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }
    val permLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val state by libraryViewModel.uiState.collectAsStateWithLifecycle()
    // The Library watches the phone's music itself; once it may read it, it looks.
    LaunchedEffect(granted) { if (granted) libraryViewModel.refreshDeviceTracks() }
    var tab by rememberSaveable { mutableStateOf(0) }
    val tabs =
        listOf(
            stringResource(R.string.library_tab_tracks),
            stringResource(R.string.library_tab_albums),
            stringResource(R.string.library_tab_artists),
            stringResource(R.string.library_tab_folders),
            stringResource(R.string.library_tab_playlists),
            stringResource(R.string.library_tab_duplicates),
        )
    // Already searched and sorted by the ViewModel — the screen draws what it is handed.
    val shown = state.tracks

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                CrystalOverline(stringResource(R.string.app_name))
                GlowTitle(stringResource(R.string.nav_library))
            }
            IconButton(onClick = onOpenSearch) { StoneIconArt(StoneIcon.SEARCH, stringResource(R.string.action_search)) }
        }
        if (!granted) {
            val activity = LocalActivity.current
            var asked by rememberSaveable { mutableStateOf(false) }
            val canAskAgain =
                activity == null ||
                    !asked ||
                    ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(
                        if (canAskAgain) {
                            R.string.library_permission_rationale
                        } else {
                            R.string.library_permission_denied_forever
                        },
                    ),
                )
                if (canAskAgain) {
                    CrystalButton(onClick = {
                        asked = true
                        permLauncher.launch(permission)
                    }) { Text(stringResource(R.string.library_permission_allow)) }
                } else {
                    val context = LocalContext.current
                    CrystalButton(
                        onClick = { context.openAppSettings() },
                    ) { Text(stringResource(R.string.library_permission_open_settings)) }
                }
            }
            return
        }
        CrystalTabs(titles = tabs, selected = tab, onSelect = { tab = it })
        // Search and sort belong to the track-shaped tabs. Playlists are ordered by hand, and
        // re-sorting someone's running order out from under them would be a bug, not a feature.
        if (tab != PLAYLISTS_TAB && tab != DUPLICATES_TAB) {
            OutlinedTextField(
                value = state.query,
                onValueChange = libraryViewModel::setQuery,
                singleLine = true,
                label = { Text(stringResource(R.string.library_search_hint)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            CrystalSegmented(
                options = LibrarySort.entries.map { stringResource(it.labelRes) },
                selected = LibrarySort.entries.indexOf(state.sort),
                onSelect = { libraryViewModel.setSort(LibrarySort.entries[it]) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        when (tab) {
            0 -> TrackList(shown, libraryViewModel, state.isSearching)
            1 -> GroupList(shown.groupBy { it.album }, libraryViewModel)
            2 -> GroupList(shown.groupBy { it.artist }, libraryViewModel)
            3 -> FoldersTab(shown.groupBy { it.folder }, libraryViewModel)
            PLAYLISTS_TAB -> PlaylistsTab(libraryViewModel)
            DUPLICATES_TAB -> DuplicatesTab(state.tracks, onPlay = { libraryViewModel.play(listOf(it)) })
        }
    }
}

@Composable
private fun TrackList(
    tracks: List<DeviceTrack>,
    viewModel: LibraryViewModel,
    searching: Boolean = false,
) {
    val queue = remember(tracks) { tracks.map { it.uri } }
    LazyColumn(Modifier.fillMaxSize()) {
        items(tracks, key = { it.uri.value }) { t -> TrackRow(t, viewModel, queue = queue) }
        if (tracks.isEmpty()) {
            // An empty library and an empty result set are different problems, and telling
            // someone "no music found" mid-search would send them looking for the wrong fix.
            val empty = if (searching) R.string.library_no_results else R.string.library_no_music
            item { Text(stringResource(empty), Modifier.padding(16.dp)) }
        }
    }
}

@Composable
private fun TrackRow(
    t: DeviceTrack,
    viewModel: LibraryViewModel,
    subtitleOverride: String? = null,
    queue: List<TrackId> = emptyList(),
) {
    // Title, artist, album, duration and artwork, with the user's edits already in, and nothing
    // else. No tempo, no key, no analysed badge: the library never waits on analysis.
    val subtitle = subtitleOverride ?: listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" · ")
    val duration = LibraryBrowse.formatDuration(t.duration)
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var addingToPlaylist by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { viewModel.play(queue.ifEmpty { listOf(t.uri) }, t.uri) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrackArtwork(t.uri, Modifier.size(44.dp), corner = 8.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (duration.isNotBlank()) {
            Text(
                duration,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.action_more)) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.action_play_next)) }, onClick = {
                viewModel.playNext(t.uri)
                menu = false
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.action_add_to_queue)) }, onClick = {
                viewModel.enqueue(t.uri)
                menu = false
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.action_add_to_playlist)) }, onClick = {
                addingToPlaylist = true
                menu = false
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.action_edit_track_info)) }, onClick = {
                editing = true
                menu = false
            })
        }
    }
    if (editing) {
        TrackInfoEditor(uri = t.uri, viewModel = viewModel, onDismiss = { editing = false })
    }
    if (addingToPlaylist) {
        NewPlaylistDialog(songs = listOf(t.uri), viewModel = viewModel, onDismiss = { addingToPlaylist = false })
    }
}

/** A new playlist of [songs]. Adding songs to a playlist already made waits for a Library Command. */
@Composable
private fun NewPlaylistDialog(
    songs: List<TrackId>,
    viewModel: LibraryViewModel,
    onDismiss: () -> Unit,
) {
    val library by viewModel.library.collectAsStateWithLifecycle()
    PlaylistNameDialog(
        title = stringResource(R.string.playlist_new),
        confirmLabel = stringResource(R.string.action_create),
        taken = library.playlists.map { it.name }.toSet(),
        onName = { name -> viewModel.createMusicPlaylist(name, songs) },
        onDismiss = onDismiss,
    )
}

@Composable
private fun GroupList(
    groups: Map<String, List<DeviceTrack>>,
    viewModel: LibraryViewModel,
) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val sel = open
    val dismiss = rememberPredictiveDismiss(enabled = sel != null) { open = null }
    if (sel != null && groups.containsKey(sel)) {
        Column(Modifier.dismissTransform(dismiss)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.library_back),
                    Modifier.clickable { open = null }.padding(end = 12.dp),
                    color = accentTextColor(),
                )
                Text(sel, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val queue = remember(sel, groups) { groups.getValue(sel).map { it.uri } }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CrystalButton(compact = true, onClick = { viewModel.play(queue) }) { Text(stringResource(R.string.library_play_all)) }
                CrystalButton(compact = true, filled = false, onClick = {
                    viewModel.shuffle(queue)
                }) { Text(stringResource(R.string.action_shuffle)) }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(groups.getValue(sel), key = { it.uri.value }) { t ->
                    TrackRow(t, viewModel, subtitleOverride = t.album, queue = queue)
                }
            }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(groups.keys.sorted()) { g ->
                Row(
                    Modifier.fillMaxWidth().clickable { open = g }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            g.ifEmpty { stringResource(R.string.library_group_unnamed) },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            pluralStringResource(
                                R.plurals.track_count,
                                groups.getValue(g).size,
                                groups.getValue(g).size,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Playback's playlists, through the Library: play, import and delete them. A new one is made from a
 * song's menu or the queue, since the Library makes a playlist of songs.
 */
@Composable
private fun PlaylistsTab(viewModel: LibraryViewModel) {
    val library by viewModel.library.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<String?>(null) }
    var imported by remember { mutableStateOf<LibraryFact.PlaylistImported?>(null) }
    LaunchedEffect(viewModel) { viewModel.playlistsImported.collect { imported = it } }
    val importPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.importPlaylistFile(uri)
        }
    Column {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CrystalButton(
                compact = true,
                filled = false,
                onClick = { importPicker.launch(arrayOf("*/*")) },
            ) { Text(stringResource(R.string.playlist_import_action)) }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item { SmartPlaylistsSection(viewModel) }
            items(library.playlists, key = { it.id.value }) { pl ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(pl.name)
                        Text(
                            pluralStringResource(R.plurals.track_count, pl.trackCount, pl.trackCount),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    IconButton(onClick = { viewModel.playPlaylist(pl.name) }) {
                        StoneIconArt(StoneIcon.PLAY, stringResource(R.string.action_play))
                    }
                    IconButton(onClick = { deleting = pl.name }) {
                        StoneIconArt(StoneIcon.CLOSE, stringResource(R.string.playlist_delete_title))
                    }
                }
            }
            if (library.playlists.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.playlist_none_yet),
                        Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
    deleting?.let { doomed ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.playlist_delete_title)) },
            text = { Text(stringResource(R.string.playlist_delete_body, doomed)) },
            confirmButton = {
                CrystalButton(onClick = {
                    viewModel.deleteMusicPlaylist(doomed)
                    deleting = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    imported?.let { result ->
        AlertDialog(
            onDismissRequest = { imported = null },
            title = { Text(stringResource(R.string.playlist_import_done_title)) },
            text = { Text(stringResource(R.string.playlist_import_summary, result.name, result.found, result.missing, 0)) },
            confirmButton = { TextButton(onClick = { imported = null }) { Text(stringResource(R.string.action_ok)) } },
        )
    }
}

@Composable
private fun FoldersTab(
    folders: Map<String, List<DeviceTrack>>,
    viewModel: LibraryViewModel,
) {
    Column {
        Text(
            stringResource(R.string.folders_library),
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.titleSmall,
        )
        Column(Modifier.padding(horizontal = 16.dp)) { MusicFoldersEditor(viewModel) }
        Text(
            stringResource(R.string.folders_device),
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        GroupList(FolderTree.rows(folders), viewModel)
    }
}
