package dev.geode.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Help
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import dev.geode.design.glass.GlassCard
import dev.geode.design.glass.GlassCrossfade
import dev.geode.design.glass.GlassListRow
import dev.geode.design.glass.GlassLoading
import dev.geode.design.glass.GlassSpacing
import dev.geode.design.glass.GlassTopBar
import dev.geode.feature.settings.api.SettingsCommand
import dev.geode.feature.settings.api.SettingsValues

/**
 * Settings, the whole screen (ADR-0003), laid out the classic way (#355): a top bar over the list of
 * sections, and a tap on one opens it as a page of its own, whose top bar leads back to the list.
 * Stateless: it shows [model] and reports everything the person does to [onAction]; no store, ViewModel
 * or IO. [helpExtra] is drawn at the end of the Help section, for what another feature adds there
 * (Diagnostics, #17).
 */
@Composable
fun SettingsScreen(
    model: SettingsScreenModel,
    onAction: (SettingsAction) -> Unit,
    modifier: Modifier = Modifier,
    helpExtra: @Composable () -> Unit = {},
) {
    val open = model.section
    val back: (@Composable () -> Unit)? =
        if (open != null) {
            {
                IconButton(onClick = { onAction(SettingsAction.Back) }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                }
            }
        } else {
            null
        }
    Surface(modifier.fillMaxSize()) {
        Column {
            GlassTopBar(open?.let { sectionName(it) } ?: stringResource(R.string.settings_title), navigation = back)
            // The list and a section's page cross-fade, as places do.
            GlassCrossfade(open, label = "settingsSection") { shown ->
                if (shown == null) {
                    SectionList(onAction)
                } else {
                    SectionPage(shown, model, onAction, helpExtra)
                }
            }
        }
    }
}

/** Every section, in its order, as one list on glass: its name, what is in it, and its icon. */
@Composable
private fun SectionList(onAction: (SettingsAction) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = SettingsDimens.gutter, vertical = SettingsDimens.groupGap),
    ) {
        item {
            GlassCard(
                Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = GlassSpacing.Small),
                verticalArrangement = Arrangement.Top,
            ) {
                SettingsSection.entries.forEach { section ->
                    GlassListRow(
                        headline = sectionName(section),
                        supporting = sectionSummary(section),
                        leading = { Icon(sectionIcon(section), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
                        onClick = { onAction(SettingsAction.Show(section)) },
                    )
                }
            }
        }
    }
}

/** One section's settings, or a spinner while the stored ones are being read. */
@Composable
private fun SectionPage(
    section: SettingsSection,
    model: SettingsScreenModel,
    onAction: (SettingsAction) -> Unit,
    helpExtra: @Composable () -> Unit,
) {
    val values = model.values
    if (values == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            GlassLoading(label = stringResource(R.string.settings_loading))
        }
    } else {
        SectionColumn { sectionItems(section, model, values, onAction, helpExtra) }
    }
}

private fun LazyListScope.sectionItems(
    section: SettingsSection,
    model: SettingsScreenModel,
    values: SettingsValues,
    onAction: (SettingsAction) -> Unit,
    helpExtra: @Composable () -> Unit,
) {
    val change: (SettingsCommand) -> Unit = { onAction(SettingsAction.Change(it)) }
    when (section) {
        SettingsSection.THEME -> themeSection(values.look, model.fontColors, change)
        SettingsSection.APPLICATION -> applicationSection(values, model.startLooks, onAction)
        SettingsSection.AUDIO -> audioSection(values, change)
        SettingsSection.EXPORT -> exportSection(values.export, change)
        SettingsSection.DEFAULT_PATHS -> defaultPathsSection(values.folders, onAction)
        SettingsSection.BATTERY -> batterySection(values.performance, change)
        SettingsSection.HELP -> {
            helpSection(values.onboarding, onAction)
            item { helpExtra() }
        }
        SettingsSection.ABOUT -> aboutSection(model.appVersion)
    }
}

@Composable
private fun SectionColumn(content: LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = SettingsDimens.gutter, vertical = SettingsDimens.groupGap),
        verticalArrangement = Arrangement.spacedBy(SettingsDimens.groupGap),
        content = content,
    )
}

@Composable
private fun sectionName(section: SettingsSection): String =
    stringResource(
        when (section) {
            SettingsSection.THEME -> R.string.settings_section_theme
            SettingsSection.APPLICATION -> R.string.settings_section_application
            SettingsSection.AUDIO -> R.string.settings_section_audio
            SettingsSection.EXPORT -> R.string.settings_section_export
            SettingsSection.DEFAULT_PATHS -> R.string.settings_section_default_paths
            SettingsSection.BATTERY -> R.string.settings_section_battery
            SettingsSection.HELP -> R.string.settings_section_help
            SettingsSection.ABOUT -> R.string.settings_section_about
        },
    )

/** The line under a section's name in the list: what the person will find in it. */
@Composable
private fun sectionSummary(section: SettingsSection): String =
    stringResource(
        when (section) {
            SettingsSection.THEME -> R.string.settings_summary_theme
            SettingsSection.APPLICATION -> R.string.settings_summary_application
            SettingsSection.AUDIO -> R.string.settings_summary_audio
            SettingsSection.EXPORT -> R.string.settings_summary_export
            SettingsSection.DEFAULT_PATHS -> R.string.settings_summary_default_paths
            SettingsSection.BATTERY -> R.string.settings_summary_battery
            SettingsSection.HELP -> R.string.settings_summary_help
            SettingsSection.ABOUT -> R.string.settings_summary_about
        },
    )

private fun sectionIcon(section: SettingsSection): ImageVector =
    when (section) {
        SettingsSection.THEME -> Icons.Rounded.Palette
        SettingsSection.APPLICATION -> Icons.Rounded.Tune
        SettingsSection.AUDIO -> Icons.Rounded.GraphicEq
        SettingsSection.EXPORT -> Icons.Rounded.Movie
        SettingsSection.DEFAULT_PATHS -> Icons.Rounded.Folder
        SettingsSection.BATTERY -> Icons.Rounded.BatterySaver
        SettingsSection.HELP -> Icons.AutoMirrored.Rounded.Help
        SettingsSection.ABOUT -> Icons.Rounded.Info
    }
