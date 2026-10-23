package dev.geode.feature.settings.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.geode.feature.settings.api.Argb
import dev.geode.feature.settings.api.EqualizerPreset
import dev.geode.feature.settings.api.EqualizerSettings
import dev.geode.feature.settings.api.FolderSettings
import dev.geode.feature.settings.api.FolderUri
import dev.geode.feature.settings.api.SettingsValues

// Previews of every section (ADR-0003). The sample values are made
// up for the previews; the real ones come from Settings.

private const val PREVIEW_VERSION = "1.8.0"
private const val PREVIEW_WIDTH_DP = 411
private const val PREVIEW_HEIGHT_DP = 891

internal val previewFontColors =
    listOf(
        FontColorChoice(null, "Theme"),
        FontColorChoice(Argb(0xFFFFFFFF.toInt()), "White"),
        FontColorChoice(Argb(0xFFD6B15A.toInt()), "Pyrite"),
    )

internal val previewValues =
    SettingsValues(
        equalizer = EqualizerSettings(enabled = true, bands = EqualizerPreset.ROCK.levels, preset = EqualizerPreset.ROCK),
        folders =
            FolderSettings(
                presetMirror = FolderUri("content://tree/primary:Backup"),
                presetFolder = FolderUri("content://tree/primary:Presets"),
                musicFolders = setOf(FolderUri("content://tree/primary:Music")),
            ),
    )

internal fun previewModel(
    section: SettingsSection,
    values: SettingsValues? = previewValues,
): SettingsScreenModel = SettingsScreenModel(section, values, previewFontColors, PREVIEW_VERSION)

@Composable
internal fun SettingsPreview(
    section: SettingsSection,
    values: SettingsValues? = previewValues,
) {
    MaterialTheme { SettingsScreen(previewModel(section, values), onAction = {}) }
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP)
@Composable
internal fun SettingsThemePreview() = SettingsPreview(SettingsSection.THEME)

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP)
@Composable
internal fun SettingsAudioPreview() = SettingsPreview(SettingsSection.AUDIO)

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP)
@Composable
internal fun SettingsExportPreview() = SettingsPreview(SettingsSection.EXPORT)

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP)
@Composable
internal fun SettingsDefaultPathsPreview() = SettingsPreview(SettingsSection.DEFAULT_PATHS)

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP)
@Composable
internal fun SettingsApplicationPreview() = SettingsPreview(SettingsSection.APPLICATION)

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP)
@Composable
internal fun SettingsHelpPreview() = SettingsPreview(SettingsSection.HELP)

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP)
@Composable
internal fun SettingsAboutPreview() = SettingsPreview(SettingsSection.ABOUT)

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP)
@Composable
internal fun SettingsLoadingPreview() = SettingsPreview(SettingsSection.THEME, values = null)
