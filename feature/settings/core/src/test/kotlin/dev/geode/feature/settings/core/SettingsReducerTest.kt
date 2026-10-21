package dev.geode.feature.settings.core

import dev.geode.engine.types.Millis
import dev.geode.feature.settings.api.LookCommand
import dev.geode.feature.settings.api.LookSettings
import dev.geode.feature.settings.api.OnboardingCommand
import dev.geode.feature.settings.api.OnboardingSettings
import dev.geode.feature.settings.api.SettingsState
import dev.geode.feature.settings.api.SettingsState.Loading
import dev.geode.feature.settings.api.SettingsState.Ready
import dev.geode.feature.settings.api.SettingsValues
import dev.geode.feature.settings.core.SettingsEvent.Command
import dev.geode.feature.settings.core.SettingsEvent.LoadFailed
import dev.geode.feature.settings.core.SettingsEvent.Loaded
import dev.geode.feature.settings.core.SettingsEvent.SaveDue
import dev.geode.feature.settings.core.SettingsEvent.SaveFailed
import dev.geode.feature.settings.core.SettingsWork.Load
import dev.geode.feature.settings.core.SettingsWork.Save
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.ReportFailure
import dev.geode.kernel.StartTimer
import org.junit.Assert.assertEquals
import org.junit.Test

/** Settings' reducer as a transition table: every state against every event (ADR-0003). */
class SettingsReducerTest {
    private val defaults = SettingsValues()
    private val stored = SettingsValues(look = LookSettings(compactPlayer = true))
    private val compact = LookCommand.SetCompactPlayer(true)
    private val dim = LookCommand.SetBackgroundDim(0.4f)
    private val setup = OnboardingCommand.FinishSetup
    private val waiting = Loading(listOf(setup, dim))
    private val ready = Ready(stored)

    private val dimmed = stored.copy(look = stored.look.copy(backgroundDim = 0.4f))
    private val setUp = SettingsValues(onboarding = OnboardingSettings(setupDone = true))
    private val tooBright = SettingsValues(look = LookSettings(barOpacity = 7f))
    private val opaque = stored.copy(look = stored.look.copy(barOpacity = 1f))
    private val saveLater = StartTimer(SaveTimer, Millis(250), SaveDue)
    private val unreadable = ReportFailure("settings could not be read, using the defaults: disk")

    private val table: List<Row> =
        listOf(
            // Loading: commands wait their turn; the stored settings, or the defaults, then take them.
            Row(Loading(), Command(compact), Next(Loading(listOf(compact)))),
            Row(waiting, Command(compact), Next(Loading(listOf(setup, dim, compact)))),
            Row(Loading(), Loaded(stored), Next(Ready(stored))),
            Row(Loading(), Loaded(tooBright), Next(Ready(SettingsValues(look = LookSettings(barOpacity = 1f))))),
            Row(waiting, Loaded(stored), Next(Ready(dimmed.copy(onboarding = OnboardingSettings(setupDone = true))), listOf(saveLater))),
            Row(Loading(listOf(compact)), Loaded(stored), Next(Ready(stored))),
            Row(Loading(), LoadFailed("disk"), Next(Ready(defaults), listOf(unreadable))),
            Row(
                Loading(listOf(setup)),
                LoadFailed("disk"),
                Next(Ready(setUp), listOf(unreadable, saveLater)),
            ),
            Row(Loading(), SaveDue, Next(Loading())),
            Row(waiting, SaveFailed("full"), Next(waiting)),
            // Ready: a change saves once things settle; a command that changes nothing does nothing.
            Row(ready, Command(dim), Next(Ready(dimmed), listOf(saveLater))),
            Row(ready, Command(compact), Next(ready)),
            Row(ready, Command(LookCommand.SetBarOpacity(9f)), Next(Ready(opaque), listOf(saveLater))),
            Row(ready, Loaded(defaults), Next(ready)),
            Row(ready, LoadFailed("disk"), Next(ready)),
            Row(ready, SaveDue, Next(ready, listOf(Perform(Save(stored))))),
            Row(ready, SaveFailed("full"), Next(ready, listOf(ReportFailure("settings could not be saved: full")))),
        )

    @Test
    fun `every state handles every event as the table says`() {
        for (row in table) {
            assertEquals("${row.state} + ${row.event}", row.next, SettingsReducer.reduce(row.state, row.event))
        }
    }

    @Test
    fun `the store starts by reading the stored settings`() {
        assertEquals(listOf(Perform(Load)), SettingsReducer.START)
    }

    @Test
    fun `failures name what went wrong`() {
        assertEquals("settings could not be read, using the defaults: ", LOAD_FAILED)
        assertEquals("settings could not be saved: ", SAVE_FAILED)
    }

    private class Row(
        val state: SettingsState,
        val event: SettingsEvent,
        val next: Next<SettingsState, SettingsEffect>,
    )
}
