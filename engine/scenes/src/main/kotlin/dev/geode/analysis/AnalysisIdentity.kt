package dev.geode.analysis

import dev.geode.engine.audio.AnalysisHop

object AnalysisIdentity {
    // 3: frames are stamped at the middle of their window (#302).
    const val ALGORITHM_VERSION: Int = 3

    val CURRENT: String =
        "alg=$ALGORITHM_VERSION" +
            "|fft=${AnalysisEngine.DEFAULT_FFT_SIZE}" +
            "|bands=${AnalysisEngine.DEFAULT_BAND_COUNT}" +
            "|hopHz=${AnalysisHop.RATE_HZ}"
}
