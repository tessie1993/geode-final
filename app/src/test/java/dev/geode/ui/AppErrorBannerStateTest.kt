package dev.geode.ui

import dev.geode.engine.types.Millis
import dev.geode.kernel.FakeClock
import dev.geode.kernel.FeatureFailure
import dev.geode.kernel.inPlaceRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The app-wide error banner's App UI logic (#147, ADR-0004): it hears every feature's failures on the
 * shared error channel and shows one at a time, for eight seconds from when it is on screen.
 */
class AppErrorBannerStateTest {
    private val clock = FakeClock()
    private val runtime = inPlaceRuntime(clock)
    private val library = FeatureFailure("library", "Could not read the music")
    private val engine = FeatureFailure("engine", "The audio engine is restarting")

    private fun waiting(failure: FeatureFailure) = ErrorBannerModel(failure.message, counting = false)

    private fun onScreen(failure: FeatureFailure) = ErrorBannerModel(failure.message, counting = true)

    @Test
    fun `nothing reported shows nothing`() {
        val banner = AppErrorBannerState(runtime)

        assertNull(banner.model.value)
        banner.onAction(ErrorBannerAction.Shown)
        banner.onAction(ErrorBannerAction.Dismiss)
        assertNull(banner.model.value)
        assertEquals(0, clock.pendingCount)
    }

    @Test
    fun `a failure any feature reports waits for the banner, shows, then goes`() {
        val banner = AppErrorBannerState(runtime)

        runtime.errors.report(library)
        assertEquals(waiting(library), banner.model.value)
        assertEquals(0, clock.pendingCount)

        banner.onAction(ErrorBannerAction.Shown)
        assertEquals(onScreen(library), banner.model.value)

        clock.advanceBy(ERROR_BANNER_VISIBLE - Millis(1))
        assertEquals(onScreen(library), banner.model.value)
        clock.advanceBy(Millis(1))
        assertNull(banner.model.value)
    }

    @Test
    fun `a failure that waits is not timed until it is on screen`() {
        val banner = AppErrorBannerState(runtime)
        runtime.errors.report(library)

        clock.advanceBy(Millis(60_000))
        assertEquals(waiting(library), banner.model.value)

        banner.onAction(ErrorBannerAction.Shown)
        clock.advanceBy(ERROR_BANNER_VISIBLE - Millis(1))
        assertEquals(onScreen(library), banner.model.value)
    }

    @Test
    fun `a newer failure replaces the one on screen and is timed from when it came`() {
        val banner = AppErrorBannerState(runtime)
        runtime.errors.report(library)
        banner.onAction(ErrorBannerAction.Shown)

        clock.advanceBy(Millis(5_000))
        runtime.errors.report(engine)
        assertEquals(onScreen(engine), banner.model.value)
        assertEquals(1, clock.pendingCount)

        clock.advanceBy(ERROR_BANNER_VISIBLE - Millis(1))
        assertEquals(onScreen(engine), banner.model.value)
        clock.advanceBy(Millis(1))
        assertNull(banner.model.value)
    }

    @Test
    fun `a newer failure replaces one still waiting, and still waits`() {
        val banner = AppErrorBannerState(runtime)

        runtime.errors.report(library)
        runtime.errors.report(engine)

        assertEquals(waiting(engine), banner.model.value)
        assertEquals(0, clock.pendingCount)
    }

    @Test
    fun `saying it is on screen again does not start the count again`() {
        val banner = AppErrorBannerState(runtime)
        runtime.errors.report(library)
        banner.onAction(ErrorBannerAction.Shown)

        clock.advanceBy(Millis(5_000))
        banner.onAction(ErrorBannerAction.Shown)
        assertEquals(1, clock.pendingCount)

        clock.advanceBy(ERROR_BANNER_VISIBLE - Millis(5_000))
        assertNull(banner.model.value)
    }

    @Test
    fun `dismissing hides it and stops its count`() {
        val banner = AppErrorBannerState(runtime)
        runtime.errors.report(library)
        banner.onAction(ErrorBannerAction.Shown)

        banner.onAction(ErrorBannerAction.Dismiss)

        assertNull(banner.model.value)
        assertEquals(0, clock.pendingCount)
    }

    @Test
    fun `a failure that waits can be dismissed before it is seen`() {
        val banner = AppErrorBannerState(runtime)
        runtime.errors.report(library)

        banner.onAction(ErrorBannerAction.Dismiss)

        assertNull(banner.model.value)
    }

    @Test
    fun `after one goes, the next failure waits to be seen again`() {
        val banner = AppErrorBannerState(runtime)
        runtime.errors.report(library)
        banner.onAction(ErrorBannerAction.Shown)
        clock.advanceBy(ERROR_BANNER_VISIBLE)

        runtime.errors.report(engine)

        assertEquals(waiting(engine), banner.model.value)
    }

    @Test
    fun `the latest failure reported before the banner was there is shown once it is`() {
        runtime.errors.report(library)
        runtime.errors.report(engine)

        val banner = AppErrorBannerState(runtime)

        assertEquals(waiting(engine), banner.model.value)
    }

    @Test
    fun `the banner stays on screen as long as the old notice banner did`() {
        assertEquals(Millis(8_000), ERROR_BANNER_VISIBLE)
    }
}
