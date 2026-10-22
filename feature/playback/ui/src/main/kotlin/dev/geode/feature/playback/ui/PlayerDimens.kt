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

    // The seek waveform (#147), as the old player drew it.
    val waveformHeight = 40.dp
    val waveformLine = 3.dp
    val playhead = 2.dp

    // The lyrics (#147).
    val lyricsPaddingY = 12.dp
    val lyricsGap = 10.dp

    // The app-wide error banner (#147).
    val bannerMarginX = 12.dp
    val bannerMarginY = 8.dp
    val bannerCorner = 14.dp
    val bannerPaddingX = 14.dp
    val bannerPaddingY = 12.dp
}

/** How much of each bar's slot the waveform's bar fills. */
internal const val BAR_FILL = 0.62f

/** The height of the quietest bar, as a share of the waveform's height; the loudest fills it. */
internal const val BAR_FLOOR = 0.08f

/** How faint the part of the waveform still to play is, the A-B loop's shading and the playhead. */
internal const val UNPLAYED_ALPHA = 0.45f
internal const val LOOP_ALPHA = 0.28f
internal const val PLAYHEAD_ALPHA = 0.85f

/** How faint lyric lines are: already sung, still to come, and untimed. */
internal const val SUNG_ALPHA = 0.35f
internal const val COMING_ALPHA = 0.65f
internal const val PLAIN_ALPHA = 0.85f

/** How far below the top the line being sung is scrolled to, in pixels. */
internal const val LYRICS_LEAD_PX = 160

/** How long after the user scrolls the lyrics they follow the music again. */
internal val LYRICS_FOLLOW_AGAIN = Millis(5_000)

/** How far through [position] is of [duration], from 0 to 1. */
internal fun fraction(
    position: Millis,
    duration: Millis,
): Float = if (duration.value <= 0) 0f else (position.value.toFloat() / duration.value).coerceIn(0f, 1f)
