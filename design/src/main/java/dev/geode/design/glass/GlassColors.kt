package dev.geode.design.glass

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * One soft field of colour in the calm backdrop, like light seen through frosted glass: where its heart
 * sits ([x], [y], as fractions of the screen's width and height), how far it reaches (a fraction of the
 * screen's longer side) and how strong it is at its heart. It fades to nothing at its edge.
 */
@Immutable
data class GlassField(
    val color: Color,
    val alpha: Float,
    val x: Float,
    val y: Float,
    val reach: Float,
)

/**
 * The colour roles of the glass App UI in one mode. Every text role keeps WCAG AA (4.5:1) on every
 * ground text can sit on: the base, glass of each kind over the worst clamped backdrop, matte glass over
 * the worst raw backdrop, and the calm backdrop's fields ([GlassContrastRules]).
 */
@Immutable
data class GlassColors(
    /** The ground under every backdrop, and what a backdrop is clamped toward. */
    val base: Color,
    /** The tint of glass, drawn at a [GlassKind]'s alpha. */
    val glass: Color,
    /** Titles and body text. */
    val textPrimary: Color,
    /** Supporting text: "artist · album", lengths, hints. */
    val textSecondary: Color,
    /** Primary actions, the current item and links. Usable as text. */
    val accent: Color,
    /** Text and icons on a filled [accent]. */
    val onAccent: Color,
    /** A quiet filled surface in the accent's hue, such as a tonal button. */
    val accentContainer: Color,
    /** Text and icons on [accentContainer]. */
    val onAccentContainer: Color,
    /** The fill behind a selected place on the Bar or a chosen chip. [textPrimary] sits on it. */
    val selected: Color,
    /** The warm second accent: highlights, what is playing now. Usable as text. */
    val warm: Color,
    /** Text and icons on a filled [warm]. */
    val onWarm: Color,
    /** Error text and icons. */
    val error: Color,
    /** Text and icons on a filled [error]. */
    val onError: Color,
    /** The calm surface of an error notice. */
    val errorContainer: Color,
    /** Text and icons on [errorContainer]. */
    val onErrorContainer: Color,
    /** Outlines of controls: at least 3:1 on every ground (WCAG 1.4.11). */
    val outline: Color,
    /** Dividers and hairlines. Decorative: never the only cue. */
    val hairline: Color,
    /** The calm backdrop's fields of colour. */
    val fields: List<GlassField>,
)

/**
 * The two glass palettes: a night glass of slate over blue-black, and a day glass of white over pale
 * mist. `GlassContrastRulesTest` holds both to WCAG AA on every ground.
 */
object GlassPalettes {
    val Dark =
        GlassColors(
            base = Color(0xFF0B0F14),
            glass = Color(0xFF1A212B),
            textPrimary = Color(0xFFEEF2F6),
            textSecondary = Color(0xFFB4BDC9),
            accent = Color(0xFF8CC2FF),
            onAccent = Color(0xFF0A1B2E),
            accentContainer = Color(0xFF23415F),
            onAccentContainer = Color(0xFFD6E8FF),
            selected = Color(0xFF2B4766),
            warm = Color(0xFFF2C27A),
            onWarm = Color(0xFF2A1C00),
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005),
            errorContainer = Color(0xFF5C1A16),
            onErrorContainer = Color(0xFFFFDAD6),
            outline = Color(0xFF8C96A3),
            hairline = Color(0xFF2C3542),
            fields =
                listOf(
                    GlassField(Color(0xFF1D3B5E), alpha = 0.55f, x = 0.15f, y = 0.08f, reach = 0.75f),
                    GlassField(Color(0xFF3B2F55), alpha = 0.45f, x = 0.95f, y = 0.45f, reach = 0.6f),
                    GlassField(Color(0xFF4A3920), alpha = 0.40f, x = 0.25f, y = 1f, reach = 0.7f),
                ),
        )

    val Light =
        GlassColors(
            base = Color(0xFFEDF0F4),
            glass = Color(0xFFFAFBFD),
            textPrimary = Color(0xFF11161C),
            textSecondary = Color(0xFF454F5B),
            accent = Color(0xFF17508F),
            onAccent = Color(0xFFFFFFFF),
            accentContainer = Color(0xFFD3E4F8),
            onAccentContainer = Color(0xFF0B2A4D),
            selected = Color(0xFFCFE0F5),
            warm = Color(0xFF714A00),
            onWarm = Color(0xFFFFFFFF),
            error = Color(0xFF9C1F18),
            onError = Color(0xFFFFFFFF),
            errorContainer = Color(0xFFF9DEDC),
            onErrorContainer = Color(0xFF410E0B),
            outline = Color(0xFF6B7581),
            hairline = Color(0xFFD3D9E0),
            fields =
                listOf(
                    GlassField(Color(0xFFC9DCF2), alpha = 0.65f, x = 0.15f, y = 0.08f, reach = 0.75f),
                    GlassField(Color(0xFFE2D8F1), alpha = 0.55f, x = 0.95f, y = 0.45f, reach = 0.6f),
                    GlassField(Color(0xFFF3E1C8), alpha = 0.55f, x = 0.25f, y = 1f, reach = 0.7f),
                ),
        )

    /** The palette for dark or light. */
    fun of(dark: Boolean): GlassColors = if (dark) Dark else Light
}
