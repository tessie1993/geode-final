package dev.geode.feature.customization.ui

import dev.geode.feature.customization.api.ValueKind
import kotlin.math.roundToInt

// Fine and coarse steps on every row (#392): precise values on a small screen, where a slider moves
// too far for one pixel of finger. A tap on a row's − or + is a fine step and a long-press a coarse
// one; each step is one edit, so one undo step.

/** A fine step: this share of a row's range. */
internal const val FINE_STEP = 0.01f

/** A coarse step: this share of a row's range, ten fine steps. */
internal const val COARSE_STEP = 0.1f

/**
 * [value] moved one fine or [coarse] step, [up] or down, and kept to [min]..[max]. A stepped value
 * moves by whole numbers: one when fine, a tenth of its range (at least one) when coarse. A switch
 * steps from one end to the other.
 */
internal fun nudged(
    value: Float,
    min: Float,
    max: Float,
    kind: ValueKind,
    up: Boolean,
    coarse: Boolean,
): Float {
    val span = max - min
    val step =
        when (kind) {
            ValueKind.CONTINUOUS -> span * if (coarse) COARSE_STEP else FINE_STEP
            ValueKind.STEPPED -> if (coarse) (span * COARSE_STEP).roundToInt().coerceAtLeast(1).toFloat() else 1f
            ValueKind.SWITCH -> span
        }
    val kept = (if (up) value + step else value - step).coerceIn(min, max)
    return if (kind == ValueKind.CONTINUOUS) kept else kept.roundToInt().toFloat()
}
