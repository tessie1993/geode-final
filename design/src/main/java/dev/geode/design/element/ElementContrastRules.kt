package dev.geode.design.element

import dev.geode.design.forest.ContrastPair
import dev.geode.design.forest.WcagContrast

/**
 * The contrast every Element's colours must keep: each text role (primary, secondary, accent, active,
 * error) at WCAG AA for body text on every ground an Element draws text on (the background, the deep
 * background, and both label surfaces), and the text on a filled accent and a filled active control.
 */
object ElementContrastRules {
    /** Every pair [colors] must satisfy. */
    fun pairs(colors: ElementColors): List<ContrastPair> {
        val grounds =
            listOf(
                "background" to colors.background,
                "deep background" to colors.backgroundDeep,
                "surface" to colors.surface,
                "raised surface" to colors.surfaceHigh,
            )
        val texts =
            listOf(
                "primary text" to colors.textPrimary,
                "secondary text" to colors.textSecondary,
                "accent" to colors.accent,
                "active" to colors.active,
                "error" to colors.error,
            )
        val onGrounds =
            texts.flatMap { (textName, text) ->
                grounds.map { (groundName, ground) ->
                    ContrastPair("$textName on $groundName", text, ground, WcagContrast.AA_TEXT)
                }
            }
        return onGrounds +
            ContrastPair("on-accent on accent", colors.onAccent, colors.accent, WcagContrast.AA_TEXT) +
            ContrastPair("on-active on active", colors.onActive, colors.active, WcagContrast.AA_TEXT)
    }

    /** The pairs of [colors] that fall short; empty when the Element is fit to ship. */
    fun failures(colors: ElementColors): List<ContrastPair> = pairs(colors).filterNot { it.passes }
}
