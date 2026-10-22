package dev.geode.appui

import dev.geode.appui.AppUiCommand.Back
import dev.geode.appui.AppUiCommand.BannerShown
import dev.geode.appui.AppUiCommand.CloseSearch
import dev.geode.appui.AppUiCommand.CloseSheet
import dev.geode.appui.AppUiCommand.DismissBanner
import dev.geode.appui.AppUiCommand.EnterFullScreen
import dev.geode.appui.AppUiCommand.LeaveFullScreen
import dev.geode.appui.AppUiCommand.Navigate
import dev.geode.appui.AppUiCommand.OpenSearch
import dev.geode.appui.AppUiCommand.OpenSettings
import dev.geode.appui.AppUiCommand.OpenSheet
import dev.geode.appui.AppUiCommand.Restore
import dev.geode.appui.AppUiCommand.ShowVisualsTab
import dev.geode.appui.AppUiCommand.Signal
import dev.geode.appui.AppUiEvent.BannerTimedOut
import dev.geode.appui.AppUiEvent.FailureReported
import dev.geode.appui.AppUiState.Browsing
import dev.geode.appui.AppUiState.FullScreen
import dev.geode.appui.CustomizeEntry.FULL_SCREEN
import dev.geode.appui.CustomizeEntry.PLAYER
import dev.geode.appui.CustomizeEntry.RECORDING
import dev.geode.appui.Place.HOME
import dev.geode.appui.Place.LIBRARY
import dev.geode.appui.Place.STUDIO
import dev.geode.appui.Place.VISUALS
import dev.geode.appui.VisualsTab.CUSTOMIZE
import dev.geode.appui.VisualsTab.MODULATORS_AND_AUTOMATION
import dev.geode.appui.VisualsTab.STYLE
import dev.geode.engine.types.Millis
import dev.geode.kernel.CancelTimer
import dev.geode.kernel.Emit
import dev.geode.kernel.Next
import dev.geode.kernel.StartTimer
import org.junit.Assert.assertEquals
import org.junit.Test
import dev.geode.appui.Place.PLAYER as PLAYER_TAB

/**
 * The App UI reducer as a transition table (ADR-0003, #182): every command and event, from the states
 * where it does something and from those where it must not.
 */
class AppUiReducerTest {
    private fun at(
        route: AppRoute,
        tab: VisualsTab = STYLE,
        searching: Boolean = false,
    ) = Navigation(if (route == HOME) listOf(HOME) else listOf(HOME, route), tab, searching)

    private val home = Browsing()
    private val player = Browsing(at(PLAYER_TAB))
    private val library = Browsing(at(LIBRARY))
    private val settings = Browsing(at(AppRoute.Settings))
    private val visuals = Browsing(at(VISUALS))
    private val fromPlayer = AppSheet.Customize(PLAYER)
    private val overFullScreen = AppSheet.Customize(FULL_SCREEN)
    private val duringRecording = AppSheet.Customize(RECORDING)
    private val playerWithSheet = player.copy(sheet = fromPlayer)
    private val searching = Browsing(at(PLAYER_TAB, searching = true))
    private val fullScreen = FullScreen(at(PLAYER_TAB))
    private val fullScreenWithSheet = fullScreen.copy(sheet = overFullScreen)
    private val shownBanner = Banner.OnScreen("Could not read the music")

    private fun row(
        state: AppUiState,
        command: AppUiCommand,
        next: AppUiState,
        effects: List<AppUiEffect> = emptyList(),
    ) = Row(state, AppUiEvent.Command(command), Next(next, effects))

