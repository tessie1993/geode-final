package dev.geode.feature.studio.api

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Render setup's presets and the render queue as the outside reads it (#22, #338, #325). */
class RenderApiTest {
    private val night = RenderTrack(TrackId("content://media/12"), "Night Drive", Millis(215_000))
    private val tide = RenderTrack(TrackId("content://media/7"), "Low Tide", Millis(187_000))
    private val chosen =
        RenderSpec(
            tracks = listOf(night),
            format = RenderFormat(RenderAspect.R4_3, RenderSize.HD720, 24, RenderQuality.BEST, RenderCodec.HEVC),
            length = RenderLength.Section(Millis(5_000), Millis(90_000)),
            extras = RenderExtras(RenderLoudness.MINUS_11_LUFS),
        )

    private fun job(
        id: Long,
        status: RenderStatus,
        batch: Long = 1,
        tracks: List<RenderTrack> = listOf(night),
    ) = RenderJob(
        RenderId(id),
        RenderId(batch),
        tracks,
        RenderLook.Current,
        RenderFormat(),
        RenderLength.WholeTrack,
        ProjectId(1),
        RenderExtras(),
        status,
    )

    @Test
    fun `each preset fills exactly what the owner's table sets`() {
        val shorts = chosen.withPreset(RenderPreset.TIKTOK_AND_SHORTS)
        assertEquals(RenderFormat(RenderAspect.R9_16, RenderSize.FHD1080, 30, RenderQuality.BEST, RenderCodec.HEVC), shorts.format)
        assertEquals(RenderLength.Section(Millis.ZERO, Millis(59_000)), shorts.length)
        assertEquals(RenderLoudness.MINUS_14_LUFS, shorts.extras.loudness)

        val youtube = chosen.withPreset(RenderPreset.YOUTUBE)
        assertEquals(RenderFormat(RenderAspect.R16_9, RenderSize.UHD4K, 60, RenderQuality.BEST, RenderCodec.HEVC), youtube.format)
        assertEquals(RenderLength.WholeTrack, youtube.length)
        assertEquals(RenderLoudness.MINUS_11_LUFS, youtube.extras.loudness)

        assertEquals(chosen.copy(format = chosen.format.copy(aspect = RenderAspect.R4_5)), chosen.withPreset(RenderPreset.FEED))
        assertEquals(chosen.copy(format = chosen.format.copy(aspect = RenderAspect.R1_1)), chosen.withPreset(RenderPreset.SQUARE))
        assertEquals(chosen.copy(length = RenderLength.WholeTrack), chosen.withPreset(RenderPreset.MASTER))
    }

    @Test
    fun `a Render setup starts from the Current look, one video per track, the whole Track`() {
        val spec = RenderSpec(listOf(night, tide))
        assertEquals(RenderLook.Current, spec.look)
        assertEquals(PlaylistMode.ONE_VIDEO_PER_TRACK, spec.playlist)
        assertEquals(RenderLength.WholeTrack, spec.length)
        assertNull(spec.project)
        assertEquals(RenderFormat(RenderAspect.R16_9, RenderSize.FHD1080, 60, RenderQuality.HIGH, RenderCodec.H264), spec.format)
        assertTrue(RenderFormat.DEFAULT_FPS in RenderFormat.FRAME_RATES)
        assertTrue(RenderFormat.OFFERED_FPS.all { it in RenderFormat.FRAME_RATES })
    }

    @Test
    fun `a Render is named after its Track, or every track of a continuous playlist`() {
        assertEquals("Night Drive", job(1, RenderStatus.Queued).title)
        assertEquals("Night Drive · Low Tide", job(1, RenderStatus.Queued, tracks = listOf(night, tide)).title)
        assertEquals("render 3", RenderId(3).toString())
    }

    @Test
    fun `only waiting and running Renders are active`() {
        assertTrue(job(1, RenderStatus.Queued).active)
        assertTrue(job(1, RenderStatus.Running(RenderPhase.RENDERING, 0.5f)).active)
        assertFalse(job(1, RenderStatus.Done).active)
        assertFalse(job(1, RenderStatus.Cancelled).active)
        assertFalse(job(1, RenderStatus.Failed(RenderProblem.NOT_SAVED)).active)
    }

    @Test
    fun `the queue tells the running Render and a playlist's overall progress`() {
        val running = job(2, RenderStatus.Running(RenderPhase.RENDERING, 0.5f))
        val state =
            StudioState(
                renders =
                    listOf(
                        job(1, RenderStatus.Done),
                        running,
                        job(3, RenderStatus.Queued),
                        job(4, RenderStatus.Failed(RenderProblem.ENGINE_FAILED)),
                        job(5, RenderStatus.Queued, batch = 5),
                    ),
            )
        assertEquals(running, state.runningRender)
        // Done 1, running 0.5, waiting 0, failed 1: 2.5 of 4.
        assertEquals(0.625f, state.batchProgress(RenderId(1)), 0f)
        assertEquals(0f, state.batchProgress(RenderId(5)), 0f)
        assertEquals(0f, state.batchProgress(RenderId(9)), 0f)
        assertEquals(1f, StudioState(renders = listOf(job(1, RenderStatus.Cancelled))).batchProgress(RenderId(1)), 0f)
        assertNull(StudioState().runningRender)
    }

    @Test
    fun `the queue is held only while Renders keep going is off and the app is away`() {
        assertFalse(StudioState().rendersHeld)
        assertFalse(StudioState(rendersKeepGoing = false).rendersHeld)
        assertFalse(StudioState(appInFront = false).rendersHeld)
        assertTrue(StudioState(rendersKeepGoing = false, appInFront = false).rendersHeld)
    }

    @Test
    fun `one continuous video has a look for each track, the setup's look where none is chosen`() {
        val calm =
            RenderLook.Preset(
                dev.geode.engine.types
                    .PresetId("calm"),
            )
        val spec =
            RenderSpec(
                listOf(night, tide),
                playlist = PlaylistMode.ONE_CONTINUOUS_VIDEO,
                trackLooks = listOf(calm),
                join = TrackJoin(Millis(3_000), FadeCurve.SMOOTH),
            )
        assertTrue(spec.continuous)
        assertEquals(listOf(calm, RenderLook.Current), spec.looksOfTracks)
        assertFalse(spec.copy(playlist = PlaylistMode.ONE_VIDEO_PER_TRACK).continuous)
        assertFalse(spec.copy(tracks = listOf(night)).continuous)
        assertEquals(listOf(RenderLook.Current, RenderLook.Current), RenderSpec(listOf(night, tide)).looksOfTracks)
        assertEquals(listOf(calm), spec.copy(tracks = listOf(night), trackLooks = listOf(calm, calm)).looksOfTracks)
        assertEquals(TrackJoin(Millis.ZERO, FadeCurve.EQUAL_POWER), RenderSpec(listOf(night)).join)
    }

    @Test
    fun `a continuous Render job keeps the look of each track and the crossfade between them`() {
        val calm =
            RenderLook.Preset(
                dev.geode.engine.types
                    .PresetId("calm"),
            )
        val joined =
            job(
                1,
                RenderStatus.Queued,
                tracks = listOf(night, tide),
            ).copy(trackLooks = listOf(calm), join = TrackJoin(Millis(2_000)))
        assertEquals(listOf(calm, RenderLook.Current), joined.looksOfTracks)
        assertEquals(TrackJoin(Millis(2_000)), joined.join)
        assertEquals(listOf(RenderLook.Current), job(1, RenderStatus.Queued).looksOfTracks)
    }
}
