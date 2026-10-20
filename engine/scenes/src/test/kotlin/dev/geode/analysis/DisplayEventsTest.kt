package dev.geode.analysis

import dev.geode.engine.audio.SoundEvent
import dev.geode.engine.audio.SoundEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The live analysis publishes a frame per hop and the Visual reads the newest one per display
 * frame. Whatever the display rate, each event must reach the Visual on exactly one frame, as the
 * one-hop field the scenes read.
 */
class DisplayEventsTest {
    /** A live frame per hop (60 per second) over five seconds, with these events fired on these hops. */
    private fun liveHops(fired: Map<Int, List<Pair<SoundEvent, Float>>>): List<AudioFeatures> {
        val block = FloatArray(SoundEvent.COUNT * 2)
        return (0 until 300).map { hop ->
            fired[hop].orEmpty().forEach { (event, strength) ->
                block[event.ordinal * 2] += 1f
                block[event.ordinal * 2 + 1] = strength
            }
            AudioFeatures.empty().copy(events = SoundEvents.read(block, 0))
        }
    }

    private val fired =
        mapOf(
            10 to listOf(SoundEvent.KICK to 0.8f, SoundEvent.TRANSIENT to 0.5f),
            11 to listOf(SoundEvent.BEAT to 0.7f),
            100 to listOf(SoundEvent.DROP to 1f, SoundEvent.SECTION_BOUNDARY to 1f),
            150 to listOf(SoundEvent.KICK to 0.3f, SoundEvent.DOWNBEAT to 1f, SoundEvent.ARRIVAL to 1f),
            220 to listOf(SoundEvent.SNARE to 0.6f, SoundEvent.HAT to 0.2f),
        )

    @Test
    fun `at 30, 60 and 120 fps every event reaches exactly one display frame`() {
        val hops = liveHops(fired)
        for (fps in listOf(30, 60, 120)) {
            val display = DisplayEvents()
            val kicks = mutableListOf<Float>()
            var beats = 0
            var beatStrength = 0f
            var transients = 0
            var snares = 0
            var hats = 0
            var downbeats = 0
            var sections = 0
            var drops = 0
            var arrivals = 0
            for (tick in 0..hops.size * fps / 60) {
                val f = display.frame(hops[minOf(tick * 60 / fps, hops.size - 1)])
                if (f.kick > 0f) kicks += f.kick
                if (f.beat) beats++
                beatStrength = maxOf(beatStrength, f.beatStrength)
                if (f.transient > 0f) transients++
                if (f.snare > 0f) snares++
                if (f.hat > 0f) hats++
                if (f.downbeat) downbeats++
                if (f.sectionBoundary) sections++
                if (f.drop) drops++
                if (f.arrival) arrivals++
            }
            assertEquals("kicks at $fps fps", listOf(0.8f, 0.3f), kicks)
            assertEquals("beats at $fps fps", 1, beats)
            assertEquals("beat strength at $fps fps", 0.7f, beatStrength, 0f)
            assertEquals("transients at $fps fps", 1, transients)
            assertEquals("snares at $fps fps", 1, snares)
            assertEquals("hats at $fps fps", 1, hats)
            assertEquals("downbeats at $fps fps", 1, downbeats)
            assertEquals("section boundaries at $fps fps", 1, sections)
            assertEquals("drops at $fps fps", 1, drops)
            assertEquals("arrivals at $fps fps", 1, arrivals)
        }
    }

    @Test
    fun `a frame between events carries none, whatever its one-hop fields held`() {
        val display = DisplayEvents()
        val stale = AudioFeatures.empty().copy(beat = true, beatStrength = 1f, transient = 0.9f, kick = 0.9f, drop = true)

        val f = display.frame(stale)

        assertFalse(f.beat)
        assertEquals(0f, f.beatStrength, 0f)
        assertEquals(0f, f.transient, 0f)
        assertEquals(0f, f.kick, 0f)
        assertFalse(f.drop)
    }
}
