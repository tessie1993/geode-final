package dev.geode.feature.studio.core

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.studio.api.PlaylistMode
import dev.geode.feature.studio.api.ProjectId
import dev.geode.feature.studio.api.RenderAspect
import dev.geode.feature.studio.api.RenderCodec
import dev.geode.feature.studio.api.RenderFormat
import dev.geode.feature.studio.api.RenderId
import dev.geode.feature.studio.api.RenderLength
import dev.geode.feature.studio.api.RenderQuality
import dev.geode.feature.studio.api.RenderSize
import dev.geode.feature.studio.api.RenderSpec
import dev.geode.feature.studio.api.RenderTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The pure decisions behind a Render: which Renders, at what frame size, bit rate and section. */
class RenderPlanTest {
    private val night = RenderTrack(TrackId("content://media/12"), "Night Drive", Millis(215_000))
    private val tide = RenderTrack(TrackId("content://media/7"), "Low Tide", Millis(187_000))
    private val project = ProjectId(4)

    private fun frame(
        aspect: RenderAspect,
        size: RenderSize,
    ) = RenderPlan.frameOf(RenderFormat(aspect = aspect, size = size))

    @Test
    fun `one video per track queues a Render per track, in order`() {
        val spec = RenderSpec(listOf(night, tide))
        assertEquals(listOf(listOf(night), listOf(tide)), RenderPlan.rendersOf(spec, project).map { it.tracks })
        assertEquals(listOf(project, project), RenderPlan.rendersOf(spec, project).map { it.project })
    }

    @Test
    fun `every Render queued carries the MilkDrop preset of the setup`() {
        val spec = RenderSpec(listOf(night, tide), milkdrop = "aurora_veil.milk")
        assertEquals(listOf("aurora_veil.milk", "aurora_veil.milk"), RenderPlan.rendersOf(spec, project).map { it.milkdrop })
        val job = RenderPlan.rendersOf(spec, project).first().queuedAs(RenderId(3), RenderId(3))
        assertEquals("aurora_veil.milk", job.milkdrop)
        assertNull(RenderPlan.rendersOf(RenderSpec(listOf(night)), project).single().milkdrop)
    }

    @Test
    fun `one continuous video would be a single Render of every track`() {
        val spec = RenderSpec(listOf(night, tide), playlist = PlaylistMode.ONE_CONTINUOUS_VIDEO)
        assertEquals(listOf(listOf(night, tide)), RenderPlan.rendersOf(spec, project).map { it.tracks })
    }

    @Test
    fun `the frame is the size on the short side at the shape, both even`() {
        assertEquals(FrameSize(1920, 1080), frame(RenderAspect.R16_9, RenderSize.FHD1080))
        assertEquals(FrameSize(1080, 1920), frame(RenderAspect.R9_16, RenderSize.FHD1080))
        assertEquals(FrameSize(720, 720), frame(RenderAspect.R1_1, RenderSize.HD720))
        assertEquals(FrameSize(480, 600), frame(RenderAspect.R4_5, RenderSize.SD480))
        assertEquals(FrameSize(1920, 1440), frame(RenderAspect.R4_3, RenderSize.QHD1440))
        assertEquals(FrameSize(3840, 2160), frame(RenderAspect.R16_9, RenderSize.UHD4K))
        // 480 × 21 / 9 = 1120; 720 × 21 / 9 = 1680, under the cap.
        assertEquals(FrameSize(1120, 480), frame(RenderAspect.R21_9, RenderSize.SD480))
    }

    @Test
    fun `a long side past the cap shrinks both, keeping the shape and even sides`() {
        // 2160 × 21 / 9 = 5040 is too long: 2160 × 4096 / 5040 = 1755, made even.
        assertEquals(FrameSize(4096, 1756), frame(RenderAspect.R21_9, RenderSize.UHD4K))
        assertEquals(FrameSize(2160, 3840), frame(RenderAspect.R9_16, RenderSize.UHD4K))
        // 1440 × 21 / 9 = 3360 stays.
        assertEquals(FrameSize(3360, 1440), frame(RenderAspect.R21_9, RenderSize.QHD1440))
    }

