package dev.geode.design.theme

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View

enum class StoneHapticCue(
    private val minApi: Int,
    private val constant: Int,
    private val fallbackMs: LongArray,
) {
    TAP(minApi = 27, constant = HapticFeedbackConstants.TEXT_HANDLE_MOVE, fallbackMs = longArrayOf(8)),

    CONFIRM(minApi = 30, constant = HapticFeedbackConstants.CONFIRM, fallbackMs = longArrayOf(12, 34, 8)),

    DESTRUCTIVE(minApi = 30, constant = HapticFeedbackConstants.REJECT, fallbackMs = longArrayOf(18, 28, 18)),

    SLIDER_TICK(minApi = 34, constant = HapticFeedbackConstants.SEGMENT_FREQUENT_TICK, fallbackMs = longArrayOf(4)),

    /** A part was selected. Silent unless the pack has its own pattern. */
    SELECT(minApi = PACK_ONLY, constant = 0, fallbackMs = longArrayOf()),

    /** A toggle turned on. Silent unless the pack has its own pattern. */
    TOGGLE_ON(minApi = PACK_ONLY, constant = 0, fallbackMs = longArrayOf()),

    /** A toggle turned off. Silent unless the pack has its own pattern. */
    TOGGLE_OFF(minApi = PACK_ONLY, constant = 0, fallbackMs = longArrayOf()),
    ;

    internal fun isAvailable(): Boolean = Build.VERSION.SDK_INT >= minApi

    internal fun isPackOnly(): Boolean = fallbackMs.isEmpty()

    internal fun platformConstant(): Int = constant

    internal fun waveform(): VibrationEffect = VibrationEffect.createWaveform(longArrayOf(0) + fallbackMs, -1)
}

private const val PACK_ONLY = Int.MAX_VALUE

/** One `VibrationEffect.Composition` primitive at [scale], [delayMs] after the one before it ends. */
data class HapticPrimitive(
    val id: Int,
    val scale: Float,
    val delayMs: Int = 0,
)

/**
 * A pack's own haptic: its Composition [primitives], or, on a device without them, the waveform of
 * off/on [timings] (ms) and [amplitudes] (0-255).
 */
data class StoneHapticPattern(
    val primitives: List<HapticPrimitive>,
    val timings: List<Long>,
    val amplitudes: List<Int>,
) {
    internal fun effect(vibrator: Vibrator): VibrationEffect {
        val ids = primitives.map { it.id }.toIntArray()
        if (vibrator.areAllPrimitivesSupported(*ids)) {
            val composition = VibrationEffect.startComposition()
            primitives.forEach { composition.addPrimitive(it.id, it.scale, it.delayMs) }
            return composition.compose()
        }
        return VibrationEffect.createWaveform(timings.toLongArray(), amplitudes.toIntArray(), -1)
    }
}

/** Plays [cue] as [pack]'s own pattern when it has one, else as the platform's. */
fun View.performStoneHaptic(
    cue: StoneHapticCue,
    pack: ThemePack? = null,
) {
    val pattern = pack?.haptics?.get(cue)
    if (pattern == null && cue.isAvailable()) {
        performHapticFeedback(cue.platformConstant())
        return
    }
    if (pattern == null && cue.isPackOnly()) return
    val vibrator = context.getSystemService(Vibrator::class.java) ?: return
    if (vibrator.hasVibrator()) vibrator.vibrate(pattern?.effect(vibrator) ?: cue.waveform())
}
