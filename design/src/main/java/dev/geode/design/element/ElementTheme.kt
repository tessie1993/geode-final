package dev.geode.design.element

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import dev.geode.design.forest.ForestColors
import dev.geode.design.forest.LocalForestColors

/** The tokens of the Element in use. Screens and components read the look from here, never from a palette. */
val LocalElementTokens = staticCompositionLocalOf { ElementSkins.Water }

/** [colors] on Material's colour roles, so Material components follow the Element too. Dark only. */
fun elementColorScheme(colors: ElementColors): ColorScheme =
    darkColorScheme(
        primary = colors.active,
        onPrimary = colors.onActive,
        secondary = colors.accent,
        onSecondary = colors.onAccent,
        tertiary = colors.secondary,
        onTertiary = colors.onActive,
        background = colors.background,
        onBackground = colors.textPrimary,
        surface = colors.background,
        onSurface = colors.textPrimary,
        surfaceVariant = colors.surface,
        onSurfaceVariant = colors.textSecondary,
        surfaceContainerLowest = colors.backgroundDeep,
        surfaceContainerLow = colors.surface,
        surfaceContainer = colors.surface,
        surfaceContainerHigh = colors.surfaceHigh,
        surfaceContainerHighest = colors.surfaceHigh,
        error = colors.error,
        onError = colors.backgroundDeep,
        outline = colors.textSecondary,
        outlineVariant = colors.divider,
    )

/**
 * [colors] in the forest roles, for the screens built on the earlier forest tokens (Customization),
 * so they follow the Element until they read [LocalElementTokens] themselves.
 */
fun forestColorsOf(colors: ElementColors): ForestColors =
    ForestColors(
        base = colors.background,
        glass = colors.surface,
        textPrimary = colors.textPrimary,
        textSecondary = colors.textSecondary,
        accent = colors.active,
        highlight = colors.accent,
        onAccent = colors.onActive,
    )

/**
 * The App UI in one Element. The app's root wraps everything in it with the Element from Settings, so
 * choosing another Element recomposes the whole App UI in it at once, with no restart.
 */
@Composable
fun ElementTheme(
    tokens: ElementTokens,
    content: @Composable () -> Unit,
) {
    val colorScheme = remember(tokens.colors) { elementColorScheme(tokens.colors) }
    val typography = remember(tokens.type) { elementTypography(tokens.type) }
    val forest = remember(tokens.colors) { forestColorsOf(tokens.colors) }
    CompositionLocalProvider(
        LocalElementTokens provides tokens,
        LocalForestColors provides forest,
    ) {
        MaterialTheme(colorScheme = colorScheme, typography = typography, content = content)
    }
}
