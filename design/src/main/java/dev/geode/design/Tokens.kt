package dev.geode.design

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Sizes of the App UI's common components. */
object CrystalSize {
    /** A one-pixel-ish rule: panel borders, tab dividers. */
    val Hairline = 1.dp

    val ButtonMinHeight = 48.dp
    val CompactButtonMinHeight = 36.dp
    val SegmentMinHeight = 40.dp
    val PlayButton = 60.dp
    val SliderThumb = 24.dp
    val SliderTrack = 14.dp
    val SliderTickRadius = 1.5.dp
    val SliderFillStroke = 3.5.dp
    val SliderFillGlowStroke = 8.dp
    val NavBarHeight = 68.dp
    val Gem = 7.dp
    val TabGem = 5.dp
}

/** Space inside and between the App UI's common components. */
object CrystalSpacing {
    val ButtonHorizontal = 22.dp
    val ButtonVertical = 10.dp
    val CompactButtonHorizontal = 16.dp
    val CompactButtonVertical = 6.dp
    val SegmentGap = 8.dp
    val SegmentHorizontal = 10.dp
    val SegmentVertical = 8.dp
    val TabEdge = 8.dp
    val GemToLabel = 3.dp
    val IconToLabel = 2.dp
}

/** Corner radii of the App UI's surfaces. */
object CrystalRadius {
    /** The default corner of a [crystalPanel]. */
    val Panel = 18.dp

    /** The long cut of a [crystalShardShape]. */
    val ShardCut = 12.dp

    /** The short cut of a [crystalShardShape]. */
    val ShardMinor = 4.dp
}

/** Glow radii of the App UI. */
object CrystalGlow {
    /** The default reach of [softGlow] beyond the element. */
    val Soft = 16.dp

    /** The glow around the play button. */
    val PlayButton = 14.dp

    /** How strongly the play button glows, as a multiple of a full [softGlow]. */
    const val PLAY_BUTTON_STRENGTH = 0.5f

    /** The blur of a [GlowTitle]'s glow, in pixels. */
    const val TITLE_BLUR = 28f
}

/** Letter spacing of the App UI's spaced-out labels. */
object CrystalLetterSpacing {
    val Overline = 2.6.sp
    val Button = 0.8.sp
    val NavLabel = 1.2.sp
}

/** How opaque the App UI draws content in each state, as a fraction of full. */
object CrystalAlpha {
    /** The label and icon of a disabled control. */
    const val DISABLED_CONTENT = 0.55f

    /** The label of an unselected segment or navigation item. */
    const val UNSELECTED_CONTENT = 0.75f

    /** The title of an unselected tab. */
    const val UNSELECTED_TAB = 0.7f

    /** A disabled slider's fill and ticks. */
    const val DISABLED_SLIDER = 0.4f

    /** A slider's step ticks. */
    const val SLIDER_TICK = 0.30f

    /** The glow under a slider's fill. */
    const val SLIDER_FILL_GLOW = 0.20f

    /** The tab row's divider. */
    const val TAB_DIVIDER = 0.45f

    /** An overline's text. */
    const val OVERLINE = 0.9f

    /** A [GlowTitle]'s glow. */
    const val TITLE_GLOW = 0.8f

    /** The navigation bar at the least opacity the person can choose. */
    const val NAV_BAR_MIN = 0.55f

    /** How much more opaque the navigation bar gets at the most opacity the person can choose. */
    const val NAV_BAR_RANGE = 0.45f

    /** The tint over the ambient art behind the App UI. */
    const val BACKGROUND_TINT = 0.16f
}
