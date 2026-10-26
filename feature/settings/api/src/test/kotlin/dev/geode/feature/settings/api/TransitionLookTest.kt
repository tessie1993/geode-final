package dev.geode.feature.settings.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The looks a change of Style may take on the live Visual (#309), as Settings stores them. */
class TransitionLookTest {
    @Test
    fun `out of the box a change of Style is the plain crossfade`() {
        assertEquals(TransitionLook.FADE, BehaviorSettings().transitionLook)
        assertEquals("fade", TransitionLook.FADE.id)
        assertEquals("random", TransitionLook.RANDOM.id)
    }

    @Test
    fun `every look is found by its id, and each id names one look`() {
        for (look in TransitionLook.entries) assertEquals(look, TransitionLook.ofId(look.id))
        val ids = TransitionLook.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `an id no look has, a hard cut included, is no look`() {
        assertNull(TransitionLook.ofId(null))
        assertNull(TransitionLook.ofId(""))
        assertNull(TransitionLook.ofId("cut"))
        assertNull(TransitionLook.ofId("Fade"))
        assertNull(TransitionLook.ofId("DreamyZoom"))
    }
}
