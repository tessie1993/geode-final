package dev.geode.render

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * The phone's "down" for the live Visual (#315), handed to the engine's GravityField.
 *
 * It reads the best sensor the phone has ([GravitySensor]: the platform's gravity, else a low-passed
 * accelerometer; never a rotation vector, which may need the magnetometer) at `SENSOR_DELAY_GAME`,
 * about 50 Hz. That is far under the 200 Hz above which Android asks for the
 * HIGH_SAMPLING_RATE_SENSORS permission, which the app does not have and must not gain. Each reading becomes a screen-space down through
 * [GravityFilter], with [rotation] (`Surface.ROTATION_*`, read per reading because the activity turns
 * with the phone without being recreated, and a half turn changes no configuration at all), scaled
 * by [weight] (Settings' Tilt: Subtle is half, Full all), and goes to [sink] on the main thread; the
 * engine takes it latest-wins and does the smoothing.
 *
 * It costs battery only while it runs: the host starts it while a Visual is on screen, resumed, and
 * Settings' tilt is on (and reduce motion off), and stops it otherwise. Stopping hands the engine
 * "no reading", so the field eases back to exactly zero, as it is on a phone with no sensor at all:
 * never a made-up vector.
 */
class GravitySource(
    context: Context,
    private val rotation: () -> Int,
    private val sink: (x: Float, y: Float, z: Float) -> Unit,
) : SensorEventListener {
    private val sensors: SensorManager? = context.getSystemService(SensorManager::class.java)
    private val kind: GravitySensor?
    private val sensor: Sensor?
    private val filter = GravityFilter()
    private val down = FloatArray(3)
    private var running = false

    /** How strongly tilt moves the Visual, 0..1; the reading's length carries it to the engine. */
    @Volatile
    var weight: Float = 1f

    init {
        val best = SENSOR_TYPES.firstNotNullOfOrNull { (type, which) -> sensors?.getDefaultSensor(type)?.let { which to it } }
        kind = best?.first
        sensor = best?.second
    }

    /** True when the phone has a sensor to read; with none, [start] does nothing and the field stays zero. */
    val available: Boolean
        get() = sensor != null

    /** Starts listening; a second call while running does nothing. */
    fun start() {
        val wanted = sensor ?: return
        if (running) return
        filter.reset()
        running = sensors?.registerListener(this, wanted, SensorManager.SENSOR_DELAY_GAME) == true
    }

    /** Stops listening and tells the engine there is no reading any more. */
    fun stop() {
        if (!running) return
        sensors?.unregisterListener(this)
        running = false
        filter.reset()
        sink(0f, 0f, 0f)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val which = kind ?: return
        if (!filter.accept(which, event.values, event.timestamp, rotation(), down)) return
        val w = weight.coerceIn(0f, 1f)
        sink(down[0] * w, down[1] * w, down[2] * w)
    }

    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int,
    ) = Unit

    private companion object {
        /** Best first: each one, if the phone has it, is the one read. */
        val SENSOR_TYPES =
            listOf(
                Sensor.TYPE_GRAVITY to GravitySensor.GRAVITY,
                Sensor.TYPE_ACCELEROMETER to GravitySensor.ACCELEROMETER,
            )
    }
}
