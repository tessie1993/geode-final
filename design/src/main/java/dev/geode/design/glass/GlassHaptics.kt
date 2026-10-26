package dev.geode.design.glass

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The App UI's one light set of haptics (#398): a press, a toggle and the ticks of a value being
 * dragged. There are no UI sounds. They go through the view's own touch feedback, so they follow
 * Android's touch-feedback setting: off there, they are off here too, and they need no permission.
 * Provided by [GlassTheme] through [LocalGlassHaptics]; outside a theme they are silent.
 */
@Stable
class GlassHaptics internal constructor(
    private val view: View?,
) {
    /** A button, a card, a chip or a place on the Bar pressed. */
    fun press() {
        view?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /** A switch turned [on] or off. */
    fun toggle(on: Boolean) {
        val feedback =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
            } else {
                HapticFeedbackConstants.VIRTUAL_KEY
            }
        view?.performHapticFeedback(feedback)
    }

    /** A dragged value passing one of its ticks ([GlassTicks]). */
    fun tick() {
        val feedback =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
            } else {
                HapticFeedbackConstants.CLOCK_TICK
            }
        view?.performHapticFeedback(feedback)
    }

    companion object {
        /** Haptics that never play, for previews and anything drawn outside [GlassTheme]. */
        val Silent = GlassHaptics(null)
    }
}

/** The haptics in use. */
val LocalGlassHaptics = staticCompositionLocalOf { GlassHaptics.Silent }

/** Haptics played through the view this composition is drawn in. */
@Composable
fun rememberGlassHaptics(): GlassHaptics {
    val view = LocalView.current
    return remember(view) { GlassHaptics(view) }
}

/**
 * Where a dragged value ticks: at each of a slider's [steps] stops when it has them, otherwise every
 * twentieth of its range, so a long drag ticks gently rather than buzzing.
 */
object GlassTicks {
    /** How many ticks a slider without stops has across its range. */
    const val CONTINUOUS_TICKS = 20

    /** Which tick [value] is at in [range], counting from 0 at the range's start. */
    fun index(
        value: Float,
        range: ClosedFloatingPointRange<Float>,
        steps: Int,
    ): Int {
        val span = range.endInclusive - range.start
        if (span <= 0f) return 0
        val fraction = ((value - range.start) / span).coerceIn(0f, 1f)
        return if (steps > 0) (fraction * (steps + 1)).roundToInt() else floor(fraction * CONTINUOUS_TICKS).toInt()
    }

    /** Whether moving from [from] to [to] passes a tick, and so plays one. */
    fun crossed(
        from: Float,
        to: Float,
        range: ClosedFloatingPointRange<Float>,
        steps: Int,
    ): Boolean = index(from, range, steps) != index(to, range, steps)

    /** Whether moving a range slider from [from] to [to] passes a tick at either end. */
    fun crossed(
        from: ClosedFloatingPointRange<Float>,
        to: ClosedFloatingPointRange<Float>,
        range: ClosedFloatingPointRange<Float>,
        steps: Int,
    ): Boolean = crossed(from.start, to.start, range, steps) || crossed(from.endInclusive, to.endInclusive, range, steps)
}

/**
 * [onChange] for a slider at [value] in [range] with [steps] stops, playing a tick ([GlassHaptics.tick])
 * each time the dragged value passes one ([GlassTicks]).
 */
@Composable
fun rememberTicking(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChange: (Float) -> Unit,
): (Float) -> Unit {
    val haptics = LocalGlassHaptics.current
    val latest by rememberUpdatedState(onChange)
    val last = remember { floatArrayOf(value) }
    SideEffect { last[0] = value }
    return remember(haptics, range, steps) {
        { next: Float ->
            if (GlassTicks.crossed(last[0], next, range, steps)) haptics.tick()
            last[0] = next
            latest(next)
        }
    }
}

/**
 * [onChange] for a range slider at [value] in [range] with [steps] stops, playing a tick
 * ([GlassHaptics.tick]) each time either dragged end passes one ([GlassTicks]).
 */
@Composable
fun rememberRangeTicking(
    value: ClosedFloatingPointRange<Float>,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChange: (ClosedFloatingPointRange<Float>) -> Unit,
): (ClosedFloatingPointRange<Float>) -> Unit {
    val haptics = LocalGlassHaptics.current
    val latest by rememberUpdatedState(onChange)
    val last = remember { arrayOf(value) }
    SideEffect { last[0] = value }
    return remember(haptics, range, steps) {
        { next: ClosedFloatingPointRange<Float> ->
            if (GlassTicks.crossed(last[0], next, range, steps)) haptics.tick()
            last[0] = next
            latest(next)
        }
    }
}

/**
 * [onClick] that plays the press haptic ([GlassHaptics.press]) first, for a Material button that has no
 * glass component to stand in for it (a text or icon button).
 */
@Composable
fun rememberPressing(onClick: () -> Unit): () -> Unit {
    val haptics = LocalGlassHaptics.current
    val latest by rememberUpdatedState(onClick)
    return remember(haptics) {
        {
            haptics.press()
            latest()
        }
    }
}

/** [onChange] that plays the toggle haptic ([GlassHaptics.toggle]) first, for a switch or a checkbox. */
@Composable
fun rememberToggling(onChange: (Boolean) -> Unit): (Boolean) -> Unit {
    val haptics = LocalGlassHaptics.current
    val latest by rememberUpdatedState(onChange)
    return remember(haptics) {
        { on: Boolean ->
            haptics.toggle(on)
            latest(on)
        }
    }
}
