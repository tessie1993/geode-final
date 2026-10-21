package dev.geode.design.water

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import dev.geode.design.R
import dev.geode.design.forest.ForestColors
import dev.geode.design.forest.ForestMode
import dev.geode.design.forest.ForestThemeId

/**
 * The scanned stone each theme's glass is cut from. Each is a CC0 photographic scan from Poly Haven,
 * reduced to its fine grain (grey, tileable, centred on mid-grey so it only lightens and darkens),
 * and laid over the glass at a few per cent. Never runtime noise. Sources: `THIRD_PARTY_NOTICES`.
 */
enum class StoneGrain(
    @param:DrawableRes val drawable: Int,
) {
    /** Cool grey slate, for Deep forest + stone. */
    Slate(R.drawable.design_stone_grain_slate),

    /** Dark speckled stone, for Night forest's obsidian-like glass. */
    Obsidian(R.drawable.design_stone_grain_obsidian),

    /** Coarse, warm basalt, for Earth + lichen. */
    Basalt(R.drawable.design_stone_grain_basalt),
}

/** How one theme's frosted stone glass looks: its tint, grain, edge light and shadow. */
data class StoneGlassMaterial(
    /** The glass's tint, drawn at the opacity [GlassOpacity] chooses. */
    val tint: Color,
    /** The stone the glass is cut from. */
    val grain: StoneGrain,
    /** How strongly the grain shows, between 3 and 6 per cent. */
    val grainAlpha: Float,
    /** The light the glass's top-start edge catches. */
    val edgeLight: Color,
    /** The soft shadow under the glass, tinted from the theme rather than black. */
    val shadow: Color,
) {
    companion object {
        /**
         * The glass of [theme] in [mode] with [colors]. Dark glass catches a rim in the theme's own
         * light: a cool near-white on slate, the teal glow from within on obsidian (no white rim),
         * a warm rust on basalt. Light glass catches sunlight.
         */
        fun of(
            theme: ForestThemeId,
            mode: ForestMode,
            colors: ForestColors,
        ): StoneGlassMaterial =
            StoneGlassMaterial(
                tint = colors.glass,
                grain =
                    when (theme) {
                        ForestThemeId.DeepForest -> StoneGrain.Slate
                        ForestThemeId.NightForest -> StoneGrain.Obsidian
                        ForestThemeId.EarthLichen -> StoneGrain.Basalt
                    },
                grainAlpha =
                    when (mode) {
                        ForestMode.Dark -> WaterTokens.GRAIN_ALPHA_DARK
                        ForestMode.Light -> WaterTokens.GRAIN_ALPHA_LIGHT
                    },
                edgeLight =
                    when (mode) {
                        ForestMode.Light -> Color.White
                        ForestMode.Dark ->
                            when (theme) {
                                ForestThemeId.DeepForest -> colors.textPrimary
                                ForestThemeId.NightForest -> colors.accent
                                ForestThemeId.EarthLichen -> colors.highlight
                            }
                    },
                shadow =
                    when (mode) {
                        ForestMode.Dark -> colors.base
                        ForestMode.Light -> colors.textPrimary
                    },
            )
    }
}
