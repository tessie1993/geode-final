package dev.geode.design.element

import androidx.compose.ui.graphics.Color
import dev.geode.design.forest.WcagContrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ElementSkinsTest {
    @Test
    fun `the first wave is Water, Wood, Stone and Bioluminescence, in that order`() {
        assertEquals(listOf("Water", "Wood", "Stone", "Bioluminescence"), ElementSkins.all.map { it.key })
        assertSame(ElementSkins.Water, ElementSkins.all[0])
        assertSame(ElementSkins.Wood, ElementSkins.all[1])
        assertSame(ElementSkins.Stone, ElementSkins.all[2])
        assertSame(ElementSkins.Bioluminescence, ElementSkins.all[3])
    }

    @Test
    fun `each Element looks different`() {
        assertEquals(
            ElementSkins.all.size,
            ElementSkins.all
                .map { it.colors }
                .toSet()
                .size,
        )
        assertEquals(
            ElementSkins.all.size,
            ElementSkins.all
                .map { it.shapes }
                .toSet()
                .size,
        )
    }

    @Test
    fun `every first-wave Element is dark`() {
        for (element in ElementSkins.all) {
            val colors = element.colors
            for (ground in listOf(colors.background, colors.backgroundDeep, colors.surface, colors.surfaceHigh)) {
                assertTrue(element.key, WcagContrast.relativeLuminance(ground) < DARK_GROUND_MAX)
            }
            assertTrue(element.key, WcagContrast.relativeLuminance(colors.textPrimary) > LIGHT_TEXT_MIN)
        }
    }

    @Test
    fun `every first-wave Element is set in Space Grotesk and Inter`() {
        for (element in ElementSkins.all) {
            assertSame(element.key, SpaceGroteskFamily, element.type.display)
            assertSame(element.key, InterFamily, element.type.text)
        }
    }

    @Test
    fun `every Element's motions take time, and selection takes longest`() {
        for (element in ElementSkins.all) {
            val motion = element.motion
            val all = listOf(motion.pressMillis, motion.releaseMillis, motion.focusMillis, motion.selectMillis, motion.pageMillis)
            assertTrue(element.key, all.all { it in MOTION_RANGE })
            assertTrue(element.key, motion.pressMillis < motion.releaseMillis)
            assertTrue(element.key, motion.selectMillis > motion.releaseMillis)
        }
    }

    @Test
    fun `Water's timings are the ones in its skin design`() {
        assertEquals(
            ElementMotion(pressMillis = 90, releaseMillis = 220, focusMillis = 160, selectMillis = 400, pageMillis = 320),
            ElementSkins.Water.motion,
        )
        assertEquals(Color(0xFF071A1C), ElementSkins.Water.colors.background)
        assertEquals(Color(0xFFF2B35E), ElementSkins.Water.colors.accent)
        assertEquals(Color(0xFF7FD8CF), ElementSkins.Water.colors.active)
    }

    @Test
    fun `no Element has art yet, so every surface is drawn in flat colours`() {
        for (element in ElementSkins.all) {
            assertEquals(element.key, ElementArt.None, element.art)
            assertNull(element.art.barSurface)
            assertNull(element.art.labelSurface)
        }
    }

    private companion object {
        const val DARK_GROUND_MAX = 0.05f
        const val LIGHT_TEXT_MIN = 0.6f
        val MOTION_RANGE = 1..1_000
    }
}
