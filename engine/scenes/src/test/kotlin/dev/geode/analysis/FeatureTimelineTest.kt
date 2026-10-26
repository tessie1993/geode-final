package dev.geode.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An export draws frame k at time t(k) and asks the timeline for what happened in (t(k), t(k+1)].
 * Every one-hop event in the analysis must then reach exactly one exported frame, at any frame rate.
 */
class FeatureTimelineTest {
    /** Ten seconds of 60 Hz analysis frames, timed the way the offline analyser times them. */
    private fun timeline(events: Map<Int, (AudioFeatures) -> AudioFeatures>): FeatureTimeline {
        val frames =
            (0..600).map { i ->
                val quiet = AudioFeatures.empty().copy(rms = i / 1000f)
                TimelineFrame(i * 1000L / 60, events[i]?.invoke(quiet) ?: quiet)
            }
        return FeatureTimeline(frames, hopMs = 16)
    }

    private val events: Map<Int, (AudioFeatures) -> AudioFeatures> =
        mapOf(
            3 to { f -> f.copy(kick = 0.5f, beat = true, beatStrength = 0.6f) },
            7 to { f -> f.copy(snare = 0.4f, transient = 0.7f) },
            8 to { f -> f.copy(hat = 0.3f) },
            100 to { f -> f.copy(downbeat = true, sectionBoundary = true) },
            101 to { f -> f.copy(drop = true) },
            350 to { f -> f.copy(arrival = true, kick = 0.9f) },
            599 to { f -> f.copy(kick = 0.2f) },
        )

    private class Seen {
        val kicks = mutableListOf<Float>()
        var beats = 0
        var transients = 0
        var snares = 0
        var hats = 0
        var downbeats = 0
        var sections = 0
        var drops = 0
        var arrivals = 0

        fun add(f: AudioFeatures) {
            if (f.kick > 0f) kicks += f.kick
            if (f.beat) beats++
            if (f.transient > 0f) transients++
            if (f.snare > 0f) snares++
            if (f.hat > 0f) hats++
            if (f.downbeat) downbeats++
            if (f.sectionBoundary) sections++
            if (f.drop) drops++
            if (f.arrival) arrivals++
        }
    }

    @Test
    fun `an export at 30, 60 or 120 fps draws every event exactly once`() {
        val t = timeline(events)
        for (fps in listOf(30, 60, 120)) {
            val seen = Seen()
            var frame = 0
            while (frame * 1000L / fps < t.durationMs) {
                // As OffscreenSceneRenderer asks for frame k.
                val timeMs = frame * 1000L / fps
                val nextTimeMs = (frame + 1) * 1000L / fps
                seen.add(t.featuresAt(timeMs, nextTimeMs - timeMs))
                frame++
            }
            assertEquals("kicks at $fps fps", listOf(0.5f, 0.9f, 0.2f), seen.kicks)
            assertEquals("beats at $fps fps", 1, seen.beats)
            assertEquals("transients at $fps fps", 1, seen.transients)
            assertEquals("snares at $fps fps", 1, seen.snares)
            assertEquals("hats at $fps fps", 1, seen.hats)
            assertEquals("downbeats at $fps fps", 1, seen.downbeats)
            assertEquals("section boundaries at $fps fps", 1, seen.sections)
            assertEquals("drops at $fps fps", 1, seen.drops)
            assertEquals("arrivals at $fps fps", 1, seen.arrivals)
        }
    }

    @Test
    fun `a span folds in every event after its start and up to its end`() {
        val t = timeline(events)

        // (100 ms, 150 ms] holds frames 7, 8 and 9 (116, 133 and 150 ms), not frame 6 (100 ms).
        val f = t.featuresAt(100L, 50L)

        assertEquals(0.4f, f.snare, 0f)
        assertEquals(0.7f, f.transient, 0f)
        assertEquals(0.3f, f.hat, 0f)
        assertEquals(0f, f.kick, 0f)
        assertFalse(f.beat)
    }

    @Test
    fun `a span folds every structural event too`() {
        val t = timeline(events)

        // (1650 ms, 1700 ms] holds frames 100, 101 and 102 (1666, 1683 and 1700 ms).
        val f = t.featuresAt(1650L, 50L)

        assertTrue(f.downbeat)
        assertTrue(f.sectionBoundary)
        assertTrue(f.drop)
        assertFalse(f.arrival)
    }

    @Test
    fun `a span with no event in it carries none, and keeps the level of its start`() {
        val t = timeline(events)

        // Frame 3 (50 ms) has a kick, but this span starts there: it holds frames 4 and 5 (66 and 83 ms).
        val f = t.featuresAt(50L, 33L)

        assertEquals(0f, f.kick, 0f)
        assertFalse(f.beat)
        assertEquals(0.003f, f.rms, 1e-6f)
    }

    @Test
    fun `without a span the frame is returned as analysed`() {
        val t = timeline(events)

        assertSame(t.frames[3].features, t.featuresAt(50L))
    }

