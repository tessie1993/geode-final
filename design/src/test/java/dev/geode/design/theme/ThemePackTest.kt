package dev.geode.design.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePackTest {
    @Test
    fun `the pack has art for every component`() {
        for (component in StoneComponent.entries) {
            ThemePackCatalog.lapisLazuli.surface(component)
        }
    }

    @Test
    fun `a pack missing art for a component says which`() {
        val pack = ThemePackCatalog.lapisLazuli.copy(surfaces = emptyMap())

        val error = runCatching { pack.surface(StoneComponent.CARD) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("theme pack 'lapis-lazuli' is missing surface art for CARD", error?.message)
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
}
