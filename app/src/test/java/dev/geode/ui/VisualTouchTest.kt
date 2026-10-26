package dev.geode.ui

import dev.geode.render.TouchField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The host-side half of the touch pipeline (#290): pointer bookkeeping and the two coordinate
 * systems the engine documents. No Android here - [VisualTouch] is deliberately a plain class.
 */
class VisualTouchTest {
    private class Recorder {
        var points = FloatArray(0)
        var count = -1
        val submissions = mutableListOf<Int>()
        val strokes = mutableListOf<Stroke>()

        val touch =
            VisualTouch(
                submit = { xy, n ->
                    points = xy.copyOf(n * 2)
                    count = n
                    submissions += n
                },
                stroke = { nx, ny, ndx, ndy, dt, strength ->
                    strokes += Stroke(nx, ny, ndx, ndy, dt, strength)
                },
            )

        fun last(): List<Float> = points.toList()
    }

    private data class Stroke(
        val nx: Float,
        val ny: Float,
        val ndx: Float,
        val ndy: Float,
        val dt: Float,
        val strength: Float,
    )

    private fun floats(vararg values: Float): List<Float> = values.toList()

    private fun assertPoints(
        expected: List<Float>,
        actual: List<Float>,
    ) {
        assertEquals("point count", expected.size, actual.size)
        expected.zip(actual).forEach { (want, got) -> assertEquals(want, got, 1e-6f) }
    }

    @Test
    fun `the centre of the view is the origin`() {
        val recorder = Recorder()
        recorder.touch.resize(800f, 600f)

        recorder.touch.down(0, 400f, 300f)

        assertPoints(floats(0f, 0f), recorder.last())
    }

    @Test
    fun `a point maps to y-up NDC and aspect is left to the shader`() {
        val recorder = Recorder()
        recorder.touch.resize(2_000f, 1_000f)

        recorder.touch.down(7, 500f, 250f)

        // A quarter in from the left and a quarter down from the top: x uses the width, y the
        // height, and neither is scaled by the other - lib_touch.glsl applies the aspect itself.
        assertPoints(floats(-0.5f, 0.5f), recorder.last())
        assertEquals(1, recorder.count)
    }

    @Test
    fun `the corners of a non-square view are the corners of NDC`() {
        val recorder = Recorder()
        recorder.touch.resize(1_080f, 2_400f)

        recorder.touch.down(0, 0f, 0f)
        recorder.touch.down(1, 1_080f, 2_400f)

        assertPoints(floats(-1f, 1f, 1f, -1f), recorder.last())
    }

    @Test
    fun `the same fraction survives a display rotation`() {
        val recorder = Recorder()

        recorder.touch.resize(1_000f, 2_000f)
        recorder.touch.down(0, 250f, 1_500f)
        assertPoints(floats(-0.5f, -0.5f), recorder.last())

        recorder.touch.clear()
        // The display rotated: the view's sides swapped, and the point is now the same fraction of
        // the new sides. The engine sees the same place on the picture.
        recorder.touch.resize(2_000f, 1_000f)
        recorder.touch.down(0, 500f, 750f)
        assertPoints(floats(-0.5f, -0.5f), recorder.last())
    }

    @Test
    fun `a move republishes every live pointer, latest wins`() {
        val recorder = Recorder()
        recorder.touch.resize(100f, 100f)
        recorder.touch.down(4, 0f, 0f)
        recorder.touch.down(9, 50f, 50f)

        recorder.touch.move(4, 10f, 20f, dt = 0.01f, strength = 1f)

        assertPoints(floats(-0.8f, 0.6f, 0f, 0f), recorder.last())
        assertEquals(2, recorder.count)
        assertEquals(listOf(1, 2, 2), recorder.submissions)
    }

    @Test
    fun `a move of a pointer that is not down publishes nothing`() {
        val recorder = Recorder()
        recorder.touch.resize(100f, 100f)
        recorder.touch.down(1, 10f, 10f)

        assertEquals(1, recorder.touch.move(5, 50f, 50f, dt = 0.01f, strength = 1f))
        assertEquals(1, recorder.touch.up(5))

        assertEquals(listOf(1), recorder.submissions)
    }

    @Test
    fun `lifting a pointer reshuffles the ones still down`() {
        val recorder = Recorder()
        recorder.touch.resize(100f, 100f)
        recorder.touch.down(1, 10f, 10f)
        recorder.touch.down(2, 20f, 20f)
        recorder.touch.down(3, 30f, 30f)

        assertEquals(2, recorder.touch.up(2))
        assertEquals(2, recorder.touch.count)

        recorder.touch.move(3, 40f, 40f, dt = 0.01f, strength = 1f)
        assertPoints(floats(-0.8f, 0.8f, -0.2f, 0.2f), recorder.last())
    }

    @Test
    fun `down, move and up publish one, one and no pointers`() {
        val recorder = Recorder()
        recorder.touch.resize(100f, 100f)

        assertEquals(1, recorder.touch.down(0, 50f, 50f))
        assertEquals(1, recorder.touch.move(0, 60f, 50f, dt = 0.01f, strength = 1f))
        assertEquals(0, recorder.touch.up(0))

        assertEquals(listOf(1, 1, 0), recorder.submissions)
        assertEquals(0, recorder.count)
    }

    @Test
    fun `the same pointer going down twice is one pointer`() {
        val recorder = Recorder()
        recorder.touch.resize(100f, 100f)

        recorder.touch.down(3, 10f, 10f)
        recorder.touch.down(3, 90f, 90f)

        assertEquals(1, recorder.touch.count)
        assertEquals(listOf(1), recorder.submissions)
    }

    @Test
    fun `more than five fingers are ignored`() {
        val recorder = Recorder()
        recorder.touch.resize(100f, 100f)
        repeat(TouchField.MAX_POINTS) { i -> recorder.touch.down(i, i.toFloat(), 0f) }
        assertEquals(TouchField.MAX_POINTS, recorder.touch.count)

        recorder.touch.down(99, 1f, 1f)

        assertEquals(TouchField.MAX_POINTS, recorder.touch.count)
        assertEquals(TouchField.MAX_POINTS, recorder.count)
    }

    @Test
    fun `the anchor leaves one stroke sample in top-down units`() {
        val recorder = Recorder()
        recorder.touch.resize(200f, 400f)
        recorder.touch.down(0, 50f, 100f)

        recorder.touch.move(0, 150f, 300f, dt = 0.02f, strength = 0.5f)

        assertEquals(1, recorder.strokes.size)
        val stroke = recorder.strokes.single()
        // 0..1 across and down, deltas as fractions of the surface, real seconds, 0..1 strength.
        assertEquals(0.75f, stroke.nx, 1e-6f)
        assertEquals(0.75f, stroke.ny, 1e-6f)
        assertEquals(0.5f, stroke.ndx, 1e-6f)
        assertEquals(0.5f, stroke.ndy, 1e-6f)
        assertEquals(0.02f, stroke.dt, 1e-6f)
        assertEquals(0.5f, stroke.strength, 1e-6f)
    }

    @Test
    fun `only the anchor strokes`() {
        val recorder = Recorder()
        recorder.touch.resize(100f, 100f)
        recorder.touch.down(0, 10f, 10f)
        recorder.touch.down(1, 90f, 10f)

        recorder.touch.move(1, 90f, 90f, dt = 0.01f, strength = 1f)
        assertTrue(recorder.strokes.isEmpty())

        recorder.touch.move(0, 20f, 10f, dt = 0.01f, strength = 1f)
        assertEquals(1, recorder.strokes.size)
    }

    @Test
    fun `a still finger or a zero interval queues no stroke`() {
        val recorder = Recorder()
        recorder.touch.resize(100f, 100f)
        recorder.touch.down(0, 10f, 10f)

        recorder.touch.move(0, 10f, 10f, dt = 0.01f, strength = 1f)
        recorder.touch.move(0, 20f, 10f, dt = 0f, strength = 1f)
        assertTrue(recorder.strokes.isEmpty())

        recorder.touch.move(0, 20f, 15f, dt = 0.01f, strength = 1f)
        assertEquals(1, recorder.strokes.size)
    }

    @Test
    fun `strength is clamped to the range the engine documents`() {
        val recorder = Recorder()
        recorder.touch.resize(100f, 100f)
        recorder.touch.down(0, 0f, 0f)

        recorder.touch.move(0, 10f, 0f, dt = 0.01f, strength = 2f)
        recorder.touch.move(0, 20f, 0f, dt = 0.01f, strength = -1f)

        assertEquals(1f, recorder.strokes[0].strength, 1e-6f)
        assertEquals(0f, recorder.strokes[1].strength, 1e-6f)
    }

    @Test
    fun `clear publishes an empty set and a pulse publishes one point`() {
        val recorder = Recorder()
        recorder.touch.resize(100f, 100f)
        recorder.touch.down(0, 25f, 75f)

        recorder.touch.clear()
        assertEquals(0, recorder.count)
        assertEquals(0, recorder.touch.count)
        assertTrue(recorder.last().isEmpty())

        recorder.touch.pulse(25f, 75f)
        assertEquals(1, recorder.count)
        assertPoints(floats(-0.5f, -0.5f), recorder.last())
    }

    @Test
    fun `a zero-sized surface still maps to a finite point`() {
        val recorder = Recorder()
        recorder.touch.resize(0f, 0f)

        recorder.touch.down(0, 0f, 0f)

        assertPoints(floats(-1f, 1f), recorder.last())
    }
}
