package dev.geode.design.glass

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import dev.geode.design.forest.ForestColors
import dev.geode.design.forest.LocalForestColors
import dev.geode.design.theme.CornerShapes

/**
 * [colors] on Material's colour roles, so plain Material components sit on glass too. The page roles
 * (background, surface) are clear: the backdrop behind every page shows through them. Filled cards and
 * text fields are thin glass over that backdrop; menus, dialogs and sheets, which may open over anything,
 * are matte.
 */
fun glassColorScheme(
    colors: GlassColors,
    dark: Boolean,
): ColorScheme {
    val clear = Color.Transparent
    val thin = colors.glass.copy(alpha = GlassKind.CARD.tintAlpha)
    val matte = colors.glass.copy(alpha = GlassKind.SHEET.matteAlpha)
    val inverseAccent = GlassPalettes.of(!dark).accent
    return if (dark) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            primaryContainer = colors.accentContainer,
            onPrimaryContainer = colors.onAccentContainer,
            inversePrimary = inverseAccent,
            secondary = colors.warm,
            onSecondary = colors.onWarm,
            secondaryContainer = colors.selected,
            onSecondaryContainer = colors.textPrimary,
            tertiary = colors.warm,
            onTertiary = colors.onWarm,
            tertiaryContainer = colors.accentContainer,
            onTertiaryContainer = colors.onAccentContainer,
            background = clear,
            onBackground = colors.textPrimary,
            surface = clear,
            onSurface = colors.textPrimary,
            surfaceVariant = thin,
            onSurfaceVariant = colors.textSecondary,
            surfaceTint = colors.accent,
            inverseSurface = colors.textPrimary,
            inverseOnSurface = colors.base,
            error = colors.error,
            onError = colors.onError,
            errorContainer = colors.errorContainer,
            onErrorContainer = colors.onErrorContainer,
            outline = colors.outline,
            outlineVariant = colors.hairline,
            scrim = Color.Black,
            surfaceBright = matte,
            surfaceContainer = matte,
            surfaceContainerHigh = matte,
            surfaceContainerHighest = thin,
            surfaceContainerLow = matte,
            surfaceContainerLowest = thin,
            surfaceDim = matte,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            primaryContainer = colors.accentContainer,
            onPrimaryContainer = colors.onAccentContainer,
            inversePrimary = inverseAccent,
            secondary = colors.warm,
            onSecondary = colors.onWarm,
            secondaryContainer = colors.selected,
            onSecondaryContainer = colors.textPrimary,
            tertiary = colors.warm,
            onTertiary = colors.onWarm,
            tertiaryContainer = colors.accentContainer,
            onTertiaryContainer = colors.onAccentContainer,
            background = clear,
            onBackground = colors.textPrimary,
            surface = clear,
            onSurface = colors.textPrimary,
            surfaceVariant = thin,
            onSurfaceVariant = colors.textSecondary,
            surfaceTint = colors.accent,
            inverseSurface = colors.textPrimary,
            inverseOnSurface = colors.base,
            error = colors.error,
            onError = colors.onError,
            errorContainer = colors.errorContainer,
            onErrorContainer = colors.onErrorContainer,
            outline = colors.outline,
            outlineVariant = colors.hairline,
            scrim = Color.Black,
            surfaceBright = matte,
            surfaceContainer = matte,
            surfaceContainerHigh = matte,
            surfaceContainerHighest = thin,
            surfaceContainerLow = matte,
            surfaceContainerLowest = thin,
            surfaceDim = matte,
        )
    }
}

/**
 * The glass App UI's theme: the [dark] or light palette with the person's adjustments, Material's roles
 * mapped onto it ([glassColorScheme]), Inter, and the corner set the person chose ([shapes]). The [tier]
 * decides how much the glass may do. Screens built on the earlier forest roles get them from the glass
 * palette too, until they read the glass roles themselves.
 */
@Composable
fun GlassTheme(
    dark: Boolean,
    tier: GlassTier = GlassTier.FULL,
    shapes: Shapes = CornerShapes.Rounded,
    accentIntensity: Float = 1f,
    backgroundDim: Float = 0f,
    barOpacity: Float = GlassKind.BAR.tintAlpha,
    fontColor: Color? = null,
    content: @Composable () -> Unit,
) {
    val style =
        remember(dark, tier, accentIntensity, backgroundDim, barOpacity, fontColor) {
            GlassStyle.of(dark, tier, accentIntensity, backgroundDim, barOpacity, fontColor)
        }
    val scheme = remember(style) { glassColorScheme(style.colors, style.dark) }
    val typography = remember { glassTypography() }
    val forest = remember(style) { forestColorsOf(style.colors) }
    CompositionLocalProvider(
        LocalGlassStyle provides style,
        LocalForestColors provides forest,
        LocalContentColor provides style.colors.textPrimary,
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
    }
}

/** The glass palette in the forest roles, for the screens still drawn with them. */
private fun forestColorsOf(colors: GlassColors): ForestColors =
    ForestColors(
        base = colors.base,
        glass = colors.glass,
        textPrimary = colors.textPrimary,
        textSecondary = colors.textSecondary,
        accent = colors.accent,
        highlight = colors.warm,
        onAccent = colors.onAccent,
    )
