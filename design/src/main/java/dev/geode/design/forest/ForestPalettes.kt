package dev.geode.design.forest

import androidx.compose.ui.graphics.Color

/**
 * The contrast-checked colours of every theme and mode, from section 2 of
 * `docs/design/research-high-end-references.md`. `ForestContrastRulesTest` holds them to WCAG AA.
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

    /** Night forest + bioluminescence, dark: green-black, teal glow and firefly yellow. */
    val NightForestDark =
        ForestColors(
            base = Color(0xFF060D0B),
            glass = Color(0xFF122019),
            textPrimary = Color(0xFFE6F4EF),
            textSecondary = Color(0xFFA7C2B9),
            accent = Color(0xFF5CE6CF),
            highlight = Color(0xFFF2E36B),
            onAccent = Color(0xFF06231D),
        )

    /** Night forest + bioluminescence, light: misty pale green at dawn. */
    val NightForestLight =
        ForestColors(
            base = Color(0xFFE9F1EC),
            glass = Color(0xFFF7FAF7),
            textPrimary = Color(0xFF10241F),
            textSecondary = Color(0xFF3F5952),
            accent = Color(0xFF08655A),
            highlight = Color(0xFF6E5A00),
            onAccent = Color(0xFFFFFFFF),
        )

    /** Earth + lichen, dark: umber-black, basalt glass, lichen and rust. */
    val EarthLichenDark =
        ForestColors(
            base = Color(0xFF18120E),
            glass = Color(0xFF2C2622),
            textPrimary = Color(0xFFF1EBE2),
            textSecondary = Color(0xFFC7BBAA),
            accent = Color(0xFFC9D99E),
            highlight = Color(0xFFF0A57C),
            onAccent = Color(0xFF18120E),
        )

    /** Earth + lichen, light: sandstone at noon. */
    val EarthLichenLight =
        ForestColors(
            base = Color(0xFFF3ECE0),
            glass = Color(0xFFFBF7F0),
            textPrimary = Color(0xFF2A1F17),
            textSecondary = Color(0xFF5A4A3C),
            accent = Color(0xFF4F6024),
            highlight = Color(0xFF9A4720),
            onAccent = Color(0xFFFBF7F0),
        )

    /** The colours of [theme] in [mode]. */
    fun of(
        theme: ForestThemeId,
        mode: ForestMode,
    ): ForestColors =
        when (theme) {
            ForestThemeId.DeepForest -> if (mode == ForestMode.Dark) DeepForestDark else DeepForestLight
            ForestThemeId.NightForest -> if (mode == ForestMode.Dark) NightForestDark else NightForestLight
            ForestThemeId.EarthLichen -> if (mode == ForestMode.Dark) EarthLichenDark else EarthLichenLight
        }
}
