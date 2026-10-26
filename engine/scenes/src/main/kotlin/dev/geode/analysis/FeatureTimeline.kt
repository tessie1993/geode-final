package dev.geode.analysis

import dev.geode.engine.audio.PulseReplay

data class TimelineFrame(
    val timeMs: Long,
    val features: AudioFeatures,
)

class FeatureTimeline(
    val frames: List<TimelineFrame>,
    val hopMs: Long,
    val key: String = "",
    val hopRateHz: Float = 60f,
) {
    val durationMs: Long = frames.lastOrNull()?.timeMs ?: 0L
    val averageEnergy: Float = if (frames.isEmpty()) 0f else frames.map { it.features.rms }.average().toFloat()
    val averageCentroid: Float = if (frames.isEmpty()) 0f else frames.map { it.features.centroid }.average().toFloat()

    /**
     * The track's settled tempo (#302): the one its Song map lays the beat grid at, not wherever the
     * last hop's tempo had drifted to by the fade-out. 0 when no tempo was heard.
     */
    val bpm: Float = SongMap.tempoOf(frames) ?: 0f
    val beatDensity: Float =
        if (frames.isEmpty()) 0f else frames.count { it.features.beat } / (frames.size / 60f + 1e-6f)

    fun withBeatSensitivity(
        beatSensitivity: Float,
        beatMinIntervalMs: Float,
    ): FeatureTimeline {
        if (frames.isEmpty()) return this
        val flux = FloatArray(frames.size) { frames[it].features.flux }
        if (flux.none { it > 0f }) return this
        val rms = FloatArray(frames.size) { frames[it].features.rms }
        val pulse = PulseReplay.decide(flux, rms, hopRateHz, beatSensitivity, beatMinIntervalMs)
        val out = ArrayList<TimelineFrame>(frames.size)
        var anyChanged = false
        for (i in frames.indices) {
            val fr = frames[i]
            val f = fr.features
            val unchanged =
                f.beat == pulse.beat[i] &&
                    f.beatStrength == pulse.strength[i] &&
                    f.transient == pulse.transient[i] &&
                    f.beatPhase == pulse.phase[i] &&
                    f.pulseConfidence == pulse.confidence[i] &&
                    f.macroEnergy == pulse.energy[i]
            out +=
                if (unchanged) {
                    fr
                } else {
                    anyChanged = true
                    fr.copy(
                        features =
                            f.copy(
                                beat = pulse.beat[i],
                                beatStrength = pulse.strength[i],
                                transient = pulse.transient[i],
                                beatPhase = pulse.phase[i],
                                pulseConfidence = pulse.confidence[i],
                                macroEnergy = pulse.energy[i],
                            ),
                    )
                }
        }
        if (!anyChanged) return this
        return FeatureTimeline(out, hopMs, key, hopRateHz)
    }

    /**
     * The frame timed nearest [timeMs], the later one on a tie. Frames are stamped at the middle of
     * their window (#302), so the first is not at 0 and the moment is found by time, not by counting
     * hops from the start, which would draw the levels half a window early.
     */
    private fun indexAt(timeMs: Long): Int {
        val after = framesUpTo(timeMs)
        if (after == 0) return 0
        if (after == frames.size) return frames.size - 1
        val before = after - 1
        return if (timeMs - frames[before].timeMs < frames[after].timeMs - timeMs) before else after
    }

    /**
     * The features to draw at [timeMs]. With a [spanMs], the span is one rendered frame: the
     * continuous values are those at [timeMs], and every one-hop event of the frames timed in
     * (timeMs, timeMs + spanMs] is folded in (flags or'ed, strengths and onset/flux maxed). Spans of
     * consecutive frames tile the timeline, so each event is drawn on exactly one rendered frame
     * at any frame rate.
     */
    fun featuresAt(
        timeMs: Long,
        spanMs: Long = 0L,
    ): AudioFeatures {
        if (spanMs <= 0L) return if (frames.isEmpty()) AudioFeatures.empty() else frames[indexAt(timeMs)].features
        return featuresAround(timeMs, timeMs, timeMs + spanMs)
    }

    /**
     * The features to draw at [timeMs] with every one-hop event of the frames timed in (fromMs, toMs]
     * folded in. A Render centres that window on each frame (#302): an event is drawn on the frame
     * nearest the moment it is heard, never a frame early, and windows that share their edges tile
     * the timeline, so each event is drawn exactly once.
     */
    fun featuresAround(
        timeMs: Long,
        fromMs: Long,
        toMs: Long,
    ): AudioFeatures {
        if (frames.isEmpty()) return AudioFeatures.empty()
        var folded = frames[indexAt(timeMs)].features.withoutEvents()
        for (i in framesUpTo(fromMs) until framesUpTo(toMs)) {
            folded = folded.foldedWith(frames[i].features)
        }
        return folded
    }

    /** How many frames are timed at or before [timeMs]. */
    private fun framesUpTo(timeMs: Long): Int {
        var lo = 0
        var hi = frames.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (frames[mid].timeMs <= timeMs) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
