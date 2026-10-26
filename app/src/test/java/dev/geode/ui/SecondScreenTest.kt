package dev.geode.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SecondScreenTest {
    private val tv = ExternalDisplay(id = 2, name = "TV")
    private val projector = ExternalDisplay(id = 3, name = "Projector")

    private fun SecondScreenState.after(vararg events: SecondScreenEvent): SecondScreenState =
        events.fold(this) { state, event -> SecondScreen.reduce(state, event) }

    private fun connected(vararg displays: ExternalDisplay) = SecondScreenEvent.DisplaysChanged(displays.toList())

    private val showing = SecondScreenState(display = tv, chosen = true)

    @Test
    fun `the state starts on the phone with nothing connected`() {
        assertEquals(SecondScreenState(display = null, chosen = false), SecondScreenState())
    }

    @Test
    fun `transitions, as a table of state, event and the state after`() {
        val table: List<Triple<SecondScreenState, SecondScreenEvent, SecondScreenState>> =
            listOf(
                // A display is connected: it is offered, not taken.
                Triple(SecondScreenState(), connected(tv), SecondScreenState(display = tv, chosen = false)),
                // The first one connected, in the platform's order.
                Triple(SecondScreenState(), connected(projector, tv), SecondScreenState(display = projector)),
                Triple(SecondScreenState(), connected(), SecondScreenState()),
                // Show takes it there; with nothing connected there is nowhere to go.
                Triple(SecondScreenState(display = tv), SecondScreenEvent.Show, showing),
                Triple(SecondScreenState(), SecondScreenEvent.Show, SecondScreenState()),
                Triple(showing, SecondScreenEvent.Show, showing),
                // Stop and a lost window both bring it back, keeping the display to offer again.
                Triple(showing, SecondScreenEvent.Stop, SecondScreenState(display = tv)),
                Triple(showing, SecondScreenEvent.Lost, SecondScreenState(display = tv)),
                Triple(SecondScreenState(display = tv), SecondScreenEvent.Stop, SecondScreenState(display = tv)),
                // The display in use stays in use while it is still connected, whatever else comes and goes.
                Triple(showing, connected(projector, tv), showing),
                Triple(showing, connected(tv), showing),
                Triple(SecondScreenState(display = tv), connected(projector, tv), SecondScreenState(display = tv)),
                // Unplugged: back on the phone, and the choice goes with the display it was for.
                Triple(showing, connected(), SecondScreenState()),
                Triple(showing, connected(projector), SecondScreenState(display = projector, chosen = false)),
            )
        for ((before, event, after) in table) {
            assertEquals("$before + $event", after, SecondScreen.reduce(before, event))
        }
    }

    @Test
    fun `a display renamed while in use keeps the choice and takes the new name`() {
        val renamed = ExternalDisplay(id = tv.id, name = "Living room")
        assertEquals(SecondScreenState(display = renamed, chosen = true), showing.after(connected(renamed)))
    }

    @Test
    fun `a display that comes back after a disconnect is offered again, not taken`() {
        val state = SecondScreenState().after(connected(tv), SecondScreenEvent.Show, connected(), connected(tv))
        assertEquals(SecondScreenState(display = tv, chosen = false), state)
        assertEquals(VisualHome.PHONE, SecondScreen.home(state, visualsOff = false, inPip = false))
        assertEquals(tv, SecondScreen.offered(state, visualsOff = false))
    }

    @Test
    fun `which surface is live`() {
        val offeredOnly = SecondScreenState(display = tv)
        val chosenButGone = SecondScreenState(display = null, chosen = true)
        val table: List<Triple<SecondScreenState, Pair<Boolean, Boolean>, VisualHome>> =
            listOf(
                // (visualsOff, inPip)
                Triple(SecondScreenState(), false to false, VisualHome.PHONE),
                Triple(offeredOnly, false to false, VisualHome.PHONE),
                Triple(showing, false to false, VisualHome.SECOND_SCREEN),
                Triple(chosenButGone, false to false, VisualHome.PHONE),
                // Visuals off: drawn nowhere, wherever it was.
                Triple(showing, true to false, VisualHome.NONE),
                Triple(SecondScreenState(), true to false, VisualHome.NONE),
                Triple(showing, true to true, VisualHome.NONE),
                // The picture-in-picture window shows the Visual itself.
                Triple(showing, false to true, VisualHome.PHONE),
                Triple(SecondScreenState(), false to true, VisualHome.PHONE),
            )
        for ((state, flags, home) in table) {
            val (visualsOff, inPip) = flags
            assertEquals("$state visualsOff=$visualsOff inPip=$inPip", home, SecondScreen.home(state, visualsOff, inPip))
        }
    }

    @Test
    fun `a connected display is offered only while the Visual is on and not already there`() {
        assertEquals(tv, SecondScreen.offered(SecondScreenState(display = tv), visualsOff = false))
        assertNull(SecondScreen.offered(SecondScreenState(display = tv), visualsOff = true))
        assertNull(SecondScreen.offered(showing, visualsOff = false))
        assertNull(SecondScreen.offered(SecondScreenState(), visualsOff = false))
    }

    @Test
    fun `the display the app itself is on is never a candidate`() {
        assertEquals(listOf(tv, projector), SecondScreen.candidates(listOf(tv, projector), own = 0))
        assertEquals(listOf(tv, projector), SecondScreen.candidates(listOf(tv, projector), own = null))
        assertEquals(listOf(projector), SecondScreen.candidates(listOf(tv, projector), own = tv.id))
        assertEquals(emptyList<ExternalDisplay>(), SecondScreen.candidates(listOf(tv), own = tv.id))
    }
}
