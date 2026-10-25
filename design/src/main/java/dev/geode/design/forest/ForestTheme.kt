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
 * The forest App UI theme: the Deep forest colours in [mode], set in Fraunces and Figtree. Material's
 * shape roles take only even corners, so surfaces take their pebble from [ForestShapes]. Superseded by
 * the Elements (`ElementTheme`), which also provide [LocalForestColors] to the screens built on it.
 */
@Composable
fun ForestTheme(
    mode: ForestMode = ForestMode.Default,
    content: @Composable () -> Unit,
) {
    val colors = ForestPalettes.of(mode)
    CompositionLocalProvider(LocalForestColors provides colors) {
        MaterialTheme(
            colorScheme = forestColorScheme(colors, mode),
            typography = forestTypography(),
            content = content,
        )
    }
}
