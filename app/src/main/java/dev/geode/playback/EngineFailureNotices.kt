package dev.geode.playback

import androidx.annotation.StringRes
import dev.geode.R
import dev.geode.engine.bridge.EngineFailure
import dev.geode.engine.bridge.EngineSubsystem
import dev.geode.engine.bridge.FailureRelay
import dev.geode.engine.bridge.NativeFailures
import dev.geode.kernel.ErrorChannel
import dev.geode.kernel.FeatureFailure

/**
 * Puts every failure the native bridge reports on today's playback notice banner. [show] receives the
 * banner text and runs on the failing call's thread, so it hands the text on to the main thread
 * itself. Audio and the Visual keep running. The app-wide [ErrorChannel] hears the same failures
 * through [reportTo], which also writes them to the local log (ADR-0004).
 */
class EngineFailureNotices(
    private val show: (Int) -> Unit,
    failures: FailureRelay = NativeFailures,
) : AutoCloseable {
    private val subscription = failures.listen { failure -> show(messageFor(failure)) }

    override fun close() {
        subscription.close()
    }

    companion object {
        /** The feature every engine failure is filed under on the app-wide error channel. */
        const val FEATURE = "engine"

        /** [failure] as the app-wide error channel files it. */
        fun featureFailure(failure: EngineFailure): FeatureFailure = FeatureFailure(FEATURE, failure.toString())

        /** Reports every failure [failures] hears to [errors] until the result is closed. */
        fun reportTo(
            errors: ErrorChannel,
            failures: FailureRelay = NativeFailures,
        ): AutoCloseable = failures.listen { errors.report(featureFailure(it)) }

        /** The banner text for [failure], by the part of the engine that failed. */
        @StringRes
        fun messageFor(failure: EngineFailure): Int =
            when (failure.subsystem) {
                EngineSubsystem.AUDIO -> R.string.engine_failure_audio
                EngineSubsystem.VISUAL -> R.string.engine_failure_visual
                else -> R.string.engine_failure_other
            }
    }
}
