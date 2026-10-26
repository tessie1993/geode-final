package dev.geode.feature.studio.core

import dev.geode.feature.studio.api.FadeCurve
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

/** One continuous video's sound (#326): its tracks joined one after another, crossfaded as Playback joins them. */
class CrossfadeJoinTest {
    private val heard = ArrayList<Float>()

    // The channels of the join made last, which the tracks written to it have.
    private var channels = 1

    private fun join(
        fade: Int,
        channels: Int = 1,
        curve: FadeCurve = FadeCurve.LINEAR,
        block: Int = CrossfadeJoin.BLOCK_FRAMES,
    ): CrossfadeJoin {
        this.channels = channels
        return CrossfadeJoin(fade, channels, curve, block) { samples, frames -> heard.addAll(samples.take(frames * channels)) }
    }

    private fun CrossfadeJoin.track(
        vararg samples: Float,
        expected: Long = 0L,
        chunk: Int = samples.size,
    ) {
        startTrack(expected)
        samples.toList().chunked(chunk).forEach { write(it.toFloatArray(), it.size / channels) }
    }

    private fun assertHeard(vararg expected: Float) = assertArrayEquals(expected, heard.toFloatArray(), 1e-4f)

    @Test
    fun `with no crossfade the tracks follow one another`() {
        val joined = join(fade = 0)
        joined.track(1f, 2f, 3f)
        joined.track(4f, 5f)
        joined.finish()
        assertHeard(1f, 2f, 3f, 4f, 5f)
        assertEquals(listOf(0L, 3L), joined.starts)
        assertEquals(listOf(0L, 0L), joined.overlaps)
        assertEquals(5L, joined.written)
    }

    @Test
    fun `the next track fades in as the one before fades out`() {
        val joined = join(fade = 2)
        joined.track(1f, 1f, 1f, 1f, 1f, 1f)
        joined.track(3f, 3f, 3f, 3f, expected = 4L)
        joined.finish()
        // Halfway through each frame of the fade: 0.75 × 1 + 0.25 × 3, then 0.25 × 1 + 0.75 × 3.
        assertHeard(1f, 1f, 1f, 1f, 1.5f, 2.5f, 3f, 3f)
        assertEquals(listOf(0L, 4L), joined.starts)
        assertEquals(listOf(0L, 2L), joined.overlaps)
    }

    @Test
    fun `however the sound arrives, it joins the same`() {
        val joined = join(fade = 2, block = 3)
        joined.track(1f, 1f, 1f, 1f, 1f, 1f, chunk = 1)
        joined.track(3f, 3f, 3f, 3f, expected = 4L, chunk = 3)
        joined.finish()
        assertHeard(1f, 1f, 1f, 1f, 1.5f, 2.5f, 3f, 3f)
    }

    @Test
    fun `a crossfade takes no more than half of the track before`() {
        val joined = join(fade = 4)
        joined.track(1f, 1f)
        joined.track(3f)
        joined.finish()
        assertHeard(1f, 2f)
        assertEquals(listOf(0L, 1L), joined.starts)
    }

    @Test
    fun `nor more than half of the track after, when its length is known`() {
        val joined = join(fade = 4)
        joined.track(1f, 1f, 1f, 1f, 1f, 1f)
        joined.track(3f, 3f, expected = 2L)
        joined.finish()
        assertHeard(1f, 1f, 1f, 1f, 1f, 2f, 3f)
        assertEquals(listOf(0L, 5L), joined.starts)
        assertEquals(listOf(0L, 1L), joined.overlaps)
    }

    @Test
    fun `a track that ends inside the crossfade leaves the one before to fade out alone`() {
        val joined = join(fade = 4)
        joined.track(1f, 1f, 1f, 1f, 1f, 1f)
        joined.track(3f)
        joined.finish()
        assertHeard(1f, 1f, 1f, 5f / 6f + 0.5f, 0.5f, 1f / 6f)
        assertEquals(listOf(0L, 3L), joined.starts)
        assertEquals(6L, joined.written)
    }

    @Test
    fun `a stereo track fades both its channels`() {
        val joined = join(fade = 2, channels = 2)
        joined.track(1f, -1f, 1f, -1f, 1f, -1f, 1f, -1f)
        joined.track(3f, -3f, 3f, -3f)
        joined.finish()
        assertHeard(1f, -1f, 1f, -1f, 1.5f, -1.5f, 2.5f, -2.5f)
        assertEquals(listOf(0L, 2L), joined.starts)
    }

