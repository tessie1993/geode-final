package dev.geode.feature.studio.core

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.studio.api.RenderFormat
import dev.geode.feature.studio.api.RenderSize
import dev.geode.feature.studio.api.RenderTrack
import dev.geode.feature.studio.api.TrackJoin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** One continuous video's timing (#326): when the Visual moves on to each track, how long it runs, what it needs. */
class ContinuousPlanTest {
    private val night = RenderTrack(TrackId("content://media/12"), "Night Drive", Millis(215_000))
    private val tide = RenderTrack(TrackId("content://media/7"), "Low Tide", Millis(187_000))

    @Test
    fun `the Visual moves on at the first downbeat once the track has come in`() {
        val downbeats = listOf(1_000L, 3_000L, 5_000L)
        assertEquals(3_000L, ContinuousPlan.changeAt(2_000L, downbeats, 120f))
        assertEquals(3_000L, ContinuousPlan.changeAt(3_000L, downbeats, 120f))
        assertEquals(1_000L, ContinuousPlan.changeAt(0L, downbeats, 120f))
    }

    @Test
    fun `it waits no longer than a bar and a beat, and never past three seconds`() {
        // A bar and a beat at 120 bpm is 2.5 s.
        assertEquals(2_500L, ContinuousPlan.maxWait(120f))
        assertEquals(3_000L, ContinuousPlan.maxWait(60f))
        assertEquals(4_500L, ContinuousPlan.changeAt(2_000L, listOf(1_000L, 6_000L), 120f))
        assertEquals(4_500L, ContinuousPlan.changeAt(2_000L, listOf(4_500L), 120f))
        assertEquals(8_000L, ContinuousPlan.changeAt(5_500L, listOf(1_000L, 3_000L, 5_000L), 120f))
        assertEquals(5_000L, ContinuousPlan.changeAt(2_000L, listOf(1_000L, 6_000L), 60f))
    }

    @Test
    fun `with no tempo heard the Visual moves on as the track comes in`() {
        assertNull(ContinuousPlan.maxWait(0f))
        assertNull(ContinuousPlan.maxWait(-10f))
        assertNull(ContinuousPlan.maxWait(Float.NaN))
        assertNull(ContinuousPlan.maxWait(Float.POSITIVE_INFINITY))
        assertEquals(2_000L, ContinuousPlan.changeAt(2_000L, listOf(3_000L), 0f))
        assertEquals(2_000L, ContinuousPlan.changeAt(2_000L, listOf(3_000L), Float.NaN))
    }

    @Test
    fun `each track after the first is moved to once the songs have crossed over`() {
        // The second track starts at 10 s with a 4 s crossfade, crossing over at 12 s; the third at 30 s with none.
        val downbeats = listOf(11_000L, 12_500L, 14_000L, 30_000L, 31_000L)
        assertEquals(
            listOf(12_500L, 30_000L),
            ContinuousPlan.changesAt(listOf(0L, 10_000L, 30_000L), listOf(0L, 4_000L, 0L), downbeats, 120f),
        )
        assertEquals(emptyList<Long>(), ContinuousPlan.changesAt(listOf(0L), listOf(0L), downbeats, 120f))
        // With no tempo heard, at once; an overlap not given is none.
        assertEquals(listOf(10_000L), ContinuousPlan.changesAt(listOf(0L, 10_000L), listOf(0L), downbeats, 0f))
        assertEquals(listOf(12_000L), ContinuousPlan.changesAt(listOf(0L, 10_000L), listOf(0L, 4_000L), downbeats, 0f))
    }

    @Test
    fun `the video is as long as the joined playlist`() {
        assertEquals(Millis(402_000), ContinuousPlan.lengthOf(listOf(night, tide), TrackJoin()))
        assertEquals(Millis(398_000), ContinuousPlan.lengthOf(listOf(night, tide), TrackJoin(Millis(4_000))))
        assertEquals(Millis(215_000), ContinuousPlan.lengthOf(listOf(night), TrackJoin(Millis(4_000))))
        assertEquals(Millis.ZERO, ContinuousPlan.lengthOf(emptyList(), TrackJoin()))
    }

    @Test
    fun `the storage it needs is the video, its sound twice, the joined sound and some room`() {
        val format = RenderFormat(size = RenderSize.FHD1080, fps = 30)
        // 6 220 800 bit/s for a minute is 46 656 000 bytes; 192 kbit/s twice is 2 880 000; the joined
        // 16-bit stereo sound at 48 kHz is 11 520 000; and 64 MiB of room.
        assertEquals(128_164_864L, ContinuousPlan.bytesNeeded(format, Millis(60_000)))
        assertEquals(67_108_864L, ContinuousPlan.bytesNeeded(format, Millis.ZERO))
        assertEquals(67_108_864L, ContinuousPlan.bytesNeeded(format, Millis(-5)))
    }
}
