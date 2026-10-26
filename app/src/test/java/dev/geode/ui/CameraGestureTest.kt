package dev.geode.ui

import dev.geode.render.scene.TouchTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Two fingers place the Camera and it stays where they leave it (#397): pinch zooms, drag pans, twist
 * turns; one commit at the end, nothing at all inside the slop or with the setting off.
 */
class CameraGestureTest {
    private class Camera(
        var value: CameraGesture.Placement = CameraGesture.Placement(),
    ) : CameraGesture.Target {
        val previews = mutableListOf<CameraGesture.Placement>()
        val commits = mutableListOf<CameraGesture.Placement>()

        override fun placement(): CameraGesture.Placement = value

        override fun preview(placement: CameraGesture.Placement) {
            previews += placement
            value = placement
        }

        override fun commit(placement: CameraGesture.Placement) {
            commits += placement
            value = placement
        }
    }

    private fun gesture(camera: Camera): CameraGesture = CameraGesture(SLOP, camera).apply { resize(SIZE, SIZE) }

    private fun assertPlacement(
        expected: CameraGesture.Placement,
        actual: CameraGesture.Placement,
    ) {
        assertEquals("zoom", expected.zoom, actual.zoom, EPSILON)
        assertEquals("pan x", expected.panX, actual.panX, EPSILON)
        assertEquals("pan y", expected.panY, actual.panY, EPSILON)
        assertEquals("turn", expected.turn, actual.turn, EPSILON)
    }

    /** Two fingers 200 px apart about the view's middle, turned [angle] anticlockwise on screen. */
    private fun CameraGesture.twistTo(angle: Double) {
        val dx = (HALF_SPAN * cos(angle)).toFloat()
        val dy = (HALF_SPAN * sin(angle)).toFloat()
        move(MID - dx, MID + dy, MID + dx, MID - dy)
    }

    @Test
    fun `fingers that rest or barely move leave the Camera alone`() {
        val camera = Camera()
        val gesture = gesture(camera)

        gesture.start(400f, 500f, 600f, 500f)
        gesture.move(405f, 500f, 605f, 500f)
        gesture.move(400f, 500f, 610f, 500f)
        gesture.twistTo(0.1)
        assertFalse(gesture.placing)
        gesture.end()

        assertEquals(emptyList<CameraGesture.Placement>(), camera.previews)
        assertEquals(emptyList<CameraGesture.Placement>(), camera.commits)
    }

    @Test
    fun `a pinch scales the zoom it started from, measured from where it took over`() {
        val camera = Camera(CameraGesture.Placement(zoom = 1.5f))
        val gesture = gesture(camera)

        gesture.start(400f, 500f, 600f, 500f)
        gesture.move(380f, 500f, 620f, 500f)
        assertTrue(gesture.placing)
        assertEquals(emptyList<CameraGesture.Placement>(), camera.previews)

        gesture.move(260f, 500f, 740f, 500f)
        assertPlacement(CameraGesture.Placement(zoom = 3f), camera.previews.single())
    }

    @Test
    fun `a drag pans the picture with the fingers, up is up`() {
        val camera = Camera()
        val gesture = gesture(camera)

        gesture.start(400f, 500f, 600f, 500f)
        gesture.move(450f, 500f, 650f, 500f)
        gesture.move(550f, 450f, 750f, 450f)

        // 100 px right of a 1000 px view is a tenth of it, a fifth of a full pan (half the frame).
        assertPlacement(CameraGesture.Placement(panX = 0.2f, panY = 0.1f), camera.previews.last())
    }

    @Test
    fun `a pan is a share of the view, whatever its size`() {
        val camera = Camera()
        val gesture = CameraGesture(SLOP, camera).apply { resize(500f, 250f) }

        gesture.start(100f, 100f, 300f, 100f)
        gesture.move(150f, 100f, 350f, 100f)
        gesture.move(200f, 75f, 400f, 75f)

        assertPlacement(CameraGesture.Placement(panX = 0.2f, panY = 0.2f), camera.previews.last())
    }

    @Test
    fun `a twist turns the Camera, anticlockwise positive, across the back of the circle and no further than half a turn`() {
        val camera = Camera()
        val gesture = gesture(camera)
        gesture.start(MID - HALF_SPAN, MID, MID + HALF_SPAN, MID)
        gesture.twistTo(TAKE_OVER)
        assertTrue(gesture.placing)

        gesture.twistTo(TAKE_OVER + PI / 2)
        assertPlacement(CameraGesture.Placement(turn = 0.5f), camera.previews.last())
        // Past the back of the circle the direction reads as negative; the turn carries on regardless.
        gesture.twistTo(TAKE_OVER + PI)
        assertPlacement(CameraGesture.Placement(turn = 1f), camera.previews.last())
        gesture.twistTo(TAKE_OVER + 1.4 * PI)
        assertEquals(1f, camera.previews.last().turn, 0f)

        // Twisted back the other way, past where it started.
        gesture.twistTo(TAKE_OVER + PI / 2)
        assertPlacement(CameraGesture.Placement(turn = 0.5f), camera.previews.last())
        gesture.twistTo(TAKE_OVER)
        gesture.twistTo(TAKE_OVER - PI / 2)
        assertPlacement(CameraGesture.Placement(turn = -0.5f), camera.previews.last())
    }

    @Test
    fun `the Camera stays where the gesture leaves it`() {
        val camera = Camera()
        val gesture = gesture(camera)
        gesture.start(400f, 500f, 600f, 500f)
        gesture.move(380f, 500f, 620f, 500f)
        gesture.move(320f, 400f, 680f, 400f)
        val left = camera.previews.last()

        gesture.end()

        assertEquals(listOf(left), camera.commits)
        assertEquals(left, camera.value)
        assertFalse(gesture.placing)
        // Nothing eases it back afterwards: moves with no gesture under way do nothing.
        gesture.move(400f, 500f, 600f, 500f)
        gesture.end()
        assertEquals(listOf(left), camera.commits)
        assertEquals(left, camera.value)
    }

    @Test
    fun `the next gesture starts from where the last one left the Camera`() {
        val camera = Camera()
        val gesture = gesture(camera)
        gesture.start(400f, 500f, 600f, 500f)
        gesture.move(380f, 500f, 620f, 500f)
        gesture.move(260f, 500f, 740f, 500f)
        gesture.end()

        gesture.start(400f, 500f, 600f, 500f)
        gesture.move(450f, 500f, 650f, 500f)
        gesture.move(550f, 500f, 750f, 500f)
        gesture.end()

        assertPlacement(CameraGesture.Placement(zoom = 2f), camera.commits[0])
        assertPlacement(CameraGesture.Placement(zoom = 2f, panX = 0.2f), camera.commits[1])
    }

    @Test
    fun `the placement stays inside the Camera's range`() {
        val camera = Camera()
        val gesture = gesture(camera)
        gesture.start(450f, 500f, 550f, 500f)
        gesture.move(430f, 500f, 570f, 500f)

        gesture.move(-2000f, -900f, 2000f, -900f)
        assertPlacement(CameraGesture.Placement(zoom = TouchTransform.ZOOM_MAX, panX = -1f, panY = 1f), camera.previews.last())
        gesture.move(1999.5f, 2000f, 2000f, 2000f)
        assertPlacement(CameraGesture.Placement(zoom = TouchTransform.ZOOM_MIN, panX = 1f, panY = -1f), camera.previews.last())
    }

    @Test
    fun `a move that changes nothing is not sent again`() {
        val camera = Camera()
        val gesture = gesture(camera)
        gesture.start(400f, 500f, 600f, 500f)
        gesture.move(380f, 500f, 620f, 500f)

        gesture.move(260f, 500f, 740f, 500f)
        gesture.move(260f, 500f, 740f, 500f)

        assertEquals(1, camera.previews.size)
    }

    @Test
    fun `a change of pair carries on from where the Camera is`() {
        val camera = Camera()
        val gesture = gesture(camera)
        gesture.start(400f, 500f, 600f, 500f)
        gesture.move(380f, 500f, 620f, 500f)
        gesture.move(260f, 500f, 740f, 500f)

        // A third finger lands: the new pair is elsewhere and further apart, and the picture does not jump.
        gesture.start(100f, 100f, 700f, 100f)
        gesture.move(100f, 100f, 700f, 100f)
        assertEquals(1, camera.previews.size)
        gesture.move(100f, 100f, 1000f, 100f)
        assertEquals(3f, camera.previews.last().zoom, EPSILON)
    }

    @Test
    fun `with the setting off two fingers are the Style's alone`() {
        val camera = Camera()
        val gesture = gesture(camera)
        gesture.enabled = false

        gesture.start(400f, 500f, 600f, 500f)
        gesture.move(380f, 500f, 620f, 500f)
        gesture.move(260f, 400f, 740f, 400f)
        gesture.end()

        assertEquals(emptyList<CameraGesture.Placement>(), camera.previews)
        assertEquals(emptyList<CameraGesture.Placement>(), camera.commits)
    }

    @Test
    fun `fingers on the same spot, or no reading, measure nothing`() {
        val camera = Camera()
        val gesture = gesture(camera)

        gesture.start(500f, 500f, 500f, 500f)
        gesture.move(100f, 500f, 900f, 500f)
        gesture.start(Float.NaN, 500f, 600f, 500f)
        gesture.move(100f, 500f, 900f, 500f)
        gesture.end()
        assertEquals(emptyList<CameraGesture.Placement>(), camera.previews)

        gesture.start(400f, 500f, 600f, 500f)
        gesture.move(380f, 500f, 620f, 500f)
        gesture.move(260f, 500f, 740f, 500f)
        gesture.move(500f, 500f, 500f, 500f)
        gesture.move(Float.POSITIVE_INFINITY, 500f, 600f, 500f)
        gesture.end()
        assertEquals(1, camera.previews.size)
        assertEquals(camera.previews, camera.commits)
    }

    private companion object {
        const val SLOP = 16f
        const val SIZE = 1000f
        const val MID = 500f
        const val HALF_SPAN = 100f

        /** Past the slop: the arc a 0.2 rad twist sweeps at 200 px apart is 20 px. */
        const val TAKE_OVER = 0.2

        const val EPSILON = 1e-4f
    }
}
