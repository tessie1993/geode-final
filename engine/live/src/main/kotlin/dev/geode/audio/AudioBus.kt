package dev.geode.audio

import dev.geode.analysis.AudioFeatures

/**
 * The live analysis' newest features, for every Visual to read on its own thread: the app's renderer
 * and the live wallpaper's each pick up [features] once per frame. The analysis publishes here from
 * its own loop, and runs only while someone is interested ([addConsumer], [removeConsumer]).
 *
 * Features older than [STALE_MS] read as none: a player that stopped feeding the analysis leaves the
 * Visual moving on the Style's own time, never on stale sound.
 */
object AudioBus {
    private const val STALE_MS = 1_500L

    @Volatile
    private var latest: AudioFeatures? = null

    @Volatile
    private var latestAtMs: Long = 0L

    fun publish(features: AudioFeatures) {
        latest = features
        latestAtMs = android.os.SystemClock.elapsedRealtime()
    }

    fun features(): AudioFeatures? {
        val f = latest ?: return null
        return if (android.os.SystemClock.elapsedRealtime() - latestAtMs > STALE_MS) null else f
    }

    val isLive: Boolean get() = features() != null

    fun clear() {
        latest = null
        latestAtMs = 0L
    }

    private val consumers =
        java.util.concurrent.atomic
            .AtomicInteger(0)

    /** Called when the first consumer arrives or the last one leaves, on the caller's thread. */
    @Volatile
    var onInterestChanged: (() -> Unit)? = null

    val hasConsumers: Boolean get() = consumers.get() > 0

    fun addConsumer() {
        if (consumers.incrementAndGet() == 1) onInterestChanged?.invoke()
    }

    fun removeConsumer() {
        if (consumers.decrementAndGet() == 0) onInterestChanged?.invoke()
    }
}
