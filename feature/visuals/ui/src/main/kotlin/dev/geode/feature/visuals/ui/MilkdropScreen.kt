package dev.geode.feature.visuals.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import dev.geode.design.glass.GlassSize
import dev.geode.design.glass.GlassSpacing
import dev.geode.feature.visuals.api.ImportReport
import dev.geode.feature.visuals.api.MilkdropImportProblem
import dev.geode.feature.visuals.api.MilkdropKind
import dev.geode.feature.visuals.api.MilkdropPackType
import dev.geode.feature.visuals.api.MilkdropPreset
import dev.geode.feature.visuals.api.PackLimit
import dev.geode.feature.visuals.api.PackProblem
import dev.geode.feature.visuals.api.PresetPlayback

/**
 * The MilkDrop presets and textures (#145): the curated presets Geode ships and the person's own,
 * listed together, the textures presets can sample, and the way to import more of either through the
 * system picker. How the last import went shows at the top, every file that was left out named with
 * the reason, until the person dismisses it. The person's own presets and textures can be deleted,
 * after a confirmation (#360). App UI over the live Visual, like the Visuals tab, so its panels are
 * frosted glass. Stateless (ADR-0003): it draws [model] and sends every tap to [onAction]; only which
 * file the confirmation asks about is its own.
 */
@Composable
fun MilkdropScreen(
    model: MilkdropModel,
    onAction: (MilkdropAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmKind by rememberSaveable { mutableStateOf<MilkdropKind?>(null) }
    var confirmFile by rememberSaveable { mutableStateOf<String?>(null) }
    val askToDelete = { kind: MilkdropKind, file: String ->
        confirmKind = kind
        confirmFile = file
    }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(GlassSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(GlassSpacing.Medium),
    ) {
        item { Header(model.canImport, onAction) }
        if (model.importing) item { ShelfHint(R.string.milkdrop_importing) }
        model.report?.let { report -> item { Report(report, onAction) } }
        if (model.loading) {
            item { ShelfHint(R.string.milkdrop_loading) }
        } else {
            item { ShelfTitle(R.string.milkdrop_presets) }
            items(model.presets, key = { "preset-${it.file}" }) { preset ->
                MilkdropPresetRow(
                    preset,
                    playing = preset.file == model.playing,
                    onPlay = { onAction(MilkdropAction.Play(preset.file)) },
                    onReload = if (model.canReload && preset.file == model.playing) ({ onAction(MilkdropAction.Reload) }) else null,
                    onDelete = if (preset.builtIn) null else ({ askToDelete(MilkdropKind.PRESETS, preset.file) }),
                    canDelete = model.canDelete,
                )
            }
            item { ShelfTitle(R.string.milkdrop_textures) }
            if (model.textures.isEmpty()) item { ShelfHint(R.string.milkdrop_no_textures) }
            items(model.textures, key = { "texture-$it" }) { texture ->
                TextureRow(texture, model.canDelete) { askToDelete(MilkdropKind.TEXTURES, texture) }
            }
        }
    }
    val kind = confirmKind
    val file = confirmFile
    if (kind != null && file != null) {
        val close = {
            confirmKind = null
            confirmFile = null
        }
        ConfirmDelete(kind, file, onDismiss = close) {
            close()
            onAction(MilkdropAction.Delete(kind, file))
        }
    }
}

/** Asks before the file [file] of [kind] is deleted for good. */
@Composable
private fun ConfirmDelete(
    kind: MilkdropKind,
    file: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val shown = if (kind == MilkdropKind.PRESETS) file.removeSuffix(".milk") else file
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.milkdrop_delete_title, shown)) },
        text = {
            val body = if (kind == MilkdropKind.PRESETS) R.string.milkdrop_delete_preset_body else R.string.milkdrop_delete_texture_body
            Text(stringResource(body))
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.milkdrop_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.visuals_cancel)) } },
    )
}

