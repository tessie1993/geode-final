package dev.geode.feature.studio.core

import dev.geode.feature.studio.api.RenderAspect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where a moved reframe crops a picture (#328), in the Edit pipeline's normalised coordinates. */
class CropBoxTest {
    private fun assertBox(
        expected: CropBox,
        actual: CropBox?,
    ) {
        val box = checkNotNull(actual)
        assertEquals(expected.left, box.left, EPSILON)
        assertEquals(expected.right, box.right, EPSILON)
        assertEquals(expected.bottom, box.bottom, EPSILON)
        assertEquals(expected.top, box.top, EPSILON)
    }

    @Test
    fun `a 9 by 16 crop of a wide picture keeps its full height and slides across`() {
        // 1920x1080 is 16:9; a 9:16 box keeps (9/16)/(16/9) = 81/256 of its width.
        val kept = 2f * 81f / 256f
        assertBox(CropBox(-1f, -1f + kept, -1f, 1f), CropBox.of(1920, 1080, RenderAspect.R9_16, 0f, 0.5f))
        assertBox(CropBox(1f - kept, 1f, -1f, 1f), CropBox.of(1920, 1080, RenderAspect.R9_16, 1f, 0.5f))
        assertBox(CropBox(-kept / 2f, kept / 2f, -1f, 1f), CropBox.of(1920, 1080, RenderAspect.R9_16, 0.5f, 0f))
    }

    @Test
    fun `a square crop of a tall picture keeps its full width and slides down`() {
        // 1080x1920 is 9:16; a square box keeps 9/16 of its height.
        val kept = 2f * 9f / 16f
        assertBox(CropBox(-1f, 1f, 1f - kept, 1f), CropBox.of(1080, 1920, RenderAspect.R1_1, 0.5f, 0f))
        assertBox(CropBox(-1f, 1f, -1f, -1f + kept), CropBox.of(1080, 1920, RenderAspect.R1_1, 0.5f, 1f))
        assertBox(CropBox(-1f, 1f, -kept / 2f, kept / 2f), CropBox.of(1080, 1920, RenderAspect.R1_1, 0.9f, 0.5f))
    }

    @Test
    fun `a picture already of the shape is kept whole`() {
        assertBox(CropBox(-1f, 1f, -1f, 1f), CropBox.of(1080, 1920, RenderAspect.R9_16, 0f, 0f))
        assertBox(CropBox(-1f, 1f, -1f, 1f), CropBox.of(1000, 1000, RenderAspect.R1_1, 1f, 1f))
    }

    @Test
    fun `a crop moved past an edge stops at it`() {
        val wide = { x: Float -> checkNotNull(CropBox.of(1920, 1080, RenderAspect.R9_16, x, 0.5f)) }
        val tall = { y: Float -> checkNotNull(CropBox.of(1080, 1920, RenderAspect.R1_1, 0.5f, y)) }
        assertBox(wide(1f), wide(3f))
        assertBox(wide(0f), wide(-3f))
        assertBox(tall(1f), tall(4f))
        assertBox(tall(0f), tall(-4f))
    }

    @Test
    fun `a picture of no size cannot be cropped`() {
        assertNull(CropBox.of(0, 1080, RenderAspect.R9_16, 0.5f, 0.5f))
        assertNull(CropBox.of(1920, 0, RenderAspect.R9_16, 0.5f, 0.5f))
        assertNull(CropBox.of(-5, -5, RenderAspect.R9_16, 0.5f, 0.5f))
    }

    private companion object {
        const val EPSILON = 1e-5f
    }
}
