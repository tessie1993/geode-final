package dev.geode.analysis

/**
 * What a whole-track analysis keeps (#345). [LIVE] is what the old editors and previews read: every hop
 * with its 128-point waveform, halving the rate past [FrameAccumulator.MAX_OFFLINE_FRAMES] (30 minutes)
 * to stay within memory. [RENDER] is a Render's: a Render hands its scenes the real sound every frame
 * (#342), so it keeps no waveform, and in the memory that frees it keeps every hop for twice as long, a
 * little over an hour at the full rate, before halving as the live profile does. [tag] keeps their cache
 * entries apart.
 */
enum class AnalysisProfile(
    val maxFrames: Int,
    val keepsWaveform: Boolean,
    val tag: String,
) {
    LIVE(FrameAccumulator.MAX_OFFLINE_FRAMES, true, ""),
    RENDER(RENDER_MAX_FRAMES, false, "|profile=render"),
}

/** A little over an hour of hops at 60 Hz: an hour-long track is analysed at the full rate throughout. */
private const val RENDER_MAX_FRAMES = 220_000
