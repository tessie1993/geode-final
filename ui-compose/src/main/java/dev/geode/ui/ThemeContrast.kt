package dev.geode.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import dev.geode.ui.theme.ThemePack

object ThemeContrast {
    const val LIGHT_CONTRAST_MIN = 0.5f

    const val BODY_CONTRAST_MIN = 4.5f

    const val HINT_CONTRAST_MIN = 3.0f
}

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

private fun Color.toArgbInt(): Int =
    ((alpha * 255f + 0.5f).toInt() shl 24) or
        ((red * 255f + 0.5f).toInt() shl 16) or
        ((green * 255f + 0.5f).toInt() shl 8) or
        (blue * 255f + 0.5f).toInt()

internal val LocalFontColor = staticCompositionLocalOf<Color?> { null }

@Composable
fun accentTextColor(): Color = LocalFontColor.current ?: MaterialTheme.colorScheme.primary

fun CornerStyle.shapes(): Shapes =
    when (this) {
        CornerStyle.SHARP ->
            Shapes(
                extraSmall = RoundedCornerShape(0.dp),
                small = RoundedCornerShape(0.dp),
                medium = RoundedCornerShape(0.dp),
                large = RoundedCornerShape(0.dp),
                extraLarge = RoundedCornerShape(0.dp),
            )
        CornerStyle.ROUNDED -> Shapes()
        CornerStyle.PILL ->
            Shapes(
                extraSmall = RoundedCornerShape(12.dp),
                small = RoundedCornerShape(16.dp),
                medium = RoundedCornerShape(24.dp),
                large = RoundedCornerShape(28.dp),
                extraLarge = RoundedCornerShape(32.dp),
            )
    }
