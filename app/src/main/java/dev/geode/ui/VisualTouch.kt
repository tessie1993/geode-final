package dev.geode.ui

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import dev.geode.render.TouchField
import kotlin.math.abs

/**
 * The fingers one host publishes to the Visual (#218).
 *
 * The engine has two touch inputs, and this feeds both from one event stream:
 *
 * - [submit] replaces the live pointer set. The native `TouchField` reads it on its next frame,
 *   latest-wins, and every Style that calls `lib_touch.glsl` reads the anchor and wakes it derives.
 * - [stroke] queues one finger's movement for the overlay/Water smear, which is a path rather than
 *   a position and therefore must not drop samples.
 *
 * ## Coordinates
 *
 * [submit] is y-up normalized device coordinates: x and y in -1..1, origin at the centre of the
 * *view* (not the display, which a wallpaper surface may be wider than), +y toward the top. Aspect
 * is deliberately not applied - that is exactly what `lib_touch.glsl` expects, and it scales x by
 * `uResolution.x / uResolution.y` itself so a circle drawn around the finger is round. Getting this
 * from the view's own size also makes a rotation a non-event: after it the width and height have
 * swapped, and the same fractions of them land on the same part of the new surface.
 *
 * [stroke] is the other unit the native API documents: 0..1 across the surface, `ny` measured from
 * the top so `1 - ny` is y-up, `ndx`/`ndy` as fractions of the surface size, `dt` in real seconds.
 *
 * ## Allocation
 *
 * One instance per host. Every array is sized once from [TouchField.MAX_POINTS], so a move copies
 * floats and allocates nothing; nothing here touches Compose.
 */
class VisualTouch(
    private val submit: (FloatArray, Int) -> Unit,
    private val stroke: (nx: Float, ny: Float, ndx: Float, ndy: Float, dt: Float, strength: Float) -> Unit,
) {
    /** Pointer ids in the order they went down; slot 0 is the anchor when it is still live. */
    private val ids = IntArray(TouchField.MAX_POINTS)
    private val xs = FloatArray(TouchField.MAX_POINTS)
    private val ys = FloatArray(TouchField.MAX_POINTS)

    /** The published pairs, reused by every submission. */
    private val out = FloatArray(TouchField.MAX_POINTS * 2)

    private var width = 1f
    private var height = 1f

    /** How many pointers are live right now. */
    val count: Int
        get() = live

    private var live = 0

    /** The view's size in pixels. Called before every event, so a resize or rotation needs no hook. */
    fun resize(
        width: Float,
        height: Float,
    ) {
        this.width = width.coerceAtLeast(1f)
        this.height = height.coerceAtLeast(1f)
    }

    /** A pointer went down. More than [TouchField.MAX_POINTS] are ignored, as in the wallpaper. */
    fun down(
        id: Int,
        x: Float,
        y: Float,
    ): Int {
        if (indexOf(id) >= 0 || live == TouchField.MAX_POINTS) return live
        ids[live] = id
        xs[live] = x
        ys[live] = y
        live++
        publish()
        return live
    }

    /**
     * A pointer moved. The whole live set is republished, and the first live pointer also leaves
     * one smear sample, because that is the finger the anchor follows.
     */
    fun move(
        id: Int,
        x: Float,
        y: Float,
        dt: Float,
        strength: Float,
    ): Int {
        val slot = indexOf(id)
        if (slot < 0) return live
        val dx = x - xs[slot]
        val dy = y - ys[slot]
        xs[slot] = x
        ys[slot] = y
        publish()
        if (slot == 0 && dt > 0f && (dx != 0f || dy != 0f)) {
            stroke(
                x / width,
                y / height,
                dx / width,
                dy / height,
                dt,
                strength.coerceIn(0f, 1f),
            )
        }
        return live
    }

    /** A pointer lifted or was cancelled: drop it and republish the ones still down. */
    fun up(id: Int): Int {
        val slot = indexOf(id)
        if (slot < 0) return live
        for (i in slot until live - 1) {
            ids[i] = ids[i + 1]
            xs[i] = xs[i + 1]
            ys[i] = ys[i + 1]
        }
        live--
        publish()
        return live
    }

    /** Nothing is down any more; the engine starts its release decay from this. */
    fun clear() {
        live = 0
        submit(out, 0)
    }

    /**
     * One short touch for a tap too quick to land on a frame: a tap that submits a point and a
     * zero in the same gesture can otherwise be overwritten latest-wins before the engine reads it.
     * The host clears it a few frames later.
     */
    fun pulse(
        x: Float,
        y: Float,
    ) {
        out[0] = x / width * 2f - 1f
        out[1] = 1f - y / height * 2f
        submit(out, 1)
    }

    private fun publish() {
        for (i in 0 until live) {
            out[i * 2] = xs[i] / width * 2f - 1f
            out[i * 2 + 1] = 1f - ys[i] / height * 2f
        }
        submit(out, live)
    }

    private fun indexOf(id: Int): Int {
        for (i in 0 until live) {
            if (ids[i] == id) return i
        }
        return -1
    }
}

