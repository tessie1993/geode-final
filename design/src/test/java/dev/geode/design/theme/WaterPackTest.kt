package dev.geode.design.theme

import androidx.compose.ui.graphics.Color
import dev.geode.design.forest.WcagContrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WaterPackTest {
    private val water = ThemePackCatalog.water
    private val slabs = listOf(WaterTokens.Background, WaterTokens.Surface, WaterTokens.SurfaceHigh)

    @Test
    fun `text tokens keep AA body contrast on the water and on every slab`() {
        for (text in listOf(WaterTokens.Text, WaterTokens.Muted, WaterTokens.Amber, WaterTokens.Aqua, WaterTokens.Danger)) {
            for (slab in slabs) {
                assertAa(text, slab, WcagContrast.AA_TEXT)
            }
        }
    }

    @Test
    fun `pebble text keeps AA body contrast from the core to the rim of every lit pebble`() {
        val labelled =
            listOf(StoneComponent.PRIMARY_BUTTON, StoneComponent.SECONDARY_BUTTON, StoneComponent.COMPACT_BUTTON, StoneComponent.CHIP)
        val lit = listOf(StoneState.DEFAULT, StoneState.FOCUSED, StoneState.PRESSED, StoneState.SELECTED)
        for (component in labelled) {
            for (state in lit) {
                val fill = waterFill(component, state)
                assertAa(WaterTokens.OnPebble, fill, WcagContrast.AA_TEXT)
                assertAa(WaterTokens.OnPebble, waterPebbleEdge(fill), WcagContrast.AA_TEXT)
                assertAa(WaterTokens.OnPebble, waterPebbleCore(component, fill, waterCore(component, state)), WcagContrast.AA_TEXT)
            }
        }
        assertEquals(WaterTokens.OnPebble, water.pebbleText)
    }

    @Test
    fun `light glyphs keep icon contrast on the dark icon pebble`() {
        for (state in listOf(StoneState.DEFAULT, StoneState.FOCUSED, StoneState.PRESSED, StoneState.SELECTED)) {
            val fill = waterFill(StoneComponent.ICON_BUTTON, state)
            val core = waterPebbleCore(StoneComponent.ICON_BUTTON, fill, waterCore(StoneComponent.ICON_BUTTON, state))
            assertAa(WaterTokens.Text, core, WcagContrast.AA_LARGE_TEXT)
        }
    }

    @Test
    fun `a disabled part is dry and dim, and its core is off`() {
        val dry = waterFill(StoneComponent.PRIMARY_BUTTON, StoneState.DISABLED)
        assertTrue(WcagContrast.relativeLuminance(dry) < WcagContrast.relativeLuminance(WaterTokens.Amber))
        assertEquals(0f, waterCore(StoneComponent.PRIMARY_BUTTON, StoneState.DISABLED))
    }

    @Test
    fun `a pressed row rises to the raised slab and a selected chip turns aqua`() {
        assertEquals(WaterTokens.Surface, waterFill(StoneComponent.LIST_ROW, StoneState.DEFAULT))
        assertEquals(WaterTokens.SurfaceHigh, waterFill(StoneComponent.LIST_ROW, StoneState.PRESSED))
        assertEquals(WaterTokens.SeaGreen, waterFill(StoneComponent.CHIP, StoneState.DEFAULT))
        assertEquals(WaterTokens.Aqua, waterFill(StoneComponent.CHIP, StoneState.SELECTED))
        assertEquals(WaterTokens.Aqua, waterFill(StoneComponent.TOGGLE, StoneState.SELECTED))
    }

    @Test
    fun `every part has a body`() {
        assertEquals(WaterBody.PEBBLE, waterBody(StoneComponent.PRIMARY_BUTTON))
        assertEquals(WaterBody.SLAB, waterBody(StoneComponent.LIST_ROW))
        assertEquals(WaterBody.RECESS, waterBody(StoneComponent.TEXT_FIELD))
        StoneComponent.entries.forEach { waterBody(it) }
    }

    @Test
    fun `every pack in the catalog can draw every part`() {
        for (pack in ThemePackCatalog.all) {
            if (pack.drawn == null) StoneComponent.entries.forEach { pack.surface(it) }
        }
        assertNotNull(water.drawn)
    }

    @Test
    fun `packs are found by slug, and an unknown slug falls back to Lapis Lazuli`() {
        assertSame(water, ThemePackCatalog.bySlug("water"))
        assertSame(ThemePackCatalog.lapisLazuli, ThemePackCatalog.bySlug("lapis-lazuli"))
        assertSame(ThemePackCatalog.lapisLazuli, ThemePackCatalog.bySlug(null))
        assertSame(ThemePackCatalog.lapisLazuli, ThemePackCatalog.bySlug("nope"))
        assertEquals(ThemePackCatalog.all.size, ThemePackCatalog.all.map { it.slug }.toSet().size)
    }

    @Test
    fun `water's touch, spin and wake follow #105`() {
        assertEquals(600, water.motion.inkBloomMs)
        assertEquals(450, water.motion.rippleRingMs)
        assertEquals(800, water.motion.scrollWakeMs)
        assertEquals(400, spinMs(water.motion, StoneComponent.ICON_BUTTON))
        assertEquals(400, spinMs(water.motion, StoneComponent.ALBUM_TILE))
        assertEquals(280, spinMs(water.motion, StoneComponent.CHIP))
        assertEquals(0, spinMs(water.motion, StoneComponent.LIST_ROW))
        assertEquals(0, spinMs(water.motion, StoneComponent.PRIMARY_BUTTON))
        assertNotNull(water.touchIndication())
    }

    @Test
    fun `lapis lazuli keeps its art and gains no touch, spin, wake or haptics`() {
        val lapis = ThemePackCatalog.lapisLazuli
        assertNull(lapis.touchIndication())
        assertEquals(0, spinMs(lapis.motion, StoneComponent.ICON_BUTTON))
        assertEquals(0, lapis.motion.scrollWakeMs)
        assertTrue(lapis.haptics.isEmpty())
        assertNull(lapis.drawn)
    }

    @Test
    fun `water's haptics cover tap, selection and toggle, each with a matching fallback`() {
        val cues = listOf(StoneHapticCue.TAP, StoneHapticCue.SELECT, StoneHapticCue.TOGGLE_ON, StoneHapticCue.TOGGLE_OFF)
        assertEquals(cues.toSet(), water.haptics.keys)
        for (pattern in water.haptics.values) {
            assertEquals(pattern.timings.size, pattern.amplitudes.size)
            assertTrue(pattern.primitives.isNotEmpty())
            assertTrue(pattern.amplitudes.all { it in 0..255 })
        }
        // The settle tick lands at the end of the 400 ms spin.
        val spin = water.haptics.getValue(StoneHapticCue.SELECT)
        assertEquals(400L, spin.timings.dropLast(1).sum())
    }

    @Test
    fun `pack-only cues are silent without a pack pattern`() {
        assertTrue(StoneHapticCue.SELECT.isPackOnly())
        assertTrue(StoneHapticCue.TOGGLE_ON.isPackOnly())
        assertTrue(!StoneHapticCue.TAP.isPackOnly())
    }

    @Test
    fun `the scroll wake builds with scrolling and tops out`() {
        assertEquals(0f, wakeAfter(0f, 0f))
        assertEquals(0.5f, wakeAfter(0f, 300f), 0.001f)
        assertEquals(1f, wakeAfter(0.9f, 600f))
    }

    private fun assertAa(
        fg: Color,
        bg: Color,
        min: Float,
    ) {
        val ratio = WcagContrast.ratio(fg, bg)
        assertTrue("contrast $ratio of $fg on $bg is under $min", ratio >= min)
    }
}
