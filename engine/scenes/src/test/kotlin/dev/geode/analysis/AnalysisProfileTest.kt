package dev.geode.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Render's analysis keeps every hop of a track over 30 minutes, where the live profile halves (#345). */
class AnalysisProfileTest {
    private val hop = AudioFeatures.empty(waveformSize = 0)

    private fun keep(
        profile: AnalysisProfile,
        hops: Int,
    ): FrameAccumulator {
        val frames = FrameAccumulator(profile.maxFrames)
        repeat(hops) { frames.add(TimelineFrame(it * 1_000L / 60, hop)) }
        return frames
    }

    @Test
    fun `an hour-long track is analysed at the full rate for a Render`() {
        val hour = 60 * 60 * 60
        val render = keep(AnalysisProfile.RENDER, hour)
        assertEquals(1, render.groupSize)
        assertEquals(hour, render.size)
    }

    @Test
    fun `the live profile still halves past 30 minutes`() {
        val live = keep(AnalysisProfile.LIVE, 40 * 60 * 60)
        assertEquals(2, live.groupSize)
    }

    @Test
    fun `a Render keeps no waveform and its cache entries are its own`() {
        assertTrue(AnalysisProfile.LIVE.keepsWaveform)
        assertFalse(AnalysisProfile.RENDER.keepsWaveform)
        assertEquals("", AnalysisProfile.LIVE.tag)
        assertNotEquals(
            AnalysisCache.cacheKey("content://media/7", 10, 20, AnalysisIdentity.CURRENT + AnalysisProfile.LIVE.tag),
            AnalysisCache.cacheKey("content://media/7", 10, 20, AnalysisIdentity.CURRENT + AnalysisProfile.RENDER.tag),
        )
    }
}
