package dev.geode.render.offscreen

import org.junit.Assert.assertEquals
import org.junit.Test

/** A ranged Render's frames sit on the track's own grid, as a whole-track Render's do (#344). */
class OffscreenRenderSpecTest {
    @Test
    fun `a range starts on the grid frame nearest its start`() {
        assertEquals(0L, OffscreenRenderSpec.firstFrameAt(0, 60))
        assertEquals(0L, OffscreenRenderSpec.firstFrameAt(-5, 60))
        assertEquals(0L, OffscreenRenderSpec.firstFrameAt(1_000, 0))
        assertEquals(60L, OffscreenRenderSpec.firstFrameAt(1_000, 60))
        // 1.008 s at 60 fps is 60.48 frames: frame 60; 1.009 s is 60.54: frame 61.
        assertEquals(60L, OffscreenRenderSpec.firstFrameAt(1_008, 60))
        assertEquals(61L, OffscreenRenderSpec.firstFrameAt(1_009, 60))
        assertEquals(720L, OffscreenRenderSpec.firstFrameAt(30_000, 24))
    }

    @Test
    fun `a grid frame falls at its whole millisecond, rounded down`() {
        assertEquals(0L, OffscreenRenderSpec.frameMs(0, 60))
        assertEquals(16L, OffscreenRenderSpec.frameMs(1, 60))
        assertEquals(1_000L, OffscreenRenderSpec.frameMs(60, 60))
        assertEquals(-17L, OffscreenRenderSpec.frameMs(-1, 60))
        assertEquals(30_000L, OffscreenRenderSpec.frameMs(OffscreenRenderSpec.firstFrameAt(30_000, 24), 24))
    }
}
