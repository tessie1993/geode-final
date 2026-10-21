package dev.geode.design.water

import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * How a touch ripple moves through frosted stone glass. Damped, never "jelly": the wavefront slows
 * as it spreads, like a drop settling on still water, and its height falls away to nothing by the
 * end, so nothing keeps oscillating. Lengths are in pixels so the physics stays free of density.
 */
data class RippleSpec(
    /** How long one ripple lives, from touch to stillness. */
    val durationMillis: Long,
    /** The crest's displacement of the glass at the moment of touch. */
    val peakAmplitudePx: Float,
    /** The distance between one crest and the next. */
    val wavelengthPx: Float,
    /** The half-width of the band of rings around the wavefront. */
    val bandPx: Float,
    /** How quickly the height falls away, as an exponential rate over the ripple's life. */
    val damping: Float,
) {
    init {
        require(durationMillis > 0) { "a ripple must last some time" }
        require(peakAmplitudePx >= 0f && wavelengthPx > 0f && bandPx > 0f && damping >= 0f)
    }
}

/** Where a ripple stands at one moment: its wavefront [radiusPx] and the crest's [amplitudePx]. */
data class RippleFrame(
    val radiusPx: Float,
    val amplitudePx: Float,
    /** The fraction of its strength the ripple keeps, from 1 at the touch to 0 at stillness. */
    val strength: Float,
)

/** One ripple from one touch at ([x], [y]) inside a surface, started at [startNanos] on the frame clock. */
data class Ripple(
    val x: Float,
    val y: Float,
    val startNanos: Long,
    /** How far the wavefront travels: from the touch to the surface's farthest corner. */
    val reachPx: Float,
)

/** The pure maths of a ripple's life, kept apart from drawing so it can be tested on the JVM. */
object RipplePhysics {
    private const val NANOS_PER_MILLI = 1_000_000L

    /** The fraction of its life [ripple] has lived at [nowNanos], from 0 to 1. */
    fun progress(
        ripple: Ripple,
        nowNanos: Long,
        spec: RippleSpec,
    ): Float {
        val elapsed = (nowNanos - ripple.startNanos).coerceAtLeast(0L)
        return (elapsed.toFloat() / (spec.durationMillis * NANOS_PER_MILLI)).coerceIn(0f, 1f)
    }

    /** Whether [ripple] still moves at [nowNanos]. A ripple that has finished costs nothing. */
    fun isAlive(
        ripple: Ripple,
        nowNanos: Long,
        spec: RippleSpec,
    ): Boolean = progress(ripple, nowNanos, spec) < 1f

    /**
     * The ripple at [nowNanos]. The wavefront decelerates (a quadratic ease out) and the height
     * decays exponentially and is pulled to exactly zero at the end, so the last frame is still.
     */
    fun frame(
        ripple: Ripple,
        nowNanos: Long,
        spec: RippleSpec,
    ): RippleFrame {
        val p = progress(ripple, nowNanos, spec)
        val remaining = 1f - p
        val eased = 1f - remaining * remaining
        val strength = exp(-spec.damping * p) * remaining
        return RippleFrame(
            radiusPx = ripple.reachPx * eased,
            amplitudePx = spec.peakAmplitudePx * strength,
            strength = strength,
        )
    }

    /** The farthest a ripple from ([x], [y]) must travel to cross a [width] × [height] surface. */
    fun reach(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
    ): Float = hypot(max(x, width - x), max(y, height - y))

    /**
     * The glass's displacement [distancePx] from the touch: a sine in a soft band around the
     * wavefront, the same shape the AGSL shader draws on Android 13+.
     */
    fun displacement(
        frame: RippleFrame,
        distancePx: Float,
        spec: RippleSpec,
    ): Float {
        val offset = distancePx - frame.radiusPx
        val band = exp(-(offset * offset) / (spec.bandPx * spec.bandPx))
        return frame.amplitudePx * band * sin(TWO_PI * offset / spec.wavelengthPx)
    }

    private const val TWO_PI = (2 * Math.PI).toFloat()
}