/** A bin button for one of the person's own files, named for screen readers by [name]. */
@Composable
private fun DeleteButton(
    name: String,
    enabled: Boolean,
    onDelete: () -> Unit,
) {
    IconButton(onClick = onDelete, enabled = enabled) {
        Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.milkdrop_delete_named, name))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(
    canImport: Boolean,
    onAction: (MilkdropAction) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().glass().padding(GlassSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(GlassSpacing.Medium),
    ) {
        Text(stringResource(R.string.milkdrop_title), style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(R.string.milkdrop_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(GlassSpacing.Small),
            verticalArrangement = Arrangement.spacedBy(GlassSpacing.Small),
        ) {
            ImportButton(R.string.milkdrop_import_presets, Icons.Rounded.LibraryAdd, canImport) {
                onAction(MilkdropAction.Pick(MilkdropKind.PRESETS))
            }
            ImportButton(R.string.milkdrop_import_textures, Icons.Rounded.Image, canImport) {
                onAction(MilkdropAction.Pick(MilkdropKind.TEXTURES))
            }
            ImportButton(R.string.milkdrop_import_folder, Icons.Rounded.FolderOpen, canImport) {
                onAction(MilkdropAction.PickPack(MilkdropPackType.FOLDER))
            }
            ImportButton(R.string.milkdrop_import_zip, Icons.Rounded.Archive, canImport) {
                onAction(MilkdropAction.PickPack(MilkdropPackType.ZIP))
            }
        }
    }
}

@Composable
private fun ImportButton(
    @StringRes label: Int,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilledTonalButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = null)
        Text(stringResource(label), Modifier.padding(start = GlassSpacing.Small))
    }
}

@Composable
private fun Report(
    report: ImportReport,
    onAction: (MilkdropAction) -> Unit,
) {
    Column(Modifier.fillMaxWidth().glass().padding(GlassSpacing.Large), verticalArrangement = Arrangement.spacedBy(GlassSpacing.Small)) {
        Text(headline(report), style = MaterialTheme.typography.titleMedium)
        // A pack (#399): the textures that came with its presets, what it held besides, and where it stopped.
        val brought = report.textures.size
        if (brought > 0 &&
            report.imported.isNotEmpty()
        ) {
            ReportNote(pluralStringResource(R.plurals.milkdrop_pack_textures, brought, brought))
        }
        report.stopped?.let { ReportNote(stringResource(it.label)) }
        if (report.broken.isNotEmpty()) ReportNote(pluralStringResource(R.plurals.milkdrop_broken, report.broken.size, report.broken.size))
        if (report.skipped.isNotEmpty()) {
            ReportNote(
                pluralStringResource(R.plurals.milkdrop_skipped, report.skipped.size, report.skipped.size),
            )
        }
        // A pack can bring thousands; the first of each kind are named, and the rest counted.
        val played = report.imported.mapNotNull { name -> report.playback[name]?.let { name to it } }
        Capped(played) { (name, playback) ->
            ReportLine(name, playback.label(), if (playback == PresetPlayback.Full) MaterialTheme.colorScheme.primary else null)
        }
        Capped(report.broken) { ReportLine(it.name, stringResource(it.problem.label), null) }
        Capped(report.skipped) { ReportLine(it.path, stringResource(it.problem.label), MaterialTheme.colorScheme.onSurfaceVariant) }
        TextButton(onClick = { onAction(MilkdropAction.DismissReport) }, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.milkdrop_dismiss))
        }
    }
}

/** The report's first line: how many came in, presets or textures, or that nothing did. */
@Composable
private fun headline(report: ImportReport): String {
    val presets = report.imported.size
    val textures = report.textures.size
    return when {
        presets == 0 && textures == 0 -> stringResource(R.string.milkdrop_nothing_imported)
        report.kind == MilkdropKind.TEXTURES -> pluralStringResource(R.plurals.milkdrop_imported_textures, presets, presets)
        presets == 0 -> pluralStringResource(R.plurals.milkdrop_imported_textures, textures, textures)
        else -> pluralStringResource(R.plurals.milkdrop_imported_presets, presets, presets)
    }
}

@Composable
private fun ReportNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The first [REPORT_LINES] of [items], each as [line], and how many more there are. */
@Composable
private fun <T> Capped(
    items: List<T>,
    line: @Composable (T) -> Unit,
) {
    items.take(REPORT_LINES).forEach { line(it) }
    val more = items.size - REPORT_LINES
    if (more > 0) ReportNote(pluralStringResource(R.plurals.milkdrop_and_more, more, more))
}

/** The most files of one kind a report names; a pack can bring thousands. */
private const val REPORT_LINES = 40

/** One file of the report: its [name], and what came of it in [color] (the error colour when null). */
@Composable
private fun ReportLine(
    name: String,
    outcome: String,
    color: Color?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            outcome,
            Modifier.padding(start = GlassSpacing.Medium).weight(1f, fill = false),
            style = MaterialTheme.typography.bodySmall,
            color = color ?: MaterialTheme.colorScheme.error,
        )
    }
}

