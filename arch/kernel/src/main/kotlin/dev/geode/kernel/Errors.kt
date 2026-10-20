package dev.geode.kernel

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** One failure, reported by the feature named [feature]. Nothing about it leaves the device. */
data class FeatureFailure(
    val feature: String,
    val message: String,
    val cause: Throwable? = null,
)

/**
 * The one app-wide channel every feature reports its failures to, which one App UI banner shows
 * (ADR-0004). Reporting only records and announces: the audio and the Visual keep running.
 *
 * Each report goes to [log] first, so it is in the local log whether or not anyone is listening, and
 * is then published on [failures]. [failures] is not replayed: the banner sees what is reported while
 * it collects, with up to [EVENT_BUFFER] reports waiting for it.
 */
class ErrorChannel(
    private val log: (FeatureFailure) -> Unit,
) {
    private val published =
        MutableSharedFlow<FeatureFailure>(extraBufferCapacity = EVENT_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val failures: Flow<FeatureFailure> = published.asSharedFlow()

    fun report(failure: FeatureFailure) {
        log(failure)
        published.tryEmit(failure)
    }
}

/**
 * What every feature store shares (ADR-0005): reducers run on [main], handlers on [background], timers
 * through [timers], and failures go to [errors]. [scope] owns the handlers' work: when work throws, the
 * failure is reported to [errors] under the [CoroutineName] it ran with, and nothing else is cancelled.
 */
class FeatureRuntime(
    val main: CoroutineDispatcher,
    val background: CoroutineDispatcher,
    val timers: TimerScheduler,
    val errors: ErrorChannel,
) {
    val scope: CoroutineScope =
        CoroutineScope(
            SupervisorJob() +
                CoroutineExceptionHandler { failed, failure ->
                    errors.report(FeatureFailure(failed[CoroutineName]?.name ?: UNNAMED, failure.toString(), failure))
                },
        )
}

/** The feature named on a failure from work that ran under no [CoroutineName]. */
const val UNNAMED = "unnamed"
