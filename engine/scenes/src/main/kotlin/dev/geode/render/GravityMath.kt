package dev.geode.render

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/** Which sensor a [GravitySource] reads, best first. */
enum class GravitySensor {
    /** The platform's own gravity: already fused and filtered, "up" in device axes. */
    GRAVITY,

    /** The raw accelerometer: "up" plus every shake and step, so it is low-passed first. */
    ACCELEROMETER,
}

/**
 * The pure half of tilt (#315): device readings to the screen-space "down" the engine's GravityField
 * takes, with no Android in it so every step is a unit test.
 *
 * ## Axes
 *
 * Android's sensors report in DEVICE axes, fixed to the phone in its natural orientation: x right,
 * y up, z out of the screen. At rest they report the reaction to gravity, so they point UP: a phone
 * upright in portrait reads (0, 9.81, 0) and one flat on a table, face up, (0, 0, 9.81).
 *
 * The engine wants DOWN in SCREEN axes: x toward the right of the picture as it is drawn now, y toward
 * its top, z out of the screen. The two differ by the display rotation, because the activity follows
 * the phone round and the picture with it; down has to stay where the floor is.
 */
object GravityMath {
    /** `Surface.ROTATION_*`, by value, so this file needs no Android. */
    const val ROTATION_0: Int = 0
    const val ROTATION_90: Int = 1
    const val ROTATION_180: Int = 2
    const val ROTATION_270: Int = 3

    /** A reading shorter than this (m/s² or unit) has no direction. */
    const val MIN_LENGTH: Float = 1e-3f

    /**
     * Device axes to screen axes for a display [rotation] (`Surface.ROTATION_*`).
     *
     * The rotation is the way the drawn picture is turned, which is opposite to the way the phone was
     * turned: ROTATION_90 is the phone turned a quarter counter-clockwise, its top now pointing left
     * and its right edge up. So the picture's right is the device's -y and its top the device's +x.
     */
    fun toScreen(
        rotation: Int,
        x: Float,
        y: Float,
        z: Float,
        out: FloatArray,
    ) {
        when (rotation and ROTATION_MASK) {
            ROTATION_90 -> {
                out[0] = -y
                out[1] = x
            }
            ROTATION_180 -> {
                out[0] = -x
                out[1] = -y
            }
            ROTATION_270 -> {
                out[0] = y
                out[1] = -x
            }
            else -> {
                out[0] = x
                out[1] = y
            }
        }
        out[2] = z
    }

    /**
     * The unit "down" opposite an "up" reading, into [out]; false (and [out] untouched) when the
     * reading has no direction or is not finite, which a caller treats as no reading at all.
     */
    fun downOf(
        upX: Float,
        upY: Float,
        upZ: Float,
        out: FloatArray,
    ): Boolean {
        if (!upX.isFinite() || !upY.isFinite() || !upZ.isFinite()) return false
        val length = sqrt(upX * upX + upY * upY + upZ * upZ)
        if (!length.isFinite() || length < MIN_LENGTH) return false
        out[0] = -upX / length
        out[1] = -upY / length
        out[2] = -upZ / length
        return true
    }

    private const val ROTATION_MASK = 3
}

/**
 * A 1-euro filter (Casiez, Roussel and Vogel, CHI 2012) on one value: a one-pole low-pass whose
 * cutoff rises with the value's own speed, so a phone held still does not jitter and a deliberate
 * turn does not lag. Timed by the readings' own clock, so it is the same at any sensor rate.
 */
class OneEuroFilter(
    private val minCutoffHz: Float = MIN_CUTOFF_HZ,
    private val beta: Float = BETA,
    private val derivativeCutoffHz: Float = DERIVATIVE_CUTOFF_HZ,
) {
    private var value = 0f
    private var speed = 0f
    private var seeded = false

    fun reset() {
        seeded = false
        speed = 0f
    }

    /** The next filtered value for [x], [dt] seconds after the last one. */
    fun filter(
        x: Float,
        dt: Float,
    ): Float {
        if (!seeded || dt <= 0f) {
            if (!seeded) {
                value = x
                speed = 0f
                seeded = true
            }
            return value
        }
        val rawSpeed = (x - value) / dt
        speed += (rawSpeed - speed) * alpha(derivativeCutoffHz, dt)
        val cutoff = minCutoffHz + beta * abs(speed)
        value += (x - value) * alpha(cutoff, dt)
        return value
    }

    private fun alpha(
        cutoffHz: Float,
        dt: Float,
    ): Float {
        val tau = 1f / (TWO_PI * cutoffHz)
        return 1f / (1f + tau / dt)
    }

    companion object {
        /** Tuned as the paper says: still, no jitter at 1 Hz; moving, beta until the lag goes. */
        const val MIN_CUTOFF_HZ: Float = 1f
        const val BETA: Float = 0.8f
        const val DERIVATIVE_CUTOFF_HZ: Float = 1f
        private const val TWO_PI = 6.2831855f
    }
}

