package dev.geode.ui

import android.app.Application
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
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
 * The gesture rule of #218, against real [MotionEvent]s: a press and release inside touch slop and
 * the tap timeout calls the host's tap once and leaves a short pulse; a drag never calls it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class VisualTouchInputRobolectricTest {
    private class Recorder {
        var count = -1
        val submissions = mutableListOf<Int>()
        var strokes = 0
        var taps = 0

        val touch =
            VisualTouch(
                submit = { _, n ->
                    count = n
                    submissions += n
                },
                stroke = { _, _, _, _, _, _ -> strokes++ },
            )
    }

    private lateinit var activity: ComponentActivity
    private lateinit var view: View

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        view = View(activity)
        activity.setContentView(view)
        view.layout(0, 0, 1_000, 1_000)
    }

    @After
    fun tearDown() {
        activity.finish()
    }

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
        return MotionEvent.obtain(
            0L,
            time,
            action,
            fingers.size,
            props,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
    }

    @Test
    fun `a tap calls onTap once and leaves a pulse that the host clears`() {
        val recorder = Recorder()
        val input = VisualTouchInput(activity, recorder.touch) { recorder.taps++ }

        assertTrue(input.onTouch(view, event(MotionEvent.ACTION_DOWN, 0L)))
        assertTrue(input.onTouch(view, event(MotionEvent.ACTION_UP, 80L)))

        assertEquals(1, recorder.taps)
        assertEquals(1, recorder.count)
        assertEquals(listOf(1, 0, 1), recorder.submissions)

        shadowOf(Looper.getMainLooper()).idleFor(TAP_PULSE_MAX_WAIT_MS, TimeUnit.MILLISECONDS)
        assertEquals(0, recorder.count)
    }

    @Test
    fun `a drag smears but never calls onTap`() {
        val recorder = Recorder()
        val input = VisualTouchInput(activity, recorder.touch) { recorder.taps++ }

        input.onTouch(view, event(MotionEvent.ACTION_DOWN, 0L))
        input.onTouch(view, event(MotionEvent.ACTION_MOVE, 16L, 600f, 600f))
        input.onTouch(view, event(MotionEvent.ACTION_UP, 32L, 600f, 600f))

        assertEquals(0, recorder.taps)
        assertTrue(recorder.strokes > 0)
        assertEquals(0, recorder.count)
    }

    @Test
    fun `a drag that returns to where it started is still a drag`() {
        val recorder = Recorder()
        val input = VisualTouchInput(activity, recorder.touch) { recorder.taps++ }

        input.onTouch(view, event(MotionEvent.ACTION_DOWN, 0L))
        input.onTouch(view, event(MotionEvent.ACTION_MOVE, 16L, 600f, 600f))
        input.onTouch(view, event(MotionEvent.ACTION_MOVE, 32L))
        input.onTouch(view, event(MotionEvent.ACTION_UP, 48L))

        assertEquals(0, recorder.taps)
    }

    @Test
    fun `a press held past the tap timeout is a touch, not a tap`() {
        val recorder = Recorder()
        val input = VisualTouchInput(activity, recorder.touch) { recorder.taps++ }
        val tooLong = ViewConfiguration.getLongPressTimeout() + 1L

        input.onTouch(view, event(MotionEvent.ACTION_DOWN, 0L))
        input.onTouch(view, event(MotionEvent.ACTION_UP, tooLong))

        assertEquals(0, recorder.taps)
        assertEquals(0, recorder.count)
    }

    @Test
    fun `a second finger takes the gesture out of the tap path`() {
        val recorder = Recorder()
        val input = VisualTouchInput(activity, recorder.touch) { recorder.taps++ }
        val pointerDown = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val pointerUp = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

        input.onTouch(view, multi(MotionEvent.ACTION_DOWN, 0L, Finger(0, 100f, 100f)))
        input.onTouch(view, multi(pointerDown, 16L, Finger(0, 100f, 100f), Finger(1, 300f, 300f)))
        input.onTouch(view, multi(pointerUp, 32L, Finger(0, 100f, 100f), Finger(1, 300f, 300f)))
        input.onTouch(view, multi(MotionEvent.ACTION_UP, 48L, Finger(0, 100f, 100f)))

        assertEquals(0, recorder.taps)
        assertEquals(0, recorder.count)
    }

    @Test
    fun `a cancelled gesture clears the engine and calls nothing`() {
        val recorder = Recorder()
        val input = VisualTouchInput(activity, recorder.touch) { recorder.taps++ }

        input.onTouch(view, event(MotionEvent.ACTION_DOWN, 0L))
        input.onTouch(view, event(MotionEvent.ACTION_CANCEL, 16L))

        assertEquals(0, recorder.taps)
        assertEquals(0, recorder.count)
        assertEquals(0, recorder.submissions.last())
    }

    private companion object {
        /** Comfortably past [VisualTouchInput]'s pulse, which is a few frames. */
        const val TAP_PULSE_MAX_WAIT_MS = 250L
    }
}
