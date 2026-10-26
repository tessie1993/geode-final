package dev.geode.feature.studio.core.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Markers and the live tap-in pass, moved from the old editor with their rules unchanged (#318). */
class MarkersTest {
    private fun marker(
        id: String,
        at: Long,
    ) = Marker(MarkerId(id), at)

    private val set = MarkerSet().add(marker("b", 2_000)).add(marker("a", 1_000))

    @Test
    fun `markers stay in time order and are found by id`() {
        assertEquals(listOf(1_000L, 2_000L), set.timesMs)
        assertFalse(set.isEmpty)
        assertTrue(MarkerSet().isEmpty)
        assertEquals(marker("a", 1_000), set.marker(MarkerId("a")))
        assertNull(set.marker(MarkerId("z")))
        assertEquals(listOf(1_000L, 3_000L), set.add(marker("b", 3_000)).timesMs)
        assertEquals(listOf(500L, 2_000L, 2_500L), set.addAll(listOf(marker("a", 500), marker("c", 2_500))).timesMs)
        assertEquals(listOf(2_000L), set.remove(MarkerId("a")).timesMs)
    }

    @Test
    fun `a marker is renamed, recoloured and annotated`() {
        val changed =
            set
                .rename(MarkerId("a"), "Drop")
                .recolour(MarkerId("a"), MarkerColour.RED)
                .annotate(MarkerId("a"), "the vocal")
        assertEquals(Marker(MarkerId("a"), 1_000, "Drop", MarkerColour.RED, "the vocal"), changed.marker(MarkerId("a")))
        assertEquals(marker("b", 2_000), changed.marker(MarkerId("b")))
        assertEquals(MarkerColour.CYAN, marker("x", 0).colour)
        assertEquals(MarkerOrigin.Manual, marker("x", 0).origin)
        assertEquals(0xFFE5484D.toInt(), MarkerColour.RED.argb)
    }

    @Test
    fun `dragging a marker snaps it, keeps it at or after zero, and makes a tap a manual mark`() {
        val tapped = MarkerSet(listOf(Marker(MarkerId("t"), 900, origin = MarkerOrigin.TappedIn(1_020, 120))))
        val moved = tapped.moveTo(MarkerId("t"), 1_500)
        assertEquals(1_500L, moved.marker(MarkerId("t"))!!.atMs)
        assertEquals(MarkerOrigin.Manual, moved.marker(MarkerId("t"))!!.origin)
        assertEquals(0L, set.moveTo(MarkerId("a"), -50).marker(MarkerId("a"))!!.atMs)
        val snap = SnapMode.Magnetic(setOf(SnapTarget.PLAYHEAD), toleranceMs = 40)
        assertEquals(1_520L, set.moveTo(MarkerId("a"), 1_490, snap, SnapContext(playheadMs = 1_520)).marker(MarkerId("a"))!!.atMs)
        val detected = MarkerOrigin.Detected(0.5f, 0.7f)
        val found = MarkerSet(listOf(Marker(MarkerId("d"), 100, origin = detected))).moveTo(MarkerId("d"), 200)
        assertEquals(detected, found.marker(MarkerId("d"))!!.origin)
    }

    @Test
    fun `the nearest marker within reach, and the markers in a span`() {
        assertEquals(MarkerId("a"), set.nearest(1_400)!!.id)
        assertEquals(MarkerId("b"), set.nearest(1_600)!!.id)
        assertEquals(MarkerId("a"), set.nearest(1_100, withinMs = 100)!!.id)
        assertNull(set.nearest(1_101, withinMs = 100))
        assertNull(MarkerSet().nearest(0))
        assertEquals(listOf(MarkerId("a")), set.inRange(1_000, 2_000).map { it.id })
        assertEquals(listOf(MarkerId("b")), set.inRange(1_001, 2_001).map { it.id })
    }

    @Test
    fun `markers follow a ripple and re-place their taps with a new latency`() {
        assertEquals(listOf(1_000L, 1_500L), set.rippled(RippleShift(1_500, -500)).timesMs)
        assertEquals(listOf(0L, 1_000L), set.rippled(RippleShift(1_000, -1_000)).timesMs)
        assertEquals(listOf(0L, 0L), set.rippled(RippleShift(0, -9_000)).timesMs)
        val tapped =
            MarkerSet(
                listOf(Marker(MarkerId("t"), 880, origin = MarkerOrigin.TappedIn(1_000, 120)), marker("m", 5_000)),
            )
        val recalibrated = tapped.recalibrateTaps(200)
        assertEquals(800L, recalibrated.marker(MarkerId("t"))!!.atMs)
        assertEquals(MarkerOrigin.TappedIn(1_000, 200), recalibrated.marker(MarkerId("t"))!!.origin)
        assertEquals(5_000L, recalibrated.marker(MarkerId("m"))!!.atMs)
        assertEquals(0L, tapped.recalibrateTaps(5_000).marker(MarkerId("t"))!!.atMs)
    }

    @Test
    fun `a tap lands a marker nudged back by the latency`() {
        val placed = TapInSession().tap(MarkerId("1"), 1_000)
        assertTrue(placed is TapResult.Placed)
        val marker = (placed as TapResult.Placed).marker
        assertEquals(1_000L - DEFAULT_TAP_LATENCY_MS, marker.atMs)
        assertEquals("Hit 1", marker.name)
        assertEquals(MarkerColour.AMBER, marker.colour)
        assertEquals(MarkerOrigin.TappedIn(1_000, DEFAULT_TAP_LATENCY_MS), marker.origin)
        assertEquals(1, placed.session.count)
        val early = TapInSession().tap(MarkerId("1"), 50)
        assertEquals(0L, (early as TapResult.Placed).marker.atMs)
    }

    @Test
    fun `a bounce inside the debounce window is one hit, in either direction`() {
        val first = (TapInSession().tap(MarkerId("1"), 1_000) as TapResult.Placed).session
        val bounce = first.tap(MarkerId("2"), 1_000 + DEFAULT_TAP_DEBOUNCE_MS - 1)
        assertEquals(TapResult.Debounced(first, DEFAULT_TAP_DEBOUNCE_MS - 1), bounce)
        val back = first.tap(MarkerId("2"), 1_000 - 10)
        assertEquals(TapResult.Debounced(first, 10), back)
        val next = first.tap(MarkerId("2"), 1_000 + DEFAULT_TAP_DEBOUNCE_MS)
        assertTrue(next is TapResult.Placed)
        assertEquals("Hit 2", (next as TapResult.Placed).marker.name)
    }

    @Test
    fun `a pass is undone, cleared and re-timed before it is committed`() {
        val settings = TapInSettings(latencyCompensationMs = 100, debounceMs = 50, colour = MarkerColour.LIME, namePrefix = "Beat")
        var session = TapInSession(settings)
        session = (session.tap(MarkerId("1"), 1_000) as TapResult.Placed).session
        session = (session.tap(MarkerId("2"), 2_000) as TapResult.Placed).session
        assertEquals(listOf("Beat 1", "Beat 2"), session.taps.map { it.name })
        assertEquals(MarkerColour.LIME, session.taps.first().colour)
        assertEquals(1, session.undoLast().count)
        assertEquals(0, session.clear().count)
        val retimed = session.withLatency(300)
        assertEquals(listOf(700L, 1_700L), retimed.taps.map { it.atMs })
        assertEquals(300L, retimed.settings.latencyCompensationMs)
        val existing = MarkerSet(listOf(marker("m", 1_880)))
        assertEquals(3, session.commitTo(existing).markers.size)
        assertEquals(listOf(900L, 1_880L), session.commitTo(existing, mergeWithinMs = 50).timesMs)
    }

    @Test
    fun `a debounce after a detected or manual mark is judged on its place`() {
        val manual = TapInSession(taps = listOf(marker("m", 1_000)))
        assertTrue(manual.tap(MarkerId("2"), 1_050) is TapResult.Debounced)
        val detected = TapInSession(taps = listOf(Marker(MarkerId("d"), 1_000, origin = MarkerOrigin.Detected(1f, 1f))))
        assertTrue(detected.tap(MarkerId("2"), 1_200) is TapResult.Placed)
        val emptyUndo = TapInSession()
        assertSame(emptyUndo, emptyUndo.undoLast())
    }
}
