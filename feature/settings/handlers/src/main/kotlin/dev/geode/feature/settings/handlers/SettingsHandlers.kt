package dev.geode.feature.settings.handlers

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.settings.api.SettingsCommand
import dev.geode.feature.settings.api.SettingsState
import dev.geode.feature.settings.api.SettingsValues
import dev.geode.feature.settings.core.SettingsEvent
import dev.geode.feature.settings.core.SettingsReducer
import dev.geode.feature.settings.core.SettingsWork
import dev.geode.kernel.EffectRunner
import dev.geode.kernel.FeatureRuntime
import dev.geode.kernel.FeatureStore
import dev.geode.kernel.Results
import kotlinx.coroutines.flow.first
import java.io.IOException

/** The name Settings reports its failures under. */
const val SETTINGS = "settings"

/**
 * Carries out Settings' work against its DataStore. A read or write that fails is reported back as
 * an event, so the reducer decides what the person sees.
 */
class SettingsHandlers(
    private val store: DataStore<Preferences>,
) : EffectRunner<SettingsWork, SettingsEvent> {
    override suspend fun run(
        work: SettingsWork,
        results: Results<SettingsEvent>,
    ) {
        when (work) {
            SettingsWork.Load -> results.report(load())
            is SettingsWork.Save -> save(work.values)?.let(results::report)
        }
    }

    private suspend fun load(): SettingsEvent =
        try {
            SettingsEvent.Loaded(SettingsCodec.decode(store.data.first().byName()))
        } catch (failure: IOException) {
            SettingsEvent.LoadFailed(reasonOf(failure))
        }

    private suspend fun save(values: SettingsValues): SettingsEvent? =
        try {
            store.edit { it.write(SettingsCodec.encode(values)) }
            null
        } catch (failure: IOException) {
            SettingsEvent.SaveFailed(reasonOf(failure))
        }

    private fun reasonOf(failure: IOException): String = failure.message ?: failure.javaClass.simpleName
}

/** How `:app` builds Settings' one store; reading the stored settings is its start effect. */
fun settingsStore(
    store: DataStore<Preferences>,
    runtime: FeatureRuntime,
): Settings =
    FeatureStore<SettingsState, SettingsCommand, SettingsEvent, SettingsWork, Nothing>(
        name = SETTINGS,
        initial = SettingsState.Loading(),
        reducer = SettingsReducer,
        toEvent = SettingsEvent::Command,
        runner = SettingsHandlers(store),
        runtime = runtime,
        start = SettingsReducer.START,
    )
