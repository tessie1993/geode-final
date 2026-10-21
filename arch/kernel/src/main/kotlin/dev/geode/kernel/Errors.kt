package dev.geode.kernel

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onSubscription

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
 * Each report goes to [log] first, so every failure is in the local log. It is then published on
 * [failures] to whoever collects, with up to [EVENT_BUFFER] reports waiting for a slow collector.
 * When nobody collects, the channel keeps only the latest failure and hands it, once, to the next
 * collector, so a banner that appears later shows what it missed; older missed failures are only in
 * the log.
 */
class ErrorChannel(
    private val log: (FeatureFailure) -> Unit,
) {
    private val published =
        MutableSharedFlow<FeatureFailure>(extraBufferCapacity = EVENT_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val lock = Any()

    // Guarded by lock: the latest failure reported while nobody collected.
    private var missed: FeatureFailure? = null

    val failures: Flow<FeatureFailure> = published.onSubscription { takeMissed()?.let { emit(it) } }

    fun report(failure: FeatureFailure) {
        log(failure)
        synchronized(lock) {
            if (published.subscriptionCount.value == 0) {
                missed = failure
                return
            }
        }
        published.tryEmit(failure)
    }

    private fun takeMissed(): FeatureFailure? = synchronized(lock) { missed.also { missed = null } }
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
