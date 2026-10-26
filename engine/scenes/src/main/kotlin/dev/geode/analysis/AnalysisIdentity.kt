package dev.geode.analysis

import dev.geode.engine.audio.AnalysisHop

object AnalysisIdentity {
    // 3: frames are stamped at the middle of their window (#302).
    // 4: hi-res files are decimated to 48 kHz or less and decoded as float before analysis (#299).
    const val ALGORITHM_VERSION: Int = 4

    val CURRENT: String =
        "alg=$ALGORITHM_VERSION" +
            "|fft=${AnalysisEngine.DEFAULT_FFT_SIZE}" +
            "|bands=${AnalysisEngine.DEFAULT_BAND_COUNT}" +
            "|hopHz=${AnalysisHop.RATE_HZ}"
}
