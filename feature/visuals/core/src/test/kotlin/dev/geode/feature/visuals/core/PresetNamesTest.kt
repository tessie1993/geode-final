package dev.geode.feature.visuals.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** How preset and folder names are cleaned, and how an import avoids a name in use (#144). */
class PresetNamesTest {
    @Test
    fun `a preset name loses stray spaces and is cut to the limit`() {
        assertEquals("Quiet time", PresetNames.preset("  Quiet \t  time\n"))
        assertEquals("a".repeat(PresetNames.NAME_LIMIT), PresetNames.preset("a".repeat(PresetNames.NAME_LIMIT + 1)))
        assertEquals("Café · Ünï/!", PresetNames.preset("Café · Ünï/!"))
        assertNull(PresetNames.preset("   "))
    }

    @Test
    fun `a folder name is made safe on the phone`() {
        assertEquals("Chill_out_ 2-b", PresetNames.folder(" Chill/out!  2-b "))
        assertEquals("Caf_", PresetNames.folder("Café"))
        assertNull(PresetNames.folder(" "))
    }

    @Test
    fun `an import keeps its name when it is free`() {
        assertEquals("Calm", PresetNames.free(" Calm ", setOf("Deep")))
    }

    @Test
    fun `an import takes the first free number after a name in use`() {
        assertEquals("Calm 2", PresetNames.free("Calm", setOf("Calm")))
        assertEquals("Calm 4", PresetNames.free("Calm", setOf("Calm", "Calm 2", "Calm 3")))
    }

    @Test
    fun `an import with no name gets the shared name`() {
        assertEquals(PresetNames.SHARED_NAME, PresetNames.free("  ", emptySet()))
        assertEquals("${PresetNames.SHARED_NAME} 2", PresetNames.free("", setOf(PresetNames.SHARED_NAME)))
    }
}
