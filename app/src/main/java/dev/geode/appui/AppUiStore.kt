package dev.geode.appui

import dev.geode.kernel.EffectRunner
import dev.geode.kernel.Feature
import dev.geode.kernel.FeatureFailure
import dev.geode.kernel.FeatureRuntime
import dev.geode.kernel.FeatureStore
import dev.geode.kernel.Results
import kotlinx.coroutines.flow.Flow

/**
 * The App UI's state as everyone else sees it (#182): a read-only [Feature.state], [Feature.send] for
 * [AppUiCommand]s, and the plain [AppSignal]s on [Feature.events] for the Elements (#180 decision 5).
 */
typealias AppUi = Feature<AppUiState, AppUiCommand, AppSignal>

/** Carries out the App UI's one piece of work: hearing [failures], the shared error channel. */
class AppUiHandlers(
    private val failures: Flow<FeatureFailure>,
) : EffectRunner<AppUiWork, AppUiEvent> {
    override suspend fun run(
        work: AppUiWork,
        results: Results<AppUiEvent>,
    ) {
        when (work) {
            AppUiWork.CollectFailures -> failures.collect { results.report(AppUiEvent.FailureReported(it.message)) }
        }
    }
}

/**
 * The App UI's one app-scoped store, over the app's [runtime]: the reducer on its main thread, the
 * banner's countdown on its timers, and the failures from its error channel, heard from the moment the
 * store is built for as long as it lives.
 */
fun appUiStore(runtime: FeatureRuntime): AppUi =
    FeatureStore<AppUiState, AppUiCommand, AppUiEvent, AppUiWork, AppSignal>(
        name = APP_UI,
        initial = AppUiState.Initial,
        reducer = AppUiReducer,
        toEvent = AppUiEvent::Command,
        runner = AppUiHandlers(runtime.errors.failures),
        runtime = runtime,
        start = AppUiReducer.start,
    )

/** The App UI's name on the failures its store reports. */
const val APP_UI = "app-ui"
