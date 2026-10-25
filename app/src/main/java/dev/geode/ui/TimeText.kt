package dev.geode.ui

import dev.geode.engine.types.Millis

/** `m:ss` for display, whole seconds only; minutes run past 59 rather than rolling into hours. */
fun Millis.minutesSeconds(): String {
    val total = (value / 1000L).coerceAtLeast(0L)
    return "%d:%02d".format(total / 60L, total % 60L)
}

internal fun formatClock(time: Millis): String {
    val total = (time.value / 1000).coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
