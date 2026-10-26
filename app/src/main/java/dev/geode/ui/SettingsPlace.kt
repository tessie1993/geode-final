package dev.geode.ui

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.geode.BuildConfig
import dev.geode.R
import dev.geode.design.CrystalOverline
import dev.geode.design.GlowTitle
import dev.geode.feature.diagnostics.api.Diagnostics
import dev.geode.feature.diagnostics.ui.DeveloperOverlay
import dev.geode.feature.diagnostics.ui.DiagnosticsScreen
import dev.geode.feature.diagnostics.ui.overlayModel
import dev.geode.feature.diagnostics.ui.toSectionModel
import dev.geode.feature.settings.api.Argb
import dev.geode.feature.settings.api.StartLook
import dev.geode.feature.settings.ui.SettingsRoute
import dev.geode.feature.settings.ui.StartLookChoice
import dev.geode.feature.visuals.api.VisualsState
import dev.geode.ui.shell.ShellViewModel
import dev.geode.wallpaper.VisualizerWallpaperService
import dev.geode.feature.settings.ui.FontColorChoice as SettingsFontColor

/**
 * Settings: the Settings feature's own place (#17), with Diagnostics' section at the end of About
 * (#62). Help replays the intro tour through [onStartTutorial].
 */
@Composable
internal fun SettingsPlace(
    shell: ShellViewModel,
    onStartTutorial: () -> Unit,
) {
    val context = LocalContext.current
    val fontColors =
        FontColorChoice.CHOICES.map { SettingsFontColor(it.argb?.let(::Argb), stringResource(it.labelRes)) }
    val visuals by shell.visuals.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            CrystalOverline(stringResource(R.string.app_name))
            GlowTitle(stringResource(R.string.nav_settings))
        }
        SettingsRoute(
            shell.settings,
            fontColors = fontColors,
            appVersion = BuildConfig.VERSION_NAME,
            onStartTutorial = onStartTutorial,
            onSetLiveWallpaper = { context.openLiveWallpaperPicker() },
            startLooks = startLookChoices(visuals),
            helpExtra = { DiagnosticsSection(shell.diagnostics) },
        )
    }
}

/**
 * The looks Settings offers to start on (#383), besides the last one: the Style on screen now, the
 * Favourites, then the saved presets. Styles this version does not draw are left out.
 */
internal fun startLookChoices(visuals: VisualsState): List<StartLookChoice> {
    val names = visuals.styles.associate { it.id to it.name }
    val styles = (listOfNotNull(visuals.current) + visuals.library?.favourites.orEmpty()).distinct()
    val styleChoices = styles.mapNotNull { id -> names[id]?.let { StartLookChoice(StartLook.Style(id), it) } }
    val presetChoices = visuals.presets?.saved.orEmpty().map { StartLookChoice(StartLook.Preset(it.id), it.id.value) }
    return styleChoices + presetChoices
}

@Composable
private fun DiagnosticsSection(diagnostics: Diagnostics) {
    val state by diagnostics.state.collectAsStateWithLifecycle()
    DiagnosticsScreen(state.toSectionModel(BuildConfig.VERSION_NAME), onAction = diagnostics::send)
}

/** The developer overlay, over the App UI only, while Diagnostics has it on (#62). */
@Composable
internal fun DeveloperOverlayHost(
    diagnostics: Diagnostics,
    modifier: Modifier = Modifier,
) {
    val state by diagnostics.state.collectAsStateWithLifecycle()
    state.overlayModel()?.let { DeveloperOverlay(it, modifier) }
}

/** The system's live wallpaper picker, opened on Geode's wallpaper, or the plain wallpaper picker. */
private fun Context.openLiveWallpaperPicker() {
    val direct =
        Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(this, VisualizerWallpaperService::class.java))
    if (runCatching { startActivity(direct) }.isFailure) {
        runCatching { startActivity(Intent(Intent.ACTION_SET_WALLPAPER)) }
    }
}
