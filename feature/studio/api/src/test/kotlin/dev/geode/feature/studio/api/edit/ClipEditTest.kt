package dev.geode.feature.studio.api.edit

import dev.geode.feature.studio.api.RenderAspect
import dev.geode.feature.studio.api.RenderSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A clip's edit as data, split from its Media3 effects (#318). */
class ClipEditTest {
    private data class Grade(
        val brightness: Float,
        val contrast: Float,
        val saturation: Float,
        val hue: Float,
        val mono: Boolean,
        val invert: Boolean,
    )

    private fun ClipEdit.grade() = Grade(brightness, contrast, saturation, hueDegrees, monochrome, invert)

    @Test
    fun `each look sets its own grade`() {
        val base = ClipEdit(brightness = 0.5f, contrast = 0.5f, saturation = 5f, hueDegrees = 5f, monochrome = true, invert = true)
        assertEquals(Grade(0f, 0f, 0f, 0f, false, false), ClipLook.NONE.applyTo(base).grade())
        assertEquals(Grade(0.02f, 0.28f, 30f, 0f, false, false), ClipLook.PUNCH.applyTo(base).grade())
        assertEquals(Grade(0.06f, 0.18f, -55f, 0f, false, false), ClipLook.BLEACH.applyTo(base).grade())
        assertEquals(Grade(0f, 0.22f, 65f, 14f, false, false), ClipLook.NEON.applyTo(base).grade())
        assertEquals(Grade(-0.14f, 0.12f, -15f, -18f, false, false), ClipLook.NIGHT.applyTo(base).grade())
        assertEquals(Grade(0.02f, 0.2f, 0f, 0f, true, false), ClipLook.MONO.applyTo(base).grade())
        assertEquals(Grade(0f, 0f, 0f, 0f, false, true), ClipLook.INVERT.applyTo(base).grade())
        assertEquals(listOf("As shot", "Punch", "Bleach", "Neon", "Night", "Mono", "Invert"), ClipLook.entries.map { it.label })
        assertEquals(2f, ClipLook.PUNCH.applyTo(base.copy(speed = 2f)).speed)
    }

    @Test
    fun `a trim keeps its part of the source, and speed changes how long it plays`() {
        assertEquals(10_000L, ClipEdit().trimmedMs(10_000))
        assertEquals(8_000L, ClipEdit(startMs = 2_000).trimmedMs(10_000))
        assertEquals(3_000L, ClipEdit(startMs = 2_000, endMs = 5_000).trimmedMs(10_000))
        assertEquals(8_000L, ClipEdit(startMs = 2_000, endMs = 50_000).trimmedMs(10_000))
        assertEquals(0L, ClipEdit(startMs = 12_000).trimmedMs(10_000))
        assertEquals(4_000L, ClipEdit(speed = 2f).outputMs(8_000))
        assertEquals(16_000L, ClipEdit(speed = 0.5f).outputMs(8_000))
        assertEquals(800_000L, ClipEdit(speed = 0f).outputMs(8_000))
    }

    @Test
    fun `an edit that changes nothing is the identity`() {
        assertTrue(ClipEdit().isIdentity(10_000))
        assertTrue(ClipEdit(endMs = 10_000).isIdentity(10_000))
        assertTrue(ClipEdit(endMs = 12_000).isIdentity(10_000))
        assertTrue(ClipEdit(caption = "  ").isIdentity(10_000))
        val changes =
            listOf(
                ClipEdit(startMs = 1),
                ClipEdit(endMs = 9_999),
                ClipEdit(brightness = 0.1f),
                ClipEdit(contrast = 0.1f),
                ClipEdit(saturation = 1f),
                ClipEdit(hueDegrees = 1f),
                ClipEdit(monochrome = true),
                ClipEdit(invert = true),
                ClipEdit(speed = 2f),
                ClipEdit(rotationDegrees = 90f),
                ClipEdit(ratio = RenderAspect.R1_1),
                ClipEdit(mute = true),
                ClipEdit(caption = "hi"),
                ClipEdit(lutUri = "content://lut"),
                ClipEdit(gammaRed = 1.2f),
            )
        for (edit in changes) assertFalse(edit.toString(), edit.isIdentity(10_000))
        assertEquals(RenderSize.FHD1080, ClipEdit().quality)
        assertNull(ClipEdit().ratio)
    }

    @Test
    fun `gamma counts when any channel moves`() {
        assertFalse(ClipEdit().hasGamma)
        assertTrue(ClipEdit(gammaRed = 0.9f).hasGamma)
        assertTrue(ClipEdit(gammaGreen = 0.9f).hasGamma)
        assertTrue(ClipEdit(gammaBlue = 0.9f).hasGamma)
    }

    @Test
    fun `keyframed clip values fold into the edit, held to their ranges`() {
        val values =
            mapOf(
                ParamId("clip.brightness") to ParamValue.Scalar(0.4f),
                ParamId("clip.contrast") to ParamValue.Scalar(-3f),
                ParamId("clip.saturation") to ParamValue.Scalar(150f),
                ParamId("clip.hueDegrees") to ParamValue.Scalar(-90f),
                ParamId("clip.speed") to ParamValue.Scalar(0.1f),
                ParamId("clip.rotationDegrees") to ParamValue.Scalar(270f),
            )
        val edit = ClipParams.applyTo(ClipEdit(caption = "kept"), values)
        val expected =
            ClipEdit(
                brightness = 0.4f,
                contrast = -1f,
                saturation = 100f,
                hueDegrees = -90f,
                speed = 0.25f,
                rotationDegrees = 180f,
                caption = "kept",
            )
        assertEquals(expected, edit)
    }

    @Test
    fun `other parameters, and values that are not one number, are left alone`() {
        val base = ClipEdit(brightness = 0.3f)
        assertEquals(base, ClipParams.applyTo(base, mapOf(ParamId("scene.zoom") to ParamValue.Scalar(2f))))
        assertEquals(base, ClipParams.applyTo(base, mapOf(ParamId("clip.brightness") to ParamValue.Toggle(true))))
        assertSame(base, ClipParams.applyTo(base, emptyMap()))
    }

    @Test
    fun `the clip values are found by id, with their ranges`() {
        assertEquals(
            listOf("clip.brightness", "clip.contrast", "clip.saturation", "clip.hueDegrees", "clip.speed", "clip.rotationDegrees"),
            ClipParams.all.map { it.id.value },
        )
        val speed = ClipParams.find(ClipParams.SPEED)!!
        assertEquals(0.25f, speed.min)
        assertEquals(4f, speed.max)
        assertEquals(1.5f, speed.read(ClipEdit(speed = 1.5f)))
        assertEquals(ParamId("clip.speed"), ClipParams.SPEED)
        assertNull(ClipParams.find(ParamId("scene.zoom")))
        val reads = ClipParams.all.map { it.read(ClipEdit(1, 2, ClipLook.NONE, 0.1f, 0.2f, 3f, 4f, speed = 5f, rotationDegrees = 6f)) }
        assertEquals(listOf(0.1f, 0.2f, 3f, 4f, 5f, 6f), reads)
        val ranges = ClipParams.all.map { it.min to it.max }
        assertEquals(listOf(-1f to 1f, -1f to 1f, -100f to 100f, -180f to 180f, 0.25f to 4f, -180f to 180f), ranges)
    }
}
