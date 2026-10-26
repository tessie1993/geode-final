package dev.geode.ui

import dev.geode.render.scene.TouchTransform
import kotlin.math.abs

/**
 * Two fingers pinching the Visual zoom its Camera, and the zoom stays where they leave it (#397, the
 * owner's decision): nothing springs back when the fingers lift. The Style still gets the same fingers
 * through [VisualTouch] as its own two-finger gesture; this only adds the Camera's zoom on top.
 *
 * The pinch is measured by the distance between the two fingers (the span). It does nothing until the
 * span has changed by more than [slop], so two fingers resting or dragging together stay the Style's
 * alone; from then on every change of span scales the zoom it started from, within the Camera's range
 * ([TouchTransform]). While the fingers move the zoom is a preview, and when the pinch ends it is
 * committed as it is: one step the person can undo, and the Customization's own Camera zoom from then
 * on, so presets, Renders and Recordings see it too.
 *
 * Pure bookkeeping: [VisualTouchInput] hands in spans, and [Target] carries the zoom to the
 * Customization. Nothing allocates per move.
 */
class PinchZoom(
    private val slop: Float,
    private val target: Target,
) {
    /** Where a pinch's zoom comes from and goes to: the Customization's Camera zoom ([CameraZoom]). */
    interface Target {
        /** The Camera's zoom now, which a pinch starts from. */
        fun zoom(): Float

        /** The zoom shown while the fingers still move. */
        fun preview(zoom: Float)

        /** The zoom the pinch leaves behind; it stays. */
        fun commit(zoom: Float)
    }

    /** Settings' "Pinch and drag the Visual": off, two fingers are the Style's alone. */
    var enabled: Boolean = true

    /** The span the zoom is measured against, and the zoom it had then. */
    private var from = 0f
    private var base = 1f
    private var zoom = 1f
    private var tracking = false
    private var pinching = false

    /** Whether the fingers are zooming the Camera now (past [slop]). */
    val zooming: Boolean
        get() = pinching

    /**
     * Two fingers are down with this [span] between them: a pinch may start. Also called when the pair
     * changes (a third finger lands, one of three lifts); a pinch under way then carries on from the
     * zoom it has reached, so the picture does not jump.
     */
    fun start(span: Float) {
        if (!enabled || !valid(span)) return
        if (pinching) base = zoom
        from = span
        tracking = true
    }

    /** The two fingers moved to this [span]. */
    fun move(span: Float) {
        if (!tracking || !valid(span)) return
        if (!pinching) {
            if (abs(span - from) <= slop) return
            // Measured from here, not from where the fingers landed: the slop itself is not a zoom.
            pinching = true
            base = target.zoom()
            zoom = base
            from = span
            return
        }
        val next = TouchTransform.zoom(base, span / from)
        if (next != zoom) {
            zoom = next
            target.preview(next)
        }
    }

    /** Fewer than two fingers are down, or the gesture was cancelled: the zoom stays as it is. */
    fun end() {
        if (pinching) target.commit(zoom)
        tracking = false
        pinching = false
    }

    private fun valid(span: Float): Boolean = span.isFinite() && span > 0f
}