    private val navigation: List<Row> =
        listOf(
            // The bar: Home, then at most one place; the place shown again stacks nothing.
            row(home, Navigate(PLAYER_TAB), player),
            row(home, Navigate(HOME), home),
            row(player, Navigate(LIBRARY), library),
            row(player, Navigate(PLAYER_TAB), player),
            row(player, Navigate(HOME), home),
            row(settings, Navigate(STUDIO), Browsing(at(STUDIO))),
            // Showing a place closes what was over the one before, and keeps the banner.
            row(playerWithSheet, Navigate(PLAYER_TAB), player),
            row(searching, Navigate(LIBRARY), library),
            row(fullScreenWithSheet, Navigate(VISUALS), visuals),
            row(player.copy(banner = shownBanner), Navigate(LIBRARY), library.copy(banner = shownBanner)),
            // Visuals keeps its sub-tab while away.
            row(Browsing(at(HOME, CUSTOMIZE)), Navigate(VISUALS), Browsing(at(VISUALS, CUSTOMIZE))),
            // Settings is above Home, from wherever it is opened.
            row(home, OpenSettings, settings),
            row(player, OpenSettings, settings),
            row(settings, OpenSettings, settings),
            row(fullScreenWithSheet, OpenSettings, settings),
            row(searching, OpenSettings, settings),
            // A Visuals sub-tab, from anywhere.
            row(home, ShowVisualsTab(CUSTOMIZE), Browsing(at(VISUALS, CUSTOMIZE))),
            row(visuals, ShowVisualsTab(MODULATORS_AND_AUTOMATION), Browsing(at(VISUALS, MODULATORS_AND_AUTOMATION))),
            row(visuals, ShowVisualsTab(STYLE), visuals),
            row(fullScreenWithSheet, ShowVisualsTab(STYLE), visuals),
            row(searching, ShowVisualsTab(CUSTOMIZE), Browsing(at(VISUALS, CUSTOMIZE))),
        )

    private val back: List<Row> =
        listOf(
            // At Home there is nothing to go back to: the system's Back leaves the app.
            row(home, Back, home),
            row(player, Back, home),
            row(settings, Back, home),
            row(library.copy(banner = shownBanner), Back, home.copy(banner = shownBanner)),
            // What is on top closes first: the sheet, then full-screen mode, then Search.
            row(playerWithSheet, Back, player),
            row(searching.copy(sheet = fromPlayer), Back, searching),
            row(fullScreenWithSheet, Back, fullScreen),
            row(fullScreen, Back, player),
            row(FullScreen(at(PLAYER_TAB, searching = true)), Back, searching),
            row(searching, Back, player),
            row(Browsing(at(HOME, searching = true)), Back, home),
        )

    private val sheets: List<Row> =
        listOf(
            row(player, OpenSheet(fromPlayer), playerWithSheet),
            row(playerWithSheet, OpenSheet(duringRecording), player.copy(sheet = duringRecording)),
            row(fullScreen, OpenSheet(overFullScreen), fullScreenWithSheet),
            row(playerWithSheet, CloseSheet, player),
            row(fullScreenWithSheet, CloseSheet, fullScreen),
            row(player, CloseSheet, player),
        )

    private val fullScreenMode: List<Row> =
        listOf(
            row(player, EnterFullScreen, fullScreen),
            // A sheet belongs to the screen it was opened over.
            row(playerWithSheet, EnterFullScreen, fullScreen),
            row(player.copy(banner = shownBanner), EnterFullScreen, fullScreen.copy(banner = shownBanner)),
            row(fullScreen, EnterFullScreen, fullScreen),
            row(fullScreenWithSheet, EnterFullScreen, fullScreenWithSheet),
            row(fullScreen, LeaveFullScreen, player),
            row(fullScreenWithSheet, LeaveFullScreen, player),
            row(fullScreen.copy(banner = shownBanner), LeaveFullScreen, player.copy(banner = shownBanner)),
            row(player, LeaveFullScreen, player),
            row(playerWithSheet, LeaveFullScreen, playerWithSheet),
        )

    private val search: List<Row> =
        listOf(
            row(player, OpenSearch, searching),
            row(playerWithSheet, OpenSearch, searching),
            row(searching, OpenSearch, searching),
            // Search is reached from the places, not from over the Visual.
            row(fullScreen, OpenSearch, fullScreen),
            row(searching, CloseSearch, player),
            row(player, CloseSearch, player),
            row(FullScreen(at(PLAYER_TAB, searching = true)), CloseSearch, fullScreen),
        )

    private val unreadable = "Could not read the music"
    private val engine = "The audio engine is restarting"
    private val countdown = listOf(StartTimer(BannerTimer, Millis(8_000), BannerTimedOut))
    private val stopCountdown = listOf(CancelTimer(BannerTimer))

    private fun failure(
        state: AppUiState,
        message: String,
        next: AppUiState,
        effects: List<AppUiEffect> = emptyList(),
    ) = Row(state, FailureReported(message), Next(next, effects))

