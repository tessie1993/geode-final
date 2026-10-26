package dev.geode.feature.settings.ui

import androidx.compose.runtime.Immutable
import dev.geode.feature.settings.api.Argb
import dev.geode.feature.settings.api.FolderUri
import dev.geode.feature.settings.api.SettingsCommand
import dev.geode.feature.settings.api.SettingsState
import dev.geode.feature.settings.api.SettingsValues
import dev.geode.feature.settings.api.StartLook

/** The Settings sections, in the order they are shown (#101). */
enum class SettingsSection {
    THEME,
    APPLICATION,
    AUDIO,
    EXPORT,
    DEFAULT_PATHS,
    BATTERY,
    HELP,
    ABOUT,
}

/** A text colour the person can pick; a null [color] is the theme's own. */
@Immutable
data class FontColorChoice(
    val color: Argb?,
    val name: String,
)

/** A look Geode can start on (#383), and the [name] it is shown by; the app lists them. */
@Immutable
data class StartLookChoice(
    val look: StartLook,
    val name: String,
)

/**
 * Everything [SettingsScreen] shows. [values] is null while the stored settings are being read.
 * Which [section] is open is App-UI-only state, kept by the route; null shows the list of sections.
 */
@Immutable
data class SettingsScreenModel(
    val section: SettingsSection?,
    val values: SettingsValues?,
    val fontColors: List<FontColorChoice>,
    val appVersion: String,
    val startLooks: List<StartLookChoice> = emptyList(),
) {
    companion object {
        /** The model for Settings' [state], with the rest supplied by the route. */
        fun of(
            state: SettingsState,
            section: SettingsSection?,
            fontColors: List<FontColorChoice>,
            appVersion: String,
            startLooks: List<StartLookChoice> = emptyList(),
        ): SettingsScreenModel =
            SettingsScreenModel(
                section = section,
                values = (state as? SettingsState.Ready)?.values,
                fontColors = fontColors,
                appVersion = appVersion,
                startLooks = startLooks,
            )
    }
}

/** What the person did on [SettingsScreen]. The route sends [Change]s to Settings and handles the rest. */
sealed interface SettingsAction {
    data class Change(
        val command: SettingsCommand,
    ) : SettingsAction

    /** Open [section] from the list. */
    data class Show(
        val section: SettingsSection,
    ) : SettingsAction

    /** Back from an open section to the list of sections. */
    data object Back : SettingsAction

    /** Pick the folder Studio saves its videos to (#21); the route opens the system's folder picker. */
    data object PickVideoFolder : SettingsAction

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
