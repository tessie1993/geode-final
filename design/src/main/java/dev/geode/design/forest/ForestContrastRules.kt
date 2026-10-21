package dev.geode.design.forest

import androidx.compose.ui.graphics.Color

/** One piece of text in one colour on one opaque ground, and the contrast it must keep. */
data class ContrastPair(
    /** What is drawn on what, for failure messages. */
    val name: String,
    val text: Color,
    val ground: Color,
    val minimum: Float,
) {
    /** The contrast this pair has. */
    val ratio: Float get() = WcagContrast.ratio(text, ground)

    /** Whether the pair keeps its [minimum]. */
    val passes: Boolean get() = ratio >= minimum
}

/**
 * The contrast every theme's colours must keep: each text role (primary, secondary, accent,
 * highlight) at WCAG AA for body text on the base, on glass over the base, and on glass over the
 * worst backdrop of its mode; and on-accent text on a filled accent.
 */
object ForestContrastRules {
    /** Every pair [colors] must satisfy in [mode], with glass drawn at [glassOpacity]. */
    fun pairs(
        colors: ForestColors,
        mode: ForestMode,
        glassOpacity: Float = ForestGlass.TEXT_OPACITY_MIN,
    ): List<ContrastPair> {
        val grounds =
            listOf(
                "base" to colors.base,
                "glass over base" to WcagContrast.over(colors.glass, glassOpacity, colors.base),
                "glass over worst backdrop" to
                    WcagContrast.over(colors.glass, glassOpacity, ForestGlass.worstBackdrop(mode)),
            )
        val texts =
            listOf(
                "primary text" to colors.textPrimary,
                "secondary text" to colors.textSecondary,
                "accent" to colors.accent,
                "highlight" to colors.highlight,
            )
        val onGrounds =
            texts.flatMap { (textName, text) ->
                grounds.map { (groundName, ground) ->
                    ContrastPair("$textName on $groundName", text, ground, WcagContrast.AA_TEXT)
                }
            }
        return onGrounds + ContrastPair("on-accent on accent", colors.onAccent, colors.accent, WcagContrast.AA_TEXT)
    }

    /** The pairs of [colors] in [mode] that fall short, empty when the palette is fit to ship. */
    fun failures(
        colors: ForestColors,
        mode: ForestMode,
        glassOpacity: Float = ForestGlass.TEXT_OPACITY_MIN,
    ): List<ContrastPair> = pairs(colors, mode, glassOpacity).filterNot { it.passes }
}
