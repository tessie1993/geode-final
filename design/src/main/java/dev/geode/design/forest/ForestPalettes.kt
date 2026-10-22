package dev.geode.design.forest

import androidx.compose.ui.graphics.Color

/**
 * The contrast-checked forest colours, from section 2 of `docs/design/research-high-end-references.md`.
 * `ForestContrastRulesTest` holds them to WCAG AA. The three forest themes were replaced by the
 * Elements (#103: Deep forest became Wood, Night forest Bioluminescence and Earth + lichen Stone, in
 * `ElementSkins`); Deep forest stays because Customization's screens are still drawn in it.
 */
object ForestPalettes {
    /** Deep forest + stone, dark: pine-black, slate glass, moss and amber. */
    val DeepForestDark =
        ForestColors(
            base = Color(0xFF0D1A14),
            glass = Color(0xFF24302B),
            textPrimary = Color(0xFFEEF1EA),
            textSecondary = Color(0xFFB9C4B8),
            accent = Color(0xFFA9C77F),
            highlight = Color(0xFFE8B15A),
            onAccent = Color(0xFF0D1A14),
        )

    /** Deep forest + stone, sunlit meadow: warm cream and soft green. */
    val DeepForestLight =
        ForestColors(
            base = Color(0xFFF4EFE2),
            glass = Color(0xFFFBF8F0),
            textPrimary = Color(0xFF1C2A20),
            textSecondary = Color(0xFF4A5A4C),
            accent = Color(0xFF4B6828),
            highlight = Color(0xFF8A5212),
            onAccent = Color(0xFFF4EFE2),
        )

    /** The colours of [mode]. */
    fun of(mode: ForestMode): ForestColors =
        when (mode) {
            ForestMode.Dark -> DeepForestDark
            ForestMode.Light -> DeepForestLight
        }
}
