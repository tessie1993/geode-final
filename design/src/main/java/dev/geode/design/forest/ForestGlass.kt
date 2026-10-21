package dev.geode.design.forest

import androidx.compose.ui.graphics.Color

/** How frosted stone glass is drawn when it carries text. */
object ForestGlass {
    /**
     * The least opacity of glass that carries text: blur, tint and scrim combined. Below it, secondary
     * text drops under WCAG AA when something bright sits behind the panel (the research measured 3.6:1
     * at 78%). Glass rises above it over bright backdrops, never below.
     */
    const val TEXT_OPACITY_MIN = 0.88f

    /** The glass tint of [colors] at [TEXT_OPACITY_MIN], ready to draw under text. */
    fun textGlass(colors: ForestColors): Color = colors.glass.copy(alpha = TEXT_OPACITY_MIN)

    /**
     * The brightest thing that may sit behind glass in [mode]: a white mote or artwork in the dark
     * themes, black in the light ones. Contrast is checked against glass over it.
     */
    fun worstBackdrop(mode: ForestMode): Color =
        when (mode) {
            ForestMode.Dark -> Color.White
            ForestMode.Light -> Color.Black
        }
}
