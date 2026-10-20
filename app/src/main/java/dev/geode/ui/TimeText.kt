package dev.geode.ui

import dev.geode.engine.types.Millis

/** `m:ss` for display, whole seconds only; minutes run past 59 rather than rolling into hours. */
fun Millis.minutesSeconds(): String {
    val total = (value / 1000L).coerceAtLeast(0L)
    return "%d:%02d".format(total / 60L, total % 60L)
}
