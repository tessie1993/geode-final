package dev.geode.feature.customization.ui

import androidx.compose.ui.unit.dp
import dev.geode.design.glass.GlassSize
import dev.geode.design.glass.GlassSpacing

/** The sizes of the Customize panel, on top of the design system's spacing (GlassSpacing). */
internal object CustomizeDimens {
    /** A Macro knob: large, as the basic view's main control. */
    val knob = GlassSize.TapTarget + GlassSpacing.Section
    val knobStroke = GlassSpacing.Small
    val liveStroke = GlassSpacing.ExtraSmall

    /** The right-hand panel in landscape and on tablets, so the Visual keeps its full height. */
    val sidePanelWidth = 400.dp

    val handleWidth = GlassSpacing.Section + GlassSpacing.ExtraSmall
    val handleHeight = GlassSpacing.ExtraSmall

    /** How far a link must be swiped before it is removed. */
    val swipeThreshold = 96.dp

    /** One row of the patch-cable board, and the width of its label columns. */
    val boardRow = GlassSize.TapTarget
    val boardLabel = 128.dp
    val boardCable = 2.dp
    val boardNode = 5.dp

    /** A Modulator's picture. */
    val picture = GlassSize.TapTarget + GlassSpacing.Medium
    val pictureStroke = 2.dp

    /** The bar of a link's live meter. */
    val meter = GlassSpacing.ExtraSmall
}

/** In portrait, the basic view's sheet stays below this share of the screen, so the Visual shows above it. */
internal const val BASIC_SHEET_FRACTION = 0.62f

/** The Advanced view's sheet, at (nearly) full height. */
internal const val FULL_SHEET_FRACTION = 0.94f

/** The knob's ring: an open circle of this many degrees, starting at the bottom left. */
internal const val KNOB_SWEEP = 270f
internal const val KNOB_START = 135f

/** The longest rise and fall the editor offers, in milliseconds. */
internal const val MAX_RESPONSE_MILLIS = 2_000

/** How faint a control the current Style does not use is drawn (#365). */
internal const val IGNORED_ALPHA = 0.38f

/** How dim the board's loop cables and the knob's empty track are drawn. */
internal const val QUIET_ALPHA = 0.35f

/** The dash of a loop cable on the board, in pixels: drawn, then gap. */
internal val LOOP_DASH = floatArrayOf(12f, 10f)
