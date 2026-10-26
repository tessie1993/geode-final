package dev.geode.feature.library.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import dev.geode.feature.library.api.RuleField
import dev.geode.feature.library.api.RuleOp
import dev.geode.feature.library.api.SmartPlaylist
import dev.geode.feature.library.api.SmartRule

/**
 * The smart playlist editor (#146) in a dialog: a new smart playlist when [playlist] is null, or the
 * one being edited. [taken] are the other smart playlists' names. What it holds while open is App UI
 * state (ADR-0003); [onSave] gets the playlist to save.
 */
@Composable
internal fun SmartPlaylistEditorDialog(
    playlist: SmartPlaylist?,
    taken: Set<String>,
    onSave: (SmartPlaylist) -> Unit,
    onDismiss: () -> Unit,
) {
    var fields by remember(playlist) { mutableStateOf(SmartPlaylistFields.of(playlist)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (playlist == null) R.string.library_smart_new else R.string.library_smart_edit)) },
        text = {
            SmartPlaylistEditor(fields, taken, onChange = { fields = it }, modifier = Modifier.verticalScroll(rememberScrollState()))
        },
        confirmButton = {
            TextButton(onClick = { onSave(fields.toPlaylist()) }, enabled = fields.canSave(taken)) {
                Text(stringResource(R.string.library_edit_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_edit_cancel)) } },
    )
}

/**
 * The editor's fields: the name, all or any, the rules and the limit. Stateless: it draws [fields] and
 * reports each change to [onChange]. [taken] are the names the playlist may not have.
 */
@Composable
fun SmartPlaylistEditor(
    fields: SmartPlaylistFields,
    taken: Set<String>,
    onChange: (SmartPlaylistFields) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(LibraryDimens.small)) {
        val nameTaken = fields.nameTaken(taken)
        OutlinedTextField(
            value = fields.name,
            onValueChange = { onChange(fields.copy(name = it)) },
            label = { Text(stringResource(R.string.library_smart_name)) },
            isError = nameTaken,
            supportingText = { if (nameTaken) Text(stringResource(R.string.library_smart_name_taken)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(LibraryDimens.small)) {
            FilterChip(
                selected = fields.matchAll,
                onClick = { onChange(fields.copy(matchAll = true)) },
                label = { Text(stringResource(R.string.library_smart_match_all)) },
            )
            FilterChip(
                selected = !fields.matchAll,
                onClick = { onChange(fields.copy(matchAll = false)) },
                label = { Text(stringResource(R.string.library_smart_match_any)) },
            )
        }
        if (fields.rules.isEmpty()) {
            Text(
                stringResource(R.string.library_smart_no_rules),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        fields.rules.forEachIndexed { index, rule ->
            HorizontalDivider()
            RuleEditor(
                rule,
                onChange = { onChange(fields.withRule(index, it)) },
                onRemove = { onChange(fields.withoutRule(index)) },
            )
        }
        TextButton(onClick = { onChange(fields.withNewRule()) }) {
            Icon(Icons.Rounded.Add, contentDescription = null)
            Text(stringResource(R.string.library_smart_add_rule))
        }
        OutlinedTextField(
            value = fields.limit,
            onValueChange = { onChange(fields.withLimit(it)) },
            label = { Text(stringResource(R.string.library_smart_limit)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** One rule: what it looks at, how it compares, and the value it compares with. */
@Composable
private fun RuleEditor(
    rule: SmartRule,
    onChange: (SmartRule) -> Unit,
    onRemove: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(LibraryDimens.tiny)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(LibraryDimens.tiny),
        ) {
            RuleField.entries.forEach { field ->
                FilterChip(
                    selected = rule.field == field,
                    onClick = { onChange(SmartPlaylistFields.withField(rule, field)) },
                    label = { Text(fieldName(field)) },
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(LibraryDimens.tiny),
        ) {
            rule.field.ops.forEach { op ->
                FilterChip(selected = rule.op == op, onClick = { onChange(rule.copy(op = op)) }, label = { Text(opName(op)) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!rule.field.isFlag) {
                OutlinedTextField(
                    value = rule.value,
                    onValueChange = { onChange(rule.copy(value = it)) },
                    label = { Text(stringResource(R.string.library_smart_value)) },
                    keyboardOptions = KeyboardOptions(keyboardType = if (rule.field.isText) KeyboardType.Text else KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Text(
                    stringResource(R.string.library_smart_flag_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.library_smart_remove_rule))
            }
        }
    }
}

@Composable
private fun fieldName(field: RuleField): String =
    stringResource(
        when (field) {
            RuleField.TITLE -> R.string.library_smart_field_title
            RuleField.ARTIST -> R.string.library_smart_field_artist
            RuleField.ALBUM -> R.string.library_smart_field_album
            RuleField.FOLDER -> R.string.library_smart_field_folder
            RuleField.DURATION_MINUTES -> R.string.library_smart_field_minutes
            RuleField.ADDED_DAYS_AGO -> R.string.library_smart_field_added_days
            RuleField.PLAY_COUNT -> R.string.library_smart_field_plays
            RuleField.FAVOURITE -> R.string.library_smart_field_favourite
        },
    )

@Composable
private fun opName(op: RuleOp): String =
    stringResource(
        when (op) {
            RuleOp.CONTAINS -> R.string.library_smart_op_contains
            RuleOp.IS -> R.string.library_smart_op_is
            RuleOp.IS_NOT -> R.string.library_smart_op_is_not
            RuleOp.AT_LEAST -> R.string.library_smart_op_at_least
            RuleOp.AT_MOST -> R.string.library_smart_op_at_most
        },
    )
