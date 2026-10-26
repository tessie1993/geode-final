package dev.geode.ui

import dev.geode.render.scene.TouchTransform
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.round

/**
 * Two fingers on the Visual place its Camera, and it stays where they leave it (#397, the owner's
 * decisions): spreading or pinching zooms, dragging pans, twisting turns. Nothing springs back when the
 * fingers lift. The Style still gets the same fingers through [VisualTouch] as its own two-finger
 * gesture; this only places the Camera on top.
 *
 * Nothing happens until the fingers have moved further than [slop]: their distance apart, their middle,
 * or the arc a twist sweeps at their distance. Two fingers resting stay the Style's alone. From then on
 * each move is measured from where the gesture took over, so the slop itself moves nothing:
 *
 * - the zoom is the zoom it started from times the change of distance, within the Camera's range
 *   ([TouchTransform]);
 * - the pan follows the fingers' middle, so the picture moves with them: a full pan
 *   ([Placement.panX], [Placement.panY]) is half the frame, the engine's camera::kMaxPan;
 * - the turn follows the twist, anticlockwise positive, half a turn at most either way.
 *
 * While the fingers move the placement is a preview, and when the gesture ends it is committed as it
 * is: one step the person can undo, and the Customization's own Camera from then on, so presets,
 * Renders and Recordings see it too ([CustomizationCamera]).
 *
 * Pure bookkeeping: [VisualTouchInput] hands in the two fingers in view pixels. Nothing allocates per
 * move but the [Placement] sent on.
 */
class CameraGesture(
    private val slop: Float,
    private val target: Target,
) {
    /** Where the Camera is: the Shared core's ZOOM, PAN_X, PAN_Y and TURN, in their own units. */
    data class Placement(
        val zoom: Float = 1f,
        val panX: Float = 0f,
        val panY: Float = 0f,
        val turn: Float = 0f,
    )

    /** Where a gesture's placement comes from and goes to: the Customization's Camera. */
    interface Target {
        /** The Camera's placement now, which a gesture starts from. */
        fun placement(): Placement

        /** The placement shown while the fingers still move. */
        fun preview(placement: Placement)

        /** The placement the gesture leaves behind; it stays. */
        fun commit(placement: Placement)
    }

    /** Settings' "Pinch and drag the Visual": off, two fingers are the Style's alone. */
    var enabled: Boolean = true

    private var width = 1f
    private var height = 1f

    // The fingers where the gesture is measured from, and where they were on the last move.
    private var fromSpan = 0f
    private var fromX = 0f
    private var fromY = 0f
    private var lastAngle = 0f
    private var twist = 0f

    private var base = Placement()
    private var current = Placement()
    private var tracking = false
    private var moving = false

    /** Whether the fingers are placing the Camera now (past [slop]). */
    val placing: Boolean
        get() = moving

    /** The view's size in pixels: a pan is a share of it. */
    fun resize(
        width: Float,
        height: Float,
    ) {
        this.width = width.coerceAtLeast(1f)
        this.height = height.coerceAtLeast(1f)
    }

    /**
     * Two fingers are down at ([ax], [ay]) and ([bx], [by]): a gesture may start. Also called when the pair
     * changes (a third finger lands, one of three lifts); a gesture under way then carries on from where
     * it has placed the Camera, so the picture does not jump.
     */
    fun start(
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
    ) {
        if (!enabled || !valid(ax, ay, bx, by)) return
        if (moving) base = current
        measureFrom(ax, ay, bx, by)
        tracking = true
    }

    /** The two fingers moved to ([ax], [ay]) and ([bx], [by]). */
    fun move(
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
    ) {
        if (!tracking || !valid(ax, ay, bx, by)) return
        val span = hypot(bx - ax, by - ay)
        val direction = angle(ax, ay, bx, by)
        twist += wrap(direction - lastAngle)
        lastAngle = direction
        val midX = (ax + bx) / 2f
        val midY = (ay + by) / 2f
        if (!moving) {
            if (!pastSlop(span, midX, midY)) return
            // Measured from here, not from where the fingers landed: the slop itself is not a move.
            moving = true
            base = target.placement()
            current = base
            measureFrom(ax, ay, bx, by)
            return
        }
        val next =
            Placement(
                zoom = TouchTransform.zoom(base.zoom, span / fromSpan),
                panX = (base.panX + (midX - fromX) / width * PAN_PER_FRAME).coerceIn(-1f, 1f),
                panY = (base.panY - (midY - fromY) / height * PAN_PER_FRAME).coerceIn(-1f, 1f),
                turn = (base.turn + twist / PI.toFloat()).coerceIn(-1f, 1f),
            )
        if (next != current) {
            current = next
            target.preview(next)
        }
    }

    /** Fewer than two fingers are down, or the gesture was cancelled: the Camera stays where it is. */
    fun end() {
        if (moving) target.commit(current)
        tracking = false
        moving = false
    }

    private fun measureFrom(
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
    ) {
        fromSpan = hypot(bx - ax, by - ay)
        fromX = (ax + bx) / 2f
        fromY = (ay + by) / 2f
        lastAngle = angle(ax, ay, bx, by)
        twist = 0f
    }

    private fun pastSlop(
        span: Float,
        midX: Float,
        midY: Float,
    ): Boolean {
        val spread = abs(span - fromSpan)
        val slide = hypot(midX - fromX, midY - fromY)
        val arc = abs(twist) * fromSpan / 2f
        return spread > slop || slide > slop || arc > slop
    }

    private companion object {
        /** A full pan is half the frame (the engine's camera::kMaxPan), so a frame's width is two. */
        const val PAN_PER_FRAME = 2f

        const val TWO_PI = (2.0 * PI).toFloat()

        /** Two fingers on the same spot, or no reading, have no distance or direction to measure. */
        fun valid(
            ax: Float,
            ay: Float,
            bx: Float,
            by: Float,
        ): Boolean {
            val span = hypot(bx - ax, by - ay)
            return span.isFinite() && span > 0f
        }

        /** The direction from the first finger to the second, anticlockwise on screen (y is down in pixels). */
        fun angle(
            ax: Float,
            ay: Float,
            bx: Float,
            by: Float,
        ): Float = atan2(-(by - ay), bx - ax)

        /** An angle's change brought within half a turn either way, so crossing the back of the circle is no jump. */
        fun wrap(delta: Float): Float = delta - TWO_PI * round(delta / TWO_PI)
    }
}
