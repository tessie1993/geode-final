package dev.geode.render.scene

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which of Geode's starter textures (#307) a MilkDrop folder gets: only names it does not hold yet. */
class MilkStarterPackTest {
    private val bundled = listOf("worms.png", "clouds.png", "cells.png", "notes.txt")

    @Test
    fun `an empty folder gets every starter image`() {
        assertEquals(listOf("worms.png", "clouds.png", "cells.png"), MilkStarterPack.texturesToInstall(bundled, emptyList()))
    }

    @Test
    fun `a name the folder holds, under any extension or case, is left to the person's own`() {
        assertEquals(listOf("cells.png"), MilkStarterPack.texturesToInstall(bundled, listOf("Worms.JPG", "clouds.png", "cellsx.png")))
    }

    @Test
    fun `the version moves on when the starter set does`() {
        assertEquals(3, MilkStarterPack.VERSION)
    }
}
