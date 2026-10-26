package dev.geode.render

/**
 * When the frame the GL thread draws now is expected on screen, in [System.nanoTime] nanoseconds
 * (CLOCK_MONOTONIC, the clock the native audio feed reads), or 0 when nothing knows yet. The Visual
 * reads the audio heard at that moment rather than at the moment it starts drawing (#295).
 *
 * A `fun interface` returning a primitive, so asking once a frame allocates nothing.
 */
fun interface FramePresentation {
    fun expectedPresentNanos(): Long
}

/** The pacer's guess at a frame's present time, from the vsync that asked for it. */
object PresentEstimate {
    /**
     * Vsyncs between the one that asks for a frame and the one that shows it, when the platform does
     * not say: the GL thread draws and swaps within the first, the compositor latches the buffer at the
     * next and the panel shows it then.
     */
    const val PIPELINE_VSYNCS = 2L

    /** The refresh period assumed until the pacer has measured the panel's. */
    const val DEFAULT_VSYNC_NANOS = 16_666_667L

    /** A platform timeline further ahead than this is not believed. */
    const val MAX_LEAD_NANOS = 100_000_000L

    /**
     * The present time of the frame asked for at [frameTimeNanos]: [timelineNanos], the platform's own
     * expected presentation time (Choreographer's frame timeline, API 33+), when it lies ahead of the
     * vsync by a believable amount; otherwise [PIPELINE_VSYNCS] periods of [vsyncPeriodNanos] (or the
     * default period while it is unmeasured) after it.
     */
    fun expectedPresentNanos(
        frameTimeNanos: Long,
        vsyncPeriodNanos: Long,
        timelineNanos: Long,
    ): Long {
        val lead = timelineNanos - frameTimeNanos
        if (lead in 1..MAX_LEAD_NANOS) return timelineNanos
        val period = if (vsyncPeriodNanos > 0L) vsyncPeriodNanos else DEFAULT_VSYNC_NANOS
        return frameTimeNanos + PIPELINE_VSYNCS * period
    }
}
