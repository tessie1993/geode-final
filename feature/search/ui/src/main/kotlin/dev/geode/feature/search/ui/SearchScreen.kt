package dev.geode.feature.search.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import dev.geode.design.forest.ForestShapes
import dev.geode.design.forest.ForestSpacing
import dev.geode.feature.search.api.PlaylistResult
import dev.geode.feature.search.api.PresetResult
import dev.geode.feature.search.api.SearchResults
import dev.geode.feature.search.api.SongResult

/**
 * Global Search (#143): one field, and the songs, playlists and presets it finds, grouped, each group
 * headed with how many it holds. A song plays with every song found as the queue, or joins the queue;
 * a playlist plays; a preset is applied. Stateless (ADR-0003): it draws [model] and sends every tap to
 * [onAction].
 */
@Composable
fun SearchScreen(
    model: SearchModel,
    onAction: (SearchAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(ForestSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(ForestSpacing.Medium),
        ) {
            SearchBar(model.query, onAction)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = ForestSpacing.Large)) {
                when (val results = model.results) {
                    SearchResults.NotSearching -> item { Hint(stringResource(R.string.search_hint)) }
                    is SearchResults.Found ->
                        if (results.isEmpty) {
                            item { Hint(stringResource(R.string.search_nothing_found, results.query)) }
                        } else {
                            found(results, onAction)
                        }
                }
            }
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onAction: (SearchAction) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = query,
            onValueChange = { onAction(SearchAction.Type(it)) },
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.search_placeholder), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onAction(SearchAction.Type("")) }) {
                        Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.search_clear))
                    }
                }
            },
            singleLine = true,
            shape = ForestShapes.Chip,
        )
        IconButton(onClick = { onAction(SearchAction.Close) }) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_close))
        }
    }
}

private fun LazyListScope.found(
    results: SearchResults.Found,
    onAction: (SearchAction) -> Unit,
) {
    if (results.songs.isNotEmpty()) {
        item { Heading(R.string.search_songs, results.songs.size) }
        items(results.songs, key = { "song:" + it.id.value }) { SongRow(it, onAction) }
    }
    if (results.playlists.isNotEmpty()) {
        item { Heading(R.string.search_playlists, results.playlists.size) }
        items(results.playlists, key = { "playlist:" + it.id.value }) { PlaylistRow(it, onAction) }
    }
    if (results.presets.isNotEmpty()) {
        item { Heading(R.string.search_presets, results.presets.size) }
        items(results.presets, key = { "preset:" + it.id.value }) { PresetRow(it, onAction) }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        modifier = Modifier.padding(vertical = ForestSpacing.Large),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Heading(
    @StringRes title: Int,
    count: Int,
) {
    Text(
        stringResource(title, count),
        modifier = Modifier.padding(top = ForestSpacing.Medium, bottom = ForestSpacing.ExtraSmall),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SongRow(
    song: SongResult,
    onAction: (SearchAction) -> Unit,
) {
    ResultRow(
        icon = Icons.Filled.MusicNote,
        title = song.title,
        detail = listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" · "),
        onClick = { onAction(SearchAction.PlaySong(song.id)) },
    ) {
        IconButton(onClick = { onAction(SearchAction.QueueSong(song.id)) }) {
            Icon(
                Icons.AutoMirrored.Filled.PlaylistAdd,
                contentDescription = stringResource(R.string.search_add_to_queue, song.title),
            )
        }
    }
}

@Composable
private fun PlaylistRow(
    playlist: PlaylistResult,
    onAction: (SearchAction) -> Unit,
) {
    ResultRow(
        icon = Icons.AutoMirrored.Filled.QueueMusic,
        title = playlist.name,
        detail = pluralStringResource(R.plurals.search_song_count, playlist.trackCount, playlist.trackCount),
        onClick = { onAction(SearchAction.PlayPlaylist(playlist.id)) },
    )
}

@Composable
private fun PresetRow(
    preset: PresetResult,
    onAction: (SearchAction) -> Unit,
) {
    ResultRow(
        icon = Icons.Filled.AutoAwesome,
        title = preset.name,
        detail = "",
        onClick = { onAction(SearchAction.ApplyPreset(preset.id)) },
    )
}

@Composable
private fun ResultRow(
    icon: ImageVector,
    title: String,
    detail: String,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = ForestSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ForestSpacing.Medium),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
    }
}
