package dev.geode.feature.studio.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** One continuous video moving from track to track as its frames go by (#326). */
class TrackCuesTest {
    @Test
    fun `it starts on the first track and moves on once each moment falls inside a frame`() {
        val cues = TrackCues(listOf(1_000L, 2_000L))
        assertEquals(0, cues.track)
        assertNull(cues.dueBy(1_000L))
        assertEquals(1, cues.dueBy(1_001L))
        assertEquals(1, cues.track)
        assertNull(cues.dueBy(1_500L))
        assertNull(cues.dueBy(2_000L))
        assertEquals(2, cues.dueBy(2_008L))
        assertNull(cues.dueBy(9_000L))
        assertEquals(2, cues.track)
    }

    @Test
    fun `moments that have all passed move straight to the last of them`() {
        val cues = TrackCues(listOf(1_000L, 1_004L, 5_000L))
        assertEquals(2, cues.dueBy(1_010L))
        assertEquals(3, cues.dueBy(6_000L))
    }

    @Test
    fun `a single track never moves`() {
        assertNull(TrackCues(emptyList()).dueBy(Long.MAX_VALUE))
    }
}
