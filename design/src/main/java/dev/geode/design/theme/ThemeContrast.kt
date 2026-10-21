package dev.geode.design.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** The contrast the App UI's text keeps against what it is drawn on. */
object ThemeContrast {
    /** The least luminance difference a chosen font colour needs on a light pack to be honoured. */
    const val LIGHT_CONTRAST_MIN = 0.5f

    /** WCAG contrast ratio for body text. */
    const val BODY_CONTRAST_MIN = 4.5f

    /** WCAG contrast ratio for hints and large text. */
    const val HINT_CONTRAST_MIN = 3.0f
}

/**
 * The font colour the person chose, or null when it would not read on this pack: a dark pack honours
 * any choice, a light one only a colour far enough from its (dimmed) background.
 */
fun ThemePack.resolvedFontColor(
    fontColorArgb: Int?,
    backgroundDim: Float = 0f,
): Int? {
    if (fontColorArgb == null || !isLight) return fontColorArgb
    val painted = Color(ColorDerive.dim(palette.background.toArgbInt(), backgroundDim))
    val diff = kotlin.math.abs(Color(fontColorArgb).luminance() - painted.luminance())
    return if (diff >= ThemeContrast.LIGHT_CONTRAST_MIN) fontColorArgb else null
}

fun ThemePack.fontColorActive(
    fontColorArgb: Int?,
    backgroundDim: Float = 0f,
): Boolean = resolvedFontColor(fontColorArgb, backgroundDim) != null

/** The font colour in force, or null when the pack's own colours apply. */
val LocalFontColor = staticCompositionLocalOf<Color?> { null }

@Composable
fun accentTextColor(): Color = LocalFontColor.current ?: MaterialTheme.colorScheme.primary
