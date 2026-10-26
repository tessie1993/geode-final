package dev.geode.ui.shell

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import dev.geode.design.glass.GlassTheme
import dev.geode.design.glass.rememberGlassTier
import dev.geode.design.theme.CornerShapes
import dev.geode.design.theme.ProvideReducedMotion
import dev.geode.feature.settings.api.CornerStyle
import dev.geode.feature.settings.api.LookSettings
import dev.geode.feature.settings.api.ThemeMode

/**
 * The App UI in frosted glass (#324): dark or light as [look]'s theme says, or as the phone is; matte
 * when the person turns on reduce transparency, and calmer under battery saver, heat or Visuals off
 * ([visualsOff]). The text size, accent, background dim, bar opacity, corners and [reducedMotion] from
 * Settings apply on top.
 */
@Composable
fun GeodeTheme(
    look: LookSettings,
    reducedMotion: Boolean,
    visualsOff: Boolean,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scaled = Density(density.density, density.fontScale * look.textScale)
    val dark =
        when (look.themeMode) {
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
        }
    GlassTheme(
        dark = dark,
        tier = rememberGlassTier(reduceTransparency = look.reduceTransparency, batterySaving = visualsOff),
        shapes = look.cornerStyle.shapes(),
        accentIntensity = look.accentIntensity,
        backgroundDim = look.backgroundDim,
        barOpacity = look.barOpacity,
        fontColor = look.fontColor?.let { Color(it.value) },
    ) {
        CompositionLocalProvider(LocalDensity provides scaled) {
            ProvideReducedMotion(appPreference = reducedMotion, content = content)
        }
    }
}

private fun CornerStyle.shapes(): Shapes =
    when (this) {
        CornerStyle.SHARP -> CornerShapes.Sharp
        CornerStyle.ROUNDED -> CornerShapes.Rounded
        CornerStyle.PILL -> CornerShapes.Pill
    }