/**
 * The one gesture pipeline every in-app Visual host installs on its surface (#218).
 *
 * It lives on the view, not in Compose, for three reasons: the touch rate is 120-240 Hz and a
 * Compose pointer event per sample would allocate and enter the recomposition machinery; the GL
 * surface is already a real view; and keeping it out of [androidx.compose.ui.viewinterop.AndroidView]'s
 * modifier lets all App UI places above it consume their own touches first.
 *
 * The rule, stated once: **a press and release inside touch slop and within the tap timeout is a
 * tap - it calls [onTap] and leaves one short pulse in the Visual. Anything that moved further is a
 * drag: it draws in the Visual and never calls [onTap].** That is what lets full-screen mode put
 * "show/hide the controls" on the same surface without a second gesture layer eating the drags.
 *
 * Every event is consumed after being published: a finger that starts on the Visual belongs to it,
 * not to a scroll container that happens to be behind the same window.
 */
internal class VisualTouchInput(
    context: Context,
    private val touch: VisualTouch,
    private val onTap: () -> Unit,
) : View.OnTouchListener {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    /**
     * A press held longer than a long press is not a tap but a deliberate touch on the picture,
     * so this is the platform's own bound rather than a number invented here.
     */
    private val tapTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong()

    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastTime = 0L
    private var tapCandidate = false

    /** Preallocated so the delayed end of a tap's pulse costs no allocation either. */
    private val endPulse = Runnable { touch.clear() }

    override fun onTouch(
        v: View,
        event: MotionEvent,
    ): Boolean {
        touch.resize(v.width.toFloat(), v.height.toFloat())
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.removeCallbacks(endPulse)
                downX = event.getX(0)
                downY = event.getY(0)
                downTime = event.eventTime
                lastTime = downTime
                tapCandidate = true
                touch.down(event.getPointerId(0), downX, downY)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger means this is no longer the one-finger tap that toggles.
                tapCandidate = false
                val i = event.actionIndex
                touch.down(event.getPointerId(i), event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                val dt = (event.eventTime - lastTime).coerceAtLeast(0L) / MILLIS_PER_SECOND
                lastTime = event.eventTime
                for (i in 0 until event.pointerCount) {
                    touch.move(event.getPointerId(i), event.getX(i), event.getY(i), dt, FULL_STRENGTH)
                }
                if (tapCandidate && outsideSlop(event.getX(0), event.getY(0))) tapCandidate = false
            }
            MotionEvent.ACTION_POINTER_UP -> {
                tapCandidate = false
                val i = event.actionIndex
                touch.up(event.getPointerId(i))
            }
            MotionEvent.ACTION_UP -> {
                val tapped = tapCandidate && event.eventTime - downTime <= tapTimeoutMs && !outsideSlop(event.getX(0), event.getY(0))
                touch.up(event.getPointerId(0))
                if (tapped) {
                    touch.pulse(event.getX(0), event.getY(0))
                    v.postDelayed(endPulse, TAP_PULSE_MS)
                    onTap()
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> {
                v.removeCallbacks(endPulse)
                tapCandidate = false
                touch.clear()
            }
        }
        return true
    }

    private fun outsideSlop(
        x: Float,
        y: Float,
    ): Boolean = abs(x - downX) > touchSlop || abs(y - downY) > touchSlop

    private companion object {
        const val MILLIS_PER_SECOND = 1_000f

        /** A finger is full strength; the engine's release decay does the fading. */
        const val FULL_STRENGTH = 1f

        /** About five frames at 60 Hz: long enough for the engine to see a tap, short enough not to linger. */
        const val TAP_PULSE_MS = 90L
    }
}
