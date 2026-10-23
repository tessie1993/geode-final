package dev.geode.feature.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import dev.geode.design.element.LocalElementTokens
import dev.geode.feature.library.api.Song
import dev.geode.feature.library.api.SongEdit

/** The song info editor (#37) in a dialog: title, artist, album and cover art. */
@Composable
internal fun SongEditorDialog(
    song: Song,
    onSave: (SongEdit) -> Unit,
    onDismiss: () -> Unit,
    onPickArtwork: ((String) -> Unit) -> Unit,
) {
    var fields by remember(song.id) { mutableStateOf(SongEditorFields.of(song)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_edit_title)) },
        text = {
            SongEditor(
                fields,
                onChange = { fields = it },
                onPickArtwork = { onPickArtwork { picked -> fields = fields.copy(artwork = picked) } },
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(fields.toEdit()) }, enabled = fields.canSave) {
                Text(stringResource(R.string.library_edit_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_edit_cancel)) } },
    )
}

/** The editor's fields. Stateless: it draws [fields] and reports each change to [onChange]. */
@Composable
fun SongEditor(
    fields: SongEditorFields,
    onChange: (SongEditorFields) -> Unit,
    onPickArtwork: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(LibraryDimens.small)) {
        Field(fields.title, R.string.library_edit_song_title) { onChange(fields.copy(title = it)) }
        Field(fields.artist, R.string.library_edit_artist) { onChange(fields.copy(artist = it)) }
        Field(fields.album, R.string.library_edit_album) { onChange(fields.copy(album = it)) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LibraryDimens.gap)) {
            Box(
                Modifier
                    .size(LibraryDimens.editorArtwork)
                    .clip(LocalElementTokens.current.shapes.card)
                    .background(LocalElementTokens.current.colors.surfaceHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (fields.artwork == null) Icons.Filled.MusicNote else Icons.Filled.Image,
                    contentDescription = stringResource(R.string.library_edit_artwork),
                    tint = LocalElementTokens.current.colors.active,
                )
            }
            Column {
                Text(
                    stringResource(if (fields.artwork == null) R.string.library_edit_file_art else R.string.library_edit_chosen_art),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onPickArtwork) { Text(stringResource(R.string.library_edit_choose_artwork)) }
                if (fields.artwork != null) {
                    TextButton(onClick = { onChange(fields.copy(artwork = null)) }) {
                        Text(stringResource(R.string.library_edit_clear_artwork))
                    }
                }
            }
        }
    }
}

@Composable
private fun Field(
    value: String,
    label: Int,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
