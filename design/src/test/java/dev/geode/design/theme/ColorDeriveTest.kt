package dev.geode.design.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class ColorDeriveTest {
    @Test
    fun `lerp runs from one colour to the other, channel by channel`() {
        val from = 0x00000000
        val to = 0xFF80C0FF.toInt()

        assertEquals(from, ColorDerive.lerpArgb(from, to, 0f))
        assertEquals(to, ColorDerive.lerpArgb(from, to, 1f))
        assertEquals(0x80406080.toInt(), ColorDerive.lerpArgb(from, to, 0.5f))
    }

    @Test
    fun `lerp clamps its fraction`() {
        val from = 0xFF102030.toInt()
        val to = 0xFFF0E0D0.toInt()

        assertEquals(from, ColorDerive.lerpArgb(from, to, -1f))
        assertEquals(to, ColorDerive.lerpArgb(from, to, 2f))
    }

    @Test
    fun `saturation scales each channel away from or toward the luma`() {
        val red = 0xFFFF0000.toInt()

        assertEquals(red, ColorDerive.scaleSaturation(red, 1f))
        // luma of pure red is 0.299 * 255 = 76.245, so grey is 76 in every channel.
        assertEquals(0xFF4C4C4C.toInt(), ColorDerive.scaleSaturation(red, 0f))
        assertEquals(0xFFA62626.toInt(), ColorDerive.scaleSaturation(red, 0.5f))
    }

    @Test
    fun `saturation keeps alpha and clamps to the channel range`() {
        val half = 0x80FF0000.toInt()

        assertEquals(0x80FF0000.toInt(), ColorDerive.scaleSaturation(half, 2f))
    }

    @Test
    fun `dimming darkens towards black and keeps alpha`() {
        val c = 0x80C86432.toInt()

        assertEquals(c, ColorDerive.dim(c, 0f))
        assertEquals(0x80000000.toInt(), ColorDerive.dim(c, 1f))
        assertEquals(0x80643219.toInt(), ColorDerive.dim(c, 0.5f))
        assertEquals(0x80000000.toInt(), ColorDerive.dim(c, 3f))
        assertEquals(c, ColorDerive.dim(c, -1f))
    }

    @Test
    fun `a colour packs to the same ARGB it was made from`() {
        assertEquals(0xFF6D98FF.toInt(), Color(0xFF6D98FF).toArgbInt())
        assertEquals(0x80102030.toInt(), Color(0x80102030).toArgbInt())
    }
}
