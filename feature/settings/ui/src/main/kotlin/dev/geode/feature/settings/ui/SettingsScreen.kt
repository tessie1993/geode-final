package dev.geode.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.geode.feature.settings.api.SettingsCommand
import dev.geode.feature.settings.api.SettingsValues

/**
 * Settings, the whole screen: the sections and the open section's settings (ADR-0003). Stateless: it shows
 * [model] and reports everything the person does to [onAction]; no store, ViewModel or IO.
 */
@Composable
fun SettingsScreen(
    model: SettingsScreenModel,
    onAction: (SettingsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxSize()) {
        Column {
            Box(Modifier.padding(horizontal = SettingsDimens.gutter, vertical = SettingsDimens.rowGap)) {
                ChoiceRow(
                    label = null,
                    options = SettingsSection.entries,
                    selected = model.section,
                    name = { sectionName(it) },
                    onSelect = { onAction(SettingsAction.Show(it)) },
                )
            }
            val values = model.values
            if (values == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.settings_loading), style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                SectionColumn { section(model, values, onAction) }
            }
        }
    }
}

private fun LazyListScope.section(
    model: SettingsScreenModel,
    values: SettingsValues,
    onAction: (SettingsAction) -> Unit,
) {
    val change: (SettingsCommand) -> Unit = { onAction(SettingsAction.Change(it)) }
    when (model.section) {
        SettingsSection.THEME -> themeSection(values.look, model.fontColors, change)
        SettingsSection.APPLICATION -> applicationSection(values, onAction)
        SettingsSection.AUDIO -> audioSection(values, change)
        SettingsSection.EXPORT -> exportSection(values.export, change)
        SettingsSection.DEFAULT_PATHS -> defaultPathsSection(values.folders, onAction)
        SettingsSection.BATTERY -> batterySection(values.performance, change)
        SettingsSection.HELP -> helpSection(values.onboarding, onAction)
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
