package dev.geode.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** A Render's Song map: the beat grid, bars, sections, builds and drops of a whole track (#320). */
class SongMapTest {
    private val hops = 1_200

    private fun time(i: Int): Long = i * 1_000L / 60

    private val times = LongArray(hops + 1) { time(it) }

    private fun frameAt(t: Long): Int = SongMap.nearest(times, t)

    /**
     * Twenty seconds at 120 bpm: an onset on every beat from 100 ms, the low end hitting hardest on every
     * fourth beat from 600 ms, a section change the live detector noticed at 4.9 s and a drop at 8.7 s.
     */
    private fun track(bpm: Float = 120f): FeatureTimeline {
        val beats = (0 until 40).map { 100L + 500L * it }
        val onsets = beats.map(::frameAt).toSet()
        val strong = beats.filterIndexed { k, _ -> k % 4 == 1 }.map(::frameAt).toSet()
        val frames =
            (0..hops).map { i ->
                var f = AudioFeatures.empty().copy(bpm = bpm, rms = i / 2_000f)
                if (i in onsets) f = f.copy(onset = 1f, beat = true, beatStrength = 0.8f)
                if (i in strong) f = f.copy(bass = 1f, kick = 0.9f)
                if (i == frameAt(4_900)) f = f.copy(sectionBoundary = true)
                if (i == frameAt(8_700)) f = f.copy(drop = true)
                TimelineFrame(time(i), f)
            }
        return FeatureTimeline(frames, hopMs = 16)
    }

    private fun near(
        expected: Long,
        actual: Long,
    ) = assertTrue("$actual is not $expected", abs(expected - actual) <= 1)

    @Test
    fun `the grid follows the tempo and the onsets, and bars start where the low end hits`() {
        val map = SongMap.of(track())
        assertEquals(500.0, map.beatMs, 1e-9)
        near(100, map.beats.first())
        near(600, map.beats[1] - map.beats[0] + 100)
        near(600, map.downbeats.first())
        near(2_600, map.downbeats[1])
    }

    @Test
    fun `sections and drops land on the bar where they begin, with a build before each drop`() {
        val map = SongMap.of(track())
        assertEquals(1, map.sections.size)
        near(4_600, map.sections.single())
        near(8_600, map.drops.single())
        val (start, drop) = map.builds.single()
        near(600, start)
        near(8_600, drop)
    }

    @Test
    fun `a Render draws each hit early by the rise time, and the map's bars, sections and drops`() {
        val timeline = track()
        val drawn = SongMap.of(timeline).choreographed(timeline)
        val lead = SongMap.RISE_LEAD_MS
        // The beat heard at 5.1 s is drawn 150 ms before it, and no longer where it was heard.
        assertTrue(drawn.frames[frameAt(5_100 - lead)].features.beat)
        assertFalse(drawn.frames[frameAt(5_100)].features.beat)
        assertTrue(drawn.frames[frameAt(4_600 - lead)].features.sectionBoundary)
        assertFalse(drawn.frames[frameAt(4_900)].features.sectionBoundary)
        assertTrue(drawn.frames[frameAt(8_600 - lead)].features.drop)
        assertTrue(drawn.frames[frameAt(2_600 - lead)].features.downbeat)
        assertEquals(timeline.frames.count { it.features.beat }, drawn.frames.count { it.features.beat })
        // Continuous values stay where they were heard; the build rises through the bars before the drop.
        assertEquals(timeline.frames[700].features.rms, drawn.frames[700].features.rms, 0f)
        assertEquals(0.5f, drawn.frames[frameAt(4_600)].features.buildup, 0.01f)
        assertEquals(0f, drawn.frames[frameAt(9_000)].features.buildup, 0f)
        assertEquals(timeline.frames.size, drawn.frames.size)
    }

    @Test
    fun `a build never lowers what the analysis heard`() {
        val loud = track().let { t -> FeatureTimeline(t.frames.map { it.copy(features = it.features.copy(buildup = 0.9f)) }, t.hopMs) }
        val drawn = SongMap.of(loud).choreographed(loud)
        assertEquals(0.9f, drawn.frames[frameAt(4_600)].features.buildup, 0f)
    }

    @Test
    fun `with no tempo the map keeps what was heard`() {
        val map = SongMap.of(track(bpm = 0f))
        assertEquals(0.0, map.beatMs, 0.0)
        assertEquals(40, map.beats.size)
        assertEquals(listOf(time(frameAt(4_900))), map.sections)
        assertEquals(emptyList<Pair<Long, Long>>(), map.builds)
        val empty = SongMap.of(FeatureTimeline(emptyList(), hopMs = 16))
        assertTrue(empty.beats.isEmpty())
        assertTrue(empty.choreographed(FeatureTimeline(emptyList(), hopMs = 16)).frames.isEmpty())
    }

    @Test
    fun `the tempo is what the second half settled on, within a playable range`() {
        fun frames(vararg bpm: Float) = bpm.mapIndexed { i, b -> TimelineFrame(i.toLong(), AudioFeatures.empty().copy(bpm = b)) }
        assertEquals(128f, SongMap.tempoOf(frames(90f, 90f, 128f, 300f, 128f, 127f)) ?: 0f, 0f)
        assertNull(SongMap.tempoOf(frames(120f, 0f, 400f)))
        assertNull(SongMap.tempoOf(emptyList()))
    }

    @Test
    fun `a heard change moves back to its bar, and two on one bar are one`() {
        val bars = listOf(0L, 2_000L, 4_000L)
        assertEquals(listOf(2_000L), SongMap.snapped(listOf(2_900L, 3_100L, 1_800L), bars, 500.0))
        assertEquals(listOf(0L, 4_000L), SongMap.snapped(listOf(4_200L, 100L), bars, 500.0))
        assertEquals(listOf(5L, 7L), SongMap.snapped(listOf(5L, 7L, 5L), emptyList(), 500.0))
    }

    @Test
    fun `the nearest frame is found from either side`() {
        val at = longArrayOf(0, 10, 20)
        assertEquals(0, SongMap.nearest(at, -5))
        assertEquals(0, SongMap.nearest(at, 5))
        assertEquals(1, SongMap.nearest(at, 6))
        assertEquals(2, SongMap.nearest(at, 99))
    }
}
