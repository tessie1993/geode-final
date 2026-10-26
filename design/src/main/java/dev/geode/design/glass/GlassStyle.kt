package dev.geode.design.glass

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import dev.geode.design.theme.ColorDerive
import dev.geode.design.theme.toArgbInt

/**
 * Everything glass surfaces read: the colours in use, whether they are the dark or light ones, the
 * tier, the accent tint and backdrop the person picked, and their adjustments. Provided by [GlassTheme]
 * through [LocalGlassStyle].
 */
@Immutable
data class GlassStyle(
    val colors: GlassColors,
    val dark: Boolean,
    val tier: GlassTier,
    /** How much of a backdrop shows through its clamp, after Background dim ([GlassStyle.clampFor]). */
    val clamp: Float = GlassTokens.BACKDROP_CLAMP,
    /** The bar's tint: its token, or more when the person asks for a more solid bar. */
    val barTintAlpha: Float = GlassKind.BAR.tintAlpha,
    /** The accent tint the [colors] were made with. */
    val accent: GlassAccent = GlassAccent.SKY,
    /** The backdrop behind the glass when no Visual plays; its fields are the [colors]' fields. */
    val backdrop: GlassBackdropChoice = GlassBackdropChoice.ARTWORK,
) {
    /** How opaque glass of [kind] is tinted over a clamped backdrop. */
    fun tintAlpha(kind: GlassKind): Float = if (kind == GlassKind.BAR) barTintAlpha else kind.tintAlpha

    /** How strongly the calm backdrop's fields show: they recede with Background dim, as the clamp does. */
    val fieldStrength: Float get() = clamp / GlassTokens.BACKDROP_CLAMP

    companion object {
        val Default = GlassStyle(GlassPalettes.Dark, dark = true, tier = GlassTier.FULL)

        /** The clamp after Background [dim]: dimming hides more of a backdrop, never less. */
        fun clampFor(dim: Float): Float = GlassTokens.BACKDROP_CLAMP * (1f - dim.coerceIn(0f, 1f))

        /** The bar's tint for the person's Bar opacity, never thinner than the bar's token. */
        fun barTintFor(opacity: Float): Float = opacity.coerceIn(GlassKind.BAR.tintAlpha, 1f)

        /**
         * The style for the [dark] or light palette with the person's choices: the [accent] tint and its
         * [accentIntensity], the [backdrop], a chosen [fontColor] (kept only where it stays legible),
         * Background [dim] and Bar [barOpacity]. However they are set, every text role keeps 4.5:1 on
         * every ground.
         */
        fun of(
            dark: Boolean,
            tier: GlassTier,
            accentIntensity: Float = 1f,
            dim: Float = 0f,
            barOpacity: Float = GlassKind.BAR.tintAlpha,
            fontColor: Color? = null,
            accent: GlassAccent = GlassAccent.SKY,
            backdrop: GlassBackdropChoice = GlassBackdropChoice.ARTWORK,
        ): GlassStyle {
            val clamp = clampFor(dim)
            val palette = GlassPalettes.of(dark).withAccent(accent.colors(dark)).copy(fields = backdrop.fields(dark))
            val colors = adjusted(palette, dark, accentIntensity, fontColor, clamp)
            return GlassStyle(colors, dark, tier, clamp, barTintFor(barOpacity), accent, backdrop)
        }

        private fun adjusted(
            palette: GlassColors,
            dark: Boolean,
            accentIntensity: Float,
            fontColor: Color?,
            clamp: Float,
        ): GlassColors {
            val grounds = GlassContrast.grounds(palette, dark, clamp).map { it.second }
            val toward = if (dark) Color.White else Color.Black

            fun saturated(color: Color): Color =
                GlassContrast.readable(
                    Color(ColorDerive.scaleSaturation(color.toArgbInt(), accentIntensity)),
                    grounds,
                    WcagContrast.AA_TEXT,
                    toward,
                )
            val accent = saturated(palette.accent)
            val onAccent =
                GlassContrast.readable(palette.onAccent, listOf(accent), WcagContrast.AA_TEXT, if (dark) Color.Black else Color.White)
            val text =
                fontColor?.copy(alpha = 1f)?.takeIf { chosen ->
                    grounds.all { WcagContrast.ratio(chosen, it) >= WcagContrast.AA_TEXT }
                }
            return palette.copy(
                textPrimary = text ?: palette.textPrimary,
                accent = accent,
                onAccent = onAccent,
                warm = saturated(palette.warm),
            )
        }
    }
}

/** The glass style in use. */
val LocalGlassStyle = staticCompositionLocalOf { GlassStyle.Default }
