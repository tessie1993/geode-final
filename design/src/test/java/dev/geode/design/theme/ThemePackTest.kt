package dev.geode.design.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePackTest {
    @Test
    fun `every pack has art for every component`() {
        for (pack in ThemePackCatalog.all) {
            for (component in StoneComponent.entries) {
                pack.surface(component)
            }
        }
    }

    @Test
    fun `packs are found by slug, and an unknown slug falls back to the first`() {
        assertSame(ThemePackCatalog.onyx, ThemePackCatalog.bySlug("onyx"))
        assertSame(ThemePackCatalog.all.first(), ThemePackCatalog.bySlug("no-such-stone"))
        assertSame(ThemePackCatalog.all.first(), ThemePackCatalog.bySlug(null))
        assertEquals(ThemePackCatalog.all.size, ThemePackCatalog.all.map { it.slug }.toSet().size)
    }

    @Test
    fun `a pack missing art for a component says which`() {
        val pack = ThemePackCatalog.onyx.copy(surfaces = emptyMap())

        val error = runCatching { pack.surface(StoneComponent.CARD) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("theme pack 'onyx' is missing surface art for CARD", error?.message)
    }

    @Test
    fun `each state picks its own art`() {
        val art = StoneStateArt(default = 1, focused = 2, pressed = 3, selected = 4, disabled = 5)

        assertEquals(1, art.forState(StoneState.DEFAULT))
        assertEquals(2, art.forState(StoneState.FOCUSED))
        assertEquals(3, art.forState(StoneState.PRESSED))
        assertEquals(4, art.forState(StoneState.SELECTED))
        assertEquals(5, art.forState(StoneState.DISABLED))
    }

    @Test
    fun `disabled outranks pressed, which outranks selected, which outranks focused`() {
        assertEquals(StoneState.DEFAULT, stoneStateOf())
        assertEquals(StoneState.DISABLED, stoneStateOf(enabled = false, pressed = true, selected = true, focused = true))
        assertEquals(StoneState.PRESSED, stoneStateOf(pressed = true, selected = true, focused = true))
        assertEquals(StoneState.SELECTED, stoneStateOf(selected = true, focused = true))
        assertEquals(StoneState.FOCUSED, stoneStateOf(focused = true))
    }

    @Test
    fun `a dark pack honours any font colour, a light one only a readable one`() {
        val dark = ThemePackCatalog.all.first { !it.isLight }
        val light = ThemePackCatalog.all.firstOrNull { it.isLight } ?: return
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()

        assertNull(dark.resolvedFontColor(null))
        assertEquals(white, dark.resolvedFontColor(white))
        assertTrue(dark.fontColorActive(white))
        assertEquals(black, light.resolvedFontColor(black))
        assertNull(light.resolvedFontColor(white))
    }
}
