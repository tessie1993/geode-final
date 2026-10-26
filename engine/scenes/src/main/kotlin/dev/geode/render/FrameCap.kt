package dev.geode.render

/**
 * How a thermal frame cap combines with the rate a pacer was asked for (#286).
 *
 * Both are frames per second where 0 means "no limit": a free-running pacer under a 30 fps cap
 * draws at 30, a 60 fps pacer under it draws at 30, and with no cap the pacer's own rate stands.
 * The cap only ever lowers the rate; it never raises a pacer that asked for less.
 */
object FrameCap {
    fun effectiveFps(
        requestedFps: Float,
        capFps: Float,
    ): Float =
        when {
            capFps <= 0f -> requestedFps
            requestedFps <= 0f -> capFps
            else -> minOf(requestedFps, capFps)
        }

    /** The time one frame has at [fps] (0 or less: the default 60), in nanoseconds: the hint session's target. */
    fun frameBudgetNanos(fps: Float): Long = (NANOS_PER_SECOND / (if (fps > 0f) fps else FramePacer.DEFAULT_TARGET_FPS)).toLong()

    private const val NANOS_PER_SECOND = 1_000_000_000.0
}
