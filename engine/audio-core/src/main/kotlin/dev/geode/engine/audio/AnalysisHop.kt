package dev.geode.engine.audio

/**
 * The analysis hop, shared by live playback and offline analysis.
 *
 * Both paths push PCM into the native session and pull one frame per hop, so a hop is a whole
 * number of samples and lasts exactly that many: dt = hop / sample rate. This mirrors the native
 * session's own rule (core/analysis/AnalysisSession.cpp: sample rate / rate, rounded down, at
 * least 1, at most one window), which is what decides the hop; it is spelled out here for the
 * sample positions the Kotlin side keeps.
 *
 * A source above [MAX_ANALYSIS_RATE_HZ] is decimated by a whole factor ([decimation]) before it is
 * analysed (core/analysis/Decimator.hpp), so its hop and window, counted in its own frames, are that
 * many times longer: the same 1/60 s and the same span of audio as at 48 kHz.
 */
object AnalysisHop {
    /** Hops per second, nominally; the true rate is sampleRate / [samples]. */
    const val RATE_HZ: Float = 60f

    /** The highest rate the analysis runs at (kMaxAnalysisRateHz). */
    const val MAX_ANALYSIS_RATE_HZ: Int = 48_000

    /** The whole factor a source at [sampleRateHz] is divided by before it is analysed: 1 up to 48 kHz. */
    fun decimation(sampleRateHz: Int): Int =
        if (sampleRateHz <= MAX_ANALYSIS_RATE_HZ) 1 else (sampleRateHz + MAX_ANALYSIS_RATE_HZ - 1) / MAX_ANALYSIS_RATE_HZ

    /** One hop, in frames of a source at [sampleRateHz]. */
    fun samples(
        sampleRateHz: Int,
        fftSize: Int,
    ): Int {
        val factor = decimation(sampleRateHz)
        return (sampleRateHz / factor / RATE_HZ).toInt().coerceAtLeast(1).coerceAtMost(fftSize) * factor
    }

    fun seconds(
        sampleRateHz: Int,
        fftSize: Int,
    ): Float = samples(sampleRateHz, fftSize).toFloat() / sampleRateHz

    /** One analysis window, in frames of a source at [sampleRateHz]. */
    fun windowFrames(
        sampleRateHz: Int,
        fftSize: Int,
    ): Int = fftSize * decimation(sampleRateHz)
}
