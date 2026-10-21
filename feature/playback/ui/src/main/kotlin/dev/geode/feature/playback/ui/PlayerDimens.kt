package dev.geode.feature.playback.ui

import androidx.compose.ui.unit.dp
import dev.geode.engine.types.Millis

/** Sizes of the player screens, in one place, until the App UI's tokens move to `:design`. */
internal object PlayerDimens {
    val gutter = 24.dp
    val gap = 16.dp
    val small = 8.dp
    val artworkMaxWidth = 360.dp
    val artworkCorner = 24.dp
    val artworkIcon = 96.dp
    val mainButton = 72.dp
    val mainIcon = 40.dp
    val sideIcon = 32.dp
    val miniGutter = 12.dp
    val miniIcon = 28.dp
    val miniProgress = 2.dp
}

/** How far through [position] is of [duration], from 0 to 1. */
internal fun fraction(
    position: Millis,
    duration: Millis,
): Float = if (duration.value <= 0) 0f else (position.value.toFloat() / duration.value).coerceIn(0f, 1f)
