package dev.geode.audio

import dev.geode.audio.dsp.DspSettings
import dev.geode.audio.dsp.NativeDspProcessor

data class AudioFxBand(
    val label: String,
    val levelMb: Int,
    val minMb: Int,
    val maxMb: Int,
)

data class AudioFxState(
    val available: Boolean = false,
    val attached: Boolean = false,
    val bassAvailable: Boolean = false,
    val loudnessAvailable: Boolean = false,
    val enabled: Boolean = false,
    val bands: List<AudioFxBand> = emptyList(),
    val presets: List<String> = emptyList(),
    val presetIndex: Int = -1,
    val bassBoost: Int = 0,
    val loudness: Int = 0,
)

object AudioFxFormat {
    fun freqLabel(milliHz: Int): String {
        val hz = milliHz / 1000
        if (hz < 1000) return "$hz Hz"
        val whole = hz / 1000
        val tenth = (hz % 1000) / 100
        return if (tenth == 0) "$whole kHz" else "$whole.$tenth kHz"
    }

    fun dbLabel(mB: Int): String {
        val abs = if (mB < 0) -mB else mB
        val whole = abs / 100
        val tenth = (abs % 100) / 10
        val num = if (tenth == 0) "$whole" else "$whole.$tenth"
        return when {
            mB > 0 -> "+$num dB"
            mB < 0 -> "-$num dB"
            else -> "0 dB"
        }
    }

    fun encodeBandLevels(levels: List<Int>): String = levels.joinToString(",")

    fun decodeBandLevels(csv: String?): List<Int> =
        csv
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            .orEmpty()
}

/**
 * Drives the native equalizer chain. What it runs comes from Settings (#17), which [EngineTuning]
 * hands over as it changes; this keeps nothing of its own.
 *
 * The chain lives inside the player's processor list, so it is always "attached"; [attach] is
 * kept for the audio-session callers and only records the id.
 */
class AudioFxController(
    private val dsp: NativeDspProcessor,
) {
    private var sessionId: Int = 0

    fun attach(sessionId: Int) {
        this.sessionId = sessionId
    }

    fun release() {
        sessionId = 0
    }

    /** What the chain runs now. */
    fun current(): DspSettings = dsp.settings

    /** Runs [settings] from the next buffer on. */
    fun apply(settings: DspSettings) = dsp.update(settings)

    /** The ReplayGain / preamp stage, in decibels; owned by playback, not by the equalizer settings. */
    fun setGainDb(db: Float) = dsp.update(dsp.settings.copy(gainDb = db))

    fun setCrossfeed(enabled: Boolean) = dsp.update(dsp.settings.copy(crossfeed = enabled))

    fun setLimiter(enabled: Boolean) = dsp.update(dsp.settings.copy(limiter = enabled))
}