    @Test
    fun `three tracks each join the one before`() {
        val joined = join(fade = 1)
        joined.track(1f, 1f)
        joined.track(3f, 3f)
        joined.track(5f, 5f)
        joined.finish()
        assertHeard(1f, 2f, 4f, 5f)
        assertEquals(listOf(0L, 1L, 2L), joined.starts)
        assertEquals(listOf(0L, 1L, 1L), joined.overlaps)
    }

    @Test
    fun `nothing written before a track starts, and a finished join is silent`() {
        val joined = join(fade = 2)
        joined.write(floatArrayOf(9f), 1)
        joined.finish()
        assertHeard()
        assertEquals(emptyList<Long>(), joined.starts)
    }

    @Test
    fun `the curves cross as Playback's do`() {
        assertEquals(0.25f, CrossfadeJoin.fadeIn(0.25f, FadeCurve.LINEAR), 1e-6f)
        assertEquals(0.75f, CrossfadeJoin.fadeOut(0.25f, FadeCurve.LINEAR), 1e-6f)
        assertEquals(sqrt(0.5f), CrossfadeJoin.fadeIn(0.5f, FadeCurve.EQUAL_POWER), 1e-5f)
        assertEquals(sqrt(0.5f), CrossfadeJoin.fadeOut(0.5f, FadeCurve.EQUAL_POWER), 1e-5f)
        assertEquals(0.5f, CrossfadeJoin.fadeIn(1f / 3f, FadeCurve.EQUAL_POWER), 1e-5f)
        assertEquals(sqrt(3f) / 2f, CrossfadeJoin.fadeOut(1f / 3f, FadeCurve.EQUAL_POWER), 1e-5f)
        // 0.25² × (3 − 0.5)
        assertEquals(0.15625f, CrossfadeJoin.fadeIn(0.25f, FadeCurve.SMOOTH), 1e-6f)
        assertEquals(0.84375f, CrossfadeJoin.fadeOut(0.25f, FadeCurve.SMOOTH), 1e-6f)
    }

    @Test
    fun `an equal-power crossfade keeps the level of two like songs`() {
        val joined = join(fade = 2, curve = FadeCurve.EQUAL_POWER)
        joined.track(1f, 1f, 1f, 1f)
        joined.track(1f, 1f, 1f, 1f)
        joined.finish()
        val k0 = CrossfadeJoin.fadeOut(0.25f, FadeCurve.EQUAL_POWER) + CrossfadeJoin.fadeIn(0.25f, FadeCurve.EQUAL_POWER)
        val k1 = CrossfadeJoin.fadeOut(0.75f, FadeCurve.EQUAL_POWER) + CrossfadeJoin.fadeIn(0.75f, FadeCurve.EQUAL_POWER)
        assertHeard(1f, 1f, k0, k1, 1f, 1f)
    }

    @Test
    fun `the sink is handed whole blocks, and the last one once, never an empty one`() {
        val calls = ArrayList<Int>()
        val joined = CrossfadeJoin(0, 1, FadeCurve.LINEAR, 3) { _, frames -> calls += frames }
        joined.startTrack()
        joined.write(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), 6)
        joined.finish()
        joined.finish()
        assertEquals(listOf(3, 3), calls)
    }

    @Test
    fun `after a track that ended inside the crossfade, the next joins what is left of the fade`() {
        val joined = join(fade = 4)
        joined.track(1f, 1f, 1f, 1f, 1f, 1f)
        joined.track(3f)
        joined.track(5f, 5f, 5f, 5f)
        joined.finish()
        // The second track's region is its one frame and the two the first fades out alone; half of it, one
        // frame, fades into the third: 0.5 × (1/6) + 0.5 × 5.
        assertHeard(1f, 1f, 1f, 5f / 6f + 0.5f, 0.5f, 0.5f / 6f + 2.5f, 5f, 5f, 5f)
        assertEquals(listOf(0L, 3L, 5L), joined.starts)
        assertEquals(listOf(0L, 3L, 1L), joined.overlaps)
    }
}
