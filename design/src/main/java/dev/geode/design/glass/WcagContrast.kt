package dev.geode.design.glass

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

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

/** The WCAG 2.x contrast formula, used to check the App UI's colour tokens. */
object WcagContrast {
    /** The contrast WCAG AA asks of body text. */
    const val AA_TEXT = 4.5f

    /** The contrast WCAG AA asks of large text, icons and control outlines. */
    const val AA_LARGE_TEXT = 3.0f

    /** The relative luminance of [color]'s RGB, ignoring its alpha: 0 for black, 1 for white. */
    fun relativeLuminance(color: Color): Float =
        RED_WEIGHT * linear(color.red) + GREEN_WEIGHT * linear(color.green) + BLUE_WEIGHT * linear(color.blue)

    /** The contrast ratio between two opaque colours, from 1 (the same) to 21 (black on white). */
    fun ratio(
        first: Color,
        second: Color,
    ): Float {
        val a = relativeLuminance(first)
        val b = relativeLuminance(second)
        return (max(a, b) + FLARE) / (min(a, b) + FLARE)
    }

    /** The opaque colour seen when [top] is drawn at [opacity] over the opaque [bottom]. */
    fun over(
        top: Color,
        opacity: Float,
        bottom: Color,
    ): Color {
        val a = opacity.coerceIn(0f, 1f)

        fun mix(
            t: Float,
            b: Float,
        ) = t * a + b * (1f - a)
        return Color(mix(top.red, bottom.red), mix(top.green, bottom.green), mix(top.blue, bottom.blue))
    }

    private fun linear(channel: Float): Float =
        if (channel <= LINEAR_KNEE) {
            channel / LINEAR_SLOPE
        } else {
            ((channel + GAMMA_OFFSET) / (1f + GAMMA_OFFSET)).pow(GAMMA)
        }

    private const val RED_WEIGHT = 0.2126f
    private const val GREEN_WEIGHT = 0.7152f
    private const val BLUE_WEIGHT = 0.0722f
    private const val FLARE = 0.05f
    private const val LINEAR_KNEE = 0.04045f
    private const val LINEAR_SLOPE = 12.92f
    private const val GAMMA_OFFSET = 0.055f
    private const val GAMMA = 2.4f
}
