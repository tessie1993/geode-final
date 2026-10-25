package dev.geode.feature.settings.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.geode.design.element.ElementTheme
import dev.geode.feature.settings.api.AmbientMode
import dev.geode.feature.settings.api.Argb
import dev.geode.feature.settings.api.Element
import dev.geode.feature.settings.api.EqualizerPreset
import dev.geode.feature.settings.api.EqualizerSettings
import dev.geode.feature.settings.api.FolderSettings
import dev.geode.feature.settings.api.FolderUri
import dev.geode.feature.settings.api.LookSettings
import dev.geode.feature.settings.api.PerformanceSettings
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
                videoFolder = FolderUri("content://tree/primary:Movies/Geode Studio"),
                presetMirror = FolderUri("content://tree/primary:Backup"),
                presetFolder = FolderUri("content://tree/primary:Presets"),
                musicFolders = setOf(FolderUri("content://tree/primary:Music")),
            ),
    )

internal fun previewModel(
    section: SettingsSection,
    values: SettingsValues? = previewValues,
): SettingsScreenModel = SettingsScreenModel(section, values, previewFontColors, PREVIEW_VERSION)

/** Settings in the Element of [values], as the app's root draws it; Water while they load. */
@Composable
internal fun SettingsPreview(
    section: SettingsSection,
    values: SettingsValues? = previewValues,
) {
    val element = values?.look?.element ?: Element.Default
    ElementTheme(element.tokens()) { SettingsScreen(previewModel(section, values), onAction = {}) }
}

/** The Theme section with [element] chosen, drawn in it. */
@Composable
internal fun SettingsThemeElementPreview(element: Element) =
    SettingsPreview(SettingsSection.THEME, previewValues.copy(look = LookSettings(element = element)))

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP)
@Composable
internal fun SettingsThemePreview() = SettingsPreview(SettingsSection.THEME)

/** The Theme section's Home group, below the fold of [SettingsThemePreview], with Still chosen. */
@Preview(widthDp = PREVIEW_WIDTH_DP)
@Composable
internal fun SettingsThemeHomePreview() {
    MaterialTheme { HomeGroup(AmbientMode.STILL, change = {}) }
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP)
@Composable
internal fun SettingsThemeWoodPreview() = SettingsThemeElementPreview(Element.WOOD)

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP)
@Composable
internal fun SettingsThemeStonePreview() = SettingsThemeElementPreview(Element.STONE)

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP)
@Composable
internal fun SettingsThemeBioluminescencePreview() = SettingsThemeElementPreview(Element.BIOLUMINESCENCE)

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
internal fun SettingsBatteryPreview() = SettingsPreview(SettingsSection.BATTERY)

/** Battery while save mode is on: the chosen values stay, with a note that save mode overrides them. */
@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP)
@Composable
internal fun SettingsBatterySaveModePreview() =
    SettingsPreview(SettingsSection.BATTERY, previewValues.copy(performance = PerformanceSettings(saveMode = true)))

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP)
@Composable
internal fun SettingsHelpPreview() = SettingsPreview(SettingsSection.HELP)

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP)
@Composable
internal fun SettingsAboutPreview() = SettingsPreview(SettingsSection.ABOUT)

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP)
@Composable
internal fun SettingsLoadingPreview() = SettingsPreview(SettingsSection.THEME, values = null)
