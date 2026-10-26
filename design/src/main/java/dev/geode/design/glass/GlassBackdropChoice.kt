package dev.geode.design.glass

import androidx.compose.ui.graphics.Color

/**
 * The backdrop the person picks for behind the glass when no Visual plays (#398). [ARTWORK], the
 * default, is the playing song's art behind Home and the plain player and the calm Mist elsewhere; the
 * others are one calm backdrop everywhere: Mist, Dusk, Aurora, or Plain, the theme's base with only the
 * grain. In the Matte tier the art gives way to the calm backdrop, as it always has. Every text role
 * stays legible straight on each one's fields (`GlassColorSchemeTest`).
 */
enum class GlassBackdropChoice {
    ARTWORK,
    MIST,
    DUSK,
    AURORA,
    PLAIN,
    ;

    /** Whether the playing song's art shows behind Home and the plain player. */
    val showsArtwork: Boolean get() = this == ARTWORK

    /** The calm backdrop's fields of colour in the [dark] or light mode. */
    fun fields(dark: Boolean): List<GlassField> =
        when (this) {
            ARTWORK, MIST -> GlassPalettes.of(dark).fields
            DUSK -> if (dark) DuskNight else DuskDay
            AURORA -> if (dark) AuroraNight else AuroraDay
            PLAIN -> emptyList()
        }
}

/** Three fields in the calm backdrop's places: top left, right and bottom, as the palette's are. */
private fun fields(
    first: Pair<Long, Float>,
    second: Pair<Long, Float>,
    third: Pair<Long, Float>,
): List<GlassField> =
    listOf(
        GlassField(Color(first.first), alpha = first.second, x = 0.15f, y = 0.08f, reach = 0.75f),
        GlassField(Color(second.first), alpha = second.second, x = 0.95f, y = 0.45f, reach = 0.6f),
        GlassField(Color(third.first), alpha = third.second, x = 0.25f, y = 1f, reach = 0.7f),
    )

/** Dusk: rose, amber and violet. */
private val DuskNight = fields(0xFF5A2A44 to 0.50f, 0xFF4A3318 to 0.45f, 0xFF33285A to 0.45f)
private val DuskDay = fields(0xFFF3D2DC to 0.60f, 0xFFF6E2C6 to 0.55f, 0xFFE0D6F2 to 0.55f)

/** Aurora: teal, green and indigo. */
private val AuroraNight = fields(0xFF164A4A to 0.50f, 0xFF22432A to 0.45f, 0xFF25305E to 0.45f)
private val AuroraDay = fields(0xFFC6E9E4 to 0.60f, 0xFFD7EDCD to 0.55f, 0xFFD3DBF3 to 0.55f)