/**
 * One sensor's readings to screen-space down, one at a time, on the thread the sensor calls back on.
 *
 * The accelerometer is low-passed toward gravity first, by a one-pole on the readings' own timestamps
 * (so the same at 50 and 200 Hz). Every sensor then goes through a [OneEuroFilter] per device axis,
 * before the display rotation is applied, so a rotation of the picture reaches the engine as the jump
 * it is (the engine starts afresh on it) rather than as a smeared turn. What is left is the engine's
 * to smooth, and its GravityField does, frame-rate independently and without ringing.
 */
class GravityFilter(
    private val accelerometerTauSeconds: Float = ACCELEROMETER_TAU_SECONDS,
    private val oneEuro: Boolean = true,
) {
    private val up = FloatArray(3)
    private val screen = FloatArray(3)
    private val euro = Array(3) { OneEuroFilter() }
    private var lastNanos = 0L
    private var euroNanos = 0L
    private var seeded = false
    private var euroSeeded = false

    /** Forget the filters, so the next reading starts them afresh (a new registration, a new sensor). */
    fun reset() {
        seeded = false
        euroSeeded = false
        lastNanos = 0L
        euroNanos = 0L
        for (f in euro) f.reset()
    }

    /**
     * One reading of [sensor] taken at [timestampNanos], with the display at [rotation]: true and
     * screen-space unit down in [out], or false when the reading says nothing.
     */
    fun accept(
        sensor: GravitySensor,
        values: FloatArray,
        timestampNanos: Long,
        rotation: Int,
        out: FloatArray,
    ): Boolean {
        val read =
            when (sensor) {
                GravitySensor.GRAVITY -> copyUp(values)
                GravitySensor.ACCELEROMETER -> lowPass(values, timestampNanos)
            }
        if (!read) return false
        val dt = if (euroSeeded) secondsBetween(euroNanos, timestampNanos) else 0f
        euroSeeded = true
        euroNanos = timestampNanos
        if (oneEuro) {
            for (i in 0 until AXES) up[i] = euro[i].filter(up[i], dt)
        }
        GravityMath.toScreen(rotation, up[0], up[1], up[2], screen)
        return GravityMath.downOf(screen[0], screen[1], screen[2], out)
    }

    private fun copyUp(values: FloatArray): Boolean {
        if (values.size < AXES) return false
        for (i in 0 until AXES) {
            if (!values[i].isFinite()) return false
        }
        for (i in 0 until AXES) up[i] = values[i]
        return true
    }

    private fun secondsBetween(
        from: Long,
        to: Long,
    ): Float = ((to - from).coerceAtLeast(0L) / NANOS_PER_SECOND).coerceAtMost(MAX_STEP_SECONDS)

    private fun lowPass(
        values: FloatArray,
        timestampNanos: Long,
    ): Boolean {
        if (values.size < AXES) return false
        for (i in 0 until AXES) {
            if (!values[i].isFinite()) return false
        }
        if (!seeded) {
            for (i in 0 until AXES) up[i] = values[i]
            seeded = true
        } else {
            val dt = secondsBetween(lastNanos, timestampNanos)
            val k = 1f - exp(-dt / accelerometerTauSeconds)
            for (i in 0 until AXES) up[i] += (values[i] - up[i]) * k
        }
        lastNanos = timestampNanos
        return true
    }

    companion object {
        /** Long enough to keep a footstep out of "down", short enough that a deliberate tilt follows. */
        const val ACCELEROMETER_TAU_SECONDS: Float = 0.2f

        private const val AXES = 3
        private const val NANOS_PER_SECOND = 1e9f
        private const val MAX_STEP_SECONDS = 0.5f
    }
}