    private val banner: List<Row> =
        listOf(
            // A failure waits until the banner has drawn it; a newer one replaces it and still waits.
            failure(player, unreadable, player.copy(banner = Banner.Waiting(unreadable))),
            failure(player.copy(banner = Banner.Waiting(unreadable)), engine, player.copy(banner = Banner.Waiting(engine))),
            // A newer one replaces the one on screen, which is on screen at once: its time starts now.
            failure(player.copy(banner = Banner.OnScreen(unreadable)), engine, player.copy(banner = Banner.OnScreen(engine)), countdown),
            failure(fullScreenWithSheet, engine, fullScreenWithSheet.copy(banner = Banner.Waiting(engine))),
            // Drawn: on screen for eight seconds from now.
            row(player.copy(banner = Banner.Waiting(unreadable)), BannerShown, player.copy(banner = Banner.OnScreen(unreadable)), countdown),
            row(
                fullScreen.copy(banner = Banner.Waiting(unreadable)),
                BannerShown,
                fullScreen.copy(banner = Banner.OnScreen(unreadable)),
                countdown,
            ),
            // Saying it again does not start the count again.
            row(player.copy(banner = Banner.OnScreen(unreadable)), BannerShown, player.copy(banner = Banner.OnScreen(unreadable))),
            row(player, BannerShown, player),
            // Dismissed: gone, and its count with it.
            row(player.copy(banner = Banner.OnScreen(unreadable)), DismissBanner, player, stopCountdown),
            row(fullScreen.copy(banner = Banner.OnScreen(unreadable)), DismissBanner, fullScreen, stopCountdown),
            row(player.copy(banner = Banner.Waiting(unreadable)), DismissBanner, player),
            row(player, DismissBanner, player),
            // Timed out: gone. A timer that is no longer the banner's changes nothing.
            Row(player.copy(banner = Banner.OnScreen(unreadable)), BannerTimedOut, Next(player)),
            Row(fullScreen.copy(banner = Banner.OnScreen(unreadable)), BannerTimedOut, Next(fullScreen)),
            Row(player.copy(banner = Banner.Waiting(unreadable)), BannerTimedOut, Next(player.copy(banner = Banner.Waiting(unreadable)))),
            Row(player, BannerTimedOut, Next(player)),
        )

    private val signals: List<Row> =
        listOf(
            // Front-end events go out as plain app signals, whatever is on screen, and change nothing.
            row(home, Signal(AppSignal.Tapped(12f, 34f)), home, listOf(Emit(AppSignal.Tapped(12f, 34f)))),
            row(player, Signal(AppSignal.TrackChanged), player, listOf(Emit(AppSignal.TrackChanged))),
            row(fullScreenWithSheet, Signal(AppSignal.RenderDone), fullScreenWithSheet, listOf(Emit(AppSignal.RenderDone))),
            row(searching, Signal(AppSignal.Favourited), searching, listOf(Emit(AppSignal.Favourited))),
        )

    private val restore: List<Row> =
        listOf(
            // What was open comes back; the banner is the live one, never a saved one.
            row(
                home.copy(banner = shownBanner),
                Restore(SavedAppUi(VISUALS, CUSTOMIZE, fromPlayer, fullScreen = false, searching = true)),
                Browsing(at(VISUALS, CUSTOMIZE, searching = true), fromPlayer, shownBanner),
            ),
            row(
                player,
                Restore(SavedAppUi(AppRoute.Settings, sheet = overFullScreen, fullScreen = true)),
                FullScreen(at(AppRoute.Settings), overFullScreen),
            ),
            row(fullScreenWithSheet, Restore(SavedAppUi()), home),
            row(settings, Restore(SavedAppUi(LIBRARY)), library),
        )

    @Test
    fun `front-end events follow the table`() = check(signals)

    @Test
    fun `restoring follows the table`() = check(restore)

    @Test
    fun `what is saved is what is open, never the banner`() {
        val open =
            FullScreen(at(VISUALS, MODULATORS_AND_AUTOMATION, searching = true), overFullScreen, shownBanner)

        assertEquals(SavedAppUi(VISUALS, MODULATORS_AND_AUTOMATION, overFullScreen, fullScreen = true, searching = true), open.saved())
        assertEquals(SavedAppUi(), home.copy(banner = shownBanner).saved())
        assertEquals(SavedAppUi(AppRoute.Settings), settings.saved())
    }

