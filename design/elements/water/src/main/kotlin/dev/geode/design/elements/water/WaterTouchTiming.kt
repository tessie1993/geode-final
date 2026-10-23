package dev.geode.design.elements.water

/**
 * The timing of Water's touch on a pebble (#154, #105): an aqua ink bloom spreads from the finger
 * and fades over [BLOOM_MS], while a ripple ring runs out ahead of it over [RING_MS]. Under reduced
 * motion nothing spreads or travels: the bloom shows at full size and fades in place over
 * [REDUCED_FADE_MS].
 *
 * Everything is a plain function of the run's progress, 0 at the press and 1 at the end, so the
 * shared contract (#153) can drive it from any clock.
 */
object WaterTouchTiming {
    const val BLOOM_MS = 600
    const val RING_MS = 450
    const val REDUCED_FADE_MS = 150

    /** How big the bloom starts, as a share of its full size. */
    const val BLOOM_START = 0.2f

    private const val SINK_DP = 1f
    private const val DEEP_SINK_DP = 2f

    /** How long the touch runs. */
    fun runMs(reducedMotion: Boolean): Int = if (reducedMotion) REDUCED_FADE_MS else BLOOM_MS

    /** The run's progress [elapsedMs] after the press, between 0 and 1. */
    fun progressAt(
        elapsedMs: Long,
        reducedMotion: Boolean,
    ): Float = (elapsedMs.toFloat() / runMs(reducedMotion)).coerceIn(0f, 1f)

    /** The bloom's size as a share of its full size: a fast start and a soft end, or full at once under reduced motion. */
    fun bloomSpread(
        progress: Float,
        reducedMotion: Boolean,
    ): Float = if (reducedMotion) 1f else BLOOM_START + (1f - BLOOM_START) * easeOut(progress)

    /** The bloom's opacity, and how brightly the pebble is lit from within: it fades to nothing over the run. */
    fun bloomAlpha(progress: Float): Float = 1f - progress.coerceIn(0f, 1f)

    /** How far the ring has run, as a share of the bloom's full size. It starts with the bloom and stays ahead of it. */
    fun ringTravel(progress: Float): Float = BLOOM_START + (1f - BLOOM_START) * easeOut(ringProgress(progress))

    /** The ring's opacity: it fades as it runs, and there is no ring under reduced motion. */
    fun ringAlpha(
        progress: Float,
        reducedMotion: Boolean,
    ): Float = if (reducedMotion) 0f else 1f - ringProgress(progress)

    /** How far a pressed pebble sinks into the water: 1 dp, 2 dp for the primary pebble, none under reduced motion. */
    fun sinkDp(
        pressed: Boolean,
        deep: Boolean,
        reducedMotion: Boolean,
    ): Float =
        when {
            !pressed || reducedMotion -> 0f
            deep -> DEEP_SINK_DP
            else -> SINK_DP
        }

    /** Whether the run is over, so nothing is left to draw. */
    fun isFinished(progress: Float): Boolean = progress >= 1f

    private fun ringProgress(progress: Float): Float = (progress * BLOOM_MS / RING_MS).coerceIn(0f, 1f)

    /** A cubic ease-out: fast at the start, soft at the end. */
    private fun easeOut(t: Float): Float {
        val left = 1f - t.coerceIn(0f, 1f)
        return 1f - left * left * left
    }
}
