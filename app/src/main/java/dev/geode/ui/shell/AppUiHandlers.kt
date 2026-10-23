package dev.geode.ui.shell

import dev.geode.kernel.EffectRunner
import dev.geode.kernel.ErrorChannel
import dev.geode.kernel.FeatureRuntime
import dev.geode.kernel.FeatureStore
import dev.geode.kernel.Results

/** Carries out the App UI's work: it reports every failure on [errors] to the reducer. */
class AppUiHandlers(
    private val errors: ErrorChannel,
) : EffectRunner<AppUiWork, AppUiEvent> {
    override suspend fun run(
        work: AppUiWork,
        results: Results<AppUiEvent>,
    ) {
        when (work) {
            AppUiWork.WatchFailures -> errors.failures.collect { results.report(AppUiEvent.Failed(it)) }
        }
    }
}

/** The App UI's one app-scoped store, starting from [initial] (a restored state after process death). */
fun appUiStore(
    runtime: FeatureRuntime,
    initial: AppUiState = AppUiState(),
): AppUi =
    FeatureStore(
        name = APP_UI,
        initial = initial,
        reducer = AppUiReducer,
        toEvent = AppUiEvent::Command,
        runner = AppUiHandlers(runtime.errors),
        runtime = runtime,
        start = AppUiReducer.start,
    )

/** The App UI's name on the error channel. */
const val APP_UI = "App UI"
