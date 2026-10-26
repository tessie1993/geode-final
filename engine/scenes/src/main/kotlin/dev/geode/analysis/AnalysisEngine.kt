package dev.geode.analysis

import dev.geode.engine.audio.VisualFeed
import dev.geode.engine.bridge.NativeSnapshots

/**
 * The live analysis' tuning. The analysis itself runs natively off the Visual's audio feed
 * ([VisualFeed], core/api/Feed.hpp): it follows the source the feed listens to, at that source's rate,
 * and the Visual reads its frames on the drawing thread, so nothing here moves audio or features.
 *
 * Until Settings or a Customization tune it, the analysis starts with [START_SENSITIVITY] and
 * [START_REFRACTORY_MS], the values it has always started with.
 */
class AnalysisEngine internal constructor(
    private val target: Target,
) {
    constructor(feed: VisualFeed) : this(FeedTarget(feed))

    var attack: Float = DEFAULT_ATTACK
        set(value) {
            field = value
            pushTuning()
        }

    var decay: Float = DEFAULT_DECAY
        set(value) {
            field = value
            pushTuning()
        }

    var beatSensitivity: Float = START_SENSITIVITY
        set(value) {
            field = BeatTuning.clampSensitivity(value)
            pushTuning()
        }

    var beatMinIntervalMs: Float = START_REFRACTORY_MS
        set(value) {
            field = BeatTuning.clampIntervalMs(value)
            pushTuning()
        }

    init {
        pushTuning()
    }

    /** The analysis starts over from the next hop: a seek, a new song. */
    fun reset() = target.reset()

    /**
     * From the next hop on, the analysis picks up the Sound-signal tuning of each Customization
     * [snapshots] receives; null detaches. Detach before the snapshots are closed.
     */
    fun attachSnapshots(snapshots: NativeSnapshots?) = target.attachSnapshots(snapshots)

    private fun pushTuning() {
        target.setTuning(
            sensitivity = beatSensitivity,
            refractoryMs = beatMinIntervalMs,
            attackSeconds = BeatTuning.envelopeSeconds(attack),
            releaseSeconds = BeatTuning.envelopeSeconds(decay),
        )
    }

    /** Where the tuning goes: the feed's native analysis, or a recorder in tests. */
    internal interface Target {
        fun setTuning(
            sensitivity: Float,
            refractoryMs: Float,
            attackSeconds: Float,
            releaseSeconds: Float,
        )

        fun reset()

        fun attachSnapshots(snapshots: NativeSnapshots?)
    }

    private class FeedTarget(
        private val feed: VisualFeed,
    ) : Target {
        override fun setTuning(
            sensitivity: Float,
            refractoryMs: Float,
            attackSeconds: Float,
            releaseSeconds: Float,
        ) = feed.setTuning(sensitivity, refractoryMs, attackSeconds, releaseSeconds)

        override fun reset() = feed.resetAnalysis()

        override fun attachSnapshots(snapshots: NativeSnapshots?) = feed.attachSnapshots(snapshots)
    }

    companion object {
        const val DEFAULT_BAND_COUNT = 64

        const val DEFAULT_FFT_SIZE = 2048

        const val DEFAULT_ATTACK = 0.6f
        const val DEFAULT_DECAY = 0.12f

        /** The beat sensitivity the live analysis starts with, before anything tunes it. */
        const val START_SENSITIVITY = 3f

        /** The shortest gap between beats the live analysis starts with, before anything tunes it. */
        const val START_REFRACTORY_MS = 60f
    }
}
