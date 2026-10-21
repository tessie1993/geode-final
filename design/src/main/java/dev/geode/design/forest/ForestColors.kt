package dev.geode.design.forest

import androidx.compose.ui.graphics.Color

/**
 * The colour roles of one theme in one mode. Every text role meets WCAG AA on [base], and on
 * [glass] at [ForestGlass.TEXT_OPACITY_MIN] over the worst backdrop ([ForestContrastRules]).
 */
data class ForestColors(
    /** The screen's ground, behind the backdrop art. */
    val base: Color,
    /** The tint of frosted stone glass, drawn at least [ForestGlass.TEXT_OPACITY_MIN] opaque under text. */
    val glass: Color,
    /** Titles and body text. */
    val textPrimary: Color,
    /** Supporting text: subtitles, metadata, hints. */
    val textSecondary: Color,
    /** The theme's accent: moss, teal glow or lichen. Usable as text. */
    val accent: Color,
    /** The "lit" highlight for the active and now-playing state: amber, firefly or rust. Usable as text. */
    val highlight: Color,
    /** Text and icons drawn on a filled [accent]. */
    val onAccent: Color,
)