    @Test
    fun `the bit rate follows the pixels, the frame rate, the quality and the codec`() {
        val hd = RenderFormat(size = RenderSize.FHD1080, fps = 30)
        // 1920 × 1080 × 30 × 0.1
        assertEquals(6_220_800, RenderPlan.bitRateOf(hd))
        assertEquals(3_732_480, RenderPlan.bitRateOf(hd.copy(quality = RenderQuality.STANDARD)))
        assertEquals(9_953_280, RenderPlan.bitRateOf(hd.copy(quality = RenderQuality.BEST)))
        assertEquals(12_441_600, RenderPlan.bitRateOf(hd.copy(fps = 60)))
        assertEquals(4_043_520, RenderPlan.bitRateOf(hd.copy(codec = RenderCodec.HEVC)))
    }

    @Test
    fun `the bit rate stays within what encoders take`() {
        val small = RenderFormat(aspect = RenderAspect.R1_1, size = RenderSize.SD480, fps = 12, quality = RenderQuality.STANDARD)
        assertEquals(RenderPlan.MIN_BIT_RATE, RenderPlan.bitRateOf(small))
        val huge = RenderFormat(size = RenderSize.UHD4K, fps = 120, quality = RenderQuality.BEST)
        assertEquals(RenderPlan.MAX_BIT_RATE, RenderPlan.bitRateOf(huge))
    }

    @Test
    fun `a section is kept within the Track`() {
        val track = Millis(200_000)
        assertEquals(
            TrackSection(Millis(10_000), Millis(30_000)),
            RenderPlan.sectionOf(RenderLength.Section(Millis(10_000), Millis(30_000)), track),
        )
        assertEquals(
            TrackSection(Millis(190_000), Millis(10_000)),
            RenderPlan.sectionOf(RenderLength.Section(Millis(190_000), Millis(30_000)), track),
        )
        assertEquals(
            TrackSection(Millis(0), Millis(59_000)),
            RenderPlan.sectionOf(RenderLength.Section(Millis.ZERO, Millis(59_000)), track),
        )
        assertEquals(
            TrackSection(Millis(0), Millis(1_000)),
            RenderPlan.sectionOf(RenderLength.Section(Millis(-5_000), Millis(6_000)), track),
        )
    }

    @Test
    fun `the whole Track is rendered for a whole-Track section, too short a section, or an unknown length`() {
        val track = Millis(200_000)
        assertNull(RenderPlan.sectionOf(RenderLength.WholeTrack, track))
        assertNull(RenderPlan.sectionOf(RenderLength.Section(Millis.ZERO, Millis(200_000)), track))
        assertNull(RenderPlan.sectionOf(RenderLength.Section(Millis.ZERO, Millis(500_000)), track))
        assertNull(RenderPlan.sectionOf(RenderLength.Section(Millis(199_500), Millis(30_000)), track))
        assertNull(RenderPlan.sectionOf(RenderLength.Section(Millis(10_000), Millis(999)), track))
        assertNull(RenderPlan.sectionOf(RenderLength.Section(Millis(10_000), Millis(30_000)), Millis.ZERO))
        assertNull(RenderPlan.sectionOf(RenderLength.Section(Millis(10_000), Millis(30_000)), Millis(-1)))
        assertEquals(
            TrackSection(Millis(10_000), Millis(1_000)),
            RenderPlan.sectionOf(RenderLength.Section(Millis(10_000), Millis(1_000)), track),
        )
        // Starting at 0 but ending before the Track does is a real section.
        assertEquals(
            TrackSection(Millis(0), Millis(199_999)),
            RenderPlan.sectionOf(RenderLength.Section(Millis.ZERO, Millis(199_999)), track),
        )
        // Ending at the Track's end but starting later is too.
        assertEquals(
            TrackSection(Millis(1), Millis(199_999)),
            RenderPlan.sectionOf(RenderLength.Section(Millis(1), Millis(199_999)), track),
        )
    }

