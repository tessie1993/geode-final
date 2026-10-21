package dev.geode.design.forest

import androidx.compose.ui.unit.dp

/**
 * The spacing scale of the water, forest and stone App UI. Screens stay quiet with generous space,
 * so the steps grow faster at the large end.
 */
object ForestSpacing {
    val Hairline = 2.dp
    val ExtraSmall = 4.dp
    val Small = 8.dp
    val Medium = 12.dp
    val Large = 16.dp
    val ExtraLarge = 24.dp
    val Section = 32.dp
    val Screen = 48.dp

    /** The least size of anything that takes a tap, whatever its pebble outline. */
    val TapTarget = 48.dp
}
