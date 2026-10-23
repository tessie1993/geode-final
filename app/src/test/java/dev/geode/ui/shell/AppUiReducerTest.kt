package dev.geode.ui.shell

import dev.geode.engine.types.Millis
import dev.geode.kernel.CancelTimer
import dev.geode.kernel.Emit
import dev.geode.kernel.FakeClock
import dev.geode.kernel.FeatureFailure
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.StartTimer
import dev.geode.kernel.inPlaceRuntime
import dev.geode.ui.shell.AppUiCommand.Back
import dev.geode.ui.shell.AppUiCommand.CloseSheet
import dev.geode.ui.shell.AppUiCommand.DismissBanner
import dev.geode.ui.shell.AppUiCommand.EnterFullScreen
import dev.geode.ui.shell.AppUiCommand.ExitFullScreen
import dev.geode.ui.shell.AppUiCommand.Open
import dev.geode.ui.shell.AppUiCommand.OpenSheet
import dev.geode.ui.shell.AppUiEvent.BannerExpired
import dev.geode.ui.shell.AppUiEvent.Command
import dev.geode.ui.shell.AppUiEvent.Failed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The App UI reducer as transition tables (ADR-0003): where Back goes, what covers what, and the banner. */
class AppUiReducerTest {
    private val home = AppUiState()
    private val music = AppUiState(place = Place.MUSIC)
    private val visualsFull = AppUiState(place = Place.VISUALS, fullScreen = true)
    private val fullWithCustomize = AppUiState(place = Place.VISUALS, fullScreen = true, sheet = Sheet.CUSTOMIZE)
    private val musicWithQueue = AppUiState(place = Place.MUSIC, sheet = Sheet.QUEUE)

    private fun reduce(
        state: AppUiState,
        command: AppUiCommand,
    ) = AppUiReducer.reduce(state, Command(command))

    @Test
    fun `the app starts on Home with nothing over it`() {
        assertEquals(AppUiState(Place.HOME, fullScreen = false, sheet = null, banner = null, shown = 0), AppUiState())
    }

    @Test
    fun `the Bar is Music, Visuals, Home, Studio, Settings`() {
        assertEquals(listOf("MUSIC", "VISUALS", "HOME", "STUDIO", "SETTINGS"), Place.entries.map { it.name })
    }

    @Test
    fun `Back closes the top thing first, then goes Home, then leaves`() {
        val table =
            listOf(
                fullWithCustomize to Next(visualsFull),
                visualsFull to Next(AppUiState(place = Place.VISUALS)),
                musicWithQueue to Next(music),
                music to Next(home),
                AppUiState(place = Place.SETTINGS) to Next(home),
                AppUiState(place = Place.STUDIO) to Next(home),
                AppUiState(place = Place.VISUALS) to Next(home),
                home to Next(home, listOf(Emit(AppUiFact.LeaveApp))),
            )
        table.forEach { (before, after) -> assertEquals("Back from $before", after, reduce(before, Back)) }
    }

    @Test
    fun `opening a page moves there and closes any sheet`() {
        assertEquals(Next(AppUiState(place = Place.STUDIO)), reduce(musicWithQueue, Open(Place.STUDIO)))
        assertEquals(Next(music), reduce(home, Open(Place.MUSIC)))
    }

    @Test
    fun `opening the page on screen changes nothing, not even an open sheet`() {
        assertEquals(Next(musicWithQueue), reduce(musicWithQueue, Open(Place.MUSIC)))
        assertEquals(Next(home), reduce(home, Open(Place.HOME)))
    }

    @Test
    fun `full screen opens over the page and closes any sheet, and leaving it returns to the page`() {
        assertEquals(Next(AppUiState(place = Place.MUSIC, fullScreen = true)), reduce(musicWithQueue, EnterFullScreen))
        assertEquals(Next(AppUiState(place = Place.VISUALS)), reduce(fullWithCustomize, ExitFullScreen))
    }

    @Test
    fun `a sheet opens over full screen, replaces another sheet, and closes`() {
        assertEquals(Next(fullWithCustomize), reduce(visualsFull, OpenSheet(Sheet.CUSTOMIZE)))
        assertEquals(Next(AppUiState(place = Place.MUSIC, sheet = Sheet.LISTENING)), reduce(musicWithQueue, OpenSheet(Sheet.LISTENING)))
        assertEquals(Next(music), reduce(musicWithQueue, CloseSheet))
        assertEquals(Next(music), reduce(music, CloseSheet))
    }

    @Test
    fun `the player bar shows on every page but Home, never in full screen, and only with a song`() {
        val shown = Place.entries.filter { AppUiState(place = it).playerBarShown(loaded = true) }
        assertEquals(listOf(Place.MUSIC, Place.VISUALS, Place.STUDIO, Place.SETTINGS), shown)
        assertFalse(visualsFull.playerBarShown(loaded = true))
        assertFalse(music.playerBarShown(loaded = false))
    }

    @Test
    fun `a failure shows on the banner for 8 seconds, and a newer one replaces it`() {
        val first = AppUiReducer.reduce(music, Failed(FeatureFailure("Library", "scan failed")))
        assertEquals(
            Next(
                music.copy(banner = Banner(1, "Library", "scan failed"), shown = 1),
                listOf(StartTimer("banner", Millis(8_000), BannerExpired(1))),
            ),
            first,
        )
        val second = AppUiReducer.reduce(first.state, Failed(FeatureFailure("Playback", "decode error")))
        assertEquals(
            Next(
                music.copy(banner = Banner(2, "Playback", "decode error"), shown = 2),
                listOf(StartTimer("banner", Millis(8_000), BannerExpired(2))),
            ),
            second,
        )
    }

    @Test
    fun `the banner's timer clears only the showing it was started for`() {
        val showing = music.copy(banner = Banner(2, "Playback", "decode error"), shown = 2)
        assertEquals(Next(music.copy(shown = 2)), AppUiReducer.reduce(showing, BannerExpired(2)))
        assertEquals(Next(showing), AppUiReducer.reduce(showing, BannerExpired(1)))
        assertEquals(Next(music), AppUiReducer.reduce(music, BannerExpired(1)))
    }

    @Test
    fun `dismissing the banner clears it and its timer`() {
        val showing = music.copy(banner = Banner(1, "Library", "scan failed"), shown = 1)
        assertEquals(Next(music.copy(shown = 1), listOf(CancelTimer("banner"))), reduce(showing, DismissBanner))
        assertEquals(Next(music), reduce(music, DismissBanner))
    }

    @Test
    fun `a saved place is restored by name, and an unknown one falls back to Home`() {
        assertEquals(Place.STUDIO, Place.named("STUDIO"))
        assertEquals(Place.HOME, Place.named("PLAYER"))
        assertEquals(Place.HOME, Place.named(null))
    }

    @Test
    fun `the store starts by following the error channel`() {
        assertEquals(listOf(Perform(AppUiWork.WatchFailures)), AppUiReducer.start)
    }

    @Test
    fun `a failure reported to the app's error channel reaches the banner, and goes after 8 seconds`() {
        val clock = FakeClock()
        val runtime = inPlaceRuntime(timers = clock)
        val store = appUiStore(runtime)
        runtime.errors.report(FeatureFailure("Settings", "could not save"))
        assertEquals(Banner(1, "Settings", "could not save"), store.state.value.banner)
        clock.advanceBy(Millis(7_999))
        assertTrue(store.state.value.banner != null)
        clock.advanceBy(Millis(1))
        assertEquals(null, store.state.value.banner)
    }
}
