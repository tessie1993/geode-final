package dev.geode.feature.studio.core

import dev.geode.feature.studio.api.RenderLoudness
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The level a platform plays back at and the headroom it wants (the old export's targets, moved here
 * with tests). [lowLufs]..[highLufs] counts as already right; [aimLufs] is where a levelling gain
 * lands; the true peak stays under [ceilingDbtp].
 */
data class LoudnessWindow(
    val aimLufs: Double,
    val lowLufs: Double,
    val highLufs: Double,
    val ceilingDbtp: Double,
)

/**
 * The gain a Render's sound needs to reach its loudness target (#26 applies it; a Render carries the
 * target, #338). Nothing here measures: the caller hands in the integrated loudness and true peak.
 */
object Loudness {
    /** A gain smaller than this cannot be heard, so the sound counts as on target. */
    const val GAIN_TOLERANCE_DB: Double = 0.1

    /** A peak this little over the ceiling is rounding, not clipping. */
    const val PEAK_TOLERANCE_DB: Double = 0.05

    private const val CEILING_DBTP = -1.0

    private val streaming = LoudnessWindow(aimLufs = -14.0, lowLufs = -15.0, highLufs = -13.0, ceilingDbtp = CEILING_DBTP)
    private val shortForm = LoudnessWindow(aimLufs = -11.0, lowLufs = -12.0, highLufs = -10.0, ceilingDbtp = CEILING_DBTP)

    /** [target]'s window, or null when the sound is left as mixed. */
    fun windowOf(target: RenderLoudness): LoudnessWindow? =
        when (target) {
            RenderLoudness.LEAVE_AS_IS -> null
            RenderLoudness.MINUS_14_LUFS -> streaming
            RenderLoudness.MINUS_11_LUFS -> shortForm
        }

    /**
     * The gain in dB that brings sound measured at [lufs] with a true peak of [truePeakDbtp] to [target]:
     * towards the aim, but never pushing the peak past the ceiling, and pulling a peak over it down even
     * when the level is right. 0 when it is already on target or left as mixed.
     */
    fun gainDb(
        target: RenderLoudness,
        lufs: Double,
        truePeakDbtp: Double,
    ): Double {
        val window = windowOf(target) ?: return 0.0
        val gain = minOf(window.aimLufs - lufs, window.ceilingDbtp - truePeakDbtp)
        val inBand = lufs >= window.lowLufs && lufs <= window.highLufs
        val peakSafe = truePeakDbtp <= window.ceilingDbtp + PEAK_TOLERANCE_DB
        if (inBand && peakSafe) return 0.0
        return if (abs(gain) < GAIN_TOLERANCE_DB) 0.0 else gain
    }

    /** [gainDb] as the factor a sample is multiplied by. */
    fun factorOf(gainDb: Double): Double = BASE.pow(gainDb / DB_PER_DECADE)

    /** A 16-bit [sample] times [factor], held to the 16-bit range so a raised peak clips rather than wraps. */
    fun scaled(
        sample: Short,
        factor: Double,
    ): Short = (sample * factor).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    private const val BASE = 10.0
    private const val DB_PER_DECADE = 20.0
}
