package dev.geode.ui

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * The gesture rule of #290, against real [MotionEvent]s: every touch reaches the Visual, a tap leaves
 * a short pulse, and only a double tap calls the host's toggle; a drag never does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class VisualTouchInputRobolectricTest {
    private class Recorder {
        var count = -1
        val submissions = mutableListOf<Int>()
        var strokes = 0
        var doubleTaps = 0

        val touch =
            VisualTouch(
                submit = { _, n ->
                    count = n
                    submissions += n
                },
                stroke = { _, _, _, _, _, _ -> strokes++ },
            )
    }

    private lateinit var activity: Activity
    private lateinit var view: View

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        view = View(activity)
        activity.setContentView(view)
        view.layout(0, 0, 1_000, 1_000)
    }

    @After
    fun tearDown() {
        activity.finish()
    }

    private fun input(recorder: Recorder) = VisualTouchInput(activity, recorder.touch) { recorder.doubleTaps++ }

    private fun event(
        action: Int,
        time: Long,
        x: Float = 100f,
        y: Float = 100f,
    ): MotionEvent = MotionEvent.obtain(0L, time, action, x, y, 0)

    private data class Finger(
        val id: Int,
        val x: Float,
        val y: Float,
    )

    private fun multi(
        action: Int,
        time: Long,
        vararg fingers: Finger,
    ): MotionEvent {
        val props =
            fingers
                .map {
                    MotionEvent.PointerProperties().apply {
                        id = it.id
                        toolType = MotionEvent.TOOL_TYPE_FINGER
                    }
                }.toTypedArray()
        val coords =
            fingers
                .map {
                    MotionEvent.PointerCoords().apply {
                        x = it.x
                        y = it.y
                        pressure = 1f
                        size = 1f
                    }
                }.toTypedArray()
        return MotionEvent.obtain(0L, time, action, fingers.size, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    private fun tap(
        input: VisualTouchInput,
        at: Long,
    ) {
        input.onTouch(view, event(MotionEvent.ACTION_DOWN, at))
        input.onTouch(view, event(MotionEvent.ACTION_UP, at + 60L))
    }

    @Test
    fun `a single tap reaches the Visual as a pulse that the host clears, and toggles nothing`() {
        val recorder = Recorder()
        val input = input(recorder)

        assertTrue(input.onTouch(view, event(MotionEvent.ACTION_DOWN, 0L)))
        assertTrue(input.onTouch(view, event(MotionEvent.ACTION_UP, 80L)))

        assertEquals(0, recorder.doubleTaps)
        assertEquals(listOf(1, 0, 1), recorder.submissions)

        shadowOf(Looper.getMainLooper()).idleFor(TAP_PULSE_MAX_WAIT_MS, TimeUnit.MILLISECONDS)
        assertEquals(0, recorder.count)
    }

    @Test
    fun `a double tap calls the host's toggle once`() {
        val recorder = Recorder()
        val input = input(recorder)

        tap(input, at = 0L)
        tap(input, at = 150L)

        assertEquals(1, recorder.doubleTaps)
    }

    @Test
    fun `a third tap starts a new pair rather than toggling again`() {
        val recorder = Recorder()
        val input = input(recorder)

        tap(input, at = 0L)
        tap(input, at = 150L)
        tap(input, at = 300L)

        assertEquals(1, recorder.doubleTaps)
    }

    @Test
    fun `two taps further apart than the double-tap timeout are two single taps`() {
        val recorder = Recorder()
        val input = input(recorder)
        val apart = ViewConfiguration.getDoubleTapTimeout() + 200L

        tap(input, at = 0L)
        tap(input, at = apart)

        assertEquals(0, recorder.doubleTaps)
    }

    @Test
    fun `two taps far apart on the screen are two single taps`() {
        val recorder = Recorder()
        val input = input(recorder)

        input.onTouch(view, event(MotionEvent.ACTION_DOWN, 0L, 100f, 100f))
        input.onTouch(view, event(MotionEvent.ACTION_UP, 60L, 100f, 100f))
        input.onTouch(view, event(MotionEvent.ACTION_DOWN, 150L, 900f, 900f))
        input.onTouch(view, event(MotionEvent.ACTION_UP, 210L, 900f, 900f))

        assertEquals(0, recorder.doubleTaps)
    }

    @Test
    fun `a drag draws in the Visual and never counts as a tap`() {
        val recorder = Recorder()
        val input = input(recorder)

        tap(input, at = 0L)
        input.onTouch(view, event(MotionEvent.ACTION_DOWN, 100L))
        input.onTouch(view, event(MotionEvent.ACTION_MOVE, 116L, 600f, 600f))
        input.onTouch(view, event(MotionEvent.ACTION_MOVE, 132L))
        input.onTouch(view, event(MotionEvent.ACTION_UP, 148L))

        assertEquals(0, recorder.doubleTaps)
        assertTrue(recorder.strokes > 0)
        assertEquals(0, recorder.count)
    }

    @Test
    fun `a press held past the tap timeout is a touch, not a tap`() {
        val recorder = Recorder()
        val input = input(recorder)
        val tooLong = ViewConfiguration.getLongPressTimeout() + 1L

        tap(input, at = 0L)
        input.onTouch(view, event(MotionEvent.ACTION_DOWN, 100L))
        input.onTouch(view, event(MotionEvent.ACTION_UP, 100L + tooLong))

        assertEquals(0, recorder.doubleTaps)
        assertEquals(0, recorder.count)
    }

    @Test
    fun `a second finger takes the gesture out of the tap path`() {
        val recorder = Recorder()
        val input = input(recorder)
        val pointerDown = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val pointerUp = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

        tap(input, at = 0L)
        input.onTouch(view, multi(MotionEvent.ACTION_DOWN, 100L, Finger(0, 100f, 100f)))
        input.onTouch(view, multi(pointerDown, 116L, Finger(0, 100f, 100f), Finger(1, 300f, 300f)))
        assertEquals(2, recorder.count)
        input.onTouch(view, multi(pointerUp, 132L, Finger(0, 100f, 100f), Finger(1, 300f, 300f)))
        assertEquals(1, recorder.count)
        input.onTouch(view, multi(MotionEvent.ACTION_UP, 148L, Finger(0, 100f, 100f)))

        assertEquals(0, recorder.doubleTaps)
        assertEquals(0, recorder.count)
    }

    @Test
    fun `a cancelled gesture clears the engine and calls nothing`() {
        val recorder = Recorder()
        val input = input(recorder)

        tap(input, at = 0L)
        input.onTouch(view, event(MotionEvent.ACTION_DOWN, 100L))
        input.onTouch(view, event(MotionEvent.ACTION_CANCEL, 116L))

        assertEquals(0, recorder.doubleTaps)
        assertEquals(0, recorder.count)
        assertEquals(0, recorder.submissions.last())
    }

    private class Camera : CameraGesture.Target {
        var value = CameraGesture.Placement()
        val commits = mutableListOf<CameraGesture.Placement>()

        override fun placement(): CameraGesture.Placement = value

        override fun preview(placement: CameraGesture.Placement) {
            value = placement
        }

        override fun commit(placement: CameraGesture.Placement) {
            commits += placement
            value = placement
        }
    }

    private fun placing(
        recorder: Recorder,
        camera: Camera,
    ) = VisualTouchInput(activity, recorder.touch, CameraGesture(SLOP, camera)) { recorder.doubleTaps++ }

    @Test
    fun `two fingers spreading and sliding place the Camera, reach the Style too, and it stays after they lift`() {
        val recorder = Recorder()
        val camera = Camera()
        val input = placing(recorder, camera)
        val pointerDown = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val pointerUp = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

        input.onTouch(view, multi(MotionEvent.ACTION_DOWN, 0L, Finger(0, 400f, 500f)))
        input.onTouch(view, multi(pointerDown, 16L, Finger(0, 400f, 500f), Finger(1, 600f, 500f)))
        // Past the slop: the gesture is measured from here, a 300 px span about the middle.
        input.onTouch(view, multi(MotionEvent.ACTION_MOVE, 32L, Finger(0, 350f, 500f), Finger(1, 650f, 500f)))
        // Twice as far apart, and 100 px up the 1000 px view.
        input.onTouch(view, multi(MotionEvent.ACTION_MOVE, 48L, Finger(0, 200f, 400f), Finger(1, 800f, 400f)))
        assertEquals(2, recorder.count)
        assertEquals(2f, camera.value.zoom, 1e-5f)
        assertEquals(0.2f, camera.value.panY, 1e-5f)

        input.onTouch(view, multi(pointerUp, 64L, Finger(0, 200f, 400f), Finger(1, 800f, 400f)))
        input.onTouch(view, multi(MotionEvent.ACTION_UP, 80L, Finger(0, 200f, 400f)))

        assertEquals(1, camera.commits.size)
        assertEquals(2f, camera.commits.single().zoom, 1e-5f)
        assertEquals(0.2f, camera.value.panY, 1e-5f)
        assertEquals(0, recorder.count)
        assertEquals(0, recorder.doubleTaps)
    }

    @Test
    fun `two fingers twisting turn the Camera`() {
        val recorder = Recorder()
        val camera = Camera()
        val input = placing(recorder, camera)
        val pointerDown = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

        input.onTouch(view, multi(MotionEvent.ACTION_DOWN, 0L, Finger(0, 400f, 500f)))
        input.onTouch(view, multi(pointerDown, 16L, Finger(0, 400f, 500f), Finger(1, 600f, 500f)))
        // A quarter turn anticlockwise in two steps: the first takes the gesture over, the second turns it.
        input.onTouch(view, multi(MotionEvent.ACTION_MOVE, 32L, Finger(0, 429.3f, 570.7f), Finger(1, 570.7f, 429.3f)))
        input.onTouch(view, multi(MotionEvent.ACTION_MOVE, 48L, Finger(0, 570.7f, 570.7f), Finger(1, 429.3f, 429.3f)))
        input.onTouch(view, multi(MotionEvent.ACTION_CANCEL, 64L, Finger(0, 570.7f, 570.7f), Finger(1, 429.3f, 429.3f)))

        assertEquals(0.5f, camera.commits.single().turn, 1e-3f)
        assertEquals(1f, camera.value.zoom, 1e-3f)
    }

    @Test
    fun `one finger never moves the Camera`() {
        val recorder = Recorder()
        val camera = Camera()
        val input = placing(recorder, camera)

        input.onTouch(view, event(MotionEvent.ACTION_DOWN, 0L, 100f, 100f))
        input.onTouch(view, event(MotionEvent.ACTION_MOVE, 16L, 900f, 900f))
        input.onTouch(view, event(MotionEvent.ACTION_UP, 32L, 900f, 900f))

        assertEquals(CameraGesture.Placement(), camera.value)
        assertEquals(emptyList<CameraGesture.Placement>(), camera.commits)
    }

    private companion object {
        /** Far below the moves the two-finger tests make, whatever the device's own slop. */
        const val SLOP = 8f

        /** Comfortably past [VisualTouchInput]'s pulse, which is a few frames. */
        const val TAP_PULSE_MAX_WAIT_MS = 250L
    }
}