/** How a preset will play, said on screen (#305). */
@Composable
private fun PresetPlayback.label(): String =
    when (this) {
        PresetPlayback.Full -> stringResource(R.string.milkdrop_plays)
        is PresetPlayback.WithoutShader -> stringResource(R.string.milkdrop_plays_without_shader, unsupported.joinToString())
        is PresetPlayback.MissingTextures -> stringResource(R.string.milkdrop_missing_textures, textures.joinToString())
    }

@Composable
private fun ShelfTitle(
    @StringRes text: Int,
) {
    Text(
        stringResource(text),
        modifier = Modifier.padding(top = GlassSpacing.Small, start = GlassSpacing.Small),
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
private fun ShelfHint(
    @StringRes text: Int,
) {
    Text(
        stringResource(text),
        modifier = Modifier.fillMaxWidth().glass().padding(GlassSpacing.Large),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * One preset; a tap shows it in the Visual, as its own Style. The one playing has [onReload] (#382), the
 * person's own [onDelete] (#360).
 */
@Composable
private fun MilkdropPresetRow(
    preset: MilkdropPreset,
    playing: Boolean,
    onPlay: () -> Unit,
    onReload: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    canDelete: Boolean,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = GlassSize.TapTarget)
            .glass()
            .clickable(onClick = onPlay)
            .padding(start = GlassSpacing.Large, end = GlassSpacing.Small, top = GlassSpacing.Small, bottom = GlassSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PresetLines(preset, playing, Modifier.weight(1f))
        if (onReload != null) {
            IconButton(onClick = onReload) {
                Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.milkdrop_reload, preset.name))
            }
        }
        if (onDelete != null) DeleteButton(preset.name, canDelete, onDelete)
    }
}

@Composable
private fun PresetLines(
    preset: MilkdropPreset,
    playing: Boolean,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text(preset.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val byline = preset.author?.let { stringResource(R.string.milkdrop_by, it) } ?: stringResource(R.string.milkdrop_yours)
        Text(
            if (playing) stringResource(R.string.milkdrop_playing) else byline,
            style = MaterialTheme.typography.bodySmall,
            color = if (preset.builtIn && !playing) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TextureRow(
    name: String,
    canDelete: Boolean,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = GlassSize.TapTarget)
            .glass()
            .padding(start = GlassSpacing.Large, end = GlassSpacing.Small, top = GlassSpacing.Small, bottom = GlassSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Image, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(
            name,
            Modifier.padding(start = GlassSpacing.Medium).weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        DeleteButton(name, canDelete, onDelete)
    }
}

/** Why a file in a pack was passed over, said on screen (#399). */
@get:StringRes
internal val PackProblem.label: Int
    get() =
        when (this) {
            PackProblem.NOT_MILKDROP -> R.string.milkdrop_skipped_not_milkdrop
            PackProblem.UNSAFE_PATH -> R.string.milkdrop_skipped_unsafe
        }

/** The limit a pack ran into, said on screen (#399). */
@get:StringRes
internal val PackLimit.label: Int
    get() =
        when (this) {
            PackLimit.ENTRIES -> R.string.milkdrop_pack_too_many
            PackLimit.SIZE -> R.string.milkdrop_pack_too_large
        }

/** How a problem is said on screen. */
@get:StringRes
internal val MilkdropImportProblem.label: Int
    get() =
        when (this) {
            MilkdropImportProblem.NOT_A_PRESET -> R.string.milkdrop_problem_not_a_preset
            MilkdropImportProblem.NOT_AN_IMAGE -> R.string.milkdrop_problem_not_an_image
            MilkdropImportProblem.WRONG_TYPE -> R.string.milkdrop_problem_wrong_type
            MilkdropImportProblem.TOO_LARGE -> R.string.milkdrop_problem_too_large
            MilkdropImportProblem.EMPTY -> R.string.milkdrop_problem_empty
            MilkdropImportProblem.UNREADABLE -> R.string.milkdrop_problem_unreadable
            MilkdropImportProblem.ALREADY_THERE -> R.string.milkdrop_problem_already_there
            MilkdropImportProblem.NOT_SAVED -> R.string.milkdrop_problem_not_saved
        }