    @Test
    fun `it is saved by name`() {
        assertEquals(listOf("HOME", "STYLE", "", "false", "false"), SavedAppUi().names())
        assertEquals(
            listOf("SETTINGS", "CUSTOMIZE", "CUSTOMIZE:RECORDING", "true", "false"),
            SavedAppUi(AppRoute.Settings, CUSTOMIZE, duringRecording, fullScreen = true).names(),
        )
        assertEquals(
            listOf("STUDIO", "MODULATORS_AND_AUTOMATION", "CUSTOMIZE:PLAYER", "false", "true"),
            SavedAppUi(STUDIO, MODULATORS_AND_AUTOMATION, fromPlayer, searching = true).names(),
        )
    }

    @Test
    fun `what was saved comes back by name`() {
        val saved =
            listOf(
                SavedAppUi(),
                SavedAppUi(AppRoute.Settings, CUSTOMIZE, duringRecording, fullScreen = true),
                SavedAppUi(STUDIO, MODULATORS_AND_AUTOMATION, fromPlayer, searching = true),
                SavedAppUi(PLAYER_TAB, sheet = overFullScreen, fullScreen = true, searching = true),
            )

        assertEquals(saved, saved.map { SavedAppUi.fromNames(it.names()) })
    }

    @Test
    fun `an unknown or missing saved name falls back to Home with nothing open`() {
        assertEquals(SavedAppUi(), SavedAppUi.fromNames(listOf("GONE", "NOPE", "CUSTOMIZE:NOPE", "yes", "TRUE")))
        assertEquals(SavedAppUi(), SavedAppUi.fromNames(emptyList()))
        assertEquals(SavedAppUi(LIBRARY), SavedAppUi.fromNames(listOf("LIBRARY", "STYLE", "PLAYER")))
        assertEquals(SavedAppUi(VISUALS, CUSTOMIZE), SavedAppUi.fromNames(listOf("VISUALS", "CUSTOMIZE", "")))
    }

    @Test
    fun `navigation follows the table`() = check(navigation)

    @Test
    fun `the banner follows the table`() = check(banner)

    @Test
    fun `the banner stays on screen as long as the old notice banner did`() {
        assertEquals(Millis(8_000), BANNER_VISIBLE)
    }

    @Test
    fun `back follows the table`() = check(back)

    @Test
    fun `sheets follow the table`() = check(sheets)

    @Test
    fun `full-screen visual mode follows the table`() = check(fullScreenMode)

    @Test
    fun `search follows the table`() = check(search)

    @Test
    fun `back has something to do everywhere but at Home with nothing open`() {
        val canGoBack =
            listOf(home, player, settings, playerWithSheet, searching, fullScreen, Browsing(at(HOME, searching = true)))
                .map { it.canGoBack }
        assertEquals(listOf(false, true, true, true, true, true, true), canGoBack)
        assertEquals(true, home.copy(sheet = fromPlayer).canGoBack)
        assertEquals(true, FullScreen().canGoBack)
    }

    @Test
    fun `the bar shows the place on top, and Home under Settings`() {
        assertEquals(listOf(HOME, PLAYER_TAB, HOME, VISUALS), listOf(home, player, settings, visuals).map { it.navigation.tab })
        assertEquals(AppRoute.Settings, settings.navigation.current)
    }

    @Test
    fun `the app starts at Home with nothing open`() {
        assertEquals(Browsing(Navigation(listOf(HOME), STYLE, searching = false), null, Banner.Hidden), AppUiState.Initial)
    }

    @Test
    fun `the bar always has all five places, Home first`() {
        assertEquals(listOf(HOME, PLAYER_TAB, LIBRARY, VISUALS, STUDIO), Place.entries)
    }

    private fun check(table: List<Row>) {
        for (row in table) {
            assertEquals("${row.state} + ${row.event}", row.next, AppUiReducer.reduce(row.state, row.event))
        }
    }

    private class Row(
        val state: AppUiState,
        val event: AppUiEvent,
        val next: Next<AppUiState, AppUiEffect>,
    )
}
