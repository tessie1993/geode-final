package dev.geode.design.glass

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The kinds of frosted glass the App UI is made of (#324), each with its own frost, from the #270
 * research. A card rests on a still backdrop; a bar floats over content that scrolls under it; a sheet
 * rises over a whole page.
 */
enum class GlassKind(
    /** How far the backdrop is blurred under live glass. */
    val blur: Dp,
    /** How opaque the glass tint is over a clamped backdrop ([GlassTokens.BACKDROP_CLAMP]). */
    val tintAlpha: Float,
    /**
     * How opaque the tint is when nothing behind it is blurred or clamped: the Matte tier, and glass
     * over the live Visual until the engine frosts it (#349). Opaque enough to carry text over white.
     */
    val matteAlpha: Float,
) {
    CARD(blur = 24.dp, tintAlpha = 0.60f, matteAlpha = 0.94f),
    BAR(blur = 32.dp, tintAlpha = 0.60f, matteAlpha = 0.94f),
    SHEET(blur = 40.dp, tintAlpha = 0.65f, matteAlpha = 0.96f),
}

/** The glass material's numbers (#270, section 6.3): the same for every kind of glass. */
object GlassTokens {
    /**
     * How much of a backdrop still shows once it is clamped: the rest is the theme's base colour. The
     * clamp is what lets a thin 60% tint carry text over the brightest album art (#270, section 5.3).
     */
    const val BACKDROP_CLAMP = 0.35f

    /** The backdrop's colour saturation under glass, a little quieter than the art. */
    const val SATURATION = 0.9f

    /** How strongly the grain shows over glass and backdrops. */
    const val GRAIN_ALPHA = 0.04f

    /** The side of the square grain tile, in pixels. */
    const val GRAIN_TILE = 128

    /** The edge light: a hairline that catches the light at the top and a little at the bottom. */
    val Edge: Dp = 1.dp
    const val EDGE_TOP_ALPHA = 0.18f
    const val EDGE_MIDDLE_ALPHA = 0.04f
    const val EDGE_BOTTOM_ALPHA = 0.10f

    /**
     * The ring round a glass card or row that has D-pad or keyboard focus (#410): a state layer alone
     * barely shows on glass over a busy backdrop, so focus is drawn in the accent.
     */
    val FocusEdge: Dp = 2.dp

    /** The most live-blurred surfaces one screen may have; every still backdrop is baked instead. */
    const val LIVE_BLURS_MAX = 2

    /** How far album art is blurred when it is baked into a backdrop. */
    val ArtworkBlur: Dp = 48.dp

    /** How long glass over the live Visual takes to fade in or out. It fades; it never slides. */
    const val FADE_MILLIS = 220

    /** How long a backdrop takes to cross-fade to the next track's art. */
    const val BACKDROP_FADE_MILLIS = 600
}

/** Space inside and between glass surfaces. Screens keep generous space, so the steps grow at the large end. */
object GlassSpacing {
    val Hairline = 2.dp
    val ExtraSmall = 4.dp
    val Small = 8.dp
    val Medium = 12.dp
    val Large = 16.dp
    val ExtraLarge = 24.dp
    val Section = 32.dp
    val Screen = 48.dp
}

/** Sizes of the App UI's common components. */
object GlassSize {
    /** The least size of anything that takes a tap, whatever its shape. */
    val TapTarget = 48.dp

    /** A top bar's height, above any status-bar inset. */
    val TopBarHeight = 64.dp

    /** A list row with one line, and with a line of supporting text under it. */
    val RowOneLine = 56.dp
    val RowTwoLines = 72.dp

    /** An icon in a bar, a row or a button. */
    val Icon = 24.dp

    /** The drag handle at the top of a sheet. */
    val HandleWidth = 32.dp
    val HandleHeight = 4.dp
}

/** One place on the navigation bar: its label, which is also what accessibility reads, and its icon. */
@Immutable
data class GlassNavItem(
    val label: String,
    val icon: ImageVector,
)

/** What a notice tells the person: plain news, that something they did worked, or that it failed. */
enum class GlassTone {
    INFO,
    SUCCESS,
    ERROR,
}
