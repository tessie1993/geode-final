package dev.geode.appui

import dev.geode.engine.types.Millis
import dev.geode.kernel.FakeClock
import dev.geode.kernel.FeatureFailure
import dev.geode.kernel.Perform
import dev.geode.kernel.inPlaceRuntime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The App UI's one store over the feature runtime (#182): it hears the shared error channel for as long
 * as it lives, counts the banner's time on the runtime's clock, and sends front-end events out.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppUiStoreTest {
    private val clock = FakeClock()
    private val runtime = inPlaceRuntime(clock)
    private val unreadable = FeatureFailure("library", "Could not read the music")
    private val engine = FeatureFailure("engine", "The audio engine is restarting")

    private fun banner(store: AppUi) = store.state.value.banner

    @Test
    fun `it starts at Home with nothing open, hearing the error channel`() {
        val store = appUiStore(runtime)

        assertEquals(AppUiState.Initial, store.state.value)
        assertEquals(listOf(Perform(AppUiWork.CollectFailures)), AppUiReducer.start)
        assertEquals(0, clock.pendingCount)
    }

    @Test
    fun `a failure any feature reports waits for the banner, shows for eight seconds, then goes`() {
        val store = appUiStore(runtime)

        runtime.errors.report(unreadable)
        assertEquals(Banner.Waiting(unreadable.message), banner(store))
        assertEquals(0, clock.pendingCount)

        store.send(AppUiCommand.BannerShown)
        assertEquals(Banner.OnScreen(unreadable.message), banner(store))

        clock.advanceBy(BANNER_VISIBLE - Millis(1))
        assertEquals(Banner.OnScreen(unreadable.message), banner(store))
        clock.advanceBy(Millis(1))
        assertEquals(Banner.Hidden, banner(store))
    }

    @Test
    fun `a failure that waits is not timed until it is on screen`() {
        val store = appUiStore(runtime)
        runtime.errors.report(unreadable)

        clock.advanceBy(Millis(60_000))
        assertEquals(Banner.Waiting(unreadable.message), banner(store))

        store.send(AppUiCommand.BannerShown)
        clock.advanceBy(BANNER_VISIBLE - Millis(1))
        assertEquals(Banner.OnScreen(unreadable.message), banner(store))
    }

    @Test
    fun `a newer failure replaces the one on screen and is timed from when it came`() {
        val store = appUiStore(runtime)
        runtime.errors.report(unreadable)
        store.send(AppUiCommand.BannerShown)

        clock.advanceBy(Millis(5_000))
        runtime.errors.report(engine)
        assertEquals(Banner.OnScreen(engine.message), banner(store))
        assertEquals(1, clock.pendingCount)

        clock.advanceBy(BANNER_VISIBLE - Millis(1))
        assertEquals(Banner.OnScreen(engine.message), banner(store))
        clock.advanceBy(Millis(1))
        assertEquals(Banner.Hidden, banner(store))
    }

    @Test
    fun `dismissing hides it and stops its count`() {
        val store = appUiStore(runtime)
        runtime.errors.report(unreadable)
        store.send(AppUiCommand.BannerShown)

        store.send(AppUiCommand.DismissBanner)

        assertEquals(Banner.Hidden, banner(store))
        assertEquals(0, clock.pendingCount)
    }

    @Test
    fun `the latest failure reported before the store was built is shown once it is`() {
        runtime.errors.report(unreadable)
        runtime.errors.report(engine)

        val store = appUiStore(runtime)

        assertEquals(Banner.Waiting(engine.message), banner(store))
    }

    @Test
    fun `the banner stays over navigation and full-screen mode`() {
        val store = appUiStore(runtime)
        runtime.errors.report(unreadable)
        store.send(AppUiCommand.BannerShown)

        store.send(AppUiCommand.Navigate(Place.VISUALS))
        store.send(AppUiCommand.EnterFullScreen)
        store.send(AppUiCommand.OpenSheet(AppSheet.Customize(CustomizeEntry.FULL_SCREEN)))

        assertEquals(
            AppUiState.FullScreen(
                Navigation(listOf(Place.HOME, Place.VISUALS)),
                AppSheet.Customize(CustomizeEntry.FULL_SCREEN),
                Banner.OnScreen(unreadable.message),
            ),
            store.state.value,
        )
        clock.advanceBy(BANNER_VISIBLE)
        assertEquals(Banner.Hidden, banner(store))
    }

    @Test
    fun `back walks down to Home, and no further`() {
        val store = appUiStore(runtime)
        store.send(AppUiCommand.Navigate(Place.PLAYER))
        store.send(AppUiCommand.EnterFullScreen)

        store.send(AppUiCommand.Back)
        assertEquals(AppUiState.Browsing(Navigation(listOf(Place.HOME, Place.PLAYER))), store.state.value)
        store.send(AppUiCommand.Back)
        store.send(AppUiCommand.Back)

        assertEquals(AppUiState.Initial, store.state.value)
        assertEquals(false, store.state.value.canGoBack)
    }

    @Test
    fun `what was open before Android killed the app comes back`() {
        val store = appUiStore(runtime)

        store.send(AppUiCommand.Restore(SavedAppUi.fromNames(listOf("VISUALS", "CUSTOMIZE", "", "false", "false"))))

        assertEquals(AppUiState.Browsing(Navigation(listOf(Place.HOME, Place.VISUALS), VisualsTab.CUSTOMIZE)), store.state.value)
    }

    @Test
    fun `front-end events go out as app signals`() =
        runTest {
            val store = appUiStore(runtime)
            val signals = mutableListOf<AppSignal>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.events.collect { signals += it } }

            store.send(AppUiCommand.Signal(AppSignal.TrackChanged))
            store.send(AppUiCommand.Signal(AppSignal.Tapped(1f, 2f)))

            assertEquals(listOf(AppSignal.TrackChanged, AppSignal.Tapped(1f, 2f)), signals)
        }
}
