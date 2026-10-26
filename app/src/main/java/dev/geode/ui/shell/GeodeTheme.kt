package dev.geode.ui.shell

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import dev.geode.design.glass.GlassTheme
import dev.geode.design.glass.rememberGlassTier
import dev.geode.design.theme.ProvideReducedMotion
import dev.geode.feature.settings.api.ThemeMode
import dev.geode.ui.GuiPrefs
import dev.geode.ui.shapes

/**
 * The App UI in frosted glass (#324): dark or light as [themeMode] says, or as the phone is; matte
 * when the person turns on [reduceTransparency], and calmer under battery saver, heat or Visuals off
 * ([visualsOff]). The text size, accent, background dim, bar opacity, corners and reduced motion from
 * Settings apply on top.
 */
@Composable
fun GeodeTheme(
    themeMode: ThemeMode,
    reduceTransparency: Boolean,
    visualsOff: Boolean,
    gui: GuiPrefs,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scaled = Density(density.density, density.fontScale * gui.textScale)
    val dark =
        when (themeMode) {
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
        }
    GlassTheme(
        dark = dark,
        tier = rememberGlassTier(reduceTransparency = reduceTransparency, batterySaving = visualsOff),
        shapes = gui.cornerStyle.shapes(),
        accentIntensity = gui.accentIntensity,
        backgroundDim = gui.backgroundDim,
        barOpacity = gui.barOpacity,
        fontColor = gui.fontColorOverride?.let { Color(it) },
    ) {
        CompositionLocalProvider(LocalDensity provides scaled) {
            ProvideReducedMotion(appPreference = gui.reducedMotion, content = content)
        }
    }
}
