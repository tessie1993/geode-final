package dev.geode.ui

import dev.geode.render.scene.TouchTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A pinch zooms the Camera and the zoom stays where the fingers leave it (#397): no spring-back, one
 * commit at the end, nothing at all inside the slop or with the setting off.
 */
class PinchZoomTest {
    private class Camera(
        var value: Float = 1f,
    ) : PinchZoom.Target {
        val previews = mutableListOf<Float>()
        val commits = mutableListOf<Float>()

        override fun zoom(): Float = value

        override fun preview(zoom: Float) {
            previews += zoom
            value = zoom
        }

        override fun commit(zoom: Float) {
            commits += zoom
            value = zoom
        }
    }

    private fun pinch(camera: Camera) = PinchZoom(SLOP, camera)

    @Test
    fun `fingers that rest or move together inside the slop leave the Camera alone`() {
        val camera = Camera()
        val pinch = pinch(camera)

        pinch.start(200f)
        pinch.move(200f + SLOP)
        pinch.move(200f - SLOP)
        assertFalse(pinch.zooming)
        pinch.end()

        assertEquals(emptyList<Float>(), camera.previews)
        assertEquals(emptyList<Float>(), camera.commits)
        assertEquals(1f, camera.value, 0f)
    }

    @Test
    fun `past the slop the span scales the zoom the pinch started from, without a jump`() {
        val camera = Camera(value = 1.5f)
        val pinch = pinch(camera)

        pinch.start(200f)
        pinch.move(240f)
        assertTrue(pinch.zooming)
        // Crossing the slop is not itself a zoom.
        assertEquals(emptyList<Float>(), camera.previews)

        pinch.move(480f)
        assertEquals(listOf(3f), camera.previews)
        pinch.move(120f)
        assertEquals(0.75f, camera.previews.last(), EPSILON)
    }

    @Test
    fun `the zoom stays where the pinch leaves it`() {
        val camera = Camera()
        val pinch = pinch(camera)

        pinch.start(200f)
        pinch.move(240f)
        pinch.move(360f)
        pinch.end()

        assertEquals(listOf(1.5f), camera.commits)
        assertEquals(1.5f, camera.value, 0f)
        assertFalse(pinch.zooming)
        // Nothing eases it back afterwards: a later move without fingers does nothing.
        pinch.move(240f)
        pinch.end()
        assertEquals(listOf(1.5f), camera.commits)
        assertEquals(1.5f, camera.value, 0f)
    }

    @Test
    fun `the next pinch starts from the zoom the last one left`() {
        val camera = Camera()
        val pinch = pinch(camera)
        pinch.start(200f)
        pinch.move(240f)
        pinch.move(480f)
        pinch.end()

        pinch.start(300f)
        pinch.move(260f)
        pinch.move(130f)
        pinch.end()

        assertEquals(listOf(2f, 1f), camera.commits)
    }

    @Test
    fun `the zoom stays inside the Camera's range`() {
        val camera = Camera()
        val pinch = pinch(camera)

        pinch.start(100f)
        pinch.move(140f)
        pinch.move(140f * 10f)
        assertEquals(TouchTransform.ZOOM_MAX, camera.previews.last(), 0f)
        pinch.move(1f)
        assertEquals(TouchTransform.ZOOM_MIN, camera.previews.last(), 0f)
        pinch.end()

        assertEquals(listOf(TouchTransform.ZOOM_MIN), camera.commits)
    }

    @Test
    fun `a span that does not change the zoom is not sent again`() {
        val camera = Camera()
        val pinch = pinch(camera)

        pinch.start(100f)
        pinch.move(140f)
        pinch.move(140f * 10f)
        pinch.move(140f * 20f)

        assertEquals(listOf(TouchTransform.ZOOM_MAX), camera.previews)
    }

    @Test
    fun `a change of pair carries on from the zoom reached`() {
        val camera = Camera()
        val pinch = pinch(camera)
        pinch.start(200f)
        pinch.move(240f)
        pinch.move(480f)

        // A third finger lands: the new pair is further apart, and the picture does not jump.
        pinch.start(600f)
        pinch.move(600f)
        assertEquals(listOf(2f), camera.previews)
        pinch.move(900f)
        assertEquals(3f, camera.previews.last(), EPSILON)
    }

    @Test
    fun `with the setting off two fingers are the Style's alone`() {
        val camera = Camera()
        val pinch = pinch(camera)
        pinch.enabled = false

        pinch.start(200f)
        pinch.move(240f)
        pinch.move(480f)
        pinch.end()

        assertEquals(emptyList<Float>(), camera.previews)
        assertEquals(emptyList<Float>(), camera.commits)
    }

    @Test
    fun `fingers on the same spot, or no reading, start nothing`() {
        val camera = Camera()
        val pinch = pinch(camera)

        pinch.start(0f)
        pinch.move(400f)
        pinch.start(Float.NaN)
        pinch.move(400f)
        pinch.end()

        assertEquals(emptyList<Float>(), camera.previews)
        assertEquals(emptyList<Float>(), camera.commits)
    }

    @Test
    fun `a move with no reading is ignored mid-pinch`() {
        val camera = Camera()
        val pinch = pinch(camera)
        pinch.start(200f)
        pinch.move(240f)
        pinch.move(480f)

        pinch.move(Float.POSITIVE_INFINITY)
        pinch.move(0f)
        pinch.end()

        assertEquals(listOf(2f), camera.previews)
        assertEquals(listOf(2f), camera.commits)
    }

    private companion object {
        const val SLOP = 16f
        const val EPSILON = 1e-5f
    }
}
