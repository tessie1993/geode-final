package dev.geode.feature.studio.core

/**
 * How long the running Render has left, from how fast its progress moved over the last
 * [windowSeconds] (the old export's estimate, moved here with tests). It says nothing until it has
 * watched a whole window and seen at least [minProgress] of the Render go by, so the first frames
 * never promise a time. The caller hands in the time of each sample: no clock is read here.
 */
class RenderEta(
    private val windowSeconds: Float = DEFAULT_WINDOW_SECONDS,
    private val minProgress: Float = DEFAULT_MIN_PROGRESS,
) {
    private var firstSampleMs = 0L
    private var firstSampleProgress = 0f
    private var latestProgress = 0f
    private var started = false

    /** The seconds left after [progress] (0..1) was reached at [atMs], or null while unknown. */
    fun sample(
        progress: Float,
        atMs: Long,
    ): Long? {
        val clamped = progress.coerceIn(0f, 1f)
        if (!started) {
            started = true
            firstSampleMs = atMs
            firstSampleProgress = clamped
            latestProgress = clamped
            return null
        }
        if (clamped < latestProgress) {
            // Progress went back (a new phase restarted it): measure afresh from here.
            firstSampleMs = atMs
            firstSampleProgress = clamped
        }
        latestProgress = clamped
        val windowMs = (windowSeconds * MILLIS_PER_SECOND).toLong()
        if (atMs - firstSampleMs > windowMs * 2) {
            // Slide the window: keep the rate measured so far, over one window's length.
            val carried = (latestProgress - firstSampleProgress) * windowMs / (atMs - firstSampleMs).toFloat()
            firstSampleMs = atMs - windowMs
            firstSampleProgress = latestProgress - carried
        }
        val elapsedMs = atMs - firstSampleMs
        val gained = latestProgress - firstSampleProgress
        if (elapsedMs < windowMs || gained < minProgress) return null
        val msPerUnit = elapsedMs / gained
        return ((1f - latestProgress) * msPerUnit / MILLIS_PER_SECOND).toLong()
    }

    private companion object {
        const val DEFAULT_WINDOW_SECONDS = 12f
        const val DEFAULT_MIN_PROGRESS = 0.02f
        const val MILLIS_PER_SECOND = 1000f
    }
}