    @Test
    fun `halving a long timeline keeps every event of both halves`() {
        val a = TimelineFrame(0L, AudioFeatures.empty().copy(kick = 0.2f, downbeat = true))
        val b =
            TimelineFrame(
                16L,
                AudioFeatures.empty().copy(kick = 0.5f, sectionBoundary = true, drop = true, arrival = true),
            )

        val merged = FrameAccumulator.merge(a, b).features

        assertEquals(0.5f, merged.kick, 0f)
        assertTrue(merged.downbeat)
        assertTrue(merged.sectionBoundary)
        assertTrue(merged.drop)
        assertTrue(merged.arrival)
    }

    /** Half a frame before frame [frame] at [fps], as OffscreenSceneRenderer centres its windows (#302). */
    private fun halfwayBefore(
        frame: Int,
        fps: Int,
    ) = Math.floorDiv((2L * frame - 1) * 1000L, 2L * fps)

    @Test
    fun `a Render's windows centred on each frame draw every event exactly once`() {
        val t = timeline(events)
        for (fps in listOf(24, 30, 60, 120)) {
            val seen = Seen()
            var frame = 0
            while (frame * 1000L / fps < t.durationMs + 1000L / fps) {
                seen.add(t.featuresAround(frame * 1000L / fps, halfwayBefore(frame, fps), halfwayBefore(frame + 1, fps)))
                frame++
            }
            assertEquals("kicks at $fps fps", listOf(0.5f, 0.9f, 0.2f), seen.kicks)
            assertEquals("beats at $fps fps", 1, seen.beats)
            assertEquals("snares at $fps fps", 1, seen.snares)
            assertEquals("hats at $fps fps", 1, seen.hats)
            assertEquals("drops at $fps fps", 1, seen.drops)
            assertEquals("arrivals at $fps fps", 1, seen.arrivals)
        }
    }

    @Test
    fun `an event is drawn on the frame nearest the moment it is heard, never a frame early`() {
        val t = timeline(events)

        // At 30 fps frame 2 is at 66 ms, its window (50 ms, 83 ms]: frame 3's kick (50 ms) is not in it.
        assertEquals(0f, t.featuresAround(66L, halfwayBefore(2, 30), halfwayBefore(3, 30)).kick, 0f)
        // Frame 1 is at 33 ms, its window (16 ms, 50 ms]: the kick heard at 50 ms is drawn there.
        assertEquals(0.5f, t.featuresAround(33L, halfwayBefore(1, 30), halfwayBefore(2, 30)).kick, 0f)
        // The continuous values are those of the frame's own moment.
        assertEquals(0.002f, t.featuresAround(33L, halfwayBefore(1, 30), halfwayBefore(2, 30)).rms, 1e-6f)
        val none = FeatureTimeline(emptyList(), hopMs = 16).featuresAround(0L, -10L, 10L)
        assertEquals(0f, none.rms, 0f)
        assertFalse(none.beat)
    }

    @Test
    fun `levels stamped at the middle of their window are drawn at that moment, not half a window early`() {
        // As the offline analyser stamps them (#302): 21 ms in, then every 16.67 ms.
        val frames = (0..600).map { i -> TimelineFrame(21L + i * 1000L / 60, AudioFeatures.empty().copy(rms = i / 1000f)) }
        val t = FeatureTimeline(frames, hopMs = 16)

        // At 100 ms the nearest hop is 5 (104 ms), not 6 (121 ms), which counting hops from 0 would give.
        assertEquals(0.005f, t.featuresAt(100L).rms, 1e-6f)
        assertEquals(0.299f, t.featuresAt(5_000L).rms, 1e-6f)
        // Before the first hop, the first; after the last, the last; halfway between two, the later.
        assertEquals(0f, t.featuresAt(0L).rms, 0f)
        assertEquals(0.6f, t.featuresAt(99_000L).rms, 1e-6f)
        assertEquals(0.001f, t.featuresAt(29L).rms, 1e-6f)
        assertEquals(0f, t.featuresAt(28L).rms, 0f)
    }

    @Test
    fun `the track's tempo is the one it settled on, not its last hop's`() {
        fun track(vararg bpm: Float) =
            FeatureTimeline(bpm.mapIndexed { i, b -> TimelineFrame(i * 16L, AudioFeatures.empty().copy(bpm = b)) }, hopMs = 16)

        // A fade-out drifts the last hops; the tempo read across the second half holds.
        assertEquals(124f, track(0f, 90f, 124f, 124f, 124f, 124f, 124f, 131f).bpm, 0f)
        // The tracker's whole range counts, up to 250 BPM.
        assertEquals(240f, track(240f, 240f, 240f).bpm, 0f)
        assertEquals(0f, track(0f, 0f).bpm, 0f)
        assertEquals(0f, FeatureTimeline(emptyList(), hopMs = 16).bpm, 0f)
    }
}
