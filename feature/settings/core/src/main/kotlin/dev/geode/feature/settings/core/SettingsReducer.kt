package dev.geode.feature.settings.core

import dev.geode.engine.types.Millis
import dev.geode.feature.settings.api.SettingsCommand
import dev.geode.feature.settings.api.SettingsState
import dev.geode.feature.settings.api.SettingsValues
import dev.geode.kernel.Effect
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.Reducer
import dev.geode.kernel.ReportFailure
import dev.geode.kernel.StartTimer

/** Everything Settings' reducer handles: the commands, what the handlers report, and its one timer. */
sealed interface SettingsEvent {
    data class Command(
        val command: SettingsCommand,
    ) : SettingsEvent

    /** The stored settings, read (and on the first run migrated from the old preferences files). */
    data class Loaded(
        val values: SettingsValues,
    ) : SettingsEvent

    data class LoadFailed(
        val reason: String,
    ) : SettingsEvent

    /** The settings have stopped changing for [SAVE_DELAY]: time to write them. */
    data object SaveDue : SettingsEvent

    data class SaveFailed(
        val reason: String,
    ) : SettingsEvent
}

/** The handlers' work. */
sealed interface SettingsWork {
    /** Read the stored settings once, when the store starts. */
    data object Load : SettingsWork

    /** Write every setting. */
    data class Save(
        val values: SettingsValues,
    ) : SettingsWork
}

typealias SettingsEffect = Effect<SettingsWork, SettingsEvent, Nothing>

/** The one timer: a burst of changes, such as a slider being dragged, is written once it settles. */
object SaveTimer

/** How long the settings must stay unchanged before they are written. */
val SAVE_DELAY = Millis(250)

/** What a failure to read the stored settings reports, with the reason after it. */
const val LOAD_FAILED = "settings could not be read, using the defaults: "

/** What a failure to write the settings reports, with the reason after it. */
const val SAVE_FAILED = "settings could not be saved: "

/**
 * Settings' reducer. While [SettingsState.Loading] it holds on to the commands it is sent; once the
 * stored settings arrive it applies them. A change that moves a value arms [SaveTimer] (again), and
 * when it fires the settings are written. Settings are the single writer of what they store.
 */
object SettingsReducer : Reducer<SettingsState, SettingsEvent, SettingsEffect> {
    /** What the store does when it starts: read the stored settings. */
    val START: List<SettingsEffect> = listOf(Perform(SettingsWork.Load))

    override fun reduce(
        state: SettingsState,
        event: SettingsEvent,
    ): Next<SettingsState, SettingsEffect> =
        when (state) {
            is SettingsState.Loading -> loading(state, event)
            is SettingsState.Ready -> ready(state, event)
        }

    private fun loading(
        state: SettingsState.Loading,
        event: SettingsEvent,
    ): Next<SettingsState, SettingsEffect> =
        when (event) {
            is SettingsEvent.Command -> Next(SettingsState.Loading(state.pending + event.command))
            is SettingsEvent.Loaded -> arrive(event.values, state.pending, emptyList())
            is SettingsEvent.LoadFailed -> arrive(SettingsValues(), state.pending, listOf(ReportFailure(LOAD_FAILED + event.reason)))
            SettingsEvent.SaveDue, is SettingsEvent.SaveFailed -> Next(state)
        }

    private fun ready(
        state: SettingsState.Ready,
        event: SettingsEvent,
    ): Next<SettingsState, SettingsEffect> =
        when (event) {
            is SettingsEvent.Command -> change(state.values, SettingsRules.apply(state.values, event.command), emptyList())
            // Settings write what they store themselves, so a later read has nothing new to say.
            is SettingsEvent.Loaded, is SettingsEvent.LoadFailed -> Next(state)
            SettingsEvent.SaveDue -> Next(state, listOf(Perform(SettingsWork.Save(state.values))))
            is SettingsEvent.SaveFailed -> Next(state, listOf(ReportFailure(SAVE_FAILED + event.reason)))
        }

    private fun arrive(
        stored: SettingsValues,
        pending: List<SettingsCommand>,
        reports: List<SettingsEffect>,
    ): Next<SettingsState, SettingsEffect> {
        val values = SettingsRules.inRange(stored)
        return change(values, pending.fold(values, SettingsRules::apply), reports)
    }

    private fun change(
        before: SettingsValues,
        after: SettingsValues,
        reports: List<SettingsEffect>,
    ): Next<SettingsState, SettingsEffect> {
        val save = if (after == before) emptyList() else listOf(StartTimer(SaveTimer, SAVE_DELAY, SettingsEvent.SaveDue))
        return Next(SettingsState.Ready(after), reports + save)
    }
}
