package dev.geode.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Test

/** The App UI comes back after process death as it was, banner aside (owner: restore everything). */
class AppUiSavingTest {
    private fun restored(state: AppUiState): AppUiState =
        AppUiSaving.restore(AppUiSaving.save(state)).fold(AppUiState()) { now, command ->
            AppUiReducer.reduce(now, AppUiEvent.Command(command)).state
        }

    @Test
    fun `every place, full screen and sheet comes back`() {
        val states =
            Place.entries.flatMap { place ->
                listOf(false, true).flatMap { full ->
                    (Sheet.entries + null).map { sheet -> AppUiState(place = place, fullScreen = full, sheet = sheet) }
                }
            }

        states.forEach { assertEquals("restoring $it", it, restored(it)) }
    }

    @Test
    fun `the banner is not saved`() {
        val showing = AppUiState(place = Place.MUSIC, banner = Banner(3, "Library", "scan failed"), shown = 3)

        assertEquals(AppUiState(place = Place.MUSIC), restored(showing))
    }

    @Test
    fun `nothing saved, or a place since removed, starts on Home`() {
        assertEquals(emptyList<AppUiCommand>(), AppUiSaving.restore(null))
        assertEquals(AppUiState(), restored(AppUiState()))
        assertEquals(emptyList<AppUiCommand>(), AppUiSaving.restore("PLAYER,false,"))
    }
}
