package dev.geode.engine.audio

import dev.geode.engine.bridge.FeatureFrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A live frame is looked at once per display frame, which may be more or less often than once per
 * hop. Edge detection on the sequenced events must report each occurrence exactly once either way.
 */
class SoundEventEdgesTest {
    /** The `GeodeEvent` block of a native frame: a sequence number then a strength, per event. */
    private class Block {
        val floats = FloatArray(SoundEvent.COUNT * 2)

        fun fire(
            event: SoundEvent,
            strength: Float,
        ) {
            floats[event.ordinal * 2] += 1f
            floats[event.ordinal * 2 + 1] = strength
        }

        fun events(): SoundEvents = SoundEvents.read(floats.copyOf(), 0)
    }

    /**
     * Hop index (at 60 per second) and strength of each kick and drop in a ten-second stretch. Kicks
     * 40 and 43 are as close as the drum picker's 50 ms refractory window lets two kicks be.
     */
    private val kicks = listOf(12 to 0.4f, 40 to 0.9f, 43 to 0.5f, 200 to 1f, 590 to 0.7f)
    private val drops = listOf(300 to 1f)

    /** The frame the analyser published after each hop. */
    private fun hops(): List<SoundEvents> {
        val block = Block()
        return (0 until 600).map { hop ->
            kicks.filter { it.first == hop }.forEach { block.fire(SoundEvent.KICK, it.second) }
            drops.filter { it.first == hop }.forEach { block.fire(SoundEvent.DROP, it.second) }
            block.events()
        }
    }

    @Test
    fun `a display at 30, 60 or 120 fps sees every event exactly once`() {
        val hops = hops()
        for (fps in listOf(30, 60, 120)) {
            val edges = SoundEventEdges()
            val kicksSeen = mutableListOf<Float>()
            var dropsSeen = 0
            var tick = 0
            while (tick * 60 / fps < hops.size + 60 / fps) {
                // The newest hop the analyser has published by this display frame.
                edges.step(hops[minOf(tick * 60 / fps, hops.size - 1)])
                if (edges.fired(SoundEvent.KICK)) kicksSeen += edges.strength(SoundEvent.KICK)
                if (edges.fired(SoundEvent.DROP)) dropsSeen++
                tick++
            }
            assertEquals("kicks at $fps fps", kicks.map { it.second }, kicksSeen)
            assertEquals("drops at $fps fps", 1, dropsSeen)
        }
    }

    @Test
    fun `two occurrences between looks fire once, with the latest strength`() {
        val block = Block()
        val edges = SoundEventEdges()
        block.fire(SoundEvent.HAT, 0.9f)
        block.fire(SoundEvent.HAT, 0.2f)

        edges.step(block.events())

        assertTrue(edges.fired(SoundEvent.HAT))
        assertEquals(0.2f, edges.strength(SoundEvent.HAT), 0f)
    }

    @Test
    fun `nothing fires before the first event`() {
        val edges = SoundEventEdges()
        edges.step(SoundEvents.NONE)

        SoundEvent.entries.forEach {
            assertFalse(edges.fired(it))
            assertEquals(0f, edges.strength(it), 0f)
        }
    }

    @Test
    fun `an event fires on the look that first sees it and not on the next`() {
        val block = Block()
        val edges = SoundEventEdges()
        block.fire(SoundEvent.SNARE, 0.6f)

        edges.step(block.events())
        assertTrue(edges.fired(SoundEvent.SNARE))
        assertEquals(0.6f, edges.strength(SoundEvent.SNARE), 0f)
        assertFalse(edges.fired(SoundEvent.HAT))

        edges.step(block.events())
        assertFalse(edges.fired(SoundEvent.SNARE))
        assertEquals(0f, edges.strength(SoundEvent.SNARE), 0f)
    }

    @Test
    fun `an analysis reset does not fire, and its first event does`() {
        val block = Block()
        val edges = SoundEventEdges()
        repeat(3) { block.fire(SoundEvent.BEAT, 1f) }
        edges.step(block.events())

        edges.step(SoundEvents.NONE)
        assertFalse(edges.fired(SoundEvent.BEAT))

        val restarted = Block()
        restarted.fire(SoundEvent.BEAT, 0.8f)
        edges.step(restarted.events())
        assertTrue(edges.fired(SoundEvent.BEAT))
        assertEquals(0.8f, edges.strength(SoundEvent.BEAT), 0f)
    }

    @Test
    fun `the events read from a native frame are written back in the same place`() {
        val frame = FloatArray(4 + SoundEvent.COUNT * 2) { it.toFloat() }
        val copy = FloatArray(frame.size)

        SoundEvents.read(frame, 4).writeTo(copy, 4)

        assertEquals(frame.drop(4), copy.drop(4))
        assertEquals(6, SoundEvents.read(frame, 4).seq(SoundEvent.TRANSIENT))
        assertEquals(7f, SoundEvents.read(frame, 4).strength(SoundEvent.TRANSIENT), 0f)
    }

    @Test
    fun `the event block matches the native frame layout`() {
        assertEquals(FeatureFrameLayout.EVENT_COUNT, SoundEvent.COUNT)
    }
}
