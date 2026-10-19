package dev.geode.ui

/**
 * Pure state transition for setting an A-B playback loop.
 * The first press sets A, the next valid press sets B, and a third press clears both.
 * An invalid B press restarts A at the current position.
 */
internal object AbLoopReducer {
    const val MIN_LOOP_MS = 1_000L

    fun advance(current: AbLoop?, positionMs: Long): AbLoop? {
        val at = positionMs.coerceAtLeast(0L)
        return when {
            current == null -> AbLoop(at)
            current.endMs == null && at > current.startMs + MIN_LOOP_MS -> current.copy(endMs = at)
            current.endMs == null -> AbLoop(at)
            else -> null
        }
    }
}
