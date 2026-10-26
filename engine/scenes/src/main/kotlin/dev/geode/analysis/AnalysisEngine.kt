package dev.geode.analysis

import dev.geode.engine.audio.VisualFeed
import dev.geode.engine.bridge.NativeSnapshots

/**
 * The live analysis' tuning. The analysis itself runs natively off the Visual's audio feed
 * ([VisualFeed], core/api/Feed.hpp): it follows the source the feed listens to, at that source's rate,
 * and the Visual reads its frames on the drawing thread, so nothing here moves audio or features.
 */
class AnalysisEngine(
    private val feed: VisualFeed,
) {
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

    var beatSensitivity: Float = BeatTuning.SENSITIVITY_DEFAULT
        set(value) {
            field = BeatTuning.clampSensitivity(value)
            pushTuning()
        }

    var beatMinIntervalMs: Float = BeatTuning.INTERVAL_MS_DEFAULT
        set(value) {
            field = BeatTuning.clampIntervalMs(value)
            pushTuning()
        }

    init {
        pushTuning()
    }

    /** The analysis starts over from the next hop: a seek, a new song. */
    fun reset() = feed.resetAnalysis()

    /**
     * From the next hop on, the analysis picks up the Sound-signal tuning of each Customization
     * [snapshots] receives; null detaches. Detach before the snapshots are closed.
     */
    fun attachSnapshots(snapshots: NativeSnapshots?) = feed.attachSnapshots(snapshots)

    private fun pushTuning() {
        feed.setTuning(
            sensitivity = beatSensitivity,
            refractoryMs = beatMinIntervalMs,
            attackSeconds = BeatTuning.envelopeSeconds(attack),
            releaseSeconds = BeatTuning.envelopeSeconds(decay),
        )
    }

    companion object {
        const val DEFAULT_BAND_COUNT = 64

        const val DEFAULT_FFT_SIZE = 2048

        const val DEFAULT_ATTACK = 0.6f
        const val DEFAULT_DECAY = 0.12f
    }
}
