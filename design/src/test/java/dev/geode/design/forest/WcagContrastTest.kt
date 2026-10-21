package dev.geode.design.forest

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class WcagContrastTest {
    @Test
    fun `luminance runs from black to white`() {
        assertEquals(0f, WcagContrast.relativeLuminance(Color.Black), TOLERANCE)
        assertEquals(1f, WcagContrast.relativeLuminance(Color.White), TOLERANCE)
    }

    @Test
    fun `luminance weights green most and blue least`() {
        assertEquals(0.2126f, WcagContrast.relativeLuminance(Color.Red), TOLERANCE)
        assertEquals(0.7152f, WcagContrast.relativeLuminance(Color.Green), TOLERANCE)
        assertEquals(0.0722f, WcagContrast.relativeLuminance(Color.Blue), TOLERANCE)
    }

    @Test
    fun `luminance is linear below the knee and gamma-curved above it`() {
        // 8/255 is under the 0.04045 knee: 0.031373 / 12.92.
        assertEquals(0.0024282f, WcagContrast.relativeLuminance(Color(0xFF080808)), SMALL_TOLERANCE)
        // 0x77 is above it: ((0.46667 + 0.055) / 1.055) ^ 2.4.
        assertEquals(0.18447f, WcagContrast.relativeLuminance(Color(0xFF777777)), SMALL_TOLERANCE)
    }

    @Test
    fun `black on white is the widest contrast and a colour on itself the narrowest`() {
        assertEquals(21f, WcagContrast.ratio(Color.Black, Color.White), TOLERANCE)
        assertEquals(21f, WcagContrast.ratio(Color.White, Color.Black), TOLERANCE)
        assertEquals(1f, WcagContrast.ratio(Color(0xFF24302B), Color(0xFF24302B)), TOLERANCE)
    }

    @Test
    fun `mid grey on white falls just short of AA`() {
        // The textbook case: #777777 on white is 4.48:1.
        assertEquals(4.48f, WcagContrast.ratio(Color(0xFF777777), Color.White), 0.01f)
    }

    @Test
    fun `drawing over mixes each channel by the opacity`() {
        val top = Color(0xFF204060)
        val bottom = Color(0xFFE0C0A0)

        assertEquals(top, WcagContrast.over(top, 1f, bottom))
        assertEquals(bottom, WcagContrast.over(top, 0f, bottom))
        // Each channel lands half way: 0x20 and 0xE0 meet at 0x80, and so on. The result is opaque.
        assertEquals(Color(0xFF808080), WcagContrast.over(top, 0.5f, bottom))
    }

    @Test
    fun `drawing over clamps the opacity`() {
        val top = Color(0xFF204060)
        val bottom = Color(0xFFE0C0A0)

        assertEquals(top, WcagContrast.over(top, 2f, bottom))
        assertEquals(bottom, WcagContrast.over(top, -1f, bottom))
    }

    @Test
    fun `the AA minimums are WCAG's`() {
        assertEquals(4.5f, WcagContrast.AA_TEXT, 0f)
        assertEquals(3.0f, WcagContrast.AA_LARGE_TEXT, 0f)
    }

    private companion object {
        const val TOLERANCE = 0.001f
        const val SMALL_TOLERANCE = 0.00005f
    }
}
