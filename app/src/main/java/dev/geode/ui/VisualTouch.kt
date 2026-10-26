package dev.geode.ui

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import dev.geode.render.TouchField
import kotlin.math.abs

/**
 * The fingers one host publishes to the Visual (#290).
 *
 * The engine has two touch inputs, and this feeds both from one event stream:
 *
 * - [submit] replaces the live pointer set. The native `TouchField` reads it on its next frame,
 *   latest-wins, and every Style that calls `lib_touch.glsl` reads the anchor and wakes it derives.
 * - [stroke] queues one finger's movement for the Styles that draw the finger's path themselves
 *   (Water's ripples, Fluid's ink) and for the shared smear, which is a path rather than a position
 *   and therefore must not drop samples.
 *
 * ## Coordinates
 *
 * [submit] is y-up normalized device coordinates: x and y in -1..1, origin at the centre of the
 * *view*, +y toward the top. Aspect is deliberately not applied - that is exactly what
 * `lib_touch.glsl` expects, and it scales x by `uResolution.x / uResolution.y` itself so a circle
 * drawn around the finger is round. Getting this from the view's own size also makes a display
 * rotation a non-event: after it the width and height have swapped, and the same fractions of them
 * land on the same part of the new surface.
 *
 * [stroke] is the other unit the native API documents: 0..1 across the surface, `ny` measured from
 * the top, `ndx`/`ndy` as fractions of the surface size, `dt` in real seconds.
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
    /** Pointer ids in the order they went down; slot 0 is the anchor while it is still down. */
    private val ids = IntArray(TouchField.MAX_POINTS)
    private val xs = FloatArray(TouchField.MAX_POINTS)
    private val ys = FloatArray(TouchField.MAX_POINTS)

    /** The published pairs, reused by every submission. */
    private val out = FloatArray(TouchField.MAX_POINTS * 2)

    private var width = 1f
    private var height = 1f
    private var live = 0

    /** How many pointers are down right now. */
    val count: Int
        get() = live

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
     * one stroke sample, because that is the finger the anchor follows.
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
            stroke(x / width, y / height, dx / width, dy / height, dt, strength.coerceIn(0f, 1f))
        }
        return live
    }

    /** A pointer lifted: drop it and republish the ones still down. */
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
     * One short touch for a tap too quick to land on a frame: a tap that submits a point and a zero
     * between two frames is overwritten latest-wins before the engine reads it. The host clears it
     * a few frames later.
     */
    fun pulse(
        x: Float,
        y: Float,
    ) {
        out[0] = ndcX(x)
        out[1] = ndcY(y)
        submit(out, 1)
    }

    private fun publish() {
        for (i in 0 until live) {
            out[i * 2] = ndcX(xs[i])
            out[i * 2 + 1] = ndcY(ys[i])
        }
        submit(out, live)
    }

    private fun ndcX(x: Float): Float = x / width * 2f - 1f

    private fun ndcY(y: Float): Float = 1f - y / height * 2f

    private fun indexOf(id: Int): Int {
        for (i in 0 until live) {
            if (ids[i] == id) return i
        }
        return -1
    }
}

/**
 * The one gesture pipeline every in-app Visual host installs on its surface (#290).
 *
 * It lives on the view, not in Compose: the touch rate is 120-240 Hz and a Compose pointer event per
 * sample would allocate and enter the recomposition machinery; the GL surface is already a real
 * view; and App UI drawn over it (the tab rows, the full-screen controls) still takes its own
 * touches first, because Compose hands a touch to the topmost thing that handles pointers.
 *
 * The rule, stated once (the glossary's Player controls): **every touch on the full-screen Visual
 * belongs to the Style, and only a double tap shows or hides the controls.** A press and release
 * inside touch slop and within the long-press timeout is a tap; it leaves one short pulse in the
 * Visual. A second tap within the platform's double-tap timeout, near the first, also calls
 * [onDoubleTap]. Anything that moved further is a drag: it draws in the Visual and never counts as a
 * tap. Hosts with nothing to toggle pass a no-op.
 *
 * Every event is consumed after being published: a finger that starts on the Visual belongs to it.
 */
internal class VisualTouchInput(
    context: Context,
    private val touch: VisualTouch,
    private val onDoubleTap: () -> Unit,
) : View.OnTouchListener {
    private val config = ViewConfiguration.get(context)
    private val touchSlop = config.scaledTouchSlop.toFloat()
    private val doubleTapSlop = config.scaledDoubleTapSlop.toFloat()

    /** A press held longer than a long press is a deliberate touch on the picture, not a tap. */
    private val tapTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong()
    private val doubleTapTimeoutMs = ViewConfiguration.getDoubleTapTimeout().toLong()

    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastTime = 0L
    private var tapCandidate = false

    /** The last tap that could still be the first of a double tap; [NO_TAP] when there is none. */
    private var lastTapTime = NO_TAP
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var secondTap = false

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
                secondTap = lastTapTime != NO_TAP && downTime - lastTapTime <= doubleTapTimeoutMs && nearLastTap(downX, downY)
                touch.down(event.getPointerId(0), downX, downY)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger means this is no longer a one-finger tap.
                tapCandidate = false
                lastTapTime = NO_TAP
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
                touch.up(event.getPointerId(event.actionIndex))
            }
            MotionEvent.ACTION_UP -> {
                val x = event.getX(0)
                val y = event.getY(0)
                val tapped = tapCandidate && event.eventTime - downTime <= tapTimeoutMs && !outsideSlop(x, y)
                touch.up(event.getPointerId(0))
                if (tapped) {
                    touch.pulse(x, y)
                    v.postDelayed(endPulse, TAP_PULSE_MS)
                    if (secondTap) {
                        lastTapTime = NO_TAP
                        onDoubleTap()
                    } else {
                        lastTapTime = event.eventTime
                        lastTapX = x
                        lastTapY = y
                    }
                } else {
                    lastTapTime = NO_TAP
                }
                secondTap = false
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> {
                v.removeCallbacks(endPulse)
                tapCandidate = false
                secondTap = false
                lastTapTime = NO_TAP
                touch.clear()
            }
        }
        return true
    }

    private fun outsideSlop(
        x: Float,
        y: Float,
    ): Boolean = abs(x - downX) > touchSlop || abs(y - downY) > touchSlop

    private fun nearLastTap(
        x: Float,
        y: Float,
    ): Boolean = abs(x - lastTapX) <= doubleTapSlop && abs(y - lastTapY) <= doubleTapSlop

    private companion object {
        const val MILLIS_PER_SECOND = 1_000f

        /** A finger is full strength; the engine's release decay does the fading. */
        const val FULL_STRENGTH = 1f

        /** About five frames at 60 Hz: long enough for the engine to see a tap, short enough not to linger. */
        const val TAP_PULSE_MS = 90L

        const val NO_TAP = Long.MIN_VALUE
    }
}
