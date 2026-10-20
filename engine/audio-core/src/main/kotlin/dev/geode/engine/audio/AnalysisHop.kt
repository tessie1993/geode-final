package dev.geode.engine.audio

/**
 * The analysis hop, shared by live playback and offline analysis.
 *
 * Both paths push PCM into the native session and pull one frame per hop, so a hop is a whole
 * number of samples and lasts exactly that many: dt = hop / sample rate. This mirrors the native
 * session's own rule (core/analysis/AnalysisSession.cpp: sample rate / rate, rounded down, at
 * least 1, at most one window), which is what decides the hop; it is spelled out here for the
 * sample positions the Kotlin side keeps.
 */
object AnalysisHop {
    /** Hops per second, nominally; the true rate is sampleRate / [samples]. */
    const val RATE_HZ: Float = 60f

    fun samples(
        sampleRateHz: Int,
        fftSize: Int,
    ): Int = (sampleRateHz / RATE_HZ).toInt().coerceAtLeast(1).coerceAtMost(fftSize)

    fun seconds(
        sampleRateHz: Int,
        fftSize: Int,
    ): Float = samples(sampleRateHz, fftSize).toFloat() / sampleRateHz
}
