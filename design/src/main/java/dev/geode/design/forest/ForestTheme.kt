package dev.geode.design.forest

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/** The colours of the forest theme in use, for what Material's colour scheme has no role for. */
val LocalForestColors = staticCompositionLocalOf { ForestPalettes.DeepForestDark }

/** The forest theme in use, for materials that differ by theme beyond colour (the stone of the glass). */
val LocalForestThemeId = staticCompositionLocalOf { ForestThemeId.Default }

/** Whether the forest theme in use is dark or light. */
val LocalForestMode = staticCompositionLocalOf { ForestMode.Default }

/** [colors] mapped onto Material's colour roles. */
fun forestColorScheme(
    colors: ForestColors,
    mode: ForestMode,
): ColorScheme =
    when (mode) {
        ForestMode.Dark ->
            darkColorScheme(
                primary = colors.accent,
                onPrimary = colors.onAccent,
                secondary = colors.highlight,
                onSecondary = colors.base,
                background = colors.base,
                onBackground = colors.textPrimary,
                surface = colors.base,
                onSurface = colors.textPrimary,
                surfaceVariant = colors.glass,
                onSurfaceVariant = colors.textSecondary,
                outline = colors.textSecondary,
            )
        ForestMode.Light ->
            lightColorScheme(
                primary = colors.accent,
                onPrimary = colors.onAccent,
                secondary = colors.highlight,
                onSecondary = colors.base,
                background = colors.base,
                onBackground = colors.textPrimary,
                surface = colors.base,
                onSurface = colors.textPrimary,
                surfaceVariant = colors.glass,
                onSurfaceVariant = colors.textSecondary,
                outline = colors.textSecondary,
            )
    }

/**
 * The water, forest and stone App UI theme: [theme]'s colours in [mode], set in Fraunces and Figtree.
 * Material's shape roles take only even corners, so surfaces take their pebble from [ForestShapes].
 * Not yet applied to any screen: the App UI switches over in one piece.
 */
@Composable
fun ForestTheme(
    theme: ForestThemeId = ForestThemeId.Default,
    mode: ForestMode = ForestMode.Default,
    content: @Composable () -> Unit,
) {
    val colors = ForestPalettes.of(theme, mode)
    CompositionLocalProvider(
        LocalForestColors provides colors,
        LocalForestThemeId provides theme,
        LocalForestMode provides mode,
    ) {
        MaterialTheme(
            colorScheme = forestColorScheme(colors, mode),
            typography = forestTypography(),
            content = content,
        )
    }
}
