package dev.geode.feature.customization.ui

import dev.geode.engine.types.Millis
import dev.geode.feature.customization.api.Wave
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

// The shapes the Modulator editors draw (#12): a picture of what each kind does over time, each value
// 0..1. They are App UI drawings of the settings, not the engine's live output.

/** The held values of the random wave's picture, one per step of a cycle. */
internal val RANDOM_STEPS = listOf(0.7f, 0.2f, 0.9f, 0.4f)

private const val TWO_PI = 2.0 * PI

/** The drift picture's two slow sines: their weights, the second one's rate and its phase. */
private const val DRIFT_CENTRE = 0.5f
private const val DRIFT_SLOW = 0.3f
private const val DRIFT_FAST = 0.15f
private const val DRIFT_FAST_RATE = 2.7
private const val DRIFT_FAST_PHASE = 1.0

private const val HALF = 0.5f

/** How many heard beats the beat Modulators' pictures span. */
internal const val PICTURE_BEATS = 16

/** [wave] at [phase], counted in cycles from its start. */
internal fun waveAt(
    wave: Wave,
    phase: Float,
): Float {
    val cycle = phase - floor(phase)
    return when (wave) {
        Wave.SINE -> HALF + HALF * sin(TWO_PI * cycle).toFloat()
        Wave.TRIANGLE -> 1f - abs(2f * cycle - 1f)
        Wave.SAW -> cycle
        Wave.SQUARE -> if (cycle < HALF) 1f else 0f
        Wave.RANDOM -> RANDOM_STEPS[(cycle * RANDOM_STEPS.size).toInt().coerceAtMost(RANDOM_STEPS.lastIndex)]
    }
}

/**
 * A hit envelope at [time], a fraction 0..1 of its whole length: up in a straight line over [attack],
 * then back down over [release].
 */
internal fun envelopeAt(
    attack: Millis,
    release: Millis,
    time: Float,
): Float {
    val total = (attack.value + release.value).toFloat()
    if (total <= 0f) return if (time <= 0f) 1f else 0f
    val peak = attack.value / total
    return when {
        time < peak -> time / peak
        peak >= 1f -> 1f
        else -> (1f - (time - peak) / (1f - peak)).coerceIn(0f, 1f)
    }
}

/** A smooth random drift's picture at [time], a fraction 0..1 of the drawing. */
internal fun driftAt(time: Float): Float =
    DRIFT_CENTRE +
        DRIFT_SLOW * sin(TWO_PI * time).toFloat() +
        DRIFT_FAST * sin(TWO_PI * DRIFT_FAST_RATE * time + DRIFT_FAST_PHASE).toFloat()

/** How many changes a beat Modulator waiting [everyBeats] beats has made by [time], a fraction 0..1 of the drawing. */
private fun changesAt(
    everyBeats: Int,
    time: Float,
): Int = (time.coerceIn(0f, 1f) * PICTURE_BEATS).toInt() / everyBeats.coerceAtLeast(1)

/** A toggle on the beat at [time]: low, then high and low by turns, every [everyBeats] beats. */
internal fun toggleAt(
    everyBeats: Int,
    time: Float,
): Float = if (changesAt(everyBeats, time) % 2 == 1) 1f else 0f

/** A random value on the beat at [time]: the random wave's held values, a new one every [everyBeats] beats. */
internal fun randomOnBeatAt(
    everyBeats: Int,
    time: Float,
): Float = RANDOM_STEPS[changesAt(everyBeats, time) % RANDOM_STEPS.size]
