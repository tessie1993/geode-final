package dev.geode.design.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** The contrast the App UI's text keeps against what it is drawn on. */
object ThemeContrast {
    /** WCAG contrast ratio for body text. */
    const val BODY_CONTRAST_MIN = 4.5f

    /** WCAG contrast ratio for hints and large text. */
    const val HINT_CONTRAST_MIN = 3.0f
}

/** The font colour the person chose, or null when the pack's own colours apply. */
val LocalFontColor = staticCompositionLocalOf<Color?> { null }

@Composable
fun accentTextColor(): Color = LocalFontColor.current ?: MaterialTheme.colorScheme.primary
