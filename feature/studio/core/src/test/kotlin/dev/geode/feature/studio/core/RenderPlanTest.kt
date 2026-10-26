package dev.geode.feature.studio.core

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.studio.api.PlaylistMode
import dev.geode.feature.studio.api.ProjectId
import dev.geode.feature.studio.api.RenderAspect
import dev.geode.feature.studio.api.RenderCodec
import dev.geode.feature.studio.api.RenderFormat
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
}
