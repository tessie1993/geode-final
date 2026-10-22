package dev.geode.feature.home.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceTest {
    @Test
    fun `the bar holds Home, Player, Library, Visuals and Studio, Home first`() {
        assertEquals(listOf(Place.HOME, Place.PLAYER, Place.LIBRARY, Place.VISUALS, Place.STUDIO), Place.bar)
    }

    @Test
    fun `Settings is not on the bar, it opens from Home`() {
        assertFalse(Place.SETTINGS.onBar)
        assertTrue(Place.entries.filter { it != Place.SETTINGS }.all { it.onBar })
    }

    @Test
    fun `the App UI opens on Home`() {
        assertEquals(Place.HOME, Place.Start)
    }
}
