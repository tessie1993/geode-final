package dev.geode.design.water

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.geode.design.forest.ForestMotion

/** The named sizes and timings of glass and water (`docs/design/research-high-end-references.md`, section 3). */
object WaterTokens {
    /** How long a touch ripple lives (the research asks for 600–900 ms). */
    const val RIPPLE_DURATION_MILLIS = 760L

    /** The crest's displacement at the touch (the research asks for 2–4 dp). */
    val RippleAmplitude = 3.dp

    /** The distance between one ring and the next. */
    val RippleWavelength = 22.dp

    /** The half-width of the band of rings around the wavefront. */
    val RippleBand = 18.dp

    /** How quickly a ripple's height falls away over its life. */
    const val RIPPLE_DAMPING = 2.2f

    /** How brightly the crest catches light on Android 13+, as a fraction added to the glass. */
    const val CREST_LIGHT = 0.07f

    /** How strong the pre-rendered ring is at the touch on Android 12. */
    const val RING_ALPHA = 0.55f

    /** How strong the opacity pulse is when animations are removed. */
    const val PULSE_ALPHA = 0.12f

    /** Where the wavefront sits in the pre-rendered ring sprite, as a fraction of its half-width. */
    const val RING_SPRITE_FRONT = 0.78f

    /** The blur radius of frosted stone glass (the research asks for 20–32 dp). */
    val BlurRadius = 24.dp

    /** The width of the light caught on the glass's edge. */
    val EdgeLightWidth = 1.dp

    /** How bright the edge light is at its top-start corner, fading to nothing by mid-edge. */
    const val EDGE_LIGHT_ALPHA = 0.18f

    /** How far along the panel's diagonal the edge light has faded out. */
    const val EDGE_LIGHT_REACH = 0.5f

    /** How dark the faint inner shadow along the glass's bottom gets. */
    const val INNER_SHADOW_ALPHA = 0.12f

    /** Where the inner shadow starts, as a fraction of the panel's height. */
    const val INNER_SHADOW_START = 0.72f

    /** The scanned stone grain's strength in dark mode (the brief asks for 3–6 %). */
    const val GRAIN_ALPHA_DARK = 0.05f

    /** The grain's strength in light mode, where it shows more readily. */
    const val GRAIN_ALPHA_LIGHT = 0.035f

    /** The soft coloured shadow under glass, tinted with the theme's base, never pure black. */
    val ShadowElevation = 10.dp

    /** How strong the coloured shadow is. */
    const val SHADOW_ALPHA = 0.35f

    /** The ripple spec for [tier] at [density]. */
    fun rippleSpec(
        tier: RippleTier,
        density: Density,
    ): RippleSpec =
        with(density) {
            RippleSpec(
                durationMillis =
                    if (tier == RippleTier.Pulse) ForestMotion.REDUCED_PULSE_MILLIS.toLong() else RIPPLE_DURATION_MILLIS,
                peakAmplitudePx = RippleAmplitude.toPx(),
                wavelengthPx = RippleWavelength.toPx(),
                bandPx = RippleBand.toPx(),
                damping = RIPPLE_DAMPING,
            )
        }
}