    @Test
    fun `a Render's scene is supersampled by its quality, within what the GPU draws`() {
        val hd = RenderFormat(aspect = RenderAspect.R16_9, size = RenderSize.FHD1080)
        assertEquals(1f, RenderPlan.supersampleOf(hd.copy(quality = RenderQuality.STANDARD)), 0f)
        assertEquals(1.5f, RenderPlan.supersampleOf(hd.copy(quality = RenderQuality.HIGH)), 0f)
        assertEquals(2f, RenderPlan.supersampleOf(hd.copy(quality = RenderQuality.BEST)), 0f)
        // 1440p is 2560 wide: twice over would pass 4096, so it stops at 1.6.
        assertEquals(1.6f, RenderPlan.supersampleOf(hd.copy(size = RenderSize.QHD1440, quality = RenderQuality.BEST)), 1e-6f)
        // 4K is 3840 wide: a little over once; 21:9 4K already fills 4096, so exactly once.
        assertEquals(4096f / 3840f, RenderPlan.supersampleOf(hd.copy(size = RenderSize.UHD4K, quality = RenderQuality.BEST)), 1e-6f)
        val wide = RenderFormat(aspect = RenderAspect.R21_9, size = RenderSize.UHD4K, quality = RenderQuality.BEST)
        assertEquals(1f, RenderPlan.supersampleOf(wide), 0f)
        // Portrait counts its long side too.
        val tall = RenderFormat(aspect = RenderAspect.R9_16, size = RenderSize.QHD1440, quality = RenderQuality.HIGH)
        assertEquals(1.5f, RenderPlan.supersampleOf(tall), 0f)
    }

    @Test
    fun `a seamless loop is as long as the Track, within the loop limits, and is the whole video`() {
        assertEquals(LongForm(Millis(215_000), Millis(215_000)), RenderPlan.longFormOf(RenderLength.Loop, Millis(215_000)))
        assertEquals(LongForm(Millis(30_000), Millis(30_000)), RenderPlan.longFormOf(RenderLength.Loop, Millis(12_000)))
        assertEquals(LongForm(Millis(300_000), Millis(300_000)), RenderPlan.longFormOf(RenderLength.Loop, Millis(900_000)))
        assertEquals(LongForm(Millis(300_000), Millis(300_000)), RenderPlan.longFormOf(RenderLength.Loop, Millis.ZERO))
    }

    @Test
    fun `an N-minute Render repeats the loop to that many minutes, within ten hours`() {
        assertEquals(LongForm(Millis(215_000), Millis(3_600_000)), RenderPlan.longFormOf(RenderLength.Minutes(60), Millis(215_000)))
        assertEquals(Millis(60_000), RenderPlan.longFormOf(RenderLength.Minutes(0), Millis(215_000))?.total)
        assertEquals(Millis(36_000_000), RenderPlan.longFormOf(RenderLength.Minutes(5_000), Millis(215_000))?.total)
        assertNull(RenderPlan.longFormOf(RenderLength.WholeTrack, Millis(215_000)))
        assertNull(RenderPlan.longFormOf(RenderLength.Section(Millis(1_000), Millis(30_000)), Millis(215_000)))
        assertNull(RenderPlan.sectionOf(RenderLength.Loop, Millis(215_000)))
    }

    @Test
    fun `the Track plays back to back for as long as the video runs`() {
        assertEquals(17, RenderPlan.playsFor(Millis(3_600_000), Millis(215_000)))
        assertEquals(2, RenderPlan.playsFor(Millis(430_000), Millis(215_000)))
        assertEquals(3, RenderPlan.playsFor(Millis(430_001), Millis(215_000)))
        assertEquals(1, RenderPlan.playsFor(Millis(100_000), Millis(215_000)))
        assertEquals(1, RenderPlan.playsFor(Millis.ZERO, Millis(215_000)))
        assertEquals(1, RenderPlan.playsFor(Millis(3_600_000), Millis.ZERO))
    }
}
