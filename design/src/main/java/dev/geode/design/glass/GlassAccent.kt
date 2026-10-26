package dev.geode.design.glass

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** The accent roles one tint sets in one mode: the accent itself, its container and the selection. */
@Immutable
data class GlassAccentColors(
    val accent: Color,
    val onAccent: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
    val selected: Color,
)

/**
 * The accent tints the person can pick for the glass highlights (#398), each with a night and a day
 * form. Sky is the palette's own. Every tint keeps every text role at 4.5:1 on every ground, in every
 * tier and mode (`GlassColorSchemeTest`); the warm second accent stays as it is.
 */
enum class GlassAccent(
    private val night: GlassAccentColors,
    private val day: GlassAccentColors,
) {
    SKY(
        night = accent(0xFF8CC2FF, 0xFF0A1B2E, 0xFF23415F, 0xFFD6E8FF, 0xFF2B4766),
        day = accent(0xFF17508F, 0xFFFFFFFF, 0xFFD3E4F8, 0xFF0B2A4D, 0xFFCFE0F5),
    ),
    TEAL(
        night = accent(0xFF6FD6C8, 0xFF00201C, 0xFF1C4A45, 0xFFC9F3EC, 0xFF24504B),
        day = accent(0xFF00625A, 0xFFFFFFFF, 0xFFC8EDE7, 0xFF00332E, 0xFFC4E6E0),
    ),
    MOSS(
        night = accent(0xFFA6D98A, 0xFF102000, 0xFF2E4A22, 0xFFDDF5CF, 0xFF33502A),
        day = accent(0xFF335A19, 0xFFFFFFFF, 0xFFD5ECC6, 0xFF15300A, 0xFFCFE5C0),
    ),
    VIOLET(
        night = accent(0xFFC3B2FF, 0xFF1E1048, 0xFF3D3366, 0xFFE7DFFF, 0xFF443A70),
        day = accent(0xFF5B45A8, 0xFFFFFFFF, 0xFFE4DCFA, 0xFF22124F, 0xFFDED5F5),
    ),
    ROSE(
        night = accent(0xFFFFADC6, 0xFF3D0A1E, 0xFF5A2A3C, 0xFFFFD9E4, 0xFF633244),
        day = accent(0xFF9A2F55, 0xFFFFFFFF, 0xFFF9DCE5, 0xFF42081F, 0xFFF3D4DE),
    ),
    ;

    /** This tint's roles in the [dark] or light mode. */
    fun colors(dark: Boolean): GlassAccentColors = if (dark) night else day
}

/** [this] palette with [tint]'s accent roles in place of its own. */
fun GlassColors.withAccent(tint: GlassAccentColors): GlassColors =
    copy(
        accent = tint.accent,
        onAccent = tint.onAccent,
        accentContainer = tint.accentContainer,
        onAccentContainer = tint.onAccentContainer,
        selected = tint.selected,
    )

private fun accent(
    accent: Long,
    onAccent: Long,
    accentContainer: Long,
    onAccentContainer: Long,
    selected: Long,
): GlassAccentColors = GlassAccentColors(Color(accent), Color(onAccent), Color(accentContainer), Color(onAccentContainer), Color(selected))
