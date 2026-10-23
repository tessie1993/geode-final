package dev.geode.ui

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeodeAppStateTest {
    @Test
    fun startsOnHome() {
        val state = GeodeAppState()
        assertEquals(listOf(GeodeDestination.HOME), state.backStack.toList())
        assertEquals(GeodeDestination.HOME, state.tab)
    }

    @Test
    fun barOrderIsHomePlayerLibraryVisualsStudio() {
        assertEquals(
            listOf("HOME", "PLAYER", "LIBRARY", "VISUALS", "STUDIO"),
            GeodeDestination.entries.map { it.name },
        )
    }

    @Test
    fun backFromAnyTabReturnsHome() {
        GeodeDestination.entries.filter { it != GeodeDestination.HOME }.forEach { tab ->
            val state = GeodeAppState()
            state.navigateTo(tab)
            assertEquals(tab, state.current)
            assertEquals(tab, state.tab)
            state.back()
            assertEquals(GeodeDestination.HOME, state.current)
        }
    }

    @Test
    fun settingsSitsAboveHomeAndBackReturnsHome() {
        val state = GeodeAppState()
        state.navigateTo(GeodeDestination.LIBRARY)
        state.openSettings()
        assertEquals(listOf(GeodeDestination.HOME, SettingsRoute), state.backStack.toList())
        assertEquals(GeodeDestination.HOME, state.tab)
        state.back()
        assertEquals(GeodeDestination.HOME, state.current)
    }

    @Test
    fun backFromHomeIsLeftToTheSystem() {
        val state = GeodeAppState()
        state.back()
        assertEquals(listOf(GeodeDestination.HOME), state.backStack.toList())
    }

    @Test
    fun reselectingATabDoesNotStackADuplicate() {
        val state = GeodeAppState()
        state.navigateTo(GeodeDestination.VISUALS)
        state.navigateTo(GeodeDestination.VISUALS)
        assertEquals(listOf(GeodeDestination.HOME, GeodeDestination.VISUALS), state.backStack.toList())
        state.navigateTo(GeodeDestination.HOME)
        state.navigateTo(GeodeDestination.HOME)
        assertEquals(listOf(GeodeDestination.HOME), state.backStack.toList())
    }

    @Test
    fun switchingTabsReplacesTheOneAboveHome() {
        val state = GeodeAppState()
        state.navigateTo(GeodeDestination.PLAYER)
        assertTrue(state.onPlayer)
        state.navigateTo(GeodeDestination.STUDIO)
        assertEquals(listOf(GeodeDestination.HOME, GeodeDestination.STUDIO), state.backStack.toList())
        assertFalse(state.onPlayer)
    }

    @Test
    fun restoresTheSavedPlaceByName() {
        val routes: List<GeodeRoute> = GeodeDestination.entries + SettingsRoute
        routes.forEach { route ->
            val saved = GeodeAppState(route, expanded = true, searching = true).saved()
            val restored = GeodeAppState.Saver.restore(saved)!!
            assertEquals(route, restored.current)
            assertTrue(restored.expanded)
            assertTrue(restored.searching)
        }
        assertEquals("SETTINGS", GeodeAppState(SettingsRoute).saved()[0])
        assertEquals("LIBRARY", GeodeAppState(GeodeDestination.LIBRARY).saved()[0])
    }

    @Test
    fun anUnknownSavedPlaceFallsBackToHome() {
        val restored = GeodeAppState.Saver.restore(listOf("GONE", false, false))!!
        assertEquals(listOf(GeodeDestination.HOME), restored.backStack.toList())
        assertEquals(GeodeDestination.HOME, GeodeAppState.routeNamed(null))
    }

    private fun GeodeAppState.saved(): List<Any> {
        val state = this
        return with(GeodeAppState.Saver) { SaverScope { true }.save(state)!! }
    }
}
