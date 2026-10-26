package dev.geode.design.glass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import dev.geode.design.forest.ContrastPair
import dev.geode.design.forest.WcagContrast

/**
 * The legibility maths of glass (#270, section 5.3). Text over glass is checked against the worst
 * backdrop that could sit behind it: white in the dark mode, black in the light one. A backdrop is
 * clamped before glass is drawn over it, so only [GlassTokens.BACKDROP_CLAMP] of it shows and the rest
 * is the theme's base colour.
 */
object GlassContrast {
    /** The brightest thing that may sit behind glass in the dark mode, the darkest in the light one. */
    fun worstBackdrop(dark: Boolean): Color = if (dark) Color.White else Color.Black

    /** What shows of [backdrop] once clamped: [clamp] of it, and the rest the [base] colour. */
    fun clamped(
        backdrop: Color,
        base: Color,
        clamp: Float,
    ): Color = WcagContrast.over(base, 1f - clamp.coerceIn(0f, 1f), backdrop)

    /** The ground text sees on glass tinted [tintAlpha] over the worst clamped backdrop. */
    fun glassGround(
        colors: GlassColors,
        tintAlpha: Float,
        clamp: Float,
        dark: Boolean,
    ): Color = WcagContrast.over(colors.glass, tintAlpha, clamped(worstBackdrop(dark), colors.base, clamp))

    /** The ground text sees on matte glass, [matteAlpha] opaque, over the worst backdrop with no clamp. */
    fun matteGround(
        colors: GlassColors,
        matteAlpha: Float,
        dark: Boolean,
    ): Color = WcagContrast.over(colors.glass, matteAlpha, worstBackdrop(dark))

    /** The ground at the heart of one of the calm backdrop's [field]s. */
    fun fieldGround(
        base: Color,
        field: GlassField,
    ): Color = WcagContrast.over(field.color, field.alpha, base)

    /** Every ground text may sit on in [colors], each with a name for failure messages. */
    fun grounds(
        colors: GlassColors,
        dark: Boolean,
        clamp: Float = GlassTokens.BACKDROP_CLAMP,
    ): List<Pair<String, Color>> {
        val glass =
            GlassKind.entries.flatMap { kind ->
                val name = kind.name.lowercase()
                listOf(
                    "$name glass" to glassGround(colors, kind.tintAlpha, clamp, dark),
                    "$name matte" to matteGround(colors, kind.matteAlpha, dark),
                )
            }
        val fields = colors.fields.mapIndexed { index, field -> "backdrop field $index" to fieldGround(colors.base, field) }
        return listOf("base" to colors.base) + glass + fields
    }

    /**
     * [wanted] when it keeps [minimum] on every one of [grounds]; otherwise [wanted] moved toward
     * [toward] in small steps until it does, or until it is [toward].
     */
    fun readable(
        wanted: Color,
        grounds: List<Color>,
        minimum: Float,
        toward: Color,
    ): Color {
        var fraction = 0f
        var color = wanted
        while (fraction < 1f && grounds.any { WcagContrast.ratio(color, it) < minimum }) {
            fraction = (fraction + READABLE_STEP).coerceAtMost(1f)
            color = lerp(wanted, toward, fraction)
        }
        return color
    }

    /** How far [readable] moves a colour toward its end at each step. */
    private const val READABLE_STEP = 0.05f
}

/**
 * The contrast every glass palette must keep: each text role (primary, secondary, accent, warm and
 * error) at WCAG AA for body text on every ground of [GlassContrast.grounds]; control outlines at 3:1
 * on the same grounds (WCAG 1.4.11); the text on each filled colour; and large primary text straight on
 * the worst clamped backdrop at 3:1, for a title drawn over album art.
 */
object GlassContrastRules {
    /** Every pair [colors] must satisfy in the [dark] or light mode, with a backdrop clamped to [clamp]. */
    fun pairs(
        colors: GlassColors,
        dark: Boolean,
        clamp: Float = GlassTokens.BACKDROP_CLAMP,
    ): List<ContrastPair> {
        val grounds = GlassContrast.grounds(colors, dark, clamp)
        val texts =
            listOf(
                "primary text" to colors.textPrimary,
                "secondary text" to colors.textSecondary,
                "accent" to colors.accent,
                "warm" to colors.warm,
                "error" to colors.error,
            )
        val onGrounds =
            texts.flatMap { (textName, text) ->
                grounds.map { (groundName, ground) -> ContrastPair("$textName on $groundName", text, ground, WcagContrast.AA_TEXT) }
            }
        val outlines =
            grounds.map { (groundName, ground) ->
                ContrastPair("outline on $groundName", colors.outline, ground, WcagContrast.AA_LARGE_TEXT)
            }
        val filled =
            listOf(
                ContrastPair("on-accent on accent", colors.onAccent, colors.accent, WcagContrast.AA_TEXT),
                ContrastPair("on-accent on accent container", colors.onAccentContainer, colors.accentContainer, WcagContrast.AA_TEXT),
                ContrastPair("primary text on selected", colors.textPrimary, colors.selected, WcagContrast.AA_TEXT),
                ContrastPair("on-warm on warm", colors.onWarm, colors.warm, WcagContrast.AA_TEXT),
                ContrastPair("on-error on error", colors.onError, colors.error, WcagContrast.AA_TEXT),
                ContrastPair("on-error on error container", colors.onErrorContainer, colors.errorContainer, WcagContrast.AA_TEXT),
            )
        val title =
            ContrastPair(
                "large primary text on the worst clamped backdrop",
                colors.textPrimary,
                GlassContrast.clamped(GlassContrast.worstBackdrop(dark), colors.base, clamp),
                WcagContrast.AA_LARGE_TEXT,
            )
        return onGrounds + outlines + filled + title
    }

    /** The pairs of [colors] that fall short; empty when the palette is fit to ship. */
    fun failures(
        colors: GlassColors,
        dark: Boolean,
        clamp: Float = GlassTokens.BACKDROP_CLAMP,
    ): List<ContrastPair> = pairs(colors, dark, clamp).filterNot { it.passes }
}
