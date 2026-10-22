package dev.geode.feature.settings.ui

import androidx.compose.runtime.Immutable
import dev.geode.feature.settings.api.Argb
import dev.geode.feature.settings.api.FolderUri
import dev.geode.feature.settings.api.SettingsCommand
import dev.geode.feature.settings.api.SettingsState
import dev.geode.feature.settings.api.SettingsValues

/**
 * The Settings sections, in the order they are shown (#101). CPU/GPU goes between [DEFAULT_PATHS]
 * and [HELP] once #28 gives it its settings; until then it would be empty, so it is not shown.
 */
enum class SettingsSection {
    THEME,
    APPLICATION,
    AUDIO,
    EXPORT,
    DEFAULT_PATHS,
    HELP,
    ABOUT,
}

/** A text colour the person can pick; a null [color] is the theme's own. */
@Immutable
data class FontColorChoice(
    val color: Argb?,
    val name: String,
)

/**
 * Everything [SettingsScreen] shows. [values] is null while the stored settings are being read.
 * Which [section] is open is App-UI-only state, kept by the route.
 */
@Immutable
data class SettingsScreenModel(
    val section: SettingsSection,
    val values: SettingsValues?,
    val fontColors: List<FontColorChoice>,
    val appVersion: String,
) {
    companion object {
        /** The model for Settings' [state], with the rest supplied by the route. */
        fun of(
            state: SettingsState,
            section: SettingsSection,
            fontColors: List<FontColorChoice>,
            appVersion: String,
        ): SettingsScreenModel =
            SettingsScreenModel(
                section = section,
                values = (state as? SettingsState.Ready)?.values,
                fontColors = fontColors,
                appVersion = appVersion,
            )
    }
}

/** What the person did on [SettingsScreen]. The route sends [Change]s to Settings and handles the rest. */
sealed interface SettingsAction {
    data class Change(
        val command: SettingsCommand,
    ) : SettingsAction

    data class Show(
        val section: SettingsSection,
    ) : SettingsAction

    /** Pick a folder to mirror presets to; the route opens the system's folder picker. */
    data object PickPresetMirror : SettingsAction

    /** Pick the folder presets live in; the route opens the system's folder picker. */
    data object PickPresetFolder : SettingsAction

    /** Pick a music folder to add; the route opens the system's folder picker. */
    data object PickMusicFolder : SettingsAction

    data class RemoveMusicFolder(
        val folder: FolderUri,
    ) : SettingsAction

    data object StartTutorial : SettingsAction

    data object SetLiveWallpaper : SettingsAction
}
