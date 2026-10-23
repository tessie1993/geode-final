package dev.geode.feature.visuals.ui

import androidx.annotation.StringRes
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
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import dev.geode.design.forest.ForestShapes
import dev.geode.design.forest.ForestSpacing
import dev.geode.feature.visuals.api.ImportReport
import dev.geode.feature.visuals.api.MilkdropImportProblem
import dev.geode.feature.visuals.api.MilkdropKind
import dev.geode.feature.visuals.api.MilkdropPreset

/**
 * The MilkDrop presets and textures (#145): the curated presets Geode ships and the person's own,
 * listed together, the textures presets can sample, and the way to import more of either through the
 * system picker. How the last import went shows at the top, every file that was left out named with
 * the reason, until the person dismisses it. App UI over the live Visual, like the Visuals tab, so its
 * panels are frosted glass. Stateless (ADR-0003): it draws [model] and sends every tap to [onAction].
 */
@Composable
fun MilkdropScreen(
    model: MilkdropModel,
    onAction: (MilkdropAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(ForestSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(ForestSpacing.Medium),
    ) {
        item { Header(model.canImport, onAction) }
        if (model.importing) item { ShelfHint(R.string.milkdrop_importing) }
        model.report?.let { report -> item { Report(report, onAction) } }
        if (model.loading) {
            item { ShelfHint(R.string.milkdrop_loading) }
        } else {
            item { ShelfTitle(R.string.milkdrop_presets) }
            items(model.presets, key = { "preset-${it.file}" }) { MilkdropPresetRow(it) }
            item { ShelfTitle(R.string.milkdrop_textures) }
            if (model.textures.isEmpty()) item { ShelfHint(R.string.milkdrop_no_textures) }
            items(model.textures, key = { "texture-$it" }) { TextureRow(it) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(
    canImport: Boolean,
    onAction: (MilkdropAction) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().glass().padding(ForestSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(ForestSpacing.Medium),
    ) {
        Text(stringResource(R.string.milkdrop_title), style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(R.string.milkdrop_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ForestSpacing.Small),
            verticalArrangement = Arrangement.spacedBy(ForestSpacing.Small),
        ) {
            ImportButton(R.string.milkdrop_import_presets, Icons.Filled.LibraryAdd, canImport) {
                onAction(MilkdropAction.Pick(MilkdropKind.PRESETS))
            }
            ImportButton(R.string.milkdrop_import_textures, Icons.Filled.Image, canImport) {
                onAction(MilkdropAction.Pick(MilkdropKind.TEXTURES))
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
    FilledTonalButton(onClick = onClick, enabled = enabled, shape = ForestShapes.Button) {
        Icon(icon, contentDescription = null)
        Text(stringResource(label), Modifier.padding(start = ForestSpacing.Small))
    }
}

@Composable
private fun Report(
    report: ImportReport,
    onAction: (MilkdropAction) -> Unit,
) {
    Column(Modifier.fillMaxWidth().glass().padding(ForestSpacing.Large), verticalArrangement = Arrangement.spacedBy(ForestSpacing.Small)) {
        val count = report.imported.size
        val headline =
            when {
                count == 0 -> stringResource(R.string.milkdrop_nothing_imported)
                report.kind == MilkdropKind.PRESETS -> pluralStringResource(R.plurals.milkdrop_imported_presets, count, count)
                else -> pluralStringResource(R.plurals.milkdrop_imported_textures, count, count)
            }
        Text(headline, style = MaterialTheme.typography.titleMedium)
        if (report.broken.isNotEmpty()) {
            Text(
                pluralStringResource(R.plurals.milkdrop_broken, report.broken.size, report.broken.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (broken in report.broken) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    broken.name,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(broken.problem.label),
                    Modifier.padding(start = ForestSpacing.Medium),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        TextButton(onClick = { onAction(MilkdropAction.DismissReport) }, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.milkdrop_dismiss))
        }
    }
}

@Composable
private fun ShelfTitle(
    @StringRes text: Int,
) {
    Text(
        stringResource(text),
        modifier = Modifier.padding(top = ForestSpacing.Small, start = ForestSpacing.Small),
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
private fun ShelfHint(
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
private fun MilkdropPresetRow(preset: MilkdropPreset) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ForestSpacing.TapTarget)
            .glass()
            .padding(horizontal = ForestSpacing.Large, vertical = ForestSpacing.Small),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(preset.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            preset.author?.let { stringResource(R.string.milkdrop_by, it) } ?: stringResource(R.string.milkdrop_yours),
            style = MaterialTheme.typography.bodySmall,
            color = if (preset.builtIn) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TextureRow(name: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ForestSpacing.TapTarget)
            .glass()
            .padding(horizontal = ForestSpacing.Large, vertical = ForestSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Image, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(
            name,
            Modifier.padding(start = ForestSpacing.Medium),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
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
