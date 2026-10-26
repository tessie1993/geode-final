package dev.geode.feature.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import dev.geode.feature.library.api.Song
import dev.geode.feature.library.api.SongEdit

/** Genres offered with one tap, as the old track info editor offered them. They are tag values, not words to translate. */
private val COMMON_GENRES = listOf("Electronic", "Rock", "Pop", "Hip-Hop", "Jazz", "Classical", "Ambient", "Other")

private const val YEAR_DIGITS = 4
private const val TRACK_NUMBER_DIGITS = 3
private const val COMMENT_LINES = 3

/**
 * The song info editor (#37) in a dialog: title, artist, album, cover art, genre, year, track number and
 * comment, and whether to write them into the file too. [onSave] gets the edit and that choice.
 */
@Composable
internal fun SongEditorDialog(
    song: Song,
    onSave: (SongEdit, Boolean) -> Unit,
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
                Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(fields.toEdit(song), fields.writeToFile) }, enabled = fields.canSave) {
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
                    .clip(RoundedCornerShape(LibraryDimens.rowArtworkCorner))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (fields.artwork == null) Icons.Rounded.MusicNote else Icons.Rounded.Image,
                    contentDescription = stringResource(R.string.library_edit_artwork),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
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
        Field(fields.genre, R.string.library_edit_genre) { onChange(fields.copy(genre = it)) }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(LibraryDimens.small)) {
            COMMON_GENRES.forEach { genre ->
                FilterChip(
                    selected = fields.genre.equals(genre, ignoreCase = true),
                    onClick = { onChange(fields.copy(genre = genre)) },
                    label = { Text(genre) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(LibraryDimens.small)) {
            Field(fields.year, R.string.library_edit_year, Modifier.weight(1f)) {
                onChange(fields.copy(year = it.filter(Char::isDigit).take(YEAR_DIGITS)))
            }
            Field(fields.trackNumber, R.string.library_edit_track_number, Modifier.weight(1f)) {
                onChange(fields.copy(trackNumber = it.filter(Char::isDigit).take(TRACK_NUMBER_DIGITS)))
            }
        }
        OutlinedTextField(
            value = fields.comment,
            onValueChange = { onChange(fields.copy(comment = it)) },
            label = { Text(stringResource(R.string.library_edit_comment)) },
            minLines = COMMENT_LINES,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.library_edit_write_file), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = fields.writeToFile, onCheckedChange = { onChange(fields.copy(writeToFile = it)) })
        }
        Text(
            stringResource(R.string.library_edit_write_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Field(
    value: String,
    label: Int,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}
