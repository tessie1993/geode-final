package dev.geode.appui

import dev.geode.appui.AppUiCommand.Back
import dev.geode.appui.AppUiCommand.CloseSearch
import dev.geode.appui.AppUiCommand.CloseSheet
import dev.geode.appui.AppUiCommand.EnterFullScreen
import dev.geode.appui.AppUiCommand.LeaveFullScreen
import dev.geode.appui.AppUiCommand.Navigate
import dev.geode.appui.AppUiCommand.OpenSearch
import dev.geode.appui.AppUiCommand.OpenSettings
import dev.geode.appui.AppUiCommand.OpenSheet
import dev.geode.appui.AppUiCommand.ShowVisualsTab
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
import dev.geode.kernel.Next
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

    @Test
    fun `navigation follows the table`() = check(navigation)

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
